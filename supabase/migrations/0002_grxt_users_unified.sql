-- GRXT Cloud unified user storage (0.4.3)
-- Run in Supabase SQL Editor AFTER 0001_grxt_auth.sql if you already used it.
-- It is also safe to run on a fresh project: missing old tables are ignored.

create extension if not exists pgcrypto;

create or replace function public.make_grxt_id(uid uuid)
returns text
language sql
immutable
as $$
    select 'GRXT-' || upper(substr(encode(digest('GRXT:' || uid::text, 'sha256'), 'hex'), 1, 4)) || '-' ||
           upper(substr(encode(digest('GRXT:' || uid::text, 'sha256'), 'hex'), 5, 4));
$$;

create table if not exists public.grxt_users (
    id uuid primary key references auth.users(id) on delete cascade,
    grxt_id text not null unique,
    email text,
    plan text not null default 'free' check (plan in ('free','premium','staff')),
    is_banned boolean not null default false,
    devices jsonb not null default '{}'::jsonb,
    settings jsonb not null default jsonb_build_object(
        'auto_route', true,
        'preferred_route', 'auto',
        'auto_start', false,
        'notifications', true,
        'theme', 'dark'
    ),
    stats jsonb not null default jsonb_build_object(
        'proxy_sessions', 0,
        'successful_sessions', 0,
        'failed_sessions', 0
    ),
    last_event jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_seen timestamptz
);

-- Backfill every Supabase Auth user into exactly one GRXT row.
insert into public.grxt_users (id, grxt_id, email, created_at)
select id, public.make_grxt_id(id), email, created_at
from auth.users
on conflict (id) do update
set email = excluded.email,
    grxt_id = excluded.grxt_id;

-- Preserve old profile fields when the previous schema exists.
do $$
begin
    if to_regclass('public.profiles') is not null then
        execute $q$
            update public.grxt_users g
            set plan = p.plan,
                is_banned = p.is_banned,
                created_at = least(g.created_at, p.created_at)
            from public.profiles p
            where p.id = g.id
        $q$;
    end if;
end $$;

-- Preserve old devices as a JSON object keyed by device_key.
do $$
begin
    if to_regclass('public.devices') is not null then
        execute $q$
            update public.grxt_users g
            set devices = d.payload
            from (
                select user_id,
                       jsonb_object_agg(
                           device_key,
                           jsonb_build_object(
                               'device_name', device_name,
                               'manufacturer', manufacturer,
                               'model', model,
                               'android_version', android_version,
                               'app_version', app_version,
                               'is_active', is_active,
                               'first_seen', first_seen,
                               'last_seen', last_seen
                           )
                       ) as payload
                from public.devices
                group by user_id
            ) d
            where d.user_id = g.id
        $q$;
    end if;
end $$;

-- Preserve old synced settings.
do $$
begin
    if to_regclass('public.user_configs') is not null then
        execute $q$
            update public.grxt_users g
            set settings = jsonb_build_object(
                'auto_route', c.auto_route,
                'preferred_route', c.preferred_route,
                'auto_start', c.auto_start,
                'notifications', c.notifications,
                'theme', c.theme
            )
            from public.user_configs c
            where c.user_id = g.id
        $q$;
    end if;
end $$;

create or replace function public.handle_new_grxt_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.grxt_users (id, grxt_id, email)
    values (new.id, public.make_grxt_id(new.id), new.email)
    on conflict (id) do update
    set email = excluded.email,
        grxt_id = excluded.grxt_id,
        updated_at = now();
    return new;
end;
$$;

drop trigger if exists on_auth_user_created_grxt on auth.users;
create trigger on_auth_user_created_grxt
after insert on auth.users
for each row execute procedure public.handle_new_grxt_user();

alter table public.grxt_users enable row level security;

drop policy if exists "grxt_users_select_own" on public.grxt_users;
drop policy if exists "grxt_users_insert_own" on public.grxt_users;
drop policy if exists "grxt_users_update_own" on public.grxt_users;

create policy "grxt_users_select_own" on public.grxt_users
for select using (auth.uid() = id);

create policy "grxt_users_insert_own" on public.grxt_users
for insert with check (auth.uid() = id);

create policy "grxt_users_update_own" on public.grxt_users
for update using (auth.uid() = id) with check (auth.uid() = id);

grant select, insert, update on public.grxt_users to authenticated;

-- Server-side merge keeps all devices in the SAME user row without clients overwriting each other.
create or replace function public.grxt_sync_user(
    p_device_key text,
    p_device_name text,
    p_manufacturer text,
    p_model text,
    p_android_version text,
    p_app_version text,
    p_event text
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    uid uuid := auth.uid();
    device_payload jsonb;
    result_row public.grxt_users;
begin
    if uid is null then
        raise exception 'GRXT Auth required';
    end if;

    device_payload := jsonb_build_object(
        p_device_key,
        jsonb_build_object(
            'device_name', p_device_name,
            'manufacturer', p_manufacturer,
            'model', p_model,
            'android_version', p_android_version,
            'app_version', p_app_version,
            'is_active', true,
            'last_seen', now()
        )
    );

    insert into public.grxt_users (
        id, grxt_id, email, devices, last_event, last_seen
    ) values (
        uid,
        public.make_grxt_id(uid),
        auth.jwt() ->> 'email',
        device_payload,
        jsonb_build_object('event', p_event, 'device_key', p_device_key, 'at', now()),
        now()
    )
    on conflict (id) do update
    set email = excluded.email,
        grxt_id = excluded.grxt_id,
        devices = coalesce(public.grxt_users.devices, '{}'::jsonb) || excluded.devices,
        last_event = excluded.last_event,
        last_seen = now(),
        updated_at = now()
    returning * into result_row;

    return jsonb_build_object(
        'id', result_row.id,
        'grxt_id', result_row.grxt_id,
        'last_seen', result_row.last_seen
    );
end;
$$;

grant execute on function public.grxt_sync_user(text,text,text,text,text,text,text) to authenticated;

-- Optional admin helper: one command deletes the Supabase Auth user, and ON DELETE CASCADE
-- automatically deletes the only public.grxt_users row. Never expose this to app users.
create or replace function public.admin_delete_grxt_user(target_user uuid)
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    delete from auth.users where id = target_user;
end;
$$;

revoke all on function public.admin_delete_grxt_user(uuid) from public, anon, authenticated;
grant execute on function public.admin_delete_grxt_user(uuid) to service_role;

-- Old per-feature tables are no longer used after data is copied above.
drop table if exists public.devices cascade;
drop table if exists public.user_configs cascade;
drop table if exists public.proxy_sessions cascade;
drop table if exists public.telemetry cascade;
drop table if exists public.security_events cascade;
drop table if exists public.profiles cascade;
drop table if exists public.app_versions cascade;
