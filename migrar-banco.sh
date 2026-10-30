#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
java_cmd="${BRANCH_CONTROL_JAVA_HOME:-${JAVA_HOME:-}}"
java_cmd="${java_cmd:+$java_cmd/bin/}java"
[[ -f backend/target/branch-control.jar ]] || { echo 'Compile o projeto primeiro.'; exit 1; }
"$java_cmd" -Dfile.encoding=UTF-8 -jar backend/target/branch-control.jar --migrate-only
