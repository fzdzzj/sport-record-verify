## Proposal: add-test-backfill (TASK-214)

### Background
E4 recon (2026-10-13) overturned the original premise: NotificationController preference endpoints already have 4 tests (TASK-187) and MapMatchServiceTest has 10 tests including golden-value discriminants (TASK-133). The real remaining gaps:

- MapMatchRequestDTO contract constraints (points @NotEmpty, @Size max=20000, @Valid-cascaded seq/lat/lng bounds on MapMatchPointDTO) have ZERO tests — the 20000-point cap and coordinate bounds are paper promises.
- MatchProperties defaults (maxSampledPoints=200, prefilterChunkPoints=50, snapThresholdMeters=25, searchRadiusMeters=300) are implicitly load-bearing for MapMatchServiceTest golden values (300/85000 radius conversion, 25m snap threshold); default drift would silently invalidate golden discriminants.
- MapMatchController has zero tests (Result wrapper + delegation untested).
- NotificationController preference endpoints lack auth-enabled positive paths (only missing-header 401 tested) and claimed-mismatch 403 (only page endpoint tested).

### Plan (MEDIUM tier: pure test additions, zero production code)
- New MapMatchRequestValidationTest (jakarta Validator, AuthRequestDtoValidationTest paradigm): empty points, 20000/20001 size boundary, cascaded point violations (seq null / lat out of range / lng null), valid pass.
- New MapMatchControllerTest (pure Mockito, manual construction): delegation + Result.success wrapper.
- New MatchPropertiesTest: four defaults assertion + Binder prefix override (DataSourcePasswordEnvBindingTest paradigm, MutablePropertySources without system sources).
- NotificationControllerTest additions: auth-enabled positive paths (X-User-Id → getPreferences/updatePreferences) + claimed-mismatch 403 on preferences.

### Non-Goals
- Zero production/config/script changes; no MockMvc/WebMvcTest (validation semantics carried by Validator-level tests); no new deps; no coverage of the already-covered service-layer algorithms.
