// Google Play Real-time Developer Notifications (Pub/Sub push endpoint).
// Authenticated with a shared secret in the subscription URL (?token=...), compared in constant time.
import { ApiError, json, log, readJson, requiredEnv, serve, timingSafeEqual } from "../_shared/http.ts";
import { dbError, serviceClient } from "../_shared/supabase.ts";
import { sha256Hex } from "../_shared/google.ts";
import { fetchProductPurchase, fetchSubscription } from "../_shared/play.ts";

type Notification = {
  packageName?: string;
  subscriptionNotification?: { purchaseToken: string; subscriptionId: string; notificationType: number };
  oneTimeProductNotification?: { purchaseToken: string; sku: string; notificationType: number };
  voidedPurchaseNotification?: { purchaseToken: string; orderId: string; productType: number; refundType?: number };
  testNotification?: unknown;
};

serve(async (req, requestId) => {
  const given = new URL(req.url).searchParams.get("token") ?? "";
  if (!timingSafeEqual(given, requiredEnv("PLAY_RTDN_SECRET"))) throw new ApiError(401, "unauthorized", "Unauthorized");

  const body = await readJson(req, 65_536);
  const message = body.message as { data?: string } | undefined;
  if (!message?.data) throw new ApiError(400, "invalid_request", "Missing Pub/Sub message");
  const n = JSON.parse(atob(message.data)) as Notification;
  if (n.packageName && n.packageName !== requiredEnv("ANDROID_PACKAGE_NAME")) {
    throw new ApiError(400, "invalid_request", "Unexpected package");
  }
  if (n.testNotification) {
    log(requestId, "rtdn_test");
    return json(req, requestId, { ok: true });
  }

  const token = n.subscriptionNotification?.purchaseToken ?? n.oneTimeProductNotification?.purchaseToken ??
    n.voidedPurchaseNotification?.purchaseToken;
  if (!token) return json(req, requestId, { ok: true, ignored: true });

  const svc = serviceClient();
  const tokenHash = await sha256Hex(token);
  const { data: record } = await svc.from("purchase_records").select("user_id, product_id, product_kind")
    .eq("purchase_token_hash", tokenHash).maybeSingle();
  if (!record || !record.user_id) {
    // The app has not redeemed this token yet; verify-purchase will record it when it does.
    log(requestId, "rtdn_unknown_token");
    return json(req, requestId, { ok: true, ignored: true });
  }

  let state: string;
  let expiresAt: string | null = null;
  let autoRenewing = false;
  let subscriptionStatus: string | null = null;
  let orderId: string | null = null;
  let purchasedAt: string | null = null;
  if (n.voidedPurchaseNotification) {
    state = "refunded";
    subscriptionStatus = record.product_kind === "subscription" ? "revoked" : null;
    orderId = n.voidedPurchaseNotification.orderId;
  } else if (record.product_kind === "subscription") {
    const s = await fetchSubscription(token);
    state = s.state;
    expiresAt = s.expiresAt;
    autoRenewing = s.autoRenewing;
    subscriptionStatus = s.subscriptionStatus;
    orderId = s.orderId;
    purchasedAt = s.purchasedAt;
  } else {
    const p = await fetchProductPurchase(record.product_id, token);
    state = p.state;
    orderId = p.orderId;
    purchasedAt = p.purchasedAt;
  }

  const { error } = await svc.rpc("record_verified_purchase", {
    p_user: record.user_id,
    p_product_id: record.product_id,
    p_product_kind: record.product_kind,
    p_token_hash: tokenHash,
    p_order_id: orderId,
    p_state: state,
    p_purchased_at: purchasedAt,
    p_expires_at: expiresAt,
    p_auto_renewing: autoRenewing,
    p_subscription_status: subscriptionStatus,
  });
  if (error) throw dbError(error);
  log(requestId, "rtdn_processed", { state });
  return json(req, requestId, { ok: true });
});
