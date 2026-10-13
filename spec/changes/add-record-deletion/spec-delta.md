## Spec Delta: add-record-deletion (TASK-223)

- New owner-only mutation surface: DELETE /api/records/{id} (identity gate 1001 / ownership 403 code 1002), in-service transactional hard-delete cascade: sport_record + track_point + track_point_archive + like rows; best-effort Redis like-key cleanup (reconciliation-tolerant).
- Deleted ids read as 404 on all existing surfaces (points/verify-result/like/feed/my); assertCanView and all existing gates unchanged.
- Leaderboard: live-query propagation expected; own-persisted aggregate → registered deviation + known limitation (contribution refresh out of scope).
- web: my-records delete button + confirm; no schema change, no new status value, no MQ.
