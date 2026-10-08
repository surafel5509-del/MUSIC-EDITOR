// GET /functions/v1/get-entitlements — authoritative tier + limits for the caller.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

const LIMITS: Record<string, unknown> = {
  FREE: {
    maxProjects: 3, maxTracksPerProject: 8, maxSimultaneousRecordTracks: 1,
    cloudStorageMb: 200, maxExportBitrateKbps: 192, losslessExport: false,
    stemExport: false, premiumInstruments: false, premiumFx: false, adsEnabled: true,
    realtimeCollaborators: 1, versionHistoryDays: 7, distributionEnabled: false,
  },
  PRO: {
    maxProjects: 2147483647, maxTracksPerProject: 64, maxSimultaneousRecordTracks: 4,
    cloudStorageMb: 20000, maxExportBitrateKbps: 320, losslessExport: true,
    stemExport: true, premiumInstruments: true, premiumFx: true, adsEnabled: false,
    realtimeCollaborators: 8, versionHistoryDays: 90, distributionEnabled: false,
  },
  PRO_PLUS: {
    maxProjects: 2147483647, maxTracksPerProject: 256, maxSimultaneousRecordTracks: 8,
    cloudStorageMb: 200000, maxExportBitrateKbps: 320, losslessExport: true,
    stemExport: true, premiumInstruments: true, premiumFx: true, adsEnabled: false,
    realtimeCollaborators: 32, versionHistoryDays: 365, distributionEnabled: true,
  },
};

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const admin = adminClient();

    const { data: ent } = await admin.from("entitlements")
      .select("*").eq("user_id", user.id).maybeSingle();

    let tier = "FREE";
    let expiresAt: string | null = null;
    if (ent) {
      const expired = ent.expires_at && new Date(ent.expires_at) < new Date();
      tier = expired && !ent.in_grace ? "FREE" : ent.tier;
      expiresAt = ent.expires_at;
      if (expired && !ent.in_grace) {
        await admin.from("entitlements").update({ tier: "FREE", in_grace: false }).eq("user_id", user.id);
      }
    }

    const { data: packs } = await admin.from("owned_packs")
      .select("pack_id").eq("user_id", user.id);

    return json({
      tier,
      limitsJson: JSON.stringify({ tier, ...LIMITS[tier] }),
      expiresAt,
      ownedPacks: (packs ?? []).map((p) => p.pack_id),
      storageUsedBytes: ent?.storage_used_bytes ?? 0,
    });
  } catch (e) {
    return fail(String(e?.message ?? e), e?.message === "unauthorized" ? 401 : 500);
  }
});
