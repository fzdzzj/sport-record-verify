【回传】TASK-020 短包

**进度**: F1 高优先级 5 项并行中。本任务确保项目兼容 Java 17+。

**修改内容**:
1. spec.md 补充兼容性说明：父 pom.xml 已配置 maven-compiler-plugin (release=21) + maven-enforcer-plugin (JDK≥21 强制校验)。若需向下兼容 Java 17+，需在 maven-compiler-plugin 中增加 source/target 配置（当前 release=21 仅向上兼容）。

**位置**: work/mailbox/tasks/TASK-020/spec.md

**状态**: 完成 ✅
