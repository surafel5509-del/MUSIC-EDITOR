# Security & Privacy Engineering

## Authentication
* Supabase GoTrue: email/password + OAuth (Google id_token, Facebook token,
  Apple via AppAuth PKCE web flow with nonce replay protection).
* JWT access tokens (1 h) + rotating refresh tokens persisted in
  **EncryptedSharedPreferences** (AES-256-GCM, Android Keystore-backed).
* Guest mode: fully local identity; upgrade migrates projects with a
  one-time device migration token (prevents account squatting).

## Authorization
* **RLS on every table** (`0007_rls.sql`): ownership + `has_project_role()`
  membership checks. Service role is used only inside edge functions, which
  re-check membership explicitly.
* Ops are append-only (no update/delete policies) — the CRDT log is auditable.
* Share links: scoped role, expiry, max uses, owner-only creation; fork links
  never grant access to the original.

## Encryption
* In transit: TLS 1.2+ everywhere (OkHttp defaults; certificate pinning to
  the Supabase edge is a hardening flag in `NetworkModule`).
* At rest: Room SQLCipher migration is config-ready (1.1 hardening item);
  tokens already encrypted via Keystore; exported media is user-owned public
  storage by design (documented in the export sheet).

## Purchase security
Client never trusts itself: every Play purchase token is verified
server-side against the Play Developer API before entitlements flip
(`verify-purchase`); refunds propagate via the Play RTDN webhook (roadmap fn
`play-rtdn`) revoking tiers.

## GDPR / CCPA
* Consent recorded per purpose (`profiles.gdpr_consents`); analytics &
  crash collection are **off by default** and gated by `AnalyticsHub`
  (nothing buffers pre-consent).
* Art. 17 erasure: `delete-account` purges rows, storage objects, auth user.
* Art. 20 portability: project documents + media export (Settings → Your data).
* Data map & retention table: [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
* No audio content ever appears in logs/analytics; event schemas are sealed
  types (no free-form maps) to prevent accidental PII capture.

## Abuse & moderation
* Rate limits: edge functions per-IP token bucket; ops push ≤ 500/batch.
* UGC moderation pipeline (roadmap): post reports table + automod hooks in
  `publish-post`; community guidelines surfaced in onboarding.
