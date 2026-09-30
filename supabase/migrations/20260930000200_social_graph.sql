-- Sit & Talk — blocks, friendships, presence, notifications, devices, onboarding.

-- ---------------------------------------------------------------------------
-- Blocks
-- ---------------------------------------------------------------------------
create table public.user_blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index user_blocks_blocked_idx on public.user_blocks (blocked_id);

create or replace function app_private.is_blocked(p_a uuid, p_b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.user_blocks
    where (blocker_id = p_a and blocked_id = p_b) or (blocker_id = p_b and blocked_id = p_a)
  );
$$;

-- ---------------------------------------------------------------------------
-- Friendships (two rows per friendship so each side keeps its own favorite flag)
-- ---------------------------------------------------------------------------
create type public.friend_request_status as enum ('pending', 'accepted', 'declined', 'cancelled');

create table public.friend_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  receiver_id uuid not null references auth.users(id) on delete cascade,
  status public.friend_request_status not null default 'pending',
  source text not null default 'profile' check (source in ('profile', 'call', 'room', 'search', 'feed')),
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  check (sender_id <> receiver_id)
);
create unique index friend_requests_pending_key on public.friend_requests (least(sender_id, receiver_id), greatest(sender_id, receiver_id)) where status = 'pending';
create index friend_requests_receiver_idx on public.friend_requests (receiver_id, status);
create index friend_requests_sender_idx on public.friend_requests (sender_id, status);

create table public.friendships (
  user_id uuid not null references auth.users(id) on delete cascade,
  friend_id uuid not null references auth.users(id) on delete cascade,
  favorite boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);
create index friendships_friend_idx on public.friendships (friend_id);

create or replace function app_private.are_friends(p_a uuid, p_b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.friendships where user_id = p_a and friend_id = p_b);
$$;

create or replace function app_private.make_friends(p_a uuid, p_b uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.friendships (user_id, friend_id) values (p_a, p_b), (p_b, p_a)
  on conflict do nothing;
$$;

-- Visibility helper for online / last seen style settings.
create or replace function app_private.visible_to(p_owner uuid, p_viewer uuid, p_policy public.visibility_policy)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_owner = p_viewer
      or (not app_private.is_blocked(p_owner, p_viewer) and (
            p_policy = 'everyone'
         or (p_policy = 'friends' and app_private.are_friends(p_owner, p_viewer))));
$$;

-- ---------------------------------------------------------------------------
-- Notifications, preferences, push outbox, devices
-- ---------------------------------------------------------------------------
create table public.notification_preferences (
  user_id uuid primary key references auth.users(id) on delete cascade,
  friend_requests boolean not null default true,
  messages boolean not null default true,
  calls boolean not null default true,
  room_invites boolean not null default true,
  events boolean not null default true,
  comments boolean not null default true,
  purchases boolean not null default true,
  show_previews boolean not null default false,
  updated_at timestamptz not null default now()
);

create table public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in (
    'friend_request', 'friend_accepted', 'incoming_call', 'missed_call', 'room_invite',
    'event_reminder', 'comment', 'reply', 'moderation', 'purchase', 'mutual_match', 'announcement')),
  actor_id uuid references auth.users(id) on delete set null,
  entity_type text,
  entity_id text,
  data jsonb not null default '{}'::jsonb,
  expires_at timestamptz,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index notifications_user_idx on public.notifications (user_id, created_at desc);

create table public.user_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  fcm_token text not null check (char_length(fcm_token) between 20 and 4096),
  platform text not null default 'android' check (platform in ('android')),
  app_version_code int,
  locale text,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
create unique index user_devices_token_key on public.user_devices (fcm_token);
create index user_devices_user_idx on public.user_devices (user_id);

-- Push deliveries waiting for the `send-push` Edge Function. Never exposed to clients.
create table app_private.push_outbox (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  category text not null,
  title_key text not null,
  body text,
  data jsonb not null default '{}'::jsonb,
  collapse_key text,
  high_priority boolean not null default false,
  expires_at timestamptz,
  status text not null default 'pending' check (status in ('pending', 'sent', 'skipped', 'failed')),
  attempts int not null default 0,
  last_error text,
  created_at timestamptz not null default now(),
  processed_at timestamptz
);
create index push_outbox_pending_idx on app_private.push_outbox (created_at) where status = 'pending';

