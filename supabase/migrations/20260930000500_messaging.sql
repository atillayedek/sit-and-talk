-- Sit & Talk — private direct and group conversations between people who know each other.
-- Postgres is the source of truth; Realtime only carries change events.

create type public.conversation_kind as enum ('direct', 'group');
create type public.message_kind as enum ('text', 'image', 'voice', 'system');

create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  kind public.conversation_kind not null,
  title text check (title is null or char_length(btrim(title)) between 1 and 60),
  direct_key text unique,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  last_message_at timestamptz,
  check ((kind = 'direct') = (direct_key is not null))
);

create table public.conversation_members (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  muted_until timestamptz,
  pinned boolean not null default false,
  archived boolean not null default false,
  cleared_before timestamptz,
  typing_until timestamptz,
  delivered_at timestamptz,
  shared_read_at timestamptz,
  primary key (conversation_id, user_id)
);
create index conversation_members_user_idx on public.conversation_members (user_id) where left_at is null;

-- Real read position (unread counts). Only its owner can see it; what peers see is `shared_read_at`,
-- which stays empty when the user turned read receipts off.
create table public.conversation_read_state (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  last_read_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create table public.messages (
  id uuid primary key,
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid references auth.users(id) on delete set null,
  kind public.message_kind not null default 'text',
  body text check (body is null or char_length(body) <= 4000),
  media_path text,
  media_mime text,
  media_duration_ms int check (media_duration_ms is null or media_duration_ms between 0 and 300000),
  reply_to_id uuid references public.messages(id) on delete set null,
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_for_all_at timestamptz,
  check (kind <> 'text' or (body is not null and char_length(btrim(body)) > 0)),
  check (kind not in ('image', 'voice') or media_path is not null)
);
create index messages_conversation_idx on public.messages (conversation_id, created_at desc);

create table public.message_hidden (
  user_id uuid not null references auth.users(id) on delete cascade,
  message_id uuid not null references public.messages(id) on delete cascade,
  primary key (user_id, message_id)
);

create table public.message_reactions (
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  emoji text not null check (char_length(emoji) between 1 and 16),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
create or replace function app_private.is_conversation_member(p_conversation uuid, p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.conversation_members
                 where conversation_id = p_conversation and user_id = p_user and left_at is null);
$$;

create or replace function app_private.direct_peer(p_conversation uuid, p_user uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select cm.user_id from public.conversation_members cm
  join public.conversations c on c.id = cm.conversation_id and c.kind = 'direct'
  where cm.conversation_id = p_conversation and cm.user_id <> p_user;
$$;

-- Can p_sender post this kind of message into the conversation right now?
create or replace function app_private.can_send_message(p_conversation uuid, p_sender uuid, p_kind public.message_kind)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_peer uuid;
  v_policy public.message_policy;
  v_media_ok boolean;
begin
  if not app_private.is_conversation_member(p_conversation, p_sender) then
    return false;
  end if;
  if app_private.is_restricted(p_sender, 'messaging') then
    return false;
  end if;
  if p_kind = 'system' then
    return false;
  end if;
  v_peer := app_private.direct_peer(p_conversation, p_sender);
  if v_peer is not null then
    if app_private.is_blocked(p_sender, v_peer) then
      return false;
    end if;
    select message_policy, media_from_non_friends into v_policy, v_media_ok from public.user_private where user_id = v_peer;
    if not app_private.are_friends(p_sender, v_peer) then
      if v_policy <> 'everyone' then
        return false;
      end if;
      if p_kind in ('image', 'voice') and not coalesce(v_media_ok, false) then
        return false;
      end if;
    end if;
  end if;
  return true;
end;
$$;

-- ---------------------------------------------------------------------------
-- Conversation RPCs
-- ---------------------------------------------------------------------------
create or replace function public.get_or_create_direct(p_user uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_key text;
  v_id uuid;
  v_policy public.message_policy;
begin
  perform app_private.require_profile(v_uid);
  if p_user = v_uid or app_private.is_blocked(v_uid, p_user) or not exists (select 1 from public.profiles where id = p_user) then
    perform app_private.err('not_found');
  end if;
  select message_policy into v_policy from public.user_private where user_id = p_user;
  if not app_private.are_friends(v_uid, p_user) and v_policy <> 'everyone' then
    perform app_private.err('messaging_not_allowed');
  end if;
  v_key := least(v_uid, p_user)::text || ':' || greatest(v_uid, p_user)::text;
  select id into v_id from public.conversations where direct_key = v_key;
  if v_id is null then
    perform app_private.rate_limit('new_direct', 30, interval '1 hour');
    insert into public.conversations (kind, direct_key, created_by) values ('direct', v_key, v_uid)
    on conflict (direct_key) do nothing
    returning id into v_id;
    if v_id is null then
      select id into v_id from public.conversations where direct_key = v_key;
    end if;
  end if;
  insert into public.conversation_members (conversation_id, user_id) values (v_id, v_uid), (v_id, p_user)
  on conflict (conversation_id, user_id) do update set left_at = null;
  return v_id;
end;
$$;

create or replace function public.create_group(p_title text, p_members uuid[])
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_id uuid;
  v_member uuid;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'messaging');
  perform app_private.rate_limit('create_group', 10, interval '1 day');
  if coalesce(array_length(p_members, 1), 0) not between 1 and 49 then
    perform app_private.err('invalid_group_size');
  end if;
  insert into public.conversations (kind, title, created_by) values ('group', btrim(p_title), v_uid) returning id into v_id;
  insert into public.conversation_members (conversation_id, user_id, role) values (v_id, v_uid, 'owner');
  foreach v_member in array p_members loop
    -- Groups are for friends only.
    if v_member <> v_uid and app_private.are_friends(v_uid, v_member) and not app_private.is_blocked(v_uid, v_member) then
      insert into public.conversation_members (conversation_id, user_id) values (v_id, v_member) on conflict do nothing;
    end if;
  end loop;
  insert into public.messages (id, conversation_id, sender_id, kind, body)
  values (gen_random_uuid(), v_id, null, 'system', 'group_created');
  return v_id;
end;
$$;

create or replace function public.add_group_members(p_conversation uuid, p_members uuid[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_member uuid;
begin
  if not exists (select 1 from public.conversation_members cm join public.conversations c on c.id = cm.conversation_id
                 where cm.conversation_id = p_conversation and cm.user_id = v_uid and cm.role = 'owner'
                   and cm.left_at is null and c.kind = 'group') then
    perform app_private.err('forbidden');
  end if;
  if (select count(*) from public.conversation_members where conversation_id = p_conversation and left_at is null)
     + coalesce(array_length(p_members, 1), 0) > 50 then
    perform app_private.err('invalid_group_size');
  end if;
  foreach v_member in array p_members loop
    if app_private.are_friends(v_uid, v_member) and not app_private.is_blocked(v_uid, v_member) then
      insert into public.conversation_members (conversation_id, user_id) values (p_conversation, v_member)
      on conflict (conversation_id, user_id) do update set left_at = null, joined_at = now();
    end if;
  end loop;
end;
$$;

create or replace function public.remove_group_member(p_conversation uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if p_user <> v_uid and not exists (
      select 1 from public.conversation_members where conversation_id = p_conversation and user_id = v_uid and role = 'owner' and left_at is null) then
    perform app_private.err('forbidden');
  end if;
  if not exists (select 1 from public.conversations where id = p_conversation and kind = 'group') then
    perform app_private.err('invalid_state');
  end if;
  update public.conversation_members set left_at = now() where conversation_id = p_conversation and user_id = p_user;
  -- The group keeps an owner while members remain.
  if not exists (select 1 from public.conversation_members where conversation_id = p_conversation and role = 'owner' and left_at is null) then
    update public.conversation_members set role = 'owner'
    where conversation_id = p_conversation and user_id = (
      select user_id from public.conversation_members where conversation_id = p_conversation and left_at is null order by joined_at limit 1);
  end if;
end;
$$;

create or replace function public.list_conversations(p_archived boolean default false)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(row_data order by (row_data ->> 'pinned')::boolean desc, row_data ->> 'sort_at' desc nulls last), '[]'::jsonb)
  from (
    select jsonb_build_object(
      'id', c.id,
      'kind', c.kind,
      'title', c.title,
      'pinned', me.pinned,
      'archived', me.archived,
      'muted', me.muted_until is not null and me.muted_until > now(),
      'sort_at', coalesce(c.last_message_at, c.created_at),
      'peer', case when c.kind = 'direct' then (
        select jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name, 'avatar_path', p.avatar_path)
        from public.conversation_members o join public.profiles p on p.id = o.user_id
        where o.conversation_id = c.id and o.user_id <> auth.uid()) end,
      'member_count', (select count(*) from public.conversation_members o where o.conversation_id = c.id and o.left_at is null),
      'last_message', (
        select jsonb_build_object('id', m.id, 'kind', m.kind, 'sender_id', m.sender_id, 'created_at', m.created_at,
                                  'body', case when m.deleted_for_all_at is not null then null
                                               when m.kind = 'text' then left(m.body, 140) else m.body end,
                                  'deleted', m.deleted_for_all_at is not null)
        from public.messages m
        where m.conversation_id = c.id
          and (me.cleared_before is null or m.created_at > me.cleared_before)
          and not exists (select 1 from public.message_hidden h where h.message_id = m.id and h.user_id = auth.uid())
        order by m.created_at desc limit 1),
      'unread_count', (
        select count(*) from public.messages m
        where m.conversation_id = c.id and m.sender_id is distinct from auth.uid() and m.deleted_for_all_at is null
          and m.created_at > greatest(coalesce(rs.last_read_at, me.joined_at), coalesce(me.cleared_before, me.joined_at)))
    ) as row_data
    from public.conversation_members me
    join public.conversations c on c.id = me.conversation_id
    left join public.conversation_read_state rs on rs.conversation_id = c.id and rs.user_id = me.user_id
    where me.user_id = auth.uid() and me.left_at is null and me.archived = coalesce(p_archived, false)
      and (c.kind <> 'direct' or not app_private.is_blocked(auth.uid(), app_private.direct_peer(c.id, auth.uid())))
  ) s;
$$;

create or replace function public.update_conversation_settings(
  p_conversation uuid, p_pinned boolean default null, p_archived boolean default null, p_muted_until timestamptz default null,
  p_clear_mute boolean default false
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.conversation_members set
    pinned = coalesce(p_pinned, pinned),
    archived = coalesce(p_archived, archived),
    muted_until = case when p_clear_mute then null else coalesce(p_muted_until, muted_until) end
  where conversation_id = p_conversation and user_id = app_private.require_user() and left_at is null;
  if not found then
    perform app_private.err('not_found');
  end if;
end;
$$;

create or replace function public.clear_conversation(p_conversation uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.conversation_members set cleared_before = now()
  where conversation_id = p_conversation and user_id = auth.uid();
$$;

create or replace function public.mark_conversation_read(p_conversation uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_receipts boolean;
begin
  if not app_private.is_conversation_member(p_conversation, v_uid) then
    perform app_private.err('not_found');
  end if;
  insert into public.conversation_read_state (conversation_id, user_id, last_read_at) values (p_conversation, v_uid, now())
  on conflict (conversation_id, user_id) do update set last_read_at = now();
  select read_receipts into v_receipts from public.user_private where user_id = v_uid;
  update public.conversation_members set
    delivered_at = now(),
    shared_read_at = case when coalesce(v_receipts, true) then now() else null end
  where conversation_id = p_conversation and user_id = v_uid;
end;
$$;

-- Called when the client has fetched new messages (app opened / realtime event received).
create or replace function public.mark_delivered()
returns void
language sql
security definer
set search_path = ''
as $$
  update public.conversation_members set delivered_at = now()
  where user_id = auth.uid() and left_at is null;
$$;

create or replace function public.set_typing(p_conversation uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.conversation_members set typing_until = now() + interval '5 seconds'
  where conversation_id = p_conversation and user_id = auth.uid() and left_at is null
    and (typing_until is null or typing_until < now() + interval '2 seconds');
$$;

create or replace function public.edit_message(p_message uuid, p_body text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_m public.messages;
begin
  select * into v_m from public.messages where id = p_message for update;
  if not found or v_m.sender_id is distinct from v_uid or v_m.kind <> 'text' or v_m.deleted_for_all_at is not null then
    perform app_private.err('not_found');
  end if;
  if v_m.created_at < now() - make_interval(mins => app_private.setting_int('message_edit_window_minutes', 15)) then
    perform app_private.err('edit_window_passed');
  end if;
  if char_length(btrim(coalesce(p_body, ''))) not between 1 and 4000 then
    perform app_private.err('invalid_message');
  end if;
  update public.messages set body = p_body, edited_at = now() where id = p_message;
end;
$$;

create or replace function public.delete_message_for_all(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_m public.messages;
begin
  select * into v_m from public.messages where id = p_message for update;
  if not found or v_m.sender_id is distinct from v_uid then
    perform app_private.err('not_found');
  end if;
  if v_m.created_at < now() - make_interval(mins => app_private.setting_int('message_unsend_window_minutes', 60)) then
    perform app_private.err('unsend_window_passed');
  end if;
  update public.messages set deleted_for_all_at = now(), body = null where id = p_message;
  if v_m.media_path is not null then
    insert into app_private.storage_cleanup (bucket, path) values ('chat-media', v_m.media_path);
    update public.messages set media_path = null where id = p_message;
  end if;
end;
$$;

create or replace function public.hide_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if not exists (select 1 from public.messages m where m.id = p_message
                 and app_private.is_conversation_member(m.conversation_id, v_uid)) then
    perform app_private.err('not_found');
  end if;
  insert into public.message_hidden (user_id, message_id) values (v_uid, p_message) on conflict do nothing;
end;
$$;

create or replace function public.react_to_message(p_message uuid, p_emoji text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if not exists (select 1 from public.messages m where m.id = p_message and m.deleted_for_all_at is null
                 and app_private.is_conversation_member(m.conversation_id, v_uid)) then
    perform app_private.err('not_found');
  end if;
  if p_emoji is null or p_emoji = '' then
    delete from public.message_reactions where message_id = p_message and user_id = v_uid;
  else
    perform app_private.rate_limit('message_reaction', 60, interval '1 minute');
    insert into public.message_reactions (message_id, user_id, emoji) values (p_message, v_uid, left(p_emoji, 16))
    on conflict (message_id, user_id) do update set emoji = excluded.emoji, created_at = now();
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Triggers: validation, conversation ordering, push fan-out
-- ---------------------------------------------------------------------------
create or replace function app_private.messages_before_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- RLS already restricts clients to their own non-system messages; system rows come from RPCs.
  if new.kind = 'system' then
    return new;
  end if;
  new.created_at := now();
  new.edited_at := null;
  new.deleted_for_all_at := null;
  if new.media_path is not null and split_part(new.media_path, '/', 1) <> new.conversation_id::text then
    raise exception using errcode = 'P0001', message = 'invalid_media_path';
  end if;
  if new.reply_to_id is not null and not exists (
      select 1 from public.messages r where r.id = new.reply_to_id and r.conversation_id = new.conversation_id) then
    new.reply_to_id := null;
  end if;
  perform app_private.hit_rate_limit(new.sender_id::text, 'send_message', 60, interval '1 minute');
  return new;
end;
$$;

create trigger messages_before_insert before insert on public.messages
for each row execute function app_private.messages_before_insert();

create or replace function app_private.messages_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  r record;
  v_show boolean;
  v_name text;
begin
  update public.conversations set last_message_at = new.created_at where id = new.conversation_id;
  if new.kind = 'system' then
    return null;
  end if;
  update public.conversation_members set archived = false, typing_until = null
  where conversation_id = new.conversation_id and (user_id <> new.sender_id or typing_until is not null);
  select display_name into v_name from public.profiles where id = new.sender_id;
  for r in select cm.user_id from public.conversation_members cm
           where cm.conversation_id = new.conversation_id and cm.user_id <> new.sender_id and cm.left_at is null
             and (cm.muted_until is null or cm.muted_until < now()) loop
    select coalesce(np.show_previews, false) into v_show from public.notification_preferences np where np.user_id = r.user_id;
    perform app_private.enqueue_push(r.user_id, 'messages', 'push_message',
      case when coalesce(v_show, false) and new.kind = 'text' then left(new.body, 120) end,
      jsonb_build_object('conversation_id', new.conversation_id, 'message_id', new.id,
                         'sender_name', case when coalesce(v_show, false) then v_name end),
      'conv:' || new.conversation_id::text, false, null);
  end loop;
  return null;
end;
$$;

create trigger messages_after_insert after insert on public.messages
for each row execute function app_private.messages_after_insert();

-- Clients never UPDATE messages directly (no UPDATE grant); edits and deletions go through RPCs.

-- Media removal queue processed by the `maintenance` Edge Function with the service role.
create table app_private.storage_cleanup (
  id bigint generated always as identity primary key,
  bucket text not null,
  path text not null,
  created_at timestamptz not null default now(),
  processed_at timestamptz,
  error text
);
create index storage_cleanup_pending_idx on app_private.storage_cleanup (created_at) where processed_at is null;
