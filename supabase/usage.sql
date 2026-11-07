-- Unicast: keep a running estimate of LiveKit minutes used each month.
-- Paste into Supabase > SQL Editor > New query, then tap Run. Safe to run more than once.
-- Only the admin (signed-in) can read or add to these numbers.

create table if not exists public.usage_minutes (
  month text primary key,                          -- like 2026-11
  participant_seconds bigint not null default 0    -- you plus every listener, added up
);

alter table public.usage_minutes enable row level security;
revoke all on public.usage_minutes from anon;

drop policy if exists "admin can read usage" on public.usage_minutes;
create policy "admin can read usage"
  on public.usage_minutes for select
  to authenticated
  using (true);

create or replace function public.add_usage(p_month text, p_seconds integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if coalesce(auth.role(), 'anon') <> 'authenticated' then
    raise exception 'Only the admin can do that.';
  end if;
  if p_seconds <= 0 or p_seconds > 3600 then
    return;
  end if;
  insert into public.usage_minutes (month, participant_seconds)
  values (p_month, p_seconds)
  on conflict (month) do update
    set participant_seconds = public.usage_minutes.participant_seconds + excluded.participant_seconds;
end;
$$;

revoke all on function public.add_usage(text, integer) from public, anon;
grant execute on function public.add_usage(text, integer) to authenticated;

-- To correct the estimate after comparing with LiveKit's dashboard, run for example:
--   update public.usage_minutes set participant_seconds = 1200 * 60 where month = '2026-11';
