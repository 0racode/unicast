-- Unicast: Supabase setup. Paste this whole file into Supabase > SQL Editor > New query, then tap Run.
-- Safe to run more than once.

-- 1. Episodes (recorded audio)
create table if not exists public.episodes (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  file_path text not null,            -- path inside the "episodes" storage bucket
  duration_seconds integer,
  created_at timestamptz not null default now()
);

-- 2. Live status (always exactly one row)
create table if not exists public.live_status (
  id integer primary key default 1 check (id = 1),
  is_live boolean not null default false,
  room_name text not null default 'unicast-live',
  updated_at timestamptz not null default now()
);

insert into public.live_status (id) values (1) on conflict (id) do nothing;

-- 3. Row level security: everyone can read, only a signed-in user (you) can change
alter table public.episodes enable row level security;
alter table public.live_status enable row level security;

drop policy if exists "episodes are public to read" on public.episodes;
create policy "episodes are public to read"
  on public.episodes for select
  to anon, authenticated
  using (true);

drop policy if exists "admin can add episodes" on public.episodes;
create policy "admin can add episodes"
  on public.episodes for insert
  to authenticated
  with check (true);

drop policy if exists "admin can edit episodes" on public.episodes;
create policy "admin can edit episodes"
  on public.episodes for update
  to authenticated
  using (true) with check (true);

drop policy if exists "admin can delete episodes" on public.episodes;
create policy "admin can delete episodes"
  on public.episodes for delete
  to authenticated
  using (true);

drop policy if exists "live status is public to read" on public.live_status;
create policy "live status is public to read"
  on public.live_status for select
  to anon, authenticated
  using (true);

drop policy if exists "admin can change live status" on public.live_status;
create policy "admin can change live status"
  on public.live_status for update
  to authenticated
  using (true) with check (true);

-- 4. Storage bucket for the audio files (public so listeners can stream and download)
insert into storage.buckets (id, name, public)
values ('episodes', 'episodes', true)
on conflict (id) do nothing;

drop policy if exists "audio is public to read" on storage.objects;
create policy "audio is public to read"
  on storage.objects for select
  to anon, authenticated
  using (bucket_id = 'episodes');

drop policy if exists "admin can upload audio" on storage.objects;
create policy "admin can upload audio"
  on storage.objects for insert
  to authenticated
  with check (bucket_id = 'episodes');

drop policy if exists "admin can replace audio" on storage.objects;
create policy "admin can replace audio"
  on storage.objects for update
  to authenticated
  using (bucket_id = 'episodes');

drop policy if exists "admin can delete audio" on storage.objects;
create policy "admin can delete audio"
  on storage.objects for delete
  to authenticated
  using (bucket_id = 'episodes');

-- 5. Let listeners' apps get live updates when the live flag changes
do $$
begin
  alter publication supabase_realtime add table public.live_status;
exception when duplicate_object then
  null;
end $$;
