## Spec Delta: add-sport-goal (TASK-225)

- New sport_goal table (02-record-db.sql idempotent append; UNIQUE user_id+period_type+metric, upsert).
- /api/records/my/goals GET/POST/DELETE (+/my/goals/{id} delete, ownership 403): identity gate same as /my; pull-on-read progress over own PASSED records in current week/month window.
- web goals card with progress bars + create/edit/delete; zero api/MQ/cross-service; FULL offline gate (sql touched).
