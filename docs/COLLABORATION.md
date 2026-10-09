# Collaboration design

## Model

A session is a project plus a realtime channel (`project:{id}`) on Supabase
Realtime. All shared mutations are expressed as `CollabOp` values:

```
AddTrack / RemoveTrack / UpdateTrackField
AddClip  / RemoveClip  / UpdateClipField
ChatMessage
```

Each op carries `(opId, siteId, lamport, projectId)`.

## Merge semantics (CRDT)

- **Entities form an add-wins OR-set.** An entity exists iff its lifecycle
  resolves to an add. Concurrent add/remove resolves by highest
  `(lamport, siteId)`.
- **Fields are LWW registers.** `UpdateClipField("gain", …)` writes win by
  `(lamport, siteId)`; independent fields never clobber each other.
- **Lamport clocks** are advanced on send *and* receive, so causality is
  preserved without synchronized clocks.

`CrdtEngine.reduce(ops)` is a pure function: same op log ⇒ same document,
order-independent. This makes late-join rebuild trivial: fetch `collab_ops`
for the project, reduce, apply.

## Transport

- **Broadcast** (`channel.broadcast("op", …)`) — low-latency, at-most-once.
- **Durable log** — every op is also inserted into `collab_ops` (Postgres),
  which feeds `postgres_change_flow` watchers and late joiners. Broadcast is
  an optimization, not the source of truth.
- **Presence** — join/leave + screen info; cursors ride on broadcast events
  (milestone 2).

## Local integration

- Local edits → `EditorSession` command → `CollabOp` → `send()`.
- Remote ops → `CrdtEngine.observe()` (clock) → `EditorSession.applyRemoteState()`.
- Remote ops **do not** enter the local undo stack: undo is per-site
  (industry standard for co-editing); a user's undo only reverts their own
  writes.

## Conflict examples

| Concurrent writes | Winner |
| --- | --- |
| A sets clip gain 0.5 (λ=5), B sets gain 0.9 (λ=7) | B (higher lamport) |
| A deletes track, B renames it (λ equal, siteId A<B) | B's rename (add-wins keeps entity; field LWW applies) |
| A adds clip X, B removes clip X (B saw the add) | remove wins (causally later) |

## WebRTC roadmap

Audio monitoring between collaborators (listen to someone's mic live) is
planned via WebRTC SFU; the CRDT layer above is transport-agnostic, so that
work does not change the merge model.
