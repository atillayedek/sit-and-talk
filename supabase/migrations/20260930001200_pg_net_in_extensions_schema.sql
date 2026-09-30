-- pg_net belongs in the extensions schema (Supabase security advisor 0014). Projects where it was
-- first created in public get it recreated there; its functions stay in the net schema either way.
do $$
begin
  if exists (select 1 from pg_extension e join pg_namespace n on n.oid = e.extnamespace
             where e.extname = 'pg_net' and n.nspname = 'public') then
    drop extension pg_net;
    create extension pg_net with schema extensions;
  end if;
end
$$;
