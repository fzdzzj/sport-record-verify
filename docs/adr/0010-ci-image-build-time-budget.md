# ADR-0010：CI 镜像构建时长余量（BuildKit 层缓存 vs 现状策略）

日期：2026-09-21
状态：已采纳（TASK-113 评估交付；纯评估，未实施任何缓存/CI 改动）

编号说明：`docs/adr/` 已存在且 0001-0009 已被占用；任务包起草时假设该目录不存在并写了
`0001` 占位名，本篇按既有编号顺延取 **0010**，slug `ci-image-build-time-budget` 保留。

## 1. 现状

`build` job 定义于 `.github/workflows/ci.yml` L10（`runs-on: ubuntu-latest` 在 L11），九个步骤：

| 行号 | 步骤名 | 内容 |
| --- | --- | --- |
| L16-17 | Checkout | `actions/checkout@v4` |
| L19-23 | Set up JDK 21 (Temurin) | `actions/setup-java@v4` |
| L26-31 | Cache Maven repository | `actions/cache@v4` → `~/.m2/repository`；只加速宿主侧 Maven，与镜像内构建无关 |
| L36-37 | Build and test | `bash scripts/verify/mvn-verify.sh --mode=online verify` |
| L42-43 | Provision placeholder env for compose checks | `cp scripts/verify/env.example .env` |
| L47-48 | Compose files parse check | `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`（6 服务编排解析判据） |
| L54-55 | Build representative service image | `docker compose -f docker-compose.yml -f docker-compose.services.yml build leaderboard-service`（只实构 1 份代表镜像，其余 5 份由上一步 config 覆盖） |
| L64-70 | Public docs wording self-check | git grep 公开口径词面自检 |
| L73-81 | Upload JaCoCo reports | artifact 上传 |

（任务包将 build job 记为 L10-55；实际含上传步骤到 L81，引用以上表为准，均可过 `sed -n '<行号>p'` 抽查。）

### 47s 基线及来源

- 来源：`work/mailbox/PLAN.md`《验收记录：add-controlled-verify-entrypoint》门槛来源行（L28）
  绑定**外部门槛 run `35571634401`**（commit `1fbf3eb`，run 1m48s，build 与 web 两 job 全绿、
  15 步无一 skip），原文："代表镜像 47s 真建成（日志含 Maven `BUILD SUCCESS` 与
  `writing image sha256:4caa1f83…`）"。即 47s 是 Build representative service image 步在
  runner 上的实测时长，**含**基础镜像拉取与容器内 Maven 全量下载。该 run 已达外部门槛
  （已推送且 CI 绿），run 号可绑、非编造。
- 出处勘误（如实）：任务包称 47s 系"概览 §8.5 转引"。经查 `docs/判定引擎-开发总览.md`
  无 §8.5 小节，docs 全域检索 "47" 仅命中 `docs/perf/` 下 GC 百分比与本台账——47s 的唯一
  可考出处即 PLAN.md L28，本 ADR 以它为准，不另造锚点。
- 代表性核查：`git diff 1fbf3eb..HEAD -- .github/workflows/ci.yml` 仅改 Public docs wording
  self-check 与 web job 尾部（12 增 9 删），上表 L36-55 三步逐字未动——47s 对当前 HEAD
  仍具代表性。
- 边界：本任务自身（本 ADR 与台账）未推送，"本任务新增修订未达外部门槛"；47s 是对既有
  外部 run 的转引，不是本任务的新实跑。

### 机制结构（决定后续一切推演）

6 份 Dockerfile 同构（仅 `-pl <模块>`、jar 路径与 EXPOSE 不同，6 份 md5 互异）：
stage1 `FROM maven:3.9-eclipse-temurin-21` → `COPY . .` → `RUN mvn -B -pl <svc> -am -DskipTests package`；
stage2 `FROM eclipse-temurin:21-jre` → 拷 jar。三个要点：

1. 容器内 `~/.m2` 每次构建从零下载（无 cache mount）；GHA runner 一次性、无层缓存——
   每个 run 都重付全部下载。
