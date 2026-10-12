## Spec Delta: add-personal-stats (TASK-211)

- New read-only endpoint GET /api/records/my (ownership-scoped pagination).
- Identity: auth.enabled=true -> X-User-Id required (401 on missing); false -> explicit userId query param required (400 on missing). No cross-user access by construction.
- Dashboard aggregates client-side from a single capped fetch (size<=500).

