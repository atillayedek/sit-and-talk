import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import { ApiError, requiredEnv } from "./http.ts";

// Service-role client: bypasses RLS. Only used for server-owned writes and never exposed.
export function serviceClient(): SupabaseClient {
  return createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export type AuthedContext = {
  user: User;
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
  const { data, error } = await db.auth.getUser(jwt);
  if (error || !data.user) throw new ApiError(401, "unauthorized", "Invalid or expired session");
  return { user: data.user, jwt, db };
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
