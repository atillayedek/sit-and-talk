import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { ApiError, requiredEnv, timingSafeEqual } from "./http.ts";

// Service-role client: bypasses RLS. Only used for server-owned writes and never exposed.
export function serviceClient(): SupabaseClient {
  return createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export type AuthedUser = { id: string; email?: string };

export type AuthedContext = {
  user: AuthedUser;
  jwt: string;
  // Client that acts as the caller, so database RLS and auth.uid() apply.
  db: SupabaseClient;
};

export async function requireUser(req: Request): Promise<AuthedContext> {
  const header = req.headers.get("authorization") ?? "";
  const jwt = header.replace(/^Bearer\s+/i, "").trim();
  if (!jwt) throw new ApiError(401, "unauthorized", "Missing access token");
  const url = requiredEnv("SUPABASE_URL");
  const anonKey = requiredEnv("SUPABASE_ANON_KEY");
  const db = createClient(url, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { authorization: `Bearer ${jwt}` } },
  });
  // The token is verified by PostgREST (which accepts the project's asymmetric signing keys), not by a
  // call to /auth/v1 from inside the function: that route is not reliably reachable from the Edge runtime.
  const claims = decodeJwtPayload(jwt) as { sub?: string; email?: string; role?: string; exp?: number };
  const expired = typeof claims.exp !== "number" || claims.exp * 1000 <= Date.now();
  if (!claims.sub || claims.role !== "authenticated" || expired) {
    console.warn(JSON.stringify({ level: "warn", event: "auth_rejected", reason: expired ? "expired" : "bad_claims" }));
    throw new ApiError(401, "unauthorized", "Invalid or expired session");
  }
  const { data: uid, error } = await db.rpc("auth_whoami");
  if (error || uid !== claims.sub) {
    console.warn(JSON.stringify({ level: "warn", event: "auth_rejected", reason: error ? `${error.code}: ${error.message}`.slice(0, 200) : "subject_mismatch" }));
    throw new ApiError(401, "unauthorized", "Invalid or expired session");
  }
  return { user: { id: claims.sub, email: claims.email }, jwt, db };
}

// Maps a PostgREST / RPC error raised by app_private.err() to an API error.
export function dbError(error: { message?: string; code?: string; details?: string } | null): ApiError {
  const code = error?.message ?? "internal";
  if (code === "rate_limited") {
    return new ApiError(429, "rate_limited", "Too many requests", Number(error?.details) || 60);
  }
  if (error?.code === "P0001") {
    const status = code === "not_found" || code === "not_room_member"
      ? 404
      : code === "forbidden" || code.startsWith("account_restricted") || code === "room_banned"
      ? 403
      : 409;
    return new ApiError(status, code, code);
  }
  return new ApiError(500, "internal", "Database error");
}

export function decodeJwtPayload(jwt: string): Record<string, unknown> {
  const part = jwt.split(".")[1] ?? "";
  const padded = part.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((part.length + 3) % 4);
  try {
    return JSON.parse(atob(padded));
  } catch {
    return {};
  }
}

// Reads a server secret: the function environment first, then Supabase Vault through the service-role-only
// `server_secret` RPC. Values are never logged or returned to clients.
export async function serverSecret(name: string): Promise<string> {
  const fromEnv = Deno.env.get(name)?.trim();
  if (fromEnv) return fromEnv;
  const { data, error } = await serviceClient().rpc("server_secret", { p_name: name });
  if (error || typeof data !== "string" || data.trim() === "") {
    throw new ApiError(503, "service_not_configured", `${name} is not configured`);
  }
  return data.trim();
}

export async function optionalServerSecret(name: string): Promise<string | null> {
  try {
    return await serverSecret(name);
  } catch {
    return null;
  }
}

// Internal endpoints (database hooks, cron) authenticate with a shared secret header.
export async function requireInternalSecret(req: Request) {
  const expected = await serverSecret("INTERNAL_HOOK_SECRET");
  const given = req.headers.get("x-internal-secret") ?? "";
  if (!timingSafeEqual(given, expected)) throw new ApiError(401, "unauthorized", "Unauthorized");
}
