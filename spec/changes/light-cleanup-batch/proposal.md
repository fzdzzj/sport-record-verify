## Proposal: light-cleanup-batch (TASK-226, H3)

### Plan (LIGHT: 沿 TASK-212 先例，零功能变更)
- 清偿 TASK-221 留痕-1：RecordControllerTest.java 补末尾换行（保基线 CRLF）。
- README 陈旧表述核对与订正（web/README.md 及根 README，recon 定清单；仅文档订正）。
- 静态基线偏差清单化：user-service/api 模块既有 Checkstyle/PMD 违规以台账登记固化（不修码）。
- BACKLOG 推荐序行刷新（F 系清空后）。

### Non-Goals
任何功能/测试逻辑变更、静态违规修复（仅登记）、sql/pom/scripts 触碰。
