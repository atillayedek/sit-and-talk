// Verifies a Google Play purchase on the server, records it and grants coins / premium.
// The client's own "purchase succeeded" callback is never trusted on its own.
import { ApiError, json, log, readJson, requireString, serve } from "../_shared/http.ts";
import { dbError, requireUser, serviceClient } from "../_shared/supabase.ts";
import { sha256Hex } from "../_shared/google.ts";
import { acknowledge, consume, fetchProductPurchase, fetchSubscription } from "../_shared/play.ts";

serve(async (req, requestId) => {
  const { user, db } = await requireUser(req);
  const body = await readJson(req);
  const kind = requireString(body, "kind", /^(consumable|subscription)$/);
  const productId = requireString(body, "product_id", /^[a-z0-9_.]{1,100}$/);
  const purchaseToken = requireString(body, "purchase_token", /^[A-Za-z0-9._\-:]{10,4096}$/, 4096);

  const svc = serviceClient();
  const { error: rateError } = await svc.rpc("edge_rate_limit", {
    p_user: user.id, p_bucket: "verify_purchase", p_max: 30, p_window_seconds: 3600,
  });
  if (rateError) throw dbError(rateError);

  const purchase = kind === "subscription"
    ? await fetchSubscription(purchaseToken)
    : await fetchProductPurchase(productId, purchaseToken);
  if (purchase.productId !== productId) throw new ApiError(400, "product_mismatch", "Product does not match the token");

  // Purchases are started with obfuscatedAccountId = sha256(user id); refuse tokens bought for another account.
  const expectedAccount = await sha256Hex(user.id);
  if (purchase.obfuscatedAccountId && purchase.obfuscatedAccountId !== expectedAccount) {
    throw new ApiError(403, "purchase_owned_by_other_account", "This purchase belongs to another account");
  }

  const tokenHash = await sha256Hex(purchaseToken);
  const { data, error } = await svc.rpc("record_verified_purchase", {
    p_user: user.id,
    p_product_id: productId,
    p_product_kind: kind,
    p_token_hash: tokenHash,
    p_order_id: purchase.orderId,
    p_state: purchase.state,
    p_purchased_at: purchase.purchasedAt,
    p_expires_at: purchase.expiresAt,
    p_auto_renewing: purchase.autoRenewing,
    p_subscription_status: purchase.subscriptionStatus,
  });
  if (error) throw dbError(error);

  // Only after the grant is durably recorded do we acknowledge / consume with Google.
  if (purchase.state === "purchased") {
    if (kind === "consumable" && !purchase.consumed) {
      await consume(productId, purchaseToken);
    } else if (!purchase.acknowledged) {
      await acknowledge(purchase, purchaseToken);
    }
    await svc.rpc("mark_purchase_acknowledged", { p_token_hash: tokenHash, p_consumed: kind === "consumable" });
  }
  log(requestId, "purchase_verified", { user: user.id, product: productId, state: purchase.state });

  const { data: entitlements } = await db.rpc("my_entitlements");
  return json(req, requestId, { purchase: data, entitlements });
});
