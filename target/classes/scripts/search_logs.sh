#!/bin/bash

# 参数 1：命名空间（必填）
NAMESPACE=$1

# 参数 2：Pod 名称（必填）
POD=$2

# 参数 3：关键字（可选）
KEYWORD=$3

# 参数 4：tail 行数（默认 1000）
TAIL=${4:-1000}

# 参数 5：上下文行数（默认 0）
CONTEXT=${5:-0}

# 参数 6：是否实时滚动（true/false）
FOLLOW=${6:-false}

# 参数 7：最近多少秒内日志（可选）
SINCE_SECONDS=${7:-0}

# 构建基本命令
CMD="kubectl logs \"$POD\" -n \"$NAMESPACE\""

if [[ "$TAIL" -gt 0 ]]; then
  CMD="$CMD --tail=${TAIL}"
fi

if [[ "$FOLLOW" == "true" ]]; then
    CMD="$CMD -f"
    echo "[Info] 实时滚动中 ..."
  else
    echo "[Info] 静态输出最近 $TAIL 行日志 ..."
fi

if [[ "$SINCE_SECONDS" -gt 0 ]]; then
  CMD="$CMD --since=${SINCE_SECONDS}s"
fi

# awk脚本写成一行，避免换行打印不全
AWK_CMD="awk 'BEGIN { inblock=0 } /^--$/ { if (inblock) { print \"----- 上下文结束 -----\"; inblock=0 } next } { if (!inblock) { print \"----- 上下文开始 -----\"; inblock=1 } print } END { if (inblock) { print \"----- 上下文结束 -----\" } }'"

# 打印分割线和完整命令
if [[ -n "$KEYWORD" && "$CONTEXT" -gt 0 ]]; then
  END_CMD="$CMD | grep -C $CONTEXT \"$KEYWORD\" | $AWK_CMD"
else
  END_CMD="$CMD"
fi

echo "[Info] 执行命令：$END_CMD"
echo "=================================分割线================================="
eval "$END_CMD"