## Proposal: add-notification-preference-ui (TASK-210)

### Background
Backend preference endpoints exist since TASK-187 (GET/PUT /api/notifications/preferences, batch upsert, view list with type/enabled/updatedAt) but web has no UI entry.

### Plan (MEDIUM tier: pure frontend, zero backend change)
- settings.page.vue (built in TASK-208) gains a notification-preference section: dynamic switch list rendered from GET response (no hardcoded type list - render what the backend returns), per-toggle PUT with single-item preferences array, refresh from PUT response on success.
- client.ts: getNotificationPreferences() / updateNotificationPreferences(items) aligned to the verified contract.
- No new page (settings is the natural home), typed-router.d.ts unchanged, README notification section gains one UI-entry line (optional).

### Non-Goals
- Zero backend/service/mapper/DTO changes; no new web page; no new dependencies.

