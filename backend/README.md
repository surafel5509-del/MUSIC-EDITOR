# StudioOne Backend (Supabase)

Provisioning, schema, RLS, edge functions, and the realtime topology for
StudioOne Mobile. The same API is documented for clients in
[docs/API.md](../docs/API.md).

## Stack

| Concern        | Supabase product                          |
|----------------|-------------------------------------------|
| Auth (OAuth2/JWT) | GoTrue — email, Google, Facebook, Apple (PKCE) |
| Database       | Postgres 15 + PostgREST                   |
| Realtime       | Realtime channels (WebSocket) + `postgres_changes` |
| Storage        | S3-compatible buckets (projects, media, packs) |
| Serverless     | Edge Functions (Deno)                     |
| Search         | Postgres FTS + pg_trgm (library/feed)     |

## Provisioning

```bash
npm i -g supabase
supabase login
supabase init          # already done in this repo
supabase link --project-ref <ref>
supabase db push       # applies migrations/ in order
supabase functions deploy
supabase storage create projects --private
supabase storage create media --public
supabase storage create packs --public
supabase storage create exports --private
```

Secrets (set in the Supabase dashboard; consumed by edge functions):

* `PLAY_SERVICE_ACCOUNT_JSON` — Play Developer API key (purchase verification)
* `ANALYSIS_WORKER_URL` — optional GPU/libsonic analysis worker
* `STRIPE_WEBHOOK_SECRET` — artist tip payouts (roadmap)

## Realtime topology

Clients subscribe to one channel per open project:

```
realtime:project:<projectId>
```

Op payloads are broadcast by the `collab-push-ops` function after durable
insert; the socket is a **hint channel** — the REST pull endpoint is the
source of truth on reconnect (see docs/COLLABORATION.md §4).

## Scaling notes (millions of users)

* `ops` table is partitioned monthly by `created_at` (migration 0009);
  hot partition lives on the primary, cold partitions moved to read replicas.
* Feed uses keyset pagination (`created_at, id`) — no OFFSET scans.
* Library search uses a materialized FTS index refreshed every 5 minutes.
* Storage objects are served through a CDN with signed URLs (15 min TTL).
* Edge functions are stateless; all durable state in Postgres.
