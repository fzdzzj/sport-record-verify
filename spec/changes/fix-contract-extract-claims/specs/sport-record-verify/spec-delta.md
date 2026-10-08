# spec-delta：fix-contract-extract-claims

变更 `fix-contract-extract-claims` 对主规格 `spec/specs/sport-record-verify/spec.md` 做以下 MODIFIED（替换既有「回传只改清单与工作树比对」Requirement；判据 A「派发—回传契约两件套完备」零改动）。

## MODIFIED Requirements

### Requirement: 回传只改清单与工作树比对

WHEN 一次回传声明了其只改文件清单,
系统 SHALL 仅提取清单小节内**列表行**中的文件声明，SHALL NOT 把否定句式行（零触碰 / 未碰 / 未触碰 / 不改 / 禁触）中的文件名当作声明，SHALL NOT 在清单小节缺失时回退整档扫描（此时该回传跳过清单比对并输出警示）。系统 SHALL 以「回传文件相对开工基线是否变动（diff 或 untracked）」判定在途回传：无变动的回传 SHALL 视为已收口，SHALL NOT 因其清单与当前工作树改动集文件名共占而重审；有变动的回传, 系统 SHALL 将其清单与工作树相对基线的实际改动文件集进行比对，并 SHALL 排除开工前已登记的本机或工具残留载体；清单与实际改动集存在差异时, 系统 SHALL 使校验失败，失败面覆盖清单多报与实际少报两种方向。WHEN 改动文件集来源不可判定, 系统 SHALL 以区别于契约失败的退出码失败，SHALL NOT 将其记为通过，也 SHALL NOT 将其记为用例失败。系统 SHALL NOT 允许一次在途回传的清单与实际改动集不一致而仍通过校验；SHALL NOT 允许已收口回传被文件名共占拉回在途比对而误报失败。

#### Scenario: 否定句式不入声明集

GIVEN 一份回传的清单小节含「显式零触碰：`pom.xml`」句式行
WHEN 提取该回传的只改清单声明
THEN `pom.xml` 不进入声明集
AND 该句式行不产出任何文件 token

#### Scenario: 已收口回传不被共占拉回比对

GIVEN 一份回传文件相对基线无变动（已收口）
WHEN 当前工作树改动集含其清单内的公共文件
THEN 该回传不重审、不判失败
AND 失败信号不指向该已收口回传

#### Scenario: 清单小节缺失不再回退全文

GIVEN 一份回传缺失清单小节
WHEN 提取该回传的声明
THEN 声明集为空并输出警示行
THEN 该回传跳过清单比对（不整档扫描）

#### Scenario: 在途回传清单与实际一致

GIVEN 一次在途回传（回传文件相对基线有变动）的只改清单等于工作树相对基线的改动文件集
WHEN 对该回传执行清单比对
THEN 校验通过
AND 无失败信号

#### Scenario: 多报未改动文件被拦截

GIVEN 一次在途回传的只改清单含工作树中实际未改动的文件
WHEN 执行清单比对
THEN 校验失败
AND 失败信号指向该多报文件

#### Scenario: 工作树存在未声明改动被拦截

GIVEN 一次在途回传对应的工作树实际改动文件集含其清单未声明的文件
WHEN 执行清单比对
THEN 校验失败
AND 失败信号指向该未声明改动

#### Scenario: 改动集来源不可判定不记通过

GIVEN 基线引用无法解析或非 git 上下文且未提供改动集来源
WHEN 执行清单比对
THEN 校验以区别于契约失败的退出码失败
AND 不记通过、不记用例失败
