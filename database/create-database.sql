-- Execute com psql como administrador do PostgreSQL, conectado ao banco postgres.
-- Nomes podem ser substituídos com -v db_name=... -v db_user=... .
\set ON_ERROR_STOP on
\if :{?db_name}
\else
  \set db_name branch_control
\endif
\if :{?db_user}
\else
  \set db_user branch_control
\endif

SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=:'db_user') AS role_exists \gset
\if :role_exists
  \echo 'Usuario ja existe; a senha e os privilegios existentes serao preservados.'
\else
  SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', :'db_user') \gexec
  -- psql solicita e confirma a senha sem salva-la no script/historico.
  \password :db_user
\endif
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L', :'db_name', :'db_user', 'UTF8')
WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname=:'db_name') \gexec
SELECT pg_get_userbyid(datdba)=:'db_user' AS owner_matches FROM pg_database WHERE datname=:'db_name' \gset
\if :owner_matches
  \echo 'Banco pronto. Configure config/database.properties e execute migrar-banco.bat ou ./migrar-banco.sh.'
\else
  \echo 'ERRO: banco existente pertence a outro usuario. Nenhum banco foi sobrescrito. Escolha outro nome.'
  \quit 1
\endif
