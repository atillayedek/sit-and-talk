-- Sit & Talk — row level security, least-privilege grants, Realtime publication, scheduled jobs.
--
-- Principles
-- * RLS is enabled on every table in `public`. Being `authenticated` never means "can read all rows".
-- * Default Supabase grants are revoked; each table gets only the privileges it needs.
-- * Writes to sensitive state (wallets, entitlements, roles, moderation, matches) happen only in
--   SECURITY DEFINER RPCs or service-role Edge Functions.

-- ---------------------------------------------------------------------------
-- Reset privileges
-- ---------------------------------------------------------------------------
revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke all on all functions in schema public from public, anon, authenticated;
revoke all on all functions in schema app_private from public, anon, authenticated;
revoke all on all tables in schema app_private from public, anon, authenticated;

grant usage on schema app_private to authenticated;

-- ---------------------------------------------------------------------------
-- Enable RLS everywhere
-- ---------------------------------------------------------------------------
do $$
declare
  t record;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('alter table public.%I enable row level security', t.tablename);
  end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Configuration
-- ---------------------------------------------------------------------------
grant select on public.app_settings, public.feature_flags, public.interests, public.gift_catalog to anon, authenticated;
create policy app_settings_read on public.app_settings for select to anon, authenticated using (is_public);
create policy feature_flags_read on public.feature_flags for select to anon, authenticated using (true);
create policy interests_read on public.interests for select to anon, authenticated using (active);
create policy gift_catalog_read on public.gift_catalog for select to anon, authenticated using (active);

grant select on public.admin_roles to authenticated;
create policy admin_roles_self on public.admin_roles for select to authenticated using (user_id = (select auth.uid()));

grant select on public.audit_logs to authenticated;
create policy audit_logs_staff on public.audit_logs for select to authenticated using ((select public.is_staff()));

-- ---------------------------------------------------------------------------
-- Profiles and private data
-- ---------------------------------------------------------------------------
grant select on public.profiles to authenticated;
grant update (display_name, bio, country_code) on public.profiles to authenticated;
create policy profiles_read on public.profiles for select to authenticated
  using (id = (select auth.uid()) or not app_private.is_blocked(id, (select auth.uid())));
create policy profiles_update_self on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

grant select on public.user_private to authenticated;
grant update (message_policy, call_policy, online_visibility, last_seen_visibility, read_receipts,
              media_from_non_friends, share_avatar_in_random, profile_discoverable) on public.user_private to authenticated;
create policy user_private_self_read on public.user_private for select to authenticated using (user_id = (select auth.uid()));
create policy user_private_self_update on public.user_private for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant select on public.user_consents to authenticated;
create policy user_consents_self on public.user_consents for select to authenticated using (user_id = (select auth.uid()));

grant select on public.user_interests to authenticated;
create policy user_interests_read on public.user_interests for select to authenticated
  using (user_id = (select auth.uid()) or (is_public and not app_private.is_blocked(user_id, (select auth.uid()))));

grant select on public.user_languages to authenticated;
create policy user_languages_read on public.user_languages for select to authenticated
  using (user_id = (select auth.uid()) or not app_private.is_blocked(user_id, (select auth.uid())));

grant select on public.user_presence to authenticated;
create policy user_presence_self on public.user_presence for select to authenticated using (user_id = (select auth.uid()));

grant select on public.account_restrictions to authenticated;
create policy account_restrictions_self on public.account_restrictions for select to authenticated using (user_id = (select auth.uid()));

grant select on public.user_blocks to authenticated;
create policy user_blocks_self on public.user_blocks for select to authenticated using (blocker_id = (select auth.uid()));

grant select on public.friend_requests to authenticated;
create policy friend_requests_party on public.friend_requests for select to authenticated
  using (sender_id = (select auth.uid()) or receiver_id = (select auth.uid()));

grant select on public.friendships to authenticated;
create policy friendships_self on public.friendships for select to authenticated using (user_id = (select auth.uid()));

