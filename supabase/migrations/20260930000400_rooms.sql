-- Sit & Talk — independent voice rooms (no servers, categories or channels).
-- Room-scoped roles only: owner, moderator, speaker, listener.

create type public.room_visibility as enum ('public', 'invite', 'password');
create type public.room_status as enum ('open', 'closed');
create type public.room_role as enum ('owner', 'moderator', 'speaker', 'listener');

create table public.rooms (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid references auth.users(id) on delete set null,
  title text not null check (char_length(btrim(title)) between 3 and 60),
  description text not null default '' check (char_length(description) <= 300),
  topic text references public.interests(slug) on delete set null,
  tags text[] not null default '{}' check (cardinality(tags) <= 5),
  language_code text not null check (language_code ~ '^[a-z]{2,3}$'),
  cover_path text,
  visibility public.room_visibility not null default 'public',
  max_participants int not null default 50 check (max_participants between 2 and 200),
  max_speakers int not null default 8 check (max_speakers between 1 and 20),
  hand_raise_required boolean not null default true,
  text_chat_enabled boolean not null default true,
  status public.room_status not null default 'open',
  channel_name text not null unique,
  event_id uuid,
  created_at timestamptz not null default now(),
  closed_at timestamptz,
  close_reason text,
  owner_left_at timestamptz,
  empty_since timestamptz
);
create index rooms_open_idx on public.rooms (created_at desc) where status = 'open';
create index rooms_owner_idx on public.rooms (owner_id);

-- Password hashes live apart from the room row so no client query can ever select them.
create table app_private.room_secrets (
  room_id uuid primary key references public.rooms(id) on delete cascade,
  password_hash text not null
);

create table public.room_members (
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role public.room_role not null default 'listener',
  rtc_uid int not null,
  self_muted boolean not null default true,
  muted_by_moderator boolean not null default false,
  hand_raised_at timestamptz,
  joined_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (room_id, user_id)
);
create index room_members_user_idx on public.room_members (user_id);

create table public.room_bans (
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  banned_by uuid references auth.users(id) on delete set null,
  reason text,
  created_at timestamptz not null default now(),
  primary key (room_id, user_id)
);

create table public.room_invites (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms(id) on delete cascade,
  inviter_id uuid references auth.users(id) on delete set null,
  invitee_id uuid references auth.users(id) on delete cascade,
  code text unique,
  max_uses int not null default 1 check (max_uses between 1 and 500),
  uses int not null default 0,
  expires_at timestamptz not null default now() + interval '24 hours',
  created_at timestamptz not null default now(),
  check (invitee_id is not null or code is not null)
);
create index room_invites_invitee_idx on public.room_invites (invitee_id);

create type public.speaker_request_kind as enum ('hand', 'invite');
create type public.speaker_request_status as enum ('pending', 'accepted', 'declined', 'cancelled');

create table public.room_speaker_requests (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  kind public.speaker_request_kind not null,
  invited_by uuid references auth.users(id) on delete set null,
  status public.speaker_request_status not null default 'pending',
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);
create unique index room_speaker_requests_pending_key on public.room_speaker_requests (room_id, user_id) where status = 'pending';

