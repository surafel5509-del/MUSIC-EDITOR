// POST /functions/v1/analyze-audio { storagePath }
// Queue-based BPM/key/loudness analysis. The heavy DSP runs in a worker
// (libsonic/essentia container in production); this function enqueues and
// returns 202. Results land on library_items / user_samples rows and are
// broadcast over realtime so the client refreshes tags.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const { storagePath } = await req.json();
    if (!storagePath) return fail("storagePath required");
    const admin = adminClient();

    const { error } = await admin.from("analysis_jobs").insert({
      user_id: user.id, storage_path: storagePath, status: "QUEUED",
    });
    if (error) {
      // Table created in migration 0010 (roadmap); tolerate dev environments.
      console.warn("analysis_jobs insert failed:", error.message);
    }

    // Production: publish to the worker queue (PG NOTIFY / SQS).
    await admin.rpc("notify_analysis_worker", { p_path: storagePath }).catch(() => {});

    return json({ queued: true }, 202);
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
