#!/usr/bin/env bash
# 统一 Maven 验收入口——本仓验收命令拼写的唯一定义处（README 与 CI 均引用本脚本）。
#
# 用法：
#   bash scripts/verify/mvn-verify.sh [--mode=auto|offline|online] [--pl <模块>] [--it] [test|verify|package]
#   bash scripts/verify/mvn-verify.sh [--mode=auto|offline|online] --static[=<模块>]
#
#   --mode=offline  强制 -o -s .mvn-settings.xml，并在调用 Maven 之前校验该 settings 声明的
#                   localRepository 目录真实存在（纯文本解析，不依赖额外插件）
#   --mode=online   剥离 -s 与 -o，依赖来源交由 Maven 自身解析；CI 用这一档作为对外权威口径
#   --mode=auto     settings 文件与其 localRepository 目录都在位时走 offline，否则走 online（默认）
#   --pl <模块>     只构建该模块及其上游，自动补 -am
#   --it            定向执行需要真实中间件（MySQL/Redis/RocketMQ）的端到端测试（缺环境变量时该测试被跳过，不记为通过）
#   --static[=模块] 静态检查三件套门槛（checkstyle:check / spotbugs:check / pmd:check），缺省模块
#                   leaderboard-service。两段执行：先 `-pl <模块> -am clean install -DskipTests` 把目标
#                   模块与上游装进本地仓——单模块 reactor 解析不到兄弟模块 SNAPSHOT（CI 里这些构件
#                   从未 deploy，尤其如此）；再对目标模块按 TASK-018 口径跑
#                   `test-compile checkstyle:check spotbugs:check pmd:check`，其中 spotbugs 用全限定
#                   GAV（其前缀不在 Maven 默认插件组内，插件只声明在目标模块 pom，取 pom 同版本定死）。
#                   本子命令不执行测试：用例门槛仍由 test/verify/package 模式承担。
#
# 退出码：
#   0    通过
#   2    参数用法错误（--static 与阶段/--it/--pl 冲突，或 <模块> 不是含 pom.xml 的模块目录名）
#   3    依赖来源不可判定——这是环境/依赖面失败，不是用例红，不得与测试混记
#   其余 Maven 原样退出码（1 = 构建或用例失败；静态检查违规也由 Maven 以 1 退出）
#
# 固定行为：请求的阶段前总是先执行 clean（--static 的 clean 在第 1 段），使结论不复用上一轮
# target/ 产物；本脚本不吞 Maven 的原始输出与退出码。
set -uo pipefail

SETTINGS_FILE=".mvn-settings.xml"
# 定向执行的端到端 IT 清单（逗号分隔类名）。常规 test/verify/package 路径完全不读本清单，
# 本扩展只作用于 --it 分支：把 --it 的 -Dtest= 从单类扩展为多类，依赖经 -am 一并在 leaderboard-service 汇总。
IT_MODULE="leaderboard-service"
IT_CLASSES="LeaderboardDailySummaryMapperMysqlIT,LeaderboardL2RedisRoundTripIT,RocketMqBrokerRoundTripIT"
IT_ENV_VARS="TASK108_IT_URL TASK108_IT_USER TASK108_IT_PASSWORD TASK110_IT_REDIS_HOST TASK110_IT_REDIS_PORT TASK110_IT_ROCKETMQ_NAMESRV TASK110_IT_ROCKETMQ_TOPIC"

usage() { sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; }

mode="auto"
phase=""
phase_given=0
pl_module=""
run_it=0
run_static=0
static_module=""

# spotbugs 前缀不在 Maven 默认插件组（org.apache.maven.plugins / org.codehaus.mojo）内，
# 且该插件只声明在目标模块 pom——用全限定 GAV 定死版本，避免前缀解析随调用上下文漂移。
SPOTBUGS_GAV="com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5"
STATIC_DEFAULT_MODULE="leaderboard-service"

