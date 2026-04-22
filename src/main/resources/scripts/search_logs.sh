#!/bin/bash
export LANG=en_US.UTF-8

# ============================================
# kubectl 自动检测和查找函数
# ============================================
find_kubectl() {
  # 1. 先检查是否在 PATH 中
  if command -v kubectl >/dev/null 2>&1; then
    echo "kubectl"
    return 0
  fi

  # 2. 常见安装位置（按优先级搜索）
  local search_paths=(
    "/usr/local/bin/kubectl"
    "/usr/bin/kubectl"
    "/opt/homebrew/bin/kubectl"        # macOS Apple Silicon Homebrew
    "/home/linuxbrew/.linuxbrew/bin/kubectl"  # Linux Homebrew
    "$HOME/.local/bin/kubectl"
    "$HOME/bin/kubectl"
    "$HOME/.asdf/shims/kubectl"        # asdf 用户
    "/usr/local/kubebuilder/bin/kubectl"
    "$HOME/.krew/bin/kubectl"          # krew 插件管理器
    "/Applications/Docker.app/Contents/Resources/bin/kubectl"  # Docker Desktop
  )

  for path in "${search_paths[@]}"; do
    if [[ -x "$path" ]]; then
      echo "$path"
      return 0
    fi
  done

  # 3. 使用 find 在常见目录中搜索（慢但全面）
  echo "[Info] 正在搜索 kubectl ..." >&2
  # shellcheck disable=SC2155
  local found=$(find /usr/local /opt /home -name kubectl -type f 2>/dev/null | head -1)
  if [[ -n "$found" && -x "$found" ]]; then
    echo "$found"
    return 0
  fi

  return 1
}

# 检查并设置 kubectl
KUBECTL=$(find_kubectl)
if [[ $? -ne 0 || -z "$KUBECTL" ]]; then
  echo "[错误] 未找到 kubectl，请先安装："
  echo ""
  echo "  macOS:   brew install kubectl"
  echo "  Linux:   curl -LO https://dl.k8s.io/release/stable.txt/bin/linux/amd64/kubectl && chmod +x kubectl && sudo mv kubectl /usr/local/bin/"
  echo "  Windows: choco install kubernetes-cli"
  echo ""
  echo "或访问: https://kubernetes.io/docs/tasks/tools/"
  exit 1
fi

echo "[Info] 找到 kubectl: $KUBECTL"

# ============================================
# kubeconfig 检查
# ============================================
if [[ -z "$KUBECONFIG" ]]; then
  if [[ -f "$HOME/.kube/config" ]]; then
    echo "[Info] 使用默认 kubeconfig: ~/.kube/config"
  else
    echo "[警告] 未找到 kubeconfig 文件（~/.kube/config）"
    echo "[提示] 请设置环境变量: export KUBECONFIG=/path/to/your/kubeconfig"
  fi
else
  echo "[Info] 使用 kubeconfig: $KUBECONFIG"
fi

# ============================================
# 参数解析
# ============================================
if [[ $# -lt 2 ]]; then
  echo "用法: $0 <命名空间> <Pod名称> [关键字] [tail行数] [上下文行数] [实时滚动] [最近秒数]"
  echo ""
  echo "示例:"
  echo "  $0 devops-boot my-pod"
  echo "  $0 devops-boot my-pod ERROR 1000 5"
  echo "  $0 devops-boot my-pod \"\" 0 0 true 60"
  exit 1
fi

NAMESPACE=$1
POD=$2
KEYWORD=${3:-""}
TAIL=${4:-1000}
CONTEXT=${5:-0}
FOLLOW=${6:-false}
SINCE_SECONDS=${7:-0}

# ============================================
# 构建命令
# ============================================
CMD="$KUBECTL logs \"$POD\" -n \"$NAMESPACE\""

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

# awk 脚本（上下文分隔）
AWK_CMD="awk 'BEGIN { inblock=0 } /^--$/ { if (inblock) { print \"----- 上下文结束 -----\"; inblock=0 } next } { if (!inblock) { print \"----- 上下文开始 -----\"; inblock=1 } print } END { if (inblock) { print \"----- 上下文结束 -----\" } }'"

# 最终命令
if [[ -n "$KEYWORD" && "$CONTEXT" -gt 0 ]]; then
  END_CMD="$CMD | grep -C $CONTEXT \"$KEYWORD\" | $AWK_CMD"
elif [[ -n "$KEYWORD" ]]; then
  END_CMD="$CMD | grep \"$KEYWORD\""
else
  END_CMD="$CMD"
fi

echo "[Info] 命名空间: $NAMESPACE"
echo "[Info] Pod: $POD"
[[ -n "$KEYWORD" ]] && echo "[Info] 关键字: $KEYWORD"
echo "[Info] 执行命令：$END_CMD"
echo "=================================分割线================================="
eval "$END_CMD"
