// Supabase client factory for edge functions.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

export function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
}

/** Client scoped to the caller's JWT — RLS applies. */
export function userClient(req: Request) {
  const auth = req.headers.get("Authorization") ?? "";
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
}

export async function requireUser(req: Request) {
  const client = userClient(req);
  const { data: { user }, error } = await client.auth.getUser();
  if (error || !user) throw new Error("unauthorized");
  return { user, client };
}
