# TASK-113 Handoff

**实现方：执行 agent（收口授权下放，指导侧不再复跑）。未 push、未建 PR。** 开工基线 `0883bec`。

## 只改清单

- docs/adr/0010-ci-image-build-time-budget.md
- work/mailbox/tasks/TASK-113/spec.md
- work/mailbox/tasks/TASK-113/handoff.md
- work/mailbox/PLAN.md

## ADR 交付与编号偏离说明

- `docs/adr/` 已存在且 0001-0009 已被占用（任务包起草时假设该目录不存在），ADR 顺延取
  **0010**，slug 保留 `ci-image-build-time-budget`；四文件白名单口径不变，白名单外零改动。
- 不建 `spec/changes/` 三件套的理由：本任务纯评估、无代码/规格需求改动，台账两件套
  （spec.md + handoff.md）即满足 `mailbox-contract.sh` 判据 A（任务包同款约定）。
- ADR-0010 五节齐全：§1 现状（ci.yml 步骤名+行号表、47s 基线来源、机制结构）/ §2 策略对比
  表（A vs B 五维度）/ §3 量化推演（47s 构成 + 本机冷/热/增量实测 + runner 口径推演表）/
  §4 结论与建议（维持 B + 重评触发条件）/ §5 后续路径（四项，仅列举不实施）。

## 47s 基线来源（如实）

- **可绑外部门槛**：PLAN.md《add-controlled-verify-entrypoint》验收记录门槛来源行（L28）
  绑定 run `35571634401`（commit `1fbf3eb`，run 1m48s，build 与 web 两 job 全绿、15 步无
  skip）："代表镜像 47s 真建成（日志含 Maven `BUILD SUCCESS` 与 `writing image
  sha256:4caa1f83…`）"——47s 为该步在 runner 上的实测时长（含基础镜像拉取与容器内 Maven
  全量下载），非编造、非新实跑。
- **出处勘误**：任务包所称"概览 §8.5"经查无对应小节（`docs/判定引擎-开发总览.md` 无 §8.5；
  docs 全域检索 "47" 仅命中 `docs/perf/` 下 GC 百分比与本台账）——ADR §1 如实记录勘误。
- **代表性核查**：`git diff 1fbf3eb..HEAD -- .github/workflows/ci.yml` 仅改词面自检与
  web job 尾部（12 增 9 删），Build and test / config -q / Build representative service
  image 三步逐字未动 → 47s 对当前 HEAD 仍具代表性。

## 量化实测（本机口径，非 runner 口径）

本机 Docker 29.6.2 + Compose v5.3.1（BuildKit），2026-09-21 实测；基础镜像本地已在
（冷跑不付拉取）、本机到 Maven Central 带宽慢 → **绝对时长被下载支配、不可外推 runner**，
仅取层机制份额（`--progress=plain` 层明细）：

- 冷（`build --no-cache leaderboard-service`）：**340.0s**，`RUN mvn -pl leaderboard-service`
  层 330.2s ≈ **97%**，其余（COPY/组装/写镜像）合计 <10s；
- 热（重跑同命令）：**2.3s**，全部层 CACHED；
- 增量（gateway 首构，紧随 leaderboard、同上下文）：**219.8s**，`[build 3/4] COPY . .`
  **CACHED**（跨 Dockerfile 复用成立）、`[build 4/4] RUN mvn -pl gateway-service` 重跑
  215.3s 占增量 ≈**98%**、stage2 元数据层亦 CACHED。

runner 口径推演（机制估算，ADR 已显式标"估算"）：每多实构 1 份 ≈ **+30~45s**；
实构 6 份时 build 段 ≈3.5~4.5 min、build job 总 ≈4.5~5.5 min（今日 1m48s 的 3 倍级）。

## 结论（ADR §4，回传摘要）

**维持策略 B（1 份实构 + config 覆盖），不引入 BuildKit 层缓存。** 关键依据：`COPY . .`
是 mvn 层父层，上下文变更日 A 与 B 等速；A 仅对 docs/web/scripts 类提交（上下文不变）
有 20~40s 秒级收益；cache mounts 不随 cache-to 导出（已查证），不改分层持久化不了
`~/.m2`；A 需改 ci.yml 并引入 bake/build-push 双轨。重评触发条件：实构镜像数 > 3、
build job > 5 分钟、或构建资源类失败。切换属未来变更，动作清单见 ADR §5。

## 实跑结论（唯一入口 `mvn-verify.sh`）

- `bash scripts/verify/mvn-verify.sh --mode=offline test`：**RC=0 / BUILD SUCCESS**，
  模块合计 **17/19/31/80/81/49/6 = 283**，0 失败 0 错误 0 跳过（与基线一致，纯文档零扰动），
  Total time 01:27。

## 判据自证

- **ci.yml 行号 sed 抽查**：`sed -n` 对 ADR 引用的 14 行逐一抽查（10/11/16/26/36/37/42/
  47/48/54/55/64/73/81），输出与 ADR §1 表逐字一致，全部命中。
- **词面自检**：判据脚本落盘执行（命令行不带中文），两口径均 ZERO-HIT 退出 0：
  ① CI 原版口径（仅 tracked，三处排除不变）零命中；
  ② `--untracked` 扩围（排除 `.trae/**`）零命中——本任务 4 个交付文件全部干净。
  辨析：首跑 untracked 全量（不排除 `.trae/`）曾命中 `.trae/tmp/wording-check.sh:5`，
  系指导侧既有验收脚本正文自带禁用词正则字面量所致；`.trae/` 属任务基线允许的本机残留
  （契约脚本同款排除），CI 只扫 tracked 永远不会触达，属验收工具自身的已知伪影而非交付
  载体命中，未改动该文件（白名单外零改动）。
- **契约**：收口提交前 `bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106`
  脏树退出 **1** 属预期——TASK-113 自身判据 B 只改清单与实际改动集 4=4 一致；残余为历史
  TASK-110/111/112 清单共占 PLAN.md 的过冲。收口提交后 ACTUAL 空（仅 `.trae/` 排除）→
  契约退出 **0**。

## 台账

- `work/mailbox/PLAN.md` 追加《验收记录：CI 镜像构建时长余量评估（TASK-113，可接手事项
  5/5，2026-09-21）》，起始行 **L266**（至 L277）。

## 未决 / 分期

- 策略 A（BuildKit 层缓存）及其前置的 Dockerfile 分层改造属**未来变更**，本任务仅列举
  不实施；重评触发条件见 ADR §4。
- 未达外部门槛：本任务修订未 push，无 CI run 编号可绑；47s 为既有外部 run 的转引。
- 临时件已清理：计时脚本、日志、offline 全量日志（`/tmp`）；计时产物镜像
  `sport-verify-leaderboard-service`、`sport-verify-gateway-service` 已 `docker rmi`
  （BuildKit 层缓存保留，不影响本地后续构建）。
