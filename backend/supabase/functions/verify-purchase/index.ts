// POST /functions/v1/verify-purchase
// Server-side Play Billing verification (the client is never trusted):
// calls the Google Play Developer API with the service account, checks the
// purchase/subscription state, records it, and grants the entitlement.
import { fail, json, preflight } from "../_shared/cors.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  try {
    const { user } = await requireUser(req);
    const { productId, purchaseToken, packageName } = await req.json();
    if (!productId || !purchaseToken || !packageName) return fail("missing fields");

    const admin = adminClient();

    // Idempotency: an already-verified token returns its grant.
    const { data: existing } = await admin.from("purchases")
      .select("*").eq("purchase_token", purchaseToken).maybeSingle();
    if (existing && existing.state === "PURCHASED") {
      return json({ granted: true, tier: tierFor(productId), expiresAt: null });
    }

    const play = await verifyWithPlay(productId, purchaseToken, packageName);
    if (!play.valid) {
      await admin.from("purchases").insert({
        user_id: user.id, product_id: productId, purchase_token: purchaseToken,
        package_name: packageName, kind: kindFor(productId), state: "FAILED",
      });
      return json({ granted: false });
    }

    await admin.from("purchases").upsert({
      user_id: user.id, product_id: productId, purchase_token: purchaseToken,
      package_name: packageName, kind: kindFor(productId), state: "PURCHASED",
      price_micros: play.priceMicros ?? 0, currency: play.currency ?? "USD",
      verified_at: new Date().toISOString(),
    }, { onConflict: "purchase_token" });

    // Grant: subscriptions extend entitlements; packs add owned_packs rows.
    const tier = tierFor(productId);
    if (tier) {
      await admin.from("entitlements").upsert({
        user_id: user.id, tier, expires_at: play.expiryTimeMillis
          ? new Date(Number(play.expiryTimeMillis)).toISOString() : null,
        in_grace: play.autoRenewing === false,
        updated_at: new Date().toISOString(),
      }, { onConflict: "user_id" });
    } else if (productId.startsWith("pack_")) {
      const { data: pack } = await admin.from("sample_packs")
        .select("id").eq("product_id", productId).maybeSingle();
      if (pack) {
        await admin.from("owned_packs").upsert(
          { user_id: user.id, pack_id: pack.id }, { onConflict: "user_id,pack_id" });
      }
    }

    return json({ granted: true, tier, expiresAt: play.expiryTimeMillis ?? null });
  } catch (e) {
    return fail(String(e?.message ?? e), 500);
  }
});

function tierFor(productId: string): string | null {
  if (productId.includes("pro_plus")) return "PRO_PLUS";
  if (productId.startsWith("sub_pro")) return "PRO";
  return null;
}

function kindFor(productId: string): string {
  if (productId.startsWith("sub_")) return productId.includes("annual") ? "SUB_ANNUAL" : "SUB_MONTHLY";
  if (productId.startsWith("pack_")) return "SOUND_PACK";
  return "PRESET_PACK";
}

/** Google Play Developer API v3 verification with a service-account JWT. */
async function verifyWithPlay(productId: string, token: string, packageName: string) {
  const sa = JSON.parse(Deno.env.get("PLAY_SERVICE_ACCOUNT_JSON") ?? "null");
  if (!sa) {
    // Dev mode without credentials: accept sandbox tokens only.
    return { valid: token.startsWith("sandbox:") };
  }
  const jwt = await signJwt(sa, "https://androidpublisher.googleapis.com/auth/androidpublisher");
  const res = await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/products/${productId}/tokens/${token}`, {
    headers: { Authorization: `Bearer ${await exchangeJwt(jwt, sa)}` },
  });
  if (!res.ok) return { valid: false };
  const body = await res.json();
  return {
    valid: body.purchaseState === 0, // 0 = purchased, 1 = canceled
    autoRenewing: body.autoRenewing,
    expiryTimeMillis: body.expiryTimeMillis,
    priceMicros: body.priceMicros,
    currency: body.priceCurrencyCode,
  };
}

async function signJwt(sa: any, scope: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const enc = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const header = enc({ alg: "RS256", typ: "JWT" });
  const claims = enc({
    iss: sa.client_email, scope, aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  });
  const data = new TextEncoder().encode(`${header}.${claims}`);
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, data);
  return `${header}.${claims}.${btoa(String.fromCharCode(...new Uint8Array(sig))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;
}

async function exchangeJwt(jwt: string, sa: any): Promise<string> {
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt,
    }),
  });
  const body = await res.json();
  return body.access_token;
}

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
  const bin = atob(b64);
  const buf = new ArrayBuffer(bin.length);
  const view = new Uint8Array(buf);
  for (let i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i);
  return buf;
}
