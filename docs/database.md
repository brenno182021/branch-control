# PostgreSQL, migrações e recuperação

PostgreSQL é o armazenamento principal. O sistema não usa os antigos arquivos JSON para gravar novas contas ou execuções. A versão validada é PostgreSQL 18.

## Criar banco e usuário

No Windows, execute `preparar-banco.bat`; no Linux, `./preparar-banco.sh`. Instale `psql` no PATH ou defina `PG_BIN` como seu diretório. Os scripts aceitam `PGHOST`, `PGPORT`, `PGUSER` (administrador), `DB_NAME` e `DB_USER` (aplicação). Os valores padrão são `127.0.0.1`, `5432`, `postgres`, `branch_control` e `branch_control`.

O `psql -W` solicita a senha administrativa. `database/create-database.sql` cria o usuário de aplicação sem superusuário, criação de bancos, criação de papéis ou replicação; solicita e confirma sua senha interativamente. Esse usuário é proprietário do banco da aplicação. Se o usuário já existir, sua senha e privilégios são preservados. Um banco existente com outro proprietário causa erro e não é sobrescrito.

Preencha `config/database.properties`, criado a partir do exemplo, ou exporte `DB_URL`, `DB_USER` e `DB_PASSWORD`. Variáveis de ambiente têm prioridade. Exemplo de URL: `jdbc:postgresql://127.0.0.1:5432/branch_control`. A aplicação não lê `.env`; esse arquivo configura somente Docker Compose.

No Compose, `PG_ADMIN_PASSWORD` protege a conta administrativa e `DB_USER`/`DB_PASSWORD` identificam a conta limitada da aplicação. `DB_NAME` define seu banco e `DB_PORT` a porta publicada no loopback. A inicialização cria papéis apenas em volume vazio. Para bancos já existentes, altere senhas com ferramentas administrativas PostgreSQL e atualize a configuração da aplicação; editar `.env` sozinho não muda o volume.

## Cluster local exclusivo no Windows

Esta opção usa os executáveis PostgreSQL sem depender de um serviço Windows nem do Docker. Instale PostgreSQL e ajuste o caminho abaixo para sua instalação. Execute na raiz do projeto; inicialize somente um diretório novo/vazio:

```powershell
$env:PG_BIN = 'C:/Program Files/PostgreSQL/18/bin'
New-Item -ItemType Directory -Force ./data | Out-Null
& "$env:PG_BIN/initdb.exe" -D ./data/postgres -U postgres -A scram-sha-256 -W
```

`initdb -W` pede a senha da conta administrativa do novo cluster. Não use essa conta na aplicação. Crie `config/local-postgres.properties` com estes valores, ajustando o caminho conforme necessário:

```properties
PG_BIN=C:/Program Files/PostgreSQL/18/bin
PG_PORT=5433
```

Esse arquivo contém localização e porta, não senhas, e deve permanecer fora do Git por ser configuração local. Inicie o cluster e crie o banco/usuário limitado:

```powershell
.\iniciar-postgres-local.bat
$env:PGHOST = '127.0.0.1'
$env:PGPORT = '5433'
$env:PGUSER = 'postgres'
.\preparar-banco.bat
```

Preencha `DB_URL=jdbc:postgresql://127.0.0.1:5433/branch_control`, o usuário e sua senha privada em `config/database.properties`. Depois execute as migrações e inicie a aplicação normalmente. `iniciar.bat` chama automaticamente `iniciar-postgres-local.bat` quando `config/local-postgres.properties` existe. O script reconhece um cluster já iniciado e exige o marcador `data/postgres/PG_VERSION`; ele não inicializa nem assume outro cluster.

Para encerrar, pare primeiro a aplicação com Ctrl+C e então execute `parar-postgres-local.bat`. Os scripts usam `pg_ctl` sobre `data/postgres`; o início escuta somente em `127.0.0.1`. Preserve o diretório e o arquivo local fora do Git. Para backup portátil, prefira `pg_dump` conforme abaixo; não copie os arquivos de um cluster em execução.

## Criar e atualizar a estrutura

