// Periodic maintenance (cron via pg_net, or the scheduled GitHub workflow): removes Storage objects
// whose owning rows were deleted (posts, stories, unsent messages, replaced avatars, deleted accounts).
import { json, log, serve } from "../_shared/http.ts";
import { dbError, requireInternalSecret, serviceClient } from "../_shared/supabase.ts";

type CleanupItem = { id: number; bucket: string; path: string };

serve(async (req, requestId) => {
  await requireInternalSecret(req);
  const svc = serviceClient();
  let removed = 0;
  let failed = 0;
  for (let round = 0; round < 10; round++) {
    const { data, error } = await svc.rpc("claim_storage_cleanup", { p_limit: 200 });
    if (error) throw dbError(error);
    const items = (data ?? []) as CleanupItem[];
    if (items.length === 0) break;
    const byBucket = new Map<string, CleanupItem[]>();
    for (const item of items) byBucket.set(item.bucket, [...(byBucket.get(item.bucket) ?? []), item]);
    for (const [bucket, group] of byBucket) {
      const { error: rmError } = await svc.storage.from(bucket).remove(group.map((g) => g.path));
      if (rmError) {
        failed += group.length;
        for (const g of group) await svc.rpc("report_storage_cleanup_failure", { p_id: g.id, p_error: rmError.message });
      } else {
        removed += group.length;
      }
    }
    if (failed > 0) break;
  }
  log(requestId, "storage_cleanup", { removed, failed });
  return json(req, requestId, { removed, failed });
});
