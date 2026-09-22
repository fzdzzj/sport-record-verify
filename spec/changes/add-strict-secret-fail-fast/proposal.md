# 变更提案：add-strict-secret-fail-fast（TASK-126）

## 为什么改

全仓四处密钥落点存在硬编码兜底默认（本地演示值）：JWT 签名密钥（user-service `JwtUtil`、
gateway `JwtTokenParser` + `application.yml`）与内部接口共享密钥（common
`InternalApiAuthFilter`、api `InternalApiFeignInterceptor`）。演示默认值对本地开发必要
（零配置可起），但生产若漏注入密钥会静默回落到公开仓库里的字面值——等于没有密钥。
另外 `InternalApiAuthFilter` 用 `String.equals` 比较共享密钥，非常量时间，理论可计时探测。

## 改什么（变更概述）

新增全局开关 `app.security.strict`（默认 false，本地演示零扰动）：

- **strict=false（默认）**：四处行为与现状逐位一致，演示默认值原样保留。
- **strict=true**：任何密钥项缺失（解析结果为 null 或等于演示默认串）即启动失败——
  JWT 两侧构造期校验、内部接口两侧 `@PostConstruct` 校验，均抛 `IllegalStateException`；
  不新增依赖。
- `InternalApiAuthFilter` 共享密钥比较改 `MessageDigest.isEqual`（常量时间），行为等价。

## 能力影响

- 影响能力：鉴权安全域（对应主规格「内部接口共享密钥校验」「密钥默认值不得用于生产」
  Scenario 及 JWT 鉴权域的密钥配置口径）。
- 规格 delta：新增一条 Requirement（密钥注入严格模式），MODIFIED 无——既有需求文本
  「本地可有演示默认值」在 strict=false 下仍成立，严格模式是叠加项不是替换项。

## 判别式

红绿均以 `ApplicationContextRunner` 属性驱动（不引用新 API，红阶段可编译可运行）：

- strict=true 且不注入密钥 → 上下文启动失败（实现前启动成功 → 红；实现后失败 → 绿）。
- strict=true 且显式注入 → 启动成功（反向绿）。
- strict 缺省（false）→ 启动成功，既有测试全绿（零扰动证明）。

## 停止边界

不改各密钥 yml 默认值本身；不动 `InternalApiHeaders.TOKEN` 传递链路语义；
不引入新依赖；不 push。
