## Proposal: add-my-records-filter (TASK-224, P5)

### Background
My-records list has pagination (page/size clamp) and per-row delete (TASK-223), but no filtering. With record volume growing, type/status/date filters are the natural completion of the list UX.

### Plan (MEDIUM-small: optional filter params on existing /my read path)
- Backend: GET /api/records/my gains optional filters — sportType (integer enum), status (verification state), date range (startDate/endDate on record startTime, both optional, start<=end validated). All optional; absent = unfiltered (backward compatible). Illegal enum/out-of-range → 400 (strict, along @Min/@Max precedent). Params bound via @RequestParam / record-service-internal binding object — **api module untouched** (preregistered; if recon forces api changes, escalate to full offline gate per tiered policy).
- MP wrapper: null-safe conditional composition (sportType eq / status eq / startTime ge+le), composed with existing user_id + orderByDesc(createdAt) + page clamp. Existing semantics zero-change when no filters.
- Degrade: auth off → userId param (same as /my today); missing → 400. Identity gate untouched.
- web: my-records section filter bar (type select + status select + date range), resetting page=1 on any filter change; client.ts getMyRecords extended params; tests.
- Zero schema, zero index (filters are on-list UX, volume per user small), zero new endpoints.

### Non-Goals
- Sorting options, server-side full-text search, filter persistence (localStorage), admin-side filtering, cross-user filtering, API module DTO changes.
