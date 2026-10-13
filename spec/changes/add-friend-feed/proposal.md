## Proposal: add-friend-feed (TASK-222, P4)

### Background
The social chain is one step short of closed: friendship (add-friend-system), per-record visibility (TASK-221 PUBLIC/FRIENDS/PRIVATE), likes (TASK-188) and the notification center are all in place, yet there is no surface where a user can SEE what friends did. TASK-221's visibility gate is a read-path guard with no consuming discovery surface; the feed is its first real consumer and the missing last link (add friend → see activity → like → notified).

### Plan (FULL tier: cross-service aggregation read path, zero schema)
- Pull-on-read feed (no fan-out table, no MQ, no new index): GET /api/records/feed?page=&size= ownership-as-identity (same resolveUserId gate as /my).
- Semantics: auth on → own records (any visibility, owner sees own) + friends' records with visibility IN (PUBLIC, FRIENDS), createdAt DESC, page/size capped like /my. Strangers' PUBLIC records are viewable on detail but NEVER pushed into feed (feed ≠ explore).
- Degrade mode (auth.enabled=false, caller=null) → PUBLIC-only global timeline (consistent with assertCanView caller=null pass semantics; degrade-zero-perturbation precedent — feed is new, PUBLIC-only is the natural null-caller view).
- Cross-service: user-service GET /internal/friends/ids?userId= (normalized friendship both-directions restore) + batch author-brief lookup for nicknames (reuse existing internal user surface if recon finds one, else add /internal/users/briefs). UserApi gains listFriendIds / listUserBriefs; UserApiFallback soft-degrades to empty list — feed then shows own records only (fail-safe, friends-leaderboard empty-board posture), nickname falls back to userId string.
- record-service: SportRecordService.pageFeed(caller, page, size) — one IN-clause query (user_id IN (self ∪ friendIds)), visibility pre-filter equivalent to assertCanView semantics (equivalence argument registered as discriminant; detail/points/verify-result/like gates UNCHANGED). FeedItemDTO = RecordSummaryDTO fields + authorUserId + authorNickname.
- web: /feed page (card = author nickname + visibility badge reuse + distance/duration/type + relative time), nav entry, pagination, client.ts types; tests per house style.
- Test-isolation discipline (TASK-221 D7 lesson, CI-red incident): any pure-Mockito unit test resolving MP wrapper SQL must init TableInfo via the house TableInfoHelper.initTableInfo idiom; new test classes must pass isolated single-class runs (-Dtest=XxxTest) — online CI ordering must not matter.

### Non-Goals
- No fan-out/MQ/event, no feed notifications, no inline like/comment actions on cards (link to detail page instead), no explore/discovery page, no visibility editing, no new index (user_id index = TASK-211 known limitation, deferred with its own ruling), no leaderboard linkage, no ci.yml changes.
