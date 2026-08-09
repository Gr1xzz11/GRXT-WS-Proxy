-- GRXT Auth / GRXT Cloud schema for Supabase
-- Run this file once in Supabase SQL Editor before testing cloud sync.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    grxt_id text not null unique,
    display_name text,
    plan text not null default 'free' check (plan in ('free','premium','staff')),
    is_banned boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    device_key text not null,
    device_name text,
    manufacturer text,
    model text,
    android_version text,
    app_version text,
    is_active boolean not null default true,
    first_seen timestamptz not null default now(),
    last_seen timestamptz not null default now(),
    unique (user_id, device_key)
);

create table if not exists public.user_configs (
    user_id uuid primary key references auth.users(id) on delete cascade,
    auto_route boolean not null default true,
    preferred_route text not null default 'auto',
    auto_start boolean not null default false,
    notifications boolean not null default true,
    theme text not null default 'dark',
    updated_at timestamptz not null default now()
);

create table if not exists public.proxy_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    device_key text not null,
    route text,
    dc integer,
    transport text,
    latency_ms integer,
    success boolean,
    error_code text,
    started_at timestamptz not null default now(),
    ended_at timestamptz
);

create table if not exists public.telemetry (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_key text not null,
    route text,
    network_type text,
    latency_ms integer,
    error_code text,
    created_at timestamptz not null default now()
);

create table if not exists public.security_events (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_key text,
    event text not null,
    created_at timestamptz not null default now()
);

create table if not exists public.app_versions (
    id bigint generated always as identity primary key,
    platform text not null,
    version text not null,
    version_code integer,
    download_url text,
    mandatory boolean not null default false,
    release_notes text,
    created_at timestamptz not null default now(),
    unique(platform, version)
);

create or replace function public.make_grxt_id(uid uuid)
returns text
language sql
immutable
as $$
    select 'GRXT-' || upper(substr(md5('GRXT:' || uid::text), 1, 4)) || '-' ||
           upper(substr(md5('GRXT:' || uid::text), 5, 4));
$$;

create or replace function public.handle_new_grxt_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, grxt_id)
    values (new.id, public.make_grxt_id(new.id))
    on conflict (id) do nothing;

    insert into public.user_configs (user_id)
    values (new.id)
    on conflict (user_id) do nothing;

    return new;
end;
$$;

drop trigger if exists on_auth_user_created_grxt on auth.users;
create trigger on_auth_user_created_grxt
after insert on auth.users
for each row execute procedure public.handle_new_grxt_user();

-- Backfill users that existed before this migration.
insert into public.profiles (id, grxt_id)
select id, public.make_grxt_id(id)
from auth.users
on conflict (id) do nothing;

insert into public.user_configs (user_id)
select id from auth.users
on conflict (user_id) do nothing;

alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.user_configs enable row level security;
alter table public.proxy_sessions enable row level security;
alter table public.telemetry enable row level security;
alter table public.security_events enable row level security;
alter table public.app_versions enable row level security;

-- Profiles
create policy "profiles_select_own" on public.profiles
for select using (auth.uid() = id);
create policy "profiles_insert_own" on public.profiles
for insert with check (auth.uid() = id);
create policy "profiles_update_own" on public.profiles
for update using (auth.uid() = id) with check (auth.uid() = id);

-- Devices
create policy "devices_select_own" on public.devices
for select using (auth.uid() = user_id);
create policy "devices_insert_own" on public.devices
for insert with check (auth.uid() = user_id);
create policy "devices_update_own" on public.devices
for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "devices_delete_own" on public.devices
for delete using (auth.uid() = user_id);

-- Synced config
create policy "configs_select_own" on public.user_configs
for select using (auth.uid() = user_id);
create policy "configs_insert_own" on public.user_configs
for insert with check (auth.uid() = user_id);
create policy "configs_update_own" on public.user_configs
for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Proxy history
create policy "sessions_select_own" on public.proxy_sessions
for select using (auth.uid() = user_id);
create policy "sessions_insert_own" on public.proxy_sessions
for insert with check (auth.uid() = user_id);
create policy "sessions_update_own" on public.proxy_sessions
for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Privacy-friendly diagnostics: no IP address is stored by this schema.
create policy "telemetry_select_own" on public.telemetry
for select using (auth.uid() = user_id);
create policy "telemetry_insert_own" on public.telemetry
for insert with check (auth.uid() = user_id);

create policy "security_events_select_own" on public.security_events
for select using (auth.uid() = user_id);
create policy "security_events_insert_own" on public.security_events
for insert with check (auth.uid() = user_id);

-- Everyone may read published app version metadata; clients cannot modify it.
create policy "app_versions_public_read" on public.app_versions
for select using (true);

create index if not exists idx_devices_user_last_seen on public.devices(user_id, last_seen desc);
create index if not exists idx_proxy_sessions_user_started on public.proxy_sessions(user_id, started_at desc);
create index if not exists idx_telemetry_user_created on public.telemetry(user_id, created_at desc);
create index if not exists idx_security_events_user_created on public.security_events(user_id, created_at desc);

insert into public.app_versions(platform, version, version_code, mandatory, release_notes)
values ('android', '0.4.0', 5, false, 'GRXT Auth, GRXT ID, device sync and cloud schema')
on conflict(platform, version) do update
set version_code = excluded.version_code,
    mandatory = excluded.mandatory,
    release_notes = excluded.release_notes;
