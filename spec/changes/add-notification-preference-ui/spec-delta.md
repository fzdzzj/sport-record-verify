## Spec Delta: add-notification-preference-ui (TASK-210)

- UI-only: settings page notification-preference section consuming existing GET/PUT /api/notifications/preferences.
- Interaction contract: toggle -> PUT single-item batch -> success feedback -> state from response; load failure shown non-fatally.

