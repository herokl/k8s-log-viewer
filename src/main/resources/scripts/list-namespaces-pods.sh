#!/bin/bash
echo "{ \"namespaces\": ["
first_ns=true
for ns in $(kubectl get ns -o jsonpath="{.items[*].metadata.name}"); do
  if $first_ns; then first_ns=false; else echo ","; fi
  echo "{\"name\":\"$ns\",\"pods\":["
  pods=$(kubectl get pods -n "$ns" -o jsonpath="{.items[*].metadata.name}")
  first_pod=true
  for pod in $pods; do
    if $first_pod; then first_pod=false; else echo ","; fi
    echo "\"$pod\""
  done
  echo "]}"
done
echo "]}"
