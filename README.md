# Branch Control

Interface web para preparar, simular e aplicar seleções de commits entre branches Git, com usuários, ambientes e histórico persistidos em PostgreSQL.

Código público: [brenno182021/branch-control](https://github.com/brenno182021/branch-control). Licença [MIT](LICENSE).

## Requisitos

No computador servidor: Git, Java 17 ou superior, Maven 3.6 ou superior, Node.js 18 ou superior compatível com a versão de esbuild do lockfile e PostgreSQL. PostgreSQL 18 é a versão usada para validação. Os navegadores dos usuários acessam esse servidor; os comandos Git usam os clones, chaves SSH e credenciais Git do computador servidor.

Execute os comandos abaixo na raiz do projeto. O workspace padrão é `./workspace`; clone nele os repositórios que serão usados pelos ambientes.

## Instalação no Windows

```powershell
git clone https://github.com/brenno182021/branch-control.git
cd branch-control
# Se psql não estiver no PATH, ajuste para sua instalação:
$env:PG_BIN = 'C:/Program Files/PostgreSQL/18/bin'
.\preparar-banco.bat
```

O script solicita a senha do administrador PostgreSQL e, ao criar o usuário da aplicação, sua nova senha. Não há senha padrão. Ele cria o banco e copia o exemplo para `config/database.properties` se esse arquivo ainda não existir. Preencha `DB_PASSWORD` com a senha do usuário da aplicação; confira URL, usuário e workspace.

O empacotamento não exige conexão com banco. Configure o banco da aplicação antes de executar as migrações ou iniciar o servidor.

```powershell
.\compilar.bat
.\migrar-banco.bat
.\iniciar.bat
```

`compilar.bat` e `compilar.sh` instalam as dependências frontend com `npm ci`, executam seus testes, geram o frontend e empacotam o backend com Maven usando `-DskipTests`. Não precisam de banco da aplicação nem de banco de testes para empacotar. Execute os testes de integração separadamente com `testar.bat` ou `./testar.sh`, quando houver PostgreSQL descartável configurado. `migrar-banco.bat` executa somente as migrações e encerra, sem iniciar HTTP ou executar operações Git. A inicialização normal também executa Flyway automaticamente.

Abra [http://127.0.0.1:8087](http://127.0.0.1:8087) **no computador servidor** para cadastrar o primeiro administrador global. Não há conta nem senha inicial predefinida. Em seguida, cadastre usuários e ambientes conforme [o modelo de permissões](docs/environments.md).

## Linux

Com os mesmos requisitos instalados:

```bash
./preparar-banco.sh
# Edite config/database.properties.
./compilar.sh
./migrar-banco.sh
./iniciar.sh
```

Se necessário, use `bash nome-do-script.sh` ou conceda permissão de execução aos scripts. `PG_BIN` pode apontar para o diretório que contém `psql`.

## PostgreSQL local exclusivo no Windows

Como alternativa a um serviço PostgreSQL existente ou ao Compose, é possível inicializar um cluster exclusivo em `data/postgres`, normalmente na porta 5433. Configure `PG_BIN` e `PG_PORT` em `config/local-postgres.properties` conforme [as instruções do banco](docs/database.md). `iniciar-postgres-local.bat` e `parar-postgres-local.bat` controlam somente esse cluster. Quando esse arquivo de configuração existe, `iniciar.bat` inicia o cluster automaticamente antes da aplicação. Pare a aplicação antes de parar o banco.

## PostgreSQL com Docker Compose

O Compose fornece o PostgreSQL; a aplicação é executada pelos scripts acima. Copie `.env.example` para `.env` e defina senhas próprias em `PG_ADMIN_PASSWORD` e `DB_PASSWORD`, além de `DB_NAME`, `DB_USER` e, se necessário, `DB_PORT`.

```bash
docker compose up -d postgres
```

O administrador PostgreSQL é separado do usuário da aplicação. A inicialização do volume cria o banco e o usuário da aplicação com privilégios limitados; não use a conta administrativa na aplicação. Configure também `config/database.properties` com os mesmos dados. `.env` é lido pelo Compose: a aplicação **não carrega esse arquivo**. Alterar variáveis do Compose não redefine usuários ou senhas de um volume já inicializado. Veja [banco e recuperação](docs/database.md).

## Configuração e acesso à rede

A aplicação lê `config/database.properties`, relativo à raiz de execução. Variáveis de ambiente `DB_URL`, `DB_USER` e `DB_PASSWORD` têm prioridade. `BRANCH_CONTROL_WORKSPACE` define a pasta dos clones. Use barras `/` nos caminhos Windows. Mantenha o arquivo real e as credenciais fora do Git.

A porta padrão é 8087. O filtro de rede restringe acesso a endereços locais/privados; isso não substitui firewall nem criptografia. Para acesso pela LAN, configure manualmente o firewall para a rede e porta necessárias. Para HTTPS, configure um keystore Spring Boot (`SERVER_SSL_ENABLED=true`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`, `SERVER_SSL_KEY_STORE_TYPE`) e `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`. Use um certificado confiável nos clientes. A senha do keystore deve ficar fora do repositório. Não exponha o serviço diretamente à Internet.

O sistema admite **uma instância da aplicação por banco/schema**, protegida por lease no PostgreSQL. Não é um hub para vários servidores Git e não configura Git nos computadores dos usuários.

## Testes reproduzíveis

Use um banco PostgreSQL exclusivo e descartável para os testes: a suíte pode recriar/apagar suas tabelas. Nunca aponte `TEST_DB_URL` para produção.

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/branch_control_test'
$env:TEST_DB_USER = 'branch_control_test'
$env:TEST_DB_PASSWORD = 'SENHA_DO_BANCO_DESCARTAVEL'
.\testar.bat
```

```bash
export TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/branch_control_test'
export TEST_DB_USER='branch_control_test'
export TEST_DB_PASSWORD='SENHA_DO_BANCO_DESCARTAVEL'
./testar.sh
```

Crie previamente esse banco/usuário usando `preparar-banco` com `DB_NAME` e `DB_USER` próprios. Em CI, forneça um PostgreSQL descartável e essas três variáveis. Os comandos são instruções de reprodução, não uma declaração de que a CI já passou.

## Dados e manutenção

Usuários, ambientes e execuções ficam no PostgreSQL; os snapshots JSONB são registros do banco, não arquivos JSON como armazenamento principal. Logs de execução também ficam no banco e têm espelho textual em `data/logs`. Consulte [schema, migração legada e backup](docs/database.md).

Não publique `.env`, `config/database.properties`, `config/local-postgres.properties`, `data/postgres/`, dumps, chaves, logs com dados sensíveis, clones de trabalho, `target/` ou `node_modules/`.
