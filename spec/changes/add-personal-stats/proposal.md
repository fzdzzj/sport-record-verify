## Proposal: add-personal-stats (TASK-211)

### Background
RecordController has no my-records list endpoint (only submit/verify-result/appeal/points); web index page is a static welcome console. Personal stats dashboard needs a data surface first.

### Plan (FULL tier: new query endpoint + frontend dashboard)
- R1 backend: GET /api/records/my?page&size -> paged own records (X-User-Id ownership when auth.enabled=true, missing -> 401; disabled -> explicit userId required, missing -> 400). New RecordSummaryDTO in api module (additions allowed, existing files untouched). Mapper page-by-userId (reuse existing query if present; no index additions - red-lined).
- R2 frontend: index.page.vue becomes personal dashboard - welcome card kept + stat cards (total distance / record count / pass rate) + last-30-days daily distance trend (pure CSS/SVG mini bars, zero new dependencies) + recent records list. Stats aggregate a single size=500 fetch (known limitation beyond 500 records).

### Non-Goals
- No changes to existing api module files, other services, sql, indexes, write paths, MQ, settlement.

