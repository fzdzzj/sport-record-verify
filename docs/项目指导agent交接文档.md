# 项目指导 Agent 交接文档

> **本文档定位**：我（项目指导 agent）离场前，把「这个项目是什么、做到哪了、我是怎么带的、有哪些必须传承的约定和坑、接下来该怎么继续带」一次性交给接手的 agent（或亲自继续的你）。
>
> **何时读**：接手本项目任何一件任务之前，先读本文 + `docs/项目速览手册.md` + `spec/specs/sport-record-verify/spec.md`。
>
> **撰写时的状态假设**：按你的要求，`add-admin-rbac`（治理面鉴权）与 `add-sport-type-threshold`（阈值分运动类型）两个提案**视为已完成**。文中会明确标注「假设已完成」，并附一条收口验证建议。

---

## 1. 项目一句话定位

**运动记录真实性校验系统（sport-record-verify）**：反作弊风控定位的 Java 微服务作品。核心不是「存运动记录」，而是「用规则链 + 空间真实性，识别伪造/作弊的运动轨迹」。这是明年面试的主打项目。

- 后端为主，前端 AI 生成（预算 10-15%）
- 单人微服务，本地 demo + 录屏演示（不买云服务器）
- GitHub 公开（代码会被面试官看）

---

## 2. 技术栈与版本矩阵（已锁定，勿动）

| 项 | 版本 | 备注 |
| --- | --- | --- |
| Java | 21 | |
| Spring Boot | 3.2.4 | **禁止升 3.3**（与 SCA 2023 分支不兼容） |
| Spring Cloud | 2023.0.1 | |
| Spring Cloud Alibaba | 2023.0.1.0 | |
| Nacos | 2.3.2 | 注册 + 配置中心 |
| Sentinel | 1.8.6 | 网关限流 |
| MyBatis-Plus | 3.5.7 | mybatis-plus-spring-boot3-starter |
| ShardingSphere-JDBC | 5.4.1 | 仅 record-service 轨迹分片 |
| Redisson | 3.27.2 | 分布式锁 |
| RocketMQ | 2.3.1 (starter) | 校验事件 |
| Caffeine | 3.1.8 | 本地缓存 |
| Lombok | 1.18.30 | |

版本锁定在父 `pom.xml` 的 `dependencyManagement`，子模块**一律不写 version**。

---

## 3. 服务全景（6 个业务服务 + 2 个基础模块）

| 模块 | 端口 | 数据库 | 职责 | 配置文件 |
| --- | --- | --- | --- | --- |
| gateway-service | 8080 | — | 统一入口、Sentinel 限流、JWT 鉴权过滤器 | yml |
| user-service | 8081 | user_db | 注册/登录/JWT 签发、好友、账号锁定 | yml |
| record-service | 8082 | record_db | 记录提交、轨迹分片存储、点赞 | **properties**（⚠️ 见坑 1） |
| verify-service | 8083 | verify_db | 校验引擎 R1-R5、规则灰度、二级缓存 | yml |
| leaderboard-service | 8084 | 复用 record_db | Redis ZSet 榜单、事件驱动、改判回滚 | yml |
| mapmatch-service | 8085 | PostGIS | OSM 路网、最近边投影匹配、R5 数据源 | yml |
| common | — | — | 统一 Result/ResultCode | — |
| api | — | — | Feign 契约 + DTO（跨服务共用） | — |

---

## 4. 已实现能力清单（按域）

### 4.1 校验引擎（核心）
- 流水线：预处理漂移过滤 → 规则链 R1-R5 → 判定聚合 + 评分
- R1 速度 >5.5m/s（HARD）、R2 加速度 >3m/s²（SOFT）、R3 停留 ≥5min<5m（HARD）、R4 距离 >3.0 倍（SOFT）、R5 离路比例（SOFT/HARD，空间匹配）
- score = 50 + 20×HARD + 10×SOFT，封顶 100
- 状态机：SUBMITTED→VERIFYING→PASSED/REJECTED；REJECTED→APPEALING→RE_PASSED/RE_CONFIRMED
- 乐观锁更新（version 字段 + WHERE 条件）

### 4.2 好友
- 双向好友，规范化存储（user_low < user_high + CHECK 约束）
- Redisson 锁 `lock:friend:{low}_{high}`，并发互加唯一性

### 4.3 点赞
- Redis INCR/DECR + 异步批量落库，`(record_id, user_id)` 复合主键

### 4.4 榜单
- Redis ZSet `leaderboard:overall`（member=userId, score=累计通过里程）
- `leaderboard_contribution` 表（record_id 主键 = 回滚锚点）
- 事件驱动最终一致 + 定时结算 `lock:scheduler:leaderboard`

