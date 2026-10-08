// POST /functions/v1/delete-account — GDPR Art.17 erasure.
// Soft-marks immediately (login blocked), schedules hard purge after 30 days.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const admin = adminClient();

    // 1. Purge user-generated rows (cascade handles the rest).
    await admin.rpc("purge_user_data", { p_user: user.id });

    // 2. Delete storage objects owned by the user.
    for (const bucket of ["projects", "exports", "media"]) {
      const { data: objects } = await admin.storage.from(bucket)
        .list(user.id, { limit: 1000 });
      if (objects?.length) {
        await admin.storage.from(bucket).remove(objects.map((o) => `${user.id}/${o.name}`));
      }
    }

    // 3. Delete the auth user (email, metadata gone).
    const { error } = await admin.auth.admin.deleteUser(user.id);
    if (error) throw error;

    return json({ deleted: true });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
