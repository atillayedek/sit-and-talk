-- Sit & Talk — Play purchases, premium entitlements, coin ledger and gifts.
--
-- * Only the `verify-purchase` Edge Function (service role) writes purchases and entitlements,
--   after verifying the purchase token with the Google Play Developer API.
-- * Balances change only through ledger entries written by SECURITY DEFINER functions.
-- * Gifts are not convertible to money: recipients get a gift count, not coins.

create table public.purchase_records (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  product_id text not null,
  product_kind text not null check (product_kind in ('consumable', 'subscription')),
  purchase_token_hash text not null unique,
  order_id text,
  state text not null check (state in ('pending', 'purchased', 'canceled', 'refunded', 'revoked', 'expired')),
  coins_granted int not null default 0,
  acknowledged boolean not null default false,
  consumed boolean not null default false,
  purchased_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index purchase_records_user_idx on public.purchase_records (user_id, created_at desc);

create table public.subscription_entitlements (
  user_id uuid primary key references auth.users(id) on delete cascade,
  product_id text not null,
  status text not null check (status in ('active', 'grace', 'on_hold', 'paused', 'canceled', 'expired', 'revoked')),
  expires_at timestamptz,
  auto_renewing boolean not null default false,
  purchase_record_id uuid references public.purchase_records(id) on delete set null,
  updated_at timestamptz not null default now()
);

create table public.wallet_accounts (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance bigint not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);

create table public.wallet_transactions (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  amount bigint not null check (amount <> 0),
  kind text not null check (kind in ('purchase', 'gift_sent', 'refund_reversal', 'admin_adjustment')),
  reference_type text,
  reference_id text,
  idempotency_key text not null,
  balance_after bigint not null check (balance_after >= 0),
  created_at timestamptz not null default now(),
  unique (user_id, idempotency_key)
);
create index wallet_transactions_user_idx on public.wallet_transactions (user_id, created_at desc);

create table public.gift_catalog (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9_]{2,32}$'),
  name_tr text not null,
  name_en text not null,
  icon_key text not null,
  price_coins int not null check (price_coins > 0),
  active boolean not null default true,
  sort int not null default 0
);

create table public.gift_transactions (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid references auth.users(id) on delete set null,
  recipient_id uuid references auth.users(id) on delete set null,
  gift_id uuid not null references public.gift_catalog(id),
  coins int not null,
  context_type text not null check (context_type in ('call', 'room', 'profile', 'post')),
  context_id text,
  idempotency_key text not null,
  created_at timestamptz not null default now(),
  unique (sender_id, idempotency_key)
);
create index gift_transactions_recipient_idx on public.gift_transactions (recipient_id, created_at desc);

create trigger purchase_records_touch before update on public.purchase_records
for each row execute function app_private.touch_updated_at();

create or replace function app_private.is_premium(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.subscription_entitlements e
    where e.user_id = p_user and e.status in ('active', 'grace', 'canceled')
      and (e.expires_at is null or e.expires_at > now()));
$$;

create or replace function public.my_entitlements()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'is_premium', app_private.is_premium(auth.uid()),
    'subscription', (select jsonb_build_object('product_id', e.product_id, 'status', e.status, 'expires_at', e.expires_at,
                                               'auto_renewing', e.auto_renewing)
                     from public.subscription_entitlements e where e.user_id = auth.uid()),
    'balance', coalesce((select balance from public.wallet_accounts where user_id = auth.uid()), 0),
    'gifts_enabled', app_private.flag('gifts'),
    'premium_enabled', app_private.flag('premium'),
    'coin_products', coalesce((select value from public.app_settings where key = 'coin_products'), '{}'::jsonb),
    'premium_products', coalesce((select value from public.app_settings where key = 'premium_products'), '[]'::jsonb));
$$;