create or replace function app_private.pref_enabled(p_user uuid, p_category text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((
    select case p_category
      when 'friend_requests' then np.friend_requests
      when 'messages' then np.messages
      when 'calls' then np.calls
      when 'room_invites' then np.room_invites
      when 'events' then np.events
      when 'comments' then np.comments
      when 'purchases' then np.purchases
      else true end
    from public.notification_preferences np where np.user_id = p_user), true);
$$;

create or replace function app_private.enqueue_push(
  p_user uuid, p_category text, p_title_key text, p_body text, p_data jsonb,
  p_collapse_key text default null, p_high_priority boolean default false, p_expires_at timestamptz default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not app_private.pref_enabled(p_user, p_category) then
    return;
  end if;
  if not exists (select 1 from public.user_devices where user_id = p_user) then
    return;
  end if;
  insert into app_private.push_outbox (user_id, category, title_key, body, data, collapse_key, high_priority, expires_at)
  values (p_user, p_category, p_title_key, p_body, coalesce(p_data, '{}'::jsonb), p_collapse_key, p_high_priority, p_expires_at);
end;
$$;

create or replace function app_private.notify(
  p_user uuid, p_kind text, p_actor uuid, p_entity_type text, p_entity_id text,
  p_data jsonb default '{}'::jsonb, p_category text default null, p_expires_at timestamptz default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid;
begin
  if p_actor is not null and app_private.is_blocked(p_user, p_actor) then
    return null;
  end if;
  insert into public.notifications (user_id, kind, actor_id, entity_type, entity_id, data, expires_at)
  values (p_user, p_kind, p_actor, p_entity_type, p_entity_id, coalesce(p_data, '{}'::jsonb), p_expires_at)
  returning id into v_id;
  perform app_private.enqueue_push(
    p_user, coalesce(p_category, p_kind), 'push_' || p_kind, null,
    jsonb_build_object('notification_id', v_id, 'kind', p_kind, 'entity_type', p_entity_type, 'entity_id', p_entity_id),
    p_kind || ':' || coalesce(p_entity_id, ''), p_kind = 'incoming_call', p_expires_at);
  return v_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- Onboarding and profile RPCs
-- ---------------------------------------------------------------------------
create or replace function public.complete_onboarding(
  p_username text,
  p_display_name text,
  p_birth_date date,
  p_interests text[],
  p_languages text[],
  p_terms_version text,
  p_privacy_version text,
  p_community_version text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_username text := lower(btrim(p_username));
  v_age int;
begin
  perform app_private.rate_limit('onboarding', 10, interval '1 hour');
  if exists (select 1 from public.user_private where user_id = v_uid and onboarding_completed_at is not null) then
    perform app_private.err('already_onboarded');
  end if;
  if v_username !~ '^[a-z0-9_.]{3,24}$' then
    perform app_private.err('invalid_username');
  end if;
  if char_length(btrim(coalesce(p_display_name, ''))) not between 2 and 32 then
    perform app_private.err('invalid_display_name');
  end if;
  if p_birth_date is null or p_birth_date > current_date then
    perform app_private.err('invalid_birth_date');
  end if;
  v_age := date_part('year', age(current_date, p_birth_date));
  if v_age < 18 then
    perform app_private.err('underage');
  end if;
  if v_age > 120 then
    perform app_private.err('invalid_birth_date');
  end if;
  if coalesce(array_length(p_languages, 1), 0) = 0 then
    perform app_private.err('languages_required');
  end if;
  if coalesce(array_length(p_interests, 1), 0) > 15 then
    perform app_private.err('too_many_interests');
  end if;
  if coalesce(p_terms_version, '') = '' or coalesce(p_privacy_version, '') = '' or coalesce(p_community_version, '') = '' then
    perform app_private.err('consent_required');
  end if;
  if exists (select 1 from public.profiles where lower(username) = v_username and id <> v_uid) then
    perform app_private.err('username_taken');
  end if;

  insert into public.profiles (id, username, display_name)
  values (v_uid, v_username, btrim(p_display_name))
  on conflict (id) do update set username = excluded.username, display_name = excluded.display_name;

  insert into public.user_private (user_id) values (v_uid) on conflict do nothing;
  update public.user_private set
    birth_date = p_birth_date,
    terms_accepted_at = now(),
    privacy_accepted_at = now(),
    community_accepted_at = now(),
    onboarding_completed_at = now()
  where user_id = v_uid;

  insert into public.user_consents (user_id, document, version) values
    (v_uid, 'terms', p_terms_version), (v_uid, 'privacy', p_privacy_version), (v_uid, 'community', p_community_version);

  delete from public.user_interests where user_id = v_uid;
  insert into public.user_interests (user_id, slug)
  select v_uid, i.slug from public.interests i where i.active and i.slug = any (p_interests)
  on conflict do nothing;

  delete from public.user_languages where user_id = v_uid;
  insert into public.user_languages (user_id, language_code)
  select distinct v_uid, lower(l) from unnest(p_languages) as l where lower(l) ~ '^[a-z]{2,3}$'
  on conflict do nothing;

  insert into public.notification_preferences (user_id) values (v_uid) on conflict do nothing;
  insert into public.user_presence (user_id) values (v_uid) on conflict do nothing;

  return jsonb_build_object('user_id', v_uid, 'username', v_username);
exception
  when unique_violation then
    perform app_private.err('username_taken');
    return null;
end;
$$;

create or replace function public.change_username(p_username text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_username text := lower(btrim(p_username));
  v_last timestamptz;
begin
  perform app_private.require_profile(v_uid);
  if v_username !~ '^[a-z0-9_.]{3,24}$' then
    perform app_private.err('invalid_username');
  end if;
  select username_changed_at into v_last from public.profiles where id = v_uid for update;
  if v_last is not null and v_last > now() - interval '14 days' then
    perform app_private.err('username_change_cooldown', ceil(extract(epoch from (v_last + interval '14 days' - now())))::int::text);
  end if;
  if exists (select 1 from public.profiles where lower(username) = v_username and id <> v_uid) then
    perform app_private.err('username_taken');
  end if;
  update public.profiles set username = v_username, username_changed_at = now() where id = v_uid;
exception
  when unique_violation then
    perform app_private.err('username_taken');
end;
$$;

create or replace function public.set_interests(p_interests text[], p_private text[] default '{}')
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.require_profile(v_uid);
  if coalesce(array_length(p_interests, 1), 0) > 15 then
    perform app_private.err('too_many_interests');
  end if;
  delete from public.user_interests where user_id = v_uid;
  insert into public.user_interests (user_id, slug, is_public)
  select v_uid, i.slug, not (i.slug = any (coalesce(p_private, '{}')))
  from public.interests i where i.active and i.slug = any (p_interests);
end;
$$;

create or replace function public.set_languages(p_languages text[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.require_profile(v_uid);
  if coalesce(array_length(p_languages, 1), 0) = 0 then
    perform app_private.err('languages_required');
  end if;
  delete from public.user_languages where user_id = v_uid;
  insert into public.user_languages (user_id, language_code)
  select distinct v_uid, lower(l) from unnest(p_languages) as l where lower(l) ~ '^[a-z]{2,3}$';
end;
$$;

create or replace function public.touch_presence()
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.user_presence (user_id, last_seen_at) values (auth.uid(), now())
  on conflict (user_id) do update set last_seen_at = now();
$$;

-- Public profile card, honoring blocks and privacy settings.
create or replace function public.get_profile(p_user uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_me uuid := app_private.require_user();
  v_p public.profiles;
  v_priv public.user_private;
  v_last timestamptz;
  v_friend boolean;
  v_request jsonb;
begin
  if p_user <> v_me and app_private.is_blocked(v_me, p_user) then
    perform app_private.err('not_found');
  end if;
  select * into v_p from public.profiles where id = p_user;
  if not found then
    perform app_private.err('not_found');
  end if;
  select * into v_priv from public.user_private where user_id = p_user;
  v_friend := app_private.are_friends(v_me, p_user);
  select last_seen_at into v_last from public.user_presence where user_id = p_user;
  select jsonb_build_object('id', fr.id, 'outgoing', fr.sender_id = v_me) into v_request
  from public.friend_requests fr
  where fr.status = 'pending'
    and ((fr.sender_id = v_me and fr.receiver_id = p_user) or (fr.sender_id = p_user and fr.receiver_id = v_me));

  return jsonb_build_object(
    'id', v_p.id,
    'username', v_p.username,
    'display_name', v_p.display_name,
    'bio', v_p.bio,
    'avatar_path', v_p.avatar_path,
    'country_code', v_p.country_code,
    'gifts_received', v_p.gifts_received,
    'created_at', v_p.created_at,
    'is_self', p_user = v_me,
    'is_friend', v_friend,
    'pending_request', v_request,
    'is_premium', app_private.is_premium(p_user),
    'interests', coalesce((select jsonb_agg(ui.slug order by ui.slug) from public.user_interests ui
                           where ui.user_id = p_user and (ui.is_public or p_user = v_me)), '[]'::jsonb),
    'languages', coalesce((select jsonb_agg(ul.language_code order by ul.language_code) from public.user_languages ul
                           where ul.user_id = p_user), '[]'::jsonb),
    'last_seen_at', case when app_private.visible_to(p_user, v_me, v_priv.last_seen_visibility) then v_last end,
    'online', case when app_private.visible_to(p_user, v_me, v_priv.online_visibility)
                   then coalesce(v_last > now() - interval '2 minutes', false) end,
    'can_message', p_user <> v_me and (v_friend or v_priv.message_policy = 'everyone'),
    'can_call', p_user <> v_me and v_friend and v_priv.call_policy <> 'nobody'
  );
end;
$$;

create or replace function public.search_users(p_query text, p_limit int default 20)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_me uuid := app_private.require_user();
  v_q text := lower(btrim(coalesce(p_query, '')));
begin
  perform app_private.rate_limit('search_users', 60, interval '1 minute');
  if char_length(v_q) < 2 then
    return '[]'::jsonb;
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name,
                                        'avatar_path', p.avatar_path,
                                        'is_friend', app_private.are_friends(v_me, p.id)))
    from (
      select p.* from public.profiles p
      join public.user_private up on up.user_id = p.id
      where p.id <> v_me
        and up.profile_discoverable
        and not app_private.is_blocked(v_me, p.id)
        and (lower(p.username) like replace(replace(v_q, '%', ''), '_', '\_') || '%')
      order by char_length(p.username), p.username
      limit least(greatest(p_limit, 1), 50)
    ) p
  ), '[]'::jsonb);
end;
$$;

-- ---------------------------------------------------------------------------
-- Block / friendship RPCs
-- ---------------------------------------------------------------------------
create or replace function public.block_user(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_me uuid := app_private.require_user();
begin
  if p_user = v_me then
    perform app_private.err('invalid_target');
  end if;
  perform app_private.rate_limit('block', 60, interval '1 hour');
  insert into public.user_blocks (blocker_id, blocked_id) values (v_me, p_user) on conflict do nothing;
  delete from public.friendships where (user_id = v_me and friend_id = p_user) or (user_id = p_user and friend_id = v_me);
  update public.friend_requests set status = 'cancelled', responded_at = now()
  where status = 'pending' and ((sender_id = v_me and receiver_id = p_user) or (sender_id = p_user and receiver_id = v_me));
  perform app_private.on_block(v_me, p_user);
end;
$$;

create or replace function public.unblock_user(p_user uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.user_blocks where blocker_id = auth.uid() and blocked_id = p_user;
$$;

create or replace function public.send_friend_request(p_user uuid, p_source text default 'profile')
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_me uuid := app_private.require_user();
  v_id uuid;
  v_reverse uuid;
begin
  perform app_private.require_profile(v_me);
  if p_user = v_me then
    perform app_private.err('invalid_target');
  end if;
  if app_private.is_blocked(v_me, p_user) then
    perform app_private.err('not_found');
  end if;
  if not exists (select 1 from public.profiles where id = p_user) then
    perform app_private.err('not_found');
  end if;
  if app_private.are_friends(v_me, p_user) then
    perform app_private.err('already_friends');
  end if;
  perform app_private.rate_limit('friend_request', 30, interval '1 hour');

  -- If the other side already asked us, accept instead of creating a duplicate.
  select id into v_reverse from public.friend_requests
  where sender_id = p_user and receiver_id = v_me and status = 'pending' for update;
  if v_reverse is not null then
    perform public.respond_friend_request(v_reverse, true);
    return v_reverse;
  end if;

  select id into v_id from public.friend_requests
  where sender_id = v_me and receiver_id = p_user and status = 'pending';
  if v_id is not null then
    return v_id;
  end if;

  insert into public.friend_requests (sender_id, receiver_id, source)
  values (v_me, p_user, case when p_source in ('profile', 'call', 'room', 'search', 'feed') then p_source else 'profile' end)
  returning id into v_id;
  perform app_private.notify(p_user, 'friend_request', v_me, 'friend_request', v_id::text, '{}'::jsonb, 'friend_requests');
  return v_id;
end;
$$;

create or replace function public.respond_friend_request(p_request uuid, p_accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_me uuid := app_private.require_user();
  v_req public.friend_requests;
begin
  select * into v_req from public.friend_requests where id = p_request for update;
  if not found or v_req.receiver_id <> v_me then
    perform app_private.err('not_found');
  end if;
  if v_req.status <> 'pending' then
    return;
  end if;
  update public.friend_requests set status = case when p_accept then 'accepted'::public.friend_request_status else 'declined'::public.friend_request_status end,
    responded_at = now() where id = p_request;
  if p_accept then
    if app_private.is_blocked(v_req.sender_id, v_me) then
      perform app_private.err('not_found');
    end if;
    perform app_private.make_friends(v_req.sender_id, v_me);
    perform app_private.notify(v_req.sender_id, 'friend_accepted', v_me, 'profile', v_me::text, '{}'::jsonb, 'friend_requests');
  end if;
end;
$$;

create or replace function public.cancel_friend_request(p_request uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.friend_requests set status = 'cancelled', responded_at = now()
  where id = p_request and sender_id = auth.uid() and status = 'pending';
$$;

create or replace function public.remove_friend(p_user uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.friendships
  where (user_id = auth.uid() and friend_id = p_user) or (user_id = p_user and friend_id = auth.uid());
$$;

create or replace function public.set_friend_favorite(p_user uuid, p_favorite boolean)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.friendships set favorite = p_favorite where user_id = auth.uid() and friend_id = p_user;
$$;

create or replace function public.list_friends()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id, 'username', p.username, 'display_name', p.display_name, 'avatar_path', p.avatar_path,
      'favorite', f.favorite, 'since', f.created_at,
      'online', case when app_private.visible_to(p.id, auth.uid(), up.online_visibility)
                     then coalesce(pr.last_seen_at > now() - interval '2 minutes', false) end,
      'last_seen_at', case when app_private.visible_to(p.id, auth.uid(), up.last_seen_visibility) then pr.last_seen_at end,
      'can_call', up.call_policy <> 'nobody'
    ) order by f.favorite desc, lower(p.display_name)), '[]'::jsonb)
  from public.friendships f
  join public.profiles p on p.id = f.friend_id
  join public.user_private up on up.user_id = p.id
  left join public.user_presence pr on pr.user_id = p.id
  where f.user_id = auth.uid();
$$;

create or replace function public.list_friend_requests()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'id', fr.id, 'outgoing', fr.sender_id = auth.uid(), 'created_at', fr.created_at, 'source', fr.source,
      'user', jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name, 'avatar_path', p.avatar_path)
    ) order by fr.created_at desc), '[]'::jsonb)
  from public.friend_requests fr
  join public.profiles p on p.id = case when fr.sender_id = auth.uid() then fr.receiver_id else fr.sender_id end
  where fr.status = 'pending' and (fr.sender_id = auth.uid() or fr.receiver_id = auth.uid());
$$;

create or replace function public.list_blocked_users()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name,
                                               'avatar_path', p.avatar_path, 'blocked_at', b.created_at)
                            order by b.created_at desc), '[]'::jsonb)
  from public.user_blocks b join public.profiles p on p.id = b.blocked_id
  where b.blocker_id = auth.uid();
$$;

create or replace function public.register_device(p_token text, p_version_code int, p_locale text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.rate_limit('register_device', 20, interval '1 hour');
  -- A token belongs to exactly one account: re-registering moves it (account switch on one device).
  insert into public.user_devices (user_id, fcm_token, app_version_code, locale)
  values (v_uid, p_token, p_version_code, left(p_locale, 16))
  on conflict (fcm_token) do update set user_id = v_uid, app_version_code = excluded.app_version_code,
    locale = excluded.locale, last_seen_at = now();
end;
$$;

create or replace function public.unregister_device(p_token text)
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.user_devices where fcm_token = p_token and user_id = auth.uid();
$$;

create or replace function public.mark_notifications_read(p_ids uuid[] default null)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.notifications set read_at = now()
  where user_id = auth.uid() and read_at is null and (p_ids is null or id = any (p_ids));
$$;
