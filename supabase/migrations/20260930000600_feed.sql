-- Sit & Talk — Discover feed: posts, comments, likes, saves, stories.

create type public.post_kind as enum ('text', 'photo', 'voice', 'question', 'status');
create type public.post_visibility as enum ('public', 'friends');
create type public.moderation_state as enum ('visible', 'pending', 'removed');

create table public.posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references auth.users(id) on delete cascade,
  kind public.post_kind not null,
  body text not null default '' check (char_length(body) <= 2000),
  audio_path text,
  audio_duration_ms int check (audio_duration_ms is null or audio_duration_ms between 1000 and 120000),
  interest_slugs text[] not null default '{}' check (cardinality(interest_slugs) <= 5),
  visibility public.post_visibility not null default 'public',
  moderation public.moderation_state not null default 'visible',
  like_count int not null default 0,
  comment_count int not null default 0,
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (kind <> 'voice' or audio_path is not null),
  check (kind not in ('text', 'question', 'status') or char_length(btrim(body)) > 0)
);
create index posts_created_idx on public.posts (created_at desc) where deleted_at is null and moderation = 'visible';
create index posts_author_idx on public.posts (author_id, created_at desc);
create index posts_interests_idx on public.posts using gin (interest_slugs);

create table public.post_media (
  post_id uuid not null references public.posts(id) on delete cascade,
  position smallint not null check (position between 0 and 3),
  path text not null unique,
  width int,
  height int,
  primary key (post_id, position)
);

create table public.post_comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  author_id uuid not null references auth.users(id) on delete cascade,
  parent_id uuid references public.post_comments(id) on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 1000),
  moderation public.moderation_state not null default 'visible',
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index post_comments_post_idx on public.post_comments (post_id, created_at);

