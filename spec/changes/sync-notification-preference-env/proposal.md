## Proposal: sync-notification-preference-env (TASK-207)

### Background
TASK-206 V1c-b: B accept real-friend request returned 500 after ownership assertion passed. Root cause: scratch user_db predates the notification_preference table addition to sql/01-user-db.sql; no versioned migration exists (Flyway rejected). The CREATE TABLE IF NOT EXISTS statement already exists at 01-user-db.sql:69. Zero product-code defect.

### Plan (MEDIUM tier: env-only sync + reverification, no production code)
- V1: idempotent replay of sql/01-user-db.sql against scratch user_db (pre/post SHOW TABLES + SHOW CREATE TABLE evidence for existing tables proving zero structural drift).
- V2: start gateway+user jars, reverify V1c-b (B accept -> 200), closing TASK-206 D2.

### Non-Goals
- No sql/ script edits, no Flyway/Testcontainers, no production code/config/CI/compose changes, no shared-container stop/restart.
- Operationally registering the replay convention (env sync via idempotent script replay) is a docs note only.

