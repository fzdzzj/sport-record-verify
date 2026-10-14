## Proposal: add-sport-goal (TASK-225, P5)

### Background
Streak (TASK-220) + stats panel (TASK-211) + feed/deletion/filter (222-224) complete the record UX. Missing piece for habit-building: weekly/monthly goals with progress.

### Plan (MEDIUM: goal table + pull-on-read progress + web goals card)
- Schema: append `sport_goal` to sql/02-record-db.sql (idempotent create, scratch two-run proof): id PK / user_id / period_type TINYINT (0 weekly, 1 monthly) / metric TINYINT (0 distance-km, 1 session-count) / target_value DECIMAL(10,2) / created_at; UNIQUE(user_id, period_type, metric) — one active goal per dimension, upsert semantics.
- Backend (record-service, co-located with records — no cross-service): GoalController `/api/records/my/goals` GET (list + progress) / POST (upsert) / DELETE `/{id}`; identity gate same as /my (missing header 1001; auth off → userId param missing 400); ownership on delete (403 code 1002). GoalService computes progress pull-on-read: current period window (ISO week Mon–Sun / calendar month), SUM(distance) or COUNT of own PASSED records (archived branch included via existing union semantics if applicable), progress = min(achieved/target, display on web), no denormalization, no events.
- Validation: period_type ∈{0,1}, metric ∈{0,1}, target_value > 0 (400 on violation); progress endpoint returns achieved + target + percent (integer 0-100, cap display at 100).
- web: stats area goals card — per-goal a-progress bar + period label + create/edit dialog (period/metric/target) + delete confirm; client.ts + tests.
- Zero api module, zero MQ, zero new services. FULL offline gate (sql touched → conservative policy reading).

### Non-Goals
- Historical goal archives/analytics, streak-goal linkage, reminders/notifications, goal sharing, leaderboards on goal completion, custom period (biweekly/yearly), multiple concurrent goals per dimension.
