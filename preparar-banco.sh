#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
"${PG_BIN:+$PG_BIN/}psql" -X -W -d postgres -v ON_ERROR_STOP=1 -v "db_name=${DB_NAME:-branch_control}" -v "db_user=${DB_USER:-branch_control}" -f database/create-database.sql
if [[ ! -e config/database.properties ]]; then cp config/database.properties.example config/database.properties; fi
echo 'Edite config/database.properties e execute ./migrar-banco.sh.'