create table public.room_messages (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid references auth.users(id) on delete set null,
  body text not null check (char_length(btrim(body)) between 1 and 500),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index room_messages_room_idx on public.room_messages (room_id, created_at desc);

-- Short-lived reaction / gift events. Pruned by the sweeper.
create table public.room_reactions (
  id bigint generated always as identity primary key,
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  kind text not null check (kind in ('clap', 'heart', 'laugh', 'wow', 'fire', 'gift')),
  gift_code text,
  target_user_id uuid references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
create index room_reactions_room_idx on public.room_reactions (room_id, created_at desc);

create table public.room_favorites (
  user_id uuid not null references auth.users(id) on delete cascade,
  room_id uuid not null references public.rooms(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, room_id)
);

create type public.room_event_status as enum ('scheduled', 'live', 'cancelled', 'finished');

create table public.room_events (
  id uuid primary key default gen_random_uuid(),
  created_by uuid references auth.users(id) on delete set null,
  title text not null check (char_length(btrim(title)) between 3 and 80),
  description text not null default '' check (char_length(description) <= 500),
  topic text references public.interests(slug) on delete set null,
  language_code text not null check (language_code ~ '^[a-z]{2,3}$'),
  starts_at timestamptz not null,
  status public.room_event_status not null default 'scheduled',
  room_id uuid references public.rooms(id) on delete set null,
  reminder_sent_at timestamptz,
  created_at timestamptz not null default now()
);
create index room_events_upcoming_idx on public.room_events (starts_at) where status = 'scheduled';

create table public.room_event_subscribers (
  event_id uuid not null references public.room_events(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (event_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Access helpers
-- ---------------------------------------------------------------------------
create or replace function app_private.room_role_of(p_room uuid, p_user uuid)
returns public.room_role
language sql
stable
security definer
set search_path = ''
as $$
  select role from public.room_members where room_id = p_room and user_id = p_user;
$$;

create or replace function app_private.is_room_member(p_room uuid, p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.room_members where room_id = p_room and user_id = p_user);
$$;

create or replace function app_private.can_see_room(p_room uuid, p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.rooms r
    where r.id = p_room
      and not exists (select 1 from public.room_bans b where b.room_id = r.id and b.user_id = p_user)
      and (r.owner_id is null or not app_private.is_blocked(r.owner_id, p_user))
      and (r.visibility in ('public', 'password')
           or r.owner_id = p_user
           or exists (select 1 from public.room_members m where m.room_id = r.id and m.user_id = p_user)
           or exists (select 1 from public.room_invites i where i.room_id = r.id and i.invitee_id = p_user and i.expires_at > now()))
  );
$$;

create or replace function app_private.require_room_moderator(p_room uuid, p_user uuid)
returns public.room_role
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_role public.room_role := app_private.room_role_of(p_room, p_user);
begin
  if v_role in ('owner', 'moderator') then
    return v_role;
  end if;
  if exists (select 1 from public.admin_roles where user_id = p_user) then
    return 'owner';
  end if;
  perform app_private.err('forbidden');
  return null;
end;
$$;

create or replace function app_private.room_state(p_room uuid, p_user uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_r public.rooms;
begin
  select * into v_r from public.rooms where id = p_room;
  if not found then
    perform app_private.err('not_found');
  end if;
  return jsonb_build_object(
    'id', v_r.id,
    'title', v_r.title,
    'description', v_r.description,
    'topic', v_r.topic,
    'tags', to_jsonb(v_r.tags),
    'language_code', v_r.language_code,
    'cover_path', v_r.cover_path,
    'visibility', v_r.visibility,
    'status', v_r.status,
    'close_reason', v_r.close_reason,
    'max_participants', v_r.max_participants,
    'max_speakers', v_r.max_speakers,
    'hand_raise_required', v_r.hand_raise_required,
    'text_chat_enabled', v_r.text_chat_enabled,
    'owner_id', v_r.owner_id,
    'created_at', v_r.created_at,
    'is_favorite', exists (select 1 from public.room_favorites f where f.room_id = p_room and f.user_id = p_user),
    'my_role', app_private.room_role_of(p_room, p_user),
    'members', coalesce((
      select jsonb_agg(jsonb_build_object(
        'user_id', m.user_id, 'role', m.role, 'rtc_uid', m.rtc_uid,
        'self_muted', m.self_muted, 'muted_by_moderator', m.muted_by_moderator,
        'hand_raised_at', m.hand_raised_at, 'joined_at', m.joined_at,
        'display_name', p.display_name, 'username', p.username, 'avatar_path', p.avatar_path)
        order by case m.role when 'owner' then 0 when 'moderator' then 1 when 'speaker' then 2 else 3 end, m.joined_at)
      from public.room_members m join public.profiles p on p.id = m.user_id
      where m.room_id = p_room), '[]'::jsonb),
    'pending_requests', case when app_private.room_role_of(p_room, p_user) in ('owner', 'moderator') then coalesce((
      select jsonb_agg(jsonb_build_object('id', sr.id, 'user_id', sr.user_id, 'kind', sr.kind, 'created_at', sr.created_at))
      from public.room_speaker_requests sr where sr.room_id = p_room and sr.status = 'pending'), '[]'::jsonb) else '[]'::jsonb end,
    'my_invite', (
      select jsonb_build_object('id', sr.id, 'created_at', sr.created_at)
      from public.room_speaker_requests sr
      where sr.room_id = p_room and sr.user_id = p_user and sr.status = 'pending' and sr.kind = 'invite'),
    'server_now', now());
end;
$$;

-- ---------------------------------------------------------------------------
-- Listing (security invoker: RLS decides what the caller can see)
-- ---------------------------------------------------------------------------
create or replace function public.list_rooms(
  p_query text default null,
  p_topic text default null,
  p_language text default null,
  p_favorites_only boolean default false,
  p_limit int default 30,
  p_offset int default 0
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select coalesce(jsonb_agg(x order by (x ->> 'participant_count')::int desc, x ->> 'created_at' desc), '[]'::jsonb)
  from (
    select jsonb_build_object(
      'id', r.id, 'title', r.title, 'description', r.description, 'topic', r.topic, 'tags', to_jsonb(r.tags),
      'language_code', r.language_code, 'cover_path', r.cover_path, 'visibility', r.visibility,
      'max_participants', r.max_participants, 'created_at', r.created_at,
      'participant_count', (select count(*) from public.room_members m where m.room_id = r.id),
      'speaker_count', (select count(*) from public.room_members m where m.room_id = r.id and m.role <> 'listener'),
      'is_favorite', exists (select 1 from public.room_favorites f where f.room_id = r.id and f.user_id = auth.uid()),
      'is_member', exists (select 1 from public.room_members m where m.room_id = r.id and m.user_id = auth.uid()),
      'owner', (select jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_path', p.avatar_path)
                from public.profiles p where p.id = r.owner_id)
    ) as x
    from public.rooms r
    where r.status = 'open'
      and (p_topic is null or r.topic = p_topic)
      and (p_language is null or r.language_code = p_language)
      and (p_query is null or r.title ilike '%' || replace(replace(p_query, '%', ''), '_', '') || '%')
      and (not p_favorites_only or exists (select 1 from public.room_favorites f where f.room_id = r.id and f.user_id = auth.uid()))
    order by r.created_at desc
    limit least(greatest(p_limit, 1), 50) offset greatest(p_offset, 0)
  ) s;
$$;

-- ---------------------------------------------------------------------------
-- Room lifecycle RPCs
-- ---------------------------------------------------------------------------
create or replace function public.create_room(
  p_title text,
  p_description text,
  p_topic text,
  p_tags text[],
  p_language text,
  p_visibility public.room_visibility,
  p_password text,
  p_max_participants int,
  p_max_speakers int,
  p_hand_raise_required boolean,
  p_text_chat_enabled boolean,
  p_event_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_room uuid;
  v_premium boolean;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'room_creation');
  if not app_private.flag('rooms') then
    perform app_private.err('feature_disabled', 'rooms');
  end if;
  if app_private.active_session_of(v_uid) is not null then
    perform app_private.err('already_in_call');
  end if;
  v_premium := app_private.is_premium(v_uid);
  perform app_private.rate_limit('create_room',
    case when v_premium then app_private.setting_int('premium_rooms_per_day', 20) else app_private.setting_int('free_rooms_per_day', 5) end,
    interval '1 day');
  if p_visibility = 'password' and char_length(coalesce(p_password, '')) not between 4 and 64 then
    perform app_private.err('invalid_room_password');
  end if;
  if not v_premium and coalesce(p_max_participants, 50) > 50 then
    perform app_private.err('premium_required');
  end if;
  if p_event_id is not null and not exists (
      select 1 from public.room_events where id = p_event_id and created_by = v_uid and status = 'scheduled') then
    perform app_private.err('not_found');
  end if;

  -- Leave any other room first: one live audio session per user.
  delete from public.room_members where user_id = v_uid;

  insert into public.rooms (owner_id, title, description, topic, tags, language_code, visibility,
                            max_participants, max_speakers, hand_raise_required, text_chat_enabled, channel_name, event_id)
  values (v_uid, btrim(p_title), coalesce(btrim(p_description), ''), p_topic,
          coalesce((select array_agg(distinct left(lower(btrim(t)), 24)) from unnest(p_tags) t where btrim(t) <> ''), '{}'),
          lower(p_language), p_visibility, coalesce(p_max_participants, 50), coalesce(p_max_speakers, 8),
          coalesce(p_hand_raise_required, true), coalesce(p_text_chat_enabled, true),
          'rm_' || replace(gen_random_uuid()::text, '-', ''), p_event_id)
  returning id into v_room;

  if p_visibility = 'password' then
    insert into app_private.room_secrets (room_id, password_hash)
    values (v_room, extensions.crypt(p_password, extensions.gen_salt('bf', 10)));
  end if;

  insert into public.room_members (room_id, user_id, role, rtc_uid)
  values (v_room, v_uid, 'owner', app_private.rtc_uid_for(v_uid));

  if p_event_id is not null then
    update public.room_events set status = 'live', room_id = v_room where id = p_event_id;
    insert into public.notifications (user_id, kind, actor_id, entity_type, entity_id)
    select s.user_id, 'event_reminder', v_uid, 'room', v_room::text
    from public.room_event_subscribers s where s.event_id = p_event_id and s.user_id <> v_uid;
  end if;
  return app_private.room_state(v_room, v_uid);
end;
$$;

create or replace function public.join_room(p_room uuid, p_password text default null, p_invite_code text default null)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_r public.rooms;
  v_hash text;
  v_count int;
  v_invite public.room_invites;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'matching');
  select * into v_r from public.rooms where id = p_room for update;
  if not found or v_r.status <> 'open' then
    perform app_private.err('room_closed');
  end if;
  if exists (select 1 from public.room_bans where room_id = p_room and user_id = v_uid) then
    perform app_private.err('room_banned');
  end if;
  if v_r.owner_id is not null and app_private.is_blocked(v_r.owner_id, v_uid) then
    perform app_private.err('not_found');
  end if;
  if exists (select 1 from public.room_members where room_id = p_room and user_id = v_uid) then
    update public.room_members set last_seen_at = now() where room_id = p_room and user_id = v_uid;
    return app_private.room_state(p_room, v_uid);
  end if;
  if app_private.active_session_of(v_uid) is not null then
    perform app_private.err('already_in_call');
  end if;

  if v_r.visibility = 'password' and v_r.owner_id is distinct from v_uid then
    perform app_private.rate_limit('room_password:' || p_room::text, 5, interval '10 minutes');
    select password_hash into v_hash from app_private.room_secrets where room_id = p_room;
    if v_hash is null or p_password is null or extensions.crypt(p_password, v_hash) <> v_hash then
      perform app_private.err('wrong_room_password');
    end if;
  elsif v_r.visibility = 'invite' and v_r.owner_id is distinct from v_uid then
    select * into v_invite from public.room_invites
    where room_id = p_room and expires_at > now() and uses < max_uses
      and (invitee_id = v_uid or (p_invite_code is not null and code = p_invite_code))
    order by invitee_id is null
    limit 1 for update;
    if not found then
      perform app_private.err('invite_required');
    end if;
    update public.room_invites set uses = uses + 1 where id = v_invite.id;
  end if;

  select count(*) into v_count from public.room_members where room_id = p_room;
  if v_count >= v_r.max_participants then
    perform app_private.err('room_full');
  end if;

  delete from public.room_members where user_id = v_uid and room_id <> p_room;
  insert into public.room_members (room_id, user_id, role, rtc_uid)
  values (p_room, v_uid,
          case when v_r.owner_id = v_uid then 'owner'::public.room_role else 'listener'::public.room_role end,
          app_private.rtc_uid_for(v_uid));
  update public.rooms set empty_since = null,
    owner_left_at = case when owner_id = v_uid then null else owner_left_at end
  where id = p_room;
  return app_private.room_state(p_room, v_uid);
end;
$$;

-- Ownership rules when the owner leaves (explicitly, or by timing out):
--   1. longest-present moderator, else 2. longest-present speaker becomes owner;
--   3. if nobody holds a speaking role the room closes.
create or replace function app_private.transfer_or_close(p_room uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_next uuid;
begin
  select user_id into v_next from public.room_members
  where room_id = p_room and role in ('moderator', 'speaker')
  order by case role when 'moderator' then 0 else 1 end, joined_at
  limit 1;
  if v_next is null then
    update public.rooms set status = 'closed', closed_at = now(), close_reason = 'owner_left' where id = p_room and status = 'open';
    delete from public.room_members where room_id = p_room;
  else
    update public.room_members set role = 'owner' where room_id = p_room and user_id = v_next;
    update public.rooms set owner_id = v_next, owner_left_at = null where id = p_room;
  end if;
end;
$$;

create or replace function public.leave_room(p_room uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_role public.room_role;
begin
  perform 1 from public.rooms where id = p_room for update;
  delete from public.room_members where room_id = p_room and user_id = v_uid returning role into v_role;
  update public.room_speaker_requests set status = 'cancelled', resolved_at = now()
  where room_id = p_room and user_id = v_uid and status = 'pending';
  if v_role = 'owner' then
    perform app_private.transfer_or_close(p_room);
  end if;
  update public.rooms set empty_since = now()
  where id = p_room and status = 'open' and not exists (select 1 from public.room_members where room_id = p_room);
end;
$$;

create or replace function public.room_heartbeat(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  update public.room_members set last_seen_at = now() where room_id = p_room and user_id = v_uid;
  if not found then
    perform app_private.err('not_room_member');
  end if;
  perform app_private.sweep_rooms();
  return app_private.room_state(p_room, v_uid);
end;
$$;

create or replace function public.get_room(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if not app_private.can_see_room(p_room, v_uid) then
    perform app_private.err('not_found');
  end if;
  return app_private.room_state(p_room, v_uid);
end;
$$;

create or replace function public.set_self_muted(p_room uuid, p_muted boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_m public.room_members;
begin
  select * into v_m from public.room_members where room_id = p_room and user_id = v_uid for update;
  if not found then
    perform app_private.err('not_room_member');
  end if;
  if not p_muted and (v_m.role = 'listener' or v_m.muted_by_moderator) then
    perform app_private.err('cannot_unmute');
  end if;
  update public.room_members set self_muted = p_muted where room_id = p_room and user_id = v_uid;
end;
$$;

create or replace function public.raise_hand(p_room uuid, p_raised boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_r public.rooms;
  v_role public.room_role := app_private.room_role_of(p_room, v_uid);
  v_speakers int;
begin
  if v_role is null then
    perform app_private.err('not_room_member');
  end if;
  if v_role <> 'listener' then
    return;
  end if;
  select * into v_r from public.rooms where id = p_room for update;
  if not p_raised then
    update public.room_members set hand_raised_at = null where room_id = p_room and user_id = v_uid;
    update public.room_speaker_requests set status = 'cancelled', resolved_at = now()
    where room_id = p_room and user_id = v_uid and status = 'pending' and kind = 'hand';
    return;
  end if;
  perform app_private.rate_limit('raise_hand', 20, interval '10 minutes');
  if not v_r.hand_raise_required then
    select count(*) into v_speakers from public.room_members where room_id = p_room and role <> 'listener';
    if v_speakers >= v_r.max_speakers then
      perform app_private.err('speakers_full');
    end if;
    -- Open stage: become a speaker directly, still muted until the user unmutes.
    update public.room_members set role = 'speaker', hand_raised_at = null, self_muted = true
    where room_id = p_room and user_id = v_uid;
    return;
  end if;
  update public.room_members set hand_raised_at = now() where room_id = p_room and user_id = v_uid;
  insert into public.room_speaker_requests (room_id, user_id, kind) values (p_room, v_uid, 'hand')
  on conflict do nothing;
end;
$$;

-- Moderator approves a raised hand or invites a listener. The listener must accept an invite.
create or replace function public.invite_to_speak(p_room uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_r public.rooms;
  v_speakers int;
  v_hand uuid;
begin
  perform app_private.require_room_moderator(p_room, v_uid);
  select * into v_r from public.rooms where id = p_room for update;
  if app_private.room_role_of(p_room, p_user) is distinct from 'listener' then
    perform app_private.err('invalid_target');
  end if;
  select count(*) into v_speakers from public.room_members where room_id = p_room and role <> 'listener';
  if v_speakers >= v_r.max_speakers then
    perform app_private.err('speakers_full');
  end if;
  select id into v_hand from public.room_speaker_requests
  where room_id = p_room and user_id = p_user and status = 'pending' and kind = 'hand';
  if v_hand is not null then
    -- The user asked to speak: approving promotes them (mic stays muted until they unmute).
    update public.room_speaker_requests set status = 'accepted', resolved_at = now() where id = v_hand;
    update public.room_members set role = 'speaker', hand_raised_at = null, self_muted = true
    where room_id = p_room and user_id = p_user;
  else
    insert into public.room_speaker_requests (room_id, user_id, kind, invited_by) values (p_room, p_user, 'invite', v_uid)
    on conflict do nothing;
  end if;
end;
$$;

create or replace function public.respond_speaker_invite(p_room uuid, p_accept boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_req uuid;
  v_r public.rooms;
  v_speakers int;
begin
  select * into v_r from public.rooms where id = p_room for update;
  select id into v_req from public.room_speaker_requests
  where room_id = p_room and user_id = v_uid and status = 'pending' and kind = 'invite' for update;
  if v_req is null then
    perform app_private.err('not_found');
  end if;
  if p_accept then
    select count(*) into v_speakers from public.room_members where room_id = p_room and role <> 'listener';
    if v_speakers >= v_r.max_speakers then
      perform app_private.err('speakers_full');
    end if;
    update public.room_members set role = 'speaker', hand_raised_at = null, self_muted = true
    where room_id = p_room and user_id = v_uid and role = 'listener';
  end if;
  update public.room_speaker_requests set status = case when p_accept then 'accepted'::public.speaker_request_status
    else 'declined'::public.speaker_request_status end, resolved_at = now() where id = v_req;
  return app_private.room_state(p_room, v_uid);
end;
$$;

create or replace function public.decline_hand(p_room uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_room_moderator(p_room, app_private.require_user());
  update public.room_speaker_requests set status = 'declined', resolved_at = now()
  where room_id = p_room and user_id = p_user and status = 'pending' and kind = 'hand';
  update public.room_members set hand_raised_at = null where room_id = p_room and user_id = p_user;
end;
$$;

create or replace function public.set_member_role(p_room uuid, p_user uuid, p_role public.room_role)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_my_role public.room_role := app_private.require_room_moderator(p_room, v_uid);
  v_target public.room_role := app_private.room_role_of(p_room, p_user);
  v_r public.rooms;
  v_speakers int;
begin
  if v_target is null or p_user = v_uid then
    perform app_private.err('invalid_target');
  end if;
  if p_role = 'owner' then
    perform app_private.err('forbidden');
  end if;
  -- Moderators cannot change other moderators or the owner; only the owner appoints moderators.
  if v_my_role = 'moderator' and (v_target in ('owner', 'moderator') or p_role = 'moderator') then
    perform app_private.err('forbidden');
  end if;
  if v_target = 'owner' then
    perform app_private.err('forbidden');
  end if;
  select * into v_r from public.rooms where id = p_room for update;
  if p_role in ('speaker', 'moderator') and v_target = 'listener' then
    select count(*) into v_speakers from public.room_members where room_id = p_room and role <> 'listener';
    if v_speakers >= v_r.max_speakers then
      perform app_private.err('speakers_full');
    end if;
    if p_role = 'speaker' then
      -- Becoming a speaker is always the user's choice: send an invite instead.
      perform public.invite_to_speak(p_room, p_user);
      return;
    end if;
  end if;
  update public.room_members set role = p_role,
    self_muted = case when p_role = 'listener' then true else self_muted end,
    hand_raised_at = null
  where room_id = p_room and user_id = p_user;
  perform app_private.audit('room_set_role', 'room', p_room::text, null, jsonb_build_object('user', p_user, 'role', p_role));
end;
$$;

create or replace function public.moderator_mute(p_room uuid, p_user uuid, p_muted boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_my_role public.room_role := app_private.require_room_moderator(p_room, v_uid);
  v_target public.room_role := app_private.room_role_of(p_room, p_user);
begin
  if v_target is null or v_target = 'owner' or (v_my_role = 'moderator' and v_target = 'moderator') then
    perform app_private.err('forbidden');
  end if;
  -- Lifting a moderator mute never opens the microphone; the user decides that.
  update public.room_members set muted_by_moderator = p_muted,
    self_muted = case when p_muted then true else self_muted end
  where room_id = p_room and user_id = p_user;
end;
$$;

create or replace function public.kick_from_room(p_room uuid, p_user uuid, p_ban boolean, p_reason text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_my_role public.room_role := app_private.require_room_moderator(p_room, v_uid);
  v_target public.room_role := app_private.room_role_of(p_room, p_user);
begin
  if p_user = v_uid or v_target = 'owner' or (v_my_role = 'moderator' and v_target = 'moderator') then
    perform app_private.err('forbidden');
  end if;
  delete from public.room_members where room_id = p_room and user_id = p_user;
  update public.room_speaker_requests set status = 'cancelled', resolved_at = now()
  where room_id = p_room and user_id = p_user and status = 'pending';
  if p_ban then
    insert into public.room_bans (room_id, user_id, banned_by, reason) values (p_room, p_user, v_uid, left(p_reason, 300))
    on conflict (room_id, user_id) do update set reason = excluded.reason, banned_by = excluded.banned_by;
  end if;
  perform app_private.audit(case when p_ban then 'room_ban' else 'room_kick' end, 'room', p_room::text, left(p_reason, 300),
                            jsonb_build_object('user', p_user));
end;
$$;

create or replace function public.unban_from_room(p_room uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_room_moderator(p_room, app_private.require_user());
  delete from public.room_bans where room_id = p_room and user_id = p_user;
  perform app_private.audit('room_unban', 'room', p_room::text, null, jsonb_build_object('user', p_user));
end;
$$;

create or replace function public.close_room(p_room uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if app_private.room_role_of(p_room, v_uid) is distinct from 'owner' and not public.is_staff() then
    perform app_private.err('forbidden');
  end if;
  update public.rooms set status = 'closed', closed_at = now(),
    close_reason = case when public.is_staff() and app_private.room_role_of(p_room, v_uid) is distinct from 'owner'
                        then 'moderation' else 'owner_closed' end
  where id = p_room and status = 'open';
  delete from public.room_members where room_id = p_room;
  update public.room_events set status = 'finished' where room_id = p_room and status = 'live';
  perform app_private.audit('room_close', 'room', p_room::text);
end;
$$;

create or replace function public.update_room(p_room uuid, p_title text, p_description text, p_hand_raise_required boolean, p_text_chat_enabled boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.require_room_moderator(p_room, v_uid);
  update public.rooms set title = btrim(p_title), description = coalesce(btrim(p_description), ''),
    hand_raise_required = p_hand_raise_required, text_chat_enabled = p_text_chat_enabled
  where id = p_room and status = 'open';
  return app_private.room_state(p_room, v_uid);
end;
$$;

create or replace function public.create_room_invite(p_room uuid, p_user uuid default null, p_max_uses int default 1)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_role public.room_role := app_private.room_role_of(p_room, v_uid);
  v_code text;
  v_id uuid;
begin
  if v_role is null then
    perform app_private.err('not_room_member');
  end if;
  perform app_private.rate_limit('room_invite', 50, interval '1 hour');
  if p_user is not null then
    if app_private.is_blocked(v_uid, p_user) then
      perform app_private.err('not_found');
    end if;
    insert into public.room_invites (room_id, inviter_id, invitee_id, max_uses) values (p_room, v_uid, p_user, 1)
    returning id into v_id;
    perform app_private.notify(p_user, 'room_invite', v_uid, 'room', p_room::text, '{}'::jsonb, 'room_invites');
  else
    if v_role not in ('owner', 'moderator') and (select visibility from public.rooms where id = p_room) = 'invite' then
      perform app_private.err('forbidden');
    end if;
    v_code := encode(extensions.gen_random_bytes(9), 'base64');
    v_code := translate(v_code, '+/=', '-_');
    insert into public.room_invites (room_id, inviter_id, code, max_uses) values (p_room, v_uid, v_code, least(greatest(coalesce(p_max_uses, 25), 1), 500))
    returning id into v_id;
  end if;
  return jsonb_build_object('id', v_id, 'code', v_code, 'room_id', p_room);
end;
$$;

create or replace function public.send_room_reaction(p_room uuid, p_kind text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if not app_private.is_room_member(p_room, v_uid) then
    perform app_private.err('not_room_member');
  end if;
  if p_kind not in ('clap', 'heart', 'laugh', 'wow', 'fire') then
    perform app_private.err('invalid_reaction');
  end if;
  perform app_private.rate_limit('room_reaction', 30, interval '1 minute');
  insert into public.room_reactions (room_id, user_id, kind) values (p_room, v_uid, p_kind);
end;
$$;

create or replace function public.set_room_favorite(p_room uuid, p_favorite boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if p_favorite then
    if not app_private.can_see_room(p_room, v_uid) then
      perform app_private.err('not_found');
    end if;
    insert into public.room_favorites (user_id, room_id) values (v_uid, p_room) on conflict do nothing;
  else
    delete from public.room_favorites where user_id = v_uid and room_id = p_room;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Events
-- ---------------------------------------------------------------------------
create or replace function public.create_room_event(p_title text, p_description text, p_topic text, p_language text, p_starts_at timestamptz)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_id uuid;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'room_creation');
  perform app_private.rate_limit('create_event', 10, interval '1 day');
  if p_starts_at < now() + interval '5 minutes' or p_starts_at > now() + interval '60 days' then
    perform app_private.err('invalid_event_time');
  end if;
  insert into public.room_events (created_by, title, description, topic, language_code, starts_at)
  values (v_uid, btrim(p_title), coalesce(btrim(p_description), ''), p_topic, lower(p_language), p_starts_at)
  returning id into v_id;
  insert into public.room_event_subscribers (event_id, user_id) values (v_id, v_uid);
  return v_id;
end;
$$;

create or replace function public.set_event_subscription(p_event uuid, p_subscribed boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if p_subscribed then
    insert into public.room_event_subscribers (event_id, user_id)
    select id, v_uid from public.room_events where id = p_event and status = 'scheduled'
    on conflict do nothing;
  else
    delete from public.room_event_subscribers where event_id = p_event and user_id = v_uid;
  end if;
end;
$$;

create or replace function public.cancel_room_event(p_event uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  update public.room_events set status = 'cancelled'
  where id = p_event and status = 'scheduled' and (created_by = v_uid or public.is_staff());
  if not found then
    perform app_private.err('not_found');
  end if;
end;
$$;

create or replace function public.list_room_events()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'id', e.id, 'title', e.title, 'description', e.description, 'topic', e.topic, 'language_code', e.language_code,
      'starts_at', e.starts_at, 'status', e.status, 'room_id', e.room_id,
      'is_mine', e.created_by = auth.uid(),
      'subscribed', exists (select 1 from public.room_event_subscribers s where s.event_id = e.id and s.user_id = auth.uid()),
      'subscriber_count', (select count(*) from public.room_event_subscribers s where s.event_id = e.id),
      'host', (select jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_path', p.avatar_path)
               from public.profiles p where p.id = e.created_by)
    ) order by e.starts_at), '[]'::jsonb)
  from public.room_events e
  where e.status in ('scheduled', 'live') and e.starts_at > now() - interval '6 hours'
    and (e.created_by is null or not app_private.is_blocked(e.created_by, auth.uid()));
$$;

-- ---------------------------------------------------------------------------
-- Room sweeper
-- ---------------------------------------------------------------------------
create or replace function app_private.sweep_rooms()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  r record;
begin
  -- Members whose app stopped heartbeating.
  for r in select room_id, user_id, role from public.room_members
           where last_seen_at < now() - interval '60 seconds' loop
    delete from public.room_members where room_id = r.room_id and user_id = r.user_id;
    if r.role = 'owner' then
      update public.rooms set owner_left_at = coalesce(owner_left_at, now()) where id = r.room_id;
    end if;
  end loop;

  -- Owner gone for 2 minutes (not coming back): transfer or close.
  for r in select id from public.rooms
           where status = 'open' and owner_left_at is not null and owner_left_at < now() - interval '2 minutes'
             and not exists (select 1 from public.room_members m where m.room_id = rooms.id and m.role = 'owner')
           for update skip locked loop
    perform app_private.transfer_or_close(r.id);
  end loop;

  update public.rooms set empty_since = now()
  where status = 'open' and empty_since is null
    and not exists (select 1 from public.room_members m where m.room_id = rooms.id);

  update public.rooms set status = 'closed', closed_at = now(), close_reason = 'empty'
  where status = 'open' and empty_since < now() - interval '2 minutes'
    and not exists (select 1 from public.room_members m where m.room_id = rooms.id);

  delete from public.room_reactions where created_at < now() - interval '2 minutes';

  -- Event reminders 10 minutes before start.
  for r in select id, created_by from public.room_events
           where status = 'scheduled' and reminder_sent_at is null and starts_at < now() + interval '10 minutes'
           for update skip locked loop
    update public.room_events set reminder_sent_at = now() where id = r.id;
    perform app_private.notify(s.user_id, 'event_reminder', r.created_by, 'room_event', r.id::text, '{}'::jsonb, 'events')
    from public.room_event_subscribers s where s.event_id = r.id;
  end loop;

  update public.room_events set status = 'finished'
  where status = 'scheduled' and starts_at < now() - interval '6 hours';
end;
$$;
