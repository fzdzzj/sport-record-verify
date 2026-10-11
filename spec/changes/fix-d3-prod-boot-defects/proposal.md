# 提案：四服务生产启动缺陷修复 v2（fix-d3-prod-boot-defects / TASK-200 改派）

本版为 round-2 改派修订版。round-1 执行侧完成 D3 缺陷A/缺陷B 全部修复与门禁验证（record/verify/leaderboard 三服务生产实起 liveness UP、offline 609 逐位、六探针 CI 等效、静态双模块全绿），user 冒烟暴露被缺陷A 掩蔽的第三个既有隐藏 bean 缺陷（缺陷C：NotificationService 双构造器无构造器级注入标注），触发停止条件 §3.1/§3.5 零提交停工（工作区恰 9 文件未提交自洽）。指导侧裁决：不回退、不另立课题，改派 v2 扩权（白名单 9→10 文件）——D3 使命「四服务生产实起全绿」未竟，拆课题致两任务交叉依赖与提交序混乱；round-1 九文件改动集自洽且全门禁绿，回退浪费已验工作。

## 背景与问题（round-1 后新增缺陷C 归因）

- **缺陷A（user，round-1 已修复）**：`StompConnectAuthInterceptor` 无 bean 注册致 WS 急切装配失败——已加 @Component；user boot 越过 WS 装配期（旧 NoSuchBeanDefinition 签名消失）即反转实证。
- **缺陷B（四服务，round-1 已修复）**：rocketmq 5.1.4 链 fastjson 类缺失——已回补 fastjson 2.0.65 runtime；record/verify/leaderboard 生产实起 liveness UP 即反转实证（leaderboard 正是完整 D3-B 失败栈实证档）。
- **缺陷C（user，round-1 冒烟新暴露）**：`NotificationService`（TASK-182/187）字节码双构造器——@RequiredArgsConstructor 生成 1 参构造 + TASK-187 兼容期显式 2 参构造，@Autowired 仅字段级（required=false），无任何构造器级标注。Spring 多构造器无标注回退无参构造，该类无无参构造 → `NoSuchMethodException <init>()` 启动失败，失败链 friendController → friendService → notificationService。潜伏机理：单测手工 new 不经 Spring、探针 lazy-initialization 懒加载业务 bean 永不创建、生产 boot 此前死在更早 WS 装配期（被缺陷A 掩蔽）——此类缺陷只有生产实起能暴露。

## 修复方案（v2 增量）

- **缺陷C（最小修复）**：显式 2 参构造（mapper + preferenceService）加构造器级 @Autowired（+1 行，import 既有）——Spring 唯一标注构造器择定，两参数均可解析（NotificationMapper 为 MyBatis mapper bean、NotificationPreferenceService 为 @Service）；三个测试类手工 new 单参仍走 Lombok 1 参构造零破坏；字段级可选注入在构造后发生，语义不变。@RequiredArgsConstructor、字段注解、方法体、javadoc 业务语义零改动。
- round-1 九文件改动原样保留为 C-01 改动面（白名单 9→10 文件）；探针模板 mock 删除已定案保持（六探针全绿）。
- 不可知性预注册：user boot 越过 NotificationService 后是否暴露第四个隐藏缺陷不可预判（探针懒加载永远探不到业务 bean）——第四个新失败签名即停止条件，停手归因回报。

## 验收核心

四服务生产 jar 实起全绿（D3 复现路径反转，liveness UP，基于最终 10 文件态全量重跑）；六探针 CI 等效全绿；offline 609 逐位不变；静态双模块 rc=0；契约门双态；token 2030 只增不减；CI 6 镜像构建 + Trivy 收缩态扫描全绿（fastjson 2.0.65 不得触发 CRITICAL，否则停止条件）。
