-- Unicast: LiveKit token maker, done inside the database (no Edge Function needed).
-- Paste into Supabase > SQL Editor > New query, replace the three PASTE_... values
-- with your own LiveKit URL, API key and API secret, then tap Run.
-- Never commit your real secret to GitHub. To change the secret later, run this file again.

create extension if not exists pgcrypto with schema extensions;

-- Where the LiveKit details are kept. Nobody can read this table through the API.
create table if not exists public.live_config (
  id integer primary key default 1 check (id = 1),
  url text not null,
  api_key text not null,
  api_secret text not null
);
alter table public.live_config enable row level security;
revoke all on public.live_config from anon, authenticated;

insert into public.live_config (id, url, api_key, api_secret)
values (1, 'PASTE_LIVEKIT_URL', 'PASTE_LIVEKIT_API_KEY', 'PASTE_LIVEKIT_API_SECRET')
on conflict (id) do update
  set url = excluded.url, api_key = excluded.api_key, api_secret = excluded.api_secret;

create or replace function public.lk_b64url(data bytea)
returns text
language sql
immutable
as $$
  select translate(encode(data, 'base64'), '+/=' || chr(10), '-_');
$$;

-- p_role = 'broadcaster': only a signed-in user (you) gets a token that can speak.
-- p_role = 'listener':    anyone gets a listen-only token.
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

  return json_build_object('token', head || '.' || body || '.' || sig, 'url', cfg.url);
end;
$$;

grant execute on function public.livekit_token(text) to anon, authenticated;
