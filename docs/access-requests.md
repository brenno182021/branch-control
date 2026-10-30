# Solicitações de acesso

Depois que o primeiro ADM configurar a aplicação, a tela de login oferece **Solicitar acesso** para pessoas sem conta.

## Para quem solicita

1. Abra Solicitar acesso.
2. Informe nome, login, senha e confirmação da senha.
3. Envie a solicitação e aguarde a aprovação.
4. Depois de aprovado, entre com o mesmo login e senha.

O login aceita de 2 a 40 letras sem acento, números, ponto, hífen e sublinhado, começando por letra ou número. A senha aceita maiúsculas, minúsculas e caracteres especiais, com mínimo de 8 caracteres e máximo de 72 bytes em UTF-8.

O solicitante não escolhe nem consulta os ambientes do servidor. A solicitação pendente não é uma conta ativa e não permite entrar. Um login já cadastrado ou com pedido pendente não pode gerar outra solicitação. Pedidos rejeitados podem ser enviados novamente se o login continuar disponível.

## Para o ADM

Na tela Ambientes, marque **Aprovar novos usuários** nos ambientes cujos membros poderão analisar solicitações. Essa permissão não concede operações Git, relatórios ou administração global de contas. O ADM já possui acesso à fila independentemente dessa marcação.

## Para quem aprova

A aba **Solicitações de acesso** mostra as pendências e sua quantidade. A fila é compartilhada entre os responsáveis, pois o ambiente só é definido ao aprovar. Ela atualiza automaticamente; o botão Atualizar permite consultar imediatamente.

Clique em Analisar, selecione o **ambiente inicial** e aprove. A seleção é obrigatória. Um usuário comum só pode escolher ambientes ativos onde seja membro com a permissão de aprovação. Um ADM pode escolher qualquer ambiente ativo.

A aprovação cria uma conta comum, habilitada, e inclui o novo usuário no ambiente escolhido. As permissões e branches autorizadas são as desse ambiente. Apenas o ADM pode promover posteriormente a conta para administrador global.

As permissões são compartilhadas pelos membros do ambiente. Se o ambiente escolhido permite aprovar novos usuários, o novo membro também recebe essa permissão; a tela destaca essa condição antes da aprovação.

Também é possível rejeitar um pedido, com motivo opcional. Pedidos analisados saem da fila de pendências. O banco mantém resultado, responsável e data da análise.

## Dados e segurança

Solicitações ficam na tabela relacional `bc_access_request`. A senha é protegida com bcrypt desde o envio e nunca é exibida para quem aprova. Após aprovação, o hash fica na conta; a cópia da solicitação é removida. Na rejeição, o hash da solicitação também é removido.

O servidor verifica a autorização atual ao analisar cada pedido. Escolher um ID de ambiente manualmente não concede acesso. A criação da conta, a associação ao ambiente e a conclusão da solicitação ocorrem na mesma transação. O envio exige a sessão CSRF da página e está sujeito a limites temporários contra envios repetidos.

A estrutura de uma instalação nova está em `database/schema.sql`. Instalações existentes precisam receber a nova tabela e a atualização da restrição de permissões por alteração manual do programador no PostgreSQL, com backup. A aplicação continua sem migrações ou alterações automáticas de estrutura.
