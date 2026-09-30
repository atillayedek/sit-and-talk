-- Server-side secret lookup for Edge Functions. Function environment variables take precedence; when a
-- value is not set there, functions read it from Supabase Vault (encrypted at rest) through this RPC.
-- Only the service_role can execute it, and only the names listed below can be read.
create or replace function public.server_secret(p_name text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_value text;
begin
  if p_name not in ('AGORA_APP_ID', 'AGORA_APP_CERTIFICATE', 'AGORA_CUSTOMER_ID', 'AGORA_CUSTOMER_SECRET',
                    'INTERNAL_HOOK_SECRET', 'FCM_PROJECT_ID', 'FCM_SERVICE_ACCOUNT_JSON',
                    'GOOGLE_PLAY_SERVICE_ACCOUNT_JSON', 'PLAY_RTDN_SECRET') then
    return null;
  end if;
  if p_name = 'INTERNAL_HOOK_SECRET' then
    select value into v_value from app_private.runtime_config where key = 'internal_hook_secret';
    return v_value;
  end if;
  if to_regclass('vault.decrypted_secrets') is null then
    return null;
  end if;
  execute 'select decrypted_secret from vault.decrypted_secrets where name = $1 order by created_at desc limit 1'
    into v_value using p_name;
  return v_value;
end;
$$;

revoke all on function public.server_secret(text) from public, anon, authenticated;
grant execute on function public.server_secret(text) to service_role;
