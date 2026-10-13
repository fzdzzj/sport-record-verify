## Spec Delta: add-streak (TASK-220)

- New read-only aggregate endpoint GET /api/records/my/streak (X-User-Id ownership, same posture as GET /my) returning { current, longest }.
- New api-module DTO MyStreakDTO; new pure calculator in record-service. No schema change, no state machine change.
