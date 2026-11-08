-- Unicast update 1: listeners can ask to speak during a live show, and episodes get descriptions.
-- Paste into Supabase > SQL Editor > New query, then tap Run. Safe to run more than once.
-- Run live_token.sql first (it holds your LiveKit details). This file does not touch them.

create extension if not exists pg_net;

-- ---------- episode descriptions ----------
alter table public.episodes add column if not exists description text;

-- ---------- livekit_token now also returns the caller's identity ----------
create or replace function public.livekit_token(p_role text)
returns json
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  cfg public.live_config;
  now_s bigint := extract(epoch from now())::bigint;
  can_pub boolean;
  who text;
  ttl integer;
  head text;
  body text;
  sig text;
begin
  select * into cfg from public.live_config where id = 1;
  if cfg.api_secret is null then
    raise exception 'Live streaming is not set up yet.';
  end if;

  if p_role = 'broadcaster' then
    if coalesce(auth.role(), 'anon') <> 'authenticated' then
      raise exception 'Only the admin can broadcast.';
    end if;
    can_pub := true;
    who := 'broadcaster';
    ttl := 21600;
  else
    can_pub := false;
    who := 'listener-' || gen_random_uuid()::text;
    ttl := 14400;
  end if;

  head := lk_b64url(convert_to('{"alg":"HS256","typ":"JWT"}', 'UTF8'));
  body := lk_b64url(convert_to(json_build_object(
    'iss', cfg.api_key,
    'sub', who,
    'nbf', now_s,
    'exp', now_s + ttl,
    'video', json_build_object(
      'room', 'unicast-live',
      'roomJoin', true,
      'canPublish', can_pub,
      'canPublishData', false,
      'canSubscribe', true
    )
  )::text, 'UTF8'));
  sig := lk_b64url(hmac(
    convert_to(head || '.' || body, 'UTF8'),
    convert_to(cfg.api_secret, 'UTF8'),
    'sha256'
  ));

  return json_build_object(
    'token', head || '.' || body || '.' || sig,
    'url', cfg.url,
    'identity', who
  );
end;
$$;

grant execute on function public.livekit_token(text) to anon, authenticated;

-- ---------- server-side helpers (only the database itself can run these) ----------

create or replace function public.lk_admin_jwt()
returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  cfg public.live_config;
  now_s bigint := extract(epoch from now())::bigint;
  head text;
  body text;
  sig text;
begin
  select * into cfg from public.live_config where id = 1;
  head := lk_b64url(convert_to('{"alg":"HS256","typ":"JWT"}', 'UTF8'));
  body := lk_b64url(convert_to(json_build_object(
    'iss', cfg.api_key,
    'sub', 'unicast-server',
    'nbf', now_s,
    'exp', now_s + 600,
    'video', json_build_object('room', 'unicast-live', 'roomAdmin', true)
  )::text, 'UTF8'));
  sig := lk_b64url(hmac(
    convert_to(head || '.' || body, 'UTF8'),
    convert_to(cfg.api_secret, 'UTF8'),
    'sha256'
  ));
  return head || '.' || body || '.' || sig;
end;
$$;

revoke all on function public.lk_admin_jwt() from public, anon, authenticated;

-- Tells LiveKit to allow or stop a listener's microphone, without them reconnecting.
create or replace function public.lk_set_publish(p_identity text, p_can_publish boolean)
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  cfg public.live_config;
begin
  select * into cfg from public.live_config where id = 1;
  perform net.http_post(
    url := replace(cfg.url, 'wss://', 'https://') || '/twirp/livekit.RoomService/UpdateParticipant',
    body := jsonb_build_object(
      'room', 'unicast-live',
      'identity', p_identity,
      'permission', jsonb_build_object(
        'can_subscribe', true,
        'can_publish', p_can_publish,
        'can_publish_data', false,
        'can_publish_sources', case when p_can_publish
          then jsonb_build_array('MICROPHONE') else '[]'::jsonb end
      )
    ),
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || public.lk_admin_jwt()
    )
  );
end;
$$;

revoke all on function public.lk_set_publish(text, boolean) from public, anon, authenticated;

