# ADR 0001: Supabase over Firebase

**Status:** accepted

## Context

The app needs auth, structured project data, binary asset storage, realtime
channels for collaboration, and serverless exports. Offline-first is
mandatory, so the backend mostly serves sync + collaboration.

## Decision

Use Supabase (Postgres + GoTrue + Storage + Realtime) with the official
Kotlin SDK (`supabase-kt` v3).

## Rationale

- First-class Kotlin SDK with suspend/Flow APIs; no Java-style listeners.
- Postgres relational model fits projects/tracks/clips and lets us store the
  durable CRDT op log (`collab_ops`) next to project rows with RLS.
- Realtime gives broadcast + presence + postgres_changes over one WebSocket,
  covering both low-latency fan-out and durable catch-up.
- Self-hostable; vendor lock-in is limited to thin client wrappers in
  `core:network` (repositories abstract everything).

## Consequences

- CI/release must inject Supabase URL/anon key (done via gradle properties).
- Row-Level-Security policies are part of the backend setup contract
  (documented in README).
