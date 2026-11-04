# Planeta 360 · Portal do Patrocinador

Portal em que cada marca patrocinadora do Planeta Atlântida acompanha a própria parceria com a RBS: materiais, prazos, pesquisa, resultados de mídia e o relatório pós-evento. A RBS cadastra as parcerias, libera os acessos, analisa os materiais, carrega as planilhas e gera o relatório a partir da base, sem montagem manual.

Esta é a versão da **Entrega 2** (primeira versão funcional) do Desafio GC 2026. A versão anterior continua no GitHub, na tag `sprint-1`.

| Parte | Tecnologia | Porta |
| --- | --- | --- |
| Banco | PostgreSQL 18 (funciona a partir do 15), tabelas criadas pelo Flyway | 5432 |
| API | Java 21+, Spring Boot 4.1, sessão por cookie com CSRF | 8080 |
| Front | React 19, TypeScript, Vite 8 e Tailwind 4, no visual do Figma | 5173 |

## Antes de começar

Instale e, depois de cada instalação, abra um terminal novo:

- **JDK 21 ou mais novo** (recomendado: 25 LTS, por exemplo o Temurin). Confira com `java -version`.
- **Node.js 24 LTS** (ou 22.22+). Confira com `node -v`.
- **PostgreSQL 18** (funciona a partir do 15).
  - macOS: `brew install postgresql@18` e depois `brew services start postgresql@18`. O Homebrew deixa o `psql` dessa versão fora do PATH. Rode a linha abaixo uma vez, abra um terminal novo e confira com `psql --version` (em Mac com Intel, troque `/opt/homebrew` por `/usr/local`):

    ```bash
    echo 'export PATH="/opt/homebrew/opt/postgresql@18/bin:$PATH"' >> ~/.zshrc
    ```

  - Windows: instalador oficial em postgresql.org. Guarde a senha do usuário `postgres` e a porta (5432, ou 5433 se já havia outro PostgreSQL na máquina). O `psql` fica no menu Iniciar, como "SQL Shell (psql)".
  - Linux (Debian/Ubuntu): `sudo apt install postgresql`.

Maven não precisa: o projeto traz o Maven Wrapper (`mvnw` no macOS e Linux, `mvnw.cmd` no Windows). Na primeira vez, o wrapper baixa o Maven e as dependências da API e o `npm install` baixa as do front: precisa de internet e leva alguns minutos.

As portas 5432 (banco), 8080 (API) e 5173 (front) precisam estar livres. Para o banco em outra porta, veja o fim do passo 2.

## Como rodar

São três passos, nesta ordem: banco, API e front. A API e o front ficam cada um no seu terminal, abertos enquanto o portal estiver em uso. Para parar, `Ctrl+C` em cada um.

**1. Criar os bancos (uma vez só).** Abra o `psql` como administrador do PostgreSQL:

- macOS: `psql postgres`
- Windows: abra o "SQL Shell (psql)", aperte Enter em Server e Database, informe a porta (Enter se for 5432), Enter em Username e digite a senha do `postgres`.
- Linux: `sudo -u postgres psql`

Rode e saia com `\q`:

```sql
CREATE ROLE portal LOGIN PASSWORD 'portal';
CREATE DATABASE portal OWNER portal;
CREATE DATABASE portal_teste OWNER portal;
```

Os bancos começam vazios. As tabelas, as regras e a amostra (Amarelo, Roxo e Verde) são criadas pelo Flyway quando a API sobe.

**Já rodou a Sprint 1 nesta máquina?** Ela usava o mesmo usuário `portal` e o mesmo banco `portal`, com as tabelas antigas. O `CREATE ROLE` vai avisar que o usuário já existe (pode seguir), mas o banco precisa ser apagado e criado de novo, com a versão antiga desligada:

```sql
DROP DATABASE portal;
CREATE DATABASE portal OWNER portal;
CREATE DATABASE portal_teste OWNER portal;
```

**Extensão do Atlas (uma vez só, e de novo ao criar um banco).** As buscas nos documentos usam o pgvector. No macOS e no Linux, rode na raiz do repositório:

```bash
scripts/preparar-banco-atlas.sh
```

