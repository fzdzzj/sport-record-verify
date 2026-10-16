# TASK-256 台账（handoff）

> **TASK**：E 系批4b-1——后端治理八项（E15+E18+E19+E20+E22+E25+E28+E30，FULL 档）
> **开工基线**：`<立项笔哈希——C-02 回填>`（main，立项笔后工作树干净）
> **方案**：2026-10-16 E 系批4b 指导侧立项侦察（12 项逐项亲验全部坐实），用户拍板「批4b 测试可观测」立项；批4b-1 后端八项
> **状态**：待实施回填（已立项待派发，2026-10-16 立项笔落库）

## 1. 开工自证（实施侧回填）

- 开工基线核验（git status --porcelain 空 / git log --oneline 基线核对 / rev-list count）：
- 白名单条件项定档（第 11 条 WebSocketNotificationConfigTest 扩展 vs 新建 StompConnectAuthInterceptorTest；R3 自代理形态①②）：

## 2. 只改清单全等声明（实施侧回填）

- git diff --name-only &lt;开工基线&gt; 与 spec §2 白名单双向全等核验：
- 未跟踪文件为零核验（git ls-files -o --exclude-standard）：

## 3. 门禁读数（实施侧回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端全模块 surefire（offline） | 待回填 | | 七模块读数与基线 59/75/205/302/195/108/35 对比只增不减 |
| 2 | 契约门 --open=TASK-256 --baseline=&lt;开工基线&gt; | 待回填 | | |
| 3 | token 门 29 项 | 待回填 | | 逐位持平或只增 |
| 4 | 词面门（模式文件法 + 探针阳性对照） | 待回填 | | ZERO_HIT + 探针命中 |
| 5 | 静态门（checkstyle delta=0） | 待回填 | | record 4 项 pre-existing 持平口径 |
| 6 | compose config | 待回填 | | 白名单零 compose 文件，可凭零 diff 论证 |
| 7 | diff 净 | 待回填 | | 新文件 LF+末尾换行 / 改文件保持行尾 |

## 4. 判别式逐项实施登记（实施侧回填）

- R1（E15）：
- R2（E18）：
- R3（E19）：
- R4（E20）：
- R5（E22）：
- R6（E25）：
- R7（E28）：
- R8（E30）：

## 5. 受限与偏差声明（实施侧回填，对照 spec §5 六项预注册）

## 6. 提交与哈希（实施侧回填）

- C-01（实施）：
- C-02（台账）：

## 7. 行尾与自证（实施侧回填）

- 新文件纯 LF + 完整末尾换行（末字节 0x0A）核验；改文件行尾保持；git diff --check / git show --check 净；.trae/tmp 临时件清理自证。
