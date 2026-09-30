-- Sit & Talk — reports, moderation actions, appeals and the staff API.
-- Report counts alone never punish anyone: staff decide, every decision is audited.

create type public.report_target as enum ('user', 'message', 'post', 'comment', 'story', 'room', 'room_message', 'call');
create type public.report_reason as enum (
  'harassment', 'hate', 'sexual_content', 'minor_safety', 'violence', 'self_harm', 'spam', 'scam', 'impersonation',
  'underage', 'other');
create type public.report_status as enum ('open', 'reviewing', 'actioned', 'dismissed');

create table public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid references auth.users(id) on delete set null,
  target_type public.report_target not null,
  target_id text not null,
  target_user_id uuid references auth.users(id) on delete set null,
  reason public.report_reason not null,
  details text not null default '' check (char_length(details) <= 1000),
  evidence_path text,
  context jsonb not null default '{}'::jsonb,
  status public.report_status not null default 'open',
  priority int not null default 0,
  resolution text,
  resolved_by uuid references auth.users(id) on delete set null,
  resolved_at timestamptz,
  created_at timestamptz not null default now()
);
create index reports_open_idx on public.reports (priority desc, created_at) where status in ('open', 'reviewing');
create index reports_target_user_idx on public.reports (target_user_id);
create unique index reports_dedupe_key on public.reports (reporter_id, target_type, target_id) where status in ('open', 'reviewing');

