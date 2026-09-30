-- Sit & Talk — server-side matchmaking queue, matches, call sessions, extensions,
-- direct (friend) calls and post-call feedback.
--
-- Consistency model
-- * Pairing happens inside a transaction that locks both queue rows
--   (FOR UPDATE / SKIP LOCKED), so a user can never be in two matches.
-- * Every state transition is guarded by a row lock on the match / session.
-- * Clients call heartbeat RPCs; those RPCs also run `app_private.sweep()`
--   opportunistically, so cleanup never depends on Android lifecycle callbacks.
--   pg_cron (if available) runs the same sweep every minute as a backstop.
-- * The server clock decides all deadlines.

create type public.talk_mode as enum ('voice', 'video', 'text');
create type public.queue_status as enum ('waiting', 'matched');
create type public.match_status as enum ('pending', 'accepted', 'declined', 'expired', 'cancelled');
create type public.call_kind as enum ('random', 'direct');
create type public.call_status as enum ('ringing', 'connecting', 'active', 'ended');

-- Stable, server-assigned Agora UID per user. Never taken from the client.
create table app_private.rtc_ids (
  user_id uuid primary key references auth.users(id) on delete cascade,
  rtc_uid int not null unique check (rtc_uid > 0)
);

create or replace function app_private.rtc_uid_for(p_user uuid)
returns int
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid int;
begin
  select rtc_uid into v_uid from app_private.rtc_ids where user_id = p_user;
  if v_uid is not null then
    return v_uid;
  end if;
  loop
    v_uid := 1 + floor(random() * 2147483646)::int;
    begin
      insert into app_private.rtc_ids (user_id, rtc_uid) values (p_user, v_uid);
      return v_uid;
    exception when unique_violation then
      select rtc_uid into v_uid from app_private.rtc_ids where user_id = p_user;
      if v_uid is not null then
        return v_uid;
      end if;
    end;
  end loop;
end;
$$;

create table public.matchmaking_queue (
  user_id uuid primary key references auth.users(id) on delete cascade,
  mode public.talk_mode not null,
  intent text not null default 'chat' check (intent in ('friends', 'chat', 'gaming', 'language', 'topic', 'listen')),
  alias text not null check (char_length(btrim(alias)) between 2 and 32),
  languages text[] not null default '{}',
  strict_language boolean not null default false,
  interests text[] not null default '{}',
  status public.queue_status not null default 'waiting',
  match_id uuid,
  joined_at timestamptz not null default now(),
  heartbeat_at timestamptz not null default now()
);
create index matchmaking_queue_waiting_idx on public.matchmaking_queue (mode, joined_at) where status = 'waiting';

create table public.matches (
  id uuid primary key default gen_random_uuid(),
  mode public.talk_mode not null,
  user_a uuid not null references auth.users(id) on delete cascade,
  user_b uuid not null references auth.users(id) on delete cascade,
  alias_a text not null,
  alias_b text not null,
  common_interests text[] not null default '{}',
  status public.match_status not null default 'pending',
  a_accepted_at timestamptz,
  b_accepted_at timestamptz,
  expires_at timestamptz not null,
  session_id uuid,
  created_at timestamptz not null default now(),
  check (user_a <> user_b)
);
create index matches_pair_idx on public.matches (least(user_a, user_b), greatest(user_a, user_b), created_at desc);
create index matches_pending_idx on public.matches (expires_at) where status = 'pending';

