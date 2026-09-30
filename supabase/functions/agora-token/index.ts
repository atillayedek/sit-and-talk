// Issues short-lived Agora RTC tokens. Channel, UID and role come from the database
// (public.rtc_authorize) — never from the request body.
import agoraToken from "agora-token";
import { ApiError, json, log, readJson, requireString, serve, UUID_RE } from "../_shared/http.ts";
import { dbError, requireUser, serverSecret } from "../_shared/supabase.ts";

const { RtcTokenBuilder, RtcRole } = agoraToken;

type Authorization = {
  channel_name: string;
  uid: number;
  role: "publisher" | "subscriber";
  mode: string;
  ttl_seconds: number;
};

serve(async (req, requestId) => {
  const { user, db } = await requireUser(req);
  const body = await readJson(req);
  const kind = requireString(body, "kind", /^(call|room)$/);
  const id = requireString(body, "id", UUID_RE);

  const { data, error } = await db.rpc("rtc_authorize", { p_kind: kind, p_id: id });
  if (error) throw dbError(error);
  const auth = data as Authorization;

  const appId = await serverSecret("AGORA_APP_ID");
  const certificate = await serverSecret("AGORA_APP_CERTIFICATE");
  const ttl = Math.min(Math.max(auth.ttl_seconds, 300), 3600);
  const role = auth.role === "publisher" ? RtcRole.PUBLISHER : RtcRole.SUBSCRIBER;
  const token = RtcTokenBuilder.buildTokenWithUid(appId, certificate, auth.channel_name, auth.uid, role, ttl, ttl);
  if (typeof token !== "string" || token.length === 0) {
    throw new ApiError(500, "token_generation_failed", "Could not create token");
  }
  log(requestId, "rtc_token_issued", { user: user.id, kind, id, role: auth.role, ttl });

  return json(req, requestId, {
    app_id: appId,
    channel_name: auth.channel_name,
    uid: auth.uid,
    token,
    role: auth.role,
    mode: auth.mode,
    expires_at_epoch_seconds: Math.floor(Date.now() / 1000) + ttl,
  });
});