2. `.dockerignore` 把 `docs/ web/ work/ scripts/ sql/ .m2-repo/ **/target/ .env` 等挡在上下文外；
   在上下文内的是 pom 集、`common/ api/`、六服务源码、`spec/`、`.github/`、compose 文件、
   根 `*.md`——**文档/前端/verify 脚本类提交不改变 `COPY . .` 层的输入校验和**。
3. 六服务在 compose 中同为 `context: .`：不同 Dockerfile 的 `COPY . .` 输入相同，BuildKit
   内容寻址下跨服务可命中；`RUN mvn -pl <模块>` 命令串各不相同，必各跑各的（本机实测
   证实，见 §3）。

## 2. 策略对比

| 维度 | A：BuildKit 层缓存（buildx + cache-from/to type=gha） | B：维持现状（1 实构 + config 覆盖） |
| --- | --- | --- |
| 时长增量预测 | 上下文变更日（代码/pom/spec/.github/compose 改动）：`COPY . .` 失效 → mvn 层必重跑，**与 B 等速**；上下文不变日（docs/web/scripts 类提交）：mvn 层全命中，47s → 缓存恢复 + 基础拉取，估 20~40s（估算） | 每多实构 1 份 = 新增 1 个 mvn 层；runner 口径估 **+30~45s/份**（机制估算，见 §3） |
| 缓存配额与维护成本 | GHA 缓存单仓约 10GB、LRU 清理；需新增 buildx builder 与 cache 步骤；导出端 compose 不支持，须引入 bake / build-push-action——与"复用 compose 构建定义"形成双轨漂移面 | 零新增设施、零缓存维护 |
| 失效风险 | type=gha 恢复失败**静默降级**为无缓存（不红、只慢，无人察觉）；缓存键误设计可永不命中；**cache mounts（~/.m2）不随 cache-to 导出**（已查证，2026-09）→ 不改 Dockerfile 分层就存不住依赖缓存 | 无缓存可失效 |
| 对 6 服务编排的适配度 | 需改 ci.yml L54-55（触碰 `.github/`，属未来变更）；6 份并行实构时缓存恢复带宽与配额压力放大 | 复用 compose 自身构建定义（ci.yml L50-53 注释的防漂移设计），零适配成本 |
| 证据强度（附带） | 不变 | 每多实构一份 = 多一份"Dockerfile 真可建成"的实证，强于 config 解析判据 |

## 3. 量化推演

### 47s 的构成与"每多实构 1 份"的机制

47s ≈ 基础镜像拉取 + `COPY . .` + `RUN mvn -pl leaderboard-service -am package`
（容器内空 `~/.m2` 全量下载 + 编译，**主要份额**）+ stage2 组装写镜像。同一 run 中
Build and test 步 ≈46s、job 总 1m48s → 镜像构建步占 build job **约 44%**。
每多实构 1 份 = 复用已拉取的基础镜像与相同的 `COPY . .` 层，新增 1 个
`RUN mvn -pl <下一服务>` 层 + stage2 组装 → **runner 口径增量 ≈ mvn 层份额 ≈ 30~45s/份**
（机制估算；各模块依赖图不同，增量随服务浮动）。

### 本机冷 / 热 / 增量对照（本机实测，非 runner 口径）

条件：Docker 29.6.2 + Compose v5.3.1（BuildKit）；基础镜像本地已在（冷跑不付拉取）；
本机到 Maven Central 带宽慢，**绝对时长被下载支配、不可外推 runner**——取其层机制份额：

| 场景 | 命令 | 实测 | 层明细（`--progress=plain`） |
| --- | --- | --- | --- |
| 冷：leaderboard 首构 | `docker compose … build --no-cache leaderboard-service` | **340.0s** | `RUN mvn -pl leaderboard-service` 层 **330.2s（≈97%）**；COPY/组装/写镜像等合计 <10s |
| 热：leaderboard 重跑 | `docker compose … build leaderboard-service` | **2.3s** | 全部层 CACHED（打标/解包 0.2s 级） |
| 增量：gateway 首构（紧随 leaderboard，同上下文） | `docker compose … build gateway-service` | **219.8s** | `[build 3/4] COPY . .` **CACHED**（跨 Dockerfile 复用成立）；仅 `[build 4/4] RUN mvn -pl gateway-service` 重跑 **215.3s（占增量 ≈98%）**；stage2 元数据层亦 CACHED |

