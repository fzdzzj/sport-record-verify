# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（规格模块枚举笔误补正：
「多模块工程结构」枚举括号内补 `mapmatch-service`，使"8 个可编译模块"与父 pom 实际 8 个 `<module>` 一致）。

## MODIFIED Requirements

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

---

## 备注

- 本变更仅修正「多模块工程结构」的模块枚举：括号内补 `mapmatch-service`，使"8 个可编译模块"
  与父 pom 8 个 `<module>`（common、api、gateway-service、user-service、record-service、
  verify-service、leaderboard-service、mapmatch-service）逐名一致。
- 不改动其他需求（「服务划分」已正确含 6 服务与 mapmatch-service，非本次范围）；不改任何代码与构建脚本。
- 本变更存在的意义即归档并入：验证通过后 MODIFIED 应用到主规格 L35，头部归档列表追加本变更名，
  目录整体移入 `spec/changes/archive/`。
