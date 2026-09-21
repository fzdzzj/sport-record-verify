# 规范：运动记录真实性校验系统（sport-record-verify）

> 首个能力域规范基线，由变更提案 `spec/changes/archive/add-microservice-skeleton/` 落地生成。
> 校验引擎、好友、点赞、排行榜等业务需求在后续变更中分别以 ADDED 需求补充。

## 本规范已归档以下提案

- add-verify-engine（校验引擎）
- add-friend-module（好友）
- add-like-module（点赞）
- add-leaderboard-module（榜单）
- add-load-test-report（压测）
- add-observability（可观测性）
- add-rule-grayscale（规则灰度）
- add-leaderboard-service（独立榜单服务）
- add-jwt-auth（鉴权）
- add-mapmatch-service（空间匹配）
- add-admin-rbac（治理面鉴权）
- add-sport-type-threshold（阈值分类型）
- add-login-lockout（账号锁定）
- add-two-level-cache（规则二级缓存）
- add-request-validation（入参校验与 HTTP 错误契约）
- add-sentinel-dynamic-rules（网关流控动态数据源）
- add-resilience-hardening（Feign 容错与内部接口凭证）
- add-request-tracing（请求贯穿标识）
- add-controlled-verify-entrypoint（统一验收入口与门槛接线）
- update-spec-module-enum（规格模块枚举补正）
- add-web-console-scaffold（Web 控制台工程脚手架）
- add-web-auth-session（Web 控制台登录会话）
- add-web-admin-console（Web 治理面控制台）
- add-web-record-console（Web 业务控制台）
- add-gateway-browser-cors（网关浏览器跨域）
- add-db-migration-entrypoint（存量迁移入口与 schema 漂移可观测）
- add-mailbox-contract-check（派发—回传契约两件套与清单比对）
- add-transaction-boundary-audit（写路径事务边界）
- add-perf-demo-innodb-flush（演示环境可选刷盘）
- add-perf-g1-pause-target（JVM 停顿目标可选）
- add-perf-mq-publish-async（提交事件异步发布）
- add-perf-submit-aggregation-gate（跨请求提交聚合须先复测）

各提案的 spec-delta 中 ADDED 需求已全部合并进本规范，MODIFIED 需求按规则处理（见「服务划分」分组与「变更历史」）。

## 工程结构

### Requirement: 多模块工程结构

WHEN 工程被构建,
系统 SHALL 产出父工程与 8 个可编译模块（common、api、gateway-service、user-service、record-service、verify-service、leaderboard-service、mapmatch-service），并 SHALL 通过统一验收入口执行全量构建。任何需要完整反应堆的消费者（含容器镜像构建）SHALL 以仓库根为上下文，使父工程与全部被声明模块可见。

#### Scenario: 全量构建成功

GIVEN 本地已安装 JDK 21 与 Maven
WHEN 通过统一验收入口执行全量构建
THEN 所有模块编译打包成功
AND 无快照依赖缺失报错
AND 构建前已打印本次生效的依赖来源

#### Scenario: 镜像构建可见完整反应堆

GIVEN 服务镜像的构建阶段需要解析父工程声明的全部模块
WHEN 以仓库根为上下文构建该镜像
THEN 父工程与被依赖模块均在构建上下文内
AND 不再出现子模块缺失导致的构建失败

#### Scenario: 验收路径不承诺安装到本地仓

GIVEN 外部工程希望从本仓获取快照构件
WHEN 仅按统一验收入口执行全量验收
THEN 产物落在各模块 target 目录
AND 入口不把构件安装进调用方的本地 Maven 仓库
AND 确需安装的场景必须显式另行执行，不属验收路径的承诺

#### Scenario: 版本不匹配被拦截

GIVEN 本地 JDK 低于 21
WHEN 执行构建
THEN 编译失败
AND 错误信息明确提示 JDK 版本要求

### Requirement: 依赖版本集中锁定

WHERE 任一子模块声明依赖,
系统 SHALL 由父工程 dependencyManagement 统一管理版本，子模块 SHALL 不重复声明版本号。

#### Scenario: 子模块引用统一版本

GIVEN 子模块声明 mybatis-plus 依赖且未写版本号
WHEN 构建解析依赖
THEN 版本继承父工程锁定值
AND 与官方兼容矩阵一致

#### Scenario: 越界版本被拒绝

GIVEN 子模块引入与锁定矩阵不兼容的 Boot 3.3
WHEN 构建解析依赖
THEN 依赖树出现与 SCA 2023 分支不兼容冲突
AND 由版本矩阵约束阻止升级

### Requirement: 统一响应与错误码

WHEN 任一服务返回结果,
系统 SHALL 使用统一 `Result<T>` 包装，失败时 SHALL 返回结构化错误码与信息。

#### Scenario: 成功响应包装

GIVEN 服务处理成功
WHEN 返回结果
THEN 响应体为 `{code:0, message:"ok", data:<payload>}`

#### Scenario: 业务异常

GIVEN 服务抛出业务异常
WHEN 全局异常处理器拦截
THEN 返回对应错误码与可读信息
AND 不泄漏堆栈细节

#### Scenario: 入参校验失败

GIVEN 客户端提交非法入参（DTO 校验违规 / 畸形 JSON / 参数类型不匹配 / 缺必填查询参数）
WHEN 控制器层触发校验
THEN 返回 HTTP 400 与结构化错误体 `{code:400, message:<字段名 + 提示>}`
AND 不落入服务端 500

#### Scenario: 路径与方法契约

GIVEN 请求路径存在但方法不符，或路径不存在
WHEN 全局异常处理器拦截
THEN 分别返回 HTTP 405 与 404 及结构化错误体

### Requirement: 服务间 Feign 契约

WHEN 服务间发生调用,
系统 SHALL 通过 api 模块定义的 Feign 接口与 DTO 完成，接口定义与实现 SHALL 分离。

#### Scenario: 跨服务探活

GIVEN verify-service 需要调用 record-service 的健康端点
WHEN verify-service 发起 Feign 调用
THEN 请求经 api 模块接口定义路由
AND 返回结果与 DTO 契约一致

#### Scenario: 契约漂移

GIVEN api 模块 DTO 字段与实现不一致
WHEN 编译或运行时反序列化
THEN 编译失败或反序列化报错
AND 提示契约不一致

### Requirement: Feign 统一默认超时

WHEN 服务经 OpenFeign 发起跨服务调用,
系统 SHALL 使用全局默认连接超时 1000ms 与读超时 3000ms，且允许按客户端名覆盖；SHALL NOT 依赖 Feign 默认 10s/60s 作为未配置服务的超时。

#### Scenario: 默认超时生效

GIVEN 消费方未为某 Feign 客户端单独配置超时
WHEN 发起该客户端调用
THEN 使用 default connect-timeout=1000 与 read-timeout=3000

#### Scenario: 可按服务覆盖

GIVEN 已为某客户端配置更短或更长超时
WHEN 发起该客户端调用
THEN 以该客户端覆盖值为准

### Requirement: Feign 降级决策显式化

WHEN 被依赖服务不可用（连接拒绝 / 超时 / 熔断 OPEN）,
系统 SHALL 按契约语义给出明确业务错误或约定降级结果，且 SHALL NOT 以未处理异常对外返回 500 堆栈；对不可软降级的契约 SHALL 抛出业务异常以驱动重试或 DLQ，禁止假装成功。

#### Scenario: 好友榜 user-service 不可用返回空榜

GIVEN leaderboard 查询 type=friend 且 user-service 不可用
WHEN 拉取好友列表失败
THEN 返回空榜（不把总榜非好友当作好友展示）
AND 记录降级 warn 日志

#### Scenario: verify 拉轨迹 record-service 不可用

GIVEN verify 判定需要 RecordApi.getRecord/listPoints
WHEN record-service 不可用
THEN 抛出 RECORD_SERVICE_UNAVAILABLE（4007）
AND 不写入成功判定
AND 消息消费可重试或进入 DLQ

#### Scenario: AuthApi 不可软降级

GIVEN 经 AuthApi 注册/登录/刷新
WHEN user-service 不可用
THEN 抛出 USER_SERVICE_UNAVAILABLE（4006）
AND 不返回伪造 token 或伪造成功

### Requirement: 服务注册与配置中心

WHEN 任一服务启动,
系统 SHALL 注册到 Nacos 注册中心并从 Nacos 配置中心拉取配置。

#### Scenario: 正常注册

GIVEN Nacos 已启动
WHEN 服务启动完成
THEN Nacos 控制台可见该服务实例
AND 服务成功拉取远程配置

#### Scenario: 注册中心不可用

GIVEN Nacos 未启动
WHEN 服务启动
THEN 启动失败或持续重试注册
AND 日志明确指向 Nacos 连接失败

### Requirement: 中间件一键编排

WHEN 开发者执行 `docker compose up`,
系统 SHALL 一键启动 Nacos、MySQL×3、Redis、RocketMQ，并保证启动依赖顺序与健康就绪。

#### Scenario: 一键起全部中间件

GIVEN 已安装 Docker
WHEN 执行 `docker compose up`
THEN 各中间件按依赖顺序启动
AND 健康检查全部就绪

#### Scenario: 依赖未就绪被等待

GIVEN MySQL 尚未健康
WHEN 依赖 MySQL 的容器启动
THEN 容器等待 MySQL 健康检查通过后再启动

### Requirement: 数据库初始化

WHEN 首次启动各服务,
系统 SHALL 执行审批版 §6 建表脚本，初始化 user_db / record_db / verify_db 中的全部表。
首次初始化之后的结构变更 SHALL 走存量迁移入口，SHALL NOT 假定再次启动容器会自动补列。

#### Scenario: 首次建表成功

GIVEN 三个库为空
WHEN 执行建表脚本
THEN 全部表创建成功
AND 字段与审批版 §6 定义一致

#### Scenario: 幂等重放

GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

#### Scenario: 已有数据卷不自动补列

GIVEN MySQL 数据卷已经完成过首次初始化
WHEN 再次执行 `docker compose up`
THEN 不会再次执行 `docker-entrypoint-initdb.d` 中的建表脚本
AND 后续结构变更须经存量迁移入口执行

### Requirement: 存量库迁移入口

WHEN 开发者对已初始化的 MySQL 数据卷执行存量迁移入口,
系统 SHALL 按确定顺序幂等执行 `sql/migrations` 下的全部脚本，使存量库结构与当前代码所需 schema 对齐；SHALL NOT 依赖 `docker-entrypoint-initdb.d` 在非首次启动时补齐列或索引；SHALL NOT 把首次建表脚本当作存量升级脚本执行。

#### Scenario: 缺列的存量库被补齐

GIVEN MySQL 数据卷已初始化
AND `user_db.user` 缺少当前代码所需的 `role` 列
WHEN 执行存量迁移入口
THEN `user.role` 列存在
AND 脚本以成功状态退出

#### Scenario: 已对齐的库幂等重跑

GIVEN 存量库结构已与当前代码对齐
WHEN 再次执行存量迁移入口
THEN 脚本成功退出
AND 不报错
AND 不破坏既有数据与结构

#### Scenario: 中间件未就绪则失败

GIVEN MySQL 容器不存在或不健康
WHEN 执行存量迁移入口
THEN 脚本以非 0 状态退出
AND 不假装迁移成功

#### Scenario: 不执行首次建表脚本

GIVEN 存量迁移入口被调用
WHEN 扫描待执行脚本
THEN 仅执行 `sql/migrations` 下的脚本
AND 不执行 `sql/01-user-db.sql` 等首次建表脚本

### Requirement: 网关统一入口

WHEN 客户端发起请求,
系统 SHALL 经 gateway-service 路由到目标服务，不直接暴露服务实例地址。

#### Scenario: 路由成功

GIVEN gateway 与目标服务均已启动
WHEN 客户端请求网关路径 `/record/**`
THEN 请求被转发到 record-service
AND 返回目标服务响应

#### Scenario: 目标不可达

GIVEN 目标服务未启动
WHEN 客户端请求对应网关路径
THEN 网关返回 502/503
AND 错误不泄漏内部拓扑

### Requirement: 网关浏览器跨域

WHEN 浏览器从已配置的前端来源直接访问网关,
系统 SHALL 对允许的来源响应 CORS 预检与跨域响应头，并允许 `Authorization` 请求头。
系统 SHALL NOT 在携带凭证的场景使用通配来源 `*`。
开发服务器反向代理 SHALL 仍可在不依赖本能力的情况下工作。

#### Scenario: 本地前端来源通过预检

GIVEN 请求 Origin 为本地 Vite 开发端口且该来源在允许列表中
WHEN 浏览器发出 OPTIONS 预检
THEN 网关允许该来源
AND 允许 Authorization 头

#### Scenario: 未允许的来源不按通配放行

GIVEN 请求 Origin 不在允许列表
WHEN 发出跨域请求
THEN 不按允许所有来源处理
AND 不把该拒绝当成鉴权失败码 1001

#### Scenario: 开发代理不强制依赖 CORS

GIVEN 前端开发服务器将请求反向代理到网关
WHEN 页面调用业务接口
THEN 请求可作为同源代理访问网关
AND 不要求必须先启用跨域才能本地开发