create table public.moderation_actions (
  id uuid primary key default gen_random_uuid(),
  target_user_id uuid references auth.users(id) on delete set null,
  action text not null check (action in ('warn', 'restrict', 'lift_restriction', 'remove_content', 'close_room', 'dismiss')),
  report_id uuid references public.reports(id) on delete set null,
  restriction_id uuid references public.account_restrictions(id) on delete set null,
  content_type text,
  content_id text,
  reason text not null,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

create table public.appeals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  restriction_id uuid references public.account_restrictions(id) on delete set null,
  moderation_action_id uuid references public.moderation_actions(id) on delete set null,
  body text not null check (char_length(btrim(body)) between 10 and 2000),
  status text not null default 'open' check (status in ('open', 'upheld', 'overturned')),
  decision_note text,
  decided_by uuid references auth.users(id) on delete set null,
  decided_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index appeals_one_open_key on public.appeals (user_id, restriction_id) where status = 'open';

-- ---------------------------------------------------------------------------
-- User-facing
-- ---------------------------------------------------------------------------
create or replace function public.create_report(
  p_target_type public.report_target, p_target_id text, p_reason public.report_reason, p_details text,
  p_evidence_path text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_target_user uuid;
  v_context jsonb := '{}'::jsonb;
  v_id uuid;
  v_dismissed int;
  v_weight int;
begin
  perform app_private.rate_limit('create_report', 20, interval '1 hour');
  -- Resolve the reported account on the server and check the reporter could actually see the content.
  case p_target_type
    when 'user' then
      v_target_user := p_target_id::uuid;
      if not exists (select 1 from public.profiles where id = v_target_user) then v_target_user := null; end if;
    when 'message' then
      select m.sender_id, jsonb_build_object('conversation_id', m.conversation_id, 'body', m.body, 'kind', m.kind, 'media_path', m.media_path)
        into v_target_user, v_context
      from public.messages m where m.id = p_target_id::uuid and app_private.is_conversation_member(m.conversation_id, v_uid);
    when 'post' then
      select p.author_id, jsonb_build_object('body', p.body, 'kind', p.kind) into v_target_user, v_context
      from public.posts p where p.id = p_target_id::uuid and app_private.can_view_post(p.id, v_uid);
    when 'comment' then
      select c.author_id, jsonb_build_object('body', c.body, 'post_id', c.post_id) into v_target_user, v_context
      from public.post_comments c where c.id = p_target_id::uuid and app_private.can_view_post(c.post_id, v_uid);
    when 'story' then
      select s.author_id, jsonb_build_object('caption', s.caption, 'media_path', s.media_path) into v_target_user, v_context
      from public.stories s where s.id = p_target_id::uuid;
    when 'room' then
      select r.owner_id, jsonb_build_object('title', r.title, 'description', r.description) into v_target_user, v_context
      from public.rooms r where r.id = p_target_id::uuid and app_private.can_see_room(r.id, v_uid);
    when 'room_message' then
      select rm.user_id, jsonb_build_object('room_id', rm.room_id, 'body', rm.body) into v_target_user, v_context
      from public.room_messages rm where rm.id = p_target_id::uuid and app_private.is_room_member(rm.room_id, v_uid);
    when 'call' then
      -- Anonymous peer: resolved here, never revealed to the reporter. Last text messages are attached as context.
      select peer.user_id, jsonb_build_object(
               'mode', cs.mode, 'kind', cs.kind, 'started_at', cs.started_at, 'ended_at', cs.ended_at,
               'peer_alias', peer.alias,
               'recent_text', coalesce((select jsonb_agg(jsonb_build_object('slot', mm.sender_slot, 'body', mm.body, 'at', mm.created_at)
                                                         order by mm.created_at)
                                        from (select * from public.match_messages where session_id = cs.id
                                              order by created_at desc limit 30) mm), '[]'::jsonb))
        into v_target_user, v_context
      from public.call_participants me
      join public.call_participants peer on peer.session_id = me.session_id and peer.user_id <> me.user_id
      join public.call_sessions cs on cs.id = me.session_id
      where me.session_id = p_target_id::uuid and me.user_id = v_uid;
  end case;

  if v_target_user is null or v_target_user = v_uid then
    perform app_private.err('invalid_target');
  end if;
  if p_evidence_path is not null and split_part(p_evidence_path, '/', 1) <> v_uid::text then
    perform app_private.err('invalid_media');
  end if;

  -- Reporters whose reports are mostly dismissed carry less weight (protects against mass false reporting).
  select count(*) filter (where status = 'dismissed') into v_dismissed from public.reports where reporter_id = v_uid;
  v_weight := case when v_dismissed >= 10 then 0 when v_dismissed >= 3 then 1 else 2 end;

  insert into public.reports (reporter_id, target_type, target_id, target_user_id, reason, details, evidence_path, context, priority)
  values (v_uid, p_target_type, p_target_id, v_target_user, p_reason, coalesce(btrim(p_details), ''), p_evidence_path, v_context,
          v_weight + case when p_reason in ('minor_safety', 'self_harm', 'violence', 'underage') then 10 else 0 end)
  on conflict (reporter_id, target_type, target_id) where status in ('open', 'reviewing')
  do update set details = excluded.details, reason = excluded.reason
  returning id into v_id;

  -- Multiple distinct reporters raise priority for review; they never trigger automatic sanctions.
  update public.reports r set priority = r.priority + 1
  where r.target_user_id = v_target_user and r.status = 'open' and r.id <> v_id and v_weight > 0;
  return v_id;
end;
$$;

create or replace function public.my_reports()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', r.id, 'target_type', r.target_type, 'reason', r.reason,
                                               'status', r.status, 'created_at', r.created_at, 'resolved_at', r.resolved_at)
                            order by r.created_at desc), '[]'::jsonb)
  from public.reports r where r.reporter_id = auth.uid();
$$;

create or replace function public.my_restrictions()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', r.id, 'kind', r.kind, 'reason', r.reason, 'starts_at', r.starts_at,
                                               'ends_at', r.ends_at, 'created_at', r.created_at,
                                               'appeal', (select jsonb_build_object('id', a.id, 'status', a.status, 'decision_note', a.decision_note)
                                                          from public.appeals a where a.restriction_id = r.id
                                                          order by a.created_at desc limit 1))
                            order by r.created_at desc), '[]'::jsonb)
  from public.account_restrictions r
  where r.user_id = auth.uid() and r.lifted_at is null and (r.ends_at is null or r.ends_at > now());
$$;

