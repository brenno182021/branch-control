# Estrutura do PostgreSQL e backup

O Branch Control usa PostgreSQL instalado no servidor ou via Docker. A aplicação acessa o banco configurado em `application.properties` e não executa criação de tabelas, migrações ou alterações automáticas de schema.

## Primeira instalação

`database/create-database.sql` cria o usuário limitado e o banco. `database/schema.sql` contém a estrutura relacional completa e deve ser executado uma vez, como o proprietário do banco vazio. Os preparadores Windows/Linux e a inicialização Docker fazem esses passos.

Para executar somente a estrutura manualmente:

```bat
psql -X -h 127.0.0.1 -p 5432 -U branch_control -W -d branch_control -v ON_ERROR_STOP=1 -f database/schema.sql
```

O script usa transação e não contém comandos para apagar tabelas existentes. Executá-lo em estrutura já criada gera erro; não é um mecanismo de atualização. Ajustes posteriores devem ser feitos pelo programador diretamente no banco, após backup.

A instalação usa a configuração padrão do serviço PostgreSQL para armazenar os dados. No Docker, utiliza volume nomeado. O projeto não contém cluster nem diretório físico do PostgreSQL.

## Configuração

Exemplo de `config/application.properties`, carregado automaticamente pelo Spring Boot:

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/branch_control
spring.datasource.username=branch_control
spring.datasource.password=SUA_SENHA
branch-control.workspace-root=./workspace
```

O arquivo real não é versionado. A configuração padrão também está em `backend/src/main/resources/application.properties`. Não há perfil de teste para iniciar a aplicação. `spring.sql.init.mode=never` impede inicialização automática de schema.

## Tabelas

| Tabela | Conteúdo |
| --- | --- |
| `bc_user` | Conta, hash bcrypt, papel, habilitação e revisão |
| `bc_environment` | Nome, repositório, habilitação e revisão |
| `bc_environment_branch_pattern` | Padrões de branches por ambiente, em ordem |
| `bc_environment_permission` | Permissões de cada ambiente |
| `bc_environment_member` | Membros dos ambientes |
| `bc_execution` | Autor histórico, estado, plano e metadados |
| `bc_execution_candidate` | Commits candidatos do plano, em ordem |
| `bc_execution_selected` | Seleção, resultado e novo hash de cada commit |
| `bc_execution_log` | Linhas de log por sequência |
| `bc_lock` | Serialização de alterações compartilhadas |

Não há arquivos JSON para persistência nem colunas JSON/JSONB. O histórico preserva seus dados; a consulta continua sujeita às permissões atuais. Logs são armazenados no banco e espelhados em texto em `data/logs`.

## Backup e restauração

Pare a aplicação antes do backup. Preserve a configuração privada, certificados, logs e clones do workspace em armazenamento protegido.

```bash
pg_dump -h 127.0.0.1 -p 5432 -U branch_control -W -d branch_control -Fc -f branch-control.dump
```

Para restaurar, mantenha a aplicação parada e crie apenas um banco vazio com o proprietário adequado, sem executar `schema.sql`. O dump já contém estrutura e dados:

```bash
pg_restore -h 127.0.0.1 -p 5432 -U branch_control -W -d branch_control --no-owner --exit-on-error branch-control.dump
```

Ajuste host, porta e usuário para a instalação. Restaure configurações e workspace, confira permissões e só então inicie. Valide backups periodicamente em banco separado. Não versione dumps ou credenciais.
