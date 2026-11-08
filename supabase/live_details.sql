-- Adds a title and description to the live show, so listeners can see what it is about.
alter table public.live_status add column if not exists title text;
alter table public.live_status add column if not exists description text;
