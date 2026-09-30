// Immediately removes a kicked/banned member from an Agora room channel using Agora's
// kicking-rule REST API. Without AGORA_CUSTOMER_ID / AGORA_CUSTOMER_SECRET this returns
// `agora_rest_not_configured`: the member is still removed from the room in the database and
// cannot renew a token, but an already-connected client is only stopped when its token expires.
import { ApiError, json, log, readJson, requireString, serve, UUID_RE } from "../_shared/http.ts";
import { dbError, requireUser, serviceClient } from "../_shared/supabase.ts";

serve(async (req, requestId) => {
  const { user, db } = await requireUser(req);
  const body = await readJson(req);
  const roomId = requireString(body, "room_id", UUID_RE);
  const targetId = requireString(body, "user_id", UUID_RE);

  // The caller must moderate this room, and the target must already be removed from it.
  const { data: room, error } = await db.rpc("get_room", { p_room: roomId });
  if (error) throw dbError(error);
  const myRole = (room as { my_role: string | null }).my_role;
  if (myRole !== "owner" && myRole !== "moderator") throw new ApiError(403, "forbidden", "forbidden");
  const members = (room as { members: { user_id: string }[] }).members;
  if (members.some((m) => m.user_id === targetId)) throw new ApiError(409, "still_member", "Remove the member first");

  const customerId = Deno.env.get("AGORA_CUSTOMER_ID");
  const customerSecret = Deno.env.get("AGORA_CUSTOMER_SECRET");
  const appId = Deno.env.get("AGORA_APP_ID");
  if (!customerId || !customerSecret || !appId) {
    return json(req, requestId, { code: "agora_rest_not_configured", enforced: false });
  }

  const { data: target, error: targetError } = await serviceClient().rpc("rtc_kick_target", {
    p_room: roomId,
    p_user: targetId,
  });
  if (targetError || !target) throw new ApiError(404, "not_found", "not_found");
  const { channel_name: channelName, uid } = target as { channel_name: string; uid: number };

  const res = await fetch("https://api.agora.io/dev/v1/kicking-rule", {
    method: "POST",
    headers: {
      "authorization": "Basic " + btoa(`${customerId}:${customerSecret}`),
      "content-type": "application/json",
    },
    body: JSON.stringify({
      appid: appId,
      cname: channelName,
      uid,
      time: 10,
      privileges: ["join_channel"],
    }),
  });
  log(requestId, "rtc_kick", { by: user.id, room: roomId, status: res.status });
  if (!res.ok) throw new ApiError(502, "agora_rest_failed", "Agora rejected the request");
  return json(req, requestId, { enforced: true });
});