O script instala o pgvector pelo Homebrew, se precisar, e prepara os bancos `portal` e `portal_teste`: extensão no schema `extensoes` e papel `atlas_ro`. O Atlas precisa do pgvector 0.8.0 ou mais novo. O script pode rodar de novo sem problema, e aceita outros bancos como argumento. Ele precisa de um superusuário do PostgreSQL. No macOS é o usuário atual. No Linux, instale o pacote (`sudo apt install postgresql-18-pgvector`) e rode com `sudo -u postgres`, ou informe `PGUSER`, `PGHOST` e `PGPASSWORD`. No Windows, instale o pgvector como manda o [README dele](https://github.com/pgvector/pgvector#windows) e rode no `psql` os comandos SQL do script.

**2. Subir a API**, num terminal:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

No Windows, no Prompt de Comando ou no PowerShell (no PowerShell, as aspas são obrigatórias):

```bat
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

A API está pronta quando aparece `Started PortalApplication`. O perfil `dev` carrega a amostra de demonstração e, ao subir, cria em `backend/armazenamento/` um arquivo de exemplo para cada arquivo da amostra (é o que faz o botão "Baixar" funcionar na demonstração); sem perfil, sobe só a estrutura (como em produção, sem amostra e sem arquivos de exemplo). O perfil `demo` (`SPRING_PROFILES_ACTIVE=demo`, ou `ATLAS_PERFIL=demo scripts/atlas-dev.sh start`) é o da apresentação: sobe como o `dev` (amostra, arquivos de exemplo e o kit de documentos do Atlas, com as chaves do mesmo arquivo de segredos), com o relatório, o diagnóstico e o Atlas da RBS ligados, mas **sem** as rotas de debug da chave do modelo (404) e sem a caixa de debug das ferramentas: quem mostra como a resposta foi feita é o diagnóstico. Ele usa as chaves de verdade. Use um banco descartável com ele. A 8080 é só a API: as telas ficam no front (passo 3).

**PostgreSQL em outra porta** (a Sprint 1 usava a 5433)? Informe a URL do banco no mesmo terminal, antes de subir a API:

```bash
export PORTAL_BD_URL=jdbc:postgresql://localhost:5433/portal
```

No Windows: `set "PORTAL_BD_URL=jdbc:postgresql://localhost:5433/portal"` no Prompt de Comando, ou `$env:PORTAL_BD_URL="jdbc:postgresql://localhost:5433/portal"` no PowerShell. Usuário e senha diferentes de `portal` vão em `PORTAL_BD_USUARIO` e `PORTAL_BD_SENHA`.

**3. Subir o front**, com a API no ar, em outro terminal:

```bash
cd frontend
npm install
npm run dev
```

Abra <http://localhost:5173>. O Vite repassa as chamadas `/api` para a porta 8080, então front e API funcionam como um site só.

**Recomeçar do zero.** Para voltar à amostra original, pare a API, rode no `psql` o `DROP DATABASE portal;` e o `CREATE DATABASE portal OWNER portal;`, rode o `scripts/preparar-banco-atlas.sh` e suba a API de novo. Os arquivos enviados ficam em `backend/armazenamento/`, que pode ser apagada junto (a API recria os arquivos de exemplo da amostra na subida).

**Quem já tinha o banco `portal` criado precisa recomeçar do zero para ver a amostra nova.** A amostra só carrega num banco vazio e não roda de novo sobre um banco que já a tem. A versão atual traz: entregas Enviado, Em análise e Aprovado com o arquivo que a marca mandou, o VT do Roxo em "Ajuste solicitado" com o recado da RBS no histórico, horários sempre no passado (contados a partir do dia em que o banco é criado) e nenhum "(simulada)" no nome da pesquisa. Num banco criado antes, as migrações novas (V4 a V6) rodam sozinhas ao subir a API, mas a amostra antiga continua como estava. O mesmo vale para o alinhamento com os planos comerciais do kit de documentos do Atlas: as cortesias e as credenciais do Roxo (4 na Arena e 2 no Camarote por dia, 5 credenciais na Arena) e da Verde (4 e 2 por dia, 10 credenciais), iguais aos PDFs dos planos da RBS, e o portão do Camarote e do Lounge (Portão 4) só aparecem num banco recriado. **A amostra mudou de novo**: além desses números, a edição 2027 passou a ter o endereço do Open Staff (`https://example.com/`), o credenciamento de 01/12 a 20/12/2026 e o prazo das cortesias em 15/01/2027; as entregas de ingressos e de logo ganharam o papel; as exibições do Amarelo se espalham por três meses; e algumas exibições do Roxo nas redes trazem o link do post. As contagens não mudam (79 linhas de plano, 1.489 linhas lidas). Cada pessoa do grupo recria o próprio banco com os comandos acima.

**Atlas (V7 a V15).** As migrações rodam sozinhas ao subir a API, sobre o banco que já existe, mas a V11 precisa do pgvector e do papel `atlas_ro`: rode antes o `scripts/preparar-banco-atlas.sh`, ou a subida para com a mensagem que pede isso. O kit de documentos e os prazos do Atlas não dependem de recriar o banco: entram na subida, nos perfis `dev` e `demo`, com os embeddings ligados.

## Se algo der errado

| O que aparece | O que fazer |
| --- | --- |
| `psql: command not found` (macOS) | Falta a linha do PATH em "Antes de começar". Rode e abra um terminal novo. |
| `'psql' não é reconhecido` (Windows) | Use o "SQL Shell (psql)" do menu Iniciar. |
| `role "portal" already exists` ou `database "portal" already exists` | Ficaram de uma instalação anterior, talvez da Sprint 1. Veja "Já rodou a Sprint 1?" no passo 1. |
| API: `Connection to localhost:5432 refused` | O PostgreSQL está parado ou em outra porta. macOS: `brew services start postgresql@18`. Windows: inicie o serviço `postgresql-x64-18` em Serviços. Outra porta: `PORTAL_BD_URL` (passo 2). |
| API: `database "portal" does not exist`, `role "portal" does not exist` ou `password authentication failed for user "portal"` | O passo 1 não rodou nesse PostgreSQL. Confira se a porta é a mesma. |
| API: `Found non-empty schema(s) "public" but no schema history table` | O banco `portal` tem as tabelas antigas da Sprint 1. Apague e crie de novo (passo 1). |
| API: `O Atlas precisa da extensão vector (pgvector) neste banco` ou `O Atlas precisa do papel atlas_ro neste PostgreSQL` | Rode `scripts/preparar-banco-atlas.sh` e suba a API de novo. |
| API: `Port 8080 was already in use` | Outra API ainda está rodando, talvez em outro terminal. Feche-a (no macOS e no Linux, `lsof -i :8080` mostra quem usa a porta). |
| API: erro de compilação com `release version 21` | O Maven achou um JDK anterior ao 21. Confira `java -version` e, se existir, o `JAVA_HOME`. |
| API, no PowerShell: `Unknown lifecycle phase ".run.profiles=dev"` | Faltaram as aspas em `"-Dspring-boot.run.profiles=dev"`. |
| Login: "Sem conexão com o servidor. Confira se a API está rodando." | A API parou ou ainda está subindo. Espere o `Started PortalApplication` no terminal do passo 2. |

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

1. Entre como `marketing@roxo.example`. "Minha parceria" mostra a próxima ação: o "VT para os telões dos palcos", em "Ajuste solicitado", com o recado da RBS (o VT passa de 30 segundos e a trilha precisa de autorização). Em **Mídia e conteúdo**, a entrega mostra o envio anterior da marca, com a observação que ela escreveu, e o **Histórico** traz o envio e o pedido de ajuste, com autor e data.
2. Em **Mídia e conteúdo**, solte um PDF (ou JPG, PNG, MP4) na área de envio, escolha a entrega e envie. O arquivo vira uma nova versão, com autor, data e status "Enviado".
3. Isolamento: ainda como Roxo, abra <http://localhost:5173/parceria/2> (a parceria do Amarelo). A tela diz "Parceria não encontrada" e a API responde 404, igual a um id que não existe.
4. Saia e entre como `b2b@rbs.example`. Em **Fila de materiais**, abra o envio do Roxo e aprove (ou peça ajuste com uma observação). Tudo fica no **Histórico**, com autor e data.
5. Em **Cargas**, envie `docs/exemplos/veiculacao.csv` duas vezes. Na segunda, nada é inserido: as linhas contam como duplicadas.
6. Em **Relatórios**, gere uma nova versão do relatório do Roxo, valide e disponibilize.
7. Volte como Roxo: o relatório aparece em **Relatório**, com as seções do modelo, e pode ser impresso ou exportado em CSV.

### Roteiro do Atlas

O Atlas é o chat: a marca pergunta sobre os números da própria parceria e sobre os documentos do evento, e o que vem de documento cita a fonte. Nos perfis `dev` e `demo` o relatório da ativação também funciona, na mesma conversa (o modo só chat, sem o relatório, continua existindo: `ATLAS_RELATORIO=false`). Ele precisa do modelo de linguagem ligado (veja abaixo); sem o modelo, o botão "Montar relatório" monta o relatório sem ele.

1. Como `b2b@rbs.example`, em **Patrocinadores**, cadastre a parceria "Cliente Teste" na edição 2027. Em **Cargas**, escolha "Plano comercial" e envie `docs/exemplos/plano.csv`: 8 linhas entram, 1 é duplicada e 2 são rejeitadas, com o motivo (mídia sem tipo e uma marca sem parceria). Para ver o plano do Amarelo crescer, envie também `docs/exemplos/plano-amarelo-p07-p10.csv` (as páginas 7 a 10 do plano dele, 36 linhas, todas entram; a unidade das impressões de display é pergunta aberta à RBS).
2. Abra a parceria do Cliente Amarelo, em **Aplicações da marca**: o plano linha a linha, com as fotos de comprovação. Use "Anexar foto" numa linha sem foto, e o total de comprovadas sobe.
3. Entre como `marketing@amarelo.example` e, em **Aplicações da marca**, informe os números que só a marca sabe (visitantes, cadastros e brindes): eles ficam com o seu nome e a data. A planilha de contagem da agência (xlsx, xls ou csv, até 20 MB) também fica ali, como um valor com autor e data (API: `POST /api/parcerias/{id}/dados-informados/ativacao.planilha_agencia/arquivo`, rota 66, e o download é a rota 18); ela não entra no relatório do Atlas.
4. Toque em **atlas**, no menu (ou use a caixa "atlas · como posso ajudar?" em Minha parceria). O Atlas abre numa janela própria, com o menu à vista. Pergunte "quantas aplicações já foram comprovadas?": a resposta chega enquanto o modelo escreve, e os números vêm do portal.
5. No perfil `dev`, cada resposta mostra, em caixas amarelas de debug, as ferramentas que o modelo usou, com o que ele pediu e o que recebeu de volta. Sem ferramenta, a resposta diz isso. Nos perfis `dev` e `demo`, o botão **Diagnóstico** embaixo da resposta abre as etapas do turno: busca, JEV, checagem e tempos.
6. Pergunte "até quando preciso informar os geradores?": a resposta traz a data com um chip, como `T1`, e a lista "Fontes" embaixo. O chip abre o manual ao lado da conversa, na página citada, com o trecho destacado.
7. Peça os números de outra marca: o Atlas não tem como consultá-los e responde só "Não encontrei isso nos documentos do portal. Para essa dúvida, fale com o atendimento da RBS.", sem dizer se existem e sem número nenhum.
8. Como `b2b@rbs.example`, abra **Atlas**. As abas são Turnos e Provedores (o diagnóstico), Documentos, Busca de teste, Prazos e Conversa (o Atlas da RBS, por edição). Em **Busca de teste**, escolha uma parceria e escreva uma consulta: aparecem as duas listas da busca, o que o modelo receberia e o que essa parceria enxerga, sem gastar o modelo.
9. Como `marketing@amarelo.example`, peça ao Atlas "monta o relatório da ativação": ele junta o que o portal já sabe, pede num cartão os números que só a marca tem, escreve os textos (só com marcadores, o servidor põe os números) e o relatório pronto abre com a origem de cada número na aba **Fontes** e, no fim do documento, em **Referências**. Um relatório que ficou pela metade (em montagem ou esperando o cartão) pode ser descartado pela API (`POST /api/atlas/relatorios/{id}/descartar`, rota 65): o cartão fecha e a conversa segue livre para começar outro. Pedir "o relatório pós-evento" é outro caminho: o Atlas explica, em texto fixo, que o oficial é da RBS e oferece um modelo em branco.

A apresentação segue um roteiro de oito passos, que o ensaio automático percorre: veja "Ensaio da demonstração", em Testes.

### Ligar o modelo de linguagem (opcional)

Sem estas variáveis, o portal e os testes funcionam normalmente, mas o Atlas não responde. Com elas, a marca conversa com o Atlas pelo chat. A resposta aparece enquanto o modelo escreve, com a etapa do momento ("Consultando os números da parceria…"). Antes dela, uma caixa conta o tempo em que o modelo pensa e mostra um resumo curto do raciocínio, que fica aberto enquanto ele pensa e depois recolhe; dá para abrir de novo, inclusive nas conversas antigas. O raciocínio completo não sai do servidor: ele volta só ao modelo. O resumo é escrito no servidor, numa chamada curta à parte, e passa pela mesma checagem do texto do Atlas (sem números nem nomes internos); recusado, a caixa mostra só o tempo.

**Pela tela, sem reiniciar (temporário, só no perfil `dev` e só para a RBS).** O selo amarelo "Debug", no topo da tela da RBS, abre um formulário para colar a chave do provedor (e, se quiser, outro endereço ou modelo), ligar ou desligar o raciocínio do modelo, testar a conexão e remover a chave. A bolinha do selo fica verde com o modelo ligado. A chave fica só na memória da API e some quando ela reinicia; nunca volta inteira para a tela. Fora do perfil `dev` as rotas de debug não existem e o selo não aparece.

**Pelas variáveis de ambiente:**

```bash
cd backend
ATLAS_LLM=openai OPENCODE_API_KEY=sua-chave ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

No Windows (PowerShell): `$env:ATLAS_LLM="openai"; $env:OPENCODE_API_KEY="sua-chave"` antes do `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`.

**Pelo arquivo de segredos (perfis `dev` e `demo`).** Copie `backend/config/segredos.exemplo.properties` para `backend/config/segredos.local.properties` e preencha as chaves. A API lê esse arquivo ao subir a partir de `backend/`, e o Git o ignora. Sem ele, o portal sobe normalmente, com o Atlas desligado. Variável de ambiente vence o arquivo.

| Variável | Padrão | Para quê |
| --- | --- | --- |
| `ATLAS_LLM` | `none` | `openai` liga o modelo (qualquer serviço compatível com a API da OpenAI) |
| `OPENCODE_API_KEY` | — | A chave do provedor. Em variável de ambiente ou no arquivo de segredos, nunca no repositório. Com `ATLAS_LLM=openai` e sem ela, o Atlas não responde e a API avisa no log |
| `ATLAS_LLM_BASE_URL` | `https://opencode.ai/inference/go/openai/v1` | O endereço do provedor |
| `ATLAS_LLM_MODELO` | `deepseek-v4.1-flash` | O modelo |
| `ATLAS_RACIOCINIO` | `true` | O raciocínio do modelo, com o resumo na conversa. Ligado, cada resposta demora uns segundos a mais e gasta mais do limite do provedor |
| `ATLAS_PRAZO_DO_RESUMO` | `PT15S` | Quanto o fim do turno espera pelos resumos do raciocínio; o que passar do prazo aparece na próxima leitura da conversa |
| `ATLAS_TETO_DIARIO` | `200` | Mensagens do Atlas por parceria por dia; depois disso, o chat responde 429 |
| `ATLAS_LIMITE_MENSAGENS_POR_HORA`, `ATLAS_LIMITE_RELATORIOS_POR_DIA` | `20`, `3` | Por usuário: mensagens de texto por hora, na conversa da marca e na da RBS (acima, 429 com quanto falta), e relatórios por dia que o modelo monta (acima, o modelo sugere o botão). O botão não conta. Ficam na memória: reiniciar a API zera |
| `ATLAS_LIMITE_DOCUMENTOS_POR_HORA`, `ATLAS_LIMITE_BUSCAS_DE_TESTE_POR_HORA` | `120`, `60` | Por usuário: aberturas do arquivo de um documento por hora (a fonte do visualizador; cada uma grava um registro) e buscas de teste da RBS por hora (cada uma pode gastar um embedding do Gemini). Acima, 429 com quanto falta. Na memória, como os outros |
| `ATLAS_DISJUNTORES` | `true` | Os disjuntores do modelo, do JEV e do Gemini: com 5 falhas seguidas (ou 50% das 10 últimas) o provedor para de ser chamado por 60 s, e o Atlas segue sem ele (frase fixa de falha, sem JEV, busca só textual). O estado está na aba Provedores |
| `ATLAS_RELATORIO` | `false` (`true` nos perfis `dev` e `demo`) | Liga o relatório montado pelo Atlas (pedido no chat, botão, cartão e documento). Desligado, o Atlas é só o chat e as rotas do relatório respondem 404 |
| `ATLAS_RBS` | `false` (`true` nos perfis `dev` e `demo`) | O Atlas da RBS: a equipe da RBS conversa com o Atlas por edição, sem parceria, para tirar dúvidas dos documentos globais da edição (rotas 67 e 68 e, na conversa dela, as 44, 45 e 49). Desligado, as rotas 67 e 68 respondem 404 e nada muda. Veja `docs/api.md`, "O Atlas da RBS" |
| `ATLAS_RBS_TETO_DIARIO` | `200` | Mensagens do modelo por pessoa da RBS por dia no Atlas da RBS; depois disso, 429 |
| `ATLAS_DEBUG_FERRAMENTAS` | `false` (`true` no perfil `dev`; sempre `false` no `demo`) | Mostra na conversa as ferramentas que o modelo usou, com argumentos e resultado. Temporário |
| `ATLAS_DIAGNOSTICO` | `false` (`true` nos perfis `dev` e `demo`) | O diagnóstico: cada turno do Atlas deixa um traço (etapas, tempos, tokens, custo) que a RBS lê pelas rotas 50 a 53. Desligado, nada é gravado e as rotas respondem 404. Veja `docs/api.md`, "Diagnóstico do Atlas" |
| `ATLAS_DIAGNOSTICO_MARCA` | `false` (`true` nos perfis `dev` e `demo`) | A marca vê o diagnóstico das próprias conversas (rota 49). Só vale com o diagnóstico ligado |
| `ATLAS_EMBEDDINGS`, `GEMINI_API_KEY` | `none`, — | Os embeddings dos documentos do Atlas: `gemini` (o Gemini de verdade, com a chave), `teste` (determinístico e sem rede, **só em banco descartável** como o `portal_e2e`: os vetores não têm o significado dos do Gemini) ou `none`. Com `none`, a API sobe do mesmo jeito, aprovar um documento o deixa em `ERRO` com o motivo, e a amostra dos perfis `dev` e `demo` não cria os documentos de exemplo (avisa no log; quando a chave chega, a próxima subida cria e indexa o kit). A cota gratuita do Gemini é de 1.000 chamadas por dia: sem cota, a busca segue só com a lista textual, e documento novo fica em `ERRO` (na hora, com "A cota do dia do Gemini acabou; ...") até reindexar. O limite por minuto, ao contrário, a indexação espera sozinha, pelo tempo que o Gemini pede (até `atlas.embeddings.espera-maxima-no-429`, 90 s). Veja `docs/api.md`, "Os documentos do Atlas" |
| `ATLAS_JEV`, `TYPESAFE_API_KEY` | `none`, — | O JEV, um classificador que entra em seis pontos de decisão, e a chave dele. Sem perfil, `ATLAS_JEV` dá o modo de todos: `none`, `sombra` (só observa) ou `ativo`. Nos perfis `dev` e `demo` cada ponto já vem fixado pela calibração, e `ATLAS_JEV` não muda nada: intenção, cartão e resposta ativos; fontes, coerência e lacunas em sombra. Ele nunca é barreira: se falhar, o turno segue com o modelo. Veja `docs/api.md`, "O JEV nos pontos de decisão" |
| `ATLAS_JEV_<PONTO>` | Fixado nos perfis `dev` e `demo` | Troca o modo de um ponto: `INTENCAO`, `CARTAO`, `FONTES`, `RESPOSTA`, `COERENCIA` ou `LACUNAS` |
| `ATLAS_JEV_MODELO` | `jev-1.14.0` | A versão do JEV |
| `ATLAS_JEV_BASE_URL`, `ATLAS_GEMINI_BASE_URL` | Endereços oficiais | Os endereços do JEV e do Gemini |
| `ATLAS_PRECO_ENTRADA`, `ATLAS_PRECO_SAIDA` | `0.30`, `1.20` | US$ por milhão de tokens, usados no custo do diagnóstico quando a resposta do provedor não traz o preço |

Todo número do chat tem origem: vem da base do portal, do que a marca informou ou de um documento citado na mesma frase. O servidor confere isso antes de a frase chegar à tela. Use só dados fictícios enquanto o provedor estiver em teste.

**Regras de envio.** Arquivos de até 20 MB nos formatos PDF, PNG, JPG (e JPEG), DOCX, XLSX, PPTX, CSV, MP4, MP3 e ZIP. A API confere o conteúdo, e não só o nome. O download sai sempre como anexo. Cada envio aceita uma observação (até 500 caracteres), que fica na versão e no histórico. As planilhas de carga são CSV em UTF-8 com vírgula como separador (no Excel, "Salvar como" e "CSV UTF-8"); as colunas estão em `docs/banco.md` e há exemplos em `docs/exemplos/`. Linha com data ou quantidade ilegível é rejeitada com o motivo e o formato esperado, e o resto do arquivo entra.

## Testes

```bash
cd backend
./mvnw test
```

No Windows: `.\mvnw.cmd test`. Os testes usam o banco `portal_teste`, que é apagado e recriado a cada execução (nunca aponte para o `portal`). Com o PostgreSQL em outra porta, informe `PORTAL_BD_TESTE_URL` (por exemplo, `jdbc:postgresql://localhost:5433/portal_teste`) do mesmo jeito que o `PORTAL_BD_URL` do passo 2. Eles cobrem o isolamento entre parcerias pela API, as permissões, o envio com histórico, a carga sem duplicidade e o relatório. As conferências do banco ficam em `backend/src/test/resources/db/verificacao.sql`. Esses testes usam provedores falsos e não pedem chave. Os marcados `@Tag("avaliacao")` chamam os provedores de verdade e ficam de fora; para rodá-los, use `./mvnw test -Dgroups=avaliacao -Dtestes.excluidos=`.

No front, `npm run build` confere os tipos e gera a versão de produção; `npm run lint` roda o Oxlint.

**No navegador.** Na primeira vez, `npx playwright install chromium`, em `frontend/`. Os testes escrevem no banco, então dizer onde eles rodam é obrigatório, e a preparação recusa o banco `portal`:

- `PORTAL_E2E_PILHA=N npm run e2e` (N de 1 a 9) usa a pilha N do `.claude/pilhas-de-teste.sh`: front na 518N, API na 808N, banco `portal_qaN`.
- `PORTAL_E2E_PILHA=dev npm run e2e` sobe o front na porta 5190 (ou na de `E2E_PORTA_FRONT`) e fala com a API do `scripts/atlas-dev.sh`, na 8080 (ou na de `ATLAS_DEV_PORTA`). Essa API precisa subir com `PORTAL_BD_URL` apontando para um banco de teste.

As capturas ficam em `frontend/e2e/capturas/`, e o resultado de cada teste em `frontend/test-results/resultado.json`. Os testes marcados `@vivo` usam o modelo de verdade; sem chave, rode `npm run e2e -- --grep-invert @vivo`. O kit de documentos precisa estar indexado (`ATLAS_EMBEDDINGS` em `gemini` ou `teste`), porque os testes das fontes dependem dele.

**Ensaio da demonstração.** `frontend/e2e/demo.spec.ts` percorre o roteiro inteiro com o modelo e o Gemini de verdade: os documentos da RBS e o envio do FAQ, o prazo com o PDF aberto na página citada, a conta com "Como calculei", os dias úteis, o relatório até o PDF, o pedido do pós-evento e o modelo em branco, o ataque como Cliente Roxo e o diagnóstico. Ele precisa do perfil `demo` num banco recém-criado, e cada turno tem até 30 s:

```bash
scripts/preparar-banco-atlas.sh portal_demo
ATLAS_DEV_PORTA=8099 ATLAS_PERFIL=demo PORTAL_BD_URL=jdbc:postgresql://localhost:5432/portal_demo scripts/atlas-dev.sh start
cd frontend
E2E_DEMO=1 PORTAL_E2E_PILHA=dev ATLAS_DEV_PORTA=8099 npx playwright test e2e/demo.spec.ts
```

As capturas de cada passo e os tempos (`demo-tempos.json`) ficam em `frontend/e2e/capturas/`. Sem `E2E_DEMO`, o teste é pulado. Para repetir, recrie o banco: o envio do FAQ muda a versão dele.

**Avaliador de conversas.** `ferramentas/avaliacao-atlas/` (Node 22, sem dependências) conversa com o Atlas de verdade, com casos e gabarito, e confere vazamento entre parcerias, origem dos números, ferramentas, fontes, contas e latência. Ele precisa da API no perfil `dev`, porque lê o debug das ferramentas:

```bash
node ferramentas/avaliacao-atlas/avaliar.mjs --marco M9 --split ajuste
```

Opções: `--split` (`ajuste`, `reserva` ou `todos`), `--categoria`, `--casos`, `--api` e, para uma conversa solta, `--livre "pergunta" --persona amarelo`. O resultado vai para `docs/atlas/controle/avaliacoes/`, que fica fora do Git, e a saída é 1 se houver vazamento, número sem origem ou caso com erro. Os testes dele: `node --test ferramentas/avaliacao-atlas/testes/*.test.mjs`. Os documentos fictícios do kit são gerados em `ferramentas/documentos-ficticios/` (`npm run gerar` e `npm run conferir`).

**CI.** `.github/workflows/ci.yml` roda a cada push na `atlas-completo` e em todo PR: a suíte do back num PostgreSQL 18 com pgvector, sem nenhuma chave, e o build e o lint do front. O e2e, o avaliador e os testes `@Tag("avaliacao")` ficam de fora, porque precisam da API ou dos provedores.

**Conferências com as chaves.** `scripts/fumaca-provedores.sh` confere o OpenCode Go, o JEV e o Gemini com chamadas reais, sem imprimir nenhuma chave. `scripts/conferir-segredos.sh` procura as chaves nos arquivos versionados, nos logs, em `backend/target/` e no traço de um banco, também sem imprimi-las (`--banco portal_e2e`, e `--historico` para todo o histórico do Git).

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

- O plano comercial de uma parceria cadastrada ao vivo entra pela carga "Plano comercial" (Cargas), e as fotos de comprovação pela aba Aplicações da marca (só a RBS anexa). Os números do festival ainda entram só pela amostra: "O festival em números" sai como indisponível nos relatórios de 2027, porque só há números de 2026.
- Corrigir uma linha do plano comercial: o reenvio com outra quantidade conta como duplicado (vale a primeira versão), e não há tela para editar a linha.
- Telas próprias de Cortesias, Credenciamento e Contrato: nesta versão cada uma dessas abas mostra as próprias entregas.
- Requisitos da RBS que esta versão não cobre (conferidos em 04/11/2026):
  - A comprovação mensal entra só em CSV, e o portal não gera a planilha mensal por tipo de entrega.
  - O link do post é gravado na carga, mas não aparece na tela.
  - A marca não envia material de mídia só em texto.
  - Aplicações não tem botões próprios para o MIV e a logo.
  - Não há formulário de quem assina o contrato.
  - A pesquisa da edição só pode ser aberta pela amostra.
  - A RBS não oculta aba, não troca a cota e não edita o investimento pela tela.
  - No pós-venda faltam texto editorial, fotos da edição, aftermovie, clipping e audiência por veículo.
- A amostra de demonstração difere dos planos comerciais que a RBS enviou:
  - O Roxo é a "Cota Apresenta", sem espaço físico.
  - As cortesias e as credenciais do Roxo e do Verde não batem com os planos.
  - As metragens e as datas de montagem do Amarelo também não.
- Relatório montado pelo Atlas: ligado nos perfis `dev` e `demo` e desligado no padrão (`ATLAS_RELATORIO`). No texto do relatório o modelo não escreve número nem data (nem por extenso): os números vêm do servidor, com a origem em "Referências", no fim do documento.
- Atlas com o modelo de linguagem: testado contra um servidor falso e com a chave de verdade do OpenCode Go. Para decidir com a equipe: os termos de uso do OpenCode, que falam em uso interno de quem assina, e o acordo de retenção zero do DeepSeek no Go, que vale até 30/11/2026 e é renovado mês a mês.
- Dado pessoal no modelo: a consulta do Atlas leva ao modelo o nome de quem informou cada dado ("Informado por ... em ..."). Fica assim por decisão do dono do produto.
- Dados informados pela marca entram sem aprovação da RBS, com o nome de quem informou e a data.
- E-mail automático do código de ativação; recuperação de senha.
- PDF do relatório: pela impressão do navegador.
- A confirmar com a RBS: abas por cota (hoje todas liberadas, Pesquisa só para Master), limite de usuários por parceria, quem envia as fotos de comprovação, formato dos resultados da pesquisa, quem aprova as perguntas, quem carrega os resultados (hoje a RBS), a lista final de formatos de arquivo (o `.ai` do Illustrator, o `.doc` e o `.ppt` ficaram de fora, e o `.xls` só entra na planilha da agência), o limite de 20 MB, se o acesso total do admin inclui liberar aba fora da cota e as cores e fontes da identidade visual de 2027.
- Segurança para produção: limite de tentativas de login e cookie de sessão `Secure` atrás de HTTPS.
- Fora desta versão: n8n, Open Staff, cifra de arquivos, Docker, Swagger e a implantação pública.

## Pastas

```text
backend/      API Spring Boot (pacotes comum, acesso, parceria, materiais, pesquisa, consolidacao, relatorio,
              aplicacoes, dados, documentos, prazos, atlas)
              src/main/resources/db/migration  estrutura, regras e dados base (Flyway)
              src/main/resources/db/dev        amostra de demonstração
              src/main/resources/atlas         prompts, modelos do relatório, modelos em branco e as perguntas do JEV
              src/main/resources/dev           arquivos de exemplo e o kit de documentos do Atlas
frontend/     React + Vite (api, atlas, componentes, estilos, paginas/acesso, paginas/parceria, paginas/rbs, sessao, util)
              e2e                              testes no navegador e o ensaio da demonstração
ferramentas/  avaliacao-atlas (avaliador de conversas e calibração do JEV) e documentos-ficticios (o kit)
scripts/      preparar-banco-atlas.sh (pgvector e papel atlas_ro), atlas-dev.sh (API em segundo plano),
              fumaca-provedores.sh e conferir-segredos.sh
.github/      workflows/ci.yml
docs/         banco.md, api.md, exemplos de CSV e a modelagem da Sprint 1
```

## Referências

- Contrato da API: [`docs/api.md`](docs/api.md)
- Banco de dados: [`docs/banco.md`](docs/banco.md)
- Figma: [Mockup · 07 · Implementação (Entrega 2)](https://www.figma.com/design/tXjjKVcZD978u5f6bYFimf/Mockup?node-id=408-2), com as telas do portal desta versão; o desenho de referência continua em [06 · UI com a marca](https://www.figma.com/design/tXjjKVcZD978u5f6bYFimf/Mockup?node-id=165-2)
- Documentos da Etapa 2 no Drive: Diagnóstico dos dados, Modelo do relatório e Modelagem do banco
