-- Sit & Talk — account deletion, data export and Storage buckets / policies.

create table public.account_deletion_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  status text not null default 'pending' check (status in ('pending', 'completed', 'failed', 'cancelled')),
  requested_at timestamptz not null default now(),
  processed_at timestamptz,
  error text
);
create unique index account_deletion_requests_pending_key on public.account_deletion_requests (user_id) where status = 'pending';

-- Everything this user can see about themselves, as one JSON document.
-- Other people's private data (their messages to you excluded) is never included.
create or replace function public.export_my_data()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  perform app_private.rate_limit('export_data', 5, interval '1 day');
  return jsonb_build_object(
    'generated_at', now(),
    'profile', (select to_jsonb(p) from public.profiles p where p.id = v_uid),
    'private_settings', (select to_jsonb(up) - 'user_id' from public.user_private up where up.user_id = v_uid),
    'consents', coalesce((select jsonb_agg(to_jsonb(c) - 'user_id') from public.user_consents c where c.user_id = v_uid), '[]'::jsonb),
    'interests', coalesce((select jsonb_agg(to_jsonb(i) - 'user_id') from public.user_interests i where i.user_id = v_uid), '[]'::jsonb),
    'languages', coalesce((select jsonb_agg(l.language_code) from public.user_languages l where l.user_id = v_uid), '[]'::jsonb),
    'friends', public.list_friends(),
    'blocked', public.list_blocked_users(),
    'posts', coalesce((select jsonb_agg(to_jsonb(p) - 'author_id') from public.posts p where p.author_id = v_uid), '[]'::jsonb),
    'comments', coalesce((select jsonb_agg(to_jsonb(c) - 'author_id') from public.post_comments c where c.author_id = v_uid), '[]'::jsonb),
    'messages_sent', coalesce((select jsonb_agg(jsonb_build_object('id', m.id, 'conversation_id', m.conversation_id, 'kind', m.kind,
                                                                   'body', m.body, 'created_at', m.created_at))
                               from public.messages m where m.sender_id = v_uid), '[]'::jsonb),
    'rooms_created', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'title', r.title, 'created_at', r.created_at))
                               from public.rooms r where r.owner_id = v_uid), '[]'::jsonb),
    'call_history', coalesce((select jsonb_agg(jsonb_build_object('session_id', cs.id, 'kind', cs.kind, 'mode', cs.mode,
                                                                  'started_at', cs.started_at, 'ended_at', cs.ended_at))
                              from public.call_participants cp join public.call_sessions cs on cs.id = cp.session_id
                              where cp.user_id = v_uid), '[]'::jsonb),
    'wallet', public.my_entitlements(),
    'wallet_transactions', public.wallet_history(200),
    'reports_filed', public.my_reports(),
    'restrictions', coalesce((select jsonb_agg(to_jsonb(r) - 'created_by' - 'user_id') from public.account_restrictions r
                              where r.user_id = v_uid), '[]'::jsonb),
    'notification_preferences', (select to_jsonb(np) - 'user_id' from public.notification_preferences np where np.user_id = v_uid));
end;
$$;

