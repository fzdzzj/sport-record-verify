【回传】TASK-024 spec.md + handoff.md

spec: work/mailbox/tasks/TASK-024/spec.md  
只改：spec.md（补充 CacheLoader 实现）、handoff.md（回传短包）  
当前进度：F1 高优先级 5 项并行中。本任务在 TASK-002 基础上增加缓存预热和穿透保护。  
完成要求：修改 spec.md 补充 CacheLoader 实现，写 handoff.md 回传短包。  
不准猜测：若 Caffeine 未集成，注释掉"需要修改"部分并说明"需先完成 TASK-002"。  

Caffeine 依赖检查：已集成（pom.xml:102-106），无需额外添加。
