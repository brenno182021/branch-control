# Comparação de commits

A prévia usa as branches do clone configurado no servidor. Ela mantém a ordem de ancestralidade dos commits de origem, exclui merges e respeita a data limite no fuso configurado.

Um commit não aparece na seleção quando já é ancestral do destino ou quando uma alteração equivalente já existe no histórico alcançável do destino. Cherry-pick normalmente gera outro hash; por isso a comparação também usa o patch estável da alteração, e não apenas o hash ou o título do commit.

A busca por equivalências inclui todo o histórico do destino, mesmo o trecho que passou a ser comum às duas branches após um merge. Assim, receber de volta na origem uma branch que contém o cherry-pick não faz o commit original reaparecer como pendente.

O limite de data seleciona os commits da origem. Ele não limita a busca de equivalentes no destino: uma alteração copiada depois da data limite também conta como já presente.

A verificação lê os patches do histórico do destino e pode demorar em repositórios grandes. O limite por operação Git é definido por `branch-control.timeout-seconds` em `application.properties` (padrão de 90 segundos). Se a leitura falhar ou ultrapassar o limite, a aplicação informa o erro em vez de apresentar uma lista parcial.

## Limites da equivalência

Patch equivalente significa a mesma alteração segundo o identificador estável do Git, que ignora diferenças de espaços em branco. Títulos iguais, sozinhos, não indicam equivalência.

Squash, alterações manuais ou resolução de conflitos podem produzir patches diferentes. Nesses casos, uma mudança de intenção semelhante ainda pode aparecer e precisa ser analisada e simulada. A busca histórica não determina se uma alteração foi posteriormente revertida.

## Qual versão das branches é comparada

A prévia registra os hashes da origem e do destino usados naquele momento. O TXT exportado inclui repositório, branches e esses hashes para conferência. Gere uma nova prévia depois de alterar o histórico.

O botão de atualização faz fetch de origin. Esse comando atualiza referências remotas; não avança automaticamente uma branch local. O destino é uma branch local do servidor, cujo estado precisa corresponder ao que se deseja receber os commits.

## Execução

A simulação usa uma cópia temporária. A aplicação real revalida a branch, o HEAD, as permissões e o estado do checkout antes de aplicar. A comparação não executa cherry-pick, merge, reset ou push.
