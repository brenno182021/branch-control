# Ambientes e permissões

O primeiro cadastro, realizado pelo navegador no endereço local do servidor, cria um administrador global (`ADM`). As demais contas são `USER`, salvo promoção explícita por um administrador. Não há grupos ou ambientes RHF predefinidos. `ADM` tem acesso total e administra contas e ambientes.

Um ambiente reúne nome livre, um repositório Git do workspace do servidor, padrões de branches, permissões, membros e estado habilitado/desabilitado. O nome é único sem distinguir maiúsculas de minúsculas. Uma pessoa pode participar de vários ambientes, com permissões diferentes em cada um. As permissões pertencem ao ambiente e valem igualmente para seus membros. Um usuário comum sem ambiente autorizado não pode acessar operações Git.

## Cadastro

Como administrador, abra Ambientes, selecione o repositório, informe um nome, os padrões, as permissões e os usuários membros. O caminho deve apontar para um clone existente no servidor. Não informe caminhos dos computadores dos navegadores. É possível editar ou desativar um ambiente; o histórico é preservado. Alterações incrementam sua revisão e invalidam prévias antigas, que precisam ser geradas novamente.

| Permissão | Acesso |
| --- | --- |
| `REPORTS` | Relatórios e exportação do histórico autorizado |
| `PREPARE` | Preparar, importar e exportar listas de commits |
| `SIMULATE` | Simular uma seleção; exige `PREPARE` |
| `APPLY` | Aplicar uma seleção; exige `SIMULATE` e `PREPARE` |
| `FETCH` | Atualizar referências do remoto |
| `SWITCH` | Trocar para uma branch local autorizada |
| `APPROVE_USERS` | Consultar solicitações pendentes e aprovar novos usuários para este ambiente |

Ter apenas `REPORTS` não concede abertura do estado Git nem operações. Um usuário comum só pode aplicar após sua própria simulação bem-sucedida para o mesmo plano (`planId`) e a mesma seleção. Uma simulação feita por outro usuário ou para outra seleção não libera a aplicação. O administrador global não está sujeito a essa exigência adicional.

A permissão **Aprovar novos usuários** é independente das operações Git. Um membro com essa permissão recebe a aba Solicitações de acesso e pode aprovar uma pessoa somente para ambientes ativos onde tenha essa autorização. O ADM pode escolher qualquer ambiente ativo. A aprovação cria uma conta comum e inclui a pessoa como membro; não concede o papel global ADM. Consulte [o fluxo de solicitações](access-requests.md).

## Padrões de branch

Informe de 1 a 50 padrões, um por linha, usando o nome da branch sem `refs/`. `*` corresponde a zero ou mais caracteres, **incluindo `/`**; `?` corresponde a um único caractere. Um padrão `*` permite todas as branches do repositório. Não há sintaxe de regex, negação ou grupos de caracteres.

| Padrão | Exemplo autorizado |
| --- | --- |
| `draft/rhf/*` | `draft/rhf/uma-tarefa` e `draft/rhf/time/uma-tarefa` |
| `chore/rhf/*` | `chore/rhf/manutencao` |
| `release/2026-??-??` | `release/2026-10-30` |

Esses nomes são apenas exemplos; nenhum ambiente é criado a partir deles. O backend normaliza referências locais e remotas: `refs/heads/draft/rhf/foo` e `refs/remotes/origin/draft/rhf/foo` são comparadas como `draft/rhf/foo`. Uma referência remota exibida como `origin/draft/rhf/foo` também corresponde a esse nome canônico, e portanto ao padrão `draft/rhf/*`.

Tanto a origem quanto o destino precisam estar autorizados. O destino de aplicação/troca deve ser local quando a operação exigir branch local. Referências fora do escopo ficam ocultas; se a branch atual não estiver autorizada, seu nome não é exibido ao usuário comum. A autorização é revalidada no backend e ao iniciar uma execução enfileirada.

## Histórico

`REPORTS` permite visualizar execuções de qualquer usuário cujos repositório **e ambas as branches** correspondam ao ambiente autorizado. Isso inclui execuções originadas em outros ambientes com o mesmo repositório e branches cobertas. O nome do ambiente de origem não concede nem impede esse acesso.

Sem `REPORTS`, o usuário acompanha somente suas próprias execuções, enquanto conservar acesso operacional ao ambiente e às branches correspondentes. Administradores veem todo o histórico. Desativar um ambiente impede operações; a permissão de relatório pode continuar permitindo consultar seu histórico.