## 验收与门槛

### Requirement: 统一验收入口

WHEN 任一会话、开发者或持续集成需要判定改动是否通过,
系统 SHALL 通过仓内唯一受版本控制的验收入口取得完整构建命令，该入口 SHALL 是命令拼写的唯一定义处；README、CI 与验收记录 SHALL 引用该入口而不是各自复述命令。入口 SHALL 在所请求阶段前执行清理，使结论不复用上一轮构建产物。SHALL NOT 存在第二处需要人工同步的验收命令写法。

#### Scenario: 通过入口执行全量验收

GIVEN 本地已安装 JDK 21 与 Maven
WHEN 执行统一验收入口并指定 verify 阶段
THEN 入口打印本次生效的依赖来源与命令全文
AND 随后执行构建与测试并透传其退出码

#### Scenario: 上一轮产物不被复用

GIVEN 工作树中存在上一轮构建留下的编译产物
WHEN 通过统一入口执行任一阶段
THEN 本次执行先完成清理再进入所请求阶段
AND 结论不依赖上一轮遗留产物

#### Scenario: 复述命令被消除

GIVEN 本变更已归档
WHEN 检索仓库中的验收命令定义
THEN 根 README 与 `.github/workflows/ci.yml` 均引用统一入口
AND 不存在与入口不一致的第二份参数组合

### Requirement: 依赖来源可判定

WHEN 统一验收入口以离线模式执行,
系统 SHALL 在实际调用构建工具之前判定其声明的本地仓库目录是否真实存在，判定 SHALL 仅依赖对 settings 文件的文本解析而不引入额外插件；来源不可判定时 SHALL 以区别于"用例失败"的独立退出码失败并打印期望与实际路径。WHEN 以在线模式执行, 系统 SHALL 不携带任何指向本机私有配置的开关。

#### Scenario: 离线仓在位时正常执行

GIVEN settings 文件存在且其声明的本地仓库目录存在
WHEN 以离线模式执行入口
THEN 入口报告依赖来源为仓内离线仓库
AND 构建按离线方式执行

#### Scenario: 离线仓缺失时不假绿

GIVEN settings 文件存在但其声明的本地仓库目录不存在
WHEN 以离线模式执行入口
THEN 入口以独立退出码失败
AND 失败信号指向依赖来源不可判定而非测试失败
AND 不产出"通过"结论

#### Scenario: 在线模式为 CI 权威口径

GIVEN 无本地私有配置与离线仓库的持续集成环境
WHEN 以在线模式执行入口
THEN 构建与测试结论作为对外权威结论
AND 与离线模式的差异被记录为依赖集差异而非用例结论

### Requirement: 交付面最小判别式

WHEN 推送或合并请求触发门槛,
系统 SHALL 校验服务编排文件组合可被解析，并 SHALL 至少构建一份服务镜像以覆盖容器交付路径；SHALL NOT 让镜像与编排文件处于"改动后无任何机械信号"的状态。编排文件对环境文件的依赖 SHALL NOT 成为门槛在干净检出上失败的原因。

#### Scenario: 编排配置不可解析时门槛失败

GIVEN 编排文件存在引用错误或端口/服务定义不合法
WHEN 门槛执行编排配置校验
THEN 该步骤以非 0 状态失败
AND 失败信息指向具体编排文件

#### Scenario: 反应堆不完整被构建步骤捕获

GIVEN 某份 Dockerfile 只复制部分模块而父工程声明全部模块
WHEN 门槛以仓库根为上下文构建代表镜像
THEN 构建步骤失败并暴露缺失的子模块
AND 该失败不被静默跳过

#### Scenario: 环境文件不入库时门槛仍可解析

GIVEN 编排文件声明的 `.env` 按约定不随仓库发布
WHEN 门槛在干净检出上执行编排解析与镜像构建
THEN 门槛先自备一份占位环境文件再执行解析
AND 不因缺失该文件而把交付面判为不可验证

#### Scenario: 同批其余镜像有确定判据

GIVEN 多份 Dockerfile 在同一批被修正
WHEN 逐份执行本地构建
THEN 每份各有一条记录在案的成功或失败结果
AND 未实跑的份被显式标注为未验证

### Requirement: 真库端到端测试有确定路径

WHERE 存在需要真实数据库的端到端测试,
系统 SHALL 提供一条被文档指路的定向执行入口，并 SHALL 声明其运行前提（所需环境变量与准备库步骤）；当执行前提缺失而被跳过时, 系统 SHALL 把该测试记为未覆盖, SHALL NOT 让其缺席被表述为通过。

#### Scenario: 前提齐备时真库执行

GIVEN 准备库已按文档建立且所需环境变量齐备
WHEN 通过统一入口的端到端分支执行该测试
THEN 注解 SQL 在真实引擎上被解析与绑定
AND 断言通过

#### Scenario: 前提缺失时按跳过处理

GIVEN 缺少任一所需环境变量
WHEN 执行该端到端分支
THEN 测试被跳过而非失败
AND 验收记录标注该测试未覆盖
AND 不据此声称真库路径已验证

### Requirement: 前端改动有门槛判据

WHEN 前端源码或依赖声明发生改动,
系统 SHALL 在门槛上执行类型检查与生产构建，并 SHALL 以锁文件一致的严格安装方式暴露依赖漂移；构建过程会重写的已跟踪生成物 SHALL 由门槛校验其与提交内容一致；前端入口文档 SHALL 指路到这几条命令与它们的处置方式。

#### Scenario: 类型错误使门槛失败

GIVEN 前端类型检查会报错的改动被提交
WHEN 门槛执行前端检查
THEN 该步骤失败并输出具体类型错误
AND 改动不能在无信号状态下合入

#### Scenario: 锁文件缺失或漂移被捕获

GIVEN 依赖声明与已提交锁文件不一致或锁文件缺失
WHEN 门槛以严格方式安装依赖
THEN 安装步骤失败
AND 失败信号指向锁文件而非业务代码

#### Scenario: 生成物与提交不同步被捕获

GIVEN 路由页面增删后提交的生成物未按新路由重新生成
WHEN 门槛执行前端构建后比对该生成物与提交内容
THEN 该步骤失败并指向该生成物文件
AND 处置方式（重新生成并一并提交）在前端入口文档中可读

### Requirement: 公开口径自检覆盖全部发布载体

WHEN 门槛执行公开口径自检,
系统 SHALL 覆盖全部随仓库公开发布的文本载体（含源码注释、配置与仓库元文件），并 SHALL 显式排除仅承载该正则自身的文件、历史归档与已被忽略清单声明为内部的载体；SHALL NOT 以扩展名白名单取样而使不变量在未列出的载体上静默失效。

#### Scenario: 非文档载体命中被判失败

GIVEN 某个 tracked 源码注释含被禁止的内部口径措辞
WHEN 门槛执行公开口径自检
THEN 该步骤失败并定位到具体文件与行
AND 失败不被文件类型豁免

#### Scenario: 自检规则自身不误伤

GIVEN 门槛定义文件本身含有该匹配正则
WHEN 自检执行
THEN 该文件被排除且不产生命中
AND 排除理由在门槛文件内可读

### Requirement: 验收结论与修订绑定

WHEN 一次改动被判定为验收通过,
系统 SHALL 在验收记录中同时保留该结论所绑定的 commit 标识与其门槛来源（外部持续集成运行编号，或一次在线模式实跑结果）；若结论仅来自本地执行且当前修订未到达外部门槛, 记录 SHALL 显式标注该状态；外部门槛结论发生变化时, 记录 SHALL 同步更新而不留过期判断。SHALL NOT 以未绑定修订的数字或口头转述作为验收凭据。

#### Scenario: 仅本地绿时标注未达外部门槛

GIVEN 本地实跑通过但当前修订尚未推送到外部门槛
WHEN 写入验收记录
THEN 记录包含 commit 标识、本地实跑结论与"未达外部门槛"标注
AND 后续会话不会把该结论读成已过门槛

#### Scenario: 外部结论绑定运行编号

GIVEN 修订已到达外部门槛并产生运行记录
WHEN 写入验收记录
THEN 记录包含该运行的标识与结果状态
AND 结论可被复验到具体修订

#### Scenario: 推送需单独授权

GIVEN 收口流程要求让修订到达外部门槛
WHEN 准备执行推送
THEN 该动作作为需单独授权的外部写操作被显式确认
AND 未获授权时以在线模式本地实跑作为替代凭据并记录该替代

### Requirement: 登录路径 schema 漂移可观测

WHEN 执行冒烟前置检查,
系统 SHALL 以错误凭据调用登录接口，并按响应区分 schema 漂移、凭据错误、账号锁定与服务未就绪；SHALL NOT 把锁定或网关失败当成缺列，也 SHALL NOT 把 HTTP 500 或业务码 9999 当成通过。

#### Scenario: schema 可用时凭据错误返回 401 与 1001

GIVEN 网关与 user-service 已启动
AND `user` 表含当前代码所需列
AND 探测账号未被锁定
WHEN 使用错误密码调用登录接口
THEN HTTP 状态为 401
AND 业务码为 1001
AND 不返回 9999
AND 不返回 HTTP 500

#### Scenario: 缺 role 列时冒烟失败

GIVEN 存量库 `user` 表缺少 `role` 列
AND 探测账号未被锁定
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败信号为 HTTP 500 或业务码 9999

#### Scenario: 服务未就绪不判为缺列

GIVEN 网关或 user-service 不可达
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败原因指向服务未就绪（连接失败或 HTTP 502/503）
AND 不指向 schema 漂移

#### Scenario: 账号锁定不判为缺列也不判为通过

GIVEN 探测手机号处于登录锁定
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败原因指向账号锁定（HTTP 403 或业务码 1002）
AND 不指向 schema 漂移
AND 不视为 schema 健康

### Requirement: 派发—回传契约两件套完备

WHEN 台账中存在一个任务目录,
系统 SHALL 要求该目录同时包含任务契约文件与回传文件；包含回传却缺失契约的目录 SHALL 使校验失败。WHEN 一个目录仅包含契约文件而无回传文件, 系统 SHALL 将其识别为进行中任务，SHALL NOT 将其当作已回传，且当该进行中任务未被显式声明时 SHALL 使校验失败；WHEN 该进行中任务被显式声明, 系统 SHALL 将其列入待办并放行其余判定。系统 SHALL NOT 通过补写或修改既有回传记录来满足该判定。

#### Scenario: 两件套齐全正常通过

GIVEN 任务目录同时含契约文件与回传文件
WHEN 对该目录执行两件套校验
THEN 校验通过
AND 不产生失败信号

#### Scenario: 有回传无契约被拦截

GIVEN 任务目录含回传文件但缺失契约文件
WHEN 执行两件套校验
THEN 校验失败
AND 失败信号指向该异常组合

#### Scenario: 仅契约的进行中任务显式声明后列待办

GIVEN 任务目录仅含契约文件，且该任务被显式声明为进行中
WHEN 执行两件套校验
THEN 该任务被列入待办清单
AND 不因缺回传而使整体校验失败

#### Scenario: 未声明的进行中任务使校验失败

GIVEN 任务目录仅含契约文件，且未被显式声明为进行中
WHEN 执行两件套校验
THEN 整体校验失败
AND 失败信号指向该未声明的进行中任务

### Requirement: 回传只改清单与工作树比对

WHEN 一次回传声明了其只改文件清单,
系统 SHALL 将该清单与工作树相对开工基线的实际改动文件集进行比对，并 SHALL 排除开工前已登记的本机或工具残留载体；清单与实际改动集存在差异且二者有交叠（回传在途）时, 系统 SHALL 使校验失败，失败面覆盖清单多报与实际少报两种方向。WHEN 改动文件集来源不可判定, 系统 SHALL 以区别于契约失败的退出码失败，SHALL NOT 将其记为通过，也 SHALL NOT 将其记为用例失败。WHEN 回传清单与改动集无交叠, 系统 SHALL 视为已收口而不重审既有记录。系统 SHALL NOT 允许一次在途回传的清单与实际改动集不一致而仍通过校验。

#### Scenario: 在途回传清单与实际一致

GIVEN 一次在途回传的只改清单等于工作树相对基线的改动文件集
WHEN 对该回传执行清单比对
THEN 校验通过
AND 无失败信号

#### Scenario: 多报未改动文件被拦截

GIVEN 回传只改清单含工作树中实际未改动的文件，且改动集余量可为该任务在途
WHEN 执行清单比对
THEN 校验失败
AND 失败信号指向该多报文件

#### Scenario: 工作树存在未声明改动被拦截

GIVEN 工作树实际改动文件集含回传只改清单未声明的文件，且余量可为该任务在途
WHEN 执行清单比对
THEN 校验失败
AND 失败信号指向该未声明改动

#### Scenario: 改动集来源不可判定不记通过

GIVEN 无法取得工作树相对基线的改动文件集（非 git 上下文且未提供外部改动集，或命令失败）
WHEN 执行清单比对
THEN 以独立退出码指示来源不可判定
AND 不产出通过与用例失败两类结论

