# TASK-106 handoff：CacheConfig 的 @Primary 补测（人造歧义哨兵）

结论：**补测成功**——新测试在「摘掉 `@Primary`」时当场变红，且有 `@Primary` 时绿；既有 7 条不受影响；
`CacheConfig.java` 生产代码零改（`git diff HEAD` 为空）。全仓 **284** 全绿。**无待主 agent 决定项**
（唯一需知会的是随附的「环境事故」一节：`.git` 对象库在本任务执行中途被批量删除，已完整恢复，与交付内容无关）。

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- leaderboard-service/src/test/java/com/sportverify/leaderboard/config/CacheConfigTest.java
- work/mailbox/tasks/TASK-106/handoff.md
- work/mailbox/PLAN.md
- scripts/verify/README.md

开工基线 `a3e6b27`（`git status` 事前仅 `?? .trae/`）。未 push、未建 PR（任务硬边界）。

## 补了什么（唯一新增的一条测试）

| 项 | 内容 |
| --- | --- |
| 新增测试方法 | `CacheConfigTest.typeLookupPrefersHierarchicalCacheManagerWhenAnotherCacheManagerExists`，声明于 **CacheConfigTest.java:106** |
| 私有 runner | `ambiguityRunner`，字段在 **CacheConfigTest.java:73**：`withUserConfiguration(CacheConfig.class)` + `withBean(RedisConnectionFactory.class, this::stubRedisConnectionFactory)` + `withBean("secondaryCacheManager", CacheManager.class, () -> new ConcurrentMapCacheManager(OVERALL_CACHE))` |
| 断言 | `assertThat(context.getBean(CacheManager.class)).isSameAs(context.getBean("hierarchicalCacheManager", CacheManager.class))`（在 `ambiguityRunner.run(...)` 内，`hasNotFailed()` 先置） |
| 未做的事 | 未往共享 `runner` 加 bean（否则 `onlyHierarchicalCacheManagerIsExposed` 的 `hasSingleBean` / `containsExactly` 会连带变红）；未断言「容器里有 2 个 CacheManager」（该计数断言有无 `@Primary` 都成立，测不到东西） |
| javadoc | 按文件既有风格：类级 `<ol>` 的「守 N 类静默失效」由 3 类改 4 类并新增第 4 条，新测试方法带完整 javadoc（守的形状不变量 + `@Primary` 为何不是装饰 + 为何只能靠人造歧义观测） |

## 红绿取证（先红后绿，均为实跑原文）

**基准（`HEAD` 版测试，全量唯一入口）**：`mvn-verify.sh --mode=offline test` → `rc=0` / `BUILD SUCCESS` /
`17 19 31 80 81 49 6 = 283`（leaderboard 49）——与任务书给的基线逐位一致。

**补测后定向跑**（裸 mvn 仅取证）：`CacheConfigTest` → `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`（8/8 绿）。

**变异（临时摘掉 `@Primary`）**：`grep -c '^    @Primary$'` 由 `1` → `0`；裸 mvn 单模块 → **rc=1**：

```
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0  -- in com.sportverify.leaderboard.config.CacheConfigTest
com.sportverify.leaderboard.config.CacheConfigTest.typeLookupPrefersHierarchicalCacheManagerWhenAnotherCacheManagerExists -- Time elapsed: 6.144 s <<< FAILURE!
java.lang.AssertionError:

Expecting:
 <Unstarted application context ...AssertableApplicationContext[startupFailure=java.lang.IllegalStateException]>
to have not failed:
but context failed to start:
 java.lang.IllegalStateException: No CacheResolver specified, and no unique bean of type CacheManager found. Mark one as primary or declare a specific CacheManager to use.
 	at org.springframework.cache.interceptor.CacheAspectSupport.afterSingletonsInstantiated(CacheAspectSupport.java:273)
 	...
 	at com.sportverify.leaderboard.config.CacheConfigTest.typeLookupPrefersHierarchicalCacheManagerWhenAnotherCacheManagerExists(CacheConfigTest.java:107)
```

- **红的落点**：方法 `CacheConfigTest.typeLookupPrefersHierarchicalCacheManagerWhenAnotherCacheManagerExists`，
  **CacheConfigTest.java:107**（`ambiguityRunner.run(...)` 那一行），失败的断言是其中的 `assertThat(context).hasNotFailed()`。
- **异常类型原文如实记录**：任务书预期 `NoUniqueBeanDefinitionException`，实测为
  `java.lang.IllegalStateException: No CacheResolver specified, and no unique bean of type CacheManager found...`，
  抛点在 `CacheAspectSupport.afterSingletonsInstantiated`。二者根因同一（容器内 `CacheManager` 非唯一且无 primary），
  只是 `@EnableCaching` 的缓存基础设在**容器启动期**就自己做了「唯一 bean」检查，比按类型注入更早失败，
  于是 `<context failed to start>` 先被 `hasNotFailed()` 抓住。**该差异为实测口径，未做修饰**；
  新测试 javadoc 已按实测原文书写（不是按预期书写）。
