# 提案：存量 CRITICAL 依赖升级治理专项（harden-dep-critical-pin / TASK-196）

## 背景与问题

TASK-195 落地 Trivy 依赖扫描门（分支 B），`.trivyignore` 登记 13 项存量 CRITICAL CVE 待专项治理。侦察报告（TASK-196 前置，全程只读留证）关键结论：

1. **重大纠偏**：`.trivyignore` 中 fastjson:1.2.83 标注为「common 模块引用」有误——全仓 0 处业务代码引用 fastjson（业务 JSON 统一 Jackson），真实链路为 rocketmq-spring-boot-starter:2.3.1（4 服务）与 sentinel-transport-common:1.8.6（gateway）传递引入
2. 13 项修复版本闭环：Tomcat 10.1.58+（7 项）、netty-handler 4.1.137.Final+（1 项）、Spring Framework 6.1.29+（webmvc/webflux 2 项）、BouncyCastle 1.85+（2 项）、fastjson 1.x EOL 无补丁（排除或桥接）
3. **pom-only 100% 可行**：零业务代码改动即可全消 13 项；Boot 3.2.4 基线不动完全合规 ADR-0001（勿升 Boot 3.3 硬约束）

## 方案（方案 A：精准版本 Pinning + 传递排除）

Boot 3.2.4 / Cloud 2023.0.1 / SCA 2023.0.1.0 三 BOM 零改动，仅在父 pom `<dependencyManagement>`（import BOM 之前，First-Declaration-Wins）精准 Pin 补丁版本 + 传递排除：

- **阶段 1（消 10 项）**：Pin spring-framework-bom:6.1.29（webmvc/webflux 2 项）+ tomcat-embed-core:10.1.58（7 项）+ netty-bom:4.1.137.Final（1 项，BOM 统一锁定防版本分裂）
- **阶段 2（消 2 项）**：Pin bcprov-jdk18on:1.85（全 6 服务，重点核验 JWT/BCrypt/Feign 链）
- **阶段 3（消 1 项）**：fastjson 排除——rocketmq starter（user/record/verify/leaderboard 模块 pom exclusions）+ sentinel starter（gateway pom exclusions）；若运行时类缺失则引入 fastjson2:fastjson-to-fastjson2:2.0.53 桥接兜底（本仓未启用 RocketMQ ACL，无 ACL 触发路径）
- 每阶段收缩 `.trivyignore`（仅删除已实测修复的登记行，文件保留含头部规则，**ci.yml 零触碰**）；三阶段后 `.trivyignore` 清至头注释空壳，全仓 0 存量已知 CRITICAL

## 方案取舍（已排除）

- **方案 B（Boot 3.3.x）**：违反 ADR-0001 硬约束；SCA 2023.0.1.0 矩阵仅验证 Boot 3.2.x；3.3.x 早期版本依赖仍落后需二次 Pin；不解决 fastjson
- **方案 C（Boot 3.4/3.5.x）**：Reactor/Framework 6.2 契约重构对 ShardingSphere 5.4.1、Sentinel 1.8.6、Nacos 适配器存在大量断点；依然不解决 fastjson

## 影响面与边界

- 改动面：C-01 恰「父 pom.xml + gateway/user/record/verify/leaderboard 五模块 pom（fastjson exclusions）+ `.trivyignore`」；C-02 台账五件
- 零触碰：业务源码与测试源码 / ci.yml / docker-compose* / scripts/verify/* / web / sql / common 与 mapmatch 模块 pom（实证不含目标链，若需触碰即停止申报）/ ADR 文档
- 回归底线：offline 569（36/44/127/137/149/64/12）逐位不变 + static 双模块（record + leaderboard）rc=0 + 6 镜像构建全绿 + Trivy 门禁跑次全绿
- 零收益百分比 claim；升级消减计数为机制性事实登记
