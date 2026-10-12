## Proposal: add-track-view (TASK-213)

### Background
Track data surface is complete (GET /api/records/{id}/points, TASK-204 ownership-hardened; TrackPointDTO has lat/lng/seq/ts/speed). No UI exists to view a record's track. Web has no map/chart libraries.

### Plan (MEDIUM tier: pure frontend)
- New record-detail page: record summary header + SVG track rendering. lat/lng min-max normalized to viewBox, polyline + start/end markers + per-point hover (seq/speed/ts).
- Entry: recent-records rows in index dashboard link to the page; verdict page link optional.
- Point pagination: single capped fetch (size=500, same convention as my-records); >500 points = partial-track known limitation.
- Zero new dependencies (pure SVG). Real map (Leaflet+OSM tiles) deferred: needs external-tile-source decision.

### Non-Goals
- Zero backend changes; zero package.json changes; no map libraries.

