// Delivers queued pushes through FCM HTTP v1. Invoked by the database (pg_net) or cron with
// the internal hook secret. Pushes are data-only: the app renders them locally, localized, and
// re-checks access when opened. Push is never the source of truth.
import { json, log, requiredEnv, requireInternalSecret, serve } from "../_shared/http.ts";
import { dbError, serviceClient } from "../_shared/supabase.ts";
import { googleAccessToken, loadServiceAccount } from "../_shared/google.ts";

type Item = {
  id: number;
  user_id: string;
  category: string;
  title_key: string;
  body: string | null;
  data: Record<string, unknown>;
  collapse_key: string | null;
  high_priority: boolean;
  expires_at: string | null;
  tokens: string[];
};

serve(async (req, requestId) => {
  requireInternalSecret(req);
  const svc = serviceClient();
  const projectId = requiredEnv("FCM_PROJECT_ID");
  const accessToken = await googleAccessToken(
    loadServiceAccount("FCM_SERVICE_ACCOUNT_JSON"),
    "https://www.googleapis.com/auth/firebase.messaging",
  );

  const { data, error } = await svc.rpc("claim_push_batch", { p_limit: 200 });
  if (error) throw dbError(error);
  const items = (data ?? []) as Item[];
  let sent = 0;

  for (const item of items) {
    if (item.tokens.length === 0) {
      await svc.rpc("complete_push", { p_id: item.id, p_status: "skipped", p_error: "no_devices", p_invalid_tokens: [] });
      continue;
    }
    const ttlSeconds = item.expires_at
      ? Math.max(0, Math.floor((new Date(item.expires_at).getTime() - Date.now()) / 1000))
      : 86_400;
    if (item.expires_at && ttlSeconds === 0) {
      await svc.rpc("complete_push", { p_id: item.id, p_status: "skipped", p_error: "expired", p_invalid_tokens: [] });
      continue;
    }
    const payload: Record<string, string> = {
      category: item.category,
      title_key: item.title_key,
      ...(item.body ? { body: item.body } : {}),
      ...(item.expires_at ? { expires_at: item.expires_at } : {}),
    };
    for (const [k, v] of Object.entries(item.data ?? {})) {
      if (v !== null && v !== undefined) payload[k] = String(v);
    }

    const invalid: string[] = [];
    let delivered = 0;
    let lastError = "";
    for (const token of item.tokens) {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            data: payload,
            android: {
              priority: item.high_priority ? "HIGH" : "NORMAL",
              ttl: `${ttlSeconds}s`,
              ...(item.collapse_key ? { collapse_key: item.collapse_key.slice(0, 64) } : {}),
            },
          },
        }),
      });
      if (res.ok) {
        delivered++;
        continue;
      }
      const text = await res.text();
      lastError = `${res.status}`;
      if (res.status === 404 || text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) invalid.push(token);
    }
    sent += delivered;
    await svc.rpc("complete_push", {
      p_id: item.id,
      p_status: delivered > 0 ? "sent" : invalid.length === item.tokens.length ? "skipped" : "failed",
      p_error: delivered > 0 ? null : lastError,
      p_invalid_tokens: invalid,
    });
  }
  log(requestId, "push_batch", { claimed: items.length, delivered: sent });
  return json(req, requestId, { claimed: items.length, delivered: sent });
});
