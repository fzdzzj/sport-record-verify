# spec-delta：存量 CRITICAL 依赖升级治理（TASK-196 harden-dep-critical-pin）

## MODIFIED 需求：父 pom 依赖管理（安全补丁 Pin）

父 pom `<dependencyManagement>` SHALL 在 spring-boot-dependencies import 条目之前精准 Pin 以下补丁版本（三 BOM 本体零改动，Boot 3.2.4 / Cloud 2023.0.1 / SCA 2023.0.1.0 基线锁定，ADR-0001 合规）：

- `io.netty:netty-bom:4.1.137.Final`（BOM 统一锁定全部 netty 构件，防 gateway/Redisson 双链版本分裂）
- `org.apache.tomcat.embed:tomcat-embed-core:10.1.58`（消 7 项：CVE-2025-24813 / 2026-41293 / 43512 / 43515 / 65182 / 65905 / 68525）
- `org.springframework:spring-framework-bom:6.1.29`（消 2 项：spring-webmvc CVE-2026-47884、spring-webflux CVE-2026-47892）
- `org.bouncycastle:bcprov-jdk18on:1.85`（消 2 项：CVE-2025-14813 / 2026-8763）

Pin 形态（BOM import vs 逐构件显式声明）由执行侧按 Maven First-Declaration-Wins 仲裁实测，登记 handoff。

## ADDED 需求：fastjson 传递排除治理

SHALL 从依赖链根除 `com.alibaba:fastjson:1.2.83`（CVE-2026-16723，1.x EOL 无补丁；全仓零业务代码引用，业务 JSON 统一 Jackson）：

- user/record/verify/leaderboard 四模块 pom 对 rocketmq-spring-boot-starter:2.3.1 声明 fastjson exclusions
- gateway pom 对 sentinel 链声明 fastjson exclusions
- 若排除后测试/MQ 链出现类缺失（NoClassDefFoundError），以父 pom Pin `com.alibaba.fastjson2:fastjson-to-fastjson2:2.0.53` 桥接兜底（本仓未启用 RocketMQ ACL，rocketmq-acl 无触发路径）
- 路径选型（直接排除 vs 排除+桥接）实测后定并登记理由

## MODIFIED 需求：.trivyignore 收缩治理

TASK-195 登记的 13 项存量指纹 SHALL 随各阶段实测修复逐行删除（含分组注释），文件保留头部规则注释（ci.yml 零触碰，`--ignorefile` 指向空壳文件持续有效）。三阶段终态：`.trivyignore` 仅剩头注释，Trivy 门禁跑次（CRITICAL + exit 1 + ignore-unfixed）零豁免下 6 镜像全绿。某项升级后 Trivy 仍检出（修复版本不覆盖该 CVE）→ 对应行保留并登记偏差，不得谎报消除。

## 验收断言

- pom-only：业务源码与测试源码零改动；offline 569（36/44/127/137/149/64/12）逐位不变；static 双模块（record + leaderboard）rc=0
- 6 服务镜像构建全绿；`mvn dependency:tree` 实证：无 fastjson:1.2.83、netty 构件无版本分裂、bcprov 全 6 服务 1.85
- 只改清单：C-01 恰 7 文件（父 pom + gateway/user/record/verify/leaderboard pom + .trivyignore；桥接兜底时父 pom 内含）；C-02 恰 4 台账文件（tasks.json / 任务书 §7 / handoff.md / PLAN.md；findings 已清零无标注项）
- 零触碰：common/mapmatch 模块 pom（实证不含目标链；若需触碰即停止申报）、ci.yml、docker-compose*、scripts/verify/*、web、sql、ADR
- token 29 项只增不减；台账纯追加；第 37 次 CI 全绿为外部终验