#### Scenario: 历史已收口不回传不重审

GIVEN 某任务的改动足迹已落入基线，与当前工作树改动集无交叠
WHEN 执行清单比对
THEN 该任务被视为已收口
AND 不因清单内容对既有记录产生重审失败

## 校验引擎

### Requirement: 轨迹分片存储

WHEN 一条运动记录的轨迹点被写入,
系统 SHALL 按 `user_id % 16` 路由到对应分片表 `track_point_0..15`，并 SHALL 先经 `sport_record → user_id` 解析分片键。

#### Scenario: 分片路由正确

GIVEN 用户 user_id=100（100%16=4）
WHEN 该用户提交轨迹点
THEN 轨迹点写入 `track_point_4`
AND 不落其他分片

#### Scenario: 记录本身不分片

GIVEN sport_record 未声明分片
WHEN 写入运动记录主表
THEN 数据落单表
AND 查询轨迹前先查 sport_record 得到 user_id 再路由

#### Scenario: 分片分页查询

GIVEN 轨迹点分布在多个分片
WHEN MyBatis-Plus 分页查询该用户轨迹
THEN 分页插件正确绑定 ShardingSphere 代理数据源
AND 返回跨分片合并的完整分页结果

### Requirement: 轨迹提交幂等

WHEN 客户端提交运动记录,
系统 SHALL 以 `request_id` 唯一识别，重复提交 SHALL 返回原结果而非重复入库。

#### Scenario: 首次提交成功

GIVEN 客户端携带新 request_id
WHEN 提交记录
THEN 记录落库，状态置 SUBMITTED
AND 进入校验流程

#### Scenario: 重复提交幂等

GIVEN 相同 request_id 已提交过
WHEN 再次提交相同 request_id
THEN 系统返回 3004 幂等冲突或原结果
AND 不产生第二条记录

### Requirement: 漂移预处理

WHEN 校验引擎处理轨迹点数组,
系统 SHALL 逐点计算瞬时速度，将 `v > V_DRIFT(默认20 m/s)` 或 `Δt < 0.1s` 的点标记为漂移并剔除，且 SHALL 保留原始数组用于审计。

#### Scenario: 漂移点剔除

GIVEN 轨迹含瞬时速度 25 m/s 的跳变点
WHEN 预处理器运行
THEN 该点被标记为漂移
AND 从有效点集剔除
AND 原始数组保留待审计

#### Scenario: 高漂移比例记软证据

GIVEN 漂移点占比 driftRatio > 30%
WHEN 预处理器汇总
THEN 记录软证据 PREPROCESS_SUSPICIOUS
AND 写入 preprocess 统计

#### Scenario: 低漂移不触发

GIVEN 漂移点占比 ≤ 30%
WHEN 预处理器汇总
THEN 不产生 PREPROCESS_SUSPICIOUS 软证据

### Requirement: 规则链判定（R1-R4）

WHEN 校验引擎执行规则链,
系统 SHALL 按序计算 R1 速度、R2 加速度、R3 停留、R4 距离一致性，并 SHALL 为每条命中规则记录级别（HARD/SOFT）与证据。

#### Scenario: 匀速刷里程触发 R1

GIVEN 滑动 10 点平均速度 >5.5 m/s 且持续 ≥10 点
WHEN R1 速度规则运行
THEN 命中 R1_SPEED
AND 级别 HARD
AND 证据含窗口均值与起止点序号

#### Scenario: 飞点拼接触发 R2

GIVEN 相邻有效点加速度 Δv/Δt >3 m/s² 出现 ≥3 次
WHEN R2 加速度规则运行
THEN 命中 R2_ACCEL
AND 级别 SOFT

#### Scenario: 原地抖动触发 R3

GIVEN 存在连续 ≥5min 位移 <5m 的段且段占比 >40% 总时长
WHEN R3 停留规则运行
THEN 命中 R3_STAY
AND 级别 HARD

#### Scenario: 折返刷里程触发 R4

GIVEN 累计轨迹距离/起终点直线距离 >3.0
WHEN R4 距离一致性规则运行
THEN 命中 R4_DISTANCE
AND 级别 SOFT

#### Scenario: 无命中

GIVEN 所有规则均未触发
WHEN 规则链运行完毕
THEN 无 rule_hits 命中记录

### Requirement: 判定聚合

WHEN 规则链执行完毕,
系统 SHALL 按「命中 HARD→REJECTED；仅 SOFT→默认 REJECTED(可配)；无命中→PASSED」聚合判定，并 SHALL 以 `score = 50 + 20×HARD数 + 10×SOFT数` 计算分数（0-100）。

#### Scenario: 命中 HARD 拒绝

GIVEN 命中至少一条 HARD 规则
WHEN 聚合判定
THEN verdict=REJECTED
AND score = 50 + 20×HARD数 + 10×SOFT数

#### Scenario: 仅 SOFT 默认拒绝

GIVEN 仅命中 SOFT 规则且未配置宽松策略
WHEN 聚合判定
THEN verdict=REJECTED

#### Scenario: 无命中通过

GIVEN 无任何规则命中
WHEN 聚合判定
THEN verdict=PASSED

#### Scenario: 证据 JSON 完整

GIVEN 判定完成
WHEN 落库 verification_result
THEN 证据 JSON 含 verdict/score/hits[rule+level+detail]/preprocess

### Requirement: 校验状态机

WHEN 校验与申诉流转,
系统 SHALL 遵循 SUBMITTED→VERIFYING→PASSED/REJECTED 与 REJECTED→APPEALING→RE_PASSED/RE_CONFIRMED，且 SHALL 以乐观锁 `UPDATE ... WHERE status AND version` 保证并发安全。

#### Scenario: 校验通过

GIVEN 记录处于 VERIFYING 且判定 verdict=PASSED
WHEN verify 回调
THEN 状态迁至 PASSED
AND 发 VERIFIED 事件

#### Scenario: 校验拒绝

GIVEN 记录处于 VERIFYING 且 verdict=REJECTED
WHEN verify 回调
THEN 状态迁至 REJECTED
AND 发 REJECTED 事件并保存证据

#### Scenario: 申诉提交

GIVEN 记录处于 REJECTED
WHEN 用户提交申诉
THEN 状态迁至 APPEALING
AND 建 appeal 单（record_id 唯一）

#### Scenario: 终判改判

GIVEN 记录处于 APPEALING 且 appeal 为 PENDING
WHEN 管理员复核通过
THEN 状态迁至 RE_PASSED
AND 发 VERIFIED 事件

#### Scenario: 终判维持拒绝

GIVEN 记录处于 APPEALING 且 appeal 为 PENDING
WHEN 管理员复核确认
THEN 状态迁至 RE_CONFIRMED
AND 发 REJECTED 事件

#### Scenario: 并发冲突

GIVEN 两个请求同时迁移同一记录
WHEN 乐观锁 WHERE status AND version 执行
THEN 仅一个影响行数为 1
AND 另一个影响 0 行，报 3003 或重试

### Requirement: 校验事件与幂等

WHEN 校验流程产生状态变化,
系统 SHALL 经 RocketMQ 发布 SUBMITTED/VERIFIED/REJECTED 事件，并 SHALL 以 eventId 去重保证消费幂等。

#### Scenario: 触发校验事件

GIVEN 记录提交进入 VERIFYING
WHEN 状态迁移完成
THEN 发布 SUBMITTED 事件（Tag 区分）
AND verify 消费后拉轨迹执行判定并回调

#### Scenario: 事件幂等消费

GIVEN 相同 eventId 的事件重复投递
WHEN 消费者处理
THEN SETNX 去重
AND 业务仅执行一次

#### Scenario: 失败进死信

GIVEN 消费失败达到重试阈值
WHEN 消费者无法处理
THEN 消息进入 record-verify-events-dlq
AND 可人工排查

### Requirement: 规则阈值可配置

WHEN 规则链读取阈值,
系统 SHALL 从 Nacos 配置 `verify.rules.*` 获取，且 SHALL 提供与审批版 §5.2 一致的默认值。

#### Scenario: 默认阈值生效

GIVEN Nacos 无覆盖配置
WHEN 规则链初始化
THEN 使用默认值 V_DRIFT=20、R1=5.5、R2=3、R3 段占比 40%、R4=3.0

#### Scenario: 覆盖阈值生效

GIVEN Nacos 配置 verify.rules.r1.speed=6.0
WHEN 规则链读取
THEN R1 阈值采用 6.0
AND 其他阈值采用默认值

### Requirement: 提交事件异步发布

WHEN 运动记录提交事务已提交,
系统 SHALL 异步发布 SUBMITTED 事件，SHALL NOT 在提交请求线程上同步等待消息中间件往返。
发布失败时系统 SHALL 仍走既有降级（直调校验或转人工），SHALL NOT 把已落库记录当成提交失败。

#### Scenario: 发送不阻塞提交线程

GIVEN 记录已落库且事务已提交
WHEN 发布 SUBMITTED 事件
THEN 提交请求路径不等待同步发送完成
AND 校验仍由事件或降级路径触发

#### Scenario: 异步发送失败仍降级

GIVEN SUBMITTED 异步发送失败
WHEN 失败回调发生
THEN 系统走直调校验或转人工
AND 不回滚已提交的记录

## 好友

### Requirement: 好友申请创建

WHEN 用户向另一用户发起好友申请,
系统 SHALL 创建 `status=PENDING` 的 friend_request 并返回申请单。

#### Scenario: 申请成功

GIVEN 用户 A(id=1001) 向用户 B(id=1002) 发起申请
AND A、B 之间无任何 PENDING 申请或既有关系
WHEN 提交 `POST /api/friends/requests {targetUserId:1002}`
THEN 创建 PENDING 申请单
AND 返回 `{id, fromUser:1001, toUser:1002, status:"PENDING"}`

#### Scenario: 目标用户不存在

GIVEN 目标用户 id 不存在
WHEN 提交好友申请
THEN 返回 2002（用户不存在）
AND 不创建申请单

### Requirement: 申请幂等去重

WHEN 用户发起好友申请,
系统 SHALL 识别同向重复申请、反向 PENDING 申请、既有关系，并 SHALL 返回 5001 或原申请单而非重复建单。

#### Scenario: 同向重复申请

GIVEN A→B 已存在 PENDING 申请
WHEN A 再次向 B 发起申请
THEN 返回 5001 或原申请单
AND 不产生第二条 PENDING 单

#### Scenario: 反向 PENDING 已存在

GIVEN B→A 已存在 PENDING 申请
WHEN A 向 B 发起申请
THEN 返回 5001（重复申请或已存在关系）

#### Scenario: 已存在关系

GIVEN A、B 已是好友（friendship 存在）
WHEN 任一方再次发起申请
THEN 返回 5001
AND 不创建申请单

### Requirement: 申请状态机

WHEN 好友申请发生流转,
系统 SHALL 遵循 PENDING→ACCEPTED/REJECTED/CANCELLED，且 SHALL 仅允许 PENDING 状态的申请流转。

#### Scenario: 同意申请

GIVEN 申请处于 PENDING
WHEN 目标用户执行 accept
THEN 申请状态迁至 ACCEPTED
AND 写入 friendship 关系

#### Scenario: 拒绝申请

GIVEN 申请处于 PENDING
WHEN 目标用户执行 reject
THEN 申请状态迁至 REJECTED
AND 不写入 friendship

#### Scenario: 非 PENDING 流转被拒

GIVEN 申请已处于 ACCEPTED/REJECTED
WHEN 再次执行 accept/reject
THEN 流转失败
AND 返回 5002（关系不存在）或业务异常

### Requirement: 好友关系规范化存储

WHEN 好友关系落库,
系统 SHALL 以 `(user_low, user_high)` 存储且强制 `user_low < user_high`，主键唯一 + CHECK 约束 SHALL 从根上消除 A-B/B-A 重复行。

#### Scenario: 关系归一化

GIVEN 用户 1002 与 1001 建立好友关系
WHEN 写入 friendship
THEN 存储为 `(user_low=1001, user_high=1002)`
AND 不出现 `(1002,1001)` 逆序行

#### Scenario: 逆序重复被拦截

GIVEN 已存在 `(1001,1002)` 关系行
WHEN 尝试写入 `(1002,1001)`
THEN 主键/CHECK 约束拒绝
AND 不产生重复关系

### Requirement: 并发互加唯一性

WHEN 两个用户并发互发申请并最终建立关系,
系统 SHALL 用 Redisson 可重入锁 `lock:friend:{low}_{high}` 串行化「检查-建单」，配合规范化存储，保证 SHALL 只产生一条 friendship。

#### Scenario: 并发互加只产生一条关系

GIVEN 用户 A 与 B 同时互发申请
WHEN 双方申请与同意并发执行
THEN 两个请求竞争同一把锁 `lock:friend:{min}_{max}`
AND 最终 friendship 仅一条

#### Scenario: 锁键归一一致

GIVEN A 发起 A→B 申请，B 发起 B→A 申请
WHEN 分别计算锁键
THEN 两者得到相同锁键 `lock:friend:{low}_{high}`（min/max）
AND 保证串行化

