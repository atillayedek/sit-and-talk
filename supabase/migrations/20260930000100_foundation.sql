-- Sit & Talk — foundation: private schema, errors, rate limits, audit, roles,
-- feature flags, settings, profiles and private user data.
--
-- Conventions
-- * Client-callable RPCs live in `public` and are SECURITY DEFINER only when they
--   must touch rows the caller cannot see; every definer function pins
--   `search_path = ''` and qualifies all names.
-- * Internal helpers live in `app_private`, which PostgREST does not expose.
-- * Errors are raised with SQLSTATE P0001 and a stable snake_case `message`
--   (e.g. `room_full`). The Android client maps these codes to localized text.

create extension if not exists pgcrypto with schema extensions;

create schema if not exists app_private;
revoke all on schema app_private from public;
grant usage on schema app_private to service_role;

-- ---------------------------------------------------------------------------
-- Errors
-- ---------------------------------------------------------------------------
create or replace function app_private.err(p_code text, p_detail text default null, p_hint text default null)
returns void
language plpgsql
set search_path = ''
as $$
begin
  raise exception using errcode = 'P0001', message = p_code, detail = coalesce(p_detail, ''), hint = coalesce(p_hint, '');
end;
$$;

create or replace function app_private.require_user()
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    perform app_private.err('not_authenticated');
  end if;
  return v_uid;
end;
$$;

-- ---------------------------------------------------------------------------
-- Settings and feature flags (server is the source of truth)
-- ---------------------------------------------------------------------------
create table public.app_settings (
  key text primary key check (key ~ '^[a-z0-9_]{2,64}$'),
  value jsonb not null,
  is_public boolean not null default true,
  description text not null default '',
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null
);

create table public.feature_flags (
  key text primary key check (key ~ '^[a-z0-9_]{2,64}$'),
  enabled boolean not null default false,
  description text not null default '',
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null
);

insert into public.app_settings (key, value, description) values
  ('call_initial_seconds', '180', 'Length of a first random call before both sides must extend'),
  ('call_extension_seconds', '300', 'Seconds added when both participants approve an extension'),
  ('match_accept_seconds', '20', 'Seconds each side has to accept a found match'),
  ('direct_ring_seconds', '35', 'Seconds a direct (friend) call rings before it is missed'),
  ('min_version_code', '1', 'Oldest Android versionCode allowed to use the service'),
  ('maintenance', '{"enabled": false, "message_tr": "", "message_en": ""}', 'Maintenance mode banner and gate'),
  ('announcement', '{"active": false, "title_tr": "", "body_tr": "", "title_en": "", "body_en": ""}', 'System announcement shown in the app'),
  ('message_edit_window_minutes', '15', 'How long a message can be edited'),
  ('message_unsend_window_minutes', '60', 'How long a message can be deleted for everyone'),
  ('coin_products', '{}', 'Google Play product id -> coins granted, e.g. {"coins_100": 100}'),
  ('premium_products', '[]', 'Google Play subscription product ids that grant premium'),
  ('free_daily_matches', '60', 'Random matches per day for non-premium users'),
  ('premium_daily_matches', '300', 'Random matches per day for premium users'),
  ('free_rooms_per_day', '5', 'Rooms a non-premium user may open per day'),
  ('premium_rooms_per_day', '20', 'Rooms a premium user may open per day')
on conflict (key) do nothing;

insert into public.feature_flags (key, enabled, description) values
  ('voice_matching', true, 'Random anonymous voice calls'),
  ('text_matching', true, 'Random text introductions'),
  ('video_matching', false, 'Random video calls — enable only when moderation staffing is in place'),
  ('rooms', true, 'Independent voice rooms'),
  ('feed', true, 'Discover feed and stories'),
  ('gifts', false, 'Gift sending — requires Play products and coin_products setting'),
  ('premium', false, 'Premium subscription — requires Play subscription products'),
  ('google_sign_in', false, 'Show Google sign-in (requires Supabase Google provider + client id)')
on conflict (key) do nothing;

create or replace function app_private.setting_int(p_key text, p_default int)
returns int
language sql
stable
set search_path = ''
as $$
  select coalesce((select (value #>> '{}')::int from public.app_settings where key = p_key), p_default);
$$;

create or replace function app_private.flag(p_key text)
returns boolean
language sql
stable
set search_path = ''
as $$
  select coalesce((select enabled from public.feature_flags where key = p_key), false);
$$;

-- ---------------------------------------------------------------------------
-- Staff roles (assigned only with the service role / SQL editor)
-- ---------------------------------------------------------------------------
create type public.staff_role as enum ('admin', 'moderator');

create table public.admin_roles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role public.staff_role not null,
  granted_at timestamptz not null default now(),
  granted_by uuid references auth.users(id) on delete set null
);

create or replace function app_private.staff_role_of(p_uid uuid)
returns public.staff_role
language sql
stable
security definer
set search_path = ''
as $$
  select role from public.admin_roles where user_id = p_uid;
$$;

create or replace function public.is_staff()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.admin_roles where user_id = auth.uid());
$$;

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.admin_roles where user_id = auth.uid() and role = 'admin');
$$;

