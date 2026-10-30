#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
[[ -z "${BRANCH_CONTROL_JAVA_HOME:-}" ]] || export JAVA_HOME="$BRANCH_CONTROL_JAVA_HOME"
pushd frontend >/dev/null
npm ci --no-fund --no-audit
npm test
npm run build
popd >/dev/null
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
mvn -B -ntp -s config/maven-settings.xml -f backend/pom.xml -DskipTests clean package
