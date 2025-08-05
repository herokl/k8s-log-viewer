#!/bin/bash

# 参数 1：命名空间（必填）
NAMESPACE=$1

# 参数 2：Pod 名称（必填）
POD=$2

# 参数 3：关键字（可选，如果为空则不进行过滤）
KEYWORD=$3

# 参数 4：tail 行数（可选，默认 1000）
TAIL=${4:-1000}

# 参数 5：上下文行数（可选，默认 2）
CONTEXT=${5:-2}

# 参数 6：是否实时滚动（true/false，默认 false）
FOLLOW=${6:-false}

# 参数 7：多少秒以内的日志（可选，例如 300 表示最近5分钟）
SINCE_SECONDS=${7:-0}

# 构建基本命令
CMD="kubectl logs \"$POD\" -n \"$NAMESPACE\" --tail=\"$TAIL\""

# 如果设置为实时滚动，加上 -f 参数
if [[ "$FOLLOW" == "true" ]]; then
  CMD="$CMD -f"
fi

# 如果设置了 sinceSeconds 参数并且大于0，追加参数
if [[ "$SINCE_SECONDS" -gt 0 ]]; then
  CMD="$CMD --since=${SINCE_SECONDS}s"
fi

# 如果 KEYWORD 为空，直接输出原始日志
if [[ -z "$KEYWORD" ]]; then
  echo "[Info] 未指定关键字，直接输出原始日志..."
  eval "$CMD"
else
  echo "[Info] 关键字：$KEYWORD，上下文：$CONTEXT，过滤中..."
  eval "$CMD" \
  | grep -C "$CONTEXT" "$KEYWORD" \
  | awk '
    BEGIN { inblock = 0 }
    /^--$/ {
      if (inblock) {
        print "\n----- 上下文结束 -----\n"
        inblock = 0
      }
      next
    }
    {
      if (!inblock) {
        print "\n----- 上下文开始 -----\n"
        inblock = 1
      }
      print
    }
    END {
      if (inblock) {
        print "\n----- 上下文结束 -----\n"
      }
    }
  '
fi