-- Ledger primitive: the only way balances change.
create or replace function app_private.ledger_apply(
  p_user uuid, p_amount bigint, p_kind text, p_reference_type text, p_reference_id text, p_idempotency_key text
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_balance bigint;
  v_existing bigint;
begin
  select balance_after into v_existing from public.wallet_transactions
  where user_id = p_user and idempotency_key = p_idempotency_key;
  if v_existing is not null then
    return v_existing;
  end if;
  insert into public.wallet_accounts (user_id) values (p_user) on conflict do nothing;
  select balance into v_balance from public.wallet_accounts where user_id = p_user for update;
  if v_balance + p_amount < 0 then
    perform app_private.err('insufficient_balance');
  end if;
  update public.wallet_accounts set balance = balance + p_amount, updated_at = now()
  where user_id = p_user returning balance into v_balance;
  insert into public.wallet_transactions (user_id, amount, kind, reference_type, reference_id, idempotency_key, balance_after)
  values (p_user, p_amount, p_kind, p_reference_type, p_reference_id, p_idempotency_key, v_balance);
  return v_balance;
end;
$$;

-- Called by the verify-purchase Edge Function (service role only) after Google verified the token.
create or replace function public.record_verified_purchase(
  p_user uuid,
  p_product_id text,
  p_product_kind text,
  p_token_hash text,
  p_order_id text,
  p_state text,
  p_purchased_at timestamptz,
  p_expires_at timestamptz,
  p_auto_renewing boolean,
  p_subscription_status text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_rec public.purchase_records;
  v_coins int;
  v_balance bigint;
begin
  insert into public.purchase_records (user_id, product_id, product_kind, purchase_token_hash, order_id, state, purchased_at, expires_at)
  values (p_user, p_product_id, p_product_kind, p_token_hash, p_order_id, p_state, p_purchased_at, p_expires_at)
  on conflict (purchase_token_hash) do update set
    state = excluded.state, order_id = coalesce(excluded.order_id, purchase_records.order_id),
    expires_at = excluded.expires_at
  returning * into v_rec;

  -- A token is bound to the account that first redeemed it.
  if v_rec.user_id is distinct from p_user then
    perform app_private.err('purchase_owned_by_other_account');
  end if;

  if p_product_kind = 'consumable' then
    select (value ->> p_product_id)::int into v_coins from public.app_settings where key = 'coin_products';
    if v_coins is null or v_coins <= 0 then
      perform app_private.err('unknown_product');
    end if;
    if p_state = 'purchased' and v_rec.coins_granted = 0 then
      v_balance := app_private.ledger_apply(p_user, v_coins, 'purchase', 'purchase', v_rec.id::text, 'purchase:' || v_rec.id::text);
      update public.purchase_records set coins_granted = v_coins where id = v_rec.id;
      insert into public.notifications (user_id, kind, entity_type, entity_id, data)
      values (p_user, 'purchase', 'purchase', v_rec.id::text, jsonb_build_object('coins', v_coins, 'state', 'purchased'));
    elsif p_state in ('refunded', 'revoked') and v_rec.coins_granted > 0 then
      -- Take back what is still available; never drive the balance negative.
      select balance into v_balance from public.wallet_accounts where user_id = p_user for update;
      if least(v_balance, v_rec.coins_granted) > 0 then
        perform app_private.ledger_apply(p_user, -least(v_balance, v_rec.coins_granted), 'refund_reversal', 'purchase',
                                         v_rec.id::text, 'refund:' || v_rec.id::text);
      end if;
    end if;
  else
    if not coalesce((select value from public.app_settings where key = 'premium_products'), '[]'::jsonb) ? p_product_id then
      perform app_private.err('unknown_product');
    end if;
    insert into public.subscription_entitlements (user_id, product_id, status, expires_at, auto_renewing, purchase_record_id)
    values (p_user, p_product_id, p_subscription_status, p_expires_at, coalesce(p_auto_renewing, false), v_rec.id)
    on conflict (user_id) do update set product_id = excluded.product_id, status = excluded.status,
      expires_at = excluded.expires_at, auto_renewing = excluded.auto_renewing,
      purchase_record_id = excluded.purchase_record_id, updated_at = now();
    insert into public.notifications (user_id, kind, entity_type, entity_id, data)
    values (p_user, 'purchase', 'subscription', p_product_id, jsonb_build_object('status', p_subscription_status));
  end if;

  select * into v_rec from public.purchase_records where id = v_rec.id;
  return jsonb_build_object('purchase_id', v_rec.id, 'state', v_rec.state, 'coins_granted', v_rec.coins_granted,
                            'needs_acknowledge', not v_rec.acknowledged, 'needs_consume', p_product_kind = 'consumable' and not v_rec.consumed);
end;
$$;

create or replace function public.mark_purchase_acknowledged(p_token_hash text, p_consumed boolean)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.purchase_records set acknowledged = true, consumed = consumed or p_consumed
  where purchase_token_hash = p_token_hash;
$$;

create or replace function public.send_gift(
  p_gift_code text, p_context_type text, p_context_id text, p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := app_private.require_user();
  v_gift public.gift_catalog;
  v_recipient uuid;
  v_existing public.gift_transactions;
  v_balance bigint;
  v_tx uuid;
begin
  perform app_private.require_profile(v_uid);
  if not app_private.flag('gifts') then
    perform app_private.err('feature_disabled', 'gifts');
  end if;
  if coalesce(p_idempotency_key, '') !~ '^[A-Za-z0-9_-]{8,64}$' then
    perform app_private.err('invalid_request');
  end if;
  select * into v_existing from public.gift_transactions where sender_id = v_uid and idempotency_key = p_idempotency_key;
  if found then
    return jsonb_build_object('gift_transaction_id', v_existing.id,
                              'balance', (select balance from public.wallet_accounts where user_id = v_uid));
  end if;
  select * into v_gift from public.gift_catalog where code = p_gift_code and active;
  if not found then
    perform app_private.err('not_found');
  end if;

  -- The recipient is resolved on the server from the context; the client never names a user id for calls.
  if p_context_type = 'call' then
    select peer.user_id into v_recipient
    from public.call_participants me
    join public.call_participants peer on peer.session_id = me.session_id and peer.user_id <> me.user_id
    join public.call_sessions cs on cs.id = me.session_id
    where me.session_id = p_context_id::uuid and me.user_id = v_uid and cs.status = 'active';
  elsif p_context_type = 'room' then
    select m.user_id into v_recipient from public.room_members m
    where m.room_id = split_part(p_context_id, ':', 1)::uuid and m.user_id = split_part(p_context_id, ':', 2)::uuid
      and app_private.is_room_member(m.room_id, v_uid);
  elsif p_context_type = 'profile' then
    v_recipient := p_context_id::uuid;
    if not exists (select 1 from public.profiles where id = v_recipient) then
      v_recipient := null;
    end if;
  elsif p_context_type = 'post' then
    select author_id into v_recipient from public.posts where id = p_context_id::uuid and app_private.can_view_post(id, v_uid);
  end if;
  if v_recipient is null or v_recipient = v_uid or app_private.is_blocked(v_uid, v_recipient) then
    perform app_private.err('invalid_target');
  end if;
  perform app_private.rate_limit('send_gift', 30, interval '1 minute');

  insert into public.gift_transactions (sender_id, recipient_id, gift_id, coins, context_type, context_id, idempotency_key)
  values (v_uid, v_recipient, v_gift.id, v_gift.price_coins, p_context_type, p_context_id, p_idempotency_key)
  returning id into v_tx;
  -- Raises insufficient_balance and rolls the whole transaction back (no gift row, no debit).
  v_balance := app_private.ledger_apply(v_uid, -v_gift.price_coins, 'gift_sent', 'gift', v_tx::text, 'gift:' || p_idempotency_key);
  update public.profiles set gifts_received = gifts_received + 1 where id = v_recipient;
  if p_context_type = 'room' then
    insert into public.room_reactions (room_id, user_id, kind, gift_code, target_user_id)
    values (split_part(p_context_id, ':', 1)::uuid, v_uid, 'gift', v_gift.code, v_recipient);
  end if;
  return jsonb_build_object('gift_transaction_id', v_tx, 'balance', v_balance);
end;
$$;

create or replace function public.wallet_history(p_limit int default 50)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', t.id, 'amount', t.amount, 'kind', t.kind, 'balance_after', t.balance_after,
                                               'created_at', t.created_at) order by t.created_at desc), '[]'::jsonb)
  from (select * from public.wallet_transactions where user_id = auth.uid() order by created_at desc
        limit least(greatest(p_limit, 1), 200)) t;
$$;

-- Editorial gift catalog (product configuration). Gifts stay disabled until the `gifts` flag is on.
insert into public.gift_catalog (code, name_tr, name_en, icon_key, price_coins, sort) values
  ('coffee', 'Kahve', 'Coffee', 'coffee', 10, 10),
  ('rose', 'Gül', 'Rose', 'local_florist', 20, 20),
  ('star', 'Yıldız', 'Star', 'star', 50, 30),
  ('heart', 'Kalp', 'Heart', 'favorite', 100, 40),
  ('crown', 'Taç', 'Crown', 'workspace_premium', 500, 50)
on conflict (code) do nothing;
