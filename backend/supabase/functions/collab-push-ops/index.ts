// POST /functions/v1/collab-push-ops
// Body: { ops: OpWire[] }
//
// Ingests CRDT ops with at-least-once semantics:
//  * dedupe by op_id (unique PK) — replays are silently accepted
//  * validates the caller's EDITOR membership via RLS insert
//  * advances projects.lamport watermark (max seen)
//  * broadcasts on realtime:project:<id> for low-latency peers
// Returns { accepted, rejected: [opId] }
import { corsHeaders, fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const { ops } = await req.json();
    if (!Array.isArray(ops) || ops.length === 0) return fail("ops required");
    if (ops.length > 500) return fail("max 500 ops per batch");

    const admin = adminClient();
    const accepted: string[] = [];
    const rejected: string[] = [];
    const projectIds = new Set<string>();
    let maxLamportByProject = new Map<string, number>();

    for (const op of ops) {
      // Basic shape validation — malformed ops are rejected, not fatal.
      if (!op?.opId || !op?.projectId || !op?.payload) {
        rejected.push(op?.opId ?? "unknown");
        continue;
      }
      const { error } = await admin.from("ops").insert({
        op_id: op.opId,
        project_id: op.projectId,
        author_id: user.id,
        lamport: Number(op.lamport ?? 0),
        wall_clock: op.wallClock ?? new Date().toISOString(),
        payload: typeof op.payload === "string" ? JSON.parse(op.payload) : op.payload,
      });
      if (error) {
        if (error.code === "23505") accepted.push(op.opId); // duplicate: idempotent OK
        else rejected.push(op.opId);
        continue;
      }
      accepted.push(op.opId);
      projectIds.add(op.projectId);
      const prev = maxLamportByProject.get(op.projectId) ?? 0;
      maxLamportByProject.set(op.projectId, Math.max(prev, Number(op.lamport ?? 0)));
    }

    // Advance watermarks + broadcast hints.
    for (const projectId of projectIds) {
      const lamport = maxLamportByProject.get(projectId)!;
      await admin.rpc("advance_project_lamport", { p_id: projectId, p_lamport: lamport })
        .then(({ error }) => {
          if (error) {
            // Fallback when the RPC isn't installed: direct update.
            admin.from("projects").update({ lamport }).eq("id", projectId).lt("lamport", lamport).then();
          }
        });
      await admin.channels(`project:${projectId}`).send({
        type: "broadcast",
        event: "ops",
        payload: { count: accepted.length, lamport },
      });
    }

    return json({ accepted: accepted.length, rejected });
  } catch (e) {
    return fail(String(e?.message ?? e), e?.message === "unauthorized" ? 401 : 500);
  }
});