while [ $# -gt 0 ]; do
  case "$1" in
    --mode=auto|--mode=offline|--mode=online) mode="${1#--mode=}" ;;
    --mode) [ $# -ge 2 ] || { echo "[verify-entry] --mode 缺少取值" >&2; exit 2; }; mode="$2"; shift ;;
    --pl) [ $# -ge 2 ] || { echo "[verify-entry] --pl 缺少模块名" >&2; exit 2; }; pl_module="$2"; shift ;;
    --it) run_it=1 ;;
    --static) run_static=1 ;;
    --static=*) run_static=1; static_module="${1#--static=}" ;;
    -h|--help) usage; exit 0 ;;
    test|verify|package)
      [ -z "$phase" ] || { echo "[verify-entry] 只能指定一个阶段，已取 $phase，又收到 $1" >&2; exit 2; }
      phase="$1"; phase_given=1 ;;
    *) echo "[verify-entry] 无法识别的参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
  shift
done

case "$mode" in auto|offline|online) ;; *) echo "[verify-entry] --mode 只接受 auto|offline|online，收到 $mode" >&2; exit 2 ;; esac

# --static 子命令参数校验：与阶段/--it/--pl 互斥；模块名必须是含 pom.xml 的模块目录名。
if [ "$run_static" -eq 1 ]; then
  if [ "$phase_given" -eq 1 ]; then
    echo "[verify-entry] --static 不接阶段参数（三 goal 口径固定），收到 $phase" >&2
    exit 2
  fi
  if [ "$run_it" -eq 1 ] || [ -n "$pl_module" ]; then
    echo "[verify-entry] --static 与 --it/--pl 互斥（目标模块用 --static=<模块> 给出）" >&2
    exit 2
  fi
  [ -n "$static_module" ] || static_module="$STATIC_DEFAULT_MODULE"
  if [ ! -f "$static_module/pom.xml" ]; then
    echo "[verify-entry] 无法识别的静态检查模块：$static_module（须为含 pom.xml 的模块目录名；缺省 $STATIC_DEFAULT_MODULE）" >&2
    exit 2
  fi
fi

[ -n "$phase" ] || phase="test"
# 真中间件端到端测试只需要 test 阶段；带上 --it 时其余阶段一律不连带跑。
if [ "$run_it" -eq 1 ] && [ "$phase" != "test" ]; then
  echo "[verify-entry] --it 会把阶段收为 test（忽略 $phase）" >&2
  phase="test"
fi

# 纯文本解析 settings 的 localRepository：跳过 XML 注释区域（含跨行注释），取首个匹配值。
parse_local_repository() {
  tr -d '\r' < "$1" | awk '
    {
      line = $0; out = ""; done = 0
      while (!done && length(line) > 0) {
        if (com == 0) {
          p = index(line, "<!--")
          if (p == 0) { out = out line; done = 1 } else { out = out substr(line, 1, p - 1); line = substr(line, p + 4); com = 1 }
        } else {
          p = index(line, "-->")
          if (p == 0) { done = 1 } else { line = substr(line, p + 3); com = 0 }
        }
      }
      if (out ~ /<localRepository>/) {
        gsub(/.*<localRepository>/, "", out); gsub(/<\/localRepository>.*/, "", out)
        print out; exit
      }
    }'
}

# 把 Windows 反斜杠与盘符写法归一成 shell 能 test 的路径，仅用于存在性判定，不改写 settings。
to_shell_path() {
  local raw="$1"
  raw="${raw//\\//}"
  printf '%s' "$raw"
}

settings_exists=0; [ -f "$SETTINGS_FILE" ] && settings_exists=1
declared_repo=""
if [ "$settings_exists" -eq 1 ]; then
  declared_repo="$(parse_local_repository "$SETTINGS_FILE")"
fi
repo_dir_exists=0
if [ -n "$declared_repo" ] && [ -d "$(to_shell_path "$declared_repo")" ]; then
  repo_dir_exists=1
fi

if [ "$mode" = "auto" ]; then
  if [ "$settings_exists" -eq 1 ] && [ "$repo_dir_exists" -eq 1 ]; then
    mode="offline"
  else
    mode="online"
  fi
fi

