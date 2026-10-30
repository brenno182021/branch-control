#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
java_cmd="${BRANCH_CONTROL_JAVA_HOME:-${JAVA_HOME:-}}"
java_cmd="${java_cmd:+$java_cmd/bin/}java"
mkdir -p data/tmp
[[ -f backend/target/branch-control.jar ]] || { echo 'Compile primeiro com ./compilar.sh'; exit 1; }
exec "$java_cmd" -Dfile.encoding=UTF-8 -jar backend/target/branch-control.jar
