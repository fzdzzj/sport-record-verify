#!/usr/bin/env bash
# 派发—回传契约校验入口（与 scripts/verify/mvn-verify.sh 并列，职责正交：Maven 入口验依赖与用例，
# 本脚本验台账契约）。把任务包"只改白名单 + 规定复跑命令 + 停止边界"从提示词文字落成可执行的校验。
#
# 用法：
#   bash scripts/verify/mailbox-contract.sh [options]
#
# 选项：
#   --ledger <dir>      任务台账目录（默认 <仓库根>/work/mailbox/tasks）
#   --open <task,...>   判据 A 放行的"仅 spec 无 handoff"进行中任务目录名列表（逗号分隔）
#   --baseline <ref>    判据 B 的 diff 基线（默认 HEAD，即开工基线）
#   --diff-file <path>  判据 B 的"实际改动集"来源：逐行路径文件；缺省用 git 工作树计算
#   -h|--help           打印本用法
#
# 判据 A（两件套完备）：任务目录须同时含 spec.md 与 handoff.md。
#   仅 spec.md → 判定进行中任务：声明于 --open 则列待办放行，否则判据 A 失败；
#   仅 handoff.md → 有回传无契约，判据 A 失败；两者皆无但目录非空 → 异常态，判据 A 失败；空目录忽略。
# 判据 B（清单比对）：以「回传文件相对开工基线是否变动」判定在途回传——handoff.md 相对基线
#   既无 diff 也非未跟踪 ⇒ 已收口，不重审（连共占计算都不做）；有变动才进入清单比对。
#   只改清单只提取「改动清单」节内的列表行 token，否定句式行（零触碰/未碰/未触碰/不改/禁触）
#   不产 token，清单节缺失时不回退整档扫描（该回传跳过比对并输出警示行）。
#   在途回传要求清单与实际改动集完全一致，清单多报或改动集未声明多出任一文件都判据 B 失败；
#   --diff-file 只解耦改动集来源，在途判定仍用 git 双查；非 git 上下文 + --diff-file 时
#   在途判定降级为共占口径并输出注明行；改动集来源不可判定 → 退出码 3。
#
# 退出码：0 通过 / 1 契约或结构不符 / 2 参数错 / 3 差异来源不可判定（不是契约红，也不得记为通过）
set -uo pipefail

LEDGER="work/mailbox/tasks"
OPEN_LIST=""
BASELINE="HEAD"
DIFF_FILE=""