create or replace function public.create_appeal(p_restriction uuid, p_body text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_id uuid;
begin
  if not exists (select 1 from public.account_restrictions where id = p_restriction and user_id = v_uid) then
    perform app_private.err('not_found');
  end if;
  perform app_private.rate_limit('create_appeal', 5, interval '1 day');
  insert into public.appeals (user_id, restriction_id, body) values (v_uid, p_restriction, btrim(p_body))
  returning id into v_id;
  return v_id;
exception when unique_violation then
  perform app_private.err('appeal_exists');
  return null;
end;
$$;

-- Account gate evaluated at app start.
create or replace function public.app_bootstrap(p_version_code int)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  return jsonb_build_object(
    'min_version_code', app_private.setting_int('min_version_code', 1),
    'update_required', coalesce(p_version_code, 0) < app_private.setting_int('min_version_code', 1),
    'maintenance', coalesce((select value from public.app_settings where key = 'maintenance'), '{}'::jsonb),
    'announcement', coalesce((select value from public.app_settings where key = 'announcement'), '{}'::jsonb),
    'flags', coalesce((select jsonb_object_agg(key, enabled) from public.feature_flags), '{}'::jsonb),
    'settings', coalesce((select jsonb_object_agg(key, value) from public.app_settings where is_public
                            and key in ('call_initial_seconds', 'call_extension_seconds', 'match_accept_seconds',
                                        'message_edit_window_minutes', 'message_unsend_window_minutes')), '{}'::jsonb),
    'profile_complete', v_uid is not null and exists (
      select 1 from public.user_private up join public.profiles p on p.id = up.user_id
      where up.user_id = v_uid and up.onboarding_completed_at is not null),
    'is_staff', v_uid is not null and exists (select 1 from public.admin_roles where user_id = v_uid),
    'staff_role', (select role from public.admin_roles where user_id = v_uid),
    'suspended', v_uid is not null and app_private.is_restricted(v_uid, 'suspension'),
    'restrictions', case when v_uid is null then '[]'::jsonb else public.my_restrictions() end,
    'deletion_pending', v_uid is not null and exists (
      select 1 from public.account_deletion_requests where user_id = v_uid and status = 'pending'),
    'server_now', now());
end;
$$;

-- ---------------------------------------------------------------------------
-- Staff API (every function checks the role on the server and writes the audit log)
-- ---------------------------------------------------------------------------
create or replace function public.admin_dashboard()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff();
  return jsonb_build_object(
    'users_total', (select count(*) from public.profiles),
    'users_new_7d', (select count(*) from public.profiles where created_at > now() - interval '7 days'),
    'active_24h', (select count(*) from public.user_presence where last_seen_at > now() - interval '24 hours'),
    'active_calls', (select count(*) from public.call_sessions where status in ('connecting', 'active')),
    'queue_waiting', (select count(*) from public.matchmaking_queue where status = 'waiting'),
    'open_rooms', (select count(*) from public.rooms where status = 'open'),
    'open_reports', (select count(*) from public.reports where status in ('open', 'reviewing')),
    'open_appeals', (select count(*) from public.appeals where status = 'open'),
    'restricted_accounts', (select count(distinct user_id) from public.account_restrictions
                            where lifted_at is null and kind <> 'warning' and (ends_at is null or ends_at > now())),
    'pending_content', (select count(*) from public.posts where moderation = 'pending' and deleted_at is null),
    'purchases_30d', (select count(*) from public.purchase_records where created_at > now() - interval '30 days' and state = 'purchased'),
    'active_subscriptions', (select count(*) from public.subscription_entitlements where status in ('active', 'grace')),
    'push_failed_24h', (select count(*) from app_private.push_outbox where status = 'failed' and created_at > now() - interval '24 hours'),
    'push_pending', (select count(*) from app_private.push_outbox where status = 'pending'),
    'server_now', now());
end;
$$;

create or replace function public.admin_list_reports(p_status public.report_status default 'open', p_limit int default 50)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', r.id, 'target_type', r.target_type, 'target_id', r.target_id, 'reason', r.reason, 'details', r.details,
      'context', r.context, 'evidence_path', r.evidence_path, 'status', r.status, 'priority', r.priority,
      'created_at', r.created_at, 'resolution', r.resolution,
      'reporter', (select jsonb_build_object('id', p.id, 'username', p.username) from public.profiles p where p.id = r.reporter_id),
      'target_user', (select jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name)
                      from public.profiles p where p.id = r.target_user_id),
      'target_report_count', (select count(distinct reporter_id) from public.reports x where x.target_user_id = r.target_user_id),
      'target_active_restrictions', (select count(*) from public.account_restrictions ar
                                     where ar.user_id = r.target_user_id and ar.lifted_at is null and (ar.ends_at is null or ar.ends_at > now()))
    ) order by r.priority desc, r.created_at)
    from (select * from public.reports where status = p_status order by priority desc, created_at
          limit least(greatest(p_limit, 1), 200)) r
  ), '[]'::jsonb);
