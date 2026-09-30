-- Lets Edge Functions confirm a caller's access token through PostgREST, which verifies the JWT
-- signature itself (asymmetric signing keys included). Returns only the caller's own user id.
create or replace function public.auth_whoami()
returns uuid
language sql
stable
security invoker
set search_path = ''
as $$
  select auth.uid();
$$;

revoke all on function public.auth_whoami() from public, anon;
grant execute on function public.auth_whoami() to authenticated;