-- Called by the delete-account Edge Function (service role) right before auth.admin.deleteUser.
-- Queues every stored file of the user for removal and closes live surfaces.
create or replace function public.prepare_account_deletion(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from public.matchmaking_queue where user_id = p_user;
  perform app_private.end_session(cp.session_id, 'account_deleted')
  from public.call_participants cp where cp.user_id = p_user and cp.left_at is null;
  delete from public.room_members where user_id = p_user;
  update public.rooms set status = 'closed', closed_at = now(), close_reason = 'owner_deleted'
  where owner_id = p_user and status = 'open';

  insert into app_private.storage_cleanup (bucket, path)
  select 'avatars', avatar_path from public.profiles where id = p_user and avatar_path is not null
  union all select 'post-media', pm.path from public.post_media pm join public.posts p on p.id = pm.post_id where p.author_id = p_user
  union all select 'post-media', audio_path from public.posts where author_id = p_user and audio_path is not null
  union all select 'stories', media_path from public.stories where author_id = p_user
  union all select 'chat-media', media_path from public.messages where sender_id = p_user and media_path is not null;

  -- Messages the user sent to others stay in those conversations without authorship or content.
  update public.messages set body = null, media_path = null, deleted_for_all_at = coalesce(deleted_for_all_at, now())
  where sender_id = p_user;
  delete from public.user_devices where user_id = p_user;
end;
$$;

-- ---------------------------------------------------------------------------
-- Storage
-- ---------------------------------------------------------------------------
-- Object names are always "<owner-or-scope-uuid>/<random>.<ext>"; clients generate random names.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('avatars', 'avatars', true, 2097152, array['image/jpeg', 'image/png', 'image/webp']),
  ('post-media', 'post-media', false, 8388608, array['image/jpeg', 'image/png', 'image/webp', 'audio/mp4', 'audio/aac', 'audio/mpeg']),
  ('stories', 'stories', false, 8388608, array['image/jpeg', 'image/png', 'image/webp']),
  ('chat-media', 'chat-media', false, 10485760, array['image/jpeg', 'image/png', 'image/webp', 'audio/mp4', 'audio/aac', 'audio/mpeg']),
  ('report-evidence', 'report-evidence', false, 8388608, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update set public = excluded.public, file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create or replace function app_private.storage_can_read(p_bucket text, p_name text, p_user uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_scope text := split_part(p_name, '/', 1);
  v_ok boolean := false;
begin
  if p_user is null then
    return false;
  end if;
  if exists (select 1 from public.admin_roles where user_id = p_user) and p_bucket = 'report-evidence' then
    return true;
  end if;
  case p_bucket
    when 'post-media' then
      if v_scope = p_user::text then return true; end if;
      select exists (select 1 from public.post_media pm where pm.path = p_name and app_private.can_view_post(pm.post_id, p_user))
          or exists (select 1 from public.posts p where p.audio_path = p_name and app_private.can_view_post(p.id, p_user))
        into v_ok;
    when 'stories' then
      if v_scope = p_user::text then return true; end if;
      select exists (select 1 from public.stories s where s.media_path = p_name and s.expires_at > now()
                     and s.moderation = 'visible' and not app_private.is_blocked(s.author_id, p_user)
                     and (s.visibility = 'public' or app_private.are_friends(s.author_id, p_user)))
        into v_ok;
    when 'chat-media' then
      begin
        v_ok := app_private.is_conversation_member(v_scope::uuid, p_user)
                and exists (select 1 from public.messages m where m.media_path = p_name and m.deleted_for_all_at is null
                            and not exists (select 1 from public.message_hidden h where h.message_id = m.id and h.user_id = p_user));
      exception when invalid_text_representation then
        v_ok := false;
      end;
    when 'report-evidence' then
      v_ok := v_scope = p_user::text;
    else
      v_ok := false;
  end case;
  return coalesce(v_ok, false);
end;
$$;

create or replace function app_private.storage_can_write(p_bucket text, p_name text, p_user uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_scope text := split_part(p_name, '/', 1);
  v_file text := split_part(p_name, '/', 2);
begin
  if p_user is null or split_part(p_name, '/', 3) <> '' or v_file !~ '^[A-Za-z0-9_-]{16,64}\.(jpg|png|webp|m4a|aac|mp3)$' then
    return false;
  end if;
  if p_bucket in ('avatars', 'post-media', 'stories', 'report-evidence') then
    return v_scope = p_user::text;
  end if;
  if p_bucket = 'chat-media' then
    begin
      return app_private.is_conversation_member(v_scope::uuid, p_user) and not app_private.is_restricted(p_user, 'messaging');
    exception when invalid_text_representation then
      return false;
    end;
  end if;
  return false;
end;
$$;

create policy "sitandtalk read objects" on storage.objects for select to authenticated
using (bucket_id = 'avatars' or app_private.storage_can_read(bucket_id, name, (select auth.uid())));

create policy "sitandtalk upload objects" on storage.objects for insert to authenticated
with check (app_private.storage_can_write(bucket_id, name, (select auth.uid())));

-- Owners may delete their own avatars / post media / stories; chat media is removed via RPC + cleanup queue.
create policy "sitandtalk delete own objects" on storage.objects for delete to authenticated
using (bucket_id in ('avatars', 'post-media', 'stories', 'report-evidence')
       and split_part(name, '/', 1) = (select auth.uid())::text);

-- Setting an avatar enforces that the file belongs to the caller.
create or replace function public.set_avatar(p_path text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_old text;
begin
  if p_path is not null and (split_part(p_path, '/', 1) <> v_uid::text
                             or p_path !~ '^[0-9a-f-]{36}/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$') then
    perform app_private.err('invalid_media');
  end if;
  select avatar_path into v_old from public.profiles where id = v_uid for update;
  update public.profiles set avatar_path = p_path where id = v_uid;
  if v_old is not null and v_old is distinct from p_path then
    insert into app_private.storage_cleanup (bucket, path) values ('avatars', v_old);
  end if;
end;
$$;

-- Maintenance hook for the service role: take a batch of files to remove.
create or replace function public.claim_storage_cleanup(p_limit int default 100)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  with batch as (
    select id from app_private.storage_cleanup where processed_at is null order by created_at
    limit least(greatest(p_limit, 1), 500) for update skip locked
  ), done as (
    update app_private.storage_cleanup c set processed_at = now()
    from batch where c.id = batch.id
    returning jsonb_build_object('id', c.id, 'bucket', c.bucket, 'path', c.path) as item
  )
  select coalesce(jsonb_agg(item), '[]'::jsonb) from done;
$$;

create or replace function public.report_storage_cleanup_failure(p_id bigint, p_error text)
returns void
language sql
security definer
set search_path = ''
as $$
  update app_private.storage_cleanup set processed_at = null, error = left(p_error, 500) where id = p_id;
$$;
