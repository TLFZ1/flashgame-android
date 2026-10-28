#!/usr/bin/env bash
# 公开前自检：工作区 / 暂存区 / 全部历史里有没有敏感串
#
# 用法：
#   tools/check-secrets.sh            # 检查工作区里已跟踪的文件（默认）
#   tools/check-secrets.sh staged     # 只检查暂存区（pre-commit 用）
#   tools/check-secrets.sh history    # 检查全部历史提交（推送公开仓库前必跑）
#
# 退出码：0 = 干净；1 = 发现问题；2 = 用法错误
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
PATFILE="$HERE/secret-patterns.txt"
ALLOWFILE="$HERE/secret-allowlist.txt"
MODE="${1:-work}"

[ -f "$PATFILE" ] || { echo "缺少 $PATFILE" >&2; exit 2; }
cd "$ROOT" || exit 2

# 读模式表：名称 -> 正则
declare -a NAMES REGEXES
while IFS= read -r line; do
  case "$line" in ''|'#'*) continue;; esac
  name="${line%%:*}"; re="${line#*:}"
  NAMES+=("$name"); REGEXES+=("$re")
done < "$PATFILE"

is_allowed() {   # $1 = 文件路径
  [ -f "$ALLOWFILE" ] || return 1
  local f pat
  f="$1"
  while IFS= read -r pat; do
    case "$pat" in ''|'#'*|'!'*) continue;; esac
    [[ "$f" =~ $pat ]] && return 0
  done < "$ALLOWFILE"
  return 1
}

content_allowed() {   # $1 = 命中内容；白名单里 ! 开头的是"内容"规则（放行文档里的示例值）
  [ -f "$ALLOWFILE" ] || return 1
  local c pat
  c="$1"
  while IFS= read -r pat; do
    case "$pat" in '!'*) pat="${pat#!}"; [[ "$c" =~ $pat ]] && return 0;; esac
  done < "$ALLOWFILE"
  return 1
}

mask() {   # 只显示前 6 位，避免把秘密刷到终端/日志里
  local s="$1"
  if [ "${#s}" -le 6 ]; then printf '%s' "***"; else printf '%s***' "${s:0:6}"; fi
}

fail=0
hits=0

report() {   # $1=来源 $2=行号 $3=名称 $4=匹配内容
  content_allowed "$4" && return 0
  hits=$((hits+1))
  fail=1
  printf '  %s:%s  [%s]  %s\n' "$1" "$2" "$3" "$(mask "$4")"
  [ "$hits" -ge 40 ] && { echo "  …（命中过多，已截断）"; exit 1; }
}

case "$MODE" in
  work)
    echo "== 检查工作区已跟踪文件 =="
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      is_allowed "$f" && continue
      # 跳过二进制
      if ! grep -Iq . "$f" 2>/dev/null; then continue; fi
      for i in "${!NAMES[@]}"; do
        while IFS= read -r m; do
          ln="${m%%:*}"; txt="${m#*:}"
          report "$f" "$ln" "${NAMES[$i]}" "$txt"
        done < <(grep -nE "${REGEXES[$i]}" "$f" 2>/dev/null | head -3)
      done
    done < <(git ls-files)
    ;;
  staged)
    echo "== 检查暂存区 =="
    diff="$(git diff --cached --unified=0 --no-color)"
    [ -z "$diff" ] && { echo "（暂存区为空）"; exit 0; }
    cur=""; ln=0
    while IFS= read -r line; do
      case "$line" in
        '+++ b/'*) cur="${line#+++ b/}";;
        '@@'*) ln="$(printf '%s' "$line" | sed -n 's/.*+\([0-9]*\).*/\1/p')";;
        '+'*)
          content="${line#+}"
          is_allowed "$cur" && continue
          for i in "${!NAMES[@]}"; do
            if [[ "$content" =~ ${REGEXES[$i]} ]]; then
              report "$cur" "$ln" "${NAMES[$i]}" "${BASH_REMATCH[0]}"
            fi
          done
          ln=$((ln+1));;
      esac
    done <<< "$diff"
    ;;
  history)
    echo "== 检查全部历史提交（git grep 遍历所有版本）=="
    REVS="$(git rev-list --all | head -300)"
    for i in "${!NAMES[@]}"; do
      while IFS= read -r m; do
        [ -z "$m" ] && continue
        # git grep 输出：<rev>:<file>:<line>:<内容>
        rest="${m#*:}"; f="${rest%%:*}"; rest="${rest#*:}"
        ln="${rest%%:*}"; txt="${rest#*:}"
        is_allowed "$f" && continue
        report "$f" "$ln" "${NAMES[$i]}" "$txt"
      done < <(git grep -nE "${REGEXES[$i]}" $REVS 2>/dev/null | head -3)
    done
    ;;
  *)
    echo "用法: $0 [work|staged|history]" >&2; exit 2;;
esac

if [ "$fail" -ne 0 ]; then
  echo
  echo "❌ 发现 $hits 处可疑内容。"
  echo "   · 误报：把匹配行加进 tools/secret-allowlist.txt 的路径白名单"
  echo "   · 真泄漏：删掉并提交；已被推送过则见 docs/SECURITY.md"
  exit 1
fi
echo "✅ 未发现可疑内容（模式文件：${#NAMES[@]} 条）"
exit 0
