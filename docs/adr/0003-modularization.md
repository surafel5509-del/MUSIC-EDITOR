# ADR 0003: Modular architecture with convention plugins

**Status:** accepted

## Decision

Multi-module Gradle build: `app`, `core:*` (8 modules), `audio`, `midi`,
and 10 `feature:*` modules. Shared build configuration lives in
`build-logic` convention plugins (`studioone.android.*`).

## Rationale

- Enforces dependency direction at the build level (features can't import
  each other or reach into data internals).
- Parallelizes compilation for a senior-sized team and keeps IDE indexing
  fast.
- Convention plugins keep ~20 build files down to a few lines each and make
  SDK/compile options single-sourced.

## Consequences

- Cross-feature coordination goes through `core:domain` holders/events,
  which must stay dependency-free.
- New modules must adopt a convention plugin rather than ad-hoc config.
