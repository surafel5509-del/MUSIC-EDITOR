// GET /functions/v1/library-search — FTS + filters over the catalog.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const url = new URL(req.url);
    const q = url.searchParams.get("q");
    const category = url.searchParams.get("category");
    const genre = url.searchParams.get("genre");
    const bpmMin = url.searchParams.get("bpm_min");
    const bpmMax = url.searchParams.get("bpm_max");
    const key = url.searchParams.get("key");
    const limit = Math.min(Number(url.searchParams.get("limit") ?? 40), 100);
    const admin = adminClient();

    let query = admin.from("library_items").select("*");
    if (q) query = query.ilike("name", `%${q}%`);
    if (category) query = query.eq("category", category);
    if (genre) query = query.eq("genre", genre);
    if (bpmMin) query = query.gte("bpm", Number(bpmMin) * 0.98); // ±2% tempo match
    if (bpmMax) query = query.lte("bpm", Number(bpmMax) * 1.02);
    if (key) query = query.eq("key_name", key);
    query = query.order("upload_count", { ascending: false }).limit(limit);

    const { data, error } = await query;
    if (error) throw error;

    const base = Deno.env.get("SUPABASE_URL")!;
    return json({
      items: (data ?? []).map((i: any) => ({
        id: i.id, name: i.name, category: i.category, genre: i.genre, mood: i.mood,
        bpm: i.bpm, keyName: i.key_name, isLoop: i.is_loop,
        durationFrames: i.duration_frames, sampleRate: i.sample_rate, format: i.format,
        previewUrl: `${base}/storage/v1/object/public/media/${i.preview_path}`,
        fullUrl: `${base}/storage/v1/object/public/packs/${i.full_path}`,
        artworkUrl: i.artwork_path ? `${base}/storage/v1/object/public/media/${i.artwork_path}` : null,
        packId: i.pack_id, isPremium: i.is_premium, tags: i.tags ?? [], license: i.license_kind,
      })),
      nextCursor: null,
    });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
