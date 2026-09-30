-- Sit & Talk — server-side authorization for Agora tokens.
-- The `agora-token` Edge Function calls this with the caller's JWT; the channel name, UID and
-- role always come from here, never from the request body.

create or replace function public.rtc_authorize(p_kind text, p_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_s public.call_sessions;
  v_me public.call_participants;
  v_r public.rooms;
  v_m public.room_members;
  v_peer uuid;
begin
  perform app_private.rate_limit('rtc_token', 40, interval '10 minutes');
  if app_private.is_restricted(v_uid, 'suspension') then
    perform app_private.err('account_restricted', 'suspension');
  end if;

  if p_kind = 'call' then
    select * into v_s from public.call_sessions where id = p_id;
    select * into v_me from public.call_participants where session_id = p_id and user_id = v_uid;
    if v_s.id is null or v_me.user_id is null or v_me.left_at is not null then
      perform app_private.err('not_found');
    end if;
    if v_s.status not in ('connecting', 'active') then
      perform app_private.err('call_ended');
    end if;
    if v_s.mode = 'text' then
      perform app_private.err('invalid_mode');
    end if;
    select user_id into v_peer from public.call_participants where session_id = p_id and user_id <> v_uid;
    if app_private.is_blocked(v_uid, v_peer) then
      perform app_private.err('not_found');
    end if;
    if v_s.kind = 'random' and app_private.is_restricted(v_uid, 'matching') then
      perform app_private.err('account_restricted', 'matching');
    end if;
    return jsonb_build_object('channel_name', v_s.channel_name, 'uid', v_me.rtc_uid, 'role', 'publisher',
                              'mode', v_s.mode, 'ttl_seconds', 3600);
  elsif p_kind = 'room' then
    select * into v_r from public.rooms where id = p_id;
    select * into v_m from public.room_members where room_id = p_id and user_id = v_uid;
    if v_r.id is null or v_m.user_id is null then
      perform app_private.err('not_room_member');
    end if;
    if v_r.status <> 'open' then
      perform app_private.err('room_closed');
    end if;
    if exists (select 1 from public.room_bans where room_id = p_id and user_id = v_uid) then
      perform app_private.err('room_banned');
    end if;
    -- Listeners get subscriber tokens; a shorter TTL for listeners bounds how long a demoted
    -- or removed user can keep a stale token.
    return jsonb_build_object('channel_name', v_r.channel_name, 'uid', v_m.rtc_uid,
                              'role', case when v_m.role = 'listener' then 'subscriber' else 'publisher' end,
                              'mode', 'voice',
                              'ttl_seconds', case when v_m.role = 'listener' then 1800 else 900 end);
  end if;
  perform app_private.err('invalid_request');
  return null;
end;
$$;

revoke execute on function public.rtc_authorize(text, uuid) from public, anon;
grant execute on function public.rtc_authorize(text, uuid) to authenticated;

-- Service role (rtc-moderation Edge Function): channel + Agora UID of a removed room member.
create or replace function public.rtc_kick_target(p_room uuid, p_user uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object('channel_name', r.channel_name, 'uid', ids.rtc_uid)
  from public.rooms r, app_private.rtc_ids ids
  where r.id = p_room and ids.user_id = p_user;
$$;

revoke execute on function public.rtc_kick_target(uuid, uuid) from public, anon, authenticated;
grant execute on function public.rtc_kick_target(uuid, uuid) to service_role;
