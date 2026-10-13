## Proposal: add-pace-analysis (TASK-219, P2)

### Background
Record-detail page (TASK-213/218) already loads track points (size=500) with per-point speed (m/s, optional non-negative). Pace trend is a standard fitness-app analysis view (Strava/Keep par). Pure frontend: data already on the client.

### Plan (MEDIUM tier: pure frontend + unit tests, zero backend / zero deps / zero CI changes)
- New pure module web/src/utils/pace.ts: computePaceSeries(points) -> [{distKm, paceSec, speed}] (haversine cumulative distance; pace = 1000/speed sec-per-km; speed<=0 -> paceSec null = pause point) + formatPace(sec) -> "m'ss\"" + null-safe.
- New unit suite web/src/utils/__tests__/pace.test.ts on the TASK-215 vitest baseline (distance math, pace conversion, zero-speed null, format boundaries, empty array).
- record-detail.page.vue: pace trend section under the track SVG — line chart X=cumulative km, Y=paceSec inverted (faster on top, pace-chart convention), pause points (paceSec null or > 3600) break the line, hover shows distance+pace+time.
- web/README.md: one short feature registration.

### Non-Goals
- No backend endpoint (uses already-loaded points), no new npm deps, no new routes (typed-router untouched), no real-browser interaction testing (type-check + build + pure-function tests carry verification, TASK-213/218 posture), >500-point partial data follows the existing in-page known limitation note.
