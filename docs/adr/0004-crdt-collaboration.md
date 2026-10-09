# ADR 0004: CRDT ops with Lamport clocks for collaboration

**Status:** accepted

## Context

Multiple users edit the same project concurrently, including offline periods
and reconnects. We need convergence without a central locking server.

## Decision

Operation-based CRDT: entity lifecycle as add-wins OR-set, fields as LWW
registers, causality via Lamport clocks; ops broadcast over Supabase
Realtime and persisted to `collab_ops`.

## Rationale

- Music documents are sparse (tracks/clips/fields), so per-field LWW gives
  intuitive behavior with minimal merge machinery.
- Lamport clocks avoid wall-clock skew across devices.
- Durable op log makes rebuild/replay trivial for late joiners; the merge is
  a pure reducer, exhaustively unit-testable (`CrdtEngineTest`).

## Consequences

- Undo is per-site (users revert their own edits), matching co-editing UX
  norms.
- Field-level granularity means two users can edit different properties of
  one clip without conflict — but simultaneous moves of the *same* clip
  resolve last-writer-wins (acceptable for v1).
