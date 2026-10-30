#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
: "${TEST_DB_URL:?Configure um PostgreSQL exclusivo de testes}"
: "${TEST_DB_USER:?Configure TEST_DB_USER}"
: "${TEST_DB_PASSWORD:?Configure TEST_DB_PASSWORD}"
pushd frontend >/dev/null
[[ -d node_modules ]] || npm ci --no-fund --no-audit
npm test
popd >/dev/null
mvn -B -ntp -s config/maven-settings.xml -f backend/pom.xml test