end;
$$;

create or replace function public.admin_restrict_user(
  p_user uuid, p_kind public.restriction_kind, p_hours int, p_reason text, p_report uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff();
  v_id uuid;
begin
  if char_length(btrim(coalesce(p_reason, ''))) < 3 then
    perform app_private.err('reason_required');
  end if;
  if p_kind = 'ban' and app_private.staff_role_of(v_staff) <> 'admin' then
    perform app_private.err('forbidden');
  end if;
  if exists (select 1 from public.admin_roles where user_id = p_user) and app_private.staff_role_of(v_staff) <> 'admin' then
    perform app_private.err('forbidden');
  end if;
  insert into public.account_restrictions (user_id, kind, reason, ends_at, created_by, report_id)
  values (p_user, p_kind, btrim(p_reason), case when p_hours is null or p_hours <= 0 then null else now() + make_interval(hours => p_hours) end,
          v_staff, p_report)
  returning id into v_id;
  insert into public.moderation_actions (target_user_id, action, report_id, restriction_id, reason, created_by)
  values (p_user, case when p_kind = 'warning' then 'warn' else 'restrict' end, p_report, v_id, btrim(p_reason), v_staff);
  if p_report is not null then
    update public.reports set status = 'actioned', resolved_by = v_staff, resolved_at = now(), resolution = p_kind::text
    where id = p_report;
  end if;
  -- Suspended / banned users are removed from live surfaces immediately.
  if p_kind in ('suspension', 'ban', 'matching') then
    delete from public.matchmaking_queue where user_id = p_user;
    perform app_private.end_session(cp.session_id, 'moderation')
    from public.call_participants cp where cp.user_id = p_user and cp.left_at is null;
  end if;
  if p_kind in ('suspension', 'ban') then
    delete from public.room_members where user_id = p_user;
  end if;
  insert into public.notifications (user_id, kind, entity_type, entity_id, data)
  values (p_user, 'moderation', 'restriction', v_id::text, jsonb_build_object('kind', p_kind));
  perform app_private.audit('restrict_user', 'user', p_user::text, p_reason,
                            jsonb_build_object('kind', p_kind, 'hours', p_hours, 'report', p_report, 'restriction', v_id));
  return v_id;
end;
$$;

create or replace function public.admin_lift_restriction(p_restriction uuid, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff();
  v_user uuid;
begin
  update public.account_restrictions set lifted_at = now() where id = p_restriction and lifted_at is null
  returning user_id into v_user;
  if v_user is null then
    perform app_private.err('not_found');
  end if;
  insert into public.moderation_actions (target_user_id, action, restriction_id, reason, created_by)
  values (v_user, 'lift_restriction', p_restriction, coalesce(btrim(p_reason), ''), v_staff);
  perform app_private.audit('lift_restriction', 'user', v_user::text, p_reason, jsonb_build_object('restriction', p_restriction));
end;
$$;

create or replace function public.admin_resolve_report(p_report uuid, p_dismiss boolean, p_note text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff();
begin
  update public.reports set status = case when p_dismiss then 'dismissed'::public.report_status else 'actioned'::public.report_status end,
    resolution = coalesce(btrim(p_note), ''), resolved_by = v_staff, resolved_at = now()
  where id = p_report and status in ('open', 'reviewing');
  if not found then
    perform app_private.err('not_found');
  end if;
  if p_dismiss then
    insert into public.moderation_actions (action, report_id, reason, created_by) values ('dismiss', p_report, coalesce(p_note, ''), v_staff);
  end if;
  perform app_private.audit(case when p_dismiss then 'dismiss_report' else 'resolve_report' end, 'report', p_report::text, p_note);
end;
$$;

create or replace function public.admin_remove_content(p_type public.report_target, p_id text, p_reason text, p_report uuid default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff();
  v_owner uuid;
begin
  if char_length(btrim(coalesce(p_reason, ''))) < 3 then
    perform app_private.err('reason_required');
  end if;
  case p_type
    when 'post' then
      update public.posts set moderation = 'removed' where id = p_id::uuid returning author_id into v_owner;
    when 'comment' then
      update public.post_comments set moderation = 'removed' where id = p_id::uuid returning author_id into v_owner;
    when 'story' then
      update public.stories set moderation = 'removed' where id = p_id::uuid returning author_id into v_owner;
    when 'message' then
      update public.messages set deleted_for_all_at = now(), body = null where id = p_id::uuid returning sender_id into v_owner;
    when 'room_message' then
      update public.room_messages set deleted_at = now() where id = p_id::uuid returning user_id into v_owner;
    when 'room' then
      update public.rooms set status = 'closed', closed_at = now(), close_reason = 'moderation' where id = p_id::uuid returning owner_id into v_owner;
      delete from public.room_members where room_id = p_id::uuid;
    else
      perform app_private.err('invalid_target');
  end case;
  insert into public.moderation_actions (target_user_id, action, report_id, content_type, content_id, reason, created_by)
  values (v_owner, case when p_type = 'room' then 'close_room' else 'remove_content' end, p_report, p_type::text, p_id, btrim(p_reason), v_staff);
  if p_report is not null then
    update public.reports set status = 'actioned', resolved_by = v_staff, resolved_at = now(), resolution = 'content_removed' where id = p_report;
  end if;
  if v_owner is not null then
    insert into public.notifications (user_id, kind, entity_type, entity_id, data)
    values (v_owner, 'moderation', p_type::text, p_id, jsonb_build_object('action', 'content_removed'));
  end if;
  perform app_private.audit('remove_content', p_type::text, p_id, p_reason, jsonb_build_object('report', p_report));
end;
$$;

create or replace function public.admin_list_appeals()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', a.id, 'body', a.body, 'created_at', a.created_at, 'status', a.status,
      'user', (select jsonb_build_object('id', p.id, 'username', p.username) from public.profiles p where p.id = a.user_id),
      'restriction', (select jsonb_build_object('id', r.id, 'kind', r.kind, 'reason', r.reason, 'ends_at', r.ends_at)
                      from public.account_restrictions r where r.id = a.restriction_id)
    ) order by a.created_at)
    from public.appeals a where a.status = 'open'), '[]'::jsonb);
