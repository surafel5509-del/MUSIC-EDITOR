// GET /functions/v1/collab-pull-ops?project_id=..&after_lamport=N&limit=500
// Returns ops with lamport > N ordered ascending + the server watermark.
// This REST path is the reconciliation source of truth after socket gaps.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, userClient } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const url = new URL(req.url);
    const projectId = url.searchParams.get("project_id");
    const after = Number(url.searchParams.get("after_lamport") ?? "0");
    const limit = Math.min(Number(url.searchParams.get("limit") ?? "500"), 1000);
    if (!projectId) return fail("project_id required");

    const client = userClient(req);
    // RLS check: reading the project verifies VIEWER+ membership.
    const { data: project, error: pErr } = await client.from("projects")
      .select("id, lamport").eq("id", projectId).single();
    if (pErr || !project) return fail("forbidden", 403);

    const admin = adminClient();
    const { data: ops, error } = await admin.from("ops")
      .select("op_id, project_id, author_id, lamport, wall_clock, payload")
      .eq("project_id", projectId)
      .gt("lamport", after)
      .order("lamport", { ascending: true })
      .limit(limit);
    if (error) throw error;

    return json({
      ops: (ops ?? []).map((o) => ({
        opId: o.op_id,
        projectId: o.project_id,
        authorId: o.author_id,
        lamport: o.lamport,
        wallClock: o.wall_clock,
        payload: typeof o.payload === "string" ? o.payload : JSON.stringify(o.payload),
      })),
      serverLamport: project.lamport,
    });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
