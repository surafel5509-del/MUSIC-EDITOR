# Real-time Collaboration

## 1. Why CRDTs

Mobile collaborators go offline constantly (transit, venues, flights). An
OT system needs a central transform server and ordered delivery; a CRDT lets
**every replica converge deterministically without coordination** — the phone
merges its own offline edits with the server's history locally, and the server
only stores and relays ops. We use operation-based CRDTs with a Hybrid Logical
Clock (HLC) for stamping.

## 2. Replicated data structures

| Data | Structure | Rationale |
|---|---|---|
| Tracks, clips (existence) | **OR-Set** (add-wins) | Losing a freshly-added clip to a stale concurrent delete is worse than the reverse; deletes are causal (remove observed tags) |
| Track/project fields (volume, pan, mute, name, tempo…) | **LWW-Register per field** keyed `(entityId, field)` | Two users editing *different* fields of one track never conflict |
| MIDI notes, automation points | LWW-Register per element id | Same-note concurrent edits are rare; last stamp wins whole-note |
| FX slots | LWW-Register per slot id | |
| Comments | grow-only set + resolved-flag register | |
| Presence | not replicated (ephemeral socket frames) | |

Stamps: `HlcTimestamp(millis, counter, nodeId)`. Concurrent writes resolve by
millis → counter → nodeId (lexicographic). Every replica computes the same
winner without talking to anyone. Wire encoding packs stamp into one `lamport`
int64 as `millis*1000 + counter` (fits ~year 2286; nodeId tie-break is
recovered from the op's author).

## 3. Op protocol

```jsonc
// OpEnvelope (Kotlin model = wire = Room outbox row)
{
  "opId": "uuidv7",             // dedupe key (idempotent ingest)
  "projectId": "…",
  "authorId": "…",
  "lamport": 17000000001230007, // packed HLC
  "wallClock": "2026-10-08T20:00:00Z",
  "payload": { "kind": "UpdateTrackField", "trackId": "…", "field": "VOLUME", "value": { "kind": "Num", "v": -6.0 } }
}
```

Payload variants: `AddTrack/RemoveTrack/UpdateTrackField`, `AddClip/RemoveClip/
UpdateClip`, `AddNote/RemoveNote/UpdateNote`, `Add/RemoveAutomationPoint`,
`UpdateFxSlot`, `UpdateProjectField`, `AddComment/ResolveComment`, `Presence`.

## 4. Transport topology

* **Socket (hint channel):** one WebSocket per open project
  (`realtime:project:<id>` via Supabase Realtime; frames `{t: op|presence|ack|hello}`).
  Low latency, lossy-tolerant — disconnects are expected.
* **REST (source of truth):** `collab-push-ops` (batched, deduped by op_id,
  RLS-checked EDITOR membership) and `collab-pull-ops?after_lamport=N`.
  On every (re)connect and every 5 s, the client pulls the gap and applies
  missed ops. The socket never mutates state that REST wouldn't reproduce.
* **Durable outbox:** every local op is written to Room (`sync_ops`) *before*
  the socket send. Process death mid-session loses nothing; `OpFlushWorker`
  (expedited WorkManager, exponential backoff) drains the outbox when
  connectivity returns.

## 5. Offline merge & conflict review

Reconnect flow: `pull(after = localWatermark)` → apply ops in lamport order →
`materialize()` the view. Because every structure is a CRDT, **order of
application doesn't matter** (property-tested: `ProjectCrdtTest.concurrent
field edits converge regardless of delivery order`).

Auto-resolution covers ~everything. What remains *human-visible*:
* both sides renamed the same field since the base snapshot → HLC winner
  applies, but a `MergeConflict` row is recorded and the conflict sheet shows
  "Ana set volume −6 dB while you set −12 dB (−12 kept)" with Keep mine /
  Keep theirs / Duplicate track actions.
* Document-level CAS: `project-upsert` rejects stale `baseVersion` and returns
  `missingOps`; the client merges and retries — the server never rewrites
  creative data.

## 6. Presence & permissions

Presence frames (`screen`, `cursorFrame`, `selectedTrackId`, color) are
ephemeral, throttled to 2 Hz, rendered as collaborator avatars + a colored
cursor ghost in the arranger. Roles: OWNER > EDITOR > COMMENTER > VIEWER;
enforced twice — RLS policies (`has_project_role`) and edge-function checks.
Share links carry role + expiry + optional fork-on-accept (remix flow copies
the document into an independent project).

## 7. Guest → account migration

Guest projects are `ownerId = null`, local-only. On `upgradeGuest`, the sync
engine re-stamps local documents with the new uid and pushes them; the server
adopts documents whose `owner_id` is null and whose fingerprint matches the
device's migration token (issued at signup) — preventing squatting.

## 8. Scale plan

Ops table partitioned monthly (migration 0009); hot partition on primary,
cold archived to object storage after 90 days. Push batches capped at 500
ops; broadcast payloads carry counts + watermark (not op bodies) so fan-out
stays tiny — peers pull deltas over REST. Per-project channels mean presence
traffic is O(collaborators), not O(users).
