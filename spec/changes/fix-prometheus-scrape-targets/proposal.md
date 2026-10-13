## Proposal: fix-prometheus-scrape-targets (TASK-216)

### Background
E11 recon (2026-10-13) overturned the original premise: the observability stack already EXISTS in full — docker-compose.yml ships Prometheus 2.51 (alert-rules mounted, TSDB volume) + Grafana 10.4 (provisioned datasource/dashboard, admin creds via .env per TASK-190) per ADR-0003. The real gap: prometheus.yml scrape targets are ALL `host.docker.internal:8080-8085` (host-process posture), while F20 containerization (docker-compose.services.yml) already moved the six services into sport-verify-net. Under the containerized posture the five backend targets (ports bound to 127.0.0.1) are unreachable via host-gateway on Linux hosts (host-gateway resolves to bridge IP where loopback-bound ports do not listen); Docker Desktop behavior is uncertain and untested. prometheus.yml's own comment and README:238 both pre-announce this change: "容器化后改 target 为服务名即可".

### Plan (MEDIUM tier: pure config + live verification, zero Java / zero compose / zero CI changes)
- Switch all six scrape job targets from host.docker.internal:808x to compose service names (gateway-service:8080 ... mapmatch-service:8085) — compose-network DNS, independent of port publishing. Update file comments to document the posture switch (containerized = authoritative; host-process dev runs need a separate host.docker.internal config).
- README:236-238 monitoring paragraph: sync wording to the containerized posture.
- Live verification: bring up the containerized stack (or minimal degraded set covering gateway 8080 full-publish + one loopbound backend + mapmatch 8085), assert Prometheus targets all UP via /api/v1/targets and up query non-empty; Grafana reachable.
- alert-rules.yml (job-name matched) and Grafana dashboards (job-label queried): zero impact, zero changes.

### Non-Goals
- No alert-rules/Grafana dashboard changes, no compose file edits, no service application.yml edits, no Alertmanager (production follow-up), no host-process-posture config file (dev runs accept degraded monitoring).
