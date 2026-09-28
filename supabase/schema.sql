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

-- Names you give to places (e.g. "Home", "Manor Park Tennis Club").
-- A stay within radius_m of a saved place is shown with that name.
create table if not exists public.places (
  id         bigint generated always as identity primary key,
  user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name       text not null,
  lat        double precision not null,
  lon        double precision not null,
  radius_m   real not null default 100,
  created_at timestamptz default now()
);

create index if not exists places_user_idx on public.places (user_id);

alter table public.places enable row level security;

drop policy if exists "places_insert_own" on public.places;
create policy "places_insert_own" on public.places
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists "places_select_own" on public.places;
create policy "places_select_own" on public.places
  for select to authenticated using (user_id = auth.uid());

drop policy if exists "places_update_own" on public.places;
create policy "places_update_own" on public.places
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "places_delete_own" on public.places;
create policy "places_delete_own" on public.places
  for delete to authenticated using (user_id = auth.uid());

revoke all on table public.places from anon;
revoke all on table public.places from authenticated;
grant select, insert, update, delete on table public.places to authenticated;

-- Where a place name came from: 'user' (you chose it), 'auto' (confident guess saved
-- automatically) or 'rejected' (an automatic name you removed, so it isn't guessed again).
alter table public.places add column if not exists source text not null default 'user';