三组数字为 2026-09-21 本机实测（脚本与日志为临时件，用完即删）。可迁移到 runner 的结论是
**份额而非绝对值**：冷构时长几乎全部落在 mvn 层（本机 97~98%）；层缓存命中路径近乎免费
（2.3s）；增量实构的增量就是一份独立 mvn 层。

### 推演表（runner 口径，机制估算）

| 实构镜像数 | build 段时长 | build job 总时长（今日 ≈1m48s 口径外推） |
| --- | --- | --- |
| 1（现状） | ≈47s（实测基线） | ≈1.8 min（实测） |
| 2 | ≈1.5 min | ≈2.6 min |
| 3 | ≈2.2~2.5 min | ≈3.2~3.5 min |
| 6 | ≈3.5~4.5 min | ≈4.5~5.5 min（今日的 3 倍级） |

## 4. 结论与建议

**维持策略 B（1 份实构 + config 覆盖），不引入 BuildKit 层缓存。** 理由：

1. 余量充足：build job 1m48s，镜像构建步占约 44%；扩到 3 份实构也只在 ~3.5 min 量级，
   尚无瓶颈信号。
2. 策略 A 的收益面被 Dockerfile 现状锁死：`COPY . .` 是 mvn 层的父层，上下文内任何变更
   （六服务源码、pom、spec/、.github/、compose 文件）都使 mvn 层失效——**代码变更日 A 与
   B 等速**；A 只在上下文不变的 run（docs/web/verify 脚本类提交，如本任务自身——改动全部
   落在被 `.dockerignore` 挡掉的 `docs/`、`work/`）把 47s 压到 20~40s，收益是秒级而非分级。
3. A 的跨 run 天花板比表面更低：cache mounts 不随 cache-to 导出（已查证），不改 Dockerfile
   分层就持久化不了 `~/.m2`；而真正的大头是分层改造（§5.1），属另一个变更。
4. A 需改 ci.yml 并引入 bake/build-push 双轨，与现有"复用 compose 构建定义防漂移"的判据
   设计（ci.yml L50-53 注释）相抵。
5. 多实构本身有正外部性：每份实构都是该 Dockerfile 真可建成的实证。

**重评触发条件（满足其一即重开本评估）**：实构镜像数 > 3；build job 总时长 > 5 分钟；
CI 出现构建资源/超时类失败。

## 5. 后续路径（若将来切换——仅列举，本任务不实施）

1. **Dockerfile 分层改造（收益大头）**：先 `COPY` pom 集 → `RUN mvn -pl <svc> -am
   dependency:go-offline` 独立成层（依赖不变即命中）→ 再 `COPY` 源码并 package——让依赖
   下载进"常规层"，type=gha 缓存才有稳定命中面。
2. **构建步 buildx 化**：`setup-buildx-action` + bake（bake 直接读同一份 compose 构建段，
   避免双轨漂移）+ `cache-from/cache-to type=gha mode=max`；compose 自带的
   `build.cache_from` 只覆盖恢复端，导出端由 bake 承接。
3. **cache mounts**（`RUN --mount=type=cache,target=/root/.m2`）：作为同 run 内多服务实构的
   去重手段；明确其**不跨 run**（GHA 后端不导出，本 ADR 已查证），不得单独作为跨 run 方案。
4. 若将来实构全部 6 份：bake 并行 + §5.1/§5.3 组合，把 build 段从 ~4 min 压回 1 min 量级；
   届时同步修订 ci.yml L50-55 判据注释与 `scripts/verify/README.md` 口径（词面自检对 ci.yml
   自身的排除约定不变）。
