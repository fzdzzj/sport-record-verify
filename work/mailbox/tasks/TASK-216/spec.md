# TASK-216: Prometheus scrape targets containerized-posture alignment (E11)

Tier: MEDIUM (pure config two files + live verification; zero Java, zero compose, zero CI). Review: guidance-side verification only, CI as external final gate. Escalation: live-verification contradiction or spill -> FULL.

## S0 hard constraints
- ZERO changes to: docker-compose.yml, docker-compose.services.yml, docker-compose.perf.yml, all six services src/main/resources (application.yml), pom files, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, scripts/, grafana/ directory, prometheus/alert-rules.yml, spec/changes/archive/**, web.
- Whitelist only (S4). No new files outside whitelist.

## S1 verified context
- Observability stack EXISTS: docker-compose.yml:154-194 prometheus (v2.51.2, alert-rules + prometheus.yml mounted ro, TSDB volume, 9090 loopback-bound) + grafana (10.4.2, provisioning + dashboards mounted, admin creds via .env GRAFANA_ADMIN_PASSWORD :?required per TASK-190).
- prometheus/prometheus.yml: six jobs (gateway/user/record/verify/leaderboard/mapmatch), all targets `host.docker.internal:8080-8085` (host-process posture). File comment itself pre-announces: "若日后把服务容器化并 join sport-verify-net，把 host.docker.internal 换成对应 compose 服务名（如 gateway-service:8080）即可".
- F20 containerization (docker-compose.services.yml): six services in sport-verify-net; gateway 8080 published on all interfaces; five backends bound 127.0.0.1 only (governance-boundary rationale, compose services header comment).
- Breakage mechanism under containerized posture: Linux host-gateway resolves to bridge IP where loopback-bound ports do not listen -> five backends unreachable; Docker Desktop behavior untested/uncertain. Compose-network DNS service names are the correct target form (independent of port publishing).
- alert-rules.yml: ServiceInstanceDown matches by job regex (job names unchanged -> zero impact); HttpErrorRateHigh/HttpP95LatencyHigh/JvmHeapUsageHigh aggregate by job label (unchanged). Grafana dashboards query by job label (unchanged).
- README.md:236-238: monitoring paragraph still describes host-process posture with the same "容器化后改 target 为服务名即可" pre-announcement.
- extra_hosts host-gateway on prometheus service: keep (harmless; Linux fallback for host-posture configs).
- offline baseline: 698 = 52/50/165/166/159/80/26.

## S2 preregistered design
- prometheus/prometheus.yml: six job targets -> `gateway-service:8080`, `user-service:8081`, `record-service:8082`, `verify-service:8083`, `leaderboard-service:8084`, `mapmatch-service:8085` (compose service names = DNS aliases in sport-verify-net). Update the "抓取地址说明" comment block: containerized F20 posture is authoritative; host-process dev runs (java -jar + middleware-only compose) accept degraded monitoring and would need a separate host.docker.internal config. Keep scrape_interval/evaluation_interval/rule_files/metrics_path/job names untouched.
- README.md monitoring paragraph (lines ~236-238): rewrite the two sentences describing host-process scraping to containerized service-name scraping; keep ADR-0003 link and the production-profile caveat (actuator收敛) untouched.
- Live verification (R3): bring up containerized stack via `docker compose -f docker-compose.yml -f docker-compose.services.yml up -d --build` (bash entry D:\git\Git\bin\bash.exe for scripts if needed; docker CLI direct for compose). Degrade path if local memory budget insufficient: minimal set = middleware six-pack + gateway-service + user-service + mapmatch-service (covers 8080 full-publish, one loopbound backend, 8085). Assert:
  - `curl http://127.0.0.1:9090/api/v1/targets` -> all six (or three) scrape pools health=="up" for started services;
  - `curl http://127.0.0.1:9090/api/v1/query?query=up` -> each started job has an up series with value 1;
  - `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/login` -> 200 (Grafana reachable).
  - Timing note: allow >=60s after stack up before asserting targets (first scrape + healthcheck start_period).

## S2.5 discriminants
- R1: prometheus.yml diff = six target lines + comment block only; job names/metrics_path/intervals/rule_files untouched.
- R2: README diff confined to monitoring paragraph posture wording; ADR-0003 link and production caveat intact.
- R3: live targets up (per started set), up query value 1 per started job, Grafana 200.
- G1: offline 698 per-digit unchanged (zero Java touched).
- G2: changed set == exactly S4 whitelist; compose files / alert-rules / grafana / services src zero diff.
- G3: standard MEDIUM gates (contract dual-state, token 2030 two-point, word-check ZERO_HIT + probe, diff --check, line endings: prometheus.yml and README.md preserve their baseline endings).

## S3 stop conditions
1. Service-name targets still DOWN in live verification (DNS/network anomaly) — report with evidence, do not silently revert to host.docker.internal to force green.
2. Fixing reachability appears to require touching compose files / service application.yml / port bindings — outside whitelist, stop.
3. Local resources insufficient even for the degraded minimal set — register UNDETERMINED with evidence and report.
4. Evidence found that host-process posture is the team's primary working posture (switch would break existing workflows) — stop and re-adjudicate posture choice.

## S4 whitelist (target C-01 = exactly 2 files)
prometheus/prometheus.yml, README.md (root).

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 698 = 52/50/165/166/159/80/26 (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- contract gate dual-state: --open TASK-216 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 two-point; word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; changed files preserve baseline line endings (verify with git ls-files --eol).
- C-01 fix(obs): ... (TASK-216); C-02 docs(mailbox): ... (TASK-216) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = `721ae46` (fix(obs): Prometheus抓取target对齐容器化服务名（TASK-216）, 2 files, +17/-15); C-02 = self-referencing hash (precedent).
- live verification evidence / offline / token final: R3 full containerized stack — all six scrape pools health=="up" via service-name URLs, query=up returns six series value=1, Grafana /login HTTP 200; offline rc=0 698 = 52/50/165/166/159/80/26 per-digit unchanged; token 29 items SUM=2030 two-point (only-increase).
- discriminant results: R1/R2/R3/G1/G2/G3 all green (detail in handoff.md).
- deviations: D1 self-referencing C-02 hash; D2 local-env blockers (stale service images, native Redis service on 6379, missing writable logs dir in image, MySQL volume password drift) resolved by local repair + a gitignored transient scaffold, deleted after use; pre-existing image defect (logback `logs/` dir absent for non-root user) recorded as non-blocking, unrelated to this change. See handoff.md S8.
