// Google Play Developer API helpers shared by verify-purchase and play-rtdn.
import { ApiError, requiredEnv } from "./http.ts";
import { googleAccessToken, loadServiceAccount } from "./google.ts";

const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";

export type VerifiedPurchase = {
  productId: string;
  kind: "consumable" | "subscription";
  state: "pending" | "purchased" | "canceled" | "refunded" | "revoked" | "expired";
  orderId: string | null;
  purchasedAt: string | null;
  expiresAt: string | null;
  autoRenewing: boolean;
  subscriptionStatus: string | null;
  acknowledged: boolean;
  consumed: boolean;
  obfuscatedAccountId: string | null;
};

async function authHeader(): Promise<Record<string, string>> {
  const token = await googleAccessToken(loadServiceAccount("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"), SCOPE);
  return { authorization: `Bearer ${token}` };
}

function packageName(): string {
  return requiredEnv("ANDROID_PACKAGE_NAME");
}

export async function fetchProductPurchase(productId: string, token: string): Promise<VerifiedPurchase> {
  const url = `${API}/${packageName()}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;
  const res = await fetch(url, { headers: await authHeader() });
  if (res.status === 404 || res.status === 410 || res.status === 400) throw new ApiError(400, "invalid_purchase", "Purchase not found");
  if (!res.ok) throw new ApiError(502, "play_api_failed", `Play API error (${res.status})`);
  const p = await res.json() as {
    purchaseState?: number;
    consumptionState?: number;
    acknowledgementState?: number;
    orderId?: string;
    purchaseTimeMillis?: string;
    obfuscatedExternalAccountId?: string;
  };
  const state = p.purchaseState === 0 ? "purchased" : p.purchaseState === 2 ? "pending" : "canceled";
  return {
    productId,
    kind: "consumable",
    state,
    orderId: p.orderId ?? null,
    purchasedAt: p.purchaseTimeMillis ? new Date(Number(p.purchaseTimeMillis)).toISOString() : null,
    expiresAt: null,
    autoRenewing: false,
    subscriptionStatus: null,
    acknowledged: p.acknowledgementState === 1,
    consumed: p.consumptionState === 1,
    obfuscatedAccountId: p.obfuscatedExternalAccountId ?? null,
  };
}

const SUBSCRIPTION_STATES: Record<string, { state: VerifiedPurchase["state"]; status: string }> = {
  SUBSCRIPTION_STATE_ACTIVE: { state: "purchased", status: "active" },
  SUBSCRIPTION_STATE_IN_GRACE_PERIOD: { state: "purchased", status: "grace" },
  SUBSCRIPTION_STATE_ON_HOLD: { state: "purchased", status: "on_hold" },
  SUBSCRIPTION_STATE_PAUSED: { state: "purchased", status: "paused" },
  SUBSCRIPTION_STATE_CANCELED: { state: "canceled", status: "canceled" },
  SUBSCRIPTION_STATE_EXPIRED: { state: "expired", status: "expired" },
  SUBSCRIPTION_STATE_PENDING: { state: "pending", status: "on_hold" },
  SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED: { state: "canceled", status: "expired" },
};

export async function fetchSubscription(token: string): Promise<VerifiedPurchase> {
  const url = `${API}/${packageName()}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`;
  const res = await fetch(url, { headers: await authHeader() });
  if (res.status === 404 || res.status === 410 || res.status === 400) throw new ApiError(400, "invalid_purchase", "Subscription not found");
  if (!res.ok) throw new ApiError(502, "play_api_failed", `Play API error (${res.status})`);
  const s = await res.json() as {
    subscriptionState?: string;
    acknowledgementState?: string;
    latestOrderId?: string;
    startTime?: string;
    lineItems?: { productId: string; expiryTime?: string; autoRenewingPlan?: { autoRenewEnabled?: boolean } }[];
    externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
  };
  const line = s.lineItems?.[0];
  if (!line) throw new ApiError(400, "invalid_purchase", "Subscription has no line items");
  const mapped = SUBSCRIPTION_STATES[s.subscriptionState ?? ""] ?? { state: "expired", status: "expired" };
  return {
    productId: line.productId,
    kind: "subscription",
    state: mapped.state,
    orderId: s.latestOrderId ?? null,
    purchasedAt: s.startTime ?? null,
    expiresAt: line.expiryTime ?? null,
    autoRenewing: line.autoRenewingPlan?.autoRenewEnabled ?? false,
    subscriptionStatus: mapped.status,
    acknowledged: s.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    consumed: false,
    obfuscatedAccountId: s.externalAccountIdentifiers?.obfuscatedExternalAccountId ?? null,
  };
}

export async function acknowledge(p: VerifiedPurchase, token: string): Promise<void> {
  const path = p.kind === "subscription"
    ? `purchases/subscriptions/${encodeURIComponent(p.productId)}/tokens/${encodeURIComponent(token)}:acknowledge`
    : `purchases/products/${encodeURIComponent(p.productId)}/tokens/${encodeURIComponent(token)}:acknowledge`;
  const res = await fetch(`${API}/${packageName()}/${path}`, {
    method: "POST",
    headers: { ...(await authHeader()), "content-type": "application/json" },
    body: "{}",
  });
  if (!res.ok && res.status !== 409) throw new ApiError(502, "play_ack_failed", `Acknowledge failed (${res.status})`);
}

export async function consume(productId: string, token: string): Promise<void> {
  const url = `${API}/${packageName()}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}:consume`;
  const res = await fetch(url, { method: "POST", headers: await authHeader() });
  if (!res.ok) throw new ApiError(502, "play_consume_failed", `Consume failed (${res.status})`);
}
