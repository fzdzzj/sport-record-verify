# TASK-247 台账（handoff）

> **TASK**：E5 生产部署姿态演练——CORS 动态多域名注入、跨域 Cookie 凭证透传与 TLS 终止协同演练（候选 H2，MEDIUM 档）  
> **开工基线**：待开工提交确定  
> **方案**：候选 H2（生产部署姿态演练）  
> **状态**：开工在途  

## 1. 实施摘要

| 判别式 | 归属 | 状态 | 说明 |
| --- | --- | --- | --- |
| R1 CORS 生产多域名动态注入 | gateway-service | 待实施 | 环境变量注入多域名，白名单命中允许、非法拒绝 |
| R2 TLS 终止与协议感知 | gateway-service | 待实施 | forward-headers-strategy: framework 与 X-Forwarded-* 识别 |
| R3 跨域 Cookie 凭证透传 | gateway-service | 待实施 | withCredentials 凭证与 Cookie 无损透传下游 |
| R4 Compose 环境变量与文档对齐 | compose + docs | 待实施 | compose.services.yml 注入占位与 README 生产指引 |

## 2. 修改白名单

严格限定在 `spec.md` §2 的 7 个文件内。

## 3. 门禁验证记录

- 后端单测：待回填
- 前端类型：待回填
- 前端单测：待回填
- 契约门禁：待回填
