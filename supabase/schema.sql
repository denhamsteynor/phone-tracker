-- Location Timeline: database schema.
-- Paste this whole file into Supabase → SQL Editor → New query, then click "Run".
-- It is safe to run more than once.

create table if not exists public.locations (
  id          bigint generated always as identity primary key,
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  recorded_at timestamptz not null,
  lat         double precision not null,
  lon         double precision not null,
  accuracy    real,
  speed       real,
  activity    text,
  created_at  timestamptz default now(),
  unique (user_id, recorded_at)
);

create index if not exists locations_user_recorded_idx
  on public.locations (user_id, recorded_at);

alter table public.locations enable row level security;

drop policy if exists "locations_insert_own" on public.locations;
create policy "locations_insert_own" on public.locations
  for insert to authenticated
  with check (user_id = auth.uid());

drop policy if exists "locations_select_own" on public.locations;
create policy "locations_select_own" on public.locations
  for select to authenticated
  using (user_id = auth.uid());

drop policy if exists "locations_delete_own" on public.locations;
create policy "locations_delete_own" on public.locations
  for delete to authenticated
  using (user_id = auth.uid());

-- Only signed-in users may touch the table, and only select/insert/delete.
revoke all on table public.locations from anon;
revoke all on table public.locations from authenticated;
grant select, insert, delete on table public.locations to authenticated;
