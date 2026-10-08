// POST /functions/v1/export-render — server-side mixdown for constrained
// devices and for cloud publishing. Enqueues a render job; the worker pulls
// the project document + referenced samples, renders through the SAME C++
// graph compiled for the server (headless Oboe-free build), encodes
// WAV/MP3(LAME)/FLAC/AAC/OGG, uploads to exports/<user>/<jobId>.<ext>, and
// notifies via realtime + push.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const body = await req.json();
    const { projectId, kind, format, sampleRate, bitDepth, bitrateKbps, loudnessTargetLUFS, metadataJson } = body;
    if (!projectId || !format) return fail("projectId and format required");
    const admin = adminClient();

    // Entitlement gate server-side (never trust the client).
    const { data: ent } = await admin.from("entitlements").select("tier").eq("user_id", user.id).maybeSingle();
    const tier = ent?.tier ?? "FREE";
    if ((format === "FLAC" || format === "OGG" || kind === "STEMS") && tier === "FREE") {
      return fail("premium export on free tier", 402);
    }
    if (bitrateKbps > 192 && tier === "FREE") return fail("premium bitrate on free tier", 402);

    const jobId = crypto.randomUUID();
    await admin.from("export_jobs").insert({
      id: jobId, user_id: user.id, project_id: projectId, kind, format,
      sample_rate: sampleRate ?? 48000, bit_depth: bitDepth ?? "PCM_24",
      bitrate_kbps: bitrateKbps ?? 320, loudness_target: loudnessTargetLUFS ?? null,
      metadata: metadataJson ? JSON.parse(metadataJson) : {},
      status: "QUEUED", output_path: `${user.id}/${jobId}.${format.toLowerCase()}`,
    }).then(({ error }) => {
      if (error) console.warn("export_jobs insert:", error.message);
    });

    return json({
      jobId,
      statusUrl: `${Deno.env.get("SUPABASE_URL")}/rest/v1/export_jobs?id=eq.${jobId}&select=status,output_path,error`,
    }, 202);
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
