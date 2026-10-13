## Proposal: add-streak (TASK-220, P1)

### Background
Streak (consecutive active days) is the single highest-stickiness lightweight feature of real fitness apps (Keep/Strava). Record data already exists server-side; this adds a small read-only aggregate endpoint + dashboard card.

### Plan (MEDIUM tier: standard flow — backend read-only endpoint + frontend card)
- Valid-day semantics (preregistered): a day counts when the user has >=1 record with terminal-PASSED status (PASSED or RE_PASSED) — same bar as contribution/leaderboard credit, anti-inflation by design; multiple records per day count once (distinct date).
- Current streak: count back from today if today has a valid day, else from yesterday (streak stays alive until the day ends); zero if neither. Longest streak: historical max run.
- New pure calculator class (static, unit-testable) + service aggregate (single-column projection query, no new SQL form) + read-only endpoint GET /api/records/my/streak (same X-User-Id ownership posture as GET /my) + dashboard "连续打卡" card (current / longest).
- No new table, no index change, no state machine / MQ / batch change.

### Non-Goals
- No per-sport-type streak, no streak notifications/badges (later P6), no timezone gymnastics (server-local dates, Asia/Shanghai), no frozen-streak repair tokens (consumer-app gimmick).