grant select, insert, update on public.notification_preferences to authenticated;
create policy notification_preferences_self on public.notification_preferences for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant select on public.notifications to authenticated;
grant update (read_at) on public.notifications to authenticated;
create policy notifications_self_read on public.notifications for select to authenticated using (user_id = (select auth.uid()));
create policy notifications_self_update on public.notifications for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant select, delete on public.user_devices to authenticated;
create policy user_devices_self_read on public.user_devices for select to authenticated using (user_id = (select auth.uid()));
create policy user_devices_self_delete on public.user_devices for delete to authenticated using (user_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Matching and calls
-- ---------------------------------------------------------------------------
create or replace function app_private.is_call_participant(p_session uuid, p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.call_participants where session_id = p_session and user_id = p_user);
$$;

grant select on public.matchmaking_queue to authenticated;
create policy matchmaking_queue_self on public.matchmaking_queue for select to authenticated using (user_id = (select auth.uid()));

-- matches: no client access at all (peer ids stay hidden); state comes from RPCs.

grant select on public.call_sessions to authenticated;
create policy call_sessions_participant on public.call_sessions for select to authenticated
  using (app_private.is_call_participant(id, (select auth.uid())));

grant select on public.call_participants to authenticated;
create policy call_participants_self on public.call_participants for select to authenticated using (user_id = (select auth.uid()));

grant select on public.call_feedback to authenticated;
create policy call_feedback_self on public.call_feedback for select to authenticated using (user_id = (select auth.uid()));

grant select on public.match_messages to authenticated;
create policy match_messages_participant on public.match_messages for select to authenticated
  using (app_private.is_call_participant(session_id, (select auth.uid())));

-- ---------------------------------------------------------------------------
-- Rooms
-- ---------------------------------------------------------------------------
grant select on public.rooms to authenticated;
create policy rooms_visible on public.rooms for select to authenticated
  using (app_private.can_see_room(id, (select auth.uid())));

grant select on public.room_members to authenticated;
create policy room_members_visible on public.room_members for select to authenticated
  using (app_private.can_see_room(room_id, (select auth.uid())));

grant select on public.room_bans to authenticated;
create policy room_bans_moderators on public.room_bans for select to authenticated
  using (app_private.room_role_of(room_id, (select auth.uid())) in ('owner', 'moderator'));

grant select on public.room_invites to authenticated;
create policy room_invites_party on public.room_invites for select to authenticated
  using (invitee_id = (select auth.uid()) or inviter_id = (select auth.uid()));

grant select on public.room_speaker_requests to authenticated;
create policy room_speaker_requests_visible on public.room_speaker_requests for select to authenticated
  using (user_id = (select auth.uid()) or app_private.room_role_of(room_id, (select auth.uid())) in ('owner', 'moderator'));

grant select on public.room_messages to authenticated;
grant insert (room_id, user_id, body) on public.room_messages to authenticated;
create policy room_messages_read on public.room_messages for select to authenticated
  using (app_private.is_room_member(room_id, (select auth.uid())) and not app_private.is_blocked(user_id, (select auth.uid())));
create policy room_messages_insert on public.room_messages for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and app_private.is_room_member(room_id, (select auth.uid()))
    and not app_private.is_restricted((select auth.uid()), 'messaging')
    and exists (select 1 from public.rooms r where r.id = room_id and r.status = 'open' and r.text_chat_enabled));

grant select on public.room_reactions to authenticated;
create policy room_reactions_read on public.room_reactions for select to authenticated
  using (app_private.is_room_member(room_id, (select auth.uid())));

grant select on public.room_favorites to authenticated;
create policy room_favorites_self on public.room_favorites for select to authenticated using (user_id = (select auth.uid()));

grant select on public.room_events to authenticated;
create policy room_events_read on public.room_events for select to authenticated
  using (created_by is null or not app_private.is_blocked(created_by, (select auth.uid())));

