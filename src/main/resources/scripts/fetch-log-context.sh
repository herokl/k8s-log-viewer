#!/bin/bash

NAMESPACE=$1
POD=$2
KEYWORD=$3
TAIL=${4:-1000}
CONTEXT=${5:-2}

kubectl logs "$POD" -n "$NAMESPACE" --tail="$TAIL" \
| grep -C "$CONTEXT" "$KEYWORD" \
| awk '
  BEGIN { inblock = 0 }
  /^--$/ { if (inblock) print "----- 上下文开始 -----"; inblock=0; next }
  {
    if (!inblock) print "----- 上下文开始 -----"
    print
    inblock = 1
  }
  END { if (inblock) print "----- 上下文结束 -----" }
'
