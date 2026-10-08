// Shared CORS + JSON helpers for all edge functions.
export const corsHeaders = {
  "Access-Control-Allow-Origin": "*", // mobile clients; tighten per environment
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, prefer",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS, PATCH, DELETE",
};

export function json(data: unknown, status = 200, extra: Record<string, string> = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json", ...extra },
  });
}

export function fail(message: string, status = 400) {
  return json({ error: message }, status);
}

export function preflight(req: Request) {
  return req.method === "OPTIONS" ? new Response("ok", { headers: corsHeaders }) : null;
}
