## Spec Delta: add-my-records-filter (TASK-224)

- GET /api/records/my + optional sportType/status/startDate/endDate filters (null = unfiltered, backward compatible; illegal → 400; start>end → 400).
- MP wrapper null-safe composition onto existing user_id + createdAt DESC + clamp; identity gate and existing pagination semantics unchanged.
- web filter bar resets page on change; zero schema/index/api-module/new-endpoint.