### Requirement: 好友列表

WHEN 用户查询好友列表,
系统 SHALL 分页返回且 SHALL 仅包含 ACCEPTED 状态的好友。

#### Scenario: 仅返回已接受好友

GIVEN 用户 A 有 ACCEPTED、PENDING、REJECTED 三种关系的申请
WHEN A 查询 `GET /api/friends?page=1&size=10`
THEN 仅返回 ACCEPTED 关系对应的好友
AND 排除 PENDING/REJECTED/CANCELLED

#### Scenario: 分页正确

GIVEN 用户好友数超过单页大小
WHEN 分页查询
THEN 返回当前页数据
AND 含正确的总数/分页元信息

## 点赞

### Requirement: 点赞前置校验

WHEN 用户对运动记录点赞,
系统 SHALL 校验记录 `status=PASSED`，未通过校验的记录 SHALL 返回 6001 且不产生点赞。

#### Scenario: 通过校验可赞

GIVEN 记录 status=PASSED
WHEN 用户提交点赞
THEN 点赞成功
AND 计数 +1

#### Scenario: 未通过校验被拒

GIVEN 记录 status=REJECTED/VERIFYING/SUBMITTED
WHEN 用户提交点赞
THEN 返回 6001（记录未通过校验不可点赞）
AND 不产生点赞与计数变化

### Requirement: 点赞幂等

WHEN 同一用户对同一记录重复点赞,
系统 SHALL 保证只计数一次，且 SHALL 落库仅一条 `(record_id,user_id)`。

#### Scenario: 重复点赞只计一次

GIVEN 用户 A 已对记录 R 点赞
WHEN 用户 A 再次点赞记录 R
THEN 计数保持不变（+1 仅发生一次）
AND record_like 落库仅一条 `(record_id,user_id)`

#### Scenario: 联合主键防重

GIVEN record_like 已存在 (record_id,user_id) 行
WHEN 异步落库尝试再次 INSERT 相同键
THEN 联合主键冲突被跳过
AND 不产生重复行

### Requirement: 计数读热写冷

WHEN 点赞/取消发生,
系统 SHALL 用 Redis `INCR`/`DECR` 维护计数，读取 SHALL 优先走 Redis，缺失时 SHALL 兜底 DB `COUNT(*)` 并回填。

#### Scenario: 计数走 Redis

GIVEN 点赞发生
WHEN 更新计数
THEN `like:count:{recordId}` 原子 INCR
AND 查询点赞数优先读该 Redis 值

#### Scenario: 兜底回填

GIVEN Redis 计数键缺失
WHEN 查询点赞数
THEN 兜底 DB COUNT(*) 得到真实值
AND 回填 Redis 计数键

### Requirement: 异步批量落库

WHEN 点赞/取消产生,
系统 SHALL 先记录 pending 操作，由定时任务 SHALL 批量持久化到 record_like，且 SHALL 用 Redisson 锁保证多实例仅一个执行。
同一批 flush 中的插入与删除 SHALL 在同一本地事务中提交或回滚；清理 pending 队列 SHALL 仅在该本地事务成功之后发生。

#### Scenario: 批量落库

GIVEN 存在待 flush 的点赞/取消操作
WHEN 定时任务触发
THEN 批量写 record_like（点赞 INSERT、取消 DELETE）
AND pending 操作被清理

#### Scenario: 多实例防重

GIVEN 多个服务实例同时触发 flush
WHEN 竞争 `lock:like:flush` 锁
THEN 仅一个实例执行 flush
AND 其他实例跳过

#### Scenario: 同一批插入与删除同进退

GIVEN 同一批 pending 既有点赞也有取消
WHEN 删除写入失败
THEN 该批插入亦不保留
AND pending 队列不被清理以便重试

### Requirement: 写路径事务边界

WHEN 一条业务操作需要写入多行或多表且中间失败必须全部回滚,
系统 SHALL 在同一本地事务中提交或回滚这些数据库写。
单行写入且有唯一键或乐观锁幂等时，系统 SHALL NOT 仅为形式增加本地事务。

#### Scenario: 提交记录与轨迹同进退

GIVEN 客户端提交一条含轨迹点的新记录
WHEN 轨迹点写入失败
THEN 不保留已插入的主记录
AND 不发出校验事件

#### Scenario: 好友接受两写同进退

GIVEN 待接受的好友申请
WHEN 写入好友关系失败
THEN 申请状态不停留在已接受
AND 不出现申请已接受但无好友行

#### Scenario: 单行注册不加形式事务

GIVEN 用户注册只插入一行 user（角色在同一行默认 USER）
WHEN 注册成功或因手机号唯一键失败
THEN 不要求额外的本地事务来保证角色初始化
AND 不创建第二张角色表作为本需求的一部分

### Requirement: 跨存储与跨服务写不纳入本地事务

WHEN 业务写同时涉及数据库与 Redis，或涉及数据库与跨服务调用,
系统 SHALL 使用最终一致、幂等与对账或补偿，SHALL NOT 用本地事务假装这些资源一起原子提交。

#### Scenario: 点赞热路径不进本地事务

GIVEN 用户点赞
WHEN 系统更新 Redis 计数并投递 pending
THEN 不开启覆盖 Redis 与 DB 的本地事务
AND 最终一致由后续 flush 与对账保证

#### Scenario: 入榜不把排行集合纳入本地事务

GIVEN 校验通过事件触发入榜
WHEN 贡献表写入后更新排行集合
THEN 不以本地事务包裹排行集合
AND 允许由结算任务以贡献表为准纠偏

#### Scenario: 校验主路径不把远程调用包进本地事务

GIVEN 校验引擎需要拉取轨迹并回调记录状态
WHEN 执行判定
THEN 本地事务（若有）不包含远程调用或消息发送
AND 不允许因此引入分布式事务框架

### Requirement: 取消点赞

WHEN 用户取消点赞,
系统 SHALL 使计数 -1 并 SHALL 异步删除对应 `(record_id,user_id)` 行，重复取消 SHALL 幂等。

#### Scenario: 取消成功

GIVEN 用户 A 已点赞记录 R
WHEN 用户 A 取消点赞
THEN 计数 -1
AND 异步删除 record_like 对应行

#### Scenario: 重复取消幂等

GIVEN 用户 A 未点赞或已取消
WHEN 用户 A 再次取消
THEN 计数不再变化（下限 0）
AND 无对应行可删

### Requirement: 最终一致

WHEN 计数与落库发生,
系统 SHALL 保证两者最终一致，并 SHALL 提供对账兜底以 DB 行为准纠偏。

#### Scenario: flush 后一致

GIVEN 点赞与取消操作已 flush
WHEN 对比 Redis 计数与 record_like 行数
THEN 两者一致

#### Scenario: 对账纠偏

GIVEN 因进程重启导致计数漂移
WHEN 对账任务执行
THEN 以 DB record_like 行为准纠正 Redis 计数

## 榜单

### Requirement: 仅通过记录入榜

WHEN 运动记录入榜,
系统 SHALL 仅累积 `status=PASSED` 或 `RE_PASSED` 记录的里程，其他状态 SHALL 不入榜。

#### Scenario: 通过记录入榜

GIVEN 记录经校验或改判进入 PASSED/RE_PASSED
WHEN 榜单刷新
THEN 该记录里程计入用户累计 pass 里程

#### Scenario: 未通过不入榜

GIVEN 记录处于 SUBMITTED/VERIFYING/REJECTED/RE_CONFIRMED
WHEN 榜单刷新
THEN 该记录里程不计入榜单

### Requirement: 事件驱动入榜

WHEN 记录状态迁移触发事件,
系统 SHALL 消费 VERIFIED 事件执行入榜，且 SHALL 以 eventId 去重保证消费幂等。

#### Scenario: VERIFIED 入榜

GIVEN 收到 VERIFIED 事件（recordId、userId、distance）
WHEN 消费者处理
THEN `ZINCRBY leaderboard:overall {distance} {userId}`
AND 写入 leaderboard_contribution（record_id 主键，status=ACTIVE）

#### Scenario: 事件幂等

GIVEN 相同 eventId 重复投递
WHEN 消费者处理
THEN SETNX 去重
AND 入榜与写贡献仅执行一次

### Requirement: 改判回滚

WHEN 已入榜记录被改判驳回,
系统 SHALL 回滚其榜单贡献，且 SHALL 保证回滚幂等。

#### Scenario: 回滚里程

GIVEN 记录 R 已入榜（存在 ACTIVE 贡献）
WHEN 消费到 REJECTED/REVERSED 事件（recordId=R）
THEN `ZINCRBY leaderboard:overall {-distance} {userId}` 回滚
AND contribution status 置 ROLLED_BACK

#### Scenario: 回滚幂等

GIVEN 记录 R 的贡献已 ROLLED_BACK
WHEN 再次消费到回滚事件
THEN 跳过，不重复回滚

#### Scenario: 无贡献不回滚

GIVEN 记录 R 从未入榜（无贡献行）
WHEN 消费到回滚事件
THEN 跳过，不产生负里程

### Requirement: 回滚与入榜并发安全

WHEN 回滚与入榜针对同一记录并发发生,
系统 SHALL 用 Redisson 锁 `lock:rollback:{recordId}` 串行化，避免里程错乱。

#### Scenario: 串行化

GIVEN 记录 R 同时触发入榜与回滚
WHEN 两者竞争锁
THEN 依序执行，最终榜单与 contribution 状态一致

### Requirement: 总榜查询

WHEN 客户端查询总榜,
系统 SHALL 按累计 pass 里程降序返回前 N 名，含排名、用户、里程。

#### Scenario: 查询成功

GIVEN 榜单 ZSet 存在成员
WHEN 请求 `GET /api/leaderboard?type=overall`
THEN 返回按里程降序的榜单
AND 每项含 rank/userId/nickname/distance

### Requirement: 好友榜查询

WHEN 客户端查询好友榜,
系统 SHALL 经 Feign 获取好友列表，ZSet 结果 SHALL 按好友过滤，只显示好友。

#### Scenario: 只显示好友

GIVEN 用户 A 有好友 B、C，非好友 D
WHEN 请求 `GET /api/leaderboard?type=friend`
THEN 结果仅含 B、C（若其有 pass 里程）
AND 排除 D

#### Scenario: 无好友或未上榜

GIVEN 用户无好友，或好友均无 pass 里程
WHEN 请求好友榜
THEN 返回空榜或友好提示

### Requirement: 快照结算防重

WHEN 定时结算任务触发,
系统 SHALL 用 Redisson 锁 `lock:scheduler:leaderboard` 保证多实例仅一个执行，并 SHALL 以 contribution 汇总为准纠偏 ZSet。

#### Scenario: 多实例仅一个执行

GIVEN 多个服务实例同时触发结算
WHEN 竞争锁
THEN 仅一个实例执行结算
AND 其他实例跳过

#### Scenario: 对账纠偏

GIVEN ZSet 与 contribution 汇总漂移
WHEN 结算任务执行
THEN 以 contribution ACTIVE 汇总为准纠正 ZSet
AND 标记 settled_at

## 榜单服务

### Requirement: 独立榜单服务

WHEN 系统初始化服务,
系统 SHALL 提供独立的 `leaderboard-service` 承载榜单读热与事件沉淀，与 record-service 的职责 SHALL 分离（record=记录读写，leaderboard=榜单）。

#### Scenario: 服务注册

GIVEN leaderboard-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 leaderboard-service 独立实例
AND 独立端口（默认 8084）

#### Scenario: 路由可达

