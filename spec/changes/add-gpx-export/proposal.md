## Proposal: add-gpx-export (TASK-218, E13)

### Background
Record-detail page (TASK-213) already loads track points (size=500) client-side. GPX export is the standard "take your data with you" feature for any fitness/track app and closes the track-viewing value loop (view -> own). Zero backend needed: points are already on the client.

### Plan (MEDIUM tier: pure frontend + unit tests, zero backend / zero deps / zero CI changes)
- New pure module web/src/utils/gpx.ts: generateGpx(points, opts) -> GPX 1.1 XML string (trk/trkseg/trkpt with lat/lon 6-decimal, `<time>` ISO8601 from ts epoch ms, XML-escaped name; empty input throws).
- New unit suite web/src/utils/__tests__/gpx.test.ts on the TASK-215 vitest baseline (single/multi point counts, coordinate precision, ISO8601 time, XML escaping of name, empty-throws).
- record-detail.page.vue: "导出 GPX" button visible only when points.length > 0; click -> generateGpx -> Blob (application/gpx+xml) -> object URL -> a[download=record-{id}.gpx] -> revoke.
- web/README.md: one short feature registration.

### Non-Goals
- No elevation `<ele>` (source DTO has none), no speed extensions (GPX 1.1 core keeps it simple), no backend endpoint, no new npm deps, no new routes/pages (typed-router untouched), no real-browser download click-through (type-check + build + pure-function tests carry verification; registered as uncovered, same posture as TASK-213).