usage() { sed -n '2,44p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --ledger=*)    LEDGER="${1#--ledger=}"; shift ;;
    --open=*)      OPEN_LIST="${1#--open=}"; shift ;;
    --baseline=*)  BASELINE="${1#--baseline=}"; shift ;;
    --diff-file=*) DIFF_FILE="${1#--diff-file=}"; shift ;;
    --ledger)    [ $# -ge 2 ] || { echo "[contract] --ledger 缺少取值" >&2; exit 2; }; LEDGER="$2"; shift 2 ;;
    --open)      [ $# -ge 2 ] || { echo "[contract] --open 缺少取值" >&2; exit 2; }; OPEN_LIST="$2"; shift 2 ;;
    --baseline)  [ $# -ge 2 ] || { echo "[contract] --baseline 缺少取值" >&2; exit 2; }; BASELINE="$2"; shift 2 ;;
    --diff-file) [ $# -ge 2 ] || { echo "[contract] --diff-file 缺少取值" >&2; exit 2; }; DIFF_FILE="$2"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "[contract] 无法识别的参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
done

# 仓库根：判据 B 用 git 计算改动集的位置；非 git 上下文时回退当前工作目录。
IN_GIT=1
if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  REPO_ROOT="$PWD"
  IN_GIT=0
else
  REPO_ROOT="$(git rev-parse --show-toplevel)"
fi

case "$LEDGER" in
  /*) ;;
  *) LEDGER="$REPO_ROOT/$LEDGER" ;;
esac
[ -d "$LEDGER" ] || { echo "[contract] 台账目录不存在：$LEDGER" >&2; exit 2; }

# 解析 --open 列表
declare -A IS_OPEN
if [ -n "$OPEN_LIST" ]; then
  in_ifs="$IFS"; IFS=","
  for t in $OPEN_LIST; do [ -n "$t" ] && IS_OPEN["$t"]=1; done
  IFS="$in_ifs"
fi

# ---------- 判据 A：两件套完备 ----------
judge_a_failed=0
declare -a returned_dirs=()
declare -a open_pending=()

while IFS= read -r d; do
  name="$(basename "$d")"
  [ -n "$name" ] || continue
  has_spec=0; has_handoff=0
  [ -f "$d/spec.md" ] && has_spec=1
  [ -f "$d/handoff.md" ] && has_handoff=1
  if [ "$has_spec" -eq 1 ] && [ "$has_handoff" -eq 1 ]; then
    echo "[contract] 两件套齐全：$name"
    returned_dirs+=("$d")
  elif [ "$has_spec" -eq 1 ]; then
    echo "[contract] 进行中（仅 spec）：$name"
    open_pending+=("$name")
    if [ "${IS_OPEN[$name]:-0}" -eq 1 ]; then
      echo "[contract]   - 已声明，列入待办放行"
    else
      echo "[contract]   - 未声明（--open 缺 $name），判据 A 失败" >&2
      judge_a_failed=1
    fi
  elif [ "$has_handoff" -eq 1 ]; then
    echo "[contract] 有回传无契约：$name" >&2
    judge_a_failed=1
  else
    if [ -n "$(ls -A "$d" 2>/dev/null)" ]; then
      echo "[contract] 异常态（无 spec 无 handoff 但目录非空）：$name" >&2
      judge_a_failed=1
    else
      echo "[contract] 空目录忽略：$name"
    fi
  fi
done < <(find "$LEDGER" -mindepth 1 -maxdepth 1 -type d | sort)

open_total="${#open_pending[@]}"
if [ "$open_total" -gt 0 ]; then
  printf '[contract] 待办进行中任务 %d 个：%s\n' "$open_total" "$(IFS=,; echo "${open_pending[*]}")"
fi

# ---------- 判据 B：改动集来源 ----------
# 提取「改动清单」节内的路径 token（path.ext 或 path.ext:line）。只认小节内列表行
#（`^\s*(-|\d+\.)\s` 开头）中的 token；行内命中否定句式（零触碰|未碰|未触碰|不改|禁触）
# 整行丢弃不产 token；小节缺失时不回退整档扫描（claims 为空，由调用方输出警示行并跳过）。
extract_claims() {
  local hf="$1" block
  block="$(awk '
    BEGIN{cap=0}
    /^#{1,6}[ \t]+/{
      if(cap==0){ if($0 ~ /只改|改动|文件清单/){ cap=1; next } }
      else{ cap=0 }
    }
    cap==1 && /^[[:space:]]*(-|([0-9]+\.))[[:space:]]/{print}
  ' "$hf")"
  printf '%s\n' "$block" \
    | grep -vE '零触碰|未碰|未触碰|不改|禁触' \
    | grep -oE '[A-Za-z0-9_./-]+\.(sh|md|java|kt|scala|groovy|js|jsx|ts|tsx|vue|json|ya?ml|xml|sql|patch|csv|txt|properties|css|html|d\.ts|example|editorconfig)' \
    | sed -E 's#^\./##' \
    | sort -u
}

# 构造实际改动集（ACTUAL）。有 --diff-file 用它；否则用 git 工作树。
declare -A ACTUAL
actual_origin=""
if [ -n "$DIFF_FILE" ]; then
  [ -f "$DIFF_FILE" ] || { echo "[contract] --diff-file 不存在：$DIFF_FILE" >&2; exit 2; }
  actual_origin="--diff-file $DIFF_FILE"
  while IFS= read -r line; do
    p="$(printf '%s' "$line" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s#^\./##')"
    [ -n "$p" ] && ACTUAL["$p"]=1
  done < "$DIFF_FILE"
else
  if [ "$IN_GIT" -ne 1 ]; then
    echo "[contract] 差异来源不可判定：非 git 上下文且未提供 --diff-file（退出码 3）" >&2
    exit 3
  fi
  if ! git -C "$REPO_ROOT" rev-parse --verify --quiet "$BASELINE^{commit}" >/dev/null 2>&1; then
    echo "[contract] 差异来源不可判定：基线 $BASELINE 无法解析（退出码 3）" >&2
    exit 3
  fi
  actual_origin="git diff --name-only $BASELINE + untracked（本机残留已排除）"
  while IFS= read -r f; do
    [ -n "$f" ] && ACTUAL["$f"]=1
  done < <(git -C "$REPO_ROOT" diff --name-only --diff-filter=ACMR "$BASELINE" 2>/dev/null || true)
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in .trae/*) continue ;; esac
    ACTUAL["$f"]=1
  done < <(git -C "$REPO_ROOT" ls-files --others --exclude-standard)
fi

# ---------- 判据 B：清单与实际改动集比对 ----------
# 前置在途判定门：handoff.md 相对基线既无 diff 也非未跟踪 ⇒ 已收口，不重审（连共占计算都不做）。
# 只有确认为在途回传才做清单比对。--diff-file 只解耦改动集来源，在途判定仍用 git 双查；
# 非 git 上下文（仅 --diff-file 可到此）+ 基线不可解析时在途判定降级为旧共占口径并注明。
judge_b_failed=0
for d in "${returned_dirs[@]}"; do
  name="$(basename "$d")"
  hf="$d/handoff.md"
  downgrade_note=0

  # --- 在途判定前置门 ---
  in_transit=0
  if [ "$IN_GIT" -eq 1 ] \
     && git -C "$REPO_ROOT" rev-parse --verify --quiet "$BASELINE^{commit}" >/dev/null 2>&1; then
    changed="$(git -C "$REPO_ROOT" diff --name-only "$BASELINE" -- "$hf" 2>/dev/null)"
    untracked_hit="$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "$hf" 2>/dev/null)"
    if [ -n "$changed" ] || [ -n "$untracked_hit" ]; then
      in_transit=1
    fi
  else
    # 非 git 上下文（仅 --diff-file 能到此）或基线不可解析：在途判定降级为旧共占口径
    in_transit=1
    downgrade_note=1
  fi

  if [ "$in_transit" -eq 0 ]; then
    echo "[contract] $name：已收口（回传文件无基线变动），不重审"
    continue
  fi

  claims="$(extract_claims "$hf")"
  if [ -z "$claims" ]; then
    echo "[contract] $name：回传未解析到改动清单，跳过判据 B（警示：无清单小节，未整档扫描）"
    continue
  fi

  if [ "$downgrade_note" -eq 1 ]; then
    echo "[contract] $name：非 git 上下文：在途判定降级为共占口径"
  fi

  unset C 2>/dev/null || true
  declare -A C
  for f in $claims; do [ -n "$f" ] && C["$f"]=1; done

  both=0; only_claims=0; only_actual=0
  for f in "${!ACTUAL[@]}"; do
    if [ "${C[$f]:-0}" -eq 1 ]; then both=$((both+1)); else only_actual=$((only_actual+1)); fi
  done
  for f in "${!C[@]}"; do
    [ "${ACTUAL[$f]:-0}" -eq 1 ] || only_claims=$((only_claims+1))
  done

  # 降级口径下以 both>0 复判在途；git 口径下已由前置门确认在途，直接比对。
  if [ "$downgrade_note" -eq 1 ] && [ "$both" -eq 0 ]; then
    echo "[contract] $name：足迹不在工作树，视为已收口，不重审"
    unset C
    continue
  fi

  if [ "$only_claims" -gt 0 ] || [ "$only_actual" -gt 0 ]; then
    echo "[contract] $name：判据 B 失败（在途回传只改清单与实际改动集不一致；改动源 $actual_origin）" >&2
    if [ "$only_claims" -gt 0 ]; then
      for f in "${!C[@]}"; do
        [ "${ACTUAL[$f]:-0}" -eq 1 ] || echo "[contract]   清单多报（实际未改动）：$f" >&2
      done
    fi
    if [ "$only_actual" -gt 0 ]; then
      for f in "${!ACTUAL[@]}"; do
        [ "${C[$f]:-0}" -eq 1 ] || echo "[contract]   改动集未声明（工作树改动未进只改清单）：$f" >&2
      done
    fi
    judge_b_failed=1
  else
    echo "[contract] $name：判据 B 通过（只改清单与实际改动集一致）"
  fi
  unset C
done

if [ "$judge_a_failed" -eq 1 ] || [ "$judge_b_failed" -eq 1 ]; then
  echo "[contract] 契约校验失败（退出码 1）：判据 A=$judge_a_failed 判据 B=$judge_b_failed" >&2
  exit 1
fi
echo "[contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 $open_total 个待办进行中）+ 判据 B 清单一致"
exit 0