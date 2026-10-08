// POST /functions/v1/share-create — owner-only scoped share links.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const { projectId, role, expiresInHours, forkOnAccept } = await req.json();
    const admin = adminClient();

    const { data: project } = await admin.from("projects")
      .select("id, owner_id").eq("id", projectId).maybeSingle();
    if (!project || project.owner_id !== user.id) return fail("owner only", 403);
    if (!["EDITOR", "COMMENTER", "VIEWER"].includes(role)) return fail("bad role");

    const expires = expiresInHours
      ? new Date(Date.now() + Number(expiresHours) * 3600_000).toISOString()
      : null;

    const { data, error } = await admin.from("share_links")
      .insert({ project_id: projectId, role, expires_at: expires, fork_on_accept: !!forkOnAccept, created_by: user.id })
      .select("token, expires_at").single();
    if (error) throw error;

    return json({
      token: data.token,
      url: `https://studioone.app/j/${data.token}`,
      expiresAt: data.expires_at,
    });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