create table public.call_sessions (
  id uuid primary key default gen_random_uuid(),
  kind public.call_kind not null,
  mode public.talk_mode not null,
  initial_mode public.talk_mode not null,
  channel_name text not null unique,
  status public.call_status not null,
  match_id uuid references public.matches(id) on delete set null,
  ring_expires_at timestamptz,
  connect_deadline timestamptz,
  started_at timestamptz,
  ends_at timestamptz,
  ended_at timestamptz,
  end_reason text,
  typing_slot smallint,
  typing_until timestamptz,
  extension_requested_slot smallint,
  extension_requested_at timestamptz,
  extensions_applied int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index call_sessions_open_idx on public.call_sessions (status) where status <> 'ended';

create table public.call_participants (
  session_id uuid not null references public.call_sessions(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  slot smallint not null check (slot in (1, 2)),
  alias text not null,
  rtc_uid int not null,
  upgrade_consent public.talk_mode,
  joined_rtc_at timestamptz,
  last_seen_at timestamptz not null default now(),
  left_at timestamptz,
  primary key (session_id, user_id),
  unique (session_id, slot)
);
create index call_participants_user_idx on public.call_participants (user_id) where left_at is null;

create table public.call_extension_requests (
  id bigint generated always as identity primary key,
  session_id uuid not null references public.call_sessions(id) on delete cascade,
  requested_by uuid references auth.users(id) on delete set null,
  approved_by uuid references auth.users(id) on delete set null,
  seconds int not null,
  requested_at timestamptz not null default now(),
  applied_at timestamptz
);
create index call_extension_requests_session_idx on public.call_extension_requests (session_id);

create table public.call_feedback (
  session_id uuid not null references public.call_sessions(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  wants_again boolean not null,
  rating smallint check (rating between 1 and 5),
  created_at timestamptz not null default now(),
  primary key (session_id, user_id)
);

-- Anonymous text-introduction messages. Senders are identified only by slot (1/2),
-- so peers never learn each other's account ids. Moderators resolve slot -> user.
create table public.match_messages (
  id uuid primary key,
  session_id uuid not null references public.call_sessions(id) on delete cascade,
  sender_slot smallint not null check (sender_slot in (1, 2)),
  body text not null check (char_length(btrim(body)) between 1 and 1000),
  created_at timestamptz not null default now()
);
create index match_messages_session_idx on public.match_messages (session_id, created_at);

create trigger call_sessions_touch before update on public.call_sessions
for each row execute function app_private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
create or replace function app_private.active_session_of(p_user uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select cp.session_id from public.call_participants cp
  join public.call_sessions cs on cs.id = cp.session_id
  where cp.user_id = p_user and cp.left_at is null and cs.status <> 'ended'
  limit 1;
$$;

create or replace function app_private.in_room(p_user uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  return exists (select 1 from public.room_members where user_id = p_user);
end;
$$;

create or replace function app_private.end_session(p_session uuid, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_s public.call_sessions;
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  if not found or v_s.status = 'ended' then
    return;
  end if;
  update public.call_sessions set status = 'ended', ended_at = now(), end_reason = p_reason,
    extension_requested_slot = null, typing_slot = null
  where id = p_session;
  update public.call_participants set left_at = coalesce(left_at, now()), upgrade_consent = null where session_id = p_session;
  if v_s.kind = 'direct' and v_s.status = 'ringing' then
    insert into public.notifications (user_id, kind, actor_id, entity_type, entity_id)
    select callee.user_id, 'missed_call', caller.user_id, 'call', p_session::text
    from public.call_participants callee
    join public.call_participants caller on caller.session_id = callee.session_id and caller.slot = 1
    where callee.session_id = p_session and callee.slot = 2;
    update public.notifications set expires_at = now()
    where kind = 'incoming_call' and entity_id = p_session::text;
  end if;
end;
$$;

create or replace function app_private.daily_match_limit(p_user uuid)
returns int
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  return case when app_private.is_premium(p_user)
              then app_private.setting_int('premium_daily_matches', 300)
              else app_private.setting_int('free_daily_matches', 60) end;
end;
$$;

-- Attempts to pair the caller's waiting queue row. Returns the match id or null.
create or replace function app_private.try_pair(p_user uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_me public.matchmaking_queue;
  v_other public.matchmaking_queue;
  v_match uuid;
  v_common text[];
begin
  select * into v_me from public.matchmaking_queue
  where user_id = p_user and status = 'waiting'
  for update skip locked;
  if not found then
    return null;
  end if;

  select q.* into v_other
  from public.matchmaking_queue q
  where q.status = 'waiting'
    and q.user_id <> p_user
    and q.mode = v_me.mode
    and q.heartbeat_at > now() - interval '30 seconds'
    and not app_private.is_blocked(p_user, q.user_id)
    and not app_private.is_restricted(q.user_id, 'matching')
    -- Avoid re-matching the same pair right away.
    and not exists (
      select 1 from public.matches m
      where least(m.user_a, m.user_b) = least(p_user, q.user_id)
        and greatest(m.user_a, m.user_b) = greatest(p_user, q.user_id)
        and m.created_at > now() - interval '15 minutes')
    -- Hard language filter only when either side asked for it.
    and (
      (not v_me.strict_language and not q.strict_language)
      or v_me.languages && q.languages)
  order by
    (v_me.languages && q.languages) desc,
    (q.intent = v_me.intent) desc,
    cardinality(array(select unnest(q.interests) intersect select unnest(v_me.interests))) desc,
    q.joined_at
  limit 1
  for update of q skip locked;

  if not found then
    return null;
  end if;

  v_common := array(select unnest(v_me.interests) intersect select unnest(v_other.interests));

  insert into public.matches (mode, user_a, user_b, alias_a, alias_b, common_interests, expires_at)
  values (v_me.mode, v_other.user_id, p_user, v_other.alias, v_me.alias, v_common,
          now() + make_interval(secs => app_private.setting_int('match_accept_seconds', 20)))
  returning id into v_match;

  update public.matchmaking_queue set status = 'matched', match_id = v_match
  where user_id in (p_user, v_other.user_id);
  return v_match;
end;
$$;

-- Requeue the users of a failed match who still want to talk.
create or replace function app_private.requeue(p_user uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.matchmaking_queue set status = 'waiting', match_id = null, heartbeat_at = now()
  where user_id = p_user;
$$;

create or replace function app_private.sweep()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  r record;
begin
  -- Stale queue rows (app killed, network lost).
  delete from public.matchmaking_queue
  where status = 'waiting' and heartbeat_at < now() - interval '45 seconds';

  -- Pending matches whose accept window passed.
  for r in select * from public.matches where status = 'pending' and expires_at < now()
           for update skip locked loop
    update public.matches set status = 'expired' where id = r.id;
    -- Whoever accepted goes back to the queue; whoever did not respond leaves it.
    if r.a_accepted_at is not null then perform app_private.requeue(r.user_a);
    else delete from public.matchmaking_queue where user_id = r.user_a and match_id = r.id; end if;
    if r.b_accepted_at is not null then perform app_private.requeue(r.user_b);
    else delete from public.matchmaking_queue where user_id = r.user_b and match_id = r.id; end if;
  end loop;

  -- Unanswered direct calls.
  for r in select id from public.call_sessions
           where status = 'ringing' and ring_expires_at < now() for update skip locked loop
    perform app_private.end_session(r.id, 'missed');
  end loop;

  -- Sessions that never got both sides into the RTC channel.
  for r in select id from public.call_sessions
           where status = 'connecting' and connect_deadline < now() for update skip locked loop
    perform app_private.end_session(r.id, 'connect_failed');
  end loop;

  -- Time is up (3 s grace for an in-flight extension approval).
  for r in select id from public.call_sessions
           where status = 'active' and ends_at is not null and ends_at < now() - interval '3 seconds'
           for update skip locked loop
    perform app_private.end_session(r.id, 'time_up');
  end loop;

  -- A participant stopped heartbeating (process death, lost network).
  for r in select distinct cs.id from public.call_sessions cs
           join public.call_participants cp on cp.session_id = cs.id
           where cs.status in ('active', 'connecting') and cp.left_at is null
             and cp.last_seen_at < now() - interval '60 seconds' loop
    perform app_private.end_session(r.id, 'peer_lost');
  end loop;

  delete from app_private.rate_limits where window_start < now() - interval '2 days';

  perform app_private.sweep_rooms();
end;
$$;

-- ---------------------------------------------------------------------------
-- Queue RPCs
-- ---------------------------------------------------------------------------
create or replace function app_private.queue_state(p_user uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_q public.matchmaking_queue;
  v_m public.matches;
  v_session uuid;
begin
  v_session := app_private.active_session_of(p_user);
  if v_session is not null then
    return jsonb_build_object('state', 'in_session', 'session_id', v_session);
  end if;
  select * into v_q from public.matchmaking_queue where user_id = p_user;
  if not found then
    return jsonb_build_object('state', 'idle');
  end if;
  if v_q.status = 'waiting' then
    return jsonb_build_object('state', 'waiting', 'mode', v_q.mode, 'joined_at', v_q.joined_at);
  end if;
  select * into v_m from public.matches where id = v_q.match_id;
  return jsonb_build_object(
    'state', 'matched',
    'match_id', v_m.id,
    'mode', v_m.mode,
    'peer_alias', case when v_m.user_a = p_user then v_m.alias_b else v_m.alias_a end,
    'common_interests', to_jsonb(v_m.common_interests),
    'expires_at', v_m.expires_at,
    'i_accepted', case when v_m.user_a = p_user then v_m.a_accepted_at is not null else v_m.b_accepted_at is not null end,
    'server_now', now());
end;
$$;

create or replace function public.join_queue(
  p_mode public.talk_mode,
  p_intent text,
  p_alias text,
  p_languages text[],
  p_strict_language boolean
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_interests text[];
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'matching');
  if not app_private.flag(p_mode::text || '_matching') then
    perform app_private.err('feature_disabled', p_mode::text || '_matching');
  end if;
  if app_private.active_session_of(v_uid) is not null then
    perform app_private.err('already_in_call');
  end if;
  if app_private.in_room(v_uid) then
    perform app_private.err('in_room');
  end if;
  if char_length(btrim(coalesce(p_alias, ''))) not between 2 and 32 then
    perform app_private.err('invalid_alias');
  end if;
  perform app_private.rate_limit('join_queue', 30, interval '1 minute');
  perform app_private.rate_limit('daily_matches', app_private.daily_match_limit(v_uid), interval '1 day');

  select coalesce(array_agg(slug), '{}') into v_interests
  from public.user_interests where user_id = v_uid and is_public;

  insert into public.matchmaking_queue as q (user_id, mode, intent, alias, languages, strict_language, interests)
  values (v_uid, p_mode, coalesce(nullif(p_intent, ''), 'chat'), btrim(p_alias),
          coalesce((select array_agg(distinct lower(l)) from unnest(p_languages) l where lower(l) ~ '^[a-z]{2,3}$'), '{}'),
          coalesce(p_strict_language, false), v_interests)
  on conflict (user_id) do update set
    mode = excluded.mode, intent = excluded.intent, alias = excluded.alias, languages = excluded.languages,
    strict_language = excluded.strict_language, interests = excluded.interests, heartbeat_at = now(),
    joined_at = case when q.status = 'waiting' and q.mode = excluded.mode then q.joined_at else now() end
  where q.status = 'waiting';

  perform app_private.try_pair(v_uid);
  return app_private.queue_state(v_uid);
end;
$$;

create or replace function public.queue_heartbeat()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.sweep();
  update public.matchmaking_queue set heartbeat_at = now() where user_id = v_uid;
  perform app_private.try_pair(v_uid);
  return app_private.queue_state(v_uid);
end;
$$;

create or replace function public.leave_queue()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_q public.matchmaking_queue;
  v_m public.matches;
  v_other uuid;
begin
  select * into v_q from public.matchmaking_queue where user_id = v_uid for update;
  if not found then
    return app_private.queue_state(v_uid);
  end if;
  if v_q.status = 'matched' and v_q.match_id is not null then
    select * into v_m from public.matches where id = v_q.match_id for update;
    if found and v_m.status = 'pending' then
      update public.matches set status = 'cancelled' where id = v_m.id;
      v_other := case when v_m.user_a = v_uid then v_m.user_b else v_m.user_a end;
      perform app_private.requeue(v_other);
    end if;
  end if;
  delete from public.matchmaking_queue where user_id = v_uid;
  return app_private.queue_state(v_uid);
end;
$$;

create or replace function public.respond_match(p_match uuid, p_accept boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_m public.matches;
  v_session uuid;
  v_other uuid;
begin
  select * into v_m from public.matches where id = p_match for update;
  if not found or v_uid not in (v_m.user_a, v_m.user_b) then
    perform app_private.err('not_found');
  end if;
  if v_m.status = 'accepted' then
    return app_private.queue_state(v_uid);
  end if;
  if v_m.status <> 'pending' or v_m.expires_at < now() then
    perform app_private.err('match_expired');
  end if;
  v_other := case when v_m.user_a = v_uid then v_m.user_b else v_m.user_a end;

  if not p_accept then
    update public.matches set status = 'declined' where id = p_match;
    delete from public.matchmaking_queue where user_id = v_uid;
    perform app_private.requeue(v_other);
    return app_private.queue_state(v_uid);
  end if;

  if v_m.user_a = v_uid then
    update public.matches set a_accepted_at = now() where id = p_match returning * into v_m;
  else
    update public.matches set b_accepted_at = now() where id = p_match returning * into v_m;
  end if;

  if v_m.a_accepted_at is not null and v_m.b_accepted_at is not null then
    insert into public.call_sessions (kind, mode, initial_mode, channel_name, status, match_id, connect_deadline)
    values ('random', v_m.mode, v_m.mode, 'st_' || replace(gen_random_uuid()::text, '-', ''), 'connecting', v_m.id,
            now() + interval '45 seconds')
    returning id into v_session;
    insert into public.call_participants (session_id, user_id, slot, alias, rtc_uid) values
      (v_session, v_m.user_a, 1, v_m.alias_a, app_private.rtc_uid_for(v_m.user_a)),
      (v_session, v_m.user_b, 2, v_m.alias_b, app_private.rtc_uid_for(v_m.user_b));
    if v_m.mode = 'text' then
      -- Text introductions need no RTC: the session is live immediately.
      update public.call_sessions set status = 'active', started_at = now(), connect_deadline = null,
        ends_at = now() + make_interval(secs => app_private.setting_int('call_initial_seconds', 180))
      where id = v_session;
    end if;
    update public.matches set status = 'accepted', session_id = v_session where id = p_match;
    delete from public.matchmaking_queue where user_id in (v_m.user_a, v_m.user_b);
  end if;
  return app_private.queue_state(v_uid);
end;
$$;

-- ---------------------------------------------------------------------------
-- Session RPCs
-- ---------------------------------------------------------------------------
create or replace function app_private.session_state(p_session uuid, p_user uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_s public.call_sessions;
  v_me public.call_participants;
  v_peer public.call_participants;
  v_peer_profile jsonb;
  v_avatar text;
  v_share boolean;
  v_m public.matches;
begin
  select * into v_s from public.call_sessions where id = p_session;
  select * into v_me from public.call_participants where session_id = p_session and user_id = p_user;
  if v_s.id is null or v_me.user_id is null then
    perform app_private.err('not_found');
  end if;
  select * into v_peer from public.call_participants where session_id = p_session and user_id <> p_user;

  if v_s.kind = 'direct' then
    select jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name, 'avatar_path', p.avatar_path)
      into v_peer_profile from public.profiles p where p.id = v_peer.user_id;
  else
    -- Random sessions stay anonymous: alias, optional avatar and shared public interests only.
    select up.share_avatar_in_random, p.avatar_path into v_share, v_avatar
    from public.user_private up join public.profiles p on p.id = up.user_id where up.user_id = v_peer.user_id;
    select * into v_m from public.matches where id = v_s.match_id;
  end if;

  return jsonb_build_object(
    'session_id', v_s.id,
    'kind', v_s.kind,
    'mode', v_s.mode,
    'status', v_s.status,
    'end_reason', v_s.end_reason,
    'my_slot', v_me.slot,
    'my_alias', v_me.alias,
    'i_left', v_me.left_at is not null,
    'my_upgrade_request', v_me.upgrade_consent,
    'peer_alias', v_peer.alias,
    'peer_rtc_uid', v_peer.rtc_uid,
    'peer_avatar_path', case when v_s.kind = 'direct' then v_peer_profile ->> 'avatar_path'
                             when v_share then v_avatar end,
    'peer_profile', v_peer_profile,
    'peer_joined', v_peer.joined_rtc_at is not null,
    'peer_upgrade_request', v_peer.upgrade_consent,
    'peer_typing', v_s.typing_slot = v_peer.slot and v_s.typing_until > now(),
    'common_interests', coalesce(to_jsonb(v_m.common_interests), '[]'::jsonb),
    'started_at', v_s.started_at,
    'ends_at', v_s.ends_at,
    'ring_expires_at', v_s.ring_expires_at,
    'extension_requested_by_me', v_s.extension_requested_slot = v_me.slot,
    'extension_requested_by_peer', v_s.extension_requested_slot = v_peer.slot,
    'extensions_applied', v_s.extensions_applied,
    'feedback_given', exists (select 1 from public.call_feedback f where f.session_id = v_s.id and f.user_id = p_user),
    'server_now', now());
end;
$$;

create or replace function public.get_call_state(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  return app_private.session_state(p_session, app_private.require_user());
end;
$$;

create or replace function public.current_call()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_session uuid;
begin
  v_session := app_private.active_session_of(v_uid);
  if v_session is null then
    return null;
  end if;
  return app_private.session_state(v_session, v_uid);
end;
$$;

-- Called by the client after Agora onJoinChannelSuccess. The clock starts only once both sides joined.
create or replace function public.mark_rtc_joined(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  if not found or not exists (select 1 from public.call_participants where session_id = p_session and user_id = v_uid and left_at is null) then
    perform app_private.err('not_found');
  end if;
  update public.call_participants set joined_rtc_at = coalesce(joined_rtc_at, now()), last_seen_at = now()
  where session_id = p_session and user_id = v_uid;
  if v_s.status = 'connecting'
     and not exists (select 1 from public.call_participants where session_id = p_session and joined_rtc_at is null) then
    update public.call_sessions set status = 'active', started_at = now(), connect_deadline = null,
      ends_at = case when v_s.kind = 'random'
                     then now() + make_interval(secs => app_private.setting_int('call_initial_seconds', 180)) end
    where id = p_session;
  end if;
  return app_private.session_state(p_session, v_uid);
end;
$$;

create or replace function public.call_heartbeat(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  update public.call_participants set last_seen_at = now()
  where session_id = p_session and user_id = v_uid and left_at is null;
  perform app_private.sweep();
  return app_private.session_state(p_session, v_uid);
end;
$$;

create or replace function public.end_call(p_session uuid, p_reason text default 'hangup')
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  if not found or not exists (select 1 from public.call_participants where session_id = p_session and user_id = v_uid) then
    perform app_private.err('not_found');
  end if;
  perform app_private.end_session(p_session,
    case when v_s.status = 'ringing' and exists (select 1 from public.call_participants where session_id = p_session and user_id = v_uid and slot = 2)
         then 'declined'
         when p_reason in ('hangup', 'next', 'report', 'block', 'rtc_failed', 'permission_denied') then p_reason
         else 'hangup' end);
  return app_private.session_state(p_session, v_uid);
end;
$$;

-- Both sides must request; the second request applies the extension atomically.
create or replace function public.request_extension(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
  v_me public.call_participants;
  v_secs int := app_private.setting_int('call_extension_seconds', 300);
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  select * into v_me from public.call_participants where session_id = p_session and user_id = v_uid and left_at is null;
  if v_s.id is null or v_me.user_id is null then
    perform app_private.err('not_found');
  end if;
  if v_s.status <> 'active' or v_s.ends_at is null then
    perform app_private.err('call_not_extendable');
  end if;
  if v_s.ends_at < now() - interval '3 seconds' then
    perform app_private.end_session(p_session, 'time_up');
    perform app_private.err('call_ended');
  end if;
  if v_s.extension_requested_slot is null then
    update public.call_sessions set extension_requested_slot = v_me.slot, extension_requested_at = now() where id = p_session;
    insert into public.call_extension_requests (session_id, requested_by, seconds) values (p_session, v_uid, v_secs);
  elsif v_s.extension_requested_slot <> v_me.slot then
    update public.call_sessions set
      ends_at = greatest(ends_at, now()) + make_interval(secs => v_secs),
      extension_requested_slot = null, extension_requested_at = null,
      extensions_applied = extensions_applied + 1
    where id = p_session;
    update public.call_extension_requests set approved_by = v_uid, applied_at = now()
    where id = (select max(id) from public.call_extension_requests where session_id = p_session and applied_at is null);
  end if;
  -- Same side asking twice is idempotent.
  return app_private.session_state(p_session, v_uid);
end;
$$;

create or replace function public.withdraw_extension(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  update public.call_sessions cs set extension_requested_slot = null, extension_requested_at = null
  from public.call_participants cp
  where cs.id = p_session and cp.session_id = cs.id and cp.user_id = v_uid and cs.extension_requested_slot = cp.slot;
  return app_private.session_state(p_session, v_uid);
end;
$$;

-- Moving text -> voice -> video needs both participants' explicit consent to the same mode.
-- Either side may always step down from video to voice (turning the camera off is never blocked).
create or replace function public.request_mode_change(p_session uuid, p_mode public.talk_mode)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
  v_me public.call_participants;
  v_rank_current int;
  v_rank_target int;
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  select * into v_me from public.call_participants where session_id = p_session and user_id = v_uid and left_at is null;
  if v_s.id is null or v_me.user_id is null then
    perform app_private.err('not_found');
  end if;
  if v_s.status <> 'active' then
    perform app_private.err('invalid_state');
  end if;
  v_rank_current := case v_s.mode when 'text' then 0 when 'voice' then 1 else 2 end;
  v_rank_target := case p_mode when 'text' then 0 when 'voice' then 1 else 2 end;

  if v_rank_target < v_rank_current then
    if not (v_s.mode = 'video' and p_mode = 'voice') then
      perform app_private.err('invalid_mode');
    end if;
    update public.call_sessions set mode = 'voice' where id = p_session;
    update public.call_participants set upgrade_consent = null where session_id = p_session;
    return app_private.session_state(p_session, v_uid);
  end if;
  if v_rank_target = v_rank_current then
    -- Withdraw a pending request.
    update public.call_participants set upgrade_consent = null where session_id = p_session and user_id = v_uid;
    return app_private.session_state(p_session, v_uid);
  end if;
  if p_mode = 'video' and v_s.kind = 'random' and not app_private.flag('video_matching') then
    perform app_private.err('feature_disabled', 'video_matching');
  end if;
  perform app_private.rate_limit('mode_change', 10, interval '5 minutes');
  update public.call_participants set upgrade_consent = p_mode where session_id = p_session and user_id = v_uid;
  if exists (select 1 from public.call_participants
             where session_id = p_session and user_id <> v_uid and left_at is null and upgrade_consent = p_mode) then
    update public.call_sessions set mode = p_mode where id = p_session;
    update public.call_participants set upgrade_consent = null where session_id = p_session;
  end if;
  return app_private.session_state(p_session, v_uid);
end;
$$;

create or replace function public.send_match_message(p_session uuid, p_id uuid, p_body text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
  v_me public.call_participants;
begin
  select * into v_s from public.call_sessions where id = p_session;
  select * into v_me from public.call_participants where session_id = p_session and user_id = v_uid and left_at is null;
  if v_s.id is null or v_me.user_id is null or v_s.status <> 'active' then
    perform app_private.err('call_ended');
  end if;
  perform app_private.require_not_restricted(v_uid, 'messaging');
  perform app_private.rate_limit('match_message', 40, interval '1 minute');
  insert into public.match_messages (id, session_id, sender_slot, body) values (p_id, p_session, v_me.slot, btrim(p_body))
  on conflict (id) do nothing;
  update public.call_sessions set typing_slot = null where id = p_session and typing_slot = v_me.slot;
end;
$$;

create or replace function public.set_match_typing(p_session uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.call_sessions cs set typing_slot = cp.slot, typing_until = now() + interval '5 seconds'
  from public.call_participants cp
  where cs.id = p_session and cp.session_id = cs.id and cp.user_id = auth.uid() and cp.left_at is null and cs.status = 'active';
$$;

create or replace function public.list_match_messages(p_session uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', mm.id, 'mine', mm.sender_slot = me.slot, 'body', mm.body,
                                               'created_at', mm.created_at) order by mm.created_at), '[]'::jsonb)
  from public.match_messages mm
  join public.call_participants me on me.session_id = mm.session_id and me.user_id = auth.uid()
  where mm.session_id = p_session;
$$;

-- Private post-call choice. Mutual "yes" connects both people as friends.
create or replace function public.submit_call_feedback(p_session uuid, p_wants_again boolean, p_rating smallint default null)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_peer uuid;
  v_mutual boolean := false;
  v_s public.call_sessions;
begin
  select * into v_s from public.call_sessions where id = p_session;
  if not found or not exists (select 1 from public.call_participants where session_id = p_session and user_id = v_uid) then
    perform app_private.err('not_found');
  end if;
  if v_s.kind <> 'random' or v_s.started_at is null then
    perform app_private.err('invalid_state');
  end if;
  select user_id into v_peer from public.call_participants where session_id = p_session and user_id <> v_uid;
  insert into public.call_feedback (session_id, user_id, wants_again, rating)
  values (p_session, v_uid, p_wants_again, p_rating)
  on conflict (session_id, user_id) do update set wants_again = excluded.wants_again, rating = excluded.rating;

  if p_wants_again and exists (select 1 from public.call_feedback where session_id = p_session and user_id = v_peer and wants_again)
     and not app_private.is_blocked(v_uid, v_peer) then
    v_mutual := true;
    if not app_private.are_friends(v_uid, v_peer) then
      perform app_private.make_friends(v_uid, v_peer);
      perform app_private.notify(v_peer, 'mutual_match', v_uid, 'profile', v_uid::text, '{}'::jsonb, 'friend_requests');
      perform app_private.notify(v_uid, 'mutual_match', v_peer, 'profile', v_peer::text, '{}'::jsonb, 'friend_requests');
    end if;
  end if;
  -- The peer's choice is never revealed unless it was a mutual yes.
  return jsonb_build_object('mutual', v_mutual, 'peer_id', case when v_mutual then v_peer end);
end;
$$;

-- ---------------------------------------------------------------------------
-- Direct calls between friends
-- ---------------------------------------------------------------------------
create or replace function public.start_direct_call(p_user uuid, p_mode public.talk_mode)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_session uuid;
  v_policy public.message_policy;
  v_me public.profiles;
  v_peer public.profiles;
begin
  perform app_private.require_profile(v_uid);
  if p_mode = 'text' then
    perform app_private.err('invalid_mode');
  end if;
  if p_user = v_uid or app_private.is_blocked(v_uid, p_user) or not app_private.are_friends(v_uid, p_user) then
    perform app_private.err('call_not_allowed');
  end if;
  select call_policy into v_policy from public.user_private where user_id = p_user;
  if v_policy = 'nobody' then
    perform app_private.err('call_not_allowed');
  end if;
  perform app_private.rate_limit('direct_call', 20, interval '10 minutes');
  if app_private.active_session_of(v_uid) is not null then
    perform app_private.err('already_in_call');
  end if;
  if app_private.in_room(v_uid) then
    perform app_private.err('in_room');
  end if;
  if app_private.active_session_of(p_user) is not null then
    perform app_private.err('peer_busy');
  end if;
  delete from public.matchmaking_queue where user_id = v_uid and status = 'waiting';
  select * into v_me from public.profiles where id = v_uid;
  select * into v_peer from public.profiles where id = p_user;

  insert into public.call_sessions (kind, mode, initial_mode, channel_name, status, ring_expires_at)
  values ('direct', p_mode, p_mode, 'st_' || replace(gen_random_uuid()::text, '-', ''), 'ringing',
          now() + make_interval(secs => app_private.setting_int('direct_ring_seconds', 35)))
  returning id into v_session;
  insert into public.call_participants (session_id, user_id, slot, alias, rtc_uid) values
    (v_session, v_uid, 1, v_me.display_name, app_private.rtc_uid_for(v_uid)),
    (v_session, p_user, 2, v_peer.display_name, app_private.rtc_uid_for(p_user));

  perform app_private.notify(p_user, 'incoming_call', v_uid, 'call', v_session::text,
    jsonb_build_object('mode', p_mode), 'calls',
    now() + make_interval(secs => app_private.setting_int('direct_ring_seconds', 35)));
  return app_private.session_state(v_session, v_uid);
end;
$$;

create or replace function public.answer_call(p_session uuid, p_accept boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
begin
  select * into v_s from public.call_sessions where id = p_session for update;
  if not found or not exists (select 1 from public.call_participants where session_id = p_session and user_id = v_uid and slot = 2) then
    perform app_private.err('not_found');
  end if;
  if v_s.status <> 'ringing' or v_s.ring_expires_at < now() then
    perform app_private.err('call_ended');
  end if;
  if not p_accept then
    perform app_private.end_session(p_session, 'declined');
    return app_private.session_state(p_session, v_uid);
  end if;
  if exists (select 1 from public.call_participants cp join public.call_sessions cs on cs.id = cp.session_id
             where cp.user_id = v_uid and cp.left_at is null and cs.status <> 'ended' and cs.id <> p_session) then
    perform app_private.err('already_in_call');
  end if;
  if app_private.in_room(v_uid) then
    perform app_private.err('in_room');
  end if;
  update public.call_sessions set status = 'connecting', connect_deadline = now() + interval '45 seconds' where id = p_session;
  update public.notifications set expires_at = now(), read_at = coalesce(read_at, now())
  where kind = 'incoming_call' and entity_id = p_session::text;
  return app_private.session_state(p_session, v_uid);
end;
$$;

-- Ends any live call with the blocked user and removes both from each other's reach.
create or replace function app_private.on_block(p_blocker uuid, p_blocked uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  r record;
begin
  for r in select cs.id from public.call_sessions cs
           where cs.status <> 'ended'
             and exists (select 1 from public.call_participants where session_id = cs.id and user_id = p_blocker)
             and exists (select 1 from public.call_participants where session_id = cs.id and user_id = p_blocked) loop
    perform app_private.end_session(r.id, 'block');
  end loop;
  for r in select id, user_a, user_b from public.matches
           where status = 'pending'
             and least(user_a, user_b) = least(p_blocker, p_blocked)
             and greatest(user_a, user_b) = greatest(p_blocker, p_blocked) loop
    update public.matches set status = 'cancelled' where id = r.id;
    delete from public.matchmaking_queue where user_id = p_blocker;
    perform app_private.requeue(p_blocked);
  end loop;
end;
$$;

-- Block / report the anonymous peer of a random session without learning their id.
create or replace function public.block_call_peer(p_session uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_peer uuid;
begin
  select peer.user_id into v_peer
  from public.call_participants me
  join public.call_participants peer on peer.session_id = me.session_id and peer.user_id <> me.user_id
  where me.session_id = p_session and me.user_id = v_uid;
  if v_peer is null then
    perform app_private.err('not_found');
  end if;
  insert into public.user_blocks (blocker_id, blocked_id) values (v_uid, v_peer) on conflict do nothing;
  delete from public.friendships where (user_id = v_uid and friend_id = v_peer) or (user_id = v_peer and friend_id = v_uid);
  perform app_private.on_block(v_uid, v_peer);
end;
$$;

create or replace function public.send_friend_request_to_call_peer(p_session uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_peer uuid;
begin
  select peer.user_id into v_peer
  from public.call_participants me
  join public.call_participants peer on peer.session_id = me.session_id and peer.user_id <> me.user_id
  join public.call_sessions cs on cs.id = me.session_id
  where me.session_id = p_session and me.user_id = v_uid and cs.started_at is not null;
  if v_peer is null then
    perform app_private.err('not_found');
  end if;
  perform public.send_friend_request(v_peer, 'call');
  -- Only confirms the request was sent; the peer's identity stays hidden until they accept.
  return jsonb_build_object('sent', true);
end;
$$;
