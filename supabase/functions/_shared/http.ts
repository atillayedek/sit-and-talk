// Shared HTTP helpers: consistent error bodies, request ids, CORS for real web origins only.

export type ErrorBody = {
  code: string;
  message: string;
  request_id: string;
  retry_after?: number;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfter?: number,
  ) {
    super(message);
  }
}

const allowedOrigins = (Deno.env.get("ALLOWED_WEB_ORIGINS") ?? "")
  .split(",")
  .map((o) => o.trim())
  .filter((o) => o.length > 0);

// CORS is not an auth mechanism; it only lets our own web pages call these endpoints from a browser.
export function corsHeaders(req: Request): Record<string, string> {
  const origin = req.headers.get("origin");
  if (!origin || !allowedOrigins.includes(origin)) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-headers": "authorization, x-client-info, apikey, content-type, idempotency-key",
    "access-control-allow-methods": "POST, OPTIONS",
    "vary": "origin",
  };
}

export function json(req: Request, requestId: string, body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(req), "content-type": "application/json", "x-request-id": requestId },
  });
}

export function errorResponse(req: Request, requestId: string, error: unknown): Response {
  if (error instanceof ApiError) {
    const body: ErrorBody = { code: error.code, message: error.message, request_id: requestId };
    const headers: Record<string, string> = {
      ...corsHeaders(req),
      "content-type": "application/json",
      "x-request-id": requestId,
    };
    if (error.retryAfter !== undefined) {
      body.retry_after = error.retryAfter;
      headers["retry-after"] = String(error.retryAfter);
    }
    return new Response(JSON.stringify(body), { status: error.status, headers });
  }
  // Never leak stack traces or upstream payloads to clients.
  console.error(JSON.stringify({ request_id: requestId, level: "error", error: String(error) }));
  return json(req, requestId, { code: "internal", message: "Unexpected server error", request_id: requestId }, 500);
}

type Handler = (req: Request, requestId: string) => Promise<Response>;

export function serve(handler: Handler, methods: string[] = ["POST"]) {
  Deno.serve(async (req) => {
    const requestId = crypto.randomUUID();
    if (req.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(req) });
    }
    if (!methods.includes(req.method)) {
      return errorResponse(req, requestId, new ApiError(405, "method_not_allowed", "Method not allowed"));
    }
    try {
      return await handler(req, requestId);
    } catch (error) {
      return errorResponse(req, requestId, error);
    }
  });
}

export async function readJson(req: Request, maxBytes = 16_384): Promise<Record<string, unknown>> {
  const length = Number(req.headers.get("content-length") ?? "0");
  if (length > maxBytes) throw new ApiError(413, "payload_too_large", "Request body too large");
  const text = await req.text();
  if (text.length > maxBytes) throw new ApiError(413, "payload_too_large", "Request body too large");
  if (text.trim() === "") return {};
  try {
    const value = JSON.parse(text);
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("not an object");
    }
    return value as Record<string, unknown>;
  } catch {
    throw new ApiError(400, "invalid_json", "Body must be a JSON object");
  }
}

export function requireString(body: Record<string, unknown>, key: string, pattern?: RegExp, maxLength = 512): string {
  const value = body[key];
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength || (pattern && !pattern.test(value))) {
    throw new ApiError(400, "invalid_request", `Invalid field: ${key}`);
  }
  return value;
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function log(requestId: string, event: string, fields: Record<string, unknown> = {}) {
  // Only non-sensitive identifiers belong here: no tokens, e-mails or message contents.
  console.log(JSON.stringify({ request_id: requestId, event, ...fields }));
}

export function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new ApiError(503, "service_not_configured", `${name} is not configured`);
  return value;
}

export function timingSafeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}