GIVEN 网关已配置 /leaderboard/** 路由
WHEN 客户端请求 /leaderboard/api/leaderboard?type=overall
THEN 返回总榜
AND 请求转发至 leaderboard-service

### Requirement: 榜单事件订阅独立

WHEN 校验产生 VERIFIED/REJECTED 事件,
系统 SHALL 由 leaderboard-service 以独立消费组订阅并沉淀榜单，record-service 的榜单消费者 SHALL 下线，避免双写。

#### Scenario: 独立消费组

GIVEN 记录通过校验并发 VERIFIED 事件
WHEN 事件被消费
THEN 仅 leaderboard-service 入榜
AND record-service 不再写入榜单

#### Scenario: 回滚由榜单服务处理

GIVEN 记录改判发 REJECTED 事件
WHEN leaderboard-service 消费
THEN 回滚榜单贡献
AND 结果与迁移前 T8 验收一致

### Requirement: 榜单数据依赖

WHEN leaderboard-service 沉淀榜单,
系统 SHALL 读取 `leaderboard_contribution` 表；该表默认复用 record_db，物理隔离到独立 leaderboard_db SHALL 列为可选。

#### Scenario: 复用 record_db

GIVEN 默认配置
WHEN leaderboard-service 读写贡献表
THEN 使用 record_db（与 record-service 共享该库中贡献表）
AND 榜单查询功能正常

#### Scenario: 独立 leaderboard_db（可选）

GIVEN 选择物理隔离
WHEN leaderboard-service 启动
THEN 连接独立 leaderboard_db
AND 贡献表迁入该库

## 服务划分

### Requirement: 服务划分

系统 SHALL 由 6 个服务构成：gateway-service、user-service、record-service、verify-service、leaderboard-service、mapmatch-service；WHEN 系统部署, 榜单职责 SHALL 由 leaderboard-service 独立承载，道路拓扑匹配 SHALL 由 mapmatch-service 独立承载，离路判定 R5 SHALL 内聚于 verify-service 并远程调用匹配服务。

> 变更说明：本需求由提案 add-leaderboard-service 与 add-mapmatch-service 两次 MODIFIED「服务划分」演进而来。系统从 4 个服务（gateway-service、user-service、record-service、verify-service，榜单内聚于 record-service）演进到 5 个服务（追加 leaderboard-service，榜单职责独立承载），再到 6 个服务（追加 mapmatch-service，道路拓扑匹配职责独立承载），服务数 4→5→6。

#### Scenario: 服务数

GIVEN 系统完整部署
WHEN 查看服务实例
THEN 可见 6 个服务各自注册
AND 空间匹配职责在 mapmatch-service 不在 verify-service

## 压测

### Requirement: 并发压测方法

WHEN 系统进行性能验证,
系统 SHALL 对提交记录接口发起 100/500/1000 三档并发，并 SHALL 记录 P95、P99、QPS、错误率。

#### Scenario: 三档并发执行

GIVEN 压测脚本就绪，环境快照固定
WHEN 依次以 100、500、1000 并发压测提交接口
THEN 每档记录 P95/P99/QPS/错误率
AND 原始数据留存供优化前后对比

#### Scenario: 环境可复现

GIVEN 压测结论被引用
WHEN 复现压测
THEN 脚本与文档可重复执行
AND 记录环境快照（JDK/内存/中间件版本）保证可比

### Requirement: 量化达标

WHEN 系统以测试集（≥200 条，正负各半）验收,
系统 SHALL 达到拦截率 ≥90%、真实通过率 ≥95%、校验 P95 <200ms。

#### Scenario: 拦截率达标

GIVEN 伪造样本集
WHEN 运行校验引擎
THEN 拦截率 ≥90%

#### Scenario: 通过率达标

GIVEN 真实样本集
WHEN 运行校验引擎
THEN 通过率 ≥95%

#### Scenario: 延迟达标

GIVEN 校验链路运行
WHEN 统计响应时间
THEN P95 <200ms

#### Scenario: 未达标如实记录

GIVEN 任一指标未达标
WHEN 验收
THEN 如实记录实测值
AND 定位瓶颈并记录优化过程（不夸大）

### Requirement: 瓶颈优化实录

WHEN 压测暴露性能瓶颈,
系统 SHALL 定位并优化，且 SHALL 产出至少 1 个 Explain 慢查询案例与 1 个 GC/连接池调优案例，并 SHALL 记录优化前后对比。

#### Scenario: 慢查询案例

GIVEN 压测发现慢 SQL
WHEN 用 Explain 分析
THEN 加索引或改写 SQL
AND 记录优化前后 Explain 与耗时对比

#### Scenario: GC/连接池案例

GIVEN 压测发现 GC 停顿或连接池瓶颈
WHEN 调优
THEN 记录优化前后 GC 停顿或吞吐对比
AND 形成因果可解释的调优案例

### Requirement: 限流与熔断验证

WHEN 系统面对高并发或依赖故障,
系统 SHALL 由 Sentinel 在网关限流并对齐 5k QPS 目标，且 verify 不可用时 SHALL 熔断降级为「转人工」，主链路 SHALL 不挂。

#### Scenario: 限流拦截

GIVEN 请求超过限流阈值
WHEN 网关处理
THEN 超限请求被限流拦截
AND 返回限流提示

#### Scenario: 熔断降级转人工

GIVEN verify-service 不可用
WHEN record 侧调用校验
THEN 熔断降级为「转人工」状态
AND 提交主链路不挂（不因校验故障整体失败）

### Requirement: 网关流控规则动态数据源

WHEN 网关加载流控规则,
系统 SHALL 从 Nacos 动态数据源加载网关流控规则（dataId=`gateway-flow-rules`，DEFAULT_GROUP），规则改动经 Nacos 推送就地生效而无需重启，且 SHALL 在无规则/解析失败时回退代码默认 5000 QPS 基线保证限流不缺省。

#### Scenario: 规则动态拉取并生效

GIVEN 网关已启动并注册 Nacos 动态规则源
WHEN 在 Nacos 中修改 `gateway-flow-rules` 的 count
THEN 网关不重启即收到推送并就地更新规则
AND 后续请求按新阈值判定

#### Scenario: 无规则时兜底默认

GIVEN Nacos 中尚无 `gateway-flow-rules` 或该 dataId 为空
WHEN 网关启动加载规则
THEN 使用代码默认 5000 QPS 基线
AND 限流行为不缺省

#### Scenario: 解析失败保持上版

GIVEN Nacos 推送了一份无法解析的规则 JSON
WHEN 网关处理该推送
THEN 保留上一版有效规则执行
AND 不因瞬时坏配置抖断限流

#### Scenario: 超限仍按 429 拦截

GIVEN 动态规则阈值已生效
WHEN 请求超过当前阈值
THEN 超限请求被限流拦截
AND 返回限流提示（HTTP 429）

### Requirement: 压测沉淀

WHEN 压测与优化完成,
系统 SHALL 产出压测报告与 ADR，将方案、数据、优化因果 SHALL 写入 README/ADR 可复现文档。

#### Scenario: 报告产出

GIVEN 压测与优化已执行
WHEN 沉淀阶段
THEN 产出 docs/perf/压测报告.md（方案/环境/数据/图表/对比/结论）
AND 产出 ADR 记录优化因果
AND README 补摘要与复现命令

### Requirement: 演示环境可选刷盘

WHEN 以仓库默认方式启动中间件,
系统 SHALL 保持 InnoDB 每次事务刷盘（崩溃不丢已提交事务）。
WHEN 显式启用压测/演示 overlay,
系统 MAY 将刷盘放宽为按秒刷新，且文档 SHALL 写明最多约一秒已提交事务可能丢失；SHALL NOT 把该模式当作默认或生产配置。

#### Scenario: 默认 compose 不放宽刷盘

GIVEN 只使用仓库主 docker-compose 文件
WHEN 启动 MySQL
THEN 不启用按秒刷盘
AND 崩溃不丢失已提交事务（与 MySQL 默认耐久一致）

#### Scenario: 显式 overlay 才放宽

GIVEN 运维显式叠加压测 overlay
WHEN 启动 MySQL
THEN 刷盘可放宽为按秒
AND 文档说明这是演示取舍而非生产口径

### Requirement: JVM 停顿目标可选

WHEN 以推荐方式启动业务服务,
系统 SHALL 允许设置 G1 停顿目标（50ms 量级），SHALL NOT 把固定大堆当作推荐配置。

#### Scenario: 推荐不含固定大堆

GIVEN 阅读启动说明或脚本
WHEN 采用推荐 JVM 参数
THEN 可包含停顿目标
AND 不包含已否决的固定 1g 堆作为推荐项

#### Scenario: 固定大堆不被默认启用

GIVEN 未显式传入堆大小
WHEN 启动服务
THEN 不因本变更而设置 -Xms/-Xmx 为 1g

### Requirement: 跨请求提交聚合须先复测

WHEN 评估把多个提交请求合并写库,
系统 SHALL 先在单记录批量插入已作为默认路径的前提下复测提交吞吐，SHALL NOT 在没有该复测证据时合并不同请求的写库。

#### Scenario: 无复测不做跨请求聚合

GIVEN 尚未在默认批量插入路径上复测 100 并发提交
WHEN 提出跨请求写聚合
THEN 不把该聚合作为已交付能力
AND 各提交请求仍独立落库

#### Scenario: 单记录批量不是跨请求聚合

GIVEN 一条记录内的多轨迹点批量插入已交付
WHEN 描述优化口径
THEN 不把它称为跨请求聚合

## 可观测性

### Requirement: 指标暴露

WHEN 任一服务运行,
系统 SHALL 经 `/actuator/prometheus` 暴露 Micrometer 指标，涵盖 JVM（堆/GC/线程）、HTTP（QPS/P95/错误率）、数据源连接池与业务判定指标。

#### Scenario: 端点可访问

GIVEN 服务已启动且依赖 micrometer-registry-prometheus 就绪
WHEN 请求 `/actuator/prometheus`
THEN 返回 Prometheus 文本格式指标
AND 包含 `jvm_` 与 `http_server_requests_` 前缀指标

#### Scenario: 端点未开启即不可达

GIVEN 服务未在 management 中暴露 prometheus 端点
WHEN 请求 `/actuator/prometheus`
THEN 返回 404 或隐藏
AND 不泄漏额外指标

### Requirement: 指标采集

WHEN Prometheus 运行,
系统 SHALL 按 prometheus.yml 静态配置抓取全部 5 个服务（gateway/user/record/verify + 未来 leaderboard）的指标端点。

#### Scenario: 抓取成功

GIVEN Prometheus 与服务均运行
WHEN 查看 Prometheus targets
THEN 各服务 target 状态为 UP
AND 指标带 instance 标签区分

#### Scenario: 实例下线可见

GIVEN 某服务停止
WHEN Prometheus 下一抓取周期
THEN 该 target 标记 DOWN
AND 触发对应告警

### Requirement: 可视化面板

WHEN 运维查看监控,
系统 SHALL 提供一个 Grafana Dashboard 展示核心指标：服务可用性、HTTP P95/错误率、JVM 堆/GC、连接池。

#### Scenario: 面板展示

GIVEN Grafana 已配置 Prometheus datasource
WHEN 打开预置 dashboard
THEN 展示服务可用性、延迟、错误率、JVM 面板
AND 数据来自 Prometheus

### Requirement: 告警规则

WHEN 指标越过阈值,
系统 SHALL 触发告警，至少覆盖：实例下线、HTTP 错误率超阈值、校验 P95 >200ms（对齐审批版 §8.2）、JVM 堆使用率 >80%。

#### Scenario: 延迟告警

GIVEN 校验接口 P95 超过 200ms 持续一段时间
WHEN Prometheus 评估告警规则
THEN 触发 P95 告警（firing 状态）

#### Scenario: 实例下线告警

GIVEN 某服务实例停止
WHEN Prometheus 检测 target DOWN
THEN 触发实例下线告警

### Requirement: HTTP 请求贯穿标识

WHEN 外部请求经网关进入系统,
系统 SHALL 保证存在 `X-Request-Id`：若请求已携带则沿用，否则生成 UUID；SHALL 写入响应头并透传至下游服务。

#### Scenario: 无入站 ID 时自动生成

GIVEN 客户端未携带 X-Request-Id
WHEN 请求经过 gateway-service
THEN 响应头包含非空 X-Request-Id
AND 下游服务请求头可见同一 X-Request-Id

#### Scenario: 客户端指定 ID 时沿用

GIVEN 客户端携带 X-Request-Id: client-fixed-id
WHEN 请求经过 gateway-service
THEN 响应头与下游透传头均为 client-fixed-id

### Requirement: 服务内日志 MDC

WHEN 业务服务（Servlet MVC）处理 HTTP 请求,
系统 SHALL 将 X-Request-Id 写入 MDC 键 `traceId`，并在请求结束时清理；日志 pattern SHALL 输出 `[%X{traceId}]`。

#### Scenario: 访问日志含 traceId

GIVEN 服务已配置统一 logging.pattern.console
WHEN 处理带 X-Request-Id 的请求并打业务日志
THEN 日志行包含该 traceId

### Requirement: MQ 跨服务透传

WHEN 生产者发布记录/校验事件,
系统 SHALL 将当前 traceId 写入消息 userProperty（键 X-Request-Id）；
WHEN 消费者处理消息,
系统 SHALL 读取该属性并还原到 MDC，处理结束后清理。

#### Scenario: 提交记录全链路同一 traceId

GIVEN 经网关提交一条运动记录且 MQ 链路正常
WHEN record 发 SUBMITTED、verify 消费并判定、verify 发 VERIFIED/REJECTED、leaderboard 消费入榜
THEN 上述各阶段业务日志可用同一 traceId 检索对齐

### Requirement: 不引入分布式追踪全家桶

WHEN 评估链路追踪方案,
系统 SHALL 保持 ADR-0003 决策：以 MDC 最小实现满足日志串联，不引入 SkyWalking / Zipkin / Sleuth。

#### Scenario: 依赖面无追踪中间件

GIVEN 本变更交付完成
WHEN 检查服务依赖与配置
THEN 无 SkyWalking / Zipkin / spring-cloud-sleuth 强制依赖

## 规则灰度

### Requirement: 规则版本化

WHEN 管理员创建规则版本,
系统 SHALL 将当前规则与阈值序列化为 `rules_json` 快照存入 rule_version，并 SHALL 维护版本状态（GRAY/ACTIVE/RETIRED）与 `gray_ratio`。

#### Scenario: 创建灰度版本

GIVEN 当前基线规则阈值
WHEN 管理员创建新版本并设 gray_ratio=10
THEN 快照 rules_json 落库
AND 版本状态 GRAY
AND 灰度比例 10

#### Scenario: 版本状态约束

GIVEN 已存在一个 ACTIVE 基线版本
WHEN 新版本被标记为 ACTIVE
THEN 旧版本置 RETIRED
AND 同一时刻至多一个 ACTIVE 版本

### Requirement: 灰度采样路由

WHEN 校验引擎执行,
系统 SHALL 按 `userId % 100 < gray_ratio` 决定使用灰度规则快照或基线规则，且 SHALL 保证同一用户始终同一分支。

#### Scenario: 命中灰度

GIVEN gray_ratio=10
AND 用户 userId%100=5（<10）
WHEN 该用户提交记录触发校验
THEN 使用灰度规则快照执行

#### Scenario: 未命中灰度

GIVEN gray_ratio=10
AND 用户 userId%100=50（≥10）
WHEN 该用户提交记录触发校验
THEN 使用基线规则执行

#### Scenario: 采样稳定性

GIVEN 同一用户重复提交
WHEN 多次触发校验
THEN 每次均命中同一分支（灰/基线）
AND 不因请求时序抖动切换分支

### Requirement: 规则快照隔离

WHEN 灰度观察期执行规则,
系统 SHALL 使用库内 `rules_json` 快照而非 Nacos 实时配置，避免灰度期间配置变更导致规则漂移。

#### Scenario: 灰度用快照

GIVEN 灰度版本观察中
AND Nacos 实时阈值此时被修改
WHEN 校验引擎取规则
THEN 灰度分支仍用版本快照执行
AND 不受 Nacos 瞬时变更影响

### Requirement: 秒级回滚

WHEN 灰度版本判定异常,
系统 SHALL 支持将 `gray_ratio` 置 0 秒级回滚，新版本 SHALL 立即不再被采样，基线 SHALL 不受影响。

#### Scenario: 回滚生效

GIVEN 灰度版本 gray_ratio=10 且出现异常
WHEN 管理员将 gray_ratio 置 0
THEN 短 TTL 缓存失效后（≤60s）新版本不再采样
AND 全部请求回归基线规则

#### Scenario: 基线性不受影响

GIVEN 灰度版本运行中
WHEN 回滚 gray_ratio=0
THEN 基线规则持续正常运行
AND 无请求中断

### Requirement: 全量发布

WHEN 灰度版本稳定,
系统 SHALL 支持将 gray_ratio 置 100 全量发布，并 SHALL 将旧版本置 RETIRED。

#### Scenario: 全量生效

GIVEN 灰度版本稳定运行满观察期
WHEN 管理员执行全量发布
THEN gray_ratio=100
AND 全部用户使用新版本规则

#### Scenario: 旧版本退役

GIVEN 新版本已全量
WHEN 发布完成
THEN 旧 ACTIVE 版本置 RETIRED
AND 不再被采样

### Requirement: 版本管理接口

WHEN 管理员管理规则,
系统 SHALL 提供版本管理端点：创建版本、调整灰度比例、全量发布。

#### Scenario: 创建与调灰度

GIVEN 管理员调用管理端点
WHEN 创建版本并调整 gray_ratio
THEN 返回版本信息与最新灰度比例

#### Scenario: 全量发布成功

GIVEN 存在 GRAY 版本
WHEN 调用全量发布端点
THEN 版本置 ACTIVE
AND 旧版本 RETIRED

## 鉴权

### Requirement: 用户注册

WHEN 用户提交注册,
系统 SHALL 校验手机号唯一并 SHALL 使用 BCrypt 哈希存储密码，重复手机号 SHALL 返回 2001。

#### Scenario: 注册成功

GIVEN 用户提交新手机号与密码
WHEN 注册接口处理
THEN 创建用户（密码 BCrypt 哈希）
AND 返回注册成功

#### Scenario: 手机号重复

GIVEN 手机号已存在注册用户
WHEN 再次注册同一手机号
THEN 返回 2001（手机号已注册）
AND 不创建新用户

### Requirement: 用户登录

WHEN 用户提交凭据,
系统 SHALL 校验密码，成功 SHALL 签发 access token 与 refresh token，失败 SHALL 返回 401 并计失败次数。

#### Scenario: 登录成功

GIVEN 手机号与密码正确
WHEN 登录接口处理
THEN 返回 access token 与 refresh token
AND access 短时效、refresh 长时效

#### Scenario: 密码错误

GIVEN 密码错误
WHEN 登录接口处理
THEN 返回 401
AND 计失败次数（超过阈值锁定的设计口径）

### Requirement: Token 刷新与轮换

WHEN access token 过期,
系统 SHALL 凭 refresh token 刷新，且 SHALL 轮换（旧 refresh 作废，新 refresh 下发）。

#### Scenario: 刷新成功

GIVEN refresh token 有效且未作废
WHEN 调用刷新接口
THEN 返回新 access 与新 refresh
AND 旧 refresh 作废

#### Scenario: refresh 已作废

GIVEN refresh token 已轮换过或过期
WHEN 调用刷新接口
THEN 返回 401（1001）

### Requirement: 网关统一鉴权

WHEN 请求进入网关,
系统 SHALL 校验 Authorization 头中的 token，失败 SHALL 返回 401（1001），成功 SHALL 解析 userId 并注入请求头透传下游。

#### Scenario: 有效 token 放行

GIVEN 请求携带有效 Bearer token
WHEN 网关过滤器处理
THEN 解析出 userId
AND 注入 X-User-Id 头透传下游

#### Scenario: 无效 token 拒绝

GIVEN 请求无 token 或 token 无效/过期
WHEN 网关过滤器处理
THEN 返回 401（1001）
AND 不放行至下游

#### Scenario: 白名单放行

GIVEN 请求路径属白名单（/api/auth、/internal、/actuator）
WHEN 网关过滤器处理
THEN 跳过鉴权直接放行

### Requirement: 用户数据隔离

WHEN 业务接口处理用户请求,
系统 SHALL 从网关注入的 userId 认定身份，而非信任调用方传入的 userId，越权访问他人资源 SHALL 返回 403（1002）。

#### Scenario: 正常访问本人数据

GIVEN 用户 A 携带自己的 token
WHEN 访问本人记录/点赞/好友
THEN 以 token 中 userId 为准处理
AND 返回正常结果

#### Scenario: 越权访问他人被拒

GIVEN 用户 A 尝试访问用户 B 的资源
WHEN 业务判定 userId 不一致
THEN 返回 403（1002）
AND 不泄露 B 的数据

### Requirement: 鉴权降级开关

WHEN 本地调试或压测需要,
系统 SHALL 提供 auth.enabled 开关，关闭时 SHALL 降级为显式携带 userId 的旧行为，默认关闭以降低迁移成本。

#### Scenario: 开关默认关闭

GIVEN auth.enabled=false（默认）
WHEN 请求受保护接口
THEN 沿用显式携带 userId 的旧行为
AND 不启用网关鉴权

#### Scenario: 开关启用

GIVEN auth.enabled=true
WHEN 请求受保护接口
THEN 强制走网关鉴权与数据隔离

### Requirement: 用户角色模型

WHEN 用户注册,
系统 SHALL 默认赋予 USER 角色，且 SHALL 提供内部接口授予 ADMIN 角色（最小权限，默认非管理员）。

#### Scenario: 注册默认 USER

GIVEN 新用户注册成功
WHEN 查询其角色
THEN 角色为 USER

#### Scenario: 内部授予 ADMIN

GIVEN 内部管理流程
WHEN 调用授予接口
THEN 指定用户角色变更为 ADMIN

### Requirement: 令牌携带角色

WHEN 登录签发令牌,
系统 SHALL 在 token 中写入 role claim，且 role SHALL 由签发端决定，SHALL 不被下游信任外部传入。

#### Scenario: token 含角色

GIVEN 用户登录成功
WHEN 解析 access token
THEN 可读取出 role claim 与 userId

### Requirement: 治理面鉴权

WHEN 请求访问管理端接口（/admin/** 或规则版本接口）,
系统 SHALL 要求 role=ADMIN，普通用户 SHALL 返回 403（1002），未登录 SHALL 返回 401（1001）。

#### Scenario: 管理员访问放行

GIVEN 请求携带 ADMIN 角色的有效 token
WHEN 访问管理端接口
THEN 放行至下游

#### Scenario: 普通用户被拒

GIVEN 请求携带 USER 角色的有效 token
WHEN 访问管理端接口
THEN 返回 403（1002）

#### Scenario: 未登录被拒

GIVEN 请求无有效 token
WHEN 访问管理端接口
THEN 返回 401（1001）

### Requirement: 白名单收紧

WHEN 网关过滤请求,
系统 SHALL 不为管理端接口提供匿名放行；内部接口（`/internal/**`）SHALL 不对公网经网关路由暴露，且网关白名单 SHALL NOT 包含无对应路由的 `/internal/**` 死配置（避免未来误加 internal 路由时安全边界塌陷为可自提权）。Actuator 指标端点本地演示可经白名单暴露，生产 profile SHALL 收敛暴露面（收窄 include，或管理端口/内网抓取隔离）。

#### Scenario: 管理端不匿名放行

GIVEN 请求路径为 /admin/**
WHEN 网关过滤
THEN 进入鉴权校验（不跳过）
AND 依角色判定放行或拒绝

#### Scenario: 内部接口维持网内

GIVEN 请求路径为 /internal/**
WHEN 网关过滤
THEN 维持网内信任（不对公网暴露）
AND 服务本地仍须通过共享密钥校验（见「内部接口共享密钥校验」）

#### Scenario: 白名单无 /internal/**

GIVEN 网关应用配置已加载
WHEN 读取 app.auth.whitelist
THEN 列表不含 `/internal/**`
AND 仍包含发 token 与探针所需前缀（如 `/api/auth/**`、`/actuator/**`）

#### Scenario: 生产 actuator 收敛口径已文档化

GIVEN 运维阅读 README 或 ADR-0007/0003
WHEN 部署生产 profile
THEN 文档要求收敛 actuator 暴露（收窄 include 或管理端口隔离）
AND 不将本地演示的 prometheus/metrics 公网可读配置直接用于生产

### Requirement: 内部接口共享密钥校验

WHEN 请求命中服务本地 `/internal/**` 路径,
系统 SHALL 校验请求头 `X-Internal-Token` 与配置密钥一致；密钥经环境变量 `INTERNAL_API_TOKEN` 注入，本地可有演示默认值。不一致时 SHALL 拒绝（403/1002），不得仅依赖网络拓扑防护。服务间经 Feign 调用 `/internal/**` 时 SHALL 由出站拦截器自动携带同一密钥。

#### Scenario: 无密钥直连 grant-admin 被拒

GIVEN user-service 监听 8081
WHEN 不带正确 `X-Internal-Token` 调用 `POST /internal/auth/grant-admin`
THEN 请求被拒绝（403/1002）
AND 不授予 ADMIN

#### Scenario: Feign 内部调用自动携带密钥

GIVEN 服务间经 Feign 调用 `/internal/**`
WHEN 请求发出
THEN 自动携带与服务端一致的 `X-Internal-Token`
AND 校验通过后正常处理

#### Scenario: 密钥默认值不得用于生产

GIVEN 部署生产环境
WHEN 未注入 `INTERNAL_API_TOKEN`
THEN 使用演示默认值属于不安全配置
AND README/ADR-0007 明示生产必须注入

## Web 控制台

### Requirement: Web 控制台工程脚手架

WHEN 开发者在仓库中启动 Web 控制台开发服务器,
系统 SHALL 提供独立的前端工程目录，使用 Vue 3 与 Vite，并将浏览器请求按网关既有前缀转发到本地网关，SHALL NOT 剥除 `/api` 或其他服务前缀。

#### Scenario: 开发服务器可启动

GIVEN 已安装 Node.js 与 pnpm
WHEN 在前端目录执行开发启动命令
THEN 开发服务器在本地端口监听
AND 不要求改动 Java 服务代码

#### Scenario: 代理保留网关前缀

GIVEN 开发代理已配置
WHEN 浏览器请求 `/leaderboard/api/leaderboard`
THEN 请求被转发到本地网关同一路径
AND 不被改写成去掉前缀的路径

#### Scenario: 统一响应按 code=0 解析

GIVEN 后端返回 JSON `{code, message, data}`
WHEN 前端请求封装处理响应
THEN `code=0` 视为成功
AND 非 0 向用户展示 `message`
AND 不把成功码当作 1

### Requirement: Web 控制台登录会话

WHEN 用户在 Web 控制台提交登录表单,
系统 SHALL 调用既有登录接口并保存 access 与 refresh；后续请求 SHALL 携带 `Authorization: Bearer` access token，SHALL NOT 由浏览器发送 `X-User-Id` 或 `X-Role` 作为身份。

#### Scenario: 登录成功进入控制台

GIVEN 用户提供正确手机号与密码
WHEN 提交登录
THEN 保存 accessToken 与 refreshToken
AND 进入需登录的控制台布局

#### Scenario: 凭据错误

GIVEN 密码错误且账号未锁定
WHEN 提交登录
THEN 停留在登录页
AND 展示凭据错误（对应 401/1001）
AND 不保存 token

#### Scenario: 账号锁定

GIVEN 账号处于登录锁定
WHEN 提交登录
THEN 展示锁定提示（对应 403/1002）
AND 不保存 token

#### Scenario: 未登录被拦

GIVEN 浏览器没有有效 access token
WHEN 访问需登录的控制台路径
THEN 跳转到登录页

#### Scenario: access 过期后刷新

GIVEN refresh token 仍有效而 access 已失效
WHEN 业务请求返回 401
THEN 使用 refresh 换取新双 token
AND 重试原请求一次

### Requirement: Web 治理面控制台

WHEN 角色为 ADMIN 的已登录用户打开治理面页面,
系统 SHALL 允许调用既有规则版本接口与申诉终判接口。
WHEN 普通用户访问同一页面或接口,
系统 SHALL 依赖网关返回 403，前端 SHALL NOT 绕过网关展示管理数据。

#### Scenario: 管理员可调灰度

GIVEN 用户持有 ADMIN 的有效 token
WHEN 调整某规则版本灰度比例
THEN 请求到达既有规则接口
AND 页面展示成功或既有业务错误码信息

#### Scenario: 普通用户被拒

GIVEN 用户角色为 USER
WHEN 访问治理面接口
THEN 展示无权限（403/1002）
AND 不展示可编辑的规则表单数据

#### Scenario: 终判需申诉编号

GIVEN 管理员打开终判页
WHEN 未提供后端申诉列表
THEN 页面提供申诉编号输入
AND 不把不存在的工单队列当成已交付能力

#### Scenario: 不暴露内部授予

GIVEN 阅读前端文档或页面
WHEN 查找授予管理员的入口
THEN 前端不提供 grant-admin 操作
AND 说明授予仍走内部凭证接口

### Requirement: Web 业务控制台

WHEN 已登录用户在 Web 控制台提交内置或粘贴的轨迹样例,
系统 SHALL 调用既有记录提交接口，并允许查询判定结果、对通过记录点赞、管理好友、查看总榜与好友榜。
系统 SHALL NOT 在本变更中新增「我的记录」后端列表接口。

#### Scenario: 提交样例记录

GIVEN 用户已登录且鉴权开启
WHEN 使用内置样例点提交记录
THEN 返回 recordId
AND 页面展示当前状态

#### Scenario: 判定中轮询

GIVEN 记录已提交且判定尚未结束
WHEN 查询判定结果
THEN 展示校验中
AND 自动再次查询直到终态或达到重试上限

#### Scenario: 拒绝后可申诉

GIVEN 判定为拒绝
WHEN 用户提交申诉原因
THEN 调用既有申诉接口
AND 展示申诉结果或错误信息

#### Scenario: 未通过不可点赞

GIVEN 记录未通过校验
WHEN 用户点赞
THEN 页面展示业务错误
AND 不把该错误显示为成功

#### Scenario: 总榜可查询

GIVEN 用户打开榜单页
WHEN 选择总榜
THEN 展示排名、昵称与里程
AND 空榜时展示空状态而非报错

#### Scenario: 会话内记住提交的记录

GIVEN 本次浏览器会话已成功提交记录
WHEN 用户进入判定页未手工输入 id
THEN 可从本机会话记录中选择最近提交的 recordId
AND 文档说明刷新或更换浏览器后不会从服务端恢复列表

## 空间匹配

### Requirement: 独立路网匹配服务

WHEN 校验需要空间真实性判定,
系统 SHALL 提供独立的 `mapmatch-service` 承载路网数据与匹配算法，暴露匹配接口，与 verify-service 的规则链 SHALL 解耦。

#### Scenario: 服务注册

GIVEN mapmatch-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 mapmatch-service 独立实例
AND 独立端口（默认 8085）

#### Scenario: 匹配接口返回

GIVEN 路网数据已加载
WHEN 调用 POST /mapmatch/match 提交轨迹点
THEN 返回匹配结果（matchedRatio / avgOffRoadDistance / offRoadRatio 等）

### Requirement: 真实路网数据

WHEN 路网服务初始化,
系统 SHALL 加载真实 OSM 路网数据（而非手工假数据），并提供幂等可重跑的导入流程。

#### Scenario: 路网导入成功

GIVEN 已下载指定城市 OSM 路网
WHEN 执行导入脚本
THEN 路网表写入空间库
AND 导入脚本幂等可重跑

#### Scenario: 数据可查询

GIVEN 路网已导入
WHEN 匹配算法查询最近道路
THEN 基于空间索引返回候选道路
AND 查询命中实际路网

### Requirement: R5 离路规则

WHEN 校验引擎执行规则链,
系统 SHALL 新增 R5 离路规则，通过路网匹配计算轨迹偏离真实道路的比例，偏离超阈值 SHALL 产生 SOFT/HARD 证据。

#### Scenario: 悬浮轨迹命中 R5

GIVEN 轨迹整体不在任何真实道路上（如海面/楼顶）
WHEN R5 执行路网匹配
THEN offRoadRatio 超过阈值
AND 命中 R5_OFFROAD 证据

#### Scenario: 真实轨迹不命中

GIVEN 真实骑行/跑步轨迹沿道路
WHEN R5 执行路网匹配
THEN offRoadRatio 低于阈值
AND 不产生 R5 命中

### Requirement: 判定聚合兼容

WHEN R5 产生证据,
系统 SHALL 将 R5 命中并入既有 hits 列表，走现有「HARD 即拒 / SOFT 计分」聚合，SHALL 不改变 R1-R4 既有行为。

#### Scenario: R5 软证据参与评分

GIVEN R5 命中 SOFT
WHEN 判定聚合
THEN score 计入 10×SOFT 项
AND 仅 SOFT 时遵循 soft-only-reject 策略

#### Scenario: R5 硬证据即拒

GIVEN R5 命中 HARD（极端偏离）
WHEN 判定聚合
THEN verdict=REJECTED

### Requirement: 匹配降级

WHEN mapmatch-service 不可用,
系统 SHALL 使 R5 降级为「不命中」，不阻断校验主链路，并 SHALL 记录告警。

#### Scenario: 服务不可用降级

GIVEN mapmatch-service 停机
WHEN R5 调用匹配接口失败
THEN 熔断降级为不命中
AND 校验主链路正常完成
AND 记录 warn 日志

## 阈值分类型

### Requirement: 记录携带运动类型

WHEN 用户提交运动记录,
系统 SHALL 接收 sportType 字段，缺省 SHALL 回退为 RUNNING（向后兼容），未知类型 SHALL 拒绝。

#### Scenario: 提交带类型

GIVEN 用户提交记录并指定 sportType=CYCLING
WHEN 记录入库
THEN sportType 字段正确保存

#### Scenario: 缺省回退

GIVEN 提交记录未指定 sportType
WHEN 记录入库
THEN sportType 回退为 RUNNING
AND 校验行为与现有 RUNNING 一致

#### Scenario: 未知类型拒绝

GIVEN 提交记录 sportType 为枚举外取值
WHEN 记录提交接口处理
THEN 返回非法参数错误

### Requirement: 阈值按类型分维度

WHEN 系统配置规则阈值,
系统 SHALL 按运动类型分维度（每类型一套 R1-R4 阈值），而非全类型共用一套。

#### Scenario: 类型独立阈值

GIVEN 规则快照含 RUNNING 与 CYCLING 两套阈值
WHEN 读取阈值
THEN RUNNING 与 CYCLING 各自独立
AND 互不影响

### Requirement: 按类型判定

WHEN 校验引擎判定记录,
系统 SHALL 依据记录的 sportType 取对应阈值集执行 R1-R4，未知类型 SHALL 保守处理（回退默认或保守拒绝，可配）。

#### Scenario: 骑行不误杀

GIVEN 一条真实骑行轨迹 sportType=CYCLING
WHEN 引擎判定
THEN 取 CYCLING 阈值（速度上限高于跑步）
AND 不触发 R1 误判

#### Scenario: 跑步沿用原阈值

GIVEN 一条跑步轨迹 sportType=RUNNING
WHEN 引擎判定
THEN 取 RUNNING 阈值（沿用现有 5.5）
AND 行为与历史一致

### Requirement: 灰度与类型维度正交

WHEN 灰度路由与类型阈值叠加,
系统 SHALL 保持两维度正交：userId%100 决定使用哪个规则版本，sportType 决定版本内用哪套阈值，SHALL 不改动现有灰度路由逻辑。

#### Scenario: 正交叠加

GIVEN 某版本快照含多类型阈值
AND 用户命中灰度
WHEN 判定
THEN 先按灰度取版本，再按 sportType 取阈值
AND 灰度路由逻辑不变

## 账号锁定

### Requirement: 登录失败锁定

WHEN 同一手机号在窗口期内连续登录失败达阈值,
系统 SHALL 锁定该账号，锁定期间 SHALL 拒绝登录且不校验密码，锁定期满 SHALL 自动解锁。

#### Scenario: 达阈值触发锁定

GIVEN 手机号在 15 分钟窗口内已失败 5 次
WHEN 再次登录（第 6 次）
THEN 系统拒绝登录
AND 不校验密码（直接返回锁定）
AND 锁定开始计时

#### Scenario: 锁定期间拒绝

GIVEN 账号处于锁定状态
WHEN 用户提交任意密码登录
THEN 返回「账号已临时锁定」
AND 不消耗 BCrypt 校验
AND 不更新失败计数

#### Scenario: 锁定期满自动解锁

GIVEN 账号锁定已达到锁定时长
WHEN 用户登录
THEN 锁定键过期（TTL 到期）
AND 恢复校验密码

### Requirement: 登录成功清零

WHEN 用户登录成功,
系统 SHALL 清除该手机号的失败计数与锁定状态，防止「试错后纠正」导致的计数残留。

#### Scenario: 成功清零

GIVEN 手机号有若干失败计数（未达阈值）
WHEN 该用户登录成功
THEN 失败计数清零
AND 若存在锁定键则一并清除

### Requirement: 防爆破与降级

WHEN 系统实施锁定,
系统 SHALL 不区分「用户不存在」与「密码错误」（统一返回，防撞库探测），且 Redis 不可用时 SHALL 降级为「继续计数告警但不阻断登录」。

#### Scenario: 防撞库探测

GIVEN 登录失败
WHEN 返回错误
THEN 用户不存在与密码错误返回同一语义
AND 不泄露账号是否存在

#### Scenario: Redis 不可用降级

GIVEN Redis 不可用
WHEN 执行登录失败计数与锁定
THEN 降级为仅告警不阻断
AND 登录流程不因 Redis 故障失败

## 规则二级缓存

### Requirement: 二级缓存读路径

WHEN 读取规则或灰度路由,
系统 SHALL 按「Caffeine 本地 → Redis → DB → 回填两级」顺序读取，缓存缺失时 SHALL 查库并回填两级。

#### Scenario: 本地命中

GIVEN 规则快照在 Caffeine 中且未过期
WHEN 读取规则
THEN 直接返回本地值
AND 不访问 Redis 与 DB

#### Scenario: 本地未命中 Redis 命中

GIVEN Caffeine 未命中但 Redis 有值
WHEN 读取规则
THEN 返回 Redis 值
AND 回填 Caffeine

#### Scenario: 两级未命中回源

GIVEN Caffeine 与 Redis 均无
WHEN 读取规则
THEN 查库（Nacos 配置落库/rule_version）
AND 回填 Redis 与 Caffeine 两级

### Requirement: 空值缓存防穿透

WHEN 查询不存在的规则或版本,
系统 SHALL 缓存空值哨兵（短 TTL），避免重复穿透到 DB。

#### Scenario: 空值缓存

GIVEN 查询的灰度版本号在 DB 不存在
WHEN 首次查询
THEN 缓存空值哨兵（短 TTL）
AND 后续相同查询不再打库

### Requirement: 互斥重建防击穿

WHEN 缓存失效且多实例并发重建同一 key,
系统 SHALL 用 Redisson 锁保证仅一个实例查库重建，其余 SHALL 等待或短退避。

#### Scenario: 单实例重建

GIVEN 某规则缓存已失效
AND 多个服务实例并发请求该规则
WHEN 触发重建
THEN 仅一个实例持有 lock:rule-rebuild:{key} 查库重建
AND 其余等待或复用重建结果

### Requirement: 随机 TTL 防雪崩

WHEN 设置缓存过期时间,
系统 SHALL 在基础 TTL 上叠加随机抖动，避免批量同时过期打库。

#### Scenario: TTL 抖动

GIVEN 基础缓存 TTL 60s
WHEN 写入缓存
THEN 实际 TTL 在 60s ± 随机抖动范围内
AND 不出现大批量同刻过期

### Requirement: Nacos 变更精准失效

WHEN 规则配置或版本变更,
系统 SHALL 精准失效对应 Caffeine 与 Redis 缓存（而非仅靠 TTL 兜底），并 SHALL 保留 TTL 兜底。

#### Scenario: 变更即失效

GIVEN 规则灰度比例或版本变更
WHEN 变更监听触发
THEN invalidate 对应 Caffeine key
AND DEL 对应 Redis key
AND 下次读取回源到最新值

## 变更历史

各提案 spec-delta 备注中有价值的上下文说明，融合记录如下：

- **add-verify-engine**：实现「校验闭环」主线；榜单贡献快照、排行榜入榜、Nacos 灰度发布属后续独立变更（已分别落地）。状态机迁移矩阵与阈值默认值以审批版 §5.1/§5.2 为唯一依据。取消「仅 SOFT→REJECTED」的宽松开关时机由灰度变更决定（见「规则灰度」分组）。
- **add-friend-module**：关系一旦 ACCEPTED 双方互见，存储层以 `(user_low,user_high)` 单向归一化承载双向关系。好友榜（经 UserApi 过滤）由「榜单」分组承接。状态机字段值与错误码（5001/5002/2002）以审批版 §4.1/§6.1/§4.8 为唯一依据。
- **add-like-module**：点赞只对 PASSED 记录开放，与校验引擎状态机强耦合（落地顺序在其后）。计数权威源为 record_like 行，Redis 为读热写冷的加速层（Redis 原子计数 + 最终一致 + 异步批量）。错误码 6001 与 record_like 表结构以审批版 §4.6/§4.8/§6.2 为唯一依据。
- **add-leaderboard-module**：榜单是校验闭环的收口，依赖校验引擎（事件）与好友模块（好友列表 Feign）。ZSet `leaderboard:overall`（member=userId，score=累计 pass 里程）为热读层；leaderboard_contribution 行为权威源与回滚锚点（Redis ZSet + 事件驱动最终一致 + 定时防重）。事件 Tag 与表结构以审批版 §4.5/§6.2/§7.1/§7.5 为唯一依据。
- **add-leaderboard-service**：架构重构，不新增业务功能，把已实现的榜单从 record-service 平移至独立服务，服务数 4→5。「为什么 5 个服务」「榜单为什么独立」成为架构决策 ADR（数据热点隔离、读多写少独立扩缩容、独立降级面）。贡献表归属默认复用 record_db（最小改动），独立 leaderboard_db 为可选项。路由前缀 /record/api/leaderboard → /leaderboard/** 为破坏性变更，需兼容期过渡。
- **add-load-test-report**：不新增业务功能，聚焦「真实数据 + 优化因果」沉淀。指标阈值（90%/95%/200ms）与压测并发档位（100/500/1000）以审批版 §8.2/§9 T12/§12.3 第 9 项与 A1 项为唯一依据。已确认不购云服务器：压测在本地 Docker Compose 环境执行，结论按本地单机能力如实标注（诚实口径）。
- **add-observability**：运维增强，不改变业务功能；指标口径对齐压测报告，形成「即时观测 + 历史实录」双层证据。/actuator/prometheus 本地演示直连；生产安全（网关不转发 actuator、内网抓取、最小权限）属「讲设计」范畴。监控栈选型（Prometheus+Grafana）理由随 ADR 记录。
- **add-rule-grayscale**：把「阈值可配」升级为「版本化灰度发布」，落地审批版 §7.4 全部流程。rule_version 表已建（sql/03-verify-db.sql），本变更不迁移表结构。采样键 userId%100 与审批版 §7.4 一致；灰度观察期用库内快照避免与 Nacos 动态刷新竞态。
- **add-jwt-auth**：收口「骨架无认证」技术债，建立鉴权能力域。双 token + refresh rotation（Redis `GETDEL` 原子消费防重放，access 15min / refresh 7d）；网关统一鉴权（身份由网关以 X-User-Id 唯一认定，下游不信任调用方自报）；用户数据隔离（越权访问他人资源 → 403/1002）；auth.enabled 降级开关（默认关闭降级为显式携带 userId 的旧行为，迁移成本可控）。引用 docs/adr/0007。
- **add-mapmatch-service**：新增独立 mapmatch-service（第 6 服务），把道路拓扑匹配从「讲设计」升级为实锤（执行计划 P2 天花板项），服务数 4→5→6。R5 是首个依赖外部服务的规则（verify 侧 `R5OffRoadRule` 实现 Rule 接口 + Feign 调 mapmatch，Rule 接口可扩展性首获远程规则红利，聚合框架零改动）；真实 OSM 单城市切片 + PostGIS（GIST+R 树）承载路网；R5 默认 SOFT、极端偏离升级 HARD，mapmatch 不可用时熔断降级为「不命中」不阻断校验主链路。引用 docs/adr/0006。
- **add-admin-rbac**：治理面鉴权，与 add-jwt-auth 分工：jwt 管业务面「认身份」，本变更管治理面「授权」（能改规则、能翻案）。USER/ADMIN 最小角色模型（注册默认 USER，ADMIN 仅内部接口显式授予）；access token 携带 role claim（由签发端背书，不信任外部传入）；`/admin/**` 与规则版本接口（RuleVersionController）仅 ADMIN 可达（普通用户 403/1002、未登录 401/1001）；白名单从「裸放行」改为「进链校验角色」；app.auth.admin.enabled 默认启用。引用 docs/adr/0007。
- **add-sport-type-threshold**：引擎从单一运动类型走向多运动类型阈值（消除 GenSamples 已知局限）。语义要点：未知/缺失类型保守回退 RUNNING，与历史行为一致；阈值分类型与灰度路由正交（灰度按 userId%100 路由版本，版本快照内部再按类型分维度，不改灰度逻辑）；rules_json 嵌套升级向后兼容（旧快照缺类型维度时回退单套阈值，仍可解析）。「规则链判定（R1-R4）」本身未改动，仅为其叠加类型维度。
- **add-login-lockout**：账号锁定能力域。口径更正说明：本需求此前长期处于「讲设计」状态（审批版列为能力项、实现只到计数+告警），本次由 add-login-lockout 在代码与规范两侧同时收口，这正是勘误 3 要解决的口径矛盾。实现事实：同一手机号在窗口期（默认 15min）内连续失败达阈值（默认 5 次）→ 写 `auth:lock:{phone}`（Redis + TTL 锁定时长 15min）；达阈值后拒绝登录，不校验密码、不消耗 BCrypt、不更新失败计数；锁定期满 TTL 到期自动解锁；登录成功清除失败计数与锁定键；锁定时「用户不存在」与「密码错误」统一返回（防撞库探测）。
- **add-two-level-cache**：规则二级缓存能力域，落地审批版 §7.3。为什么两级：Caffeine 本地低延迟（热路径不访问 Redis/DB）+ Redis 跨实例共享（本实例 miss 可命中他实例已回填副本，减少打库）。三防护各自手段：穿透=空值哨兵（短 TTL 5s，DB 查不到也缓存「确认无数据」）；击穿=Redisson `lock:rule-rebuild:{key}` 互斥重建（仅持锁实例查库，锁等待 3s 短超时）；雪崩=Redis TTL 随机抖动（60s ± 10s，批量写入错峰过期）。失效策略取舍：Nacos/版本变更监听精准失效 Caffeine invalidate + Redis DEL，TTL 只做兜底上界（回滚 ≤60s 收敛）；广播不可用时降级为 TTL 收敛，不产生新故障面。Redis 不可用一律降级「只走 Caffeine + DB」不报错不阻断校验主链路。二级缓存仅覆盖规则快照/灰度路由读路径；判定结果缓存维持单层 Caffeine（有状态机幂等兜底）。引用 docs/adr/0008。
- **add-request-validation**：入参校验与 HTTP 错误契约。此前 DTO 无校验注解、@Valid 是死代码，畸形 JSON/参数类型不匹配/缺参落入 Exception 兜底返回 500（把调用方入参问题误呈现为服务端故障）。本变更：DTO 声明式校验注解（auth 手机号/密码、record requestId/sportType/轨迹点上限 20000、规则比例 0-100 等）+ 写接口统一 @Valid；GlobalExceptionHandler 补 HttpMessageNotReadableException / MethodArgumentTypeMismatchException / MissingServletRequestParameterException → 400、NoHandlerFoundException → 404、HttpRequestMethodNotSupportedException → 405；service 层仅删与注解重复的判空，业务规则（状态机/幂等/枚举语义）保留；错误码总表沉淀至 docs/错误码表.md。引用变更 spec/changes/archive/add-request-validation/。
- **add-sentinel-dynamic-rules**：把网关流控从「代码加载、重启生效」升级为「Nacos 动态数据源、改阈值不重启即生效」。规则对象为 `GatewayFlowRule`（resource/count/intervalSec/grade），粒度与 `SentinelGatewayRuleConfig` 中的路由 ID 一致；dataId `gateway-flow-rules`（DEFAULT_GROUP）。两条兜底口径：无规则时回退代码默认 5000 QPS 基线（限流不缺省），推送坏 JSON 时保留上一版有效规则（不因瞬时坏配置抖断限流）。Sentinel 本身仍是本地 Caffeine 之外的独立组件，不参与规则二级缓存路径。
- **add-resilience-hardening**：Feign 容错标准化 + 内部接口凭证硬化。全局默认超时 connect 1000ms / read 3000ms（此前未配置的客户端走 Feign 默认 10s/60s，慢依赖可拖死整条链路）；每个 Feign 契约要么有 fallbackFactory、要么显式标注「不可软降级」——降级决策是产品语义而非实现细节：好友榜选**空榜**（反对「不过滤」，否则总榜非好友会泄露进好友榜）、RecordApi/AuthApi **不可软降级**（无轨迹无法判定、不可伪造 token/成功，fallback 只把故障转成 4007/4006 驱动重试或 DLQ）。内部接口从「仅网内拓扑信任」升级为「共享密钥校验」：服务本地 `/internal/**` 校验 `X-Internal-Token`（`app.internal.token` / 环境变量 `INTERNAL_API_TOKEN`，默认值仅本地演示），Feign 出站拦截器自动注入；网关白名单删除无路由的 `/internal/**` 死配置（原本不是漏洞，但未来误加 internal 路由会塌陷为可自提权）。新增错误码 4006-4008。引用 docs/adr/0007。
- **add-request-tracing**：请求贯穿标识，以 MDC 最小实现满足「一次请求可检索对齐」，保持 ADR-0003 决策不引入 SkyWalking/Zipkin/Sleuth。三层：① 网关 `RequestIdGlobalFilter` 保证存在 `X-Request-Id`（已有则沿用，否则生成 UUID），写响应头并透传下游（WebFlux 过滤器是 `GlobalFilter` 而非 Servlet Filter）；② 各业务服务 `TraceIdFilter` 写入 MDC 键 `traceId` 并在请求结束清理，统一日志 pattern `[%X{traceId}]`（record-service 经 properties 的 `logging.pattern.console`，故六服务口径一致）；③ MQ 侧 Producer 把 traceId 写入消息 userProperty（键 `X-Request-Id`）、Consumer 还原到 MDC 并在处理后清理，使 record→verify→leaderboard 跨服务异步链路共用同一 traceId。已知残余：verify→record 的 Feign 状态回调用例在 record 侧日志会生成新 traceId（MQ 主链路已串通）；traceId 刻意不打 Micrometer tag（高基数）。引用变更 spec/changes/archive/add-request-tracing/。
- **add-controlled-verify-entrypoint**：统一验收入口与门槛接线，起于一次 Harness 评审（窗口 2026-08-22~09-21，150 会话 / 502 Task Episode）。为什么要唯一入口：D12 事故证明"漏 `-s` 的 mvn 命令"会给出可信的假绿（换规范口径后 leaderboard 连依赖都解析不了，整批"通过"作废），而修正后的口径当时只活在台账散文里，README 与 CI 沿用的正是被作废的那一类命令；入口因此承担两件事——命令拼写唯一定义 + **调用构建前先打印并校验生效依赖来源**（离线仓缺失时以独立退出码失败，不与用例红混记）。四条新门槛步骤的落地过程本身成了两条口径教训：其一，`compose config -q` "在 HEAD 实测 0 退出"是在有 `.env` 的开发机上量的，干净检出下六个服务的 `env_file: [.env]` 会让该步在解析阶段就失败——机器态当仓库态，与本变更要堵的是同一类错误，故新增「环境文件不入库时门槛仍可解析」场景；其二，词面自检原先只看 markdown，实测 4 处命中在 java/yml 注释里，扩围后改为覆盖全部公开文本载体。真库 IT 与前端检查从"存在但没有路径"变为可触发（IT 缺环境变量按未覆盖计，不得记为通过；前端以 `--frozen-lockfile` + type-check + build 上门槛）。收口纪律同步升级：验收记录必须绑定 commit 与门槛来源，外部门槛结论变化时当场更新，不留过期判断。归档同一轮补记：前端生成物一致性判据已落地——提交真实的 `typed-router.d.ts`（原提交是 11 行手写桩且承重），并以手写的 `web/src/vue-router-auto-shim.d.ts` 供 `vue-router/auto` 的类型（vue-router@4.6 把该类型入口留成空占位、unplugin-vue-router@0.19.2 不写它），前端门槛由三条命令扩为四条。引用变更 spec/changes/archive/add-controlled-verify-entrypoint/。