// Deletes the caller's account. Requires a fresh sign-in (password re-entered within 10 minutes),
// removes stored files, then deletes the auth user (profile and personal rows cascade).
import { ApiError, json, log, serve } from "../_shared/http.ts";
import { dbError, decodeJwtPayload, requireUser, serviceClient } from "../_shared/supabase.ts";

const REAUTH_WINDOW_SECONDS = 600;

type CleanupItem = { id: number; bucket: string; path: string };

serve(async (req, requestId) => {
  const { user, jwt } = await requireUser(req);
  const claims = decodeJwtPayload(jwt);
  const amr = Array.isArray(claims.amr) ? claims.amr as { method?: string; timestamp?: number }[] : [];
  const lastAuth = Math.max(0, ...amr.map((a) => Number(a.timestamp ?? 0)));
  if (Date.now() / 1000 - lastAuth > REAUTH_WINDOW_SECONDS) {
    throw new ApiError(401, "reauthentication_required", "Sign in again to delete the account");
  }

  const svc = serviceClient();
  const { data: request, error: reqError } = await svc.from("account_deletion_requests")
    .insert({ user_id: user.id }).select("id").single();
  if (reqError && reqError.code !== "23505") throw dbError(reqError);

  const { error: prepError } = await svc.rpc("prepare_account_deletion", { p_user: user.id });
  if (prepError) throw dbError(prepError);

  // Remove files now; anything that fails stays queued for the maintenance job.
  for (let round = 0; round < 20; round++) {
    const { data, error } = await svc.rpc("claim_storage_cleanup", { p_limit: 200 });
    if (error) throw dbError(error);
    const items = (data ?? []) as CleanupItem[];
    if (items.length === 0) break;
    const byBucket = new Map<string, CleanupItem[]>();
    for (const item of items) byBucket.set(item.bucket, [...(byBucket.get(item.bucket) ?? []), item]);
    for (const [bucket, group] of byBucket) {
      const { error: rmError } = await svc.storage.from(bucket).remove(group.map((g) => g.path));
      if (rmError) {
        for (const g of group) await svc.rpc("report_storage_cleanup_failure", { p_id: g.id, p_error: rmError.message });
      }
    }
  }

  const { error: delError } = await svc.auth.admin.deleteUser(user.id);
  if (delError) {
    if (request) {
      await svc.from("account_deletion_requests").update({ status: "failed", error: delError.message.slice(0, 300) })
        .eq("id", request.id);
    }
    throw new ApiError(500, "deletion_failed", "Account could not be deleted");
  }
  if (request) {
    await svc.from("account_deletion_requests").update({ status: "completed", processed_at: new Date().toISOString() })
      .eq("id", request.id);
  }
  log(requestId, "account_deleted");
  return json(req, requestId, { deleted: true });
});
