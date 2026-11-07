-- Unicast: count plays and downloads for each episode.
-- Paste into Supabase > SQL Editor > New query, then tap Run. Safe to run more than once.

alter table public.episodes add column if not exists play_count integer not null default 0;
alter table public.episodes add column if not exists download_count integer not null default 0;

-- Listeners' apps call this when someone plays or downloads an episode.
create or replace function public.track_episode(p_id uuid, p_kind text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if p_kind = 'play' then
    update public.episodes set play_count = play_count + 1 where id = p_id;
  elsif p_kind = 'download' then
    update public.episodes set download_count = download_count + 1 where id = p_id;
  end if;
end;
$$;

grant execute on function public.track_episode(uuid, text) to anon, authenticated;
