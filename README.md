# Planeta 360 · Portal do Patrocinador

Portal em que cada marca patrocinadora do Planeta Atlântida acompanha a própria parceria com a RBS: materiais, prazos, pesquisa, resultados de mídia e o relatório pós-evento. A RBS cadastra as parcerias, libera os acessos, analisa os materiais, carrega as planilhas e gera o relatório a partir da base, sem montagem manual.

Esta é a versão da **Entrega 2** (primeira versão funcional) do Desafio GC 2026. A versão anterior continua no GitHub, na tag `sprint-1`.

| Parte | Tecnologia | Porta |
| --- | --- | --- |
| Banco | PostgreSQL 18 (funciona a partir do 15), tabelas criadas pelo Flyway | 5432 |
| API | Java 21+, Spring Boot 4.1, sessão por cookie com CSRF | 8080 |
| Front | React 19, TypeScript, Vite 8 e Tailwind 4, no visual do Figma | 5173 |

## Antes de começar

Instale:

- **JDK 21 ou mais novo** (recomendado: 25 LTS). Confira com `java -version`.
- **Node.js 24 LTS** (ou 22.22+). Confira com `node -v`.
- **PostgreSQL 18.**
  - macOS: `brew install postgresql@18` e depois `brew services start postgresql@18`.
  - Windows: instalador oficial em postgresql.org. Marque o "Command Line Tools" para ter o `psql`.

Maven não precisa: o projeto traz o Maven Wrapper (`mvnw` no macOS e Linux, `mvnw.cmd` no Windows).

## Como rodar

**1. Criar os bancos (uma vez só).** Abra o `psql` como administrador do PostgreSQL (no Windows, o usuário `postgres`; no macOS com Homebrew, o seu próprio usuário) e rode:

```sql
CREATE ROLE portal LOGIN PASSWORD 'portal';
CREATE DATABASE portal OWNER portal;
CREATE DATABASE portal_teste OWNER portal;
```

Os bancos começam vazios. As tabelas, as regras e a amostra (Amarelo, Roxo e Verde) são criadas pelo Flyway quando a API sobe.

**2. Subir a API**, num terminal:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

No Windows: `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev`. A API está pronta quando aparece `Started PortalApplication`. O perfil `dev` carrega a amostra de demonstração; sem perfil, sobe só a estrutura (como em produção).

**3. Subir o front**, em outro terminal:

```bash
cd frontend
npm install
npm run dev
```

Abra <http://localhost:5173>. O Vite repassa as chamadas `/api` para a porta 8080, então front e API funcionam como um site só.

**Recomeçar do zero.** Para voltar à amostra original, apague e recrie o banco `portal` (passo 1) e suba a API de novo.

## Usuários de teste

Todos usam a senha `senha123`. O sistema detecta o perfil pela conta: a RBS cai no painel de gestão e o patrocinador na própria parceria.

| E-mail | Perfil | Vê |
| --- | --- | --- |
| `b2b@rbs.example` | Admin RBS | Todas as parcerias e a gestão |
| `pesquisa@rbs.example` | Admin RBS | Todas as parcerias e a gestão |
| `marketing@amarelo.example` | Patrocinador | Cliente Amarelo · Master · 2027 |
| `atendimento@agencia-x.example` | Patrocinador (agência) | Cliente Amarelo · Master · 2027 |
| `marketing@roxo.example` | Patrocinador | Cliente Roxo · Subcota G · 2027 |
| `marketing@verde.example` | Patrocinador | Cliente Verde · Subcota · 2027 |

**Primeiro acesso de um e-mail novo.** A RBS cadastra a parceria (ou autoriza o e-mail no Perfil) e recebe um código de ativação, que aparece uma vez só. A pessoa entra em "Primeiro acesso", informa o e-mail, o código e cria a senha. Na V1 não há e-mail automático: a RBS repassa o código.

## Roteiro de demonstração (item 7)

Com o banco recém-criado (amostra original):

1. Entre como `marketing@roxo.example`. "Minha parceria" mostra a próxima ação: o "VT para os telões dos palcos", com o ajuste pedido pela RBS.
2. Em **Mídia e conteúdo**, solte um PDF (ou JPG, PNG, MP4) na área de envio, escolha a entrega e envie. O arquivo vira uma nova versão, com autor, data e status "Enviado".
3. Isolamento: ainda como Roxo, abra <http://localhost:5173/parceria/2> (a parceria do Amarelo). A tela diz "Parceria não encontrada" e a API responde 404, igual a um id que não existe.
4. Saia e entre como `b2b@rbs.example`. Em **Fila de materiais**, abra o envio do Roxo e aprove (ou peça ajuste com uma observação). Tudo fica no **Histórico**, com autor e data.
5. Em **Cargas**, envie `docs/exemplos/veiculacao.csv` duas vezes. Na segunda, nada é inserido: as linhas contam como duplicadas.
6. Em **Relatórios**, gere uma nova versão do relatório do Roxo, valide e disponibilize.
7. Volte como Roxo: o relatório aparece em **Relatório**, com as seções do modelo, e pode ser impresso ou exportado em CSV.

