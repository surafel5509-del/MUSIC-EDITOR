// POST /functions/v1/share-accept — redeem a share token.
// Non-fork: inserts project_members row. Fork: deep-copies the document into
// a new project owned by the accepter (remix lineage recorded client-side).
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const { token } = await req.json();
    const admin = adminClient();

    const { data: link } = await admin.from("share_links").select("*").eq("token", token).maybeSingle();
    if (!link) return fail("invalid link", 404);
    if (link.expires_at && new Date(link.expires_at) < new Date()) return fail("link expired", 410);
    if (link.max_uses && link.use_count >= link.max_uses) return fail("link exhausted", 410);

    const { data: project } = await admin.from("projects")
      .select("id, owner_id, document, document_version").eq("id", link.project_id).single();

    if (link.fork_on_accept) {
      const doc = typeof project.document === "string" ? JSON.parse(project.document) : project.document;
      const forkId = crypto.randomUUID();
      doc.id = forkId;
      doc.ownerId = user.id;
      doc.name = `${doc.name ?? "Project"} (remix)`;
      doc.documentVersion = 1;
      const { error } = await admin.from("projects").insert({
        id: forkId, owner_id: user.id, name: doc.name, document: doc,
        document_version: 1, bpm: doc?.tempoMap?.markers?.[0]?.bpm ?? 120,
      });
      if (error) throw error;
      await admin.from("share_links").update({ use_count: link.use_count + 1 }).eq("token", token);
      return json({ projectId: forkId, role: "OWNER", forked: true, documentVersion: 1, document: JSON.stringify(doc) });
    }

    await admin.from("project_members").upsert(
      { project_id: project.id, user_id: user.id, role: link.role },
      { onConflict: "project_id,user_id" },
    );
    await admin.from("share_links").update({ use_count: link.use_count + 1 }).eq("token", token);

    return json({
      projectId: project.id, role: link.role, forked: false,
      documentVersion: project.document_version,
      document: JSON.stringify(project.document),
    });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