- **约束 1 成立**：本次变异只有新增那 1 条红（`Failures: 1` / 8 条），既有 7 条保持绿，共享 `runner` 未被污染。

**还原（加回 `@Primary`）**：

- `cp`（不带 `-p`）+ `touch` 还原；`cmp <备份> CacheConfig.java` → **rc=0（零差异）**；
  `git diff --stat HEAD -- CacheConfig.java` → **空**；
- 字节码取证：`javap -v -p -cp leaderboard-service/target/classes ...CacheConfig | grep -c Primary`
  → 变异 **0** / 还原 **2**（与任务书给的两点一致）；
- 还原后定向复跑 → `rc=0`、`CacheConfigTest` 8/8 绿。

**终验（还原后全量，唯一入口）**：`mvn-verify.sh --mode=offline test` → `rc=0` / `BUILD SUCCESS` /
**`17 19 31 80 81 50 6 = 284`**（leaderboard **49 → 50**，其余模块一条不多一条不少，`Failures 0 / Errors 0 / Skipped 0`）。

## 环境事故（与交付内容无关，但需知会）：`.git` 对象库被批量删除后已完整恢复

- **现象**：任务执行中途（10:50）`.git/refs/` 整目录消失、`.git/objects/` 只剩 6 个文件、
  两个 `pack-*.pack` 丢失（`.idx` 尚存）⇒ `git status` 报 `fatal: not a git repository`。
- **未受影响**：工作树**一个字节都没动**（事后 `git status --porcelain` 与事前逐字一致：仅本任务的文件 + `?? .trae/`）。
- **根因线索**：被删文件是**走回收站**的（`D:\$Recycle.Bin\<SID>\` 里 `$I`/`$R` 配对齐全，时间戳 10:50:07–10:50:42），
  且回收站里同时出现 `objects/maintenance.lock` / `AUTO_MERGE.lock` / `index.lock` 残影；
  与本仓 2026-09-13 那次 `git gc` 血案同源（该环境**沙箱对 `.git` 的写操作受限**，已在用户级备忘第 3 条记载）。
  本次触发点疑为一次 `git stash push`（**已改掉，本任务全程不再使用 `git stash`**）。
- **恢复动作（全部实测，未 re-clone、未丢任何提交）**：解析回收站 `$I` 元数据取原始路径，
  把对应 `$R` 拷回原位（只还原 `d:\code\sports\.git\` **带尾分隔符**、且**当天**时间戳的条目，
  含**整目录型**条目要递归拷贝）；重建 `.git/refs` 目录并按 `reflog` 权威值写回 `refs/heads/main`。
  校验闭环：`git fsck --no-reflogs` 无 broken link（仅剩 dangling）、`git rev-list --count HEAD` = **240**、
  `git log` 顶端 `a3e6b27`、`git status --porcelain` 与事前一致、`origin/main...main = 0 3`（恢复后即「本地领先 3」，
  与 TASK-117「未 push」吻合，且 `refs/remotes/origin/main` 也被一并找回）。- **顺带清理**（非本仓受版本控制内容）：误还原的旧 `.git-rewrite/` 与 2 个 0 字节 `tmp_pack_*` 已**移出**仓库，
  暂存于 `C:\Users\fzdzzj\.workbuddy\scratch\task106\moved-out\`（未删除）。
- **兜底已落地**：全历史备份 `C:\Users\fzdzzj\.workbuddy\scratch\task106\sport-record-verify-20260922.bundle`（`git bundle --all`，2.0 MB，在仓库外）。

## 契约自证

- 收口提交前脏树：`mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1**（TASK-106 此时仍只有 `spec.md`，
  未列入 `--open` ⇒ 判据 A 失败）；这正是白名单第 4 项要同步 `--open` 值的原因。
- 收口提交后：`mailbox-contract.sh --open=TASK-018` → 退出 **0**（见下方实测）。
- 暂存清单：`git diff --cached --name-only` 与白名单 4 项**完全一致**（无多报、无漏报）。
- 词面自检：CI 同款模式经 UTF-8 脚本承载后，**本任务 4 个改动文件 0 命中**（`LC_ALL=C` 与默认
  `C.UTF-8` 各跑一次均无命中）。附带发现：本机 MSYS `git grep -i` 在 `C.UTF-8` 下把字节 `0x8E`/`0x9E`
  视作大小写等价（cp1252 的 Ž/ž），使既有未触碰文件 `MapMatchResultDTO.java` 中「垂距」的「垂」
  （`0xE5 0x9E 0x82`）被误判为**词面自检的禁用词**命中（二者仅差第二字节；TASK-118 实测：该误判
  只在模式含多分支时复现）；
  `LC_ALL=C` 下全量 **ZERO-HIT**，判为 locale 伪影、非真命中。该处原文引用已由 TASK-118 改写为指代表述。

## 待主 agent 决定

**无。**（三点知会而无需裁决：① 上述 `.git` 事故已自愈且未影响交付；② 变异异常类型与任务书「预期」不同，
属实测口径且已逐字留证，测试语义未放宽；③ 未 push 属任务硬边界，外部门槛留待下次 push 由 CI 复验。）