Compile o JAR com `compilar.bat` ou `./compilar.sh`; esses comandos usam `-DskipTests` no backend e não precisam de conexão PostgreSQL. Com o banco configurado, execute `migrar-banco.bat` ou `./migrar-banco.sh`. O runner executa Flyway e encerra sem servidor HTTP ou operações Git. A inicialização normal também executa as migrações pendentes. Em ambos os casos, a conexão precisa permitir criação e alteração de objetos no schema da aplicação.

Flyway registra versões em `flyway_schema_history`. `V1__branch_control.sql` cria contas, execuções, logs, controle de importação e locks. `V2__dynamic_environments.sql` converte os papéis atuais para `ADM`/`USER` e cria ambientes dinâmicos. Não edite migrações já aplicadas; evolua o schema com uma nova versão. Não há limpeza automática de banco habilitada.

| Tabela | Conteúdo |
| --- | --- |
| `bc_user` | Identificação, hash bcrypt, papel, habilitação e revisão da conta |
| `bc_environment` | Repositório, padrões e permissões JSONB, habilitação e revisão |
| `bc_environment_member` | Associação entre usuários e ambientes |
| `bc_execution` | Estado, autor histórico e snapshot JSONB da execução |
| `bc_execution_log` | Linhas de log ordenadas por sequência |
| `bc_legacy_import` | Marcador e contagens de importação legada |
| `bc_lock` | Serialização de alterações compartilhadas |

Os snapshots mantêm os dados históricos da execução; não substituem a verificação das permissões atuais. Logs também são espelhados em arquivos de texto em `data/logs`. Uma lease PostgreSQL impede duas instâncias da aplicação no mesmo banco/schema; use uma instância por banco/schema. Essa proteção não transforma o produto em serviço distribuído nem coordena clones de vários servidores.

## Importação legada explícita

1. Pare a instalação antiga e faça backup integral de seus dados e configurações.
2. Prepare um banco novo, sem usuários e execuções da aplicação. Mantenha os arquivos legados disponíveis em um diretório separado ou no antigo `data`.
3. Configure a conexão e defina `BRANCH_CONTROL_IMPORT_LEGACY=true` e `BRANCH_CONTROL_LEGACY_DIRECTORY=./data` (ou o diretório de origem).
4. Inicie a aplicação para realizar a importação; confira as contas, as execuções e os logs. Depois desative a opção de importação.

A importação preserva IDs e hashes das senhas e não remove os arquivos de origem. `RHF-ADM` vira `ADM`; os demais grupos legados viram `USER`. O histórico pode conservar o nome do grupo original no retrato do autor. Nenhum ambiente é criado automaticamente: o administrador precisa cadastrar repositórios, padrões e membros. O marcador de importação evita repetir a carga; não remova esse marcador para forçar uma segunda importação em banco ocupado.

## Backup e restauração

Pare a aplicação antes do backup para manter o banco, os clones e os arquivos de log consistentes. Preserve uma cópia de `config/database.properties`, configurações de certificados e `data/logs` em armazenamento protegido; não publique esses arquivos. Preserve também os clones do workspace se precisar recuperar modificações locais e branches ainda não enviadas ao remoto.

Com `pg_dump`, `pg_restore` e `psql` no PATH, usando a conta da aplicação:

```bash
pg_dump -h 127.0.0.1 -U branch_control -W -d branch_control -Fc -f branch-control.dump
```

Para restaurar, mantenha a aplicação parada, crie um banco vazio com o mesmo proprietário usando o script de preparação e execute:

```bash
pg_restore -h 127.0.0.1 -U branch_control -W -d branch_control --no-owner --exit-on-error branch-control.dump
```

Restaure configurações, logs e workspace apropriados, confira as permissões de arquivos e só então inicie a aplicação. Se usar outro proprietário, revise os grants e a configuração antes de iniciar. Restaure também `flyway_schema_history`; ela faz parte do dump completo. Valide periodicamente a restauração em um banco separado. Nunca versione dumps, senhas ou arquivos de configuração reais.

## Testes de banco

Os testes de integração são executados separadamente do empacotamento. `testar.bat` e `testar.sh` exigem as três variáveis de conexão. Crie um banco/usuário exclusivo e descartável, configure `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD` e execute `testar.bat` ou `./testar.sh`. A suíte pode apagar e recriar tabelas: não use o banco da aplicação. Em CI, forneça uma instância PostgreSQL descartável. Consulte os comandos completos no [README](../README.md).