-- ---------- speak requests ----------

create table if not exists public.speak_requests (
  id uuid primary key default gen_random_uuid(),
  identity text not null,
  name text not null,
  status text not null default 'pending',   -- pending, approved, ended, declined
  created_at timestamptz not null default now()
);

alter table public.speak_requests enable row level security;
revoke all on public.speak_requests from anon, authenticated;

-- A listener asks to speak. Returns the request id.
create or replace function public.raise_hand(p_identity text, p_name text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  new_id uuid;
  live boolean;
  waiting integer;
begin
  select is_live into live from public.live_status where id = 1;
  if not coalesce(live, false) then
    raise exception 'There is no live show right now.';
  end if;
  if length(coalesce(p_identity, '')) < 5 then
    raise exception 'You are not connected to the live show.';
  end if;
  select count(*) into waiting from public.speak_requests
    where status = 'pending' and created_at > now() - interval '6 hours';
  if waiting >= 30 then
    raise exception 'Too many requests right now. Please try again soon.';
  end if;

  update public.speak_requests set status = 'ended'
    where identity = p_identity and status = 'pending';

  insert into public.speak_requests (identity, name)
  values (p_identity, left(trim(coalesce(nullif(p_name, ''), 'Listener')), 40))
  returning id into new_id;

  return new_id;
end;
$$;

create or replace function public.my_speak_status(p_id uuid)
returns text
language sql
security definer
set search_path = public
as $$
  select status from public.speak_requests where id = p_id;
$$;

-- A listener finishes or cancels their own turn.
create or replace function public.finish_speaking(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  r public.speak_requests;
begin
  select * into r from public.speak_requests where id = p_id;
  if r.id is null then
    return;
  end if;
  if r.status in ('pending', 'approved') then
    update public.speak_requests set status = 'ended' where id = p_id;
    if r.status = 'approved' then
      perform public.lk_set_publish(r.identity, false);
    end if;
  end if;
end;
$$;

grant execute on function public.raise_hand(text, text) to anon, authenticated;
grant execute on function public.my_speak_status(uuid) to anon, authenticated;
grant execute on function public.finish_speaking(uuid) to anon, authenticated;

-- ---------- admin only ----------

create or replace function public.list_speak_requests()
returns table (id uuid, identity text, name text, status text)
language plpgsql
security definer
set search_path = public
as $$
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  return query
    select r.id, r.identity, r.name, r.status
    from public.speak_requests r
    where r.status in ('pending', 'approved')
      and r.created_at > now() - interval '6 hours'
    order by r.created_at;
end;
$$;

create or replace function public.approve_speaker(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  r public.speak_requests;
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  select * into r from public.speak_requests where id = p_id;
  if r.id is null or r.status <> 'pending' then
    return;
  end if;
  update public.speak_requests set status = 'approved' where id = p_id;
  perform public.lk_set_publish(r.identity, true);
end;
$$;

create or replace function public.end_speaker(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  r public.speak_requests;
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  select * into r from public.speak_requests where id = p_id;
  if r.id is null then
    return;
  end if;
  update public.speak_requests set status = 'ended' where id = p_id;
  perform public.lk_set_publish(r.identity, false);
end;
$$;

create or replace function public.decline_speaker(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  update public.speak_requests set status = 'declined' where id = p_id and status = 'pending';
end;
$$;

-- Called when the live show ends.
create or replace function public.clear_speak_requests()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  update public.speak_requests set status = 'ended' where status in ('pending', 'approved');
end;
$$;

revoke all on function public.list_speak_requests() from public, anon;
revoke all on function public.approve_speaker(uuid) from public, anon;
revoke all on function public.end_speaker(uuid) from public, anon;
revoke all on function public.decline_speaker(uuid) from public, anon;
revoke all on function public.clear_speak_requests() from public, anon;

grant execute on function public.list_speak_requests() to authenticated;
grant execute on function public.approve_speaker(uuid) to authenticated;
grant execute on function public.end_speaker(uuid) to authenticated;
grant execute on function public.decline_speaker(uuid) to authenticated;
grant execute on function public.clear_speak_requests() to authenticated;
