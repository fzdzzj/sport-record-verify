# TASK-217: Image log dir fix (E18)

Tier: MEDIUM (six Dockerfiles only + live verification; zero Java, zero compose, zero CI). Review: guidance-side verification only, CI as external final gate (image build gate covers Dockerfile changes automatically). Escalation: bare-boot failure persists or fix spills outside Dockerfiles -> FULL.

## S0 hard constraints
- ZERO changes to: docker-compose*.yml, all six services src/main/resources (logback-spring.xml, application.yml), pom files, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, scripts/, web, prometheus/, grafana/, spec/changes/archive/**.
- Whitelist only (S4).

## S1 verified context
- Defect (TASK-216 live evidence): all six containers fail bare bootstrapping — logback FileNotFoundException on relative `logs/<svc>.log` (logging.file.name in application.yml; resolved to /app/logs under WORKDIR /app); gateway additionally fails on Sentinel JUL log dir (${user.home}/logs/csp — system user created without -m has no home). Root cause: F20 Dockerfiles create non-root `app` user but never mkdir /app/logs (or sentinel dir).
- Dockerfile runtime stage (six, same shape): FROM eclipse-temurin:21-jre; WORKDIR /app; `RUN groupadd -r app && useradd -r -g app app`; COPY jar; USER app; ENTRYPOINT ["java", "-jar", "app.jar"].
- Sentinel log dir system property (official): `csp.sentinel.log.dir` (default ${user.home}/logs/csp).
- TASK-216 workaround was a tmpfs /app/logs scaffold (gitignored override, deleted after use) — that scaffold MUST NOT be used in this task's verification; the whole point is zero-scaffold bare bootstrapping.
- Local env quirks from TASK-216 (Redis 6379 native service, MySQL volume password drift) may recur — same local, non-repo remedies acceptable if needed; record as deviations.
- offline baseline: 698 = 52/50/165/166/159/80/26. CI build job builds all six images (Dockerfile changes validated there).

## S2 preregistered design
- Five service Dockerfiles (user/record/verify/leaderboard/mapmatch): extend the existing user-creation RUN line to `RUN groupadd -r app && useradd -r -g app app && mkdir -p /app/logs && chown app:app /app/logs`.
- gateway-service Dockerfile: `RUN groupadd -r app && useradd -r -g app app && mkdir -p /app/logs/sentinel && chown -R app:app /app/logs`; ENTRYPOINT becomes ["java", "-Dcsp.sentinel.log.dir=/app/logs/sentinel", "-jar", "app.jar"].
- Comment lines in Dockerfiles updated where they mention the user/run stage (one short line noting writable log dir for TASK-198 logback + sentinel).

## S2.5 discriminants
- R1: five Dockerfile diffs = exactly one RUN line extended (+ mkdir/chown) each; no other lines touched.
- R2: gateway Dockerfile diff = one RUN line extended + ENTRYPOINT one line (+optional comment); sentinel dir under /app/logs.
- R3: live zero-scaffold verification — full stack `docker compose -f docker-compose.yml -f docker-compose.services.yml up -d --build`: (a) six service containers healthy (docker compose ps); (b) Prometheus /api/v1/targets six pools health=="up"; (c) /api/v1/query?query=up six series value 1; (d) Grafana /login 200; (e) in-container evidence: head -c of /app/logs/<svc>.log shows JSON log line content for at least user-service and gateway; gateway /app/logs/sentinel dir exists (ls). NO tmpfs/override scaffold of any kind.
- G1: offline 698 per-digit unchanged.
- G2: changed set == exactly S4 whitelist (6 Dockerfiles + ledger); compose/services resources/prometheus/grafana/CI zero diff.
- G3: standard MEDIUM gates (contract dual-state, token 2030 two-point, word-check ZERO_HIT + probe, diff --check, line endings preserved per baseline).

## S3 stop conditions
1. Zero-scaffold bare bootstrapping still fails on any service — report with container log evidence, do not reintroduce tmpfs scaffold to force green.
2. Fix appears to require touching compose files / application.yml / logback-spring.xml / adding JVM memory or resource changes — outside whitelist, stop.
3. Local resource insufficient for full stack — degraded minimal set (middleware + gateway + user + mapmatch) acceptable for (a)/(e); Prometheus targets assertion (b)/(c) per started set.
4. CI image build gate turns red on the Dockerfile change (after local pass) — report run link, no blind retries.

## S4 whitelist (target C-01 = exactly 6 files)
gateway-service/Dockerfile, user-service/Dockerfile, record-service/Dockerfile, verify-service/Dockerfile, leaderboard-service/Dockerfile, mapmatch-service/Dockerfile.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 698 = 52/50/165/166/159/80/26 (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- contract gate dual-state: --open TASK-217 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 two-point; word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; changed files preserve baseline line endings.
- C-01 fix(build): ... (TASK-217); C-02 docs(mailbox): ... (TASK-217) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = `4ce4df8` (fix(build): 镜像可写日志目录与非root引导修复（TASK-217）, exactly 6 Dockerfiles, +7/-7); C-02 = self-referencing hash (precedent).
- live verification evidence / offline / token final: R3 zero-scaffold full stack (docker compose -f docker-compose.yml -f docker-compose.services.yml up -d --build) — six service containers healthy, six Prometheus scrape pools health=="up" (service-name URLs), query=up returns six series value=1, Grafana /login HTTP 200, plus in-container evidence: head of /app/logs/user-service.log shows JSON log lines and gateway /app/logs + /app/logs/sentinel both exist (sentinel-record.log present). No tmpfs/override log scaffold of any kind. offline rc=0 per-digit 698 = 52/50/165/166/159/80/26; token 29 items SUM=2034 baseline and close (only-increase; ≥ historical 2030 reference).
- discriminant results: R1/R2/R3/G1/G2/G3 all green (detail in handoff.md).
- deviations: D1 self-referencing C-02 hash; D2 local env remedy (native Redis holds 6379 -> gitignored transient override remaps host port 16379, deleted after use; not in repo); D3 token SUM reference 2030 vs measured baseline 2034 (only-increase, drift already present at e408e0c); D4 contract extract_claims has no extension whitelist entry for `Dockerfile` (README S163 known blind spot) so any post-handoff `--baseline=e408e0c` run is false-red by construction — open-state gate run in the C-01 in-transit window per TASK-215/216 precedent. See handoff.md S8.