grant select on public.room_event_subscribers to authenticated;
create policy room_event_subscribers_self on public.room_event_subscribers for select to authenticated
  using (user_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Messaging
-- ---------------------------------------------------------------------------
grant select on public.conversations to authenticated;
create policy conversations_member on public.conversations for select to authenticated
  using (app_private.is_conversation_member(id, (select auth.uid())));

grant select on public.conversation_members to authenticated;
create policy conversation_members_member on public.conversation_members for select to authenticated
  using (app_private.is_conversation_member(conversation_id, (select auth.uid())));

grant select on public.conversation_read_state to authenticated;
create policy conversation_read_state_self on public.conversation_read_state for select to authenticated
  using (user_id = (select auth.uid()));

grant select on public.messages to authenticated;
grant insert (id, conversation_id, sender_id, kind, body, media_path, media_mime, media_duration_ms, reply_to_id)
  on public.messages to authenticated;
create policy messages_read on public.messages for select to authenticated
  using (
    app_private.is_conversation_member(conversation_id, (select auth.uid()))
    and not exists (select 1 from public.message_hidden h where h.message_id = id and h.user_id = (select auth.uid()))
    and created_at > coalesce((select cm.cleared_before from public.conversation_members cm
                               where cm.conversation_id = messages.conversation_id and cm.user_id = (select auth.uid())),
                              '-infinity'::timestamptz));
create policy messages_insert on public.messages for insert to authenticated
  with check (
    sender_id = (select auth.uid())
    and kind in ('text', 'image', 'voice')
    and app_private.can_send_message(conversation_id, (select auth.uid()), kind));

grant select on public.message_hidden to authenticated;
create policy message_hidden_self on public.message_hidden for select to authenticated using (user_id = (select auth.uid()));

grant select on public.message_reactions to authenticated;
create policy message_reactions_member on public.message_reactions for select to authenticated
  using (exists (select 1 from public.messages m where m.id = message_id
                 and app_private.is_conversation_member(m.conversation_id, (select auth.uid()))));

-- ---------------------------------------------------------------------------
-- Feed
-- ---------------------------------------------------------------------------
grant select on public.posts to authenticated;
create policy posts_visible on public.posts for select to authenticated using (app_private.can_view_post(id, (select auth.uid())));

grant select on public.post_media to authenticated;
create policy post_media_visible on public.post_media for select to authenticated using (app_private.can_view_post(post_id, (select auth.uid())));

grant select on public.post_comments to authenticated;
create policy post_comments_visible on public.post_comments for select to authenticated
  using (app_private.can_view_post(post_id, (select auth.uid())) and not app_private.is_blocked(author_id, (select auth.uid()))
         and (moderation = 'visible' or author_id = (select auth.uid())));

grant select on public.post_reactions, public.saved_posts, public.hidden_posts to authenticated;
create policy post_reactions_self on public.post_reactions for select to authenticated using (user_id = (select auth.uid()));
create policy saved_posts_self on public.saved_posts for select to authenticated using (user_id = (select auth.uid()));
create policy hidden_posts_self on public.hidden_posts for select to authenticated using (user_id = (select auth.uid()));

grant select on public.stories to authenticated;
create policy stories_visible on public.stories for select to authenticated
  using (author_id = (select auth.uid()) or (
    expires_at > now() and moderation = 'visible'
    and not app_private.is_blocked(author_id, (select auth.uid()))
    and (visibility = 'public' or app_private.are_friends(author_id, (select auth.uid())))));

-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
grant select on public.purchase_records, public.subscription_entitlements, public.wallet_accounts, public.wallet_transactions,
  public.gift_transactions to authenticated;
create policy purchase_records_self on public.purchase_records for select to authenticated using (user_id = (select auth.uid()));
create policy subscription_entitlements_self on public.subscription_entitlements for select to authenticated using (user_id = (select auth.uid()));
create policy wallet_accounts_self on public.wallet_accounts for select to authenticated using (user_id = (select auth.uid()));
create policy wallet_transactions_self on public.wallet_transactions for select to authenticated using (user_id = (select auth.uid()));
create policy gift_transactions_party on public.gift_transactions for select to authenticated
  using (sender_id = (select auth.uid()) or recipient_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Moderation and account
-- ---------------------------------------------------------------------------
-- reports / moderation_actions: no direct client access (report rows contain resolved target ids).
grant select on public.appeals to authenticated;
create policy appeals_self on public.appeals for select to authenticated using (user_id = (select auth.uid()));

grant select on public.account_deletion_requests to authenticated;
create policy account_deletion_requests_self on public.account_deletion_requests for select to authenticated
  using (user_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Function privileges
-- ---------------------------------------------------------------------------
grant execute on all functions in schema public to authenticated;

-- Service-role only (Edge Functions).
revoke execute on function
  public.edge_rate_limit(uuid, text, int, int),
  public.record_verified_purchase(uuid, text, text, text, text, text, timestamptz, timestamptz, boolean, text),
  public.mark_purchase_acknowledged(text, boolean),
  public.prepare_account_deletion(uuid),
  public.claim_storage_cleanup(int),
  public.report_storage_cleanup_failure(bigint, text)
from authenticated;
grant execute on function
  public.edge_rate_limit(uuid, text, int, int),
  public.record_verified_purchase(uuid, text, text, text, text, text, timestamptz, timestamptz, boolean, text),
  public.mark_purchase_acknowledged(text, boolean),
  public.prepare_account_deletion(uuid),
  public.claim_storage_cleanup(int),
  public.report_storage_cleanup_failure(bigint, text)
to service_role;

-- The version / maintenance gate is readable before sign-in.
grant execute on function public.app_bootstrap(int) to anon;

-- ---------------------------------------------------------------------------
-- Realtime: change events only; Postgres stays the source of truth.
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  foreach t in array array[
    'matchmaking_queue', 'call_sessions', 'match_messages', 'rooms', 'room_members', 'room_messages', 'room_reactions',
    'room_speaker_requests', 'conversation_members', 'messages', 'message_reactions', 'notifications', 'friend_requests'] loop
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Scheduled work (pg_cron + pg_net when the project has them enabled)
-- ---------------------------------------------------------------------------
-- Operator-provided runtime configuration (never committed):
--   insert into app_private.runtime_config values ('functions_base_url', 'https://<ref>.supabase.co/functions/v1'),
--                                                ('internal_hook_secret', '<random secret also set as INTERNAL_HOOK_SECRET>');
create table app_private.runtime_config (
  key text primary key,
  value text not null
);

create or replace function app_private.invoke_internal_function(p_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_base text;
  v_secret text;
begin
  select value into v_base from app_private.runtime_config where key = 'functions_base_url';
  select value into v_secret from app_private.runtime_config where key = 'internal_hook_secret';
  if v_base is null or v_secret is null or not exists (select 1 from pg_extension where extname = 'pg_net') then
    return;
  end if;
  execute 'select net.http_post(url := $1, headers := $2, body := $3, timeout_milliseconds := 5000)'
    using v_base || '/' || p_name,
          jsonb_build_object('content-type', 'application/json', 'x-internal-secret', v_secret),
          '{}'::jsonb;
exception when others then
  -- Delivery is retried by the next cron tick; never fail the caller's transaction.
  raise warning 'invoke_internal_function(%) failed: %', p_name, sqlerrm;
end;
$$;

create or replace function app_private.push_outbox_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.invoke_internal_function('send-push');
  return null;
end;
$$;

create trigger push_outbox_dispatch after insert on app_private.push_outbox
for each statement execute function app_private.push_outbox_after_insert();

-- Service role: claim pending pushes for delivery.
create or replace function public.claim_push_batch(p_limit int default 100)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  with batch as (
    select id from app_private.push_outbox
    where status = 'pending' and (expires_at is null or expires_at > now()) and attempts < 5
    order by created_at limit least(greatest(p_limit, 1), 500) for update skip locked
  ), claimed as (
    update app_private.push_outbox o set attempts = o.attempts + 1
    from batch where o.id = batch.id
    returning jsonb_build_object(
      'id', o.id, 'user_id', o.user_id, 'category', o.category, 'title_key', o.title_key, 'body', o.body,
      'data', o.data, 'collapse_key', o.collapse_key, 'high_priority', o.high_priority, 'expires_at', o.expires_at,
      'tokens', (select coalesce(jsonb_agg(d.fcm_token), '[]'::jsonb) from public.user_devices d where d.user_id = o.user_id)) as item
  )
  select coalesce(jsonb_agg(item), '[]'::jsonb) from claimed;
$$;

create or replace function public.complete_push(p_id bigint, p_status text, p_error text, p_invalid_tokens text[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  update app_private.push_outbox set status = case when p_status in ('sent', 'skipped', 'failed') then p_status else 'failed' end,
    last_error = left(p_error, 500), processed_at = now()
  where id = p_id;
  if coalesce(array_length(p_invalid_tokens, 1), 0) > 0 then
    delete from public.user_devices where fcm_token = any (p_invalid_tokens);
  end if;
end;
$$;

revoke execute on function public.claim_push_batch(int), public.complete_push(bigint, text, text, text[]) from public, anon, authenticated;
grant execute on function public.claim_push_batch(int), public.complete_push(bigint, text, text, text[]) to service_role;

create or replace function app_private.cron_tick()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.sweep();
  update app_private.push_outbox set status = 'skipped', processed_at = now()
  where status = 'pending' and expires_at is not null and expires_at < now();
  delete from app_private.push_outbox where created_at < now() - interval '14 days';
  delete from public.notifications where created_at < now() - interval '90 days';
  if exists (select 1 from app_private.push_outbox where status = 'pending') then
    perform app_private.invoke_internal_function('send-push');
  end if;
  if exists (select 1 from app_private.storage_cleanup where processed_at is null) then
    perform app_private.invoke_internal_function('maintenance');
  end if;
end;
$$;

do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    begin
      create extension if not exists pg_cron;
      perform cron.schedule('sitandtalk-tick', '* * * * *', 'select app_private.cron_tick()');
    exception when others then
      raise notice 'pg_cron not configured (%); heartbeat RPCs still run the sweeper.', sqlerrm;
    end;
  end if;
  if exists (select 1 from pg_available_extensions where name = 'pg_net') then
    begin
      create extension if not exists pg_net with schema extensions;
    exception when others then
      raise notice 'pg_net not available (%); push delivery must be triggered by the scheduled workflow.', sqlerrm;
    end;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Final privilege pass (runs last so it also covers functions created above)
-- ---------------------------------------------------------------------------
revoke all on all functions in schema app_private from public, anon, authenticated;
-- Helpers evaluated inside RLS / storage policies run as the calling role.
grant execute on function
  app_private.is_blocked(uuid, uuid),
  app_private.are_friends(uuid, uuid),
  app_private.can_see_room(uuid, uuid),
  app_private.is_room_member(uuid, uuid),
  app_private.room_role_of(uuid, uuid),
  app_private.is_conversation_member(uuid, uuid),
  app_private.can_send_message(uuid, uuid, public.message_kind),
  app_private.can_view_post(uuid, uuid),
  app_private.is_restricted(uuid, public.restriction_kind),
  app_private.storage_can_read(text, text, uuid),
  app_private.storage_can_write(text, text, uuid),
  app_private.is_call_participant(uuid, uuid)
to authenticated;

do $$
declare
  f record;
begin
  -- Supabase's default privileges grant EXECUTE to anon on new functions; only app_bootstrap is public.
  for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and p.prokind = 'f'
             and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e') loop
    execute format('revoke execute on function %s from public, anon', f.sig);
  end loop;
end;
$$;
grant execute on function public.app_bootstrap(int) to anon;