### 4.5 事件与幂等
- RocketMQ topic `record-verify-events`，tags SUBMITTED/VERIFIED/REJECTED/REVERSED
- 事件幂等 eventId SETNX；DLQ `record-verify-events-dlq`

### 4.6 压测与优化
- 压测脚本 `scripts/perf/`，量化达标 + 瓶颈优化实录（ADR-0002）

### 4.7 可观测性
- Prometheus + Grafana，5 服务指标暴露 + 面板 + 告警规则（ADR-0003）

### 4.8 规则灰度
- rule_version 表 + gray_ratio + userId%100 采样 + 快照隔离 + 秒级回滚（ADR-0004）

### 4.9 空间匹配（R5）
- 独立 mapmatch-service + PostGIS + 真实 OSM 单城市切片 + 最近边投影（HMM 进阶为「讲设计」）（ADR-0006）

### 4.10 鉴权
- JWT 双 token + refresh rotation（Redis + Lua）+ 网关统一鉴权 + 数据隔离越权 403 + 账号锁定（ADR-0007）
- **治理面鉴权（add-admin-rbac，假设已完成）**：USER/ADMIN 角色，/admin/** 需 ADMIN

### 4.11 阈值分运动类型（add-sport-type-threshold，假设已完成）
- sportType 维度阈值，消除「骑行被 R1 误杀」，与灰度路由正交

---

## 5. 架构决策记录（ADR 索引）

| 编号 | 决策 | 一句话 |
| --- | --- | --- |
| 0001 | 版本矩阵与技术选型 | 为什么这套栈 |
| 0002 | 压测与优化实录 | 性能怎么达标 |
| 0003 | 监控选型 | 为什么 Prometheus+Grafana |
| 0004 | 规则灰度发布 | 为什么 Nacos+rule_version |
| 0005 | 服务划分 | 为什么 4→5 服务（榜单独立） |
| 0006 | 空间匹配 | 为什么 PostGIS/最近边投影/HMM 进阶 |
| 0007 | 鉴权设计 | 为什么 JWT/refresh rotation/账号锁定 |

> **编号纪律**：ADR 编号以文件实际序号为准，任务书指定编号与落盘可能顺延（见坑 3）。

---

## 6. 规范驱动工作流（openspec，本项目方法论核心）

这是本项目区别于普通 CRUD 项目的**最大方法论资产**，面试可讲「规范驱动开发」。

每个提案 = 三个文件（都在 `spec/changes/<change-id>/`）：
1. `proposal.md` — Why / What Changes / Impact / 时间线 / 风险 / 备注
2. `tasks.json` — 实施清单，按 number 顺序做
3. `specs/sport-record-verify/spec-delta.md` — EARS 规范差异（`## ADDED Requirements` / `## MODIFIED Requirements`）

**生命周期**：创建提案 → 委派实施（子 agent）→ 代码落地 → `archive` 移动 + spec-delta 合并进主规范 `spec/specs/sport-record-verify/spec.md`。

主规范目前 654 行、约 54 条需求、120 场景（分 10 个能力域），是「代码 → 规范 → 面试」的桥。

---

## 7. 我的协作方法（交接的重点：怎么继续带）

### 7.1 提问方式
- 用选项式提问（`ask_user_question`），**一次 2-4 项**，不抛开放问题
- 推荐项放第一，标注 `(Recommended)`
- 用户偏好「一项一项列出来问我」，后来允许「一次带几项」→ 稳定在 2-4 项

### 7.2 commit 规范（细粒度）
- 每个 tasks.json 的 step 一个 commit，**不攒着**
- conventional commits：第一行 `类型(范围): 摘要`（≤50字）
- body 中文逐条「`- 改了什么：为什么`」
- **commit 之间保持可独立编译**
- 用户明确要「拆成更多更小的 commit」，不是「更详细的 message body」

### 7.3 委派提示词四件套规格（写死，每次照抄）
1. **只允许写哪些文件** + 声明「无其他写入者」
2. **最多修复尝试 1 次**（一轮不通过就如实上报，不循环验证）
3. **回报 ≤300 字**：产出路径/行数/校验结果/未解决项/待决策项
4. **细节留在文件里**，不贴代码进回报

自包含提示词必含：必读材料（proposal/spec-delta/tasks.json）、必读现有代码、关键现状（不要重复造轮子）、中文注释要求、验收项、commit 计划。

### 7.4 诚实原则（不可破）
- 不吹未实现功能；Canal/Seata 明确「讲设计，不用」；OSM 原为讲设计后落地
- 「为什么微服务」= 单人负担控制（诚实回答）
- 课程项目（hmall/daijia）不上简历

---

## 8. 关键坑与约定（必须传承，别让下一个人重踩）

1. **record-service 用 application.properties 不是 yml**：ShardingSphere 5.4.1 的 YamlEngine 需要 snakeyaml 1.x，Boot 3.2.4 YAML 加载需要 2.x，二者冲突 → record-service 降级 snakeyaml 1.33 并改用 properties。**改 record-service 配置永远别加 yml**。
2. **两个 Redis 抢 6379**：Windows 原生 redis-server 与 Docker 容器同时监听，服务实际连的是原生那个。验证数据用 `docker exec sport-verify-redis redis-cli -h host.docker.internal -p 6379 ...`，别直接 `redis-cli`（那看的是空容器）。详见 `docs/本地验证与交付说明.md`。
3. **ADR 编号顺延**：任务书指定 0004-服务划分，实际被灰度占用，落盘 0005。以文件序号为准。
4. **RE_CONFIRMED 触发**：公开接口规定 RE_PASSED 不能再申诉；测试时重置申诉单状态走「终判驳回」路径（与生产同一条事件路径）。
5. **榜单路由破坏性变更**：`/record/api/leaderboard` → `/leaderboard/api/leaderboard`，无兼容期过渡（符合提案口径）。
6. **宿主机进程 + 容器中间件**：Prometheus 抓宿主机用 `host.docker.internal:8080-8084`，容器内访问宿主机的专用域名。
7. **网关是 WebFlux 反应式**：过滤器是 GlobalFilter，不是 Servlet Filter。

---

## 9. 当前收口状态（实测，非假设）

### 9.1 git
- 59 个 commit，工作区在最近一次检查时干净

### 9.2 规范归档状态
- **archive（9 个，已归档）**：add-microservice-skeleton、add-verify-engine、add-friend-module、add-like-module、add-leaderboard-module、add-load-test-report、add-observability、add-rule-grayscale、add-leaderboard-service
- **changes/ 未归档（5 个）**：
  - `add-jwt-auth` — 代码已实现（7 commit），**spec 未归档**
  - `add-mapmatch-service` — 代码已实现（7 commit），**spec 未归档**
  - `add-login-lockout` — 账号锁定，代码状态待最终确认
  - `add-admin-rbac` — 治理面鉴权，**假设已完成**（按本次指令）
  - `add-sport-type-threshold` — 阈值分类型，**假设已完成**（按本次指令）

### 9.3 待办收口（建议下一步做）
1. **归档收口**：把上面 5 个（或确认代码完成后）的 spec-delta 合并进主规范 `spec.md`，提案移入 archive。这是当前最大的规范债。
2. **add-two-level-cache 状态核对**：该提案在最近一次检查中已不在 changes/ 下，需确认它是否已实施+归档，避免遗漏。

---

## 10. 剩余方向（已评估，ROI 排序）

| 优先级 | 方向 | Change ID（拟） | 说明 |
| --- | --- | --- | --- |
| 高 | 面试话术包 | — | 故事线三版本 + 20条 Q&A + 记忆包（用户暂缓，说「先不搞面试」） |
| 中 | 全链路 trace + 日志聚合 | add-tracing | 跨 6 服务无 traceId 串联，目前只能 grep |
| 低 | 统一 outbox/本地消息表 | add-outbox | 把零散幂等/补偿收口成标准最终一致（重构风险高） |
| 不做 | Canal / Seata | — | 明确「讲设计即可」，态度一致性：不加 |
| 讲设计 | OSM 全国路网 / HMM 进阶 | — | 空间匹配的进阶弹药，不落地 |

---

## 11. 交接验证清单（接手后先跑一遍）

```bash
# 1. 编译
mvn clean install

# 2. 中间件（含可选 prometheus/grafana，postgis 因 mapmatch 需要）
docker compose up -d

# 3. 起 6 服务（gateway 8080 / user 8081 / record 8082 / verify 8083 / leaderboard 8084 / mapmatch 8085）

# 4. 冒烟：榜单（破坏性变更后的新路径）
curl http://127.0.0.1:8080/leaderboard/api/leaderboard?type=overall

# 5. 鉴权冒烟（若 add-admin-rbac 已做）
#    普通用户 token 访问 /admin/** → 403；无 token → 401；ADMIN → 放行

# 6. 空间匹配冒烟（mapmatch）
#    真实沿路轨迹 → R5 不命中；悬浮轨迹 → R5 命中

# 7. Redis 数据核对（记得 host.docker.internal，见坑 2）
docker exec sport-verify-redis redis-cli -h host.docker.internal -p 6379 KEYS 'leaderboard:*'
```

---

## 12. 一句话总结（给接手者）

这个项目已经**功能上相当完整**（6 服务、校验引擎、好友/点赞/榜单、压测、监控、灰度、空间匹配、鉴权闭环），技术深度和诚实度都达标。**当前真正的债不是「再加功能」，而是「收口」**：把未归档的 5 个提案合进规范、把话术包做出来。继续加 P2 功能边际收益递减，收口 + 面试沉淀才是 ROI 最高的路。
