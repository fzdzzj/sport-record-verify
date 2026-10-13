# TASK-215: web test baseline (vitest + token/client anchor tests)

Tier: MEDIUM (test infrastructure + pure-logic tests, zero production logic changes). Review: guidance-side verification only, CI as external final gate (web job incl. new test step). Escalation: assertion failure or contradiction -> FULL.

## S0 hard constraints
- ZERO changes to: production web src logic (any .vue page / main.ts / notificationWs.ts / useNotificationBell.ts must not be edited to make tests pass), all six backend services, api module, common, parent pom, sql, mvn-verify.sh, mailbox-contract.sh, compose, web runtime deps (dependencies section of package.json).
- ci.yml: ONLY a single new step inside the web job (after "Type check", before "Build"). Any other ci.yml change is a red-line violation.
- No @vue/test-utils, no Playwright, no axios-mock-adapter, no msw — devDeps additions limited to vitest + happy-dom (vitest ecosystem deps pulled transitively by pnpm are fine).

## S1 verified context
- web stack: Vue 3.5 + Vite 7 + TS 5.6 + vue-router 4 (unplugin-vue-router) + axios + ant-design-vue + tanstack vue-query; no pinia, no stores. Zero test deps today.
- token.ts exports: TOKEN_KEYS {ACCESS:'access_token', ROLE:'user_role'}, getAccessToken, getRole, setTokens(accessToken, role) [2-arg since TASK-205], clearTokens (also removes legacy 'refresh_token' key), hasAccessToken, setAuthFailHandler, triggerAuthFail (clearTokens + handler + exception swallowing).
- client.ts: module-level axios instance; request interceptor sets Authorization: Bearer only when token present (never X-User-Id/X-Role); response interceptor: body.code===0 -> passthrough body, code!=0 -> throws Error with .code/.data/.status; HTTP 401 -> single refresh (skip for /api/auth/login|refresh|register URLs) via api.post('/api/auth/refresh', {}, {withCredentials:true}) then retry original once with new token; refresh failure -> triggerAuthFail + throw.
- CI web job (ci.yml:114-148): checkout, Node 22, pnpm 10.25.0, frozen-lockfile install, Type check, Build, typed-router diff check. Insert point: after Type check, before Build.
- vite.config.ts: plugins VueRouter/tailwindcss/vue, @ -> src alias (must be shared by tests — client.ts imports '@/utils/token').
- Local Windows esbuild trap: TMP/TEMP must be redirected to repo .trae/tmp before pnpm test/build (TASK-213 D3 lesson).

## S2 preregistered design
- package.json: scripts.test = "vitest run"; devDependencies + vitest (^3.x latest stable compatible with vite 7) + happy-dom. pnpm install to update pnpm-lock.yaml (frozen-lockfile CI compatibility).
- vite.config.ts: add test field — { name: 'web', environment: 'happy-dom' } via defineConfig from 'vitest/config' reference (or equivalent official pattern); explicit imports in tests (globals: false).
- token.test.ts (web/src/utils/__tests__/): R2a TOKEN_KEYS constants exact; R2b setTokens writes both keys + getAccessToken/getRole/hasAccessToken roundtrip; R2c clearTokens removes ACCESS+ROLE and legacy 'refresh_token' key even if present; R2d triggerAuthFail without handler only clears (no throw); R2e registered handler invoked; R2f handler throwing is swallowed (no propagation).
- client.test.ts (web/src/api/__tests__/, axios adapter injection pattern — replace api.defaults.adapter with a vi.fn): R3a request without token sends no Authorization header; R3b with token sends Bearer; R3c code=0 response resolves to body; R3d code!=0 rejects with .code/.message; R3e HTTP 401 on /api/auth/* URL rejects immediately (no refresh call — adapter call count == 1); R3f HTTP 401 on business URL triggers refresh POST with withCredentials:true, then retries original once with new Bearer (adapter receives second call with updated header); R3g refresh POST itself 401s -> triggerAuthFail invoked + rejects.
- CI: web job step `- name: Web unit tests` / `run: pnpm --dir web run test` between Type check and Build.
- web/README.md: one section registering test baseline (how to run, scope, deferred component/E2E rationale).

## S2.5 discriminants
- R1: pnpm test rc=0 with both suites collected; pnpm run type-check rc=0; pnpm run build success; git diff --exit-code -- web/src/typed-router.d.ts clean.
- R2a-f / R3a-g: as preregistered in S2, red-green per test.
- G1: offline 698 = 52/50/165/166/159/80/26 per-digit unchanged (web not in offline baseline).
- G2: production web src diff empty (only test files new; package.json/vite.config.ts/ci.yml/README as whitelisted).
- G3: package.json dependencies section zero-diff; ci.yml diff = exactly one added step inside web job.
- Word-check/token/diff-check/line-endings per standard MEDIUM gates.

## S3 stop conditions
1. Any production web src file appears to need modification to make tests pass (tests must accommodate current behavior; if current behavior contradicts preregistered expectations -> stop and report as suspected defect).
2. vitest/vite-7 version conflict resolvable only by downgrading vite or touching runtime deps.
3. ci.yml change grows beyond the single web-job step.
4. pnpm-lock resolution failure or frozen-lockfile incompatibility.

## S4 whitelist (target C-01 = exactly 7 files)
web/package.json, web/pnpm-lock.yaml, web/vite.config.ts,
web/src/utils/__tests__/token.test.ts (new), web/src/api/__tests__/client.test.ts (new),
.github/workflows/ci.yml (web job single step only), web/README.md.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 698 = 52/50/165/166/159/80/26 (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- web local: pnpm test rc=0 (TMP redirect), type-check rc=0, build success, typed-router diff clean.
- contract gate dual-state: --open TASK-215 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 two-point (pnpm-lock new lines must not contain credential literals); word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; new files pure LF + trailing newline.
- C-01 test(web): ... (TASK-215); C-02 docs(mailbox): ... (TASK-215) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = bc448db (test(web), 7 files, +634/−2); C-02 = self-referencing hash (precedent)
- web test results: vitest 3.2.7, 2 suites / 13 tests (token 6 + client 7) all green rc=0; type-check rc=0; build success (3283 modules); typed-router diff clean.
- offline (web not in offline baseline): 698 = 52/50/165/166/159/80/26 per-digit unchanged, BUILD SUCCESS rc=0.
- token 29 items two-point: 2030 (open) / 2030 (close); changed-set zero credential literals (pnpm-lock new lines carry only dep specifiers/integrity + js-tokens package name).
- discriminant results: R1 green; R2a-f green; R3a-g green (R3f asserts refresh withCredentials:true + retried original carries new Bearer); G1/G2/G3 green.
- deviations: D1 C-02 self-referencing hash (precedent); D2 vite.config.ts single stray CR introduced by editor was normalized to pure LF (baseline LF); D3 new test files' trailing LF re-added; D4 web/README verification section still says "四条命令" (left untouched to honor "登记一节" scope) though CI now runs a fifth (test) command — the new section documents the added step.
