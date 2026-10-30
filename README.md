# Branch Control

Aplicação web para preparar, simular e aplicar commits entre branches Git, com usuários, ambientes, permissões e histórico em PostgreSQL. Código público: [brenno182021/branch-control](https://github.com/brenno182021/branch-control), licença [MIT](LICENSE).

A comparação remove commits já presentes ou com patch equivalente no destino, inclusive quando o cherry-pick já faz parte do histórico comum das duas branches. Consulte [como a comparação funciona](docs/commit-comparison.md).

## Requisitos do servidor

Git, Java 17 ou superior, Maven 3.6 ou superior, Node.js 18 ou superior compatível com esbuild e PostgreSQL instalado no servidor ou executado via Docker. PostgreSQL 18 é a versão validada.

Os comandos Git utilizam os clones e as credenciais do servidor. Os computadores da rede acessam apenas a interface web.

## Instalação com PostgreSQL instalado

Instale e inicie o serviço PostgreSQL normalmente. O banco da aplicação ficará na mesma instância e no diretório de dados dos demais bancos.

No terminal CMD do Windows:

```bat
git clone https://github.com/brenno182021/branch-control.git
cd branch-control
set "PG_BIN=C:\Program Files\PostgreSQL\18\bin"
set "PGHOST=127.0.0.1"
set "PGPORT=5432"
preparar-banco.bat
```

Ajuste o caminho e a porta para sua instalação. O preparador solicita a senha administrativa, cria o usuário limitado e o banco, e executa `database/schema.sql` como o usuário da aplicação. A senha desse usuário é solicitada também para criar as tabelas. Não há senha padrão.

O SQL é destinado à **primeira instalação em um banco vazio**. Ele não apaga nem atualiza tabelas existentes. O aplicativo não cria clusters PostgreSQL, não executa migrações e não modifica automaticamente a estrutura do banco.

No Linux, use `./preparar-banco.sh` com as mesmas variáveis e requisitos.

## Configurar application.properties

O preparador copia o exemplo para `config/application.properties`. Edite esse arquivo com as propriedades padrão do Spring Boot:

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/branch_control
spring.datasource.username=branch_control
spring.datasource.password=SUA_SENHA
```

Use a porta da instância PostgreSQL e a senha definida na preparação. O ADM pode cadastrar repositórios em qualquer pasta acessível no servidor, inclusive em outros discos. Informe o caminho completo na tela Ambientes, por exemplo `C:/projetos/gpi` ou `D:/repositorios/sistema`. Não é necessário mover ou copiar o clone para dentro do Branch Control. Use barras `/` em caminhos Windows.

A aplicação não cria uma pasta para armazenar clones nem sugere diretórios. O caminho é informado diretamente pelo ADM. A conta que executa a aplicação precisa ter acesso ao clone cadastrado.

Mesmo que a interface seja acessada por outro computador da rede, os caminhos e os comandos Git sempre pertencem ao servidor onde o Branch Control está executando. O sistema não usa arquivos ou pastas do computador do navegador.

O Spring Boot carrega `config/application.properties` automaticamente ao iniciar na raiz do projeto. Também é possível ajustar `backend/src/main/resources/application.properties` antes de compilar. O arquivo externo evita recompilar para trocar a conexão e fica fora do Git. As variáveis padrão `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` podem substituir esses valores.

## PostgreSQL via Docker

Como alternativa ao serviço instalado, copie `.env.example` para `.env` e informe senhas próprias para `PG_ADMIN_PASSWORD` e `DB_PASSWORD`. Confira `DB_NAME`, `DB_USER` e `DB_PORT`.

```bat
docker compose up -d postgres
```

Em volume vazio, o container cria o usuário, o banco e as tabelas usando o mesmo `database/schema.sql`. Não execute o preparador novamente nesse banco. Os dados ficam em volume nomeado do Docker.

Copie `config/application.properties.example` para `config/application.properties` e configure a conexão com a porta publicada e as credenciais da aplicação. `.env` configura apenas o Compose; o Spring Boot utiliza `application.properties`. Alterar `.env` não altera senhas de um volume existente.

## Compilar e iniciar

No Windows:

```bat
compilar.bat
iniciar.bat
```

No Linux:

```bash
./compilar.sh
./iniciar.sh
```

A execução usa diretamente o banco configurado, sem perfil de teste. As tabelas precisam estar criadas antes de iniciar. Alterações futuras de estrutura são responsabilidade do programador e devem ser aplicadas manualmente no banco, com backup.

Abra [http://127.0.0.1:8087](http://127.0.0.1:8087) no próprio servidor e cadastre o primeiro administrador global. Não há conta inicial predefinida. Depois cadastre usuários e ambientes conforme [as permissões](docs/environments.md).

Pessoas sem conta podem usar **Solicitar acesso** na tela de login. O ADM e os membros de ambientes com **Aprovar novos usuários** recebem as pendências e escolhem o ambiente inicial ao aprovar. A pessoa só consegue entrar depois da aprovação. Veja [solicitações de acesso](docs/access-requests.md).

## Acesso pela rede

A porta padrão é 8087. Os navegadores da rede devem acessar `http://IP_DO_SERVIDOR:8087`. Configure o firewall e HTTPS conforme sua rede. O filtro da aplicação aceita endereços locais/privados; não exponha o servidor diretamente à Internet.

Para HTTPS, configure as propriedades normais do Spring Boot: `server.ssl.enabled`, `server.ssl.key-store`, `server.ssl.key-store-password`, `server.ssl.key-store-type` e `server.servlet.session.cookie.secure=true`. Use certificado confiável nos clientes.

A aplicação permite uma instância por banco/schema, protegida por lease PostgreSQL.

## Dados e manutenção

Toda persistência é relacional. Senhas de usuários são protegidas com hash bcrypt. Logs também têm espelho textual em `data/logs`; a pasta não contém uma instância PostgreSQL. Consulte [estrutura e backup](docs/database.md).

Não publique `config/application.properties`, `.env`, dumps, chaves, logs sensíveis ou clones de trabalho.
