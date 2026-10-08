// POST /functions/v1/project-upsert
// Body: UpsertProjectRequest { projectId, documentVersion, document, baseVersion, lamport }
//
// Optimistic concurrency: the write lands only when baseVersion matches the
// server's current document_version. On mismatch the client receives the ops
// it hasn't seen (missingOps) and must CRDT-merge locally, then retry. This
// keeps last-writer semantics OUT of the server — the merge is deterministic
// on-device, and the server never rewrites creative data.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const body = await req.json();
    const { projectId, document, documentVersion, baseVersion } = body;
    if (!projectId || !document) return fail("projectId and document required");

    const admin = adminClient();
    const { data: existing } = await admin.from("projects")
      .select("id, owner_id, document_version, lamport")
      .eq("id", projectId).maybeSingle();

    // Ownership / membership check (service role bypasses RLS).
    if (existing) {
      if (existing.owner_id !== user.id) {
        const { data: member } = await admin.from("project_members")
          .select("role").eq("project_id", projectId).eq("user_id", user.id).maybeSingle();
        if (!member || member.role === "VIEWER" || member.role === "COMMENTER") {
          return fail("forbidden", 403);
        }
      }
      if (existing.document_version > Number(baseVersion ?? 0)) {
        // Conflict: hand back ops after the client's known version.
        const { data: missing } = await admin.from("ops")
          .select("op_id, project_id, author_id, lamport, wall_clock, payload")
          .eq("project_id", projectId)
          .gt("lamport", existing.lamport)
          .order("lamport", { ascending: true })
          .limit(500);
        return json({
          accepted: false,
          serverVersion: existing.document_version,
          missingOps: (missing ?? []).map((o) => ({
            opId: o.op_id, projectId: o.project_id, authorId: o.author_id,
            lamport: o.lamport, wallClock: o.wall_clock,
            payload: typeof o.payload === "string" ? o.payload : JSON.stringify(o.payload),
          })),
        });
      }
    } else if (existing === null) {
      // New project: owner must be the caller.
    }

    const doc = typeof document === "string" ? JSON.parse(document) : document;
    const row = {
      id: projectId,
      owner_id: existing?.owner_id ?? user.id,
      name: doc?.name ?? "Untitled",
      template: doc?.template ?? "EMPTY",
      bpm: doc?.tempoMap?.markers?.[0]?.bpm ?? 120,
      key_name: doc?.key ? `${doc.key.noteName} ${doc.key.scale.displayName}` : null,
      time_signature: doc?.timeSignature ? `${doc.timeSignature.numerator}/${doc.timeSignature.beatUnit}` : "4/4",
      sample_rate: doc?.sampleRate ?? 48000,
      track_count: doc?.tracks?.length ?? 0,
      duration_bars: doc?.durationBars ?? 64,
      document: doc,
      document_version: Number(documentVersion ?? 1),
      is_archived: doc?.isArchived ?? false,
      genre: doc?.genre ?? null,
    };

    const { error } = await admin.from("projects").upsert(row, { onConflict: "id" });
    if (error) throw error;

    return json({ accepted: true, serverVersion: row.document_version, missingOps: [] });
  } catch (e) {
    return fail(String(e?.message ?? e), e?.message === "unauthorized" ? 401 : 500);
  }
});