settings_label="未注入 -s（依赖来源交由 Maven 自身解析）"
repo_label="未解析（online 模式不读仓内 settings）"
mvn_args=(-B -ntp)

if [ "$mode" = "offline" ]; then
  settings_label="$SETTINGS_FILE"
  if [ "$settings_exists" -ne 1 ]; then
    echo "[verify-entry] 依赖来源不可判定：模式 offline 需要 $SETTINGS_FILE，期望路径 $PWD/$SETTINGS_FILE 不存在" >&2
    echo "[verify-entry] 这是环境/依赖面失败（退出码 3），不是用例红；请改用 --mode=online 或补出该 settings 文件" >&2
    exit 3
  fi
  if [ -z "$declared_repo" ]; then
    echo "[verify-entry] 依赖来源不可判定：$SETTINGS_FILE 里解析不到 <localRepository>（期望一个文本值）" >&2
    exit 3
  fi
  repo_label="$declared_repo"
  if [ "$repo_dir_exists" -ne 1 ]; then
    echo "[verify-entry] 依赖来源不可判定：$SETTINGS_FILE 声明的本地仓库目录不存在" >&2
    echo "[verify-entry]   期望路径：$declared_repo" >&2
    echo "[verify-entry]   实际路径：$(to_shell_path "$declared_repo")（按当前工作目录 $PWD 解析）" >&2
    echo "[verify-entry] 未调用 Maven，退出码 3；这不是一次通过的验收，也不是用例红" >&2
    exit 3
  fi
  mvn_args+=(-o -s "$SETTINGS_FILE")
fi

if [ "$run_static" -eq 1 ]; then
  echo "[verify-entry] 生效模式：$mode"
  echo "[verify-entry] settings 路径：$settings_label"
  echo "[verify-entry] localRepository：$repo_label"
  echo "[verify-entry] 静态检查目标模块：$static_module"
  echo "[verify-entry] 第 1/2 段命令全文：mvn ${mvn_args[*]} -pl $static_module -am clean install -DskipTests"
  mvn "${mvn_args[@]}" -pl "$static_module" -am clean install -DskipTests
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "[verify-entry] 第 1/2 段（目标模块与上游装料）以退出码 $rc 结束（模式 $mode）" >&2
    exit $rc
  fi
  echo "[verify-entry] 第 2/2 段命令全文：mvn ${mvn_args[*]} -f $static_module/pom.xml test-compile checkstyle:check $SPOTBUGS_GAV:check pmd:check"
  mvn "${mvn_args[@]}" -f "$static_module/pom.xml" test-compile checkstyle:check "$SPOTBUGS_GAV:check" pmd:check
  rc=$?
  [ $rc -eq 0 ] || echo "[verify-entry] Maven 以退出码 $rc 结束（模式 $mode）；静态检查违规或构建失败见上方输出" >&2
  exit $rc
fi

mvn_args+=(clean "$phase")
if [ "$run_it" -eq 1 ]; then
  mvn_args+=(-pl "$IT_MODULE" -am "-Dtest=$IT_CLASSES" "-Dsurefire.failIfNoSpecifiedTests=false")
elif [ -n "$pl_module" ]; then
  mvn_args+=(-pl "$pl_module" -am)
fi

echo "[verify-entry] 生效模式：$mode"
echo "[verify-entry] settings 路径：$settings_label"
echo "[verify-entry] localRepository：$repo_label"
echo "[verify-entry] 命令全文：mvn ${mvn_args[*]}"
if [ "$run_it" -eq 1 ]; then
  missing=""
  for v in $IT_ENV_VARS; do
    [ -n "${!v:-}" ] || missing="$missing $v"
  done
  if [ -n "$missing" ]; then
    echo "[verify-entry] 真中间件端到端测试前提缺失：$missing（该测试会被 assume 跳过，按口径记为未覆盖，不得计入通过）" >&2
  fi
fi

mvn "${mvn_args[@]}"
rc=$?
[ $rc -eq 0 ] || echo "[verify-entry] Maven 以退出码 $rc 结束（模式 $mode）；来源判定见上方打印" >&2
exit $rc
