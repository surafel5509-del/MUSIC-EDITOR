# Monetization

## Tiers
| | Free | Pro | Pro+ |
|---|---|---|---|
| Projects | 3 | ∞ | ∞ |
| Tracks/project | 8 | 64 | 256 |
| Simultaneous record tracks | 1 | 4 | 8 |
| Cloud storage | 200 MB | 20 GB | 200 GB |
| Export | ≤192 kbps, WAV/AAC | +FLAC/OGG/320k, stems | + distribution |
| Instruments/FX | core | all | all |
| Collaborators | 1 | 8 | 32 |
| Version history | 7 d | 90 d | 365 d |
| Ads | yes (policy-gated) | no | no |

Limits live in ONE place (`TierLimits`, mirrored server-side in
`get-entitlements`) and are enforced through `CheckEntitlement` — features
never hardcode gates.

## Purchase security
Play Billing → client sends `purchaseToken` → `verify-purchase` edge function
validates against Play Developer API (service account) → entitlement row
flips → client refreshes. Refunds/revocations via Play RTDN webhook (roadmap).
Acknowledgement handled server-flow-first to survive client kills.

## À-la-carte IAPs
Sound packs & preset packs (`pack_*` products) grant `owned_packs` rows;
library items flip to downloadable. Rewarded ads grant 24 h temporary pack
access (free tier, opt-in only).

## Ads policy (non-intrusive by construction)
Placements: feed native card, projects-list banner, post-export interstitial,
rewarded library unlock. **Banned contexts:** inside arranger/mixer/piano
roll, during recording/playback, mid-export. Enforced by `AdPlacement`
gating + review checklist.

## Artist monetization
Tips (external payment handle on profile), paid posts (`is_premium_only` +
`price_micros` gated playback), and distribution (Pro+) are schema-ready
(posts/tips tables); payout orchestration (Stripe Connect) is a roadmap epic.
