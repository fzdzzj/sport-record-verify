## Spec Delta: add-record-visibility (TASK-221)

- New read-path authorization semantics on record detail surfaces: GET /{id}/points, GET /{id}/verify-result (gains caller), like/unlike/getLike honor per-record visibility (PUBLIC/FRIENDS/PRIVATE); appeal and /my* unchanged (owner-only / ownership-as-identity).
- Schema: sport_record gains `visibility TINYINT NOT NULL DEFAULT 0` (dual-write: migrations ALTER for existing volumes + 02-record-db.sql CREATE TABLE for fresh installs). New api enum RecordVisibility; RecordSubmitDTO/RecordSummaryDTO gain Integer visibility.
- Cross-service: UserApi.areFriends + user-service /internal/friends/exists (normalized friendship selectCount), fallback false (fail-safe privacy).
- Leaderboard credit semantics intentionally unchanged (user adjudication 2026-10-13); visibility×leaderboard linkage deferred.
