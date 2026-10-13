## Proposal: add-record-deletion (TASK-223, P5)

### Background
The user main chain is submit + view only — no delete/withdraw anywhere (site-wide, E14-class absence). Besides PRIVATE, deletion is the last data-autonomy gap: users need a "regret button" for mis-uploads and privacy take-back (GDPR-ish right to erasure). Cascade surfaces are wide, hence FULL tier with preregistered adjudication.

### Plan (FULL tier: owner-only hard delete with in-service cascade)
- DELETE /api/records/{id} — ownership-as-identity gate (resolveUserId; missing header 1001), owner check (userId==caller else 403 code 1002), then single-transaction hard delete cascade within record-service: sport_record + track_point + track_point_archive + like DB rows.
- Like storage cleanup: delete DB like rows in-transaction; best-effort Redis cleanup (member set + count keys) outside transaction, tolerated by existing reconciliation (rebuild path already handles dangling); no new MQ/event.
- Post-delete reads: pagePoints/verify-result/like trio/feed/my on deleted id → 404 (requireRecord miss); visibility gate untouched (assertCanView unchanged).
- Leaderboard contribution: S1 recon decides — if leaderboard aggregates live via record-service query, distance vanishes automatically; if it holds its own persisted aggregates, register as deviation and keep contribution refresh OUT of scope (documented known limitation; no cross-service compensating write — that is its own future topic). If recon reveals a delete-compensation write path is REQUIRED for correctness, S3 stop.
- web: my-records list per-row delete button + confirm dialog; feed cards get no delete (non-owner). client.ts + tests.
- No soft-delete status (status stays verification-state-only), no tombstone/audit trail (Non-Goal, registered), no notification retro-cleanup (like notifications are historical facts, kept), no admin undelete, no batch delete.

### Non-Goals
- Soft delete / status DELETED, tombstone, undelete, admin hard-delete, batch delete, leaderboard compensating events, notification history rewriting, recycle bin UI.
