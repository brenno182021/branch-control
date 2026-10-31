# Relatórios em PDF

Na aba **Relatórios**, escolha ambiente, período e resultado. **Exportar PDF** baixa um documento com os filtros atuais. Em **Ver hashes e log**, o botão **Baixar PDF desta execução** gera o documento de uma execução específica.

O PDF apresenta o nome completo de cada commit aplicado, hash original, novo hash, usuário, ambiente, branches e data da execução, no fuso configurado. Nomes e hashes longos ocupam mais de uma linha; o documento tem paginação.

Somente commits registrados como `APPLIED` em execuções reais entram no PDF. Uma execução que terminou com conflito ou falha pode conter commits aplicados antes da interrupção; esses commits aparecem. Simulações, pendências e commits identificados como já presentes ou equivalentes não são contabilizados como novas aplicações.

O período filtra a data de início da execução, assim como o relatório na tela. Um filtro sem aplicações produz um PDF válido com total zero. O relatório registra aplicações no clone do servidor; o estado `APPLIED` não comprova que houve push. O resultado do push fica no log da execução.

Os PDFs respeitam as permissões atuais de relatórios e os padrões de branches autorizados. Acesso operacional a uma execução não concede, por si só, acesso ao seu PDF. O administrador global pode consultar todos os relatórios.

Os arquivos são gerados no servidor e baixados pelo navegador. A fonte Noto Sans é incorporada ao PDF; não é necessário instalar fontes no servidor. Caracteres ausentes nessa fonte aparecem pela sua identificação Unicode (`U+...`), preservando uma referência legível.

## Componentes de terceiros

A geração utiliza [Apache PDFBox](https://pdfbox.apache.org/) sob Apache License 2.0. A fonte [Noto Sans](https://github.com/notofonts/noto-fonts/tree/main/hinted/ttf/NotoSans) é distribuída sob SIL Open Font License 1.1, preservada em `backend/src/main/resources/fonts/OFL.txt`. O código do Branch Control continua sob a licença MIT do projeto.
