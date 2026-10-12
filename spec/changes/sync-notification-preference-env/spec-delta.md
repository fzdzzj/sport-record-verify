## Spec Delta: sync-notification-preference-env (TASK-207)

- No contract/spec changes. Environment-only: scratch user_db gains notification_preference (and any other missing) tables via idempotent replay of the existing 01-user-db.sql.
- TASK-206 D2 flips from open env gap to closed (reverified PASS).

