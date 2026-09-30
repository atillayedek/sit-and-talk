-- The TalkRoom prototype that previously used this Supabase project created public.profiles, public.rooms
-- and public.room_members. Sit & Talk replaces it; those tables are kept intact (no data is deleted) in a
-- schema that is not exposed through the API. On a fresh database this migration does nothing.
do $$
begin
  if to_regclass('public.room_members') is not null
     and not exists (select 1 from information_schema.columns
                     where table_schema = 'public' and table_name = 'profiles' and column_name = 'display_name') then
    create schema if not exists talkroom_legacy;
    revoke all on schema talkroom_legacy from public, anon, authenticated;
    alter table public.room_members set schema talkroom_legacy;
    if to_regclass('public.rooms') is not null then alter table public.rooms set schema talkroom_legacy; end if;
    if to_regclass('public.profiles') is not null then alter table public.profiles set schema talkroom_legacy; end if;
    if to_regprocedure('public.touch_updated_at()') is not null then
      alter function public.touch_updated_at() set schema talkroom_legacy;
    end if;
  end if;
end
$$;
