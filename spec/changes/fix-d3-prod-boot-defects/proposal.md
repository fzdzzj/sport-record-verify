# 提案：四服务生产启动缺陷修复 v3（fix-d3-prod-boot-defects / TASK-200 改派）

本版为 round-3 改派修订版。round-2 完成缺陷C 修复（NotificationService 显式 2 参构造加构造器级 @Autowired，恰 +1 行），user 生产实起实证 notificationService bean 创建成功（失败点前移），随即暴露第四个新失败签名——缺陷D：NotificationController 与缺陷C 逐字同构（@RequiredArgsConstructor + 显式 2 参构造 + 仅字段级 @Autowired），触发 v2 停止条件 §3.1 零提交停工（工作区恰 10 文件未提交自洽）。指导侧裁决：扩权 v3 普查式根治——不再逐 bean 改派，以同构模式全仓普查定界，一次性清零。

## 背景与问题（round-2 后新增缺陷D 归因与普查实证）

- **缺陷A/B（round-1 已修复）**：拦截器 @Component 已加（user 越过 WS 装配期）；fastjson 2.0.65 runtime 已回补（record/verify/leaderboard 实起 liveness UP）。
- **缺陷C（round-2 已修复）**：NotificationService 双构造器无构造器级标注——显式 2 参构造已加 @Autowired（+1 行），user 实起实证 bean 创建成功，失败点从 notificationService 前移到 notificationController。
- **缺陷D（round-2 冒烟新暴露）**：`NotificationController`（user-service/controller）与缺陷C 逐字同构——@RequiredArgsConstructor 生成 1 参构造（final notificationService）+ TASK-187 兼容期显式 2 参构造（notificationService + notificationPreferenceService），@Autowired 仅字段级（required=false），无构造器级标注。Spring 双构造器无标注回退无参构造 → `NoSuchMethodException: NotificationController.<init>()` 启动失败。
- **普查实证（指导侧亲核，双窗口一致）**：全仓 `@RequiredArgsConstructor` + 显式构造器同构模式恰 2 文件（NotificationService 已修 + NotificationController 待修），4000/8000 字符双窗口扫描结果一致，record/verify/leaderboard/gateway/mapmatch 零命中——与三服务 round-1 实起全绿自洽。同构缺陷类经本轮修复后全仓清零。

## 修复方案（v3 增量）

- **缺陷D（唯一新增改动）**：NotificationController 显式 2 参构造加构造器级 @Autowired（+1 行，import 已在文件头）——与缺陷C 同范式；修复参数均可解析（NotificationService 为 @Service bean 且 round-2 实证创建成功、NotificationPreferenceService 为 @Service）。
- **禁删构造器**：NotificationControllerTest 存在唯一 2 参手工 new 调用方（`new NotificationController(service, preferenceService)`），删除显式构造器即破坏测试——修复必须为加注解范式，构造器签名不变，调用方零破坏。
- round-1 九文件 + round-2 NotificationService.java 原样保留为 C-01 改动面（白名单 10→11 文件）。
- 不可知性预注册：同构缺陷类已全仓清零，user boot 越过 NotificationController 后若再暴露**第五个新失败签名**（必然为新型缺陷，非同构、非 D3-A/B/C/D 已修签名）→ 停止条件，停手归因回报。

## 验收核心

四服务生产 jar 实起全绿（D3 复现路径反转，liveness UP，基于最终 11 文件态全量重跑）；六探针 CI 等效全绿；offline 609 逐位不变；静态双模块 rc=0；契约门双态；token 2030 只增不减；CI 6 镜像构建 + Trivy 收缩态扫描全绿（fastjson 2.0.65 不得触发 CRITICAL，否则停止条件）。