create or replace function app_private.require_staff(p_admin_only boolean default false)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_role public.staff_role;
begin
  select role into v_role from public.admin_roles where user_id = v_uid;
  if v_role is null or (p_admin_only and v_role <> 'admin') then
    perform app_private.err('forbidden');
  end if;
  return v_uid;
end;
$$;

-- ---------------------------------------------------------------------------
-- Audit log
-- ---------------------------------------------------------------------------
create table public.audit_logs (
  id bigint generated always as identity primary key,
  actor_id uuid references auth.users(id) on delete set null,
  action text not null,
  target_type text not null,
  target_id text,
  reason text,
  result text not null default 'ok',
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index audit_logs_created_idx on public.audit_logs (created_at desc);
create index audit_logs_target_idx on public.audit_logs (target_type, target_id);

create or replace function app_private.audit(
  p_action text, p_target_type text, p_target_id text, p_reason text default null,
  p_metadata jsonb default '{}'::jsonb, p_result text default 'ok'
)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.audit_logs (actor_id, action, target_type, target_id, reason, result, metadata)
  values (auth.uid(), p_action, p_target_type, p_target_id, p_reason, p_result, coalesce(p_metadata, '{}'::jsonb));
$$;

-- ---------------------------------------------------------------------------
-- Rate limiting (shared, persistent — works across Edge Function instances)
-- ---------------------------------------------------------------------------
create table app_private.rate_limits (
  subject text not null,
  bucket text not null,
  window_start timestamptz not null,
  hits int not null default 0,
  primary key (subject, bucket, window_start)
);

-- Fixed-window counter. Raises `rate_limited` with the retry delay (seconds) in DETAIL.
create or replace function app_private.hit_rate_limit(p_subject text, p_bucket text, p_max int, p_window interval)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_epoch double precision := extract(epoch from now());
  v_len double precision := extract(epoch from p_window);
  v_start timestamptz := to_timestamp(floor(v_epoch / v_len) * v_len);
  v_hits int;
begin
  insert into app_private.rate_limits as r (subject, bucket, window_start, hits)
  values (p_subject, p_bucket, v_start, 1)
  on conflict (subject, bucket, window_start) do update set hits = r.hits + 1
  returning hits into v_hits;
  if v_hits > p_max then
    perform app_private.err('rate_limited', ceil(extract(epoch from (v_start + p_window - now())))::int::text);
  end if;
end;
$$;

create or replace function app_private.rate_limit(p_bucket text, p_max int, p_window interval)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.hit_rate_limit(app_private.require_user()::text, p_bucket, p_max, p_window);
end;
$$;

-- Edge Functions call this through the service role to rate limit per user.
create or replace function public.edge_rate_limit(p_user uuid, p_bucket text, p_max int, p_window_seconds int)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.hit_rate_limit(p_user::text, p_bucket, p_max, make_interval(secs => p_window_seconds));
end;
$$;

-- ---------------------------------------------------------------------------
-- Profiles (public), private data and restrictions
-- ---------------------------------------------------------------------------
create type public.message_policy as enum ('everyone', 'friends', 'nobody');
create type public.visibility_policy as enum ('everyone', 'friends', 'nobody');

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null check (username ~ '^[a-z0-9_.]{3,24}$'),
  display_name text not null check (char_length(btrim(display_name)) between 2 and 32),
  bio text not null default '' check (char_length(bio) <= 280),
  avatar_path text check (avatar_path is null or avatar_path ~ '^[0-9a-f-]{36}/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$'),
  country_code text check (country_code is null or country_code ~ '^[A-Z]{2}$'),
  gifts_received int not null default 0,
  username_changed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index profiles_username_key on public.profiles (lower(username));

create table public.user_private (
  user_id uuid primary key references auth.users(id) on delete cascade,
  birth_date date,
  terms_accepted_at timestamptz,
  privacy_accepted_at timestamptz,
  community_accepted_at timestamptz,
  onboarding_completed_at timestamptz,
  message_policy public.message_policy not null default 'friends',
  call_policy public.message_policy not null default 'friends',
  online_visibility public.visibility_policy not null default 'friends',
  last_seen_visibility public.visibility_policy not null default 'friends',
  read_receipts boolean not null default true,
  media_from_non_friends boolean not null default false,
  share_avatar_in_random boolean not null default false,
  profile_discoverable boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.user_consents (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  document text not null check (document in ('terms', 'privacy', 'community')),
  version text not null,
  accepted_at timestamptz not null default now()
);
create index user_consents_user_idx on public.user_consents (user_id);

create table public.interests (
  slug text primary key check (slug ~ '^[a-z0-9_]{2,40}$'),
  name_tr text not null,
  name_en text not null,
  category text not null default 'general',
  active boolean not null default true,
  sort int not null default 0
);

create table public.user_interests (
  user_id uuid not null references auth.users(id) on delete cascade,
  slug text not null references public.interests(slug) on delete cascade,
  is_public boolean not null default true,
  primary key (user_id, slug)
);
create index user_interests_slug_idx on public.user_interests (slug);

create table public.user_languages (
  user_id uuid not null references auth.users(id) on delete cascade,
  language_code text not null check (language_code ~ '^[a-z]{2,3}$'),
  primary key (user_id, language_code)
);

create table public.user_presence (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_seen_at timestamptz not null default now()
);

create type public.restriction_kind as enum ('warning', 'messaging', 'matching', 'room_creation', 'posting', 'suspension', 'ban');

create table public.account_restrictions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  kind public.restriction_kind not null,
  reason text not null,
  starts_at timestamptz not null default now(),
  ends_at timestamptz,
  lifted_at timestamptz,
  created_by uuid references auth.users(id) on delete set null,
  report_id uuid,
  created_at timestamptz not null default now(),
  check (ends_at is null or ends_at > starts_at)
);
create index account_restrictions_user_idx on public.account_restrictions (user_id) where lifted_at is null;

create or replace function app_private.is_restricted(p_uid uuid, p_kind public.restriction_kind)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.account_restrictions r
    where r.user_id = p_uid
      and r.lifted_at is null
      and r.starts_at <= now()
      and (r.ends_at is null or r.ends_at > now())
      and (r.kind = p_kind or r.kind in ('suspension', 'ban'))
      and r.kind <> 'warning'
  );
