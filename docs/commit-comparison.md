# Comparação de commits

A prévia usa as branches do clone configurado no servidor. Ela mantém a ordem de ancestralidade dos commits de origem, exclui merges e respeita a data limite no fuso configurado.

Um commit não aparece na seleção quando já é ancestral do destino ou quando uma alteração equivalente já existe no histórico alcançável do destino. Cherry-pick normalmente gera outro hash; por isso a comparação também usa o patch estável da alteração, e não apenas o hash ou o título do commit.

A busca por equivalências inclui todo o histórico do destino, mesmo o trecho que passou a ser comum às duas branches após um merge. Assim, receber de volta na origem uma branch que contém o cherry-pick não faz o commit original reaparecer como pendente.

O limite de data seleciona os commits da origem. Ele não limita a busca de equivalentes no destino: uma alteração copiada depois da data limite também conta como já presente.

A verificação lê os patches do histórico do destino e pode demorar em repositórios grandes. O limite por operação Git é definido por `branch-control.timeout-seconds` em `application.properties` (padrão de 90 segundos). Se a leitura falhar ou ultrapassar o limite, a aplicação informa o erro em vez de apresentar uma lista parcial.

O backend reaproveita os identificadores de patches em um cache limitado em memória, por repositório e contexto Git. Repetir uma comparação pode aproveitar esse cálculo; quando o destino avança sobre a mesma história, somente o trecho novo precisa ser indexado. A primeira consulta após iniciar o aplicativo ou invalidar o cache ainda pode demorar. Reiniciar o serviço descarta o cache, sem alterar dados no PostgreSQL.

As leituras desse índice são serializadas dentro da aplicação. Comparações simultâneas, inclusive de repositórios diferentes, podem aguardar o cálculo em andamento.

## Limites da equivalência

Patch equivalente significa a mesma alteração segundo o identificador estável do Git, que ignora diferenças de espaços em branco. Títulos iguais, sozinhos, não indicam equivalência.

Squash, alterações manuais ou resolução de conflitos podem produzir patches diferentes. Nesses casos, uma mudança de intenção semelhante ainda pode aparecer e precisa ser analisada e simulada. A busca histórica não determina se uma alteração foi posteriormente revertida.

## Qual versão das branches é comparada

A prévia registra os hashes da origem e do destino usados naquele momento. O TXT exportado inclui repositório, branches e esses hashes para conferência. Gere uma nova prévia depois de alterar o histórico.

O botão de atualização faz fetch de origin. Esse comando atualiza referências remotas; não avança automaticamente uma branch local. O destino é uma branch local do servidor, cujo estado precisa corresponder ao que se deseja receber os commits.

## Execução

A simulação usa uma cópia temporária. A aplicação real revalida a branch, o HEAD, as permissões e o estado do checkout antes de aplicar. A comparação não executa cherry-pick, merge, reset ou push.

## Aplicação parcial e push

Se uma prévia contém quatro commits e você seleciona e aplica somente um, esse commit sai da lista e os outros três continuam disponíveis, na mesma ordem. Os commits selecionados que o Git identificou como já presentes ou equivalentes também saem. Uma simulação não remove candidatos. Conflitos, falhas e commits ainda não processados permanecem pendentes.

O backend renova a prévia com outro identificador e o HEAD resultante da execução. Não é necessário listar novamente após uma aplicação normal. Para contas comuns, selecione os próximos commits e simule essa nova seleção antes de aplicar. A renovação não inclui commits novos que tenham entrado na origem depois da prévia original. Mudanças externas no destino ou nas permissões podem impedir essa renovação; nesse caso, confira o repositório e gere outra lista.

Após uma execução real concluída com pelo menos um commit aplicado, a tela pergunta se você deseja fazer push. **Agora não** mantém os commits apenas no clone do servidor. O botão **Enviar ao remoto (push)** no log permite solicitar o envio depois, enquanto o estado da branch continuar válido.

A confirmação exibe `origin`, a branch de destino e o HEAD que será enviado. O push publica todos os commits locais alcançáveis por esse HEAD que o remoto ainda não possui, inclusive commits anteriores à execução. Não é um envio isolado dos hashes selecionados.

Somente o responsável pela execução ou um ADM pode confirmar o envio, com a permissão `APPLY` no ambiente e acesso às branches envolvidas. O backend verifica novamente o ambiente, a branch ativa, o HEAD, alterações locais e operações Git pendentes. O destino é sempre a branch de mesmo nome em `origin`; tags e submódulos não são enviados automaticamente e não há force push.

O clone do servidor precisa ter `origin` e credenciais Git já configurados para envio sem interação. Uma rejeição do remoto ou falha de autenticação aparece como erro e é registrada no log da execução. Os cherry-picks locais e a lista restante são preservados. Corrija o problema no servidor antes de tentar novamente; a aplicação não faz merge, rebase ou force push para contornar rejeições.
