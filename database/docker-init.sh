#!/usr/bin/env bash
set -euo pipefail
# Runs only on an empty PostgreSQL volume. Secrets come from .env/Compose.
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v app_name="$APP_DB_NAME" -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', :'app_user', :'app_password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L', :'app_name', :'app_user', 'UTF8') \gexec
SQL
