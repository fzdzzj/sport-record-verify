## Proposal: fix-image-log-dir (TASK-217, E18)

### Background
TASK-216 live verification exposed a pre-existing containerization defect: TASK-198's logback JSON file appender writes to relative `logs/<svc>.log` (resolved to /app/logs/ under WORKDIR), but the F20 images run as non-root `app` user and never create /app/logs — all six services fail bare bootstrapping with FileNotFoundException (gateway additionally fails on Sentinel JUL log dir ~/logs/csp whose home dir does not exist). TASK-216 worked around it with a tmpfs /app/logs scaffold (deleted after use); the defect itself remains: the containerized posture (authoritative since F20) cannot boot without scaffolding.

### Plan (MEDIUM tier: Dockerfile-only fix, zero Java / zero compose / zero CI changes)
- Five service Dockerfiles (user/record/verify/leaderboard/mapmatch): extend the existing groupadd/useradd RUN to also `mkdir -p /app/logs && chown app:app /app/logs`.
- gateway-service Dockerfile: additionally create /app/logs/sentinel and redirect Sentinel JUL logs there via ENTRYPOINT `-Dcsp.sentinel.log.dir=/app/logs/sentinel` (unified log root; avoids depending on the system-user home dir).
- Live verification: full containerized stack rebuild (`--build`) with ZERO scaffolding — six services healthy + TASK-216's three assertions (targets up / up query value 1 / Grafana 200) + in-container log file evidence (head of /app/logs/<svc>.log JSON lines; gateway sentinel dir listing).

### Non-Goals
- No logback-spring.xml / application.yml changes (relative-path semantics kept), no compose file edits, no ci.yml edits (image build gate picks up Dockerfile changes automatically), no log shipping/collection (Loki etc. — separate candidate).
