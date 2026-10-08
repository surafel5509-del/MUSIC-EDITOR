# Privacy Policy (TEMPLATE — counsel review required before shipping)

_Last updated: [DATE] · StudioOne Mobile ("the App"), operated by [LEGAL ENTITY] ("we")._

## 1. What we collect
| Category | Examples | Purpose | Legal basis |
|---|---|---|---|
| Account | email, display name, avatar, OAuth profile id | Authentication, profile | Contract |
| User content | projects, recordings, comments, posts | Provide creation/collaboration features | Contract |
| Purchases | product ids, tokens (no card data — handled by Google Play) | Entitlements, fraud prevention | Contract / Legal obligation |
| Diagnostics | crash reports, performance counters, feature analytics | Reliability, product improvement | **Consent** (off by default) |
| Device | model, OS version, audio device capabilities | Latency optimization, compatibility | Legitimate interest |

We do **not** collect: contacts, location, microphone audio except your own
recordings, or advertising identifiers without separate consent.

## 2. Consent gates
Analytics and crash reporting initialize only after you grant consent
(Settings → Privacy). Refusing has zero functional impact. Ad personalization
is a separate toggle; free-tier ads are contextual when off.

## 3. Sharing & processors
* Supabase (EU/US regions — configurable): database, auth, storage, realtime.
* Google Firebase: crash & analytics (consent-gated), push notifications.
* Google Play Billing: purchases.
No processor receives audio content except where YOU publish or share it.
Sub-processor list & DPA references: [URL].

## 4. Retention
* Account data: until deletion.
* Projects/ops: until you delete them; op log partitions archived after 90
  days, hard-purged after 24 months for inactive projects.
* Diagnostics: 90 days (crashes), 13 months (aggregated analytics).
* Backups: rolling 7-day PITR; deleted data ages out of backups ≤ 35 days.

## 5. Your rights (GDPR/CCPA)
Access & portability (export ZIP of projects + profile), rectification,
erasure (**Settings → Delete account** — immediate purge incl. storage and
auth record), restriction/objection, and withdrawal of consent at any time.
Requests: privacy@[DOMAIN]; regulator complaints unaffected. DPO contact:
dpo@[DOMAIN]. SCCs govern EU→US transfers.

## 6. Security
TLS 1.2+ in transit; access controls & RLS per-user; tokens encrypted at rest
on-device (Android Keystore); service credentials held in CI/edge secret
stores; breach notification ≤ 72 h to authorities, users without undue delay.

## 7. Children
Not directed to under-16 (or local equivalent); no knowingly collected minor
data; family-plan review roadmap.

## 8. Changes
Material changes → in-app notice + email 14 days ahead; continued use =
acceptance. Archive of prior versions retained.