end;
$$;

create or replace function public.admin_decide_appeal(p_appeal uuid, p_overturn boolean, p_note text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff();
  v_a public.appeals;
begin
  select * into v_a from public.appeals where id = p_appeal and status = 'open' for update;
  if not found then
    perform app_private.err('not_found');
  end if;
  update public.appeals set status = case when p_overturn then 'overturned' else 'upheld' end,
    decision_note = btrim(p_note), decided_by = v_staff, decided_at = now()
  where id = p_appeal;
  if p_overturn and v_a.restriction_id is not null then
    update public.account_restrictions set lifted_at = now() where id = v_a.restriction_id and lifted_at is null;
  end if;
  insert into public.notifications (user_id, kind, entity_type, entity_id, data)
  values (v_a.user_id, 'moderation', 'appeal', p_appeal::text, jsonb_build_object('overturned', p_overturn));
  perform app_private.audit('decide_appeal', 'appeal', p_appeal::text, p_note, jsonb_build_object('overturned', p_overturn));
end;
$$;

create or replace function public.admin_set_flag(p_key text, p_enabled boolean, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff(true);
begin
  update public.feature_flags set enabled = p_enabled, updated_at = now(), updated_by = v_staff where key = p_key;
  if not found then
    perform app_private.err('not_found');
  end if;
  perform app_private.audit('set_flag', 'feature_flag', p_key, p_reason, jsonb_build_object('enabled', p_enabled));
end;
$$;

create or replace function public.admin_set_setting(p_key text, p_value jsonb, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid := app_private.require_staff(true);
  v_old jsonb;
begin
  select value into v_old from public.app_settings where key = p_key for update;
  if not found then
    perform app_private.err('not_found');
  end if;
  if jsonb_typeof(v_old) <> jsonb_typeof(p_value) then
    perform app_private.err('invalid_setting_type');
  end if;
  update public.app_settings set value = p_value, updated_at = now(), updated_by = v_staff where key = p_key;
  perform app_private.audit('set_setting', 'app_setting', p_key, p_reason, jsonb_build_object('old', v_old, 'new', p_value));
end;
$$;

create or replace function public.admin_upsert_interest(p_slug text, p_name_tr text, p_name_en text, p_category text, p_active boolean, p_sort int)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff(true);
  insert into public.interests (slug, name_tr, name_en, category, active, sort)
  values (p_slug, p_name_tr, p_name_en, coalesce(p_category, 'general'), coalesce(p_active, true), coalesce(p_sort, 0))
  on conflict (slug) do update set name_tr = excluded.name_tr, name_en = excluded.name_en, category = excluded.category,
    active = excluded.active, sort = excluded.sort;
  perform app_private.audit('upsert_interest', 'interest', p_slug);
end;
$$;

create or replace function public.admin_upsert_gift(p_code text, p_name_tr text, p_name_en text, p_icon_key text, p_price int, p_active boolean, p_sort int)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff(true);
  insert into public.gift_catalog (code, name_tr, name_en, icon_key, price_coins, active, sort)
  values (p_code, p_name_tr, p_name_en, p_icon_key, p_price, coalesce(p_active, true), coalesce(p_sort, 0))
  on conflict (code) do update set name_tr = excluded.name_tr, name_en = excluded.name_en, icon_key = excluded.icon_key,
    price_coins = excluded.price_coins, active = excluded.active, sort = excluded.sort;
  perform app_private.audit('upsert_gift', 'gift', p_code, null, jsonb_build_object('price', p_price, 'active', p_active));
end;
$$;

create or replace function public.admin_user_summary(p_user uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff();
  perform app_private.audit('view_user_summary', 'user', p_user::text);
  -- Deliberately excludes e-mail, birth date, devices and message contents.
  return jsonb_build_object(
    'profile', (select jsonb_build_object('id', p.id, 'username', p.username, 'display_name', p.display_name,
                                          'created_at', p.created_at, 'bio', p.bio) from public.profiles p where p.id = p_user),
    'reports_against', (select count(*) from public.reports where target_user_id = p_user),
    'distinct_reporters', (select count(distinct reporter_id) from public.reports where target_user_id = p_user),
    'restrictions', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'kind', r.kind, 'reason', r.reason,
                                                                   'starts_at', r.starts_at, 'ends_at', r.ends_at, 'lifted_at', r.lifted_at)
                                               order by r.created_at desc)
                              from public.account_restrictions r where r.user_id = p_user), '[]'::jsonb),
    'is_premium', app_private.is_premium(p_user));
