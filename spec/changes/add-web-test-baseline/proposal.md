## Proposal: add-web-test-baseline (TASK-215)

### Background
Web frontend has zero test baseline (no test runner, no test deps; changes verified only by type-check + build + CI web job). High-value untested surfaces: token.ts (TASK-205 credential posture: setTokens 2-arg, legacy refresh_token key cleanup, auth-fail handler) and client.ts interceptors (TASK-205 security semantics: Bearer injection, code!=0 throw, 401 single-refresh retry with withCredentials, auth-endpoint bypass).

### Plan (MEDIUM tier: test infrastructure + pure-logic tests, zero production logic changes)
- Tooling decision (preregistered): Vitest 3.x + happy-dom — shares vite.config.ts alias chain, pure-node fast, CI-second-level cost. NOT introducing @vue/test-utils (no lightweight mountable component exists — pages are router/axios/query-heavy; component tests deferred to a later task). NOT introducing Playwright/E2E (overlaps TASK-206 HTTP-level real-chain verification paradigm at much higher CI cost).
- Add scripts.test = vitest run; devDeps vitest + happy-dom; vite.config.ts test field (environment happy-dom, globals false — explicit imports).
- New token.test.ts (~6-8 tests): TOKEN_KEYS constants, get/set/has, clearTokens removes legacy refresh_token key (TASK-205 behavior lock), triggerAuthFail handler registration/invocation/exception swallowing.
- New client.test.ts (~5-7 tests via axios adapter injection — no axios-mock-adapter dep): Bearer header with/without token, code=0 passthrough, code!=0 throws coded error, 401 on auth endpoints bypasses refresh, 401 elsewhere triggers single refresh retry (withCredentials asserted) and retries original with new token, refresh failure triggers auth-fail.
- CI web job: insert `pnpm --dir web run test` step after Type check (before Build).

### Non-Goals
- Zero changes to production web src logic, zero backend changes, no component-mount tests, no E2E, no new runtime deps (test deps only).
