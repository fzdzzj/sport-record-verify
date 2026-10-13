## Spec Delta: add-friend-feed (TASK-222)

- New read surface: GET /api/records/feed (ownership-as-identity) — own records (any visibility) + friends' PUBLIC/FRIENDS records, createdAt DESC, paged; strangers never appear even for PUBLIC. Degrade mode (caller=null) → PUBLIC-only global timeline.
- Cross-service reads: user-service /internal/friends/ids (normalized both-direction restore) + batch author briefs (reuse existing surface if present); UserApi listFriendIds/listUserBriefs with empty-list fallbacks (feed degrades to own-only, nickname falls back to userId).
- No schema change, no new index, no MQ/event, no mutation of existing visibility gates (feed pre-filter proven equivalent to assertCanView semantics).
- New web /feed page + nav entry; visibility badge component reused.