create table public.post_reactions (
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table public.saved_posts (
  user_id uuid not null references auth.users(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, post_id)
);

create table public.hidden_posts (
  user_id uuid not null references auth.users(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  reason text not null default 'hidden' check (reason in ('hidden', 'not_interested')),
  created_at timestamptz not null default now(),
  primary key (user_id, post_id)
);

create table public.stories (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references auth.users(id) on delete cascade,
  media_path text not null unique,
  caption text not null default '' check (char_length(caption) <= 200),
  visibility public.post_visibility not null default 'friends',
  moderation public.moderation_state not null default 'visible',
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '24 hours'
);
create index stories_active_idx on public.stories (author_id, expires_at);

-- ---------------------------------------------------------------------------
-- Visibility
-- ---------------------------------------------------------------------------
create or replace function app_private.can_view_post(p_post uuid, p_viewer uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.posts p
    where p.id = p_post
      and p.deleted_at is null
      and (p.author_id = p_viewer or (
            p.moderation = 'visible'
        and not app_private.is_blocked(p.author_id, p_viewer)
        and (p.visibility = 'public' or app_private.are_friends(p.author_id, p_viewer))))
  );
$$;

create or replace function app_private.post_json(p public.posts, p_viewer uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', p.id, 'kind', p.kind, 'body', p.body, 'audio_path', p.audio_path, 'audio_duration_ms', p.audio_duration_ms,
    'interests', to_jsonb(p.interest_slugs), 'visibility', p.visibility, 'moderation', p.moderation,
    'like_count', p.like_count, 'comment_count', p.comment_count, 'created_at', p.created_at,
    'is_mine', p.author_id = p_viewer,
    'liked', exists (select 1 from public.post_reactions r where r.post_id = p.id and r.user_id = p_viewer),
    'saved', exists (select 1 from public.saved_posts s where s.post_id = p.id and s.user_id = p_viewer),
    'media', coalesce((select jsonb_agg(jsonb_build_object('path', m.path, 'width', m.width, 'height', m.height) order by m.position)
                       from public.post_media m where m.post_id = p.id), '[]'::jsonb),
    'author', (select jsonb_build_object('id', a.id, 'username', a.username, 'display_name', a.display_name, 'avatar_path', a.avatar_path)
               from public.profiles a where a.id = p.author_id)
  );
$$;

-- p_tab: 'new' | 'friends' | 'interests' | 'discover' | 'saved' | 'user'
-- Keyset pagination on created_at (p_before). 'discover' ranks the last 7 days by real engagement.
create or replace function public.get_feed(p_tab text, p_before timestamptz default null, p_limit int default 20, p_user uuid default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_limit int := least(greatest(coalesce(p_limit, 20), 1), 50);
  v_before timestamptz := coalesce(p_before, now() + interval '1 second');
  v_interests text[];
begin
  select coalesce(array_agg(slug), '{}') into v_interests from public.user_interests where user_id = v_uid;
  return coalesce((
    select jsonb_agg(app_private.post_json(c.post_row, v_uid) order by c.score desc nulls last, c.created_at desc)
    from (
      select p as post_row, p.created_at,
             case when p_tab = 'discover'
                  then (p.like_count * 2 + p.comment_count * 3 + 1)::numeric
                       / power(extract(epoch from (now() - p.created_at)) / 3600 + 2, 1.5) end as score
      from public.posts p
      where p.deleted_at is null
        and p.created_at < v_before
        and (p.author_id = v_uid or (
              p.moderation = 'visible'
          and not app_private.is_blocked(p.author_id, v_uid)
          and (p.visibility = 'public' or app_private.are_friends(p.author_id, v_uid))))
        and not exists (select 1 from public.hidden_posts h where h.post_id = p.id and h.user_id = v_uid)
        and case p_tab
              when 'friends' then app_private.are_friends(p.author_id, v_uid)
              when 'interests' then p.interest_slugs && v_interests
              when 'discover' then p.created_at > now() - interval '7 days' and p.author_id <> v_uid
              when 'saved' then exists (select 1 from public.saved_posts s where s.post_id = p.id and s.user_id = v_uid)
              when 'user' then p.author_id = p_user
              else true end
      order by
        case when p_tab = 'discover'
             then (p.like_count * 2 + p.comment_count * 3 + 1)::numeric
                  / power(extract(epoch from (now() - p.created_at)) / 3600 + 2, 1.5) end desc nulls last,
        p.created_at desc
      limit v_limit
    ) c
  ), '[]'::jsonb);
end;
$$;

create or replace function public.get_post(p_post uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_p public.posts;
begin
  if not app_private.can_view_post(p_post, v_uid) then
    perform app_private.err('not_found');
  end if;
  select * into v_p from public.posts where id = p_post;
  return app_private.post_json(v_p, v_uid);
end;
$$;

create or replace function public.create_post(
  p_id uuid, p_kind public.post_kind, p_body text, p_media jsonb, p_audio_path text, p_audio_duration_ms int,
  p_interests text[], p_visibility public.post_visibility
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_item jsonb;
  v_pos int := 0;
  v_p public.posts;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'posting');
  if not app_private.flag('feed') then
    perform app_private.err('feature_disabled', 'feed');
  end if;
  -- Idempotent retry: the same client id returns the existing post.
  select * into v_p from public.posts where id = p_id;
  if found then
    if v_p.author_id <> v_uid then
      perform app_private.err('conflict');
    end if;
    return app_private.post_json(v_p, v_uid);
  end if;
  perform app_private.rate_limit('create_post', 20, interval '1 hour');
  if p_kind = 'photo' and coalesce(jsonb_array_length(p_media), 0) not between 1 and 4 then
    perform app_private.err('invalid_media');
  end if;
  if p_audio_path is not null and split_part(p_audio_path, '/', 1) <> v_uid::text then
    perform app_private.err('invalid_media');
  end if;
  insert into public.posts (id, author_id, kind, body, audio_path, audio_duration_ms, interest_slugs, visibility)
  values (p_id, v_uid, p_kind, coalesce(btrim(p_body), ''), p_audio_path, p_audio_duration_ms,
          coalesce((select array_agg(i.slug) from public.interests i where i.slug = any (p_interests) and i.active), '{}'),
          coalesce(p_visibility, 'public'))
  returning * into v_p;
  if p_kind = 'photo' then
    for v_item in select * from jsonb_array_elements(p_media) loop
      if split_part(v_item ->> 'path', '/', 1) <> v_uid::text then
        perform app_private.err('invalid_media');
      end if;
      insert into public.post_media (post_id, position, path, width, height)
      values (v_p.id, v_pos, v_item ->> 'path', (v_item ->> 'width')::int, (v_item ->> 'height')::int);
      v_pos := v_pos + 1;
    end loop;
  end if;
  return app_private.post_json(v_p, v_uid);
end;
$$;

create or replace function public.delete_post(p_post uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_p public.posts;
begin
  select * into v_p from public.posts where id = p_post for update;
  if not found or (v_p.author_id <> v_uid and not public.is_staff()) then
    perform app_private.err('not_found');
  end if;
  update public.posts set deleted_at = now() where id = p_post and deleted_at is null;
  insert into app_private.storage_cleanup (bucket, path)
  select 'post-media', path from public.post_media where post_id = p_post
  union all select 'post-media', v_p.audio_path where v_p.audio_path is not null;
  delete from public.post_media where post_id = p_post;
end;
$$;

create or replace function public.set_post_like(p_post uuid, p_liked boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_count int;
begin
  if not app_private.can_view_post(p_post, v_uid) then
    perform app_private.err('not_found');
  end if;
  if p_liked then
    perform app_private.rate_limit('post_like', 120, interval '1 minute');
    insert into public.post_reactions (post_id, user_id) values (p_post, v_uid) on conflict do nothing;
  else
    delete from public.post_reactions where post_id = p_post and user_id = v_uid;
  end if;
  -- Counters come from real rows, never from a client-supplied value.
  update public.posts set like_count = (select count(*) from public.post_reactions where post_id = p_post)
  where id = p_post returning like_count into v_count;
  return jsonb_build_object('liked', p_liked, 'like_count', v_count);
end;
$$;

create or replace function public.set_post_saved(p_post uuid, p_saved boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if p_saved then
    if not app_private.can_view_post(p_post, v_uid) then
      perform app_private.err('not_found');
    end if;
    insert into public.saved_posts (user_id, post_id) values (v_uid, p_post) on conflict do nothing;
  else
    delete from public.saved_posts where user_id = v_uid and post_id = p_post;
  end if;
end;
$$;

create or replace function public.hide_post(p_post uuid, p_reason text)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.hidden_posts (user_id, post_id, reason)
  values (auth.uid(), p_post, case when p_reason = 'not_interested' then 'not_interested' else 'hidden' end)
  on conflict (user_id, post_id) do update set reason = excluded.reason;
$$;

create or replace function public.list_comments(p_post uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
begin
  if not app_private.can_view_post(p_post, v_uid) then
    perform app_private.err('not_found');
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', c.id, 'parent_id', c.parent_id, 'created_at', c.created_at,
      'body', case when c.deleted_at is not null or c.moderation = 'removed' then null else c.body end,
      'deleted', c.deleted_at is not null or c.moderation = 'removed',
      'is_mine', c.author_id = v_uid,
      'author', jsonb_build_object('id', a.id, 'username', a.username, 'display_name', a.display_name, 'avatar_path', a.avatar_path)
    ) order by c.created_at)
    from public.post_comments c join public.profiles a on a.id = c.author_id
    where c.post_id = p_post and not app_private.is_blocked(c.author_id, v_uid)
      and (c.moderation <> 'pending' or c.author_id = v_uid)
  ), '[]'::jsonb);
end;
$$;

create or replace function public.add_comment(p_post uuid, p_body text, p_parent uuid default null)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_id uuid;
  v_author uuid;
  v_parent_author uuid;
begin
  perform app_private.require_profile(v_uid);
  perform app_private.require_not_restricted(v_uid, 'posting');
  if not app_private.can_view_post(p_post, v_uid) then
    perform app_private.err('not_found');
  end if;
  perform app_private.rate_limit('add_comment', 30, interval '10 minutes');
  if p_parent is not null then
    select author_id into v_parent_author from public.post_comments where id = p_parent and post_id = p_post and deleted_at is null;
    if v_parent_author is null then
      perform app_private.err('not_found');
    end if;
  end if;
  insert into public.post_comments (post_id, author_id, parent_id, body) values (p_post, v_uid, p_parent, btrim(p_body))
  returning id into v_id;
  update public.posts set comment_count = (select count(*) from public.post_comments where post_id = p_post and deleted_at is null)
  where id = p_post returning author_id into v_author;
  if v_author <> v_uid then
    perform app_private.notify(v_author, 'comment', v_uid, 'post', p_post::text, jsonb_build_object('comment_id', v_id), 'comments');
  end if;
  if v_parent_author is not null and v_parent_author not in (v_uid, v_author) then
    perform app_private.notify(v_parent_author, 'reply', v_uid, 'post', p_post::text, jsonb_build_object('comment_id', v_id), 'comments');
  end if;
  return v_id;
end;
$$;

create or replace function public.delete_comment(p_comment uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_c public.post_comments;
begin
  select * into v_c from public.post_comments where id = p_comment for update;
  if not found or (v_c.author_id <> v_uid and not public.is_staff()
                   and not exists (select 1 from public.posts where id = v_c.post_id and author_id = v_uid)) then
    perform app_private.err('not_found');
  end if;
  update public.post_comments set deleted_at = now() where id = p_comment;
  update public.posts set comment_count = (select count(*) from public.post_comments where post_id = v_c.post_id and deleted_at is null)
  where id = v_c.post_id;
end;
$$;

create or replace function public.create_story(p_media_path text, p_caption text, p_visibility public.post_visibility)
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
  perform app_private.require_not_restricted(v_uid, 'posting');
  perform app_private.rate_limit('create_story', 20, interval '1 day');
  if split_part(p_media_path, '/', 1) <> v_uid::text then
    perform app_private.err('invalid_media');
  end if;
  insert into public.stories (author_id, media_path, caption, visibility)
  values (v_uid, p_media_path, coalesce(btrim(p_caption), ''), coalesce(p_visibility, 'friends'))
  returning id into v_id;
  return v_id;
end;
$$;

create or replace function public.delete_story(p_story uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_path text;
begin
  delete from public.stories where id = p_story and (author_id = auth.uid() or public.is_staff()) returning media_path into v_path;
  if v_path is not null then
    insert into app_private.storage_cleanup (bucket, path) values ('stories', v_path);
  end if;
end;
$$;

create or replace function public.list_stories()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'author', jsonb_build_object('id', a.id, 'username', a.username, 'display_name', a.display_name, 'avatar_path', a.avatar_path),
      'is_mine', a.id = auth.uid(),
      'items', items) order by (a.id = auth.uid()) desc, latest desc), '[]'::jsonb)
  from (
    select s.author_id,
           max(s.created_at) as latest,
           jsonb_agg(jsonb_build_object('id', s.id, 'media_path', s.media_path, 'caption', s.caption,
                                        'created_at', s.created_at, 'expires_at', s.expires_at) order by s.created_at) as items
    from public.stories s
    where s.expires_at > now()
      and (s.author_id = auth.uid() or (
            s.moderation = 'visible'
        and not app_private.is_blocked(s.author_id, auth.uid())
        and (s.visibility = 'public' or app_private.are_friends(s.author_id, auth.uid()))))
    group by s.author_id
  ) g join public.profiles a on a.id = g.author_id;
$$;
