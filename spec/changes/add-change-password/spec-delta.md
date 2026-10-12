## Spec Delta: add-change-password (TASK-208)

- New external endpoint POST /api/auth/change-password (auth.enabled=true path; X-User-Id ownership implicit = self).
- Semantics: verify old (BCrypt) -> update hash -> revoke ALL live refresh keys (auth:refresh:{userId}:*). Old refresh becomes 401; current access remains valid up to 15min (aligned TASK-202 timing doctrine).
- Error contract: 1001 wrong-old/banned/not-found (no existence leak), 400 same-as-old / malformed new password.

