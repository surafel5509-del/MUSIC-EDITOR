# Backend API Reference

Base URLs (injected via `ApiConfig`):
* REST (PostgREST): `{SUPABASE_URL}/rest/v1`
* Edge functions:  `{SUPABASE_URL}/functions/v1`
* Realtime WS:     `wss://{SUPABASE_URL}/realtime/v1/websocket`
* Storage:         `{SUPABASE_URL}/storage/v1`

**Auth:** every call carries `apikey: <anon>` and `Authorization: Bearer <JWT>`
(GoTrue access token, 1 h TTL, auto-refreshed). Guest sessions use anon-only
calls (public catalog/feed reads).

## Collaboration

### `POST /functions/v1/collab-push-ops`
```jsonc
// req
{ "ops": [ { "opId": "…", "projectId": "…", "authorId": "…", "lamport": 123,
             "wallClock": "ISO", "payload": "<CollabOp JSON string>" } ] }
// res 200
{ "accepted": 12, "rejected": ["opId3"] }
```
Dedupes by `opId` (replays count as accepted). Requires EDITOR role (RLS).
Broadcasts `{count, lamport}` hint on `realtime:project:<id>`.

### `GET /functions/v1/collab-pull-ops?project_id=&after_lamport=&limit=`
```jsonc
{ "ops": [ …OpWire… ], "serverLamport": 456 }
```
Requires VIEWER+. Ordered by lamport ascending; limit ≤ 1000.

### `POST /functions/v1/project-upsert`
```jsonc
// req
{ "projectId": "…", "documentVersion": 12, "document": "<Project JSON>",
  "baseVersion": 11, "lamport": 456 }
// res 200 (accepted)
{ "accepted": true, "serverVersion": 12, "missingOps": [] }
// res 200 (CAS conflict)
{ "accepted": false, "serverVersion": 15, "missingOps": [ …OpWire… ] }
```

## Sharing

* `POST /share-create {projectId, role, expiresInHours?, forkOnAccept}` → `{token, url, expiresAt}` (OWNER only)
* `POST /share-accept {token}` → `{projectId, role, forked, documentVersion, document?}`
  (410 when expired/exhausted; `document` present on fork or first join)

## Monetization

* `GET /get-entitlements` → `{tier, limitsJson, expiresAt?, ownedPacks[], storageUsedBytes}`
* `POST /verify-purchase {productId, purchaseToken, packageName}` → `{granted, tier?, expiresAt?}`
  (server calls Play Developer API with a service account; idempotent per token)

## Social

* `GET /feed?tab=for_you|following|trending|new&cursor=&limit=` → `{posts: PostWire[], nextCursor}`
  (keyset cursor `"<epochMs>:<postId>"`)
* `POST /publish-post` (PublishPostRequest) → `PostWire` — media paths must be under `<uid>/`
* `POST /posts/{id}/like`, `DELETE /posts/{id}/like`
* Direct PostgREST reads: `post_comments`, `follows`, `profiles` (RLS-scoped)

## Library

* `GET /library-search?q=&category=&genre=&bpm_min=&bpm_max=&key=&limit=&cursor=`
  → `{items: LibraryItemWire[], nextCursor}` (±2 % BPM tolerance applied server-side)
* Downloads: public `packs`/`media` buckets via CDN; signed URLs for private assets.

## Jobs

* `POST /analyze-audio {storagePath}` → 202 `{queued:true}` — BPM/key/loudness worker; results land on the item row + realtime event.
* `POST /export-render` (ExportJobRequest) → 202 `{jobId, statusUrl}` — server mixdown (premium formats, constrained devices). Poll `export_jobs?id=eq.<jobId>`.

## GDPR

* `POST /delete-account` — purges rows (`purge_user_data`), storage objects, then the auth user. Irreversible; the client shows a 2-step confirm.
* Data export (Art. 20): `GET /rest/v1/projects?owner_id=eq.<uid>&select=*` + storage listing under `<uid>/` — the Settings screen bundles this as a ZIP (roadmap: server-side bundle job).

## Error model

Edge functions return `{ "error": "<message>" }` with HTTP status:
400 validation · 401 auth · 402 entitlement · 403 RLS · 404 missing ·
410 gone (expired link) · 429 rate limited (per-ip pg bucket) · 5xx retryable.
Clients map 5xx/429 to exponential backoff (`core:common retryWithBackoff`).