end;
$$;

create or replace function public.admin_list_rooms()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff();
  return coalesce((select jsonb_agg(jsonb_build_object(
      'id', r.id, 'title', r.title, 'visibility', r.visibility, 'created_at', r.created_at,
      'owner', (select jsonb_build_object('id', p.id, 'username', p.username) from public.profiles p where p.id = r.owner_id),
      'participant_count', (select count(*) from public.room_members m where m.room_id = r.id),
      'open_reports', (select count(*) from public.reports x where x.target_type = 'room' and x.target_id = r.id::text and x.status = 'open'))
    order by r.created_at desc)
    from public.rooms r where r.status = 'open'), '[]'::jsonb);
end;
$$;

create or replace function public.admin_recent_purchases(p_limit int default 50)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff(true);
  return coalesce((select jsonb_agg(jsonb_build_object('id', x.id, 'product_id', x.product_id, 'state', x.state,
                                                       'coins_granted', x.coins_granted, 'created_at', x.created_at,
                                                       'user_id', x.user_id) order by x.created_at desc)
                   from (select * from public.purchase_records order by created_at desc limit least(greatest(p_limit, 1), 200)) x), '[]'::jsonb);
end;
$$;

create or replace function public.admin_publish_announcement(p_title_tr text, p_body_tr text, p_title_en text, p_body_en text, p_active boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_staff(true);
  update public.app_settings set value = jsonb_build_object('active', p_active, 'title_tr', p_title_tr, 'body_tr', p_body_tr,
                                                             'title_en', p_title_en, 'body_en', p_body_en),
    updated_at = now(), updated_by = auth.uid()
  where key = 'announcement';
  perform app_private.audit('publish_announcement', 'app_setting', 'announcement', null, jsonb_build_object('active', p_active));
end;
$$;
