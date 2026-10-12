## Proposal: add-change-password (TASK-208)

### Background
AuthController has register/login/refresh/logout only; internal face has ban/unban/grant/revoke. No self-service password change exists — the last missing piece of the auth domain. Combined with TASK-205 cookie hardening, changing password should also revoke ALL live refresh tokens of the user (stolen-session window collapses to access-token TTL 15min).

### Plan (FULL tier: security semantics)
- POST /api/auth/change-password (X-User-Id from gateway, auth.enabled=true guard per pagePoints paradigm).
- AuthService.changePassword(userId, oldPassword, newPassword): user lookup + status=0 -> 401; BCrypt matches(old) fail -> 1001 (same as login, no user-existence leak); same-as-old -> 400 preregistered; else encode new hash, update DB, then bulk-delete Redis keys matching auth:refresh:{userId}:* (full revocation; same-construct as existing single-key logout delete).
- New ChangePasswordRequestDTO in user-service local dto package (api module untouched; pattern per RegisterRequestDTO).
- web: settings.page.vue (old/new/confirm fields) + entry from index page; client.ts api call; typed-router regenerated.

### Non-Goals
- No email/SMS verification flow (no channel infra); no password-reset-by-admin (separate governance concern); no api module change; no sql change.

