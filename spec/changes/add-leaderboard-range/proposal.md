## Proposal: add-leaderboard-range (TASK-209)

### Background
Leaderboard has overall/friend (Redis ZSet) and /daily (cumulative snapshot table leaderboard_daily_summary - cumulative-to-date, NOT daily delta). Weekly/monthly views need time-range aggregation.

### Plan (FULL tier: core query logic)
- New GET /api/leaderboard/range?start=YYYY-MM-DD&end=YYYY-MM-DD&size=50: single-query aggregation over leaderboard_contribution WHERE status=ACTIVE AND settled_at in [start 00:00, end+1d 00:00), GROUP BY user_id SUM(distance) DESC topN (cap 500, default 50 - aligned daily doctrine).
- Authority source = contribution table (same table the settlement task reconciles from); zero write-path / ZSet / index / sql changes. settled_at has no dedicated index - registered as known limitation for a future perf topic (index changes are globally red-lined).
- Validation: start required; end defaults to today; start>end -> 400; span > 366 days -> 400.
- web leaderboard.page.vue: day/week/month tabs - week = ISO Monday of current week, month = 1st of current month (natural-period semantics, in-progress period visible up to today).

### Non-Goals
- No new table/view/sql; no ZSet periodic keys; no write-path (applyVerified/revert/settlement) changes; no index additions; no friend-filter on range (friends via existing type=friend overall only).

