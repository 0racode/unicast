-- Unicast: support for "going live" notifications.
-- Paste into Supabase > SQL Editor > New query, then tap Run. Safe to run more than once.

-- Remembers when listeners were last notified, so going live twice does not spam them.
alter table public.live_status add column if not exists last_notified_at timestamptz;