$$;

create or replace function app_private.require_not_restricted(p_uid uuid, p_kind public.restriction_kind)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if app_private.is_restricted(p_uid, p_kind) then
    perform app_private.err('account_restricted', p_kind::text);
  end if;
end;
$$;

create or replace function app_private.require_profile(p_uid uuid)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.user_private up
    join public.profiles p on p.id = up.user_id
    where up.user_id = p_uid and up.onboarding_completed_at is not null
  ) then
    perform app_private.err('profile_incomplete');
  end if;
end;
$$;

-- Creates the private row for every new auth user. Profiles are created at onboarding.
create or replace function app_private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.user_private (user_id) values (new.id) on conflict do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute function app_private.handle_new_user();

create or replace function app_private.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger profiles_touch before update on public.profiles
for each row execute function app_private.touch_updated_at();
create trigger user_private_touch before update on public.user_private
for each row execute function app_private.touch_updated_at();

-- Seed catalog: editorial product configuration, not user data.
insert into public.interests (slug, name_tr, name_en, category, sort) values
  ('music', 'Müzik', 'Music', 'culture', 10),
  ('movies', 'Film', 'Movies', 'culture', 20),
  ('series', 'Dizi', 'TV series', 'culture', 30),
  ('books', 'Kitap', 'Books', 'culture', 40),
  ('games', 'Oyun', 'Gaming', 'hobby', 50),
  ('sports', 'Spor', 'Sports', 'hobby', 60),
  ('football', 'Futbol', 'Football', 'hobby', 70),
  ('fitness', 'Fitness', 'Fitness', 'lifestyle', 80),
  ('travel', 'Seyahat', 'Travel', 'lifestyle', 90),
  ('food', 'Yemek', 'Food', 'lifestyle', 100),
  ('coffee', 'Kahve', 'Coffee', 'lifestyle', 110),
  ('pets', 'Evcil hayvanlar', 'Pets', 'lifestyle', 120),
  ('nature', 'Doğa', 'Nature', 'lifestyle', 130),
  ('photography', 'Fotoğrafçılık', 'Photography', 'creative', 140),
  ('art', 'Sanat', 'Art', 'creative', 150),
  ('design', 'Tasarım', 'Design', 'creative', 160),
  ('writing', 'Yazarlık', 'Writing', 'creative', 170),
  ('technology', 'Teknoloji', 'Technology', 'knowledge', 180),
  ('science', 'Bilim', 'Science', 'knowledge', 190),
  ('history', 'Tarih', 'History', 'knowledge', 200),
  ('philosophy', 'Felsefe', 'Philosophy', 'knowledge', 210),
  ('languages', 'Dil öğrenme', 'Language learning', 'knowledge', 220),
  ('career', 'Kariyer', 'Career', 'life', 230),
  ('startups', 'Girişimcilik', 'Startups', 'life', 240),
  ('university', 'Üniversite', 'University', 'life', 250),
  ('anime', 'Anime', 'Anime', 'culture', 260),
  ('podcasts', 'Podcast', 'Podcasts', 'culture', 270),
  ('astronomy', 'Astronomi', 'Astronomy', 'knowledge', 280),
  ('cars', 'Otomobil', 'Cars', 'hobby', 290),
  ('dance', 'Dans', 'Dance', 'creative', 300)
on conflict (slug) do nothing;
