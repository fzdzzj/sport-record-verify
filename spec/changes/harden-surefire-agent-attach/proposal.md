# surefire agent attach 加固（TASK-181）

## Why

第 22 次外部门槛 CI（run `37736029636`，head `d44c29c`）两次执行均失败于 gateway-service：Mockito 5 inline mock maker 初始化失败（`Could not self-attach to current VM using external process`、`NPE in thread "Attach Listener"`，级联 `StackWalkerStrategy` NoClassDefFound 假红，Failures 恒 0）。同 commit 本地 offline 450 全绿、3 小时前同镜像 run 21 全绿、本批改动全在 record-service + docs（gateway 在 reactor 中先于 record 执行，未触及改动面）——排除代码回归，判定为 runner 机群条件漂移触发仓库潜伏机制：JDK 21 默认禁用进程内 self-attach，ByteBuddy agent 挂载间歇性落入「外部子进程 attach」兜底路径，该路径在部分 runner 上不稳定且以污染 Attach Listener 线程的方式失败。

## What Changes

- root `pom.xml`（[pluginManagement](../../../pom.xml)）新增 surefire 插件显式配置：`<argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>`——`@{argLine}` 晚绑定保留既有 JaCoCo prepare-agent 注入，`-Djdk.attach.allowAttachSelf=true` 使 ByteBuddy agent 走进程内确定性 attach 路径，消除对外部子进程兜底的依赖。surefire 版本不锁定（沿现状继承 3.6.0），非新 Maven 插件。
- 无测试/代码/配置语义变更：测试数 450 不变，离线基线本地可验证；不触碰 jacoco 插件与 enforcer。

## Impact

- 影响面：全部七模块 surefire JVM 启动参数（一行）；gateway 等含 Mockito inline mock 的模块收益最直接。
- 验收：本地 offline 450 恒等 + 静态门不增；无本地可复现的 flaky（本地 Windows attach 行为不同），修复有效性由第 23 次外部门槛 CI 绿作外部终验。
- 台账登记第 22 次门槛红×2 的根因归档与本修复的因果链；不声称「已修复 CI 基础设施」，只登记「已消除仓库侧该类失败的触发机制」。