**Regras de envio.** Arquivos de até 20 MB nos formatos PDF, PNG, JPG, DOCX, XLSX, PPTX, CSV, MP4, MP3 e ZIP. A API confere o conteúdo, e não só o nome. O download sai sempre como anexo. As planilhas de carga são CSV em UTF-8 com vírgula como separador; as colunas estão em `docs/banco.md` e há exemplos em `docs/exemplos/`.

## Testes

```bash
cd backend
./mvnw test
```

Os testes usam o banco `portal_teste`, que é apagado e recriado a cada execução (nunca aponte para o `portal`). Eles cobrem o isolamento entre parcerias pela API, as permissões, o envio com histórico, a carga sem duplicidade e o relatório. As conferências do banco ficam em `backend/src/test/resources/db/verificacao.sql`.

No front, `npm run build` confere os tipos e gera a versão de produção; `npm run lint` roda o Oxlint.

## Itens da Entrega 2

| # | Item | Onde está |
| --- | --- | --- |
| 1 | Diagnóstico dos dados | Amostra organizada em `backend/src/main/resources/db/dev/R__amostra.sql`; entidades Java em `record` nos pacotes de cada área |
| 2 | Modelo do relatório | Seções e catálogo de 17 indicadores no banco (V3); tela Relatório |
| 3 | Acesso e cadastro | Login por sessão, primeiro acesso por código, Patrocinadores (cadastro), Perfil (empresa, abas e e-mails); isolamento testado pela API |
| 4 | Materiais e acompanhamento | Mídia e conteúdo e as abas, com versões, status e histórico; Fila de materiais da RBS |
| 5 | Consolidação dos dados | Cargas (planilhas de veiculação e de pesquisa) sem contagem duplicada, rejeições com motivo, previsto x exibido e faltantes |
| 6 | Relatório | Gerado por parceria a partir da base; a RBS valida e disponibiliza; exporta CSV e imprime |
| 7 | Integração | Menu com as sete abas, painel da RBS e o roteiro acima |
| 8 | Modelagem e database | `docs/banco.md` (tabelas, colunas, regras e Sprint 1 x Entrega 2), `docs/modelagem/sprint-1/` e as migrações do Flyway |

## Pendências registradas

- Comprovações de marca, plano comercial (previsto) e números do festival entram só pela amostra. Uma parceria cadastrada ao vivo fica sem previsto, e a planilha dela é rejeitada com o motivo "aproveitamento não encontrado".
- "O festival em números" sai como indisponível nos relatórios de 2027, porque só há números de 2026.
- Telas próprias de Aplicações de marca, Cortesias, Credenciamento e Contrato: nesta versão cada aba mostra as próprias entregas.
- E-mail automático do código de ativação; recuperação de senha.
- PDF do relatório: pela impressão do navegador.
- A confirmar com a RBS: abas por cota (hoje todas liberadas, Pesquisa só para Master), limite de usuários por parceria, quem envia as fotos de comprovação, formato dos resultados da pesquisa, quem aprova as perguntas, a lista final de formatos de arquivo (o `.ai` do Illustrator ficou de fora) e o limite de 20 MB.
- Segurança para produção: limite de tentativas de login e cookie de sessão `Secure` atrás de HTTPS.
- Fora da Entrega 2: assistente Atlas (o botão aparece desabilitado), JEV, n8n, Open Staff, cifra de arquivos, Docker e Swagger.

## Pastas

```text
backend/    API Spring Boot (pacotes comum, acesso, parceria, materiais, pesquisa, consolidacao, relatorio)
            src/main/resources/db/migration  estrutura, regras e dados base (Flyway)
            src/main/resources/db/dev        amostra de demonstração
frontend/   React + Vite (api, componentes, paginas/acesso, paginas/parceria, paginas/rbs)
docs/       banco.md, api.md, exemplos de CSV e a modelagem da Sprint 1
```

## Referências

- Contrato da API: [`docs/api.md`](docs/api.md)
- Banco de dados: [`docs/banco.md`](docs/banco.md)
- Figma: [Mockup · 06 · UI com a marca](https://www.figma.com/design/tXjjKVcZD978u5f6bYFimf/Mockup?node-id=165-2)
- Documentos da Etapa 2 no Drive: Diagnóstico dos dados, Modelo do relatório e Modelagem do banco
