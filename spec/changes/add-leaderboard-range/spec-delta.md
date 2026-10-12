## Spec Delta: add-leaderboard-range (TASK-209)

- New read-only endpoint GET /api/leaderboard/range (no userId identity involved - public aggregate like /daily).
- Aggregation semantics: ACTIVE contributions by settled_at within [start, end+1d); weekly/monthly are front-end compositions of natural periods (ISO week Monday / calendar month).
- Error contract: 400 for missing/invalid start, start>end, span>366d.

