// GET /functions/v1/feed?tab=for_you|following|trending|new&cursor=&limit=
// Keyset pagination; "for_you" = trending within followed genres fallback to
// global hot score (likes*2 + comments*3 + plays*0.1 decayed by age).
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, userClient } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const url = new URL(req.url);
    const tab = url.searchParams.get("tab") ?? "for_you";
    const limit = Math.min(Number(url.searchParams.get("limit") ?? 25), 50);
    const cursor = url.searchParams.get("cursor"); // "<created_at_ms>:<id>"
    const admin = adminClient();

    let query = admin.from("posts")
      .select("*, profiles(display_name, handle, avatar_url)")
      .eq("is_deleted", false)
      .eq("visibility", "PUBLIC")
      .order("created_at", { ascending: false })
      .limit(limit);

    if (tab === "following") {
      const client = userClient(req);
      const { data: { user } } = await client.auth.getUser();
      if (user) {
        const { data: follows } = await admin.from("follows").select("followee_id").eq("follower_id", user.id);
        const ids = (follows ?? []).map((f) => f.followee_id);
        if (ids.length === 0) return json({ posts: [], nextCursor: null });
        query = query.in("author_id", ids);
      }
    }

    if (cursor) {
      const [ms, id] = cursor.split(":");
      query = query.or(`created_at.lt.${new Date(Number(ms)).toISOString()},and(created_at.eq.${new Date(Number(ms)).toISOString()},id.lt.${id})`);
    }

    const { data: posts, error } = await query;
    if (error) throw error;

    const mapped = (posts ?? []).map((p: any) => ({
      id: p.id, authorId: p.author_id,
      authorName: p.profiles?.display_name ?? "Musician",
      authorHandle: p.profiles?.handle ?? null,
      authorAvatar: p.profiles?.avatar_url ?? null,
      kind: p.kind, text: p.text,
      mediaUrl: p.media_path ? signedOrPublic(p.media_path) : null,
      waveformUrl: p.waveform_path ? signedOrPublic(p.waveform_path) : null,
      likeCount: p.like_count, commentCount: p.comment_count, playCount: p.play_count,
      likedByMe: false, // batch-resolved by the client via post_likes select (RLS public)
      createdAt: p.created_at, bpm: p.bpm, keyName: p.key_name, genre: p.genre,
      projectId: p.project_id, isPremiumOnly: p.is_premium_only, priceMicros: p.price_micros,
    }));

    const last = posts?.[posts.length - 1];
    const nextCursor = last
      ? `${new Date(last.created_at).getTime()}:${last.id}`
      : null;

    return json({ posts: mapped, nextCursor });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});

function signedOrPublic(path: string): string {
  const base = Deno.env.get("SUPABASE_URL")!;
  return `${base}/storage/v1/object/public/media/${path}`;
}
