// POST /functions/v1/publish-post — validates storage paths, denormalizes
// author info, increments profile track_count, returns the PostWire.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const body = await req.json();
    const admin = adminClient();

    // Only allow media paths under the caller's own storage prefix.
    for (const path of [body.mediaStoragePath, body.waveformPath]) {
      if (path && !String(path).startsWith(`${user.id}/`)) {
        return fail("media path outside user prefix", 403);
      }
    }

    const { data: post, error } = await admin.from("posts").insert({
      author_id: user.id,
      kind: body.kind ?? "TRACK",
      text: body.text ?? null,
      media_path: body.mediaStoragePath ?? null,
      waveform_path: body.waveformPath ?? null,
      project_id: body.projectId ?? null,
      genre: body.genre ?? null,
      bpm: body.bpm ?? null,
      key_name: body.keyName ?? null,
      visibility: body.visibility ?? "PUBLIC",
      is_premium_only: body.isPremiumOnly ?? false,
      price_micros: body.priceMicros ?? 0,
      remix_of: body.remixOf ?? null,
      challenge_id: body.challengeId ?? null,
    }).select("*").single();
    if (error) throw error;

    await admin.from("profiles").update({ track_count: admin.rpc ? undefined : undefined }).eq("id", user.id).then();
    await admin.rpc("increment_track_count", { p_user: user.id }).then(() => {});

    const { data: profile } = await admin.from("profiles").select("*").eq("id", user.id).single();

    return json({
      id: post.id, authorId: user.id,
      authorName: profile?.display_name ?? "Musician",
      authorHandle: profile?.handle ?? null, authorAvatar: profile?.avatar_url ?? null,
      kind: post.kind, text: post.text,
      mediaUrl: post.media_path ? `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/media/${post.media_path}` : null,
      waveformUrl: post.waveform_path ? `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/media/${post.waveform_path}` : null,
      likeCount: 0, commentCount: 0, playCount: 0, likedByMe: false,
      createdAt: post.created_at, bpm: post.bpm, keyName: post.key_name,
      genre: post.genre, projectId: post.project_id,
      isPremiumOnly: post.is_premium_only, priceMicros: post.price_micros,
    });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});
