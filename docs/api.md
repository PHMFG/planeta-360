# API do Planeta 360 · Portal do Patrocinador

Este documento é o **contrato entre o back-end e o front-end**. Quem faz a tela lê aqui o que cada rota pede, o que devolve e quais erros esperar, sem precisar abrir o código.

- Todas as 53 rotas estão implementadas e cobertas por testes de integração (`cd backend && ./mvnw test`), inclusive o isolamento entre parcerias. A coluna "Etapa" diz em que etapa do plano cada rota entrou (as do Atlas, 37 em diante, entram como `Atlas 1`, `Atlas 2` e `Atlas M2`). Mudança de formato daqui em diante entra neste arquivo no mesmo commit do código.
- O banco está descrito em [`docs/banco.md`](banco.md). Os nomes de status, cotas e abas são os mesmos do banco.

## Como falar com a API

| Assunto | Regra |
| --- | --- |
| Endereço | Todas as rotas começam com `/api`. Em desenvolvimento a API roda em `http://localhost:8080` e o Vite repassa `/api` para ela, então front e API ficam na **mesma origem** (o cookie de sessão não precisa de CORS). |
| Formato | JSON em UTF-8, nomes de campo em `camelCase`. Os únicos arquivos que não são JSON são os downloads, os CSV e os envios (`multipart/form-data`). |
| Datas | Data e hora em ISO 8601 **com fuso**: `"2026-10-18T10:15:30-03:00"`. Data sem hora (prazo, início do evento): `"2026-12-16"`. |
| Números | Números JSON. Dinheiro e quantidades têm até 2 casas decimais (`1000000.00`). Percentuais têm 1 casa (`98.3`) e **todos** são **cortados** na primeira casa, nunca arredondados, na tela, no CSV e na consolidação: 459.900 de 460.000 é `99.9` (nunca `100`) e 17 de 19 é `89.4` (não `89.5`). Só chega a `100` quem está completo. O percentual de **entrega de mídia** pode passar de 100 quando o exibido supera o previsto (907.300 de 460.000 é `197.2`): é informação válida, e nada na API corta o número em 100; quem desenha uma barra limita a largura, não o valor. Os demais percentuais calculados pela API (entregas concluídas, aplicações comprovadas, cortesias destinadas) não passam de 100 por construção. O percentual de **resultado de pesquisa** (`unidade: PERCENTUAL`, que vem da planilha da pesquisa) é outra coisa, a fatia dos respondentes, e continua de 0 a 100. Nos textos (`baseCalculo`, `detalhe`) os números saem no padrão brasileiro: `459.900 de 460.000`. |
| Campos vazios | Um campo sem valor vem como `null`. As listas vêm sempre como `[]`, nunca `null`. |
| Códigos | Cotas: `MASTER`, `SUBCOTA_G`, `SUBCOTA`. Abas: `PERFIL`, `APLICACOES`, `MIDIA`, `CORTESIAS`, `CREDENCIAMENTO`, `PESQUISA`, `CONTRATO`. Perfis: `ADMIN_RBS`, `ADMIN_PATROCINADOR`. |
| Listas longas | Histórico, fila de entregas, cargas e rejeições são paginadas: `?pagina=0&tamanho=50` (pagina começa em 0; tamanho padrão 50, máximo 200; `pagina` negativa ou `tamanho` fora de 1 a 200 devolve 422). A resposta é `{"itens": [...], "pagina": 0, "tamanho": 50, "total": 137}`. As outras listas vêm inteiras, em array. |
| Filtros de data | `de=2026-10-01&ate=2026-10-31` (datas, inclusive, no horário de Brasília). Vale o que o formato `AAAA-MM-DD` escreve: do ano 0001 ao 9999. Data que não existe (`2026-02-30`), em outro formato ou com ano fora disso (`+300000-01-01`) dá 422 em `campos.de` ou `campos.ate` (`"Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-10-23."`). |

**Quem pode chamar.** Cada rota abaixo diz um destes:

- **Público**: sem login.
- **Logado**: qualquer usuário com sessão.
- **Admin RBS**: só o perfil `ADMIN_RBS`. O patrocinador recebe **403**.
- **Patrocinador da parceria**: o `ADMIN_PATROCINADOR` com vínculo vigente naquela parceria. A RBS também passa. Quem não tem vínculo recebe **404**, igual a uma parceria que não existe (veja "Isolamento" no fim).

## O formato do erro

Todo erro, sem exceção, vem como `application/problem+json` no formato `ProblemDetail` do Spring (RFC 9457):

```json
{
  "type": "about:blank",
  "title": "Conflito",
  "status": 409,
  "detail": "Este patrocinador já tem parceria nesta edição do evento.",
  "instance": "/api/parcerias"
}
```

- `detail` é uma frase em português, **pronta para mostrar ao usuário**. `title` é o nome curto do erro.
- Erro de validação (422) traz também `campos`, com o texto de cada campo errado. A chave é o nome do campo do corpo (ou do parâmetro da URL):

```json
{
  "type": "about:blank",
  "title": "Dados inválidos",
  "status": 422,
  "detail": "Confira os campos destacados e tente de novo.",
  "instance": "/api/auth/login",
  "campos": { "email": "E-mail inválido.", "senha": "Informe a senha." }
}
```

- A API **nunca** redireciona para uma página de login: sem sessão, responde 401 em JSON.
- A API **nunca** devolve mensagem crua do banco nem rastro de erro. Erro inesperado sai como 500 com texto genérico; o detalhe fica só no log do servidor.

| Status | Título | Quando acontece |
| --- | --- | --- |
| 400 | Requisição inválida | Corpo vazio ou que não é JSON, ou filtro da URL com o tipo errado (`?pagina=abc`, `?eventoId=abc`, `?atrasadas=talvez`): o `detail` diz qual, por exemplo `"O parâmetro pagina precisa ser um número inteiro."` ou `"O parâmetro atrasadas precisa ser true ou false."`. |
| 401 | Não autenticado | Sem sessão (ou sessão vencida), e-mail/senha incorretos no login, ou sessão de uma conta que perdeu o acesso (veja "Sessão e CSRF"). |
| 403 | Acesso negado | Logado, mas o perfil não pode fazer a operação (rota só da RBS), a aba está fechada para a parceria (duas frases diferentes, veja "Aba"), ou falta o token CSRF. |
| 404 | Não encontrado | O recurso **não existe ou é de outra parceria**. Os dois casos têm a mesma resposta, de propósito. Vale também para rota inexistente e para **id na URL que não é número** (`GET /api/parcerias/abc`): um id que não pode existir é um id que não existe, e o `detail` é a frase de "não encontrado" do recurso da rota (`"Não encontramos esta parceria."`, `"... esta entrega."`, `"... este arquivo."`, `"... esta pergunta."`, `"... este relatório."` (também o do Atlas), `"... esta carga."`, `"... esta conversa."`, `"... esta solicitação."`, `"... este turno."` (rota 51), `"... este provedor."` (rota 53), `"Não encontramos este e-mail nesta parceria."`), igual à de um id numérico sem registro. |
| 405 | Método não permitido | A rota existe, mas não aceita esse método HTTP. |
| 409 | Conflito | Uma regra do negócio recusou: regra do banco (SQLSTATE da classe 23, com a frase do gatilho), transição de status que não é permitida, registro duplicado. |
| 413 | Arquivo grande demais | Envio acima de 20 MB. |
| 415 | Tipo de conteúdo não aceito | Extensão ou tipo de arquivo fora da lista permitida, ou `Content-Type` errado. |
| 422 | Dados inválidos | Campo faltando ou fora do formato. Vem com `campos`. |
| 429 | Limite de uso atingido | Um limite de uso foi atingido. Hoje só a rota 44: o teto diário de mensagens do Atlas por parceria. O `detail` diz o que fazer no lugar. |
| 500 | Erro interno | Falha inesperada. O `detail` é sempre genérico. |
| 503 | Serviço indisponível | Uma parte de que a rota depende está fora do ar. Hoje só a rota 44: mensagem de texto no Atlas sem o modelo de linguagem. O `detail` diz o que fazer no lugar. |

**Ordem das conferências.** Quando uma chamada tem mais de um problema, a API responde, em geral, pelo primeiro destes: 401 (sem sessão, ou conta que perdeu o acesso) → 403 por perfil (rota só da RBS) → 404 (não existe ou não é do usuário) → 403 por aba não liberada → 400 e 422 (formato do corpo, das partes ou dos filtros) → 409 (o estado atual não permite). A validação do corpo vem depois da permissão: quem não pode usar a rota nunca recebe um erro de campo. O `HEAD` de uma rota `GET` segue as mesmas conferências, na mesma ordem (a resposta não traz corpo): a marca que faz `HEAD /api/historico?tamanho=999` recebe 403, como no `GET`, e não 422. Regras de campo que dependem do estado atual (por exemplo, a observação obrigatória da rota 19) vêm depois do 409; cada rota diz.

## Sessão e CSRF

O login abre uma **sessão no servidor**, identificada pelo cookie `JSESSIONID` (`HttpOnly`, `SameSite=Lax`, vale 8 horas sem uso). O JavaScript não lê esse cookie; o navegador o envia sozinho. Não existe token JWT.

Toda chamada que **altera** dados (`POST`, `PUT`, `PATCH`, `DELETE`) precisa de proteção contra CSRF:

1. O servidor grava o cookie `XSRF-TOKEN`, **legível pelo JavaScript**.
2. O front copia o valor desse cookie para o cabeçalho `X-XSRF-TOKEN` em cada chamada que altera dados.
3. Sem o cabeçalho, ou com valor diferente do cookie, a resposta é **403** com `detail` falando do token CSRF.

O cookie nasce no `GET /api/auth/csrf` ou no primeiro método que exige CSRF. Se ele não existir (primeira visita, ou depois do logout, que o apaga), o front chama `GET /api/auth/csrf`, que devolve o token e grava o cookie. A resposta já traz o valor pronto para usar no cabeçalho.

Roteiro de uso:

```text
1. GET  /api/auth/csrf        -> {"cabecalho":"X-XSRF-TOKEN","token":"..."} (e o cookie XSRF-TOKEN)
2. POST /api/auth/login       -> cabeçalho X-XSRF-TOKEN: <token>; corpo com e-mail e senha; responde o UsuarioLogado
3. (chamadas normais)         -> o navegador envia o cookie JSESSIONID sozinho; nos POST/PUT/PATCH/DELETE vai o X-XSRF-TOKEN
4. POST /api/auth/logout      -> 204; a sessão acaba
```

No `fetch`, use sempre `credentials: "same-origin"` (ou `"include"`, se front e API estiverem em origens diferentes). Se uma chamada voltar **401** no meio do uso, a sessão terminou (venceu, foi encerrada ou a conta perdeu o acesso): leve o usuário à tela de login.

**A conta é conferida em toda chamada com sessão.** A cada requisição a API confere, no banco, se a conta ainda vale: ativa e, para o patrocinador, com pelo menos um vínculo com parceria. Se a RBS revogou o e-mail ou desativou a conta depois do login, **qualquer rota** responde 401 com `detail` `"Sua sessão terminou. Entre de novo."` e a sessão é encerrada na hora (o `JSESSIONID` deixa de valer). Não existe 404 nem 403 "de transição": o front só precisa tratar o 401 em um lugar. O login, o `GET /api/auth/csrf`, a ativação e o logout continuam funcionando com uma sessão assim, para a pessoa entrar de novo com outra conta.

O login mantém o cookie `XSRF-TOKEN`; o **logout o apaga** (junto com o `JSESSIONID`). Depois de sair, chame `GET /api/auth/csrf` de novo antes do próximo POST.

## As rotas em uma tabela

Todas prontas e testadas. A coluna "Etapa" mostra em que etapa do plano a rota entrou (3 a 7).

| # | Rota | Quem chama | O que faz | Etapa |
| --- | --- | --- | --- | --- |
| 1 | `GET /api/auth/csrf` | Público | Devolve o token CSRF e grava o cookie | 3 |
| 2 | `POST /api/auth/login` | Público | Entra com e-mail e senha | 3 |
| 3 | `POST /api/auth/logout` | Logado | Encerra a sessão | 3 |
| 4 | `GET /api/auth/eu` | Logado | Devolve quem está logado e suas parcerias | 3 |
| 5 | `POST /api/auth/ativar` | Público | Primeiro acesso: define a senha com o código | 4 |
| 6 | `GET /api/eventos` | Logado | Lista as edições do festival | 4 |
| 7 | `GET /api/parcerias` | Logado | Lista as parcerias (RBS: todas, com resumo) | 4 |
| 8 | `POST /api/parcerias` | Admin RBS | Cria uma parceria | 4 |
| 9 | `GET /api/parcerias/{id}` | Patrocinador da parceria, ou Admin RBS | Dados da parceria, abas e e-mails | 4 |
| 10 | `PATCH /api/parcerias/{id}` | Admin RBS | Edita o Perfil e libera abas | 4 |
| 11 | `GET /api/parcerias/{id}/emails` | Admin RBS | Lista os e-mails autorizados | 4 |
| 12 | `POST /api/parcerias/{id}/emails` | Admin RBS | Autoriza um e-mail e gera o código de ativação | 4 |
| 13 | `DELETE /api/parcerias/{id}/emails/{emailId}` | Admin RBS | Revoga o e-mail e tira o acesso | 4 |
| 14 | `GET /api/parcerias/{id}/entregas` | Patrocinador da parceria, ou Admin RBS | Lista as entregas, por aba | 5 |
| 15 | `POST /api/parcerias/{id}/entregas` | Admin RBS | Cria uma entrega | 5 |
| 16 | `GET /api/entregas` | Admin RBS | Fila de entregas de todas as parcerias, com filtros | 5 |
| 17 | `POST /api/entregas/{id}/arquivos` | Patrocinador da parceria, ou Admin RBS | Envia um arquivo (a RBS envia o retorno) | 5 |
| 18 | `GET /api/arquivos/{id}` | Patrocinador da parceria, ou Admin RBS | Baixa um arquivo, como anexo | 5 |
| 19 | `PATCH /api/entregas/{id}/status` | Admin RBS | Muda o status da entrega, com observação | 5 |
| 20 | `GET /api/parcerias/{id}/historico` | Patrocinador da parceria, ou Admin RBS | Histórico da parceria | 5 |
| 21 | `GET /api/historico` | Admin RBS | Histórico geral, com filtros | 5 |
| 22 | `GET /api/historico.csv` | Admin RBS | O mesmo histórico, em CSV | 5 |
| 23 | `GET /api/parcerias/{id}/pesquisa` | Patrocinador da parceria, ou Admin RBS | Perguntas e resultados da pesquisa | 6 |
| 24 | `PUT /api/parcerias/{id}/pesquisa/perguntas` | Admin Patrocinador da parceria | Envia as até 3 perguntas | 6 |
| 25 | `PATCH /api/pesquisa/perguntas/{id}/status` | Admin RBS | Aprova a pergunta ou pede ajuste | 6 |
| 26 | `POST /api/cargas` | Admin RBS | Envia um CSV de veiculação, de pesquisa ou do plano comercial | 6 |
| 27 | `GET /api/cargas` | Admin RBS | Lista as cargas feitas | 6 |
| 28 | `GET /api/cargas/{id}/rejeicoes` | Admin RBS | Linhas que não entraram na carga, com o motivo | 6 |
| 29 | `GET /api/parcerias/{id}/consolidacao` | Patrocinador da parceria, ou Admin RBS | Previsto x exibido e faltantes | 6 |
| 30 | `GET /api/painel` | Admin RBS | Números do painel da RBS | 6 |
| 31 | `GET /api/relatorios` | Admin RBS | Situação do relatório de cada parceria | 7 |
| 32 | `GET /api/relatorios.csv` | Admin RBS | A mesma lista, em CSV | 7 |
| 33 | `POST /api/parcerias/{id}/relatorios` | Admin RBS | Gera uma nova versão do relatório | 7 |
| 34 | `PATCH /api/relatorios/{id}/status` | Admin RBS | Valida ou disponibiliza o relatório | 7 |
| 35 | `GET /api/parcerias/{id}/relatorio` | Patrocinador da parceria, ou Admin RBS | Relatório da parceria (o patrocinador só vê o disponibilizado) | 7 |
| 36 | `GET /api/relatorios/{id}/csv` | Patrocinador da parceria, ou Admin RBS | Um relatório em CSV | 7 |
| 37 | `GET /api/parcerias/{id}/aplicacoes` | Patrocinador da parceria, ou Admin RBS | O plano de aplicações de marca, linha a linha, com as fotos | Atlas 1 |
| 38 | `POST /api/parcerias/{id}/aplicacoes/{aproveitamentoId}/comprovacoes` | Admin RBS | Anexa uma foto de comprovação a uma aplicação | Atlas 1 |
| 39 | `GET /api/campos` | Logado | O catálogo dos campos que a marca pode informar | Atlas 1 |
| 40 | `GET /api/parcerias/{id}/dados` | Patrocinador da parceria, ou Admin RBS | Os valores vigentes dos campos de uma aba, com autor e data | Atlas 1 |
| 41 | `POST /api/parcerias/{id}/dados` | Patrocinador da parceria, ou Admin RBS | Grava de 1 a 20 valores novos de uma aba | Atlas 1 |
| 42 | `POST /api/parcerias/{id}/atlas/conversas` | Admin Patrocinador da parceria | Abre uma conversa com o Atlas; com o atalho, já monta o relatório | Atlas 2 |
| 43 | `GET /api/parcerias/{id}/atlas/conversas` | Admin Patrocinador da parceria | As conversas de quem pede nesta parceria | Atlas 2 |
| 44 | `POST /api/atlas/conversas/{id}/mensagens` | Dono da conversa | Manda uma mensagem, ou o atalho "Montar relatório" | Atlas 2 |
| 45 | `GET /api/atlas/conversas/{id}` | Dono da conversa | A conversa inteira | Atlas 2 |
| 46 | `POST /api/atlas/solicitacoes/{id}/respostas` | Dono da conversa | Responde o cartão de perguntas (accept, decline ou cancel) | Atlas 2 |
| 47 | `GET /api/atlas/relatorios/{id}` | Patrocinador da parceria, ou Admin RBS | O relatório do Atlas, pronto ou em montagem | Atlas 2 |
| 48 | `GET /api/parcerias/{id}/atlas/relatorios` | Patrocinador da parceria, ou Admin RBS | Os relatórios do Atlas da parceria | Atlas 2 |
| 49 | `GET /api/atlas/conversas/{id}/diagnostico` | Dono da conversa (com o diagnóstico e `atlas.diagnostico.marca` ligados) | Os turnos da conversa com as etapas, os tokens e o custo | Atlas M2 |
| 50 | `GET /api/atlas/diagnostico/turnos` | Admin RBS (com o diagnóstico ligado) | Os turnos de todas as conversas, paginados, com filtros | Atlas M2 |
| 51 | `GET /api/atlas/diagnostico/turnos/{turno}` | Admin RBS (com o diagnóstico ligado) | Um turno com as etapas, a parceria e quem conversou | Atlas M2 |
| 52 | `GET /api/atlas/provedores` | Admin RBS (com o diagnóstico ligado) | O estado do modelo de linguagem, do JEV e do Gemini, e o teto diário por parceria | Atlas M2 |
| 53 | `POST /api/atlas/provedores/{nome}/teste` | Admin RBS (com o diagnóstico ligado) | Uma chamada mínima a um provedor (`llm`, `jev` ou `gemini`) | Atlas M2 |

São 53 operações (método + caminho). A rota 5 (`ativar`) faz parte do contrato de autenticação, mas só é implementada na etapa 4. As rotas 37 em diante vieram com o Atlas: a coluna "Etapa" diz `Atlas 1` (plano de aplicações e dados informados), `Atlas 2` (conversa, cartão de perguntas e relatório) ou `Atlas M2` (o diagnóstico: rotas 49 a 53, veja "Diagnóstico do Atlas"). A 49 é só do Admin Patrocinador, dono da conversa; as 50 a 53 são só do Admin RBS (o outro perfil recebe **403** antes de tudo). **As rotas 49 a 53 estão sempre mapeadas e respondem 404 com o diagnóstico desligado** (`atlas.diagnostico.ligado=false`, o padrão). Nas rotas 42 a 46 só o Admin Patrocinador chama (a RBS recebe **403** antes de o corpo ser lido); nas 47 e 48 chamam a marca da parceria e a RBS. **Neste MVP o relatório do Atlas está desligado** (`atlas.relatorio.ligado=false`, o padrão): as rotas **46, 47 e 48 não existem (404)**, e as rotas 42 e 44 só aceitam conversa por texto (`atalho` e `modelo` dão 422). Veja "Modo só chat". O contrato do relatório, nas rotas 42 a 48, vale com `atlas.relatorio.ligado=true`.

As rotas de debug do modelo de linguagem (`/api/debug/llm`) são temporárias, só existem no perfil `dev`, são só da RBS e ficam fora desta tabela e da conta das 53: veja "Rotas de debug (temporárias)", no fim.

## Objetos que se repetem

Estes formatos aparecem em várias rotas.

**Usuário resumido** (quem fez algo): `{ "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" }`

**Cota**: `{ "codigo": "MASTER", "nome": "Master" }` (o `nome` é o do banco: Master, Subcota G, Subcota)

**Evento**: `{ "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 }`

**Aba**: `{ "codigo": "PESQUISA", "nome": "Pesquisa da ativação", "ordem": 6, "liberada": true, "incluidaNaCota": true }`. Sempre as 7 abas, em ordem. Dois booleanos dizem coisas diferentes:

- `liberada: false` = a aba está **fechada** para esta parceria (a RBS ainda não abriu, fechou ou escondeu do menu); o front mostra o cadeado. Uma aba só conta como liberada se estiver liberada **e** visível no banco.
- `incluidaNaCota: false` = a aba **não faz parte da cota** da parceria (vem de `cota_tipo_aba`; hoje a Pesquisa só está na cota Master). Aba fora da cota nunca abre: a RBS não consegue liberá-la (rota 10 devolve 422). Use este campo para mostrar "fora da sua cota" em vez de "aguardando liberação".

**As duas frases do 403 de aba.** Toda rota que o patrocinador não pode usar por causa de uma aba fechada responde **403** com o mesmo `title` ("Acesso negado") e uma de duas frases em `detail`, escolhida pelo `incluidaNaCota` da aba:

| Situação da aba | `detail` do 403 |
| --- | --- |
| A cota da parceria **não inclui** a aba (`incluidaNaCota: false`) | `"Esta aba não faz parte da cota da sua parceria."` |
| A cota inclui a aba, mas a RBS ainda não a abriu, a fechou ou a escondeu do menu (`incluidaNaCota: true`, `liberada: false`) | `"Esta aba ainda não foi liberada para a sua parceria."` |

A regra é uma só em toda a API (lista de entregas, envio de arquivo, download de arquivo, pesquisa, plano de aplicações): aba aberta = liberada **e** visível. A RBS nunca recebe esse 403.

**O que sai quando a aba está fechada.** Para o patrocinador, nada que pertença a uma aba fechada sai por rota nenhuma: a lista de entregas (rota 14), o download de arquivo de entrega e o da foto de comprovação (rota 18) e o plano de aplicações (rota 37) respondem 403, o envio (rota 17) responde 403, e a consolidação (rota 29) não traz a parte daquela aba. A RBS continua vendo tudo.

`incluidaNaCota` vem em toda lista de abas: `parcerias[].abas` do `UsuarioLogado` (login e `GET /api/auth/eu`) e `abas` da parceria (rotas 8, 9 e 10).

**Arquivo** (um envio, uma versão):

```json
{
  "id": 12, "entregaId": 31, "nomeOriginal": "logo_final.pdf", "mimeType": "application/pdf",
  "tamanhoBytes": 482113, "versao": 2, "categoria": "MATERIAL_MARCA",
  "enviadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "enviadoEm": "2026-10-17T18:03:00-03:00",
  "observacao": "Logo em vetor, como pedido."
}
```

`observacao` é o recado que quem enviou escreveu **junto com aquela versão** (até 500 caracteres), ou `null` se não escreveu nada. Cada versão tem o seu: a v1 e a v2 de um arquivo podem ter recados diferentes. O mesmo texto também fica no histórico (`detalhe` do `ENVIO_ARQUIVO` ou da `NOVA_VERSAO`).

**Entrega**:

```json
{
  "id": 31, "parceriaId": 2, "abaCodigo": "APLICACOES",
  "titulo": "Logo final para aplicações no evento", "descricao": null,
  "status": "APROVADO", "prazo": "2026-10-20",
  "criadoEm": "2026-09-28T10:00:00-03:00", "atualizadoEm": "2026-10-18T10:15:00-03:00",
  "arquivos": [ /* Arquivo, da versão mais nova para a mais antiga */ ],
  "ultimaObservacao": { "texto": "Enviar o logo em vetor.", "por": "Equipe B2B RBS", "em": "2026-10-16T09:42:00-03:00" }
}
```

Status da entrega: `NAO_INICIADO`, `AGUARDANDO_ENVIO`, `ENVIADO`, `EM_ANALISE`, `AJUSTE_SOLICITADO`, `APROVADO`, `NAO_APLICAVEL`. `ultimaObservacao` é o texto que a RBS escreveu na última mudança de status (ou `null`).

---

# Rotas de autenticação (`/api/auth`)

Estas são o contrato que o front já usa. O formato **não muda** sem aviso.

## O objeto UsuarioLogado

Devolvido por `POST /api/auth/login` e `GET /api/auth/eu`:

```json
{
  "id": 3,
  "nome": "Marketing Amarelo",
  "email": "marketing@amarelo.example",
  "perfil": "ADMIN_PATROCINADOR",
  "parcerias": [
    {
      "id": 2,
      "patrocinador": "Cliente Amarelo",
      "cota": { "codigo": "MASTER", "nome": "Master" },
      "evento": { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 },
      "abas": [
        { "codigo": "PERFIL", "nome": "Perfil do cliente", "ordem": 1, "liberada": true, "incluidaNaCota": true },
        { "codigo": "APLICACOES", "nome": "Aplicações de marca no evento", "ordem": 2, "liberada": true, "incluidaNaCota": true },
        { "codigo": "MIDIA", "nome": "Mídia e conteúdo", "ordem": 3, "liberada": true, "incluidaNaCota": true },
        { "codigo": "CORTESIAS", "nome": "Cortesias", "ordem": 4, "liberada": true, "incluidaNaCota": true },
        { "codigo": "CREDENCIAMENTO", "nome": "Credenciamento", "ordem": 5, "liberada": true, "incluidaNaCota": true },
        { "codigo": "PESQUISA", "nome": "Pesquisa da ativação", "ordem": 6, "liberada": true, "incluidaNaCota": true },
        { "codigo": "CONTRATO", "nome": "Contrato", "ordem": 7, "liberada": true, "incluidaNaCota": true }
      ]
    }
  ]
}
```

- `perfil` é `ADMIN_PATROCINADOR` ou `ADMIN_RBS`.
- `parcerias` lista as parcerias a que o usuário tem vínculo, da edição mais nova para a mais antiga. Para o `ADMIN_RBS` vem **vazia**, porque a RBS enxerga todas (use `GET /api/parcerias`).
- `abas` traz sempre as 7 abas, em ordem; `liberada` é `false` quando a aba está fechada para a parceria e `incluidaNaCota` é `false` quando a cota nem inclui a aba (por exemplo, a Pesquisa em cota que não é Master). Veja "Aba" em "Objetos que se repetem".
- A resposta **nunca** traz senha nem hash.

## 1. `GET /api/auth/csrf`  · etapa 3

**Quem chama:** público. **Corpo:** nenhum.

Devolve o token CSRF e garante o cookie `XSRF-TOKEN` (o mesmo valor). O front chama antes do primeiro `POST` se o cookie não existir.

**200**

```json
{ "cabecalho": "X-XSRF-TOKEN", "token": "448e7507-5e30-45e8-bb85-e4da7cec954a" }
```

Use `cabecalho` como nome do cabeçalho e `token` como valor nas chamadas que alteram dados.

## 2. `POST /api/auth/login`  · etapa 3

**Quem chama:** público (exige o token CSRF).

**Corpo**

```json
{ "email": "marketing@amarelo.example", "senha": "senha123" }
```

| Campo | Regra |
| --- | --- |
| `email` | Obrigatório, formato de e-mail, até 254 caracteres. Espaços nas pontas são ignorados e maiúsculas não importam. |
| `senha` | Obrigatória, até 72 caracteres. |

**200**: o `UsuarioLogado` (acima). Também abre a sessão (`Set-Cookie: JSESSIONID=...; HttpOnly; SameSite=Lax`). Se o navegador já tinha uma sessão, o servidor troca o id dela ao entrar (proteção contra fixação de sessão).

**Erros**

| Status | Quando |
| --- | --- |
| 401 | E-mail ou senha incorretos. O `detail` é sempre `"E-mail ou senha incorretos."`, seja e-mail inexistente, senha errada, conta que ainda não definiu a senha, conta desativada ou **acesso revogado**. Assim a resposta não revela quais e-mails existem. |
| 403 | Faltou o token CSRF. |
| 422 | Corpo com campo faltando ou e-mail em formato inválido (`campos.email`, `campos.senha`). |

**Histórico:** grava `LOGIN` no sucesso. Na falha grava `LOGIN_FALHOU`, com o e-mail tentado no `detalhe` (`"e-mail tentado: x@y"`); se o e-mail existe, grava também o id do usuário.

## 3. `POST /api/auth/logout`  · etapa 3

**Quem chama:** logado (exige o token CSRF). **Corpo:** nenhum.

**204**, sem corpo. Encerra a sessão e apaga os cookies `JSESSIONID` e `XSRF-TOKEN`. Sem sessão também responde 204 (sair duas vezes não é erro). Sem o token CSRF, 403.

**Histórico:** grava `LOGOUT`.

## 4. `GET /api/auth/eu`  · etapa 3

**Quem chama:** logado. **Corpo:** nenhum.

**200**: o `UsuarioLogado`, **lido de novo no banco** a cada chamada. Serve para o front descobrir se a sessão ainda vale ao abrir o app.

**Erros**

| Status | Quando |
| --- | --- |
| 401 | Sem sessão: `{"title":"Não autenticado","detail":"Entre no portal para continuar."}`. Também quando a sessão existe, mas a conta foi desativada ou perdeu todos os vínculos (acesso revogado) depois do login: a sessão é encerrada na hora e o `detail` é `"Sua sessão terminou. Entre de novo."`. Isto vale para **toda** rota com sessão, não só para esta (veja "Sessão e CSRF"). |

## 5. `POST /api/auth/ativar`  · etapa 4

**Quem chama:** público (exige o token CSRF). Primeiro acesso: o patrocinador define a senha com o **código de ativação** que a RBS repassou (na V1 não há e-mail automático).

**Corpo**

```json
{ "email": "novo@cliente.example", "codigo": "K7M2-9QXD", "senha": "uma-senha-com-8-ou-mais" }
```

| Campo | Regra |
| --- | --- |
| `email` | O e-mail que a RBS autorizou. |
| `codigo` | O código recebido da RBS (maiúsculas e o hífen não importam). Vale 7 dias e só pode ser usado uma vez. |
| `senha` | De 8 a 72 caracteres. |
| `nome` | **Opcional.** Como o usuário quer ser chamado. Se faltar, o servidor usa a parte do e-mail antes do `@`. (Acréscimo ao contrato do front: ele não precisa mandar.) |

**204**, sem corpo. A conta é criada (ou a senha é definida), o vínculo com a parceria é feito e a ação `ATIVACAO` vai para o histórico. **Não** abre sessão: depois disso o front chama `POST /api/auth/login`.

**Erros**

| Status | Quando |
| --- | --- |
| 422 | Código inválido, vencido ou já usado (inclui conta que já foi ativada), e-mail não autorizado ou revogado, ou senha fora do tamanho. Para o código e o e-mail a frase é a mesma (`"Código inválido ou vencido. Peça um novo à RBS."`), para não revelar quais e-mails existem. Erros de formato vêm em `campos`. |
| 403 | Faltou o token CSRF. |

Depois de **5 códigos errados** para o mesmo e-mail, o código é bloqueado e a RBS precisa gerar outro (`POST /api/parcerias/{id}/emails`). Bloqueado, até o código certo é recusado.

Detalhes que o front pode contar com (etapa 4):

- Os 422 de código e de e-mail trazem também `campos.codigo` com a mesma frase, para a tela destacar o campo. Erro de formato (senha curta, e-mail inválido) **não** conta como código errado.
- O código aceito tem 8 símbolos de um alfabeto sem `0`, `O`, `1` e `I`, escrito `K7M2-9QXD`; a API ignora hífen, espaço e caixa.
- Se o mesmo e-mail foi autorizado em mais de uma parceria antes do primeiro acesso (uma agência, por exemplo), a conta nasce com acesso a **todas** elas e os outros códigos pendentes deixam de valer. O histórico ganha uma linha `ATIVACAO` por parceria.
- A senha também tem o limite de 72 **bytes** (acento conta mais de um); acima disso, 422 em `campos.senha`.

---

# Parcerias, eventos e e-mails autorizados (etapa 4)

## 6. `GET /api/eventos`  · etapa 4

**Quem chama:** logado. **Corpo:** nenhum.

**200**: as edições do festival, da mais nova para a mais antiga.

```json
[
  { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027, "dataInicio": null, "dataFim": null, "local": "Saba, Atlântida (RS)" },
  { "id": 1, "nome": "Planeta Atlântida", "edicao": 2026, "dataInicio": "2026-01-30", "dataFim": "2026-01-31", "local": "Saba, Atlântida (RS)" }
]
```

As datas de 2027 ainda estão em branco (aguardando a RBS).

## 7. `GET /api/parcerias`  · etapa 4

**Quem chama:** logado. A **RBS** recebe todas as parcerias, cada uma com um resumo. O **patrocinador** recebe só as dele.

**Filtros (opcionais):** `eventoId`, `situacao` (`EM_NEGOCIACAO`, `ATIVA`, `ENCERRADA`, `CANCELADA`) e `q` (parte do nome do patrocinador). A ordem é edição mais nova primeiro e nome do patrocinador.

**200**

```json
[
  {
    "id": 2,
    "patrocinador": { "id": 1, "nome": "Cliente Amarelo" },
    "evento": { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 },
    "cota": { "codigo": "MASTER", "nome": "Master" },
    "nomeProduto": "Cota Patrocínio Master",
    "situacao": "ATIVA",
    "entregas": { "total": 11, "aprovadas": 10, "pendentes": 1, "naoAplicaveis": 0, "percentualConcluido": 90.9 },
    "usuarios": 2,
    "relatorio": { "id": 1, "versao": 1, "status": "RASCUNHO" }
  }
]
```

- `entregas.pendentes` são as entregas que não estão `APROVADO` nem `NAO_APLICAVEL`. `percentualConcluido` = aprovadas ÷ (total − não aplicáveis); `null` se não há entregas.
- Para o **patrocinador**, o resumo de `entregas` conta só as entregas de aba aberta (liberada **e** visível), as mesmas que a lista de entregas (rota 14) devolve. Sem nenhuma, vem zerado e com `percentualConcluido` `null`. A RBS conta todas as abas.
- `usuarios` é o número de usuários vinculados. `relatorio` é a **versão mais nova** do relatório da parceria (ou `null` se ainda não há).
- Para o **patrocinador**: `usuarios` vem `null`, e `relatorio` só aparece se a versão mais nova que a RBS **disponibilizou** existir (senão `null`).

## 8. `POST /api/parcerias`  · etapa 4

**Quem chama:** Admin RBS (exige o token CSRF). Cria a parceria de uma marca em uma edição, com a cota e os e-mails que poderão entrar.

**Corpo**

```json
{
  "patrocinador": { "nome": "Cliente Novo", "segmento": "Varejo" },
  "eventoId": 2,
  "cotaCodigo": "SUBCOTA",
  "nomeProduto": "Cota Ativação Arena",
  "valorInvestimento": 100000.00,
  "baseValor": "LIQUIDO",
  "emails": [
    { "email": "marketing@novo.example", "vinculo": "MARCA" },
    { "email": "atendimento@agencia.example", "vinculo": "AGENCIA" }
  ]
}
```

| Campo | Regra |
| --- | --- |
| `patrocinador` | **Existente:** `{ "id": 1 }`. **Novo:** `{ "nome": "...", "segmento": "..." }` (segmento é opcional). Mande um dos dois. |
| `eventoId`, `cotaCodigo` | Obrigatórios. A cota define quais abas abrem por padrão. |
| `nomeProduto` | Opcional, até 120 caracteres. |
| `valorInvestimento`, `baseValor` | Opcionais, mas **um não vai sem o outro**. `baseValor`: `BRUTO_TABELA` ou `LIQUIDO`. |
| `emails` | Opcional (pode autorizar depois, pela rota 12). `vinculo`: `MARCA` ou `AGENCIA`. |

**201** (com o cabeçalho `Location: /api/parcerias/{id}`): o objeto da rota 9, em que cada e-mail autorizado traz também **`codigoAtivacao`** e **`codigoExpiraEm`**. O código só aparece **nesta resposta**; depois não há como ver de novo (só gerar outro). A RBS repassa o código ao patrocinador. Se o e-mail já pertence a uma conta de patrocinador que fez o primeiro acesso por outra parceria, o vínculo é feito na hora: `situacao` vem `ATIVO` e `codigoAtivacao` vem `null`.

**Nome do patrocinador.** O nome novo é guardado **normalizado**: sem espaços nas pontas e com qualquer sequência de espaços, tabulações ou espaços não quebráveis juntada num espaço só (`"  Marca   Nova "` vira `"Marca Nova"`). Para saber se o nome já existe, a API compara o nome normalizado com os já cadastrados, também normalizados, sem diferença de maiúsculas: `"Cliente  Verde"` e `"  cliente  ROXO "` batem com `"Cliente Verde"` e `"Cliente Roxo"` e dão 409, em vez de criar um segundo patrocinador. A mesma regra vale no `nome` do `PATCH` da rota 10.

Os e-mails são guardados em minúsculas e sem espaços nas pontas. A parceria nasce `ATIVA`, com as 7 abas abertas conforme a cota (o banco copia `cota_tipo_aba`). Tudo vai numa transação só: com qualquer erro, nada é gravado.

**Erros**

| Status | Quando |
| --- | --- |
| 403 | Não é Admin RBS, ou faltou o token CSRF. |
| 409 | O patrocinador já tem parceria nesta edição (`"Este patrocinador já tem parceria nesta edição do evento."`), ou já existe um patrocinador com este nome (`"Já existe um patrocinador com este nome. Escolha-o na lista \"Patrocinador\"."`; a comparação é feita sobre o nome normalizado, veja abaixo). |
| 422 | Campo faltando, evento/cota/patrocinador que não existem, `id` e `nome` do patrocinador juntos (ou nenhum), valor sem base, e-mail inválido, repetido na lista ou de um usuário da RBS (`campos`; os da lista saem como `emails[0].email`). |

**Histórico:** `CRIACAO` da parceria e `AUTORIZACAO` de cada e-mail.

## 9. `GET /api/parcerias/{id}`  · etapa 4

**Quem chama:** patrocinador da parceria, ou Admin RBS. Quem não é da parceria recebe **404**.

**200**

```json
{
  "id": 2,
  "patrocinador": { "id": 1, "nome": "Cliente Amarelo", "segmento": "Bebidas" },
  "evento": { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 },
  "cota": { "codigo": "MASTER", "nome": "Master" },
  "nomeProduto": "Cota Patrocínio Master",
  "valorInvestimento": 1000000.00,
  "baseValor": "BRUTO_TABELA",
  "situacao": "ATIVA",
  "criadoEm": "2026-09-20T11:00:00-03:00",
  "abas": [ { "codigo": "PERFIL", "nome": "Perfil do cliente", "ordem": 1, "liberada": true, "incluidaNaCota": true } ],
  "emailsAutorizados": [
    { "id": 5, "email": "marketing@amarelo.example", "vinculo": "MARCA", "situacao": "ATIVO" },
    { "id": 6, "email": "atendimento@agencia-x.example", "vinculo": "AGENCIA", "situacao": "ATIVO" }
  ]
}
```

- `abas` traz as 7 abas, com `liberada` e `incluidaNaCota` (o exemplo mostra uma só para ficar curto).
- `emailsAutorizados` traz só os e-mails **não revogados**, com `situacao` `ATIVO` (já tem conta e vínculo) ou `PENDENTE` (autorizado, ainda sem primeiro acesso). Os detalhes (quem autorizou, revogados) estão na rota 11, só da RBS.

## 10. `PATCH /api/parcerias/{id}`  · etapa 4

**Quem chama:** Admin RBS (exige o token CSRF). Edita o **Perfil** da parceria e libera ou fecha abas. Mande só o que muda.

**Corpo (todos os campos são opcionais)**

```json
{
  "patrocinador": { "nome": "Cliente Amarelo S.A.", "segmento": "Bebidas" },
  "nomeProduto": "Cota Patrocínio Master",
  "valorInvestimento": 1200000.00,
  "baseValor": "LIQUIDO",
  "situacao": "ENCERRADA",
  "abas": [ { "codigo": "PESQUISA", "liberada": true } ]
}
```

A cota e o evento **não mudam** por aqui (mudar a cota refaria as abas). `abas` liberam (`liberada: true`) ou fecham uma aba só nesta parceria; cada aba pode levar também `visivel`.

**Só se libera aba que a cota inclui.** A aba precisa estar em `cota_tipo_aba` para a cota da parceria (`incluidaNaCota: true`; a decisão do projeto é a Pesquisa só para a cota Master). Mandar `liberada: true` para uma aba fora da cota, que está fechada, devolve **422** e nada do `PATCH` é gravado (todo o corpo é recusado, como em qualquer outro 422). O `detail` traz a frase, e `campos` a repete na chave `abas[i].liberada` (`i` = posição da aba na lista enviada):

```json
{
  "type": "about:blank", "title": "Dados inválidos", "status": 422,
  "detail": "A cota Subcota G não inclui a aba Pesquisa da ativação.",
  "instance": "/api/parcerias/3",
  "campos": { "abas[0].liberada": "A cota Subcota G não inclui a aba Pesquisa da ativação." }
}
```

Quando o 422 vem junto com outro erro de campo, o `detail` volta a ser `"Confira os campos destacados e tente de novo."` e a frase fica só em `campos`. **Fechar** (`liberada: false`) e ocultar (`visivel`) valem para qualquer aba. Repetir `liberada: true` numa aba que já estava aberta (dado de antes da regra) é um `PATCH` sem mudança e não dá erro; depois de fechada, ela não volta a abrir.

**200**: o objeto da rota 9, já com as mudanças. **Erros:** 403, 404, 409 (nome de patrocinador repetido), 422 (`valorInvestimento` sem `baseValor`, aba que não existe, situação inválida, aba fora da cota que se tenta liberar).

**Regras:** campo ausente ou `null` = não muda; texto em branco em `nomeProduto`, `patrocinador.segmento` limpa o campo. O `patrocinador.nome` é normalizado como na rota 8 (sem espaços nas pontas nem repetidos) e comparado com os outros patrocinadores sem diferença de maiúsculas ou de espaços; repetido dá 409 (`"Já existe um patrocinador com este nome."`). Mandar o nome atual com espaços a mais não muda nada e não grava histórico. O valor e a base vão sempre juntos (no estado final). O nome e o segmento do `patrocinador` valem para a marca inteira, em todas as edições. Mandar uma aba no estado em que ela já está não grava nada.

**Histórico:** uma linha `EDICAO` **por campo alterado** (`detalhe` = nome do campo, com `valorAnterior` e `valorNovo`) e uma `LIBERACAO_ABA` para cada aba alterada (`valorAnterior`/`valorNovo` = `liberada`, `fechada` ou `oculta`). O `PATCH` sem mudança real não grava histórico.

## 11. `GET /api/parcerias/{id}/emails`  · etapa 4

**Quem chama:** Admin RBS. **200**: todos os e-mails da parceria, inclusive os revogados.

```json
[
  {
    "id": 5, "email": "marketing@amarelo.example", "vinculo": "MARCA", "situacao": "ATIVO", "usuarioId": 3,
    "autorizadoPor": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" }, "autorizadoEm": "2026-09-28T10:00:00-03:00",
    "revogadoPor": null, "revogadoEm": null
  }
]
```

`situacao`: `PENDENTE` (autorizado, sem primeiro acesso), `ATIVO` (já entrou alguma vez e tem vínculo) ou `REVOGADO`. `usuarioId` é `null` enquanto não houver conta.

## 12. `POST /api/parcerias/{id}/emails`  · etapa 4

**Quem chama:** Admin RBS (exige o token CSRF). Autoriza um e-mail e gera o **código de ativação**.

**Corpo:** `{ "email": "novo@cliente.example", "vinculo": "AGENCIA" }`

**201** (e-mail novo)

```json
{
  "id": 7, "email": "novo@cliente.example", "vinculo": "AGENCIA", "situacao": "PENDENTE", "usuarioId": null,
  "autorizadoPor": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" }, "autorizadoEm": "2026-10-23T15:00:00-03:00",
  "revogadoPor": null, "revogadoEm": null,
  "codigoAtivacao": "K7M2-9QXD", "codigoExpiraEm": "2026-10-30T15:00:00-03:00"
}
```

O `codigoAtivacao` aparece **só nesta resposta**. No banco fica apenas o hash. O código vale 7 dias e é de uso único.

Casos especiais:

- **E-mail pendente** (autorizado, ainda sem primeiro acesso): chamar de novo **reemite** o código; o anterior deixa de valer. Responde **200**.
- **E-mail revogado antes** (reautorizar): o acesso volta. Se a pessoa já tinha conta, o vínculo volta na hora e `codigoAtivacao` vem `null`; se não tinha, vem um código novo. Responde **200** (a linha já existia).
- **E-mail de uma conta que já fez o primeiro acesso** (por outra parceria): o vínculo é feito na hora, `situacao` vem `ATIVO` e `codigoAtivacao` vem `null`. Responde **201**.
- O e-mail é guardado em minúsculas e sem espaços nas pontas. E-mail de um usuário da RBS não pode ser vinculado a uma parceria: 422 em `campos.email`.

**Erros:** 403; 404 (parceria); 409 (`"Este e-mail já está autorizado nesta parceria."`, quando já está ativo); 422 (e-mail ou vínculo inválidos). **Histórico:** `AUTORIZACAO`.

## 13. `DELETE /api/parcerias/{id}/emails/{emailId}`  · etapa 4

**Quem chama:** Admin RBS (exige o token CSRF). **Corpo:** nenhum.

Revoga o e-mail. A linha **não é apagada**: fica marcada como revogada (a trilha é mantida), o banco remove o vínculo da pessoa com a parceria e invalida os códigos pendentes. O acesso some **na hora**: quem estava logado perde a parceria na chamada seguinte.

**204**, sem corpo. **Erros:** 403; 404 (parceria ou e-mail que não existe ou é de outra parceria); 409 (`"Este e-mail já estava revogado."`). **Histórico:** `REVOGACAO`.

Na chamada seguinte da pessoa revogada, **qualquer rota** (`GET /api/auth/eu`, `GET /api/parcerias/{id}`, `GET /api/parcerias`, um `PUT`...) responde 401 com `"Sua sessão terminou. Entre de novo."` e a sessão termina. Isso vale quando o e-mail revogado era o último vínculo da pessoa; se ela ainda tem outra parceria, só a parceria revogada some (404 nela e fora da lista).

---

# Entregas, arquivos e histórico (etapa 5)

## 14. `GET /api/parcerias/{id}/entregas`  · etapa 5

**Quem chama:** patrocinador da parceria, ou Admin RBS.

**Filtros (opcionais):** `aba` (código da aba, ex.: `MIDIA`) e `status`. A ordem é a da aba e, dentro dela, a de criação.

O patrocinador só enxerga as entregas das abas **liberadas** para a parceria dele. Pedir `aba=` de uma aba fechada devolve **403**: `"Esta aba não faz parte da cota da sua parceria."` se a cota não inclui a aba, ou `"Esta aba ainda não foi liberada para a sua parceria."` se a cota inclui e a RBS ainda não a abriu (veja "Aba"). Sem `aba=`, vêm as de todas as abas liberadas. A RBS vê todas.

**200**: array de **Entrega** (formato em "Objetos que se repetem").

**Erros:** 403 (aba fechada), 404 (parceria), 422 (`aba` ou `status` com valor que não existe).

## 15. `POST /api/parcerias/{id}/entregas`  · etapa 5

**Quem chama:** Admin RBS (exige o token CSRF). Só a RBS cria entregas; o patrocinador só responde a elas.

**Corpo**

```json
{
  "abaCodigo": "MIDIA",
  "titulo": "VT para os telões dos palcos",
  "descricao": "Arquivo em MP4, até 30 segundos.",
  "prazo": "2026-12-01",
  "status": "AGUARDANDO_ENVIO"
}
```

`abaCodigo` e `titulo` (até 160 caracteres) são obrigatórios; `descricao` tem até 2000 caracteres e `prazo` é uma data (`AAAA-MM-DD`). `status` inicial é opcional: `NAO_INICIADO`, `AGUARDANDO_ENVIO` (padrão) ou `NAO_APLICAVEL`.

**201**: a **Entrega** criada. **Erros:** 403, 404 (parceria), 422 (`campos`). **Histórico:** `CRIACAO`.

## 16. `GET /api/entregas`  · etapa 5

**Quem chama:** Admin RBS. É a **fila de trabalho** da RBS: as entregas de todas as parcerias.

**Filtros (todos opcionais):** `status` (um ou vários, separados por vírgula: `ENVIADO,EM_ANALISE`), `parceriaId`, `eventoId`, `abaCodigo`, `atrasadas=true` (prazo vencido e entrega ainda pendente), `q` (parte do título), mais `pagina` e `tamanho`.

**Ordem:** primeiro as que esperam análise (`ENVIADO` e `EM_ANALISE`), das que esperam há mais tempo para as mais novas (conta o último envio do patrocinador; sem envio, a última atualização); depois as demais, pelo prazo mais próximo (sem prazo por último).

`atrasada` é verdadeira quando o prazo é anterior a hoje (no horário de Brasília) e a entrega ainda está pendente, isto é, não está `APROVADO` nem `NAO_APLICAVEL`. `ultimoEnvioEm` é a data do último arquivo enviado pelo patrocinador (`null` se ele ainda não enviou). Valor de filtro que não existe (`status`, `abaCodigo`) devolve 422.

**200**: uma página.

```json
{
  "itens": [
    {
      "id": 33,
      "parceria": { "id": 2, "patrocinador": "Cliente Amarelo", "cota": "MASTER" },
      "abaCodigo": "MIDIA", "titulo": "Peça de 30\" para mídia própria",
      "status": "EM_ANALISE", "prazo": null, "atrasada": false,
      "atualizadoEm": "2026-10-20T09:00:00-03:00", "ultimoEnvioEm": "2026-10-19T17:30:00-03:00"
    }
  ],
  "pagina": 0, "tamanho": 50, "total": 1
}
```

## 17. `POST /api/entregas/{id}/arquivos`  · etapa 5

**Quem chama:** patrocinador da parceria (envia o material) ou Admin RBS (envia o retorno). Exige o token CSRF. A entrega de outra parceria devolve **404** para o patrocinador.

**Corpo:** `multipart/form-data`

| Parte | Obrigatória | O que é |
| --- | --- | --- |
| `arquivo` | Sim | O arquivo. Até **20 MB**. |
| `categoria` | Não | Tipo do material. Padrão: `MATERIAL_MARCA` (patrocinador) ou `OUTRO` (RBS). |
| `observacao` | Não | Recado de até 500 caracteres. Fica ligado à versão (campo `observacao` do Arquivo) e também no histórico. |

**[UPLOAD] Regras do arquivo**

- Extensões aceitas: `pdf`, `png`, `jpg`, `jpeg`, `docx`, `xlsx`, `pptx`, `csv`, `mp4`, `mp3`, `zip` (`mp4`, `mp3` e `zip` vieram da reunião com a RBS; a lista completa e o limite seguem em [PENDENTE] no fim deste arquivo). Sem diferenciar maiúsculas de minúsculas; só vale o que vem depois do último ponto.
- O servidor também confere o **conteúdo**, pelos primeiros bytes (a "assinatura" do formato), e não só o nome: `pdf` começa com `%PDF`; `png` e `jpg`/`jpeg` com a assinatura de imagem; `zip`, `docx`, `xlsx` e `pptx` com `PK`; `mp4` tem `ftyp` logo no começo; `mp3` começa com `ID3` ou com um quadro de áudio; `csv` precisa ser texto UTF-8 válido, sem bytes nulos. Planilha que é texto mas não está em UTF-8 (o Excel grava em ANSI/Latin-1 quando não se escolhe "CSV UTF-8") recebe 415 com a instrução de salvar de novo como "CSV UTF-8 (separado por vírgulas)"; arquivo que não parece texto (um PNG renomeado para `.csv`) recebe 415 com a frase de arquivo renomeado. Um executável renomeado para `.pdf` é recusado (415). SVG e executáveis ficam de fora, porque podem carregar script. O tipo (`mimeType`) gravado vem da extensão conferida, nunca do que o navegador informou.
- O nome original perde o caminho da pasta e os caracteres de controle (`C:\fotos\logo.png` vira `logo.png`) e tem no máximo 255 caracteres.
- O servidor guarda o arquivo com um nome que ele mesmo gera. O nome original só aparece na resposta e no download.
- Categorias que o **patrocinador** pode usar: `MATERIAL_MARCA`, `MATERIAL_MIDIA`, `DOCUMENTO_INGRESSOS`, `CONTRATO`, `OUTRO`. As demais (`PLANO_COMERCIAL`, `RELATORIO_VEICULACAO`, `COMPROVACAO`, `PESQUISA`, `RELATORIO_PARCERIA`) são só da RBS.
- Cada envio vira uma **nova versão** da entrega (1, 2, 3...). As versões antigas continuam disponíveis.
- O patrocinador só envia em entrega de aba **liberada**, e só quando a entrega está em `NAO_INICIADO`, `AGUARDANDO_ENVIO`, `AJUSTE_SOLICITADO` ou `ENVIADO`. Depois de `EM_ANALISE`, `APROVADO` ou `NAO_APLICAVEL`, o envio é recusado com 409.
- O envio do patrocinador muda o status da entrega para `ENVIADO`. O retorno da RBS **não** muda o status.

**201**: o **Arquivo** criado (com a `observacao` enviada, ou `null`), mais o status atual da entrega. O cabeçalho `Location` aponta para `/api/arquivos/{id}`.

```json
{
  "id": 12, "entregaId": 31, "nomeOriginal": "logo_final.pdf", "mimeType": "application/pdf",
  "tamanhoBytes": 482113, "versao": 2, "categoria": "MATERIAL_MARCA",
  "enviadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "enviadoEm": "2026-10-17T18:03:00-03:00",
  "observacao": "Logo em vetor, como pedido.",
  "statusDaEntrega": "ENVIADO"
}
```

**Erros**

| Status | Quando |
| --- | --- |
| 403 | Aba fechada para a parceria (as duas frases de "Aba": fora da cota, ou ainda não liberada) ou categoria que o patrocinador não pode usar. |
| 404 | Entrega que não existe ou é de outra parceria. |
| 409 | A entrega não aceita mais envio no status em que está. |
| 413 | Arquivo acima de 20 MB (20.971.520 bytes; exatamente 20 MB ainda passa). |
| 415 | Extensão ou conteúdo fora da lista, ou requisição que não é `multipart/form-data`. |
| 422 | Parte `arquivo` ausente ou vazia, categoria que não existe, ou observação com mais de 500 caracteres (`campos.arquivo`, `campos.categoria`, `campos.observacao`). A frase de `campos.categoria` lista só as categorias que o **perfil de quem enviou** pode usar: para a marca, `"Categoria inválida. Use uma destas: CONTRATO, DOCUMENTO_INGRESSOS, MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO."`; para a RBS, as 10. |

**Histórico:** `ENVIO_ARQUIVO` no primeiro envio e `NOVA_VERSAO` nos seguintes, e `ALTERACAO_STATUS` quando o status muda.

## 18. `GET /api/arquivos/{id}`  · etapa 5

**Quem chama:** patrocinador da parceria dona do arquivo, ou Admin RBS. Arquivo de outra parceria, ou **arquivo geral da RBS** (sem parceria), devolve 404 para o patrocinador.

**200**: os bytes do arquivo, **sempre como anexo** (o navegador baixa, nunca abre na página):

```text
Content-Type: application/pdf
Content-Disposition: attachment; filename="logo_final.pdf"; filename*=UTF-8''logo_final.pdf
X-Content-Type-Options: nosniff
```

O `filename*` leva o nome original em UTF-8 (com acentos e espaços codificados); o `filename` ao lado é a versão só com letras ASCII, para navegadores antigos. O PDF de um relatório que a RBS ainda não disponibilizou também dá 404 ao patrocinador.

**Aba fechada.** O arquivo que pertence a uma aba (o arquivo de uma entrega, por exemplo) segue a mesma regra da lista de entregas: se a aba está fechada para a parceria do patrocinador, o download dá **403** com a mesma frase da lista (`"Esta aba não faz parte da cota da sua parceria."` ou `"Esta aba ainda não foi liberada para a sua parceria."`, veja "Aba"), e nada é gravado no histórico. A ordem é: 404 (arquivo que não existe, de outra parceria, geral da RBS ou relatório não disponibilizado) antes do 403 de aba. Arquivo que não é de nenhuma aba (sem `aba` no banco) não tem essa conferência. A RBS baixa qualquer arquivo, de aba aberta ou fechada.

**Erros:** 403 (aba fechada); 404 (não existe, é de outra parceria, ou o arquivo sumiu do armazenamento). **Histórico:** `DOWNLOAD`, com quem baixou, de que IP e navegador.

## 19. `PATCH /api/entregas/{id}/status`  · etapa 5

**Quem chama:** Admin RBS (exige o token CSRF). Só a RBS muda o status à mão. O patrocinador muda o status só de forma indireta, ao enviar um arquivo (rota 17).

**Corpo**

```json
{ "status": "AJUSTE_SOLICITADO", "observacao": "Enviar o logo em vetor." }
```

`observacao` tem até 500 caracteres e é **obrigatória** ao pedir ajuste e ao reabrir uma entrega aprovada. Ela aparece para o patrocinador em `ultimaObservacao` e no histórico. A API confere primeiro se a mudança é permitida (409) e só depois a observação (422).

`ultimaObservacao` vem do histórico: é o texto da última mudança de status feita pela RBS **que tinha recado**. Uma mudança sem texto (por exemplo, aprovar) e o status que muda sozinho quando o patrocinador envia um arquivo não apagam o recado anterior.

**As mudanças permitidas** (o banco não limita; quem limita é a API):

| De | Para |
| --- | --- |
| `NAO_INICIADO` | `AGUARDANDO_ENVIO`, `NAO_APLICAVEL` |
| `AGUARDANDO_ENVIO` | `NAO_APLICAVEL` |
| `ENVIADO` | `EM_ANALISE`, `AJUSTE_SOLICITADO`, `APROVADO` |
| `EM_ANALISE` | `AJUSTE_SOLICITADO`, `APROVADO` |
| `AJUSTE_SOLICITADO` | `NAO_APLICAVEL` (para voltar a andar, o patrocinador reenvia o arquivo) |
| `APROVADO` | `EM_ANALISE` (reabrir, com observação) |
| `NAO_APLICAVEL` | `NAO_INICIADO` |

**200**: a **Entrega** com o novo status. **Erros:** 403; 404; 409 (`"Esta entrega não pode ir de EM_ANALISE para NAO_INICIADO."`); 422 (status que não existe, ou observação que falta).

**Histórico:** `ALTERACAO_STATUS`, com o status anterior, o novo e a observação em `detalhe`.

## 20. `GET /api/parcerias/{id}/historico`  · etapa 5

**Quem chama:** patrocinador da parceria, ou Admin RBS.

**Filtros (opcionais):** `acao` (uma ou várias, separadas por vírgula), `entidade` (`entrega`, `arquivo`, `parceria`...), `de`, `ate`, `pagina`, `tamanho`. A ordem é da ação mais nova para a mais antiga.

**O que o patrocinador não vê** [PERMISSAO]: além do IP e do navegador, as etapas internas de trabalho da RBS no relatório: `GERACAO_RELATORIO` e `VALIDACAO`. Elas não aparecem nas linhas, nem no `total`, nem num filtro por `acao` (pedir `acao=VALIDACAO` devolve lista vazia). A `DISPONIBILIZACAO`, que é quando o relatório passa a existir para a marca, continua aparecendo. A RBS vê tudo.

**200**: uma página.

```json
{
  "itens": [
    {
      "id": 40, "acao": "ALTERACAO_STATUS", "entidade": "entrega", "entidadeId": 31,
      "valorAnterior": "EM_ANALISE", "valorNovo": "AJUSTE_SOLICITADO", "detalhe": "Enviar o logo em vetor.",
      "usuario": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" },
      "ocorridoEm": "2026-10-16T09:42:00-03:00",
      "ip": null, "userAgent": null
    }
  ],
  "pagina": 0, "tamanho": 50, "total": 1
}
```

`ip` e `userAgent` só vêm preenchidos para a RBS; o patrocinador recebe `null`. As ações possíveis são: `CRIACAO`, `ENVIO_ARQUIVO`, `NOVA_VERSAO`, `ALTERACAO_STATUS`, `EDICAO`, `LIBERACAO_ABA`, `EXCLUSAO`, `GERACAO_RELATORIO`, `VALIDACAO`, `DISPONIBILIZACAO`, `LOGIN`, `LOGIN_FALHOU`, `LOGOUT`, `DOWNLOAD`, `AUTORIZACAO`, `REVOGACAO`, `ATIVACAO`, `CARGA`.

Login, logout e falha de login **não pertencem a nenhuma parceria** (são da conta), então só aparecem no histórico geral (rota 21).

## 21. `GET /api/historico`  · etapa 5

**Quem chama:** Admin RBS. O histórico de todas as parcerias e também dos acessos (login, logout, falha de login).

**Filtros (opcionais):** `parceriaId`, `usuarioId`, `acao`, `entidade`, `de`, `ate`, `q` (texto no detalhe e nos valores), `pagina`, `tamanho`.

**200**: uma página, com o mesmo formato da rota 20 mais o campo `parceria`:

```json
{
  "itens": [
    {
      "id": 16, "parceria": null, "acao": "LOGIN_FALHOU", "entidade": "usuario", "entidadeId": null,
      "valorAnterior": null, "valorNovo": null, "detalhe": "e-mail tentado: amarelo.marketing@amarelo.example",
      "usuario": null, "ocorridoEm": "2026-10-15T14:08:00-03:00",
      "ip": "200.0.2.10", "userAgent": "Mozilla/5.0 (Macintosh; ...)"
    }
  ],
  "pagina": 0, "tamanho": 50, "total": 1
}
```

`parceria` é `{ "id": 2, "patrocinador": "Cliente Amarelo" }` ou `null`. `usuario` é `null` na falha de login com e-mail que não existe.

## 22. `GET /api/historico.csv`  · etapa 5

**Quem chama:** Admin RBS. Os mesmos filtros da rota 21 (sem `pagina` e `tamanho`).

**200**: um arquivo CSV para abrir no Excel:

```text
Content-Type: text/csv; charset=UTF-8
Content-Disposition: attachment; filename="historico-2026-10-23.csv"
```

- Separador **ponto e vírgula** e UTF-8 **com BOM**, para o Excel em português abrir as colunas e os acentos certos.
- Colunas: `id;ocorrido_em;acao;entidade;entidade_id;parceria;usuario;perfil;valor_anterior;valor_novo;detalhe;ip;user_agent`.
- Célula que começa com `=`, `+`, `-` ou `@` recebe um apóstrofo na frente, para o Excel não executar como fórmula.
- Datas saem em ISO 8601 com o fuso de Brasília. **Números decimais saem com vírgula** (`90,9`, e não `90.9`) e sem zeros à direita (`100,00` vira `100`), porque é assim que o Excel em português lê um número; inteiros não mudam. O histórico em si não tem colunas decimais, mas a regra vale para todo CSV da API (por exemplo, o do relatório, rota 36, onde o valor de `ENTREGAS_CONCLUIDAS_PCT` passou a sair como `90,9`).
- Máximo de **50 mil linhas**. Passou disso: 422 pedindo para filtrar por `de` e `ate`.

---

# Pesquisa, cargas e consolidação (etapa 6)

## 23. `GET /api/parcerias/{id}/pesquisa`  · etapa 6

**Quem chama:** patrocinador da parceria, ou Admin RBS. O patrocinador só acessa se a aba `PESQUISA` estiver **liberada** para a parceria dele; senão recebe **403**: `"Esta aba não faz parte da cota da sua parceria."` quando a cota não inclui a Pesquisa (hoje, tudo o que não é Master), ou `"Esta aba ainda não foi liberada para a sua parceria."` quando a cota inclui e a RBS ainda não abriu.

**200**

```json
{
  "parceriaId": 2,
  "abaLiberada": true,
  "limitePerguntas": 3,
  "pesquisa": {
    "id": 2, "nome": "Pesquisa nos espaços de ativação 2027", "escopo": "ATIVACAO",
    "metodologia": "Entrevistas nos espaços da marca (Arena e Camarote)", "baseRespondentes": 412, "nivelConfianca": null
  },
  "perguntas": [
    {
      "id": 7, "ordem": 1, "texto": "Você visitou o espaço da marca na Arena ou no Camarote?",
      "status": "APROVADA", "observacaoRbs": null,
      "enviadaPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
      "enviadaEm": "2026-11-20T16:00:00-03:00"
    }
  ],
  "resultados": [
    { "perguntaId": 7, "ordem": 1, "alternativa": "Sim", "valor": 62.5, "unidade": "PERCENTUAL", "baseRespondentes": 412 }
  ]
}
```

- `status` da pergunta: `RASCUNHO` (ainda não enviada, devolvida para ajuste ou reaberta pela RBS depois de aprovada), `ENVIADA` (espera a RBS) ou `APROVADA`. `observacaoRbs` é o recado da RBS na última decisão sobre a pergunta (pedido de ajuste ou aprovação com observação), enquanto a pergunta continua no status que aquela decisão gerou; quando a marca reenvia, o recado antigo sai e vem `null`.
- `pesquisa` é `null` enquanto a RBS não abriu a pesquisa da ativação daquela edição. A marca já pode enviar as perguntas nesse período; a carga de resultados liga cada uma à pesquisa depois.
- `abaLiberada` é sempre `true` para o patrocinador (senão ele recebe 403). Para a RBS mostra o estado real da aba. Aba só conta como liberada se estiver liberada **e** visível.
- As perguntas vêm em ordem (`ordem` 1 a 3); os resultados, por pergunta e depois na ordem da carga.
- `resultados` só existem para perguntas `APROVADA` e sempre trazem a **base de respondentes**. `unidade`: `PERCENTUAL`, `NOTA` ou `CONTAGEM`.

## 24. `PUT /api/parcerias/{id}/pesquisa/perguntas`  · etapa 6

**Quem chama:** o Admin Patrocinador da parceria (exige o token CSRF). A RBS recebe 403. Só com a aba `PESQUISA` liberada (senão 403, com a frase de cota ou de aba ainda não liberada; veja "Aba").

**Corpo.** A lista **completa** das perguntas que a marca quer (o `PUT` substitui o conjunto):

```json
{
  "perguntas": [
    { "ordem": 1, "texto": "Você visitou o espaço da marca na Arena ou no Camarote?" },
    { "ordem": 2, "texto": "De 0 a 10, quanto você recomendaria a marca a um amigo?" }
  ]
}
```

| Regra | Detalhe |
| --- | --- |
| Quantidade | De 1 a **3** perguntas. |
| `ordem` | 1, 2 ou 3, sem repetir. |
| `texto` | De 10 a 300 caracteres. |
| O que acontece | Pergunta nova ou alterada fica `ENVIADA`, esperando a RBS. Pergunta já `APROVADA` e sem mudança continua como está. Pergunta devolvida (`RASCUNHO`) volta a `ENVIADA` ao ser enviada de novo, mesmo com o mesmo texto. Pergunta `ENVIADA` e sem mudança fica como está. |
| Pergunta aprovada | A marca não pode alterá-la nem tirá-la da lista (409). Para mudar, a marca fala com a RBS, que **reabre** a pergunta pedindo ajuste (rota 25); ela volta a `RASCUNHO` e a marca passa a poder editá-la. |
| Pergunta reaberta com resultados | Uma pergunta que já teve resultados carregados não pode ser **retirada** da lista (409: `"A pergunta 3 já tem resultados carregados e não pode ser retirada da lista. Altere o texto dela ou fale com a RBS."`), porque apagá-la apagaria os resultados. Alterar o texto dela é permitido. |
| Pergunta fora da lista | Pergunta que ainda não foi aprovada e não vem na lista é retirada. |
| Tudo ou nada | Se qualquer pergunta recusar, nada é gravado. Lista igual à que já está salva não muda nada e não grava histórico. |

**200**: o mesmo objeto da rota 23.

**Erros:** 403; 404; 409 (`"A pergunta 2 já foi aprovada pela RBS e não pode mais ser alterada."`, com o número da pergunta aprovada que foi alterada ou retirada); 422 (mais de 3, ordem repetida, texto curto ou longo, em `campos`). As chaves de `campos` seguem o corpo: `perguntas` (quantidade, "Envie de 1 a 3 perguntas."), `perguntas[0].texto`, `perguntas[0].ordem` e, para ordem repetida, o segundo item repetido (`perguntas[1].ordem`). Corpo que não é JSON: 400. A RBS recebe 403 antes de qualquer outra conferência (inclusive com parceria que não existe). A ordem é: 403 (RBS) → 404 (parceria que não é do usuário ou não existe) → 403 (aba `PESQUISA` não liberada) → só então o corpo é lido e validado (400 e 422). Ou seja, numa parceria sem a aba liberada o patrocinador recebe 403 com qualquer corpo, vazio ou inválido.

**Histórico:** `EDICAO` (entidade `pesquisa_pergunta`), uma linha por pergunta que mudou (enviada, alterada, devolvida e reenviada, ou retirada), com o texto anterior e o novo.

## 25. `PATCH /api/pesquisa/perguntas/{id}/status`  · etapa 6

**Quem chama:** Admin RBS (exige o token CSRF). A RBS decide sobre uma pergunta `ENVIADA` (aprova ou devolve) e também pode **reabrir** uma pergunta `APROVADA` pedindo ajuste.

**Corpo**

```json
{ "decisao": "PEDIR_AJUSTE", "observacao": "Troque \"bom\" por uma escala de 0 a 10." }
```

| `decisao` | Vale para | Efeito |
| --- | --- | --- |
| `APROVAR` | pergunta `ENVIADA` | A pergunta fica `APROVADA`. Só a partir daí a carga de resultados aceita linhas dela. `observacao` é opcional. |
| `PEDIR_AJUSTE` | pergunta `ENVIADA` ou `APROVADA` | A pergunta vai (ou volta) a `RASCUNHO` e a `observacao` (obrigatória) aparece para a marca em `observacaoRbs`. Em uma pergunta `ENVIADA` é a devolução para ajuste; em uma `APROVADA` é a **reabertura** (abaixo). |

**200**: a pergunta (mesmo formato dos itens de `perguntas` da rota 23). **Erros:** 403; 404; 409 (a decisão não cabe no status da pergunta: aprovar só a `ENVIADA`; pedir ajuste só a `ENVIADA` ou a `APROVADA`); 422 (decisão inválida em `campos.decisao`, observação que falta em `campos.observacao`, ou observação com mais de 1000 caracteres).

O patrocinador recebe 403 nesta rota mesmo para a pergunta da própria parceria e mesmo para um id que não existe: a permissão é conferida antes de procurar a pergunta (e antes de ler o corpo).

**Reabrir uma pergunta aprovada.** A marca não altera nem retira uma pergunta aprovada; o caminho para mudá-la é a RBS reabri-la. `PEDIR_AJUSTE` numa pergunta `APROVADA`, com `observacao` obrigatória (sem ela, 422 e nada muda), devolve a pergunta ao estado em que a marca pode editar:

```json
{
  "id": 7, "ordem": 1, "texto": "Você visitou o espaço da marca na Arena ou no Camarote?",
  "status": "RASCUNHO", "observacaoRbs": "Troque \"bom\" por uma escala de 0 a 10.",
  "enviadaPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "enviadaEm": "2026-11-20T16:00:00-03:00"
}
```

É o mesmo objeto da rota 23, com `status` `RASCUNHO` e o recado da RBS em `observacaoRbs`. A marca vê o recado em `GET /api/parcerias/{id}/pesquisa`, edita o texto (ou só reenvia) com o `PUT` da rota 24 e a pergunta volta a `ENVIADA`, esperando a RBS aprovar de novo. Enquanto não estiver `APROVADA`, os `resultados` dela **deixam de aparecer** na rota 23 e a carga de resultados recusa linhas dela; os resultados já carregados ficam guardados e voltam a aparecer quando a pergunta for aprovada de novo. Se o texto mudou, esses resultados eram da versão anterior da pergunta: a RBS deve conferir (e recarregar) antes de aprovar.

**Histórico:** `ALTERACAO_STATUS` (entidade `pesquisa_pergunta`, status anterior e novo; na reabertura, `APROVADA` para `RASCUNHO`), com a observação em `detalhe`. Qualquer Admin RBS pode decidir (regra provisória: a RBS ainda vai confirmar se é só o time de pesquisa).

## 26. `POST /api/cargas`  · etapa 6

**Quem chama:** Admin RBS (exige o token CSRF). Envia uma planilha CSV de **veiculação** (mídia exibida), de **pesquisa** (resultados agregados) ou do **plano** comercial (o que a cota comprou: o previsto de mídia e de aplicações de marca). Os formatos das colunas estão em [`docs/banco.md`](banco.md#formatos-dos-csv), e há exemplos em `docs/exemplos/`.

**Corpo:** `multipart/form-data`

| Parte | Obrigatória | O que é |
| --- | --- | --- |
| `arquivo` | Sim | O CSV, com extensão `.csv` (UTF-8, vírgula como separador, cabeçalho na primeira linha; o BOM do Excel no começo é aceito). Até 20 MB. |
| `tipo` | Sim | `VEICULACAO`, `PESQUISA` ou `PLANO`. |
| `eventoId` | Sim | A edição a que a planilha se refere. |
| `escopo` | Só em `PESQUISA` | `ATIVACAO` (padrão) ou `GERAL` (pesquisa do festival, sem patrocinador). Em `VEICULACAO` e em `PLANO` a parte é ignorada. |

**Como funciona** [CARGA] [DUPLICIDADE]

1. A API confere o cabeçalho e guarda o arquivo como arquivo geral da RBS (sem parceria; categoria `RELATORIO_VEICULACAO`, `PESQUISA` ou `PLANO_COMERCIAL`). O cabeçalho precisa ter **todas** as colunas do tipo, em qualquer ordem e em maiúsculas ou minúsculas; coluna a mais é ignorada. Veiculação: `patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link`. Pesquisa: `patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes`. Plano: `patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina`. Célula vazia vale como nulo.
2. Grava as linhas numa área de passagem e chama a função do banco, que rejeita o que não tem campo obrigatório ou não bate com nenhuma parceria, e **insere sem duplicar**. Data ou número que não dá para ler (por exemplo `exibido_em` = "ontem", ou decimal com vírgula) não derruba o arquivo: a linha é rejeitada, com o campo e o **motivo específico**, que diz o formato esperado (veja a rota 28). Data sem fuso vale no horário de Brasília. Tudo corre numa transação: se algo falhar, não ficam a carga, as linhas nem o arquivo.
3. A chave da veiculação é a **linha do plano comercial + data e hora**. Reenviar o mesmo arquivo não insere nada: tudo conta como duplicada. Reenviar com quantidade corrigida também conta como duplicada (a primeira versão prevalece).
4. Na pesquisa da ativação, só pergunta `APROVADA` recebe resultado; a linha de pergunta ainda não aprovada é rejeitada.
5. **No plano**, cada linha vira uma linha de `aproveitamento_previsto` da parceria da marca naquela edição (a mesma carga serve ao previsto de mídia e ao de aplicações de marca). A chave é **parceria + veículo + formato + espaço do programa** (categoria, unidade e janela ficam fora): linha repetida no arquivo, ou que já está no plano, conta como duplicada e a **primeira versão prevalece**. Reenviar uma linha com a quantidade corrigida **não** corrige o plano (editar uma linha fica fora desta etapa). `categoria`, `tipo_midia` e `unidade` valem em maiúsculas ou minúsculas. Os textos são gravados sem espaço nas pontas, e `fonte_pagina` fora de 1 a 32767 (ou que não é número) vira nula sem rejeitar a linha. Aplicação de marca não leva tipo de mídia: se vier um tipo válido, ele é ignorado. Os motivos de rejeição estão na rota 28.

**201**

```json
{
  "id": 3, "tipo": "VEICULACAO", "eventoId": 2, "arquivoId": 44,
  "executadaPor": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" },
  "executadaEm": "2026-11-21T09:00:00-03:00", "processadaEm": "2026-11-21T09:00:02-03:00",
  "linhasLidas": 1489, "linhasInseridas": 1483, "linhasDuplicadas": 2, "linhasRejeitadas": 4
}
```

Linhas lidas = inseridas + duplicadas + rejeitadas. As rejeitadas, com o motivo, estão na rota 28.

`linha`, nas rejeições, é o número da linha no arquivo (o cabeçalho é a linha 1; linha em branco conta no número, mas não é lida).

**Erros:** 403 (conferido antes de qualquer outra coisa); 413 (acima de 20 MB); 415 (não é CSV: extensão diferente de `.csv`, texto que não está em UTF-8, com a instrução de salvar como CSV UTF-8, ou conteúdo que não parece texto, com a frase de arquivo renomeado); 422 com `campos`: parte ausente (`campos.arquivo` = `Envie a planilha CSV na parte "arquivo" do formulário.`), planilha de 0 bytes (`campos.arquivo` = `A planilha está vazia.`), parte inválida (`tipo`, `eventoId`, `escopo`), evento que não existe (`campos.eventoId`), edição sem a pesquisa pedida cadastrada (`campos.escopo`), e problemas do arquivo em `campos.arquivo`: cabeçalho com coluna faltando (lista as que faltam), texto maior que a coluna do banco (diz a linha e a coluna), CSV mal formado (aspas abertas) e planilha sem nenhuma linha de dados. Nesses casos nada é gravado.

Texto maior que a coluna derruba o arquivo inteiro (422), como nas outras cargas. No plano, os limites são: `patrocinador` 120, `categoria` 20, `bloco` 80, `veiculo` 80, `formato` 160, `espaco_programa` 160, `janela` 80, `tipo_midia` 20, `unidade` 15.

**Histórico:** `CARGA`. No banco o `tipo` `VEICULACAO` é gravado como `MIDIA` e `PLANO` como `PLANO_COMERCIAL`; a resposta e a lista (rota 27) usam sempre `VEICULACAO`, `PESQUISA` e `PLANO`.

## 27. `GET /api/cargas`  · etapa 6

**Quem chama:** Admin RBS. **Filtros (opcionais):** `tipo` (`VEICULACAO`, `PESQUISA` ou `PLANO`; outro valor dá 422 com `Tipo inválido. Use VEICULACAO, PESQUISA ou PLANO.`), `eventoId`, `pagina`, `tamanho`. A ordem é da carga mais nova para a mais antiga (pelo id da carga).

**200**: uma página de cargas, cada item no formato da resposta da rota 26.

## 28. `GET /api/cargas/{id}/rejeicoes`  · etapa 6

**Quem chama:** Admin RBS. **Filtros (opcionais):** `pagina`, `tamanho`. A ordem é pela linha do arquivo.

**200**: uma página.

```json
{
  "itens": [
    { "linha": 12, "campo": "exibido_em", "motivo": "Campo obrigatório vazio ou inválido", "conteudo": "Cliente Verde | No evento | VT telão 30\" | Telões (1 por dia)" },
    { "linha": 1301, "campo": "patrocinador", "motivo": "Patrocinador sem parceria nesta edição", "conteudo": "Cliente Azul | Rede Atlântida RS | Chamadas assinadas 5\" | Indeterminado | 01/12/2026 10:00" }
  ],
  "pagina": 0, "tamanho": 50, "total": 2
}
```

`linha` é o número da linha no arquivo enviado. `conteudo` é a linha reescrita pelo banco, com os campos separados por ` | ` (a data em horário de Brasília; o que não deu para ler aparece como foi escrito na planilha).

**Os motivos** de uma linha que não entrou. Cada linha tem um só (o primeiro problema encontrado):

| Campo | Motivo |
| --- | --- |
| `patrocinador`, `veiculo`, `formato` (veiculação) | `Campo obrigatório vazio ou inválido` |
| `exibido_em` vazio | `Campo obrigatório vazio ou inválido` |
| `exibido_em` ilegível (`31/02/2026`, `2026-13-45`, `amanhã`, só a data) | `Data inválida. Use o formato 2026-11-20T10:15:30-03:00 (ano-mês-dia, hora e fuso).` |
| `quantidade` vazia | `Campo obrigatório vazio ou inválido` |
| `quantidade` ilegível (`1x`, `12a`, `1,5`) | `Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).` |
| `quantidade` zero ou negativa | `A quantidade deve ser maior que zero.` |
| `patrocinador` sem parceria na edição | `Patrocinador sem parceria nesta edição` |
| linha que não está no plano comercial | `Aproveitamento não encontrado no plano comercial` |
| `valor` ilegível (pesquisa) | `Valor inválido. Use só números, com ponto nos decimais, como 8.4 ou 64.` |
| `unidade` fora da lista (pesquisa) | `Unidade inválida. Use PERCENTUAL, NOTA ou CONTAGEM.` |
| `base_respondentes` ilegível (pesquisa) | `Base de respondentes inválida. Use só números inteiros, como 412.` |
| `base_respondentes` zero ou negativa | `A base de respondentes deve ser maior que zero.` |
| `pergunta` ilegível ou fora de 1 a 3 (pesquisa da ativação) | `Pergunta inválida. Use o número da pergunta: 1, 2 ou 3.` / `Pergunta fora do intervalo de 1 a 3` |
| percentual fora de 0 a 100 | `Percentual fora do intervalo de 0 a 100` |
| pergunta sem cadastro ou ainda não aprovada | `Pergunta não cadastrada para esta parceria nesta pesquisa` / `Pergunta ainda não aprovada pela RBS` |

**Os motivos do plano** (carga `PLANO`). Também um só por linha, na ordem desta tabela (vale o primeiro problema). O `campo` é o nome da coluna do CSV.

| Campo | Motivo |
| --- | --- |
| `patrocinador`, `categoria`, `veiculo` ou `formato` vazio; `quantidade_prevista` ou `unidade` vazia | `Campo obrigatório vazio ou inválido` (o `veiculo` é obrigatório também nas aplicações de marca: use `No evento`) |
| `quantidade_prevista` ilegível (`1x`, `1,5`) | `Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).` |
| `quantidade_prevista` zero ou negativa | `A quantidade deve ser maior que zero.` |
| `categoria` fora da lista | `Categoria inválida. Use MIDIA ou APLICACAO_MARCA.` |
| `unidade` fora da lista | `Unidade inválida. Use INSERCAO, CPM ou APLICACAO.` |
| `unidade` diferente de `APLICACAO` numa aplicação de marca | `Aplicação de marca usa a unidade APLICACAO.` |
| `tipo_midia` vazio numa mídia, ou preenchido e fora da lista (em qualquer categoria) | `Tipo de mídia inválido. Use ASSINADA, PROPRIA, MERCHANDISING, BRANDED ou CONTEUDO_DE_MARCA.` |
| `patrocinador` sem parceria na edição | `Patrocinador sem parceria nesta edição` |

**Erros:** 403 (também para carga que não existe: a permissão é conferida primeiro); 404 (carga que não existe, para a RBS). Vale para as cargas de veiculação, de pesquisa e de plano.

## 29. `GET /api/parcerias/{id}/consolidacao`  · etapa 6

**Quem chama:** patrocinador da parceria, ou Admin RBS. Reúne o **previsto x exibido** e o que falta para o relatório ficar completo. Unidades de mídia (`INSERCAO`, `CPM`, `APLICACAO`) **nunca são somadas entre si**.

**200**

```json
{
  "parceriaId": 2,
  "midia": {
    "porUnidade": [
      { "unidade": "INSERCAO", "previsto": 1200.00, "exibido": 1180.00, "percentual": 98.3 },
      { "unidade": "CPM", "previsto": 5000.00, "exibido": 5200.00, "percentual": 104.0 }
    ],
    "porVeiculo": [
      { "veiculo": "Rede Atlântida RS", "unidade": "INSERCAO", "previsto": 600.00, "exibido": 590.00, "percentual": 98.3 }
    ],
    "linhas": [
      { "aproveitamentoId": 81, "veiculo": "Rede Atlântida RS", "formato": "Citação 5\"", "espacoPrograma": "Programa da Manhã",
        "unidade": "INSERCAO", "previsto": 120.00, "exibido": 118.00, "percentual": 98.3 }
    ]
  },
  "aplicacoes": { "previstas": 12.00, "comprovadas": 10.00, "fotos": 14, "percentual": 83.3 },
  "entregas": { "total": 11, "aprovadas": 10, "pendentes": 1, "naoAplicaveis": 0, "percentualConcluido": 90.9 },
  "cortesias": { "disponiveis": 120, "destinadas": 80, "percentual": 66.7 },
  "faltantes": [
    { "tipo": "ENTREGA_PENDENTE", "detalhe": "Peça de 30\" para mídia própria (em analise)" }
  ]
}
```

- `percentual` é exibido ÷ previsto × 100 e **pode passar de 100** quando o exibido supera o previsto (no exemplo, o CPM: 5.200 de 5.000 é `104.0`; 907.300 de 460.000 é `197.2`). Todo percentual desta rota (mídia, aplicações, entregas, cortesias) é **cortado** na primeira casa decimal, não arredondado: 459.900 de 460.000 vale `99.9`, porque `100` diria "completo" quando faltam exibições, e 17 de 19 aplicações vale `89.4`. Seções sem plano comercial (`midia` sem linhas, por exemplo) vêm com listas vazias e `null` nos totais: `aplicacoes`, `entregas` e `cortesias` continuam como objeto, com todos os campos `null` quando a parceria não tem dado naquela seção.
- **Aba fechada para o patrocinador.** A parte de cada aba só sai se a aba estiver aberta (liberada **e** visível) para a parceria dele. Com a aba `MIDIA` fechada, `midia` vem com as três listas **vazias** (`"porUnidade": []`, `"porVeiculo": []`, `"linhas": []`), a mesma forma de "sem plano comercial": o campo `midia` continua no corpo (nunca `null` nem omitido), mas não sai nenhuma linha de previsto nem de exibido. Com a aba `APLICACOES` fechada, `aplicacoes` vem com todos os campos `null`; com `CORTESIAS` fechada, `cortesias` vem com todos os campos `null`. `entregas` não pertence a uma aba só: o resumo do patrocinador conta só as entregas de aba aberta (as mesmas da rota 14) e, sem nenhuma, vem com todos os campos `null`. `faltantes` segue como está. A resposta é 200 (a rota não nega a consolidação inteira). A RBS recebe tudo, de aba aberta ou fechada.
- `porUnidade` e `porVeiculo` vêm em ordem de unidade (`INSERCAO`, `CPM`, `APLICACAO`); `porVeiculo` e `linhas`, por veículo e formato. Linha do plano ainda sem exibição traz `exibido` 0 e `percentual` 0, nunca `null`.
- `faltantes` é o que impede ou enfraquece o relatório. Tipos: `MIDIA_SEM_EXIBICAO`, `MIDIA_ABAIXO_DO_PREVISTO`, `APLICACAO_SEM_COMPROVACAO`, `PESQUISA_SEM_PERGUNTAS`, `PERGUNTA_SEM_RESULTADO`, `ENTREGA_PENDENTE`, `PARCERIA_SEM_USUARIO`, `FESTIVAL_SEM_NUMEROS`.
- Os `faltantes` vêm do mais para o menos importante, na ordem dos tipos listada acima. [PENDENTE] A RBS ainda não definiu uma ordem de importância; vale a ordem desta lista.
- **Filtrado para o patrocinador:** ele vê só os faltantes que **ele** pode resolver (`ENTREGA_PENDENTE` e `PESQUISA_SEM_PERGUNTAS`). Os demais são de trabalho interno da RBS e só ela recebe. [PENDENTE] A RBS ainda vai confirmar se a marca vê o previsto x exibido antes de o relatório ser validado.

**Erros:** 404 (parceria que não existe ou é de outra).

## 30. `GET /api/painel`  · etapa 6

**Quem chama:** Admin RBS. **Filtro (opcional):** `eventoId` (padrão: a edição mais nova).

**200**

```json
{
  "evento": { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 },
  "parcerias": { "total": 3, "ativas": 3, "semUsuario": 0 },
  "entregas": {
    "porStatus": { "NAO_INICIADO": 0, "AGUARDANDO_ENVIO": 0, "ENVIADO": 1, "EM_ANALISE": 1, "AJUSTE_SOLICITADO": 1, "APROVADO": 26, "NAO_APLICAVEL": 1 },
    "esperandoAnalise": 2, "atrasadas": 0
  },
  "relatorios": { "semRelatorio": 0, "rascunho": 3, "validado": 0, "disponibilizado": 0, "marcaVe": 0 },
  "ultimaCarga": { "id": 3, "tipo": "VEICULACAO", "executadaEm": "2026-11-21T09:00:00-03:00", "linhasInseridas": 1483, "linhasRejeitadas": 4 },
  "pendencias": [
    { "parceriaId": 2, "patrocinador": "Cliente Amarelo", "tipo": "ENTREGA_PENDENTE", "detalhe": "Peça de 30\" para mídia própria (em analise)" }
  ]
}
```

`pendencias` são os faltantes (rota 29) de todas as parcerias da edição, limitados aos 20 mais importantes (mesma ordem de tipos da rota 29; dentro do tipo, por patrocinador). `ultimaCarga` é `null` se ainda não houve nenhuma.

Como cada número é contado:

- `parcerias.ativas`: parcerias com situação `ATIVA`; `semUsuario`: parcerias sem nenhum vínculo de usuário (o faltante `PARCERIA_SEM_USUARIO`).
- `entregas.porStatus` traz sempre os 7 status, com 0 nos que não têm entrega. `esperandoAnalise` = `ENVIADO` + `EM_ANALISE` (a bola está com a RBS).
- `entregas.atrasadas`: prazo já vencido (data de Brasília; vencer hoje ainda não é atraso) e entrega ainda com a marca: `NAO_INICIADO`, `AGUARDANDO_ENVIO` ou `AJUSTE_SOLICITADO`. Entrega já enviada não conta como atraso da marca. [PENDENTE] A RBS pode preferir outra definição.
- `relatorios` conta a situação da **versão mais nova** de cada parceria da edição; `semRelatorio` é quem não tem versão. `marcaVe` é outra conta: quantas parcerias da edição têm **pelo menos uma versão disponibilizada**, mesmo que a mais nova seja rascunho ou validada (a marca continua vendo a disponibilizada anterior). Uma parceria com a v2 disponibilizada e a v3 em rascunho conta em `rascunho` e em `marcaVe`. O card "Relatórios disponibilizados" do Painel deve mostrar `marcaVe`.
- `ultimaCarga` é a carga mais nova **da edição, de veiculação ou de pesquisa**; o `tipo` usa o nome da API (`VEICULACAO`, e não `MIDIA` do banco). A carga do plano comercial (`PLANO`) não entra nessa conta: ela enche o previsto e não ocupa o card.
- `eventoId` que não existe: 404; que não é número: 400. O patrocinador recebe 403 antes de qualquer conferência.

---

# Relatórios (etapa 7)

O relatório de uma parceria é uma **fotografia** dos números do momento em que foi gerado. Cada geração cria uma versão nova (1, 2, 3...) em `RASCUNHO`. O status só avança **`RASCUNHO` → `VALIDADO` → `DISPONIBILIZADO`**, nessa ordem, sem pular nem voltar, e depois de validado os números ficam congelados. O patrocinador só enxerga a versão **disponibilizada**. Um número sem dado na base fica `disponivel: false` e sem valor: **nunca é estimado**.

## 31. `GET /api/relatorios`  · etapa 7

**Quem chama:** Admin RBS. A situação do relatório de **cada parceria** de uma edição.

**Filtros (opcionais):** `eventoId` (padrão: a edição mais nova), `status` (`SEM_RELATORIO`, `RASCUNHO`, `VALIDADO`, `DISPONIBILIZADO`: a situação da versão mais nova).

**200**

```json
[
  {
    "parceriaId": 2,
    "patrocinador": "Cliente Amarelo",
    "cota": { "codigo": "MASTER", "nome": "Master" },
    "evento": { "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 },
    "situacao": "RASCUNHO",
    "relatorio": {
      "id": 1, "versao": 1, "status": "RASCUNHO", "geradoEm": "2026-11-22T10:00:00-03:00",
      "validadoEm": null, "disponibilizadoEm": null,
      "indicadoresDisponiveis": 35, "indicadoresIndisponiveis": 2
    },
    "disponibilizada": { "id": 1, "versao": 1, "disponibilizadoEm": "2026-11-23T11:00:00-03:00" },
    "faltantes": 4
  }
]
```

`relatorio` é a **versão mais nova** (ou `null`, com `situacao: "SEM_RELATORIO"`). `indicadoresDisponiveis` e `indicadoresIndisponiveis` contam linhas de números do relatório (um indicador por recorte conta uma vez por recorte); o indicador do catálogo sem linha na versão conta como indisponível, como na leitura (rota 35). `faltantes` é quantos itens a consolidação (rota 29) ainda aponta para a parceria.

`disponibilizada` é a **versão que a marca vê**: a disponibilizada mais nova, com o `id`, a `versao` e quando ela foi liberada (`disponibilizadoEm`). Fica `null` se nenhuma versão foi disponibilizada. Ela pode ser mais antiga que `relatorio`: com a v3 em rascunho e a v2 disponibilizada, a linha traz `relatorio.versao` 3 (`situacao` `RASCUNHO`) e `disponibilizada.versao` 2, e a tela da RBS deve mostrar as duas ("Rascunho · v3, a marca vê a v2"). Quando a versão mais nova é a própria disponibilizada, `disponibilizada` repete o mesmo `id` e `versao`.

## 32. `GET /api/relatorios.csv`  · etapa 7

**Quem chama:** Admin RBS. Os mesmos filtros e as mesmas linhas da rota 31, em CSV (mesmo formato de arquivo da rota 22: ponto e vírgula, UTF-8 com BOM, `Content-Disposition: attachment; filename="relatorios-2026-11-22.csv"`).

Colunas: `parceria_id;patrocinador;cota;edicao;situacao;versao;gerado_em;validado_em;disponibilizado_em;indicadores_disponiveis;indicadores_indisponiveis;faltantes;marca_ve_versao;marca_ve_disponibilizado_em`. As duas últimas são a versão que a marca vê e quando foi liberada (`disponibilizada` da rota 31), em branco se nenhuma foi disponibilizada.

## 33. `POST /api/parcerias/{id}/relatorios`  · etapa 7

**Quem chama:** Admin RBS (exige o token CSRF). **Corpo:** nenhum.

Gera uma **nova versão** do relatório da parceria, em `RASCUNHO`, copiando os números da base (a função `fn_gerar_relatorio` do banco). Duas gerações ao mesmo tempo ganham versões diferentes. O relatório sai sempre com as 6 seções e os 17 indicadores do modelo: o que não tem dado é gravado como indisponível, com o motivo em `baseCalculo` (veja a rota 35).

**201** (com `Location: /api/relatorios/{id}`): o relatório completo (formato da rota 35).

**Erros:** 403; 404 (parceria). **Histórico:** `GERACAO_RELATORIO` (`"versão 2 (RASCUNHO)"`).

## 34. `PATCH /api/relatorios/{id}/status`  · etapa 7

**Quem chama:** Admin RBS (exige o token CSRF).

**Corpo:** `{ "status": "VALIDADO" }` ou `{ "status": "DISPONIBILIZADO" }`

- `VALIDADO`: a RBS conferiu os números. Grava quem validou e quando; os números ficam congelados.
- `DISPONIBILIZADO`: libera o relatório para o patrocinador. Grava quem liberou e quando. Só vale a partir de `VALIDADO`.
- Pedir o status que o relatório já tem devolve **200** sem alterar nada e sem gravar histórico.
- `RASCUNHO` passa na validação: em rascunho, é o caso acima (200 sem efeito); em relatório já validado, dá 409 pela ordem de status.

**200**: o relatório (formato da rota 35).

**Erros:** 403; 404; 409 (`"O relatório não pode ir de RASCUNHO para DISPONIBILIZADO. A ordem é RASCUNHO, VALIDADO, DISPONIBILIZADO."`, a frase do banco); 422 (status que não existe).

**Histórico:** `VALIDACAO` ou `DISPONIBILIZACAO`.

## 35. `GET /api/parcerias/{id}/relatorio`  · etapa 7

**Quem chama:** patrocinador da parceria, ou Admin RBS.

- **Patrocinador:** recebe a versão **disponibilizada** mais nova. Se a RBS ainda não disponibilizou nenhuma, **404** (`"O relatório desta parceria ainda não está disponível."`).
- **RBS:** recebe a versão mais nova de qualquer status, ou a pedida em `?versao=2` (404 se a versão não existe; 422 se for menor que 1).
- `?versao=` não vale para o patrocinador: ele sempre recebe a disponibilizada mais nova.

**200**

```json
{
  "id": 1, "parceriaId": 2, "versao": 1, "status": "DISPONIBILIZADO",
  "geradoEm": "2026-11-22T10:00:00-03:00", "geradoPor": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" },
  "validadoEm": "2026-11-23T09:00:00-03:00", "disponibilizadoEm": "2026-11-23T11:00:00-03:00",
  "secoes": [
    {
      "secao": "Cumprimento das entregas",
      "indicadores": [
        {
          "codigo": "ENTREGAS_CONCLUIDAS_PCT", "nome": "Entregas concluídas", "recorte": "",
          "valor": 90.9, "unidade": "%", "baseCalculo": "10 aprovadas de 11 aplicáveis", "disponivel": true
        }
      ]
    },
    {
      "secao": "O festival em números",
      "indicadores": [
        {
          "codigo": "FESTIVAL_PESQUISA", "nome": "Pesquisa geral do festival", "recorte": "",
          "valor": null, "unidade": "%", "baseCalculo": "sem registro na base", "disponivel": false
        }
      ]
    }
  ]
}
```

- **O relatório sempre traz as 6 seções do modelo, na ordem do modelo, e os 17 indicadores do catálogo**, mesmo sem nenhum dado (tela, JSON e CSV). A ordem é esta, e o exemplo acima mostra só duas seções para ficar curto:

  | Seção | Indicadores (`codigo`) |
  | --- | --- |
  | Cumprimento das entregas | `ENTREGAS_CONCLUIDAS_PCT`, `ENTREGAS_PENDENTES` |
  | Mídia: previsto x exibido | `MIDIA_PREVISTO`, `MIDIA_EXIBIDO`, `MIDIA_ENTREGA_PCT`, `MIDIA_VEICULO_PCT` |
  | Ativação no evento | `APLICACOES_PREVISTAS`, `APLICACOES_COMPROVADAS`, `APLICACOES_COMPROVADAS_PCT`, `COMPROVACOES_ENVIADAS` |
  | Pesquisa da ativação | `PESQUISA_BASE`, `PESQUISA_RESULTADO` |
  | Cortesias e credenciamento | `CORTESIAS_DISPONIVEIS`, `CORTESIAS_DESTINADAS_PCT`, `CREDENCIAIS_DIA` |
  | O festival em números | `FESTIVAL_NUMERO`, `FESTIVAL_PESQUISA` |

- Indicador do catálogo **sem dado** sai como `disponivel: false`, `valor: null` e `recorte: ""`, uma linha só, nunca zero e nunca estimado. Isso vale para a parceria sem plano comercial (cadastrada ao vivo), para a cota que não inclui a Pesquisa (a seção "Pesquisa da ativação" vem com os dois indicadores indisponíveis) e para o que a base ainda não tem. Quando há dado por recorte (por unidade, veículo, pergunta...), vêm as linhas dos recortes com dado e nenhuma linha "indisponível" ao lado; sem nenhum recorte, vem a linha única sem recorte. O `baseCalculo` da linha indisponível diz o motivo: `"sem mídia no plano comercial"`, `"sem aplicações de marca no plano comercial"`, `"não se aplica a esta cota"` ou `"sem registro na base"`.
- A mesma regra vale na **leitura**: a versão gerada antes desta regra (que não tinha as linhas dos indicadores sem dado) também sai completa, com `"sem registro na base"` no que faltava; a fotografia gravada não é alterada. Os contadores `indicadoresDisponiveis` e `indicadoresIndisponiveis` da rota 31 contam o que falta como indisponível, igual ao que a leitura mostra.
- `recorte` separa um indicador em vários números (por unidade, por veículo, por pergunta). Vem `""` quando é um número só.
- `baseCalculo` é o texto "como foi calculado", com os números no padrão brasileiro: milhar com ponto e decimal com vírgula (`"459.900 de 460.000"`, `"1.105 de 1.113"`). Todo percentual do relatório (entregas concluídas, mídia por unidade e por veículo, aplicações comprovadas, cortesias destinadas) é cortado na primeira casa: 459.900 de 460.000 sai `99.9` e só chega a `100` quando está completo; 17 de 19 sai `89.4`. O percentual de entrega de mídia (`MIDIA_ENTREGA_PCT`, `MIDIA_VEICULO_PCT`) **passa de 100** quando o exibido supera o previsto (`"907.300 de 460.000"` vale `197.2`). Os relatórios já gerados antes desta regra mantêm os números que tinham (cada versão é uma fotografia); a regra vale para as versões novas.
- `valor` é `null` quando `disponivel` é `false`; o front mostra "indisponível", nunca um zero.
- Para o patrocinador, `geradoPor` vem `null` (dado interno).

**Erros:** 404 (parceria de outra, ou sem relatório disponível para o patrocinador).

## 36. `GET /api/relatorios/{id}/csv`  · etapa 7

**Quem chama:** Admin RBS (qualquer versão) ou patrocinador da parceria (só se o relatório estiver `DISPONIBILIZADO`; senão **404**).

**200**: CSV do relatório, um número por linha, no mesmo formato de arquivo da rota 22 (`filename="relatorio-cliente-amarelo-v1.csv"`: o nome do patrocinador, sem acento e com hífens, e a versão).

Colunas: `secao;indicador_codigo;indicador;recorte;valor;unidade;base_calculo;disponivel`. Número indisponível sai com `valor` em branco e `disponivel` igual a `nao`. O CSV traz as mesmas linhas da rota 35: as 6 seções, na ordem do modelo, com os 17 indicadores (um relatório sem nenhum dado tem 17 linhas, todas `nao`). Percentual acima de 100 sai como número (`197,2`), sem corte.

**Histórico:** `DOWNLOAD` (entidade `relatorio`).

---

# Aplicações de marca (Atlas, parte 1)

A aba **Aplicações** deixa de ser só uma lista de entregas: ela mostra o plano de aplicações de marca no evento (o que a cota comprou, vindo da carga `PLANO`, rota 26) com as fotos de comprovação de cada linha. O plano e as fotos têm os mesmos números da consolidação (rota 29, campo `aplicacoes`): uma linha conta como comprovada até o limite do previsto, e o percentual é cortado na primeira casa.

## 37. `GET /api/parcerias/{id}/aplicacoes`  · Atlas 1

**Quem chama:** patrocinador da parceria, ou Admin RBS. A marca de outra parceria recebe **404**; a marca só lê com a aba `APLICACOES` aberta (**403** com as duas frases de "Aba" quando a aba está fechada ou fora da cota); a RBS lê sempre.

**200**

```json
{
  "parceriaId": 2,
  "totais": { "previstas": 19.00, "comprovadas": 17.00, "fotos": 17, "percentual": 89.4 },
  "linhas": [
    {
      "id": 31, "bloco": "Aplicações de marca", "veiculo": "No evento", "formato": "Elemento na Arena",
      "espacoPrograma": null, "janela": null, "previsto": 1.00, "comprovadas": 1.00, "fotos": 2,
      "comprovacoes": [
        {
          "id": 5, "arquivoId": 77, "nomeOriginal": "arena.jpg", "descricao": "Testeira no palco",
          "registradoPor": { "id": 1, "nome": "Equipe B2B", "perfil": "ADMIN_RBS" },
          "registradoEm": "2026-10-03T14:00:00-03:00"
        }
      ]
    }
  ]
}
```

- `totais` são exatamente os números da rota 29 (`aplicacoes`): `previstas` é a soma do previsto das linhas, `comprovadas` soma, linha a linha, o menor entre o previsto e as fotos, `fotos` é o número de fotos e `percentual` é `comprovadas ÷ previstas × 100` cortado na primeira casa (17 de 19 é `89.4`).
- Por linha, `comprovadas` = o menor entre `previsto` e `fotos`: uma segunda foto numa aplicação de quantidade 1 aumenta `fotos` (2) e não passa `comprovadas` de 1.
- `linhas` traz só as aplicações de marca (a mídia fica na rota 29), em ordem de `bloco`, `formato` e `id`. `comprovacoes` vem da foto mais antiga para a mais nova, e `arquivoId` é o que a rota 18 baixa (a aba `APLICACOES` fechada para a marca vale também para o download: **403**). `registradoEm` tem o fuso de Brasília.
- Parceria **sem aplicação de marca no plano** (ainda sem a carga `PLANO`, ou só com mídia): `totais` vem com os quatro campos `null` e `linhas` vem `[]`. Nunca zero inventado.

**Erros:** 401; 403 (aba fechada, só para a marca); 404 (parceria que não existe ou é de outra).

## 38. `POST /api/parcerias/{id}/aplicacoes/{aproveitamentoId}/comprovacoes`  · Atlas 1

**Quem chama:** Admin RBS (exige o token CSRF). O patrocinador recebe **403** antes de qualquer outra conferência, com qualquer parceria, qualquer linha e qualquer conjunto de partes. Quem envia as fotos é decisão provisória da RBS (veja "Pendências"). `aproveitamentoId` é o `id` de uma linha da rota 37.

**Corpo:** `multipart/form-data`

| Parte | Obrigatória | O que é |
| --- | --- | --- |
| `arquivo` | Sim | A foto, em `png`, `jpg` ou `jpeg`. Até **20 MB**. |
| `descricao` | Não | Texto de até 200 caracteres (sem os espaços das pontas; só espaços valem como ausente). |

**Como funciona** [UPLOAD] [ISOLAMENTO]

1. O servidor confere a extensão (só foto) e o **conteúdo** pela assinatura: um executável renomeado para `.png` é recusado, como em qualquer envio.
2. Grava o arquivo (nome gerado pelo servidor, categoria `COMPROVACAO`) com a parceria **e a aba `APLICACOES`**, e a comprovação ligada à linha. Com a aba gravada, a marca baixa a foto pela rota 18 só enquanto a aba está aberta.
3. A linha passa a contar a foto nas rotas 37 e 29 na hora.

**201**: a comprovação criada, no mesmo formato de cada item de `comprovacoes` da rota 37.

```json
{
  "id": 5, "arquivoId": 77, "nomeOriginal": "arena.jpg", "descricao": "Testeira no palco",
  "registradoPor": { "id": 1, "nome": "Equipe B2B", "perfil": "ADMIN_RBS" },
  "registradoEm": "2026-10-03T14:00:00-03:00"
}
```

**Erros**

| Status | Quando |
| --- | --- |
| 403 | Patrocinador (`"Esta operação é restrita à equipe da RBS."`), ou falta o token CSRF. |
| 404 | Parceria que não existe (`"Não encontramos esta parceria."`), ou linha que não existe ou é de outra parceria (`"Não encontramos esta aplicação."`). Id da linha que não é número também é 404. |
| 413 | Foto acima de 20 MB. |
| 415 | Extensão que não é foto (`"Envie uma foto em PNG ou JPG."`), ou conteúdo que não bate com a extensão (`"O conteúdo do arquivo não é um PNG de verdade. Confira se o arquivo não foi renomeado."`). |
| 422 | A linha é de mídia: `detail` e `campos.aproveitamentoId` = `"A comprovação vale só para aplicação de marca."`. Ou parte `arquivo` ausente ou vazia (`campos.arquivo`), ou `descricao` com mais de 200 caracteres (`campos.descricao`). |

**Histórico:** `CRIACAO` (entidade `comprovacao_marca`, com o nome do arquivo em `valorNovo` e o formato da aplicação, mais a descrição, em `detalhe`).

---

# Dados informados pela marca (Atlas, parte 1)

Os números que o sistema não tem e só a marca (ou a agência dela) sabe, como os visitantes no espaço e os brindes distribuídos. O que se pode informar está num **catálogo fixo** (rota 39); o valor de cada parceria tem **autor, data e origem** (`PORTAL` para o formulário da aba, `ATLAS` para o cartão do assistente) e entra pela mesma rota de gravação (41) nos dois casos, com a mesma validação. Cada valor é uma linha nova: corrigir é gravar de novo, e a mais recente vale.

## 39. `GET /api/campos`  · Atlas 1

**Quem chama:** qualquer pessoa logada. O catálogo é fixo e não carrega dado de parceria nenhuma. **Filtro (opcional):** `aba` (um dos 7 códigos de aba; outro valor dá **422** com `campos.aba` = `Aba inválida. Use PERFIL, APLICACOES, ...`). Sem `aba`, vêm os campos de todas as abas; aba que existe e não tem campos devolve `[]`.

**200**: a lista de campos, na ordem do formulário.

```json
[
  { "chave": "ativacao.visitantes_total", "aba": "APLICACOES", "rotulo": "Visitantes no espaço", "tipo": "INTEIRO",
    "unidade": "pessoas", "minimo": 0, "maximo": null, "ajuda": "Quantas pessoas passaram pelo espaço da marca durante o festival, segundo a contagem da agência.",
    "limiteCampo": null },
  { "chave": "ativacao.cadastros_consentidos", "aba": "APLICACOES", "rotulo": "Cadastros com consentimento", "tipo": "INTEIRO",
    "unidade": "cadastros", "minimo": 0, "maximo": null, "ajuda": "...", "limiteCampo": "ativacao.visitantes_total" }
]
```

Hoje são quatro campos, todos da aba `APLICACOES`, inteiros a partir de 0 e sem máximo: `ativacao.visitantes_total` (Visitantes no espaço), `ativacao.cadastros_consentidos` (Cadastros com consentimento; **limite: os visitantes**), `ativacao.brindes_disponiveis` (Brindes disponíveis) e `ativacao.brindes_distribuidos` (Brindes distribuídos; **limite: os disponíveis**).

- `tipo`: `INTEIRO`, `DECIMAL`, `DATA` ou `ARQUIVO`. Um campo `ARQUIVO` ainda não é aceito pela rota 41.
- `minimo` e `maximo`: `null` quando não há limite. Num campo `INTEIRO` saem como inteiro (`0`), num `DECIMAL` com até 2 casas.
- `limiteCampo`: a `chave` do campo cujo valor vigente este não pode passar, ou `null`.

**Erros:** 401; 422 (aba que não existe).

## 40. `GET /api/parcerias/{id}/dados?aba=APLICACOES`  · Atlas 1

**Quem chama:** patrocinador da parceria, ou Admin RBS. A marca de outra parceria recebe **404**; a marca só lê com a aba pedida aberta (**403** com as duas frases de "Aba"); a RBS lê sempre. **Filtro obrigatório:** `aba` (um dos 7 códigos; ausente, vazia ou que não existe dá **422** com `campos.aba`).

**200**: os campos da aba, na ordem do catálogo (rota 39), cada um com o valor vigente.

```json
{
  "parceriaId": 2,
  "aba": "APLICACOES",
  "campos": [
    {
      "chave": "ativacao.visitantes_total", "aba": "APLICACOES", "rotulo": "Visitantes no espaço", "tipo": "INTEIRO",
      "unidade": "pessoas", "minimo": 0, "maximo": null, "ajuda": "...", "limiteCampo": null,
      "vigente": {
        "valor": 1200, "origem": "PORTAL",
        "informadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
        "informadoEm": "2026-10-29T10:00:00-03:00"
      }
    },
    {
      "chave": "ativacao.cadastros_consentidos", "aba": "APLICACOES", "rotulo": "Cadastros com consentimento", "tipo": "INTEIRO",
      "unidade": "cadastros", "minimo": 0, "maximo": null, "ajuda": "...", "limiteCampo": "ativacao.visitantes_total",
      "vigente": null
    }
  ]
}
```

- Cada campo traz os mesmos dados da rota 39 mais `vigente`: o valor **mais recente** que a parceria informou. `vigente` é `null` enquanto ninguém informou.
- `valor` é um inteiro (campo `INTEIRO`), um decimal (`DECIMAL`) ou uma data `AAAA-MM-DD` (`DATA`). `origem` é `PORTAL` (formulário da aba) ou `ATLAS` (cartão do assistente). `informadoPor` é quem informou (a marca ou a RBS) e `informadoEm` tem o fuso de Brasília.
- Corrigir um número é gravá-lo de novo (rota 41): a rota mostra só o mais recente, e os anteriores ficam guardados.

**Erros:** 401; 403 (aba fechada, só para a marca); 404 (parceria que não existe ou é de outra); 422 (`aba`).

## 41. `POST /api/parcerias/{id}/dados`  · Atlas 1

**Quem chama:** patrocinador da parceria, ou Admin RBS (exige o token CSRF). A marca de outra parceria recebe **404**; a marca só grava com a aba dos campos aberta (**403**). Grava de 1 a 20 valores de uma aba só; a origem gravada é `PORTAL`. O formato do corpo é conferido primeiro: lista ausente, vazia ou com mais de 20 itens, item nulo e chave em branco ou longa dão **422** antes do 404 e do 403, inclusive para a marca de outra parceria. Depois vêm 404, 403 e os erros de valor.

**Corpo**

```json
{ "valores": [ { "chave": "ativacao.visitantes_total", "valor": 1200 }, { "chave": "ativacao.cadastros_consentidos", "valor": 900 } ] }
```

`valor` é um **número JSON** (`1200`, e não `"1200"`); `1200.0` vale como o inteiro 1200. Num campo `DATA`, um texto `"2026-12-01"`.

**Como funciona** [VALIDACAO] [HISTORICO]

1. Cada valor vira **uma linha nova** com autor, data e origem. Os anteriores não mudam: corrigir é gravar de novo, e a rota 40 mostra o mais recente.
2. **Valor igual ao vigente não grava linha nova** (nem entra no histórico). Se todos forem iguais, a resposta é a mesma, sem nada gravado.
3. Tudo ou nada: com qualquer erro, nenhum valor do pedido é gravado.
4. Dois pedidos da mesma parceria ao mesmo tempo esperam um pelo outro, para os limites serem conferidos sobre o valor vigente de verdade.

**200**: o mesmo corpo da rota 40 para a aba dos campos enviados, já com os valores novos.

**Erros**

| Status | Quando |
| --- | --- |
| 400 | Corpo vazio ou que não é JSON. |
| 403 | Aba dos campos fechada para a marca (as duas frases de "Aba"; conferido antes dos erros de valor), ou falta o token CSRF. |
| 404 | Parceria que não existe ou é de outra. |
| 422 | Veja abaixo. |

**Os erros 422** trazem `campos` chaveado pela **chave do campo** (por exemplo `"ativacao.cadastros_consentidos"`), todos juntos numa resposta só:

| Quando | Frase |
| --- | --- |
| Lista de valores ausente, vazia ou com mais de 20 itens | `campos.valores`: `Envie a lista de valores.` / `Envie de 1 a 20 valores.` |
| Item sem chave | `campos["valores[0].chave"]`: `Informe a chave do campo.` |
| Chave que não está no catálogo | `Campo desconhecido.` |
| Mesma chave duas vezes no pedido | `Este campo aparece mais de uma vez no pedido.` |
| Campos de abas diferentes no mesmo pedido | `campos.valores`: `Envie os campos de uma aba só.` |
| Campo de arquivo (ainda não aceito por JSON) | `Este campo recebe um arquivo, que ainda não é aceito por aqui.` |
| Valor ausente ou `null` | `Informe o valor.` (em campo `DATA`: `Informe a data no formato AAAA-MM-DD.`) |
| Item nulo na lista | `Valor vazio.` |
| Chave com mais de 80 caracteres | `A chave do campo tem no máximo 80 caracteres.` |
| Valor que não é número inteiro num campo `INTEIRO` (`12.5`, `"1200"`, `true`) | `Informe um número inteiro.` |
| Valor que não é número num campo `DECIMAL`, ou com mais de 2 casas | `Informe um número.` / `Use no máximo 2 casas decimais.` |
| Data fora de `AAAA-MM-DD` num campo `DATA` | `Informe a data no formato AAAA-MM-DD.` |
| Número acima do que o banco guarda (999.999.999.999,99) | `O valor é grande demais.` |
| Abaixo do mínimo ou acima do máximo do campo | `O valor mínimo é 0.` / `O valor máximo é 100.` |
| Acima do `limiteCampo`: o valor vigente dele, ou o valor novo enviado no mesmo pedido | `Não pode passar de Visitantes no espaço (1.200).` |
| O inverso: baixar o campo-limite abaixo de um campo que depende dele e já tem valor vigente (e não está no pedido) | `Não pode ficar abaixo de Cadastros com consentimento (900).` |

Os números das frases saem no padrão brasileiro (`1.200`). Sem valor vigente no campo-limite, não há com o que comparar, e o valor passa. Se o campo-limite e o dependente vêm no mesmo pedido, vale o par novo (cadastros 400 com visitantes 500 passa, mesmo com os vigentes 900 e 1.200); um dependente acima do limite novo recebe o erro no próprio campo.

**Histórico:** `CRIACAO` por valor gravado (entidade `dado_informado`), com o valor anterior em `valorAnterior` (quando havia), o novo em `valorNovo` (já formatado, `1.200`) e o rótulo do campo com a origem em `detalhe` (`Visitantes no espaço (portal)`).

---

# O Atlas: conversa, cartão de perguntas e relatório (Atlas, parte 2)

O Atlas monta o relatório de **Ativação no evento** da marca. Ele monta a estrutura e redige, mas **nunca escreve um número**: todo número do relatório vem do portal (a consolidação e o plano de aplicações), de um dado informado com autor e data (rotas 40 e 41) ou de uma conta registrada, e cada um aparece em "Fontes e cálculos". O relatório é um **documento de trabalho da marca**, com o selo "Não validado pela RBS": o relatório oficial (rotas 31 a 36) não muda.

O relatório sai de dois jeitos, pelas mesmas rotas. Pelo **botão "Montar relatório"** (o caminho sem modelo de linguagem, chamado de plano B): botão &rarr; base do relatório com os números do portal &rarr; cartão de perguntas com o que só a marca sabe &rarr; frases-modelo nos textos &rarr; relatório pronto. Ou pelo **texto livre no chat**, quando a API sobe com o modelo de linguagem ligado (`llmDisponivel` `true`; veja "O modelo de linguagem"): o modelo chama as mesmas operações como ferramentas, escreve os textos (`redigidoPor` `ATLAS`) e para no cartão para a marca responder. O modelo vem **desligado por padrão**: aí `llmDisponivel` é `false`, o botão continua funcionando e a mensagem de texto da rota 44 responde 503.

> **Neste MVP o relatório está desligado.** Só o chat e a consulta aos números da parceria ficam; o relatório volta quando houver o JEV e as outras ferramentas. O código dele continua no projeto e é desligado pela propriedade `atlas.relatorio.ligado` (veja "Modo só chat"). O resto deste capítulo descreve o relatório e vale com a propriedade ligada, salvo onde o texto diz o contrário.

## Modo só chat (`atlas.relatorio.ligado`)

| Propriedade | Variável | Padrão | O que faz |
| --- | --- | --- | --- |
| `atlas.relatorio.ligado` | `ATLAS_RELATORIO` | `false` (também no perfil `dev`; `true` no perfil `test`, para os testes do relatório) | `false`: o Atlas é só o chat. `true`: o relatório de trabalho de sempre. |

Com `atlas.relatorio.ligado=false`:

- **Ferramentas.** O modelo recebe só `parceria_consultar` (a consulta de leitura de sempre, com os marcadores `{{ref}}`, a mesma checagem do texto, o filtro, o resumo do raciocínio e o teto diário). Se ele chamar uma `relatorio_*` mesmo assim (uma conversa antiga pode induzi-lo), a resposta da ferramenta é um erro (`{"erro": "Não existe a ferramenta relatorio_iniciar. Use só as ferramentas oferecidas."}`) e nada é criado.
- **Prompt.** O modelo recebe `atlas/prompts/chat.txt`, sem o fluxo do relatório e sem oferecer montar relatório, com todas as regras de números, marcadores, isolamento e de tratar o retorno das ferramentas como dado. Se pedirem um relatório, o Atlas diz, sem números, que o relatório ainda não está disponível nesta versão e que pode responder perguntas sobre a parceria. Com a propriedade ligada, o prompt continua o de `relatorio.txt`.
- **Rota 42.** Sem corpo ou `{}` abre a conversa vazia. Com `atalho` ou `modelo`: **422** com `campos.atalho` = `"O relatório não está disponível nesta versão."` (depois das conferências de 403 e 404; nenhuma conversa é aberta).
- **Rota 44.** Só `{"texto": "..."}`. Com `atalho` ou `modelo` (também junto de `texto`): **422** com a mesma frase em `campos.atalho`. Sem `texto` nem atalho, o 422 de `campos.texto` é `"Escreva a mensagem."`.
- **Rotas 46, 47 e 48.** Não são mapeadas: **404** em `ProblemDetail`, como qualquer rota que não existe (a RBS também recebe 404 na 46, e não 403).
- **Itens da conversa.** Nenhum `cartao`, `relatorio_pronto` nem `aviso` de cartão, também em conversa antiga que os tenha (o histórico em si não muda e continua indo ao modelo como está). O `relatorio` de cada conversa da rota 43 vem `null`. Os relatórios já prontos ficam no banco, sem rota que os leia.
- **Campo `relatorioDisponivel`.** O objeto Conversa (rotas 42, 44, 45 e 46, e o `fim` do fluxo) e a resposta da rota 43 trazem `relatorioDisponivel`, o valor da propriedade (`false` aqui). O front mantém os botões do relatório, desabilitados.
- **Frases fixas do servidor** (nenhuma fala do botão):

| Onde | Com o relatório ligado | Só chat |
| --- | --- | --- |
| 503 da rota 44 (sem modelo de linguagem) | `"O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório."` | `"O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde."` |
| 429 da rota 44 (teto diário) | `"O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã."` | `"O Atlas atingiu o limite de uso de hoje desta parceria. Tente amanhã."` |
| 422 da rota 44 sem texto nem atalho | `"Escreva a mensagem ou use o botão Montar relatório."` | `"Escreva a mensagem."` |
| Texto recusado duas vezes | `"Não consegui responder isso agora. Pergunte de outro jeito ou monte o relatório da ativação."` | `"Não consegui responder isso agora. Pergunte de outro jeito."` |
| Limite de 8 chamadas por turno | `"Parei por aqui: esta resposta pediu passos demais. Diga de novo o que você quer, ou use o botão Montar relatório."` | `"Parei por aqui: esta resposta pediu passos demais. Diga de novo o que você quer."` |
| Falha do modelo | `"Não consegui falar com o modelo de linguagem agora. O que já foi feito está salvo; tente de novo daqui a pouco ou use o botão Montar relatório."` | `"Não consegui falar com o modelo de linguagem agora. Tente de novo daqui a pouco."` |
| Prazo do turno (3 min) | `"Esta resposta demorou mais do que o esperado e eu parei. O que já foi feito está salvo; tente de novo ou use o botão Montar relatório."` | `"Esta resposta demorou mais do que o esperado e eu parei. Tente de novo."` |
| Resposta vazia do modelo | `"Não consegui responder agora. Tente de novo ou use o botão Montar relatório."` | `"Não consegui responder agora. Tente de novo."` |

  O item `texto` com qualquer uma das frases acima (as dos dois modos) sai com `porModelo` `false`.
- **Sem modelo de linguagem** não há plano B nem botão: a conversa abre (`llmDisponivel` `false`) e a mensagem de texto responde 503.
- **Banco.** As tabelas do relatório (`atlas_relatorio`, `atlas_solicitacao`, `atlas_calculo`, `atlas_decisao`) ficam sem uso; nenhuma migração muda.

**Debug das ferramentas** (`atlas.debug.ferramentas`): veja "Debug das ferramentas", em "Rotas de debug (temporárias)".

**Regras que valem nas rotas 42 a 48**

- **Perfil.** 42 a 46 são só do Admin Patrocinador; a RBS recebe **403** (`"Só o Admin Patrocinador da parceria conversa com o Atlas."`) antes de qualquer outra conferência e do corpo, com qualquer id e qualquer corpo. 47 e 48 são da marca da parceria e da RBS.
- **Isolamento.** Recurso de outra parceria dá **404** com a mesma frase de um id que não existe (`"Não encontramos esta parceria."`, `"... esta conversa."`, `"... esta solicitação."`, `"... este relatório."`). A **conversa é de quem a abriu**: a conversa e o cartão de outro usuário da mesma parceria também dão 404. O **relatório é da parceria**: qualquer usuário dela (e a RBS) o vê nas rotas 47 e 48. A parceria é conferida a cada chamada (vínculo revogado vale na chamada seguinte).
- **Aba.** A aba do modelo, `APLICACOES`, tem de estar aberta para a marca: fechada, todas as rotas 42 a 48 respondem **403** com as duas frases de "Aba". Vale **também para o relatório pronto**: fechar a aba esconde o documento da marca (rotas 47 e 48). A RBS lê o relatório sempre.
- **POST** exige o token CSRF. Erros em `ProblemDetail`.
- **Um turno por vez em cada conversa.** As rotas 42 (com atalho), 44 e 46 começam um turno; enquanto ele roda (com o modelo, uma conversa pode levar alguns segundos), outra chamada na mesma conversa recebe **409** (`"O Atlas ainda está respondendo nesta conversa. Espere a resposta."`). O turno termina sozinho, também em erro, e um turno parado há mais de 5 minutos deixa de valer.
- **Ordem das conferências:** 403 (RBS) &rarr; 404 &rarr; 403 (aba) &rarr; 400 e 422 (formato do corpo) &rarr; 503 &rarr; 429 (teto diário; só a mensagem de texto) &rarr; 409 &rarr; 422 dos valores do cartão (que dependem do estado). Na resposta em fluxo das rotas 44 e 46 (veja "Resposta em fluxo"), todas essas conferências vêm **antes** de o fluxo abrir, com os mesmos status e o mesmo `ProblemDetail` em JSON.

## O modelo de linguagem (texto livre)

Com o modelo ligado (pelas variáveis de ambiente ou, no perfil `dev`, pela chave posta nas rotas de debug do fim deste arquivo), a mensagem de texto da rota 44 e a resposta do cartão da rota 46 rodam o **loop do agente**. O contrato para quem usa a API:

- **Um turno** começa na mensagem da pessoa (ou na resposta do cartão). O modelo responde com texto, ou pede ferramentas; o servidor as executa **uma a uma, na ordem**, grava a resposta de cada uma e devolve o resultado ao modelo, até ele responder com texto. As ferramentas são `relatorio_iniciar`, `relatorio_solicitar_campos`, `relatorio_editar` (operação `definir_texto`) e `relatorio_finalizar`, as mesmas operações do botão, e a consulta `parceria_consultar`. O modelo só recebe as ferramentas do estado do relatório (sem relatório, `parceria_consultar` e `relatorio_iniciar`; em montagem, `parceria_consultar` e as outras três, menos `relatorio_solicitar_campos` depois de três cartões no mesmo relatório; com o cartão aberto, nenhuma: o turno para). **Com `atlas.relatorio.ligado=false`, só `parceria_consultar`** (veja "Modo só chat").
- **Pergunta sobre os números sem relatório.** `parceria_consultar` (sem argumento) é **só de leitura**: não cria relatório e não grava nada além da trilha da chamada. Devolve as referências do modelo-base para a parceria da conversa, resolvidas com o perfil de quem pede (o filtro de aba da rota 29): `{abaFechada, aviso, dados:[{ref, rotulo, valor, unidade, origem}], indisponiveis:[{ref, rotulo, motivo}]}`, em que `dados` é o mesmo que `relatorio_iniciar` devolve em dados disponíveis mais os cálculos fixos (`calc:`) que já dá para resolver, e `indisponiveis` traz os que estão sem valor (dado não informado, conta sem a entrada). Com a aba `APLICACOES` fechada para a marca (conferida no banco na hora da chamada), vem `abaFechada: true`, o `aviso` (`"A aba Aplicações de marca no evento está fechada para esta parceria. Não há dados para consultar; diga isso à pessoa sem citar número."`) e as listas vazias. O modelo responde citando o dado com o marcador (`"Aplicações de marca previstas: {{cons:aplicacoes.previstas}}; comprovadas no evento: {{cons:aplicacoes.comprovadas}}."`) e o item `texto` sai com os números (`"... previstas: 19; comprovadas no evento: 17."`). Os marcadores do chat, `calc:` incluído, resolvem contra a parceria da conversa com as fórmulas fixas do modelo-base, com ou sem relatório aberto.
- **Nenhuma ferramenta recebe parceria.** A parceria, o usuário e a conversa vêm da sessão; um argumento que cite outra parceria (ou qualquer argumento fora do esquema) é recusado e volta ao modelo como erro. A marca só chega aos dados da própria parceria, por qualquer caminho.
- **Até 8 chamadas de ferramenta por turno.** Erro de ferramenta (argumento inválido, relatório no estado errado, texto recusado) volta ao modelo como mensagem de ferramenta e nunca vira 500. Passado o limite, o turno termina com um texto fixo (`"Parei por aqui: ..."`).
- **O cartão pausa o turno.** Ao pedir `relatorio_solicitar_campos`, o servidor grava o cartão, deixa essa chamada **sem resposta** e para: as chamadas seguintes da mesma rodada (inclusive outro `relatorio_solicitar_campos`) respondem erro (`"Aguardando a resposta do cartão..."`). A rota 46 grava os valores e a resposta da ferramenta (`{acao, campos:[{chave, ref, valor}]}`, com o mesmo id do pedido) e o loop retoma. **Mensagem de texto com o cartão aberto** (rota 44) primeiro fecha a chamada pendente com `{"acao":"cancel"}`, marca o cartão `CANCELADA` e só então entra; o `aviso` de cartão fechado abre os itens do turno.
- **O modelo não escreve número nem termo interno.** Texto de parágrafo (`relatorio_editar`) e texto de chat passam pela mesma checagem: sem algarismo fora de marcador `{{ref}}`, todo marcador existe e resolve nesta parceria, sem chave crua fora de marcador (`dado:`, `cons:`, `calc:`, `lista:`, `parc:`, `evento:`), sem nome de ferramenta (`relatorio_...`, `parceria_...`), sem as palavras "marcador", "tool" e "cartão de solicitação", sem HTML, link, imagem nem URL e, no parágrafo, até 3 frases e 600 caracteres. **No chat, todo marcador também precisa ter valor agora** (um dado não informado não vira "indisponível" no meio da frase: o modelo diz com palavras que ele ainda não existe). A recusa de um parágrafo volta ao modelo, que tem mais uma tentativa; na **segunda recusa do mesmo bloco ele fica com a frase-modelo** (`redigidoPor` `MODELO`). No chat, com texto recusado o servidor pede **uma** nova redação; se a segunda também for recusada, a resposta vira o texto fixo `"Não consegui responder isso agora. Pergunte de outro jeito ou monte o relatório da ativação."`. O histórico guarda o texto com os marcadores; o item `texto` da conversa os traz resolvidos. Número por extenso escapa da regra e é proibido no prompt.
- **O parágrafo redigido pelo modelo** sai com `redigidoPor: "ATLAS"` na rota 47 (e `"MODELO"` quando é a frase-modelo).
- **Falha do modelo** (rede, limite do provedor, tempo esgotado): o turno termina com um texto fixo (`"Não consegui falar com o modelo de linguagem agora. O que já foi feito está salvo..."`) e resposta **200**; o histórico continua válido e o botão segue funcionando. Um turno que passa de **3 minutos** (`atlas.prazo-do-turno`) não começa outra ida ao modelo e termina do mesmo jeito (`"Esta resposta demorou mais do que o esperado e eu parei..."`), para não vencer a trava de 5 minutos do turno.
- **Teto diário.** Para uma rodada de testes não gastar o limite do provedor, cada parceria tem um teto de mensagens do assistente por dia: contam as mensagens do assistente gravadas hoje (dia de Brasília), em todas as conversas da parceria, menos as do botão: cada ida ao modelo que grava uma mensagem conta, inclusive a que só pede ferramentas, e as frases fixas do servidor também; o limite é configurável (`atlas.teto-diario-por-parceria`, padrão **200**). Com o teto atingido, a rota 44 com `texto` responde **429** (`"O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã."`), sem gravar a mensagem nem chamar o modelo. O **botão não conta** e continua funcionando; a resposta do cartão (rota 46) com o teto atingido segue o plano B (frases-modelo) em vez de dar 429, para a marca não ficar presa a um cartão aberto. A conferência é no início do turno: um turno que começou abaixo do teto termina.
- O **botão** (atalho) continua sendo o plano B, com ou sem modelo. Cartão aberto pelo botão e respondido com o modelo ligado é retomado pelo modelo.
- **Raciocínio do modelo.** Vem **ligado** por padrão: cada ida ao modelo leva `"thinking": {"type": "enabled"}` (com `atlas.raciocinio=false`, variável `ATLAS_RACIOCINIO`, vai `"disabled"` e não há raciocínio, `pensando`, resumo nem item; vale para o modelo das variáveis de ambiente e para o da chave do debug, que pode trocar). O `reasoning_content` de cada resposta é gravado com a mensagem do assistente e **volta ao modelo em todas as idas seguintes**, também depois da pausa do cartão (o histórico é remontado do banco): com ferramentas, o DeepSeek responde 400 sem ele. **O raciocínio cru nunca sai do servidor**: não vai em evento, em item, em resposta nenhuma da API nem no log. A conversa recebe só um **resumo** dele, gerado no servidor assim:
  - Para cada mensagem do assistente gravada com `reasoning_content` não vazio, o servidor faz uma chamada **à parte** ao mesmo provedor: sem ferramentas, com `"thinking": {"type": "disabled"}`, no máximo 200 tokens de saída e temperatura baixa. A entrada é só o raciocínio daquela mensagem (o começo é cortado se passar de 12 mil caracteres), tratado como dado e nunca como instrução. O prompt fica em `atlas/prompts/resumo-do-raciocinio.txt` e pede até duas frases curtas, em português do Brasil, na primeira pessoa e no passado, sobre o que o Atlas conferiu e decidiu (`"Conferi os números da parceria e citei as aplicações previstas e comprovadas."`), sem número, nome de ferramenta, chave, marcador nem identificador.
  - O resumo passa pela checagem do texto do chat (sem algarismo, chave crua, nome de ferramenta nem termo interno) e, além dela, não pode ter `{{`. **Recusado, com falha do provedor ou com tempo esgotado, a mensagem fica sem resumo**, sem segunda tentativa (para poupar o limite do provedor). O log diz só que o resumo foi recusado ou falhou, nunca o conteúdo.
  - A chamada roda à parte (não atrasa a ida seguinte do loop), **sem transação aberta**, e o resumo é gravado com um `UPDATE` curto em `atlas_mensagem.resumo_do_raciocinio` (V9). Ele não é uma mensagem do histórico (o modelo nunca o recebe de volta) e não conta no teto diário.
  - **Antes do `fim`** (e, sem fluxo, antes de responder o JSON), o turno espera os resumos pendentes por até **15 segundos** (`atlas.prazo-do-resumo`, variável `ATLAS_PRAZO_DO_RESUMO`). O que não ficou pronto sai sem resumo no `fim`; se ficar pronto depois, ainda é gravado e aparece na próxima leitura da conversa (rota 45). O turno (`turno_desde`) não fica preso por causa de resumo.
  - Na conversa, o resumo é o item `raciocinio` (veja "O objeto Conversa"); no fluxo, o evento `pensando` avisa que o modelo está raciocinando e o evento `resumo` traz o resumo (veja "Resposta em fluxo"). Mensagem de antes da V9, com raciocínio e sem resumo, não gera item: não se gera resumo retroativo.

## Resposta em fluxo (rotas 44 e 46)

As rotas **44** e **46** respondem **em fluxo** (Server-Sent Events) quando o pedido traz `Accept: text/event-stream`, sozinho ou junto de outros tipos (o front manda `Accept: text/event-stream, application/json`). Sem `text/event-stream` no `Accept` (ou só com `*/*`), elas respondem como sempre: o objeto Conversa em JSON no fim do turno. Não há rota nova.

- **Erros antes do fluxo.** Tudo o que dá erro na rota dá o **mesmo erro HTTP**, em `ProblemDetail` (`application/problem+json`), antes de o fluxo abrir: 400, 403, 404, 409 (turno em andamento; cartão já respondido; cartão aberto no atalho), 422 (do corpo e, na 46, dos valores do cartão), 429 e 503. Só depois de passar por tudo isso (e de gravar a mensagem da pessoa, ou os valores do cartão) a resposta vira **200** `text/event-stream`.
- **O turno** (`turno_desde`) dura até o evento `fim`: antes dele o turno espera os resumos do raciocínio pendentes (até 15 s), e a trava é solta logo antes dele, de modo que quem o recebe já pode mandar a próxima mensagem. Se o cliente fecha a conexão no meio, o turno **termina do mesmo jeito no servidor** e grava tudo o que gravaria; a conversa (rota 45) mostra o resultado. Nenhuma transação fica aberta durante o fluxo.
- **Com o botão** (atalho da rota 44) ou na rota 46 sem modelo (ou com o teto atingido), o plano B roda inteiro antes, e o fluxo traz só o `fim`.

Cada evento tem `event:` e `data:` com um JSON. Numa ida ao modelo a ordem é `pensando` (só se o modelo raciocinar), depois `texto` ou `etapa`; o turno pode ter várias idas, e a sequência se repete. O `resumo` do raciocínio de uma ida chega quando fica pronto, em qualquer ponto depois do `pensando` dela: pode vir depois dos `texto` daquela ida ou durante a ida seguinte, e sempre antes do `fim`. O fluxo termina sempre com `fim` ou `erro`. O raciocínio cru não tem evento: nenhum pedaço dele vai ao cliente.

| `event` | `data` | Quando |
| --- | --- | --- |
| `pensando` | `{}` | O modelo começou a raciocinar nesta ida: **uma vez por ida ao modelo**, no primeiro pedaço de raciocínio que chega. Não leva nada do raciocínio; a tela mostra "Pensando…" com o tempo. Não há com `atlas.raciocinio=false`. |
| `resumo` | `{"texto": "..."}` | O resumo do raciocínio de uma ida, já conferido e gravado (veja "Raciocínio do modelo"). Chega quando fica pronto, e sempre antes do `fim`. A ida cujo resumo foi recusado, falhou ou passou do prazo não tem `resumo` (nem item `raciocinio` no `fim`). |
| `etapa` | `{"texto": "..."}` | Uma ferramenta começou a rodar. A frase, nunca o nome da ferramenta: `"Consultando os números da parceria…"` (`parceria_consultar`), `"Abrindo o relatório…"` (`relatorio_iniciar`), `"Preparando as perguntas…"` (`relatorio_solicitar_campos`), `"Redigindo o documento…"` (`relatorio_editar`), `"Finalizando o documento…"` (`relatorio_finalizar`). |
| `texto` | `{"delta": "..."}` | Um pedaço da resposta para a marca, **já filtrado**: um marcador `{{...}}` fica retido até fechar e sai resolvido e formatado, igual ao item `texto` do `fim`; a última palavra espera o espaço seguinte. A cada pedaço, o que chegou até ali passa pela checagem do texto do chat. As frases fixas do servidor (a troca de um texto recusado duas vezes, a falha do modelo, o limite e o prazo do turno) também chegam como `texto`, inteiras. |
| `ferramenta` | `{"nome": "parceria_consultar", "argumentos": "{}", "resultado": "{...}", "erro": false, "duracaoMs": 42}` | **Só com `atlas.debug.ferramentas=true`.** Uma ferramenta terminou: logo que ela termina, antes da ferramenta seguinte e da próxima ida ao modelo (depois da `etapa` dela). O mesmo corpo do item `ferramenta` do `fim`, sem o `tipo` (veja "Debug das ferramentas"). Desligado, o evento não existe. |
| `texto_recusado` | `{}` | A checagem recusou o texto desta ida ao modelo (algarismo fora de marcador, chave crua, marcador que não existe ou sem valor, nome de ferramenta, "marcador"...), durante o fluxo ou na checagem do texto inteiro no fim da ida; ou o modelo falhou no meio de um texto. O servidor para de repassar: **a tela descarta o `texto` que recebeu nesta ida**. A seguir vêm a nova redação (novos `texto`) ou a frase fixa. |
| `fim` | o objeto Conversa | O mesmo JSON que a rota devolve sem fluxo (`conversaId`, `llmDisponivel`, `relatorioDisponivel`, `debugDeFerramentas`, `diagnosticoDisponivel`, `itens` do turno). Fecha o fluxo. O traço do turno já está gravado quando o `fim` sai: o `turno` dos itens pode ser aberto na hora. |
| `erro` | `{"status": 500, "detail": "Algo deu errado do nosso lado. Tente de novo em instantes; se continuar, avise a equipe."}` | Uma falha inesperada depois de o fluxo abrir (a falha do modelo continua virando a frase fixa, com `fim`). Fecha o fluxo. O fluxo também fecha sozinho depois de 6 minutos. |

Exemplo (rota 44, o modelo consulta os números e responde):

```text
event:pensando
data:{}

event:etapa
data:{"texto":"Consultando os números da parceria…"}

event:pensando
data:{}

event:resumo
data:{"texto":"Conferi os números da parceria."}

event:texto
data:{"delta":"Os números de "}

event:texto
data:{"delta":"Cliente Amarelo estão no portal."}

event:resumo
data:{"texto":"Conferi o que veio da consulta e montei a resposta."}

event:fim
data:{"conversaId":12,"llmDisponivel":true,"relatorioDisponivel":false,"debugDeFerramentas":false,"itens":[{"tipo":"usuario","texto":"Como estou?"},{"tipo":"raciocinio","resumo":"Conferi os números da parceria."},{"tipo":"raciocinio","resumo":"Conferi o que veio da consulta e montei a resposta."},{"tipo":"texto","texto":"Os números de Cliente Amarelo estão no portal.","porModelo":true}]}
```

O `fim` é a referência: o que a tela montou com os pedaços dá lugar aos itens dele. No exemplo, o primeiro `resumo` chega durante a ida seguinte e o segundo depois do texto dela; o que não chegou antes do `fim` não aparece nele.

## O objeto Conversa (rotas 42, 44, 45 e 46)

```json
{ "conversaId": 12, "llmDisponivel": false, "relatorioDisponivel": true, "debugDeFerramentas": false,
  "diagnosticoDisponivel": false,
  "itens": [
    { "tipo": "usuario", "texto": "Montar o relatório da ativação" },
    { "tipo": "texto", "texto": "Montei a base do relatório de ativação com os números do portal. Faltam números que só vocês sabem.", "porModelo": false },
    { "tipo": "cartao", "solicitacaoId": 31, "relatorioId": 7, "mensagem": "Para fechar a seção de público, preciso destes números.", "status": "PENDENTE",
      "campos": [ { "chave": "ativacao.visitantes_total", "aba": "APLICACOES", "rotulo": "Visitantes no espaço", "tipo": "INTEIRO", "unidade": "pessoas",
                    "minimo": 0, "maximo": null, "ajuda": "Quantas pessoas passaram pelo espaço da marca durante o festival, segundo a contagem da agência.",
                    "limiteCampo": null, "obrigatorio": true, "vigente": null } ] },
    { "tipo": "relatorio_pronto", "relatorioId": 7, "titulo": "Ativação no evento" },
    { "tipo": "aviso", "texto": "Cartão fechado. Use Montar relatório para continuar." } ] }
```

- `llmDisponivel` é `true` quando há um modelo de linguagem agora (a chave posta pelo debug ou as variáveis de ambiente; muda na hora ao pôr ou apagar a chave) e `false` no padrão (sem ele o Atlas só oferece o botão, se o relatório estiver ligado). As rotas 42, 43, 44, 45 e 46 trazem o mesmo valor.
- `relatorioDisponivel` é o valor de `atlas.relatorio.ligado`: `false` no Atlas só chat (o padrão), em que não saem `cartao`, `relatorio_pronto` nem `aviso`. As rotas 42, 43 (ao lado de `llmDisponivel`), 44, 45 e 46 trazem o mesmo valor. O exemplo abaixo é o de um Atlas com o relatório ligado.
- `debugDeFerramentas` é o valor de `atlas.debug.ferramentas` (veja "Debug das ferramentas"): `true`, os itens trazem também `ferramenta`. Não vem na rota 43.
- `diagnosticoDisponivel` é `true` quando a marca vê o diagnóstico das próprias conversas: `atlas.diagnostico.ligado` **e** `atlas.diagnostico.marca` (veja "Diagnóstico do Atlas"). Então a rota 49 responde e os itens do assistente (`raciocinio`, `texto`, `ferramenta`, `cartao` e `relatorio_pronto`) trazem o campo `turno`: o id do turno do traço a que a mensagem pertence (um UUID, texto). O front mostra o botão "Diagnóstico" nas respostas que têm `turno`. Falso (o padrão), o campo `turno` não existe em item nenhum. Não vem na rota 43.
- As rotas **42, 44 e 46 devolvem só os itens do turno**; a **45 devolve a conversa inteira**.
- Os itens são **derivados do histórico** da conversa, que o servidor grava no formato de chat completions (`user`, `assistant` com `tool_calls`, `tool`), como se o modelo tivesse chamado as ferramentas: nada é guardado só para a tela.

| `tipo` | Quando aparece | Campos |
| --- | --- | --- |
| `usuario` | O que a pessoa disse (o botão aparece como "Montar o relatório da ativação"; no histórico que vai ao modelo, a mensagem continua "Montar o relatório da ativação (botão)") | `texto` |
| `raciocinio` | O **resumo** do raciocínio do modelo de uma mensagem do assistente, **antes** dos itens dela (`texto`, `cartao`, `relatorio_pronto`). O raciocínio cru (`reasoning_content`) nunca aparece: o resumo é gerado no servidor (veja "Raciocínio do modelo") e já passou pela checagem do texto. Só aparece quando a mensagem tem resumo gravado: sem raciocínio, com resumo recusado, atrasado ou perdido, nas mensagens de antes da V9, no botão e nas frases fixas do servidor não há item (a exceção é `Não consegui responder agora`, que pode ter um, quando o modelo raciocinou e não respondeu). A tela o mostra numa caixa minimizável. | `resumo`, `turno` (só com `diagnosticoDisponivel`) |
| `texto` | O que o Atlas disse. Os marcadores `{{...}}` já vêm trocados pelo valor; o que não tem valor vira "indisponível". `porModelo` é `true` só quando o texto foi escrito pelo modelo de linguagem; é `false` nas frases fixas do servidor: as do botão (plano B: "Montei a base...", "Continuo o relatório...", "Pronto: ..."), a frase fixa que troca um texto recusado duas vezes e as de falha, limite e prazo do turno | `texto`, `porModelo`, `turno` (só com `diagnosticoDisponivel`) |
| `ferramenta` | **Debug, só com `atlas.debug.ferramentas=true`.** Uma ferramenta que o agente chamou, na ordem em que rodou, **depois** do `raciocinio` e do `texto` da mesma mensagem do assistente (e antes do `cartao` que ela abriu). Veja "Debug das ferramentas" | `nome`, `argumentos`, `resultado`, `erro`, `duracaoMs`, `turno` (só com `diagnosticoDisponivel`) |
| `cartao` | O cartão de perguntas, com o **estado de agora** | `solicitacaoId`, `relatorioId`, `mensagem`, `status` (`PENDENTE`, `RESPONDIDA`, `RECUSADA` ou `CANCELADA`), `campos`, `turno` (só com `diagnosticoDisponivel`) |
| `relatorio_pronto` | O relatório ficou pronto; o front abre o A4 pela rota 47. **Só com `atlas.relatorio.ligado=true`**, como `cartao` e `aviso` | `relatorioId`, `titulo`, `turno` (só com `diagnosticoDisponivel`) |
| `aviso` | Aviso do sistema: o cartão foi fechado (cancelado, ou o relatório foi descartado). O texto segue o `llmDisponivel` da resposta: `"Cartão fechado. Use Montar relatório para continuar."` sem o modelo, `"Cartão fechado. Quando tiver os números, peça para continuar."` com ele | `texto` |

Cada item de `campos` do cartão é um campo do catálogo (rota 39) mais `obrigatorio` e `vigente`, que usa o formato da rota 40 (`{valor, origem, informadoPor, informadoEm}`, ou `null` se ninguém informou). O cartão pede, de uma vez, o que o relatório precisa e ainda não tem valor: primeiro as obrigatórias (visitantes e cadastros), depois as opcionais (brindes disponíveis e distribuídos). Não há campo de arquivo no cartão.

## 42. `POST /api/parcerias/{id}/atlas/conversas`  · Atlas 2

**Quem chama:** Admin Patrocinador da parceria (exige o token CSRF). A RBS recebe **403**; a marca de outra parceria recebe **404**; com a aba `APLICACOES` fechada, **403** com as duas frases de "Aba".

**Corpo (opcional)**

- Sem corpo ou `{}`: abre uma conversa vazia.
- `{"atalho": "MONTAR_RELATORIO", "modelo": "ATIVACAO_EVENTO"}`: abre a conversa e já roda o plano B (o botão "Montar relatório"). **Só com `atlas.relatorio.ligado=true`**; com a propriedade desligada (o padrão), `atalho` ou `modelo` dão 422.

**201**: o objeto Conversa. Conversa vazia: `itens: []`. Com o atalho, os itens do turno: `usuario`, `texto` e o `cartao` com as lacunas (status `PENDENTE`). Se a marca já informou tudo o que é obrigatório pelo formulário da aba (rota 41), não há cartão: os itens são `usuario`, `texto` ("Pronto: ...") e `relatorio_pronto`.

**Erros**

| Status | Quando |
| --- | --- |
| 400 | Corpo que não é JSON. |
| 403 | RBS; aba `APLICACOES` fechada; falta o token CSRF. |
| 404 | Parceria que não existe ou é de outra. |
| 422 | Atalho ou modelo desconhecido, ou só um dos dois (`campos.atalho`: `"Atalho desconhecido. Use MONTAR_RELATORIO."`; `campos.modelo`: `"Informe o modelo."` / `"Modelo desconhecido. Use ATIVACAO_EVENTO."`). Com o relatório desligado, qualquer `atalho` ou `modelo`: `campos.atalho` = `"O relatório não está disponível nesta versão."`. |

**Histórico:** `CRIACAO` (entidade `atlas_relatorio`) quando o atalho cria o relatório.

## 43. `GET /api/parcerias/{id}/atlas/conversas`  · Atlas 2

**Quem chama:** Admin Patrocinador da parceria. Lista as conversas **deste usuário** nesta parceria, da mexida há menos tempo para a mais antiga, **até 20**. Antes de listar, descarta o relatório que ficou parado (veja "Descarte"; com o relatório desligado não descarta).

**200**

```json
{ "llmDisponivel": false, "relatorioDisponivel": true,
  "conversas": [ { "id": 12, "criadaEm": "2026-10-30T10:00:00-03:00", "atualizadaEm": "2026-10-30T10:05:00-03:00",
                   "relatorio": { "id": 7, "status": "PRONTO" } } ] }
```

`relatorioDisponivel` é o `atlas.relatorio.ligado`. `relatorio` é o relatório mais novo da conversa, ou `null` se ela ainda não tem um (**sempre `null` com o relatório desligado**). `atualizadaEm` é a hora da última mensagem (ou a criação, se não há mensagem). Conversa de outro usuário da mesma parceria não aparece.

**Erros:** 401; 403 (RBS, ou aba fechada); 404 (parceria).

## 44. `POST /api/atlas/conversas/{id}/mensagens`  · Atlas 2

**Quem chama:** só o dono da conversa (exige o token CSRF). A RBS recebe **403**; conversa que não existe, é de outra parceria ou de outro usuário da mesma parceria recebe **404** (`"Não encontramos esta conversa."`).

**Corpo:** **ou** `{"texto": "..."}` **ou** `{"atalho": "MONTAR_RELATORIO", "modelo": "ATIVACAO_EVENTO"}`. Com `atlas.relatorio.ligado=false` (o padrão), só `texto`: `atalho` ou `modelo` dão 422.

- **Com o atalho**, roda o plano B nesta conversa. Se já há um relatório **em montagem** nela (por exemplo, depois de um cartão cancelado), continua **dele** em vez de criar outro: reabre o cartão com as lacunas que faltam. Depois de um relatório pronto, o atalho começa um relatório novo.
- **Com texto**, roda o loop do modelo de linguagem (veja "O modelo de linguagem"). O texto é aparado dos espaços das pontas depois de conferir o tamanho. Sem modelo de linguagem, responde **503** (`"O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório."`; com o relatório desligado, `"O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde."`). Com um cartão aberto na conversa, a mensagem primeiro o fecha com `cancel`.
- **Com `Accept: text/event-stream`**, a resposta vem em fluxo (veja "Resposta em fluxo"): os erros abaixo continuam saindo antes, em `ProblemDetail`; com o atalho, o fluxo traz só o `fim`.

**200**: o objeto Conversa, só com os itens do turno: `usuario`, o resumo do raciocínio do modelo (`raciocinio`), o que o Atlas disse (`texto`), o `cartao` se ele pediu números (o turno para aí), e `relatorio_pronto` se fechou o relatório. Com cartão aberto antes da mensagem, o primeiro item é o `aviso` do cartão fechado. Em fluxo: **200** `text/event-stream`, e o mesmo objeto no evento `fim`.

**Erros**

| Status | Quando |
| --- | --- |
| 400 | Corpo vazio ou que não é JSON. |
| 403 | RBS; aba fechada; falta o token CSRF. |
| 404 | Conversa que não é sua (inexistente, de outra parceria ou de outro usuário). |
| 409 | Um turno em andamento na conversa; ou, no atalho, um cartão aberto (`"Este relatório está esperando a resposta do cartão. Responda ou feche o cartão antes de montar de novo."`). Mensagem de texto com cartão aberto não dá 409: fecha o cartão. |
| 422 | Texto vazio ou com mais de 1.000 caracteres, contados antes de aparar os espaços (`campos.texto`: `"A mensagem tem no máximo 1.000 caracteres."`); texto com o caractere nulo (`\u0000`), que o banco não guarda (`campos.texto`: `"A mensagem tem caracteres inválidos."`; a conferência vem antes de gravar qualquer coisa e vale também na resposta em fluxo); nem texto nem atalho (`campos.texto`: `"Escreva a mensagem."`, ou `"Escreva a mensagem ou use o botão Montar relatório."` com o relatório ligado); os dois (`campos.atalho`: `"Envie o texto ou o atalho, e não os dois."`); atalho ou modelo desconhecido. Com o relatório desligado, qualquer `atalho` ou `modelo` (mesmo junto de `texto`): `campos.atalho` = `"O relatório não está disponível nesta versão."`. |
| 429 | O teto diário de mensagens do Atlas da parceria foi atingido (só no texto; o botão não conta): `"O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã."` (com o relatório desligado, `"... Tente amanhã."`) |
| 503 | Texto sem modelo de linguagem. |

## 45. `GET /api/atlas/conversas/{id}`  · Atlas 2

**Quem chama:** só o dono da conversa. Devolve a **conversa inteira** (o objeto Conversa com todos os itens), com cada cartão no estado de agora e o valor vigente de cada campo dele. Antes de abrir, descarta o relatório dela que ficou parado (veja "Descarte"; com o relatório desligado não descarta).

**Erros:** 401; 403 (RBS, ou aba fechada); 404 (conversa que não é sua).

## 46. `POST /api/atlas/solicitacoes/{id}/respostas`  · Atlas 2

> **Só existe com `atlas.relatorio.ligado=true`.** Desligada (o padrão), a rota não é mapeada e responde **404** em `ProblemDetail`, como uma rota que não existe, para qualquer perfil.

**Quem chama:** só o dono da conversa do cartão (exige o token CSRF). A RBS recebe **403**; cartão que não existe, é de outra parceria ou de outro usuário da mesma parceria recebe **404** (`"Não encontramos esta solicitação."`).

**Corpo**

```json
{ "acao": "accept", "valores": [ { "chave": "ativacao.visitantes_total", "valor": 1200 }, { "chave": "ativacao.cadastros_consentidos", "valor": 1072 } ] }
```

| `acao` | O que faz |
| --- | --- |
| `accept` | Grava os `valores` pelo mesmo caminho da rota 41, com origem **`ATLAS`** e as mesmas validações; o cartão vira `RESPONDIDA`. Depois o plano B preenche cada bloco redigível com a frase-modelo e **finaliza** o relatório (`PRONTO`). |
| `decline` | O cartão vira `RECUSADA`; as lacunas pedidas contam como recusadas: os números que dependem delas saem **indisponíveis**, com o motivo, nunca zero. O plano B finaliza o relatório do mesmo jeito. `valores` é ignorado. |
| `cancel` | O cartão vira `CANCELADA`, o relatório volta a **montando** e nada é gravado. O turno termina com um item `aviso` (`"Cartão fechado. Use Montar relatório para continuar."`; com o modelo de linguagem ligado, `"Cartão fechado. Quando tiver os números, peça para continuar."`); o botão continua o mesmo relatório. `valores` é ignorado. |

**200**: o objeto Conversa, só com os itens do turno. **Sem modelo de linguagem**, em `accept` e `decline`: `texto` (`"Pronto: o relatório de ativação está montado. A origem de cada número está em Fontes e cálculos."`) e `relatorio_pronto`; em `cancel`: `aviso`. **Com o modelo ligado**, depois de gravar os valores a API põe no histórico a resposta da ferramenta pendente, com o mesmo id do pedido (`{"acao": "accept", "campos": [{"chave": "ativacao.visitantes_total", "ref": "dado:ativacao.visitantes_total", "valor": 1200}]}`; `decline` e `cancel` levam `campos` vazio) e **o loop retoma**: os itens do turno são o que o modelo fez a seguir (resumo do raciocínio, texto, outro cartão, `relatorio_pronto`), e em `cancel` o `aviso` vem primeiro. Isso vale para o cartão aberto pelo botão também.

**Em fluxo** (`Accept: text/event-stream`; veja "Resposta em fluxo"): os valores são conferidos e gravados **antes** de o fluxo abrir (o 422 deles sai em `ProblemDetail`, sem gravar nada), e a retomada do modelo é transmitida até o `fim`. Sem o modelo, ou com o teto atingido, o fluxo traz só o `fim`.

**Como funciona o `accept`** [VALIDACAO] [HISTORICO]

1. Cada `chave` tem de ser um dos campos do cartão. Cada campo **obrigatório** do cartão precisa de valor: no pedido, ou já vigente.
2. Os valores vão pelo `DadoInformadoService`, o mesmo da rota 41: tipo, mínimo, máximo e os limites entre campos (cadastros não passam de visitantes). Valor igual ao vigente não grava linha nova. Tudo ou nada: com qualquer erro, nada é gravado e o cartão continua aberto.
3. Cada valor gravado entra no histórico como `CRIACAO` (entidade `dado_informado`), com `(Atlas)` no detalhe.

**Erros**

| Status | Quando |
| --- | --- |
| 400 | Corpo vazio ou que não é JSON. |
| 403 | RBS; aba fechada; falta o token CSRF. |
| 404 | Cartão que não é seu. |
| 409 | `"Este cartão já foi respondido."` (o cartão não está `PENDENTE`), ou um turno em andamento na conversa. |
| 422 | `acao` que não é `accept`, `decline` ou `cancel` (`campos.acao`: `"Use accept, decline ou cancel."`); mais de 20 valores (`campos.valores`: `"Envie até 20 valores."`). No `accept`, `campos` pela **chave do campo**: chave fora do cartão (`"Este campo não está no cartão."`), obrigatória sem valor (`"Informe este número."`) e as frases da rota 41 (`"Informe um número inteiro."`, `"O valor mínimo é 0."`, `"Não pode passar de Visitantes no espaço (1.200)."`...). |

## 47. `GET /api/atlas/relatorios/{id}`  · Atlas 2

> **Só existe com `atlas.relatorio.ligado=true`**; desligada, responde **404** como rota que não existe.

**Quem chama:** qualquer usuário da parceria do relatório (o relatório é da parceria, não de quem o criou) e a RBS. Relatório de outra parceria ou que não existe: **404** (`"Não encontramos este relatório."`). Aba `APLICACOES` fechada: **403** para a marca, **inclusive no relatório pronto**; a RBS lê sempre.

**200**: o relatório. Se está `PRONTO`, é o **snapshot** gravado na hora em que ficou pronto (congelado: nada muda depois). Se ainda está montando (ou foi descartado), é a resolução ao vivo, com o perfil de quem pede; um parágrafo ainda sem texto mostra a frase-modelo.

```json
{
  "id": 7, "parceriaId": 2, "patrocinador": "Cliente Amarelo", "modelo": "ATIVACAO_EVENTO", "titulo": "Ativação no evento", "status": "PRONTO",
  "criadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "criadoEm": "2026-10-30T10:00:00-03:00", "finalizadoEm": "2026-10-30T10:05:00-03:00",
  "selo": "Documento de trabalho da marca, montado pelo Atlas. Não validado pela RBS.",
  "secoes": [
    { "id": "aplicacoes", "titulo": "Aplicações de marca", "blocos": [
      { "id": "b-kpi-aplic", "tipo": "kpi_grade", "itens": [
        { "rotulo": "Previstas", "valor": "19", "indisponivel": null, "ref": "cons:aplicacoes.previstas" },
        { "rotulo": "Comprovação", "valor": "89,4%", "indisponivel": null, "ref": "cons:aplicacoes.percentual" } ] },
      { "id": "b-tab-aplic", "tipo": "tabela", "colunas": ["Aplicação", "Previsto", "Comprovadas", "Fotos"],
        "linhas": [["Elemento na Arena", "1", "1", "2"]], "ref": "lista:aplicacoes" } ] } ] }
```

`status`: `MONTANDO`, `AGUARDANDO_RESPOSTA` (um cartão espera), `PRONTO` ou `DESCARTADO`. `secoes` traz as seis seções do modelo (capa, resumo, aplicações, público e engajamento, comprovações, fontes e cálculos), cada uma com blocos de um **catálogo fechado**; o tipo vai em `tipo`:

| `tipo` | Campos (além de `id`) |
| --- | --- |
| `capa` | `titulo`, `subtitulo` (a marca), `edicao` (`"Planeta Atlântida 2027"`) |
| `titulo` | `texto` |
| `paragrafo` | `texto`, `redigidoPor`: `"ATLAS"` (o modelo de linguagem escreveu) ou `"MODELO"` (a frase-modelo do arquivo do modelo) |
| `kpi` | `item` |
| `kpi_grade` | `itens` |
| `tabela` | `colunas`, `linhas` (todas as células já formatadas), `ref` |
| `galeria_comprovacoes` | `itens`: `{arquivoId, nomeOriginal, aplicacao, descricao, registradoEm}` (o `arquivoId` é o que a rota 18 baixa) |
| `campo_informado` | `rotulo`, `valor`, `origem` |
| `aviso_indisponivel` | `motivo` |
| `fontes` | `itens`: `{ref, rotulo, valor, origem}` |

**Os números** saem como texto, no padrão do portal e **cortados** na primeira casa como em todo o portal: `"1.200"`, `"89,3%"` (1.072 de 1.200). Um item de KPI sem valor traz `valor: null` e `indisponivel` com o motivo (`"Não informado pela marca."`, `"Faltam dados para o cálculo: ..."`, `"Não dá para calcular: Visitantes no espaço é zero."`, `"Esta parceria não tem aplicações de marca no plano comercial."`). **Nunca zero no lugar de "sem dado".** Um parágrafo cuja frase tem uma referência sem valor sai como `aviso_indisponivel` (`"Sem os dados para escrever este texto: Visitantes no espaço e Taxa de cadastro."`); uma tabela ou galeria vazia, também.

**Fontes e cálculos.** Cada número usado no relatório aparece em `fontes` com a origem: `cons:...` &rarr; `"Consolidação do portal: plano comercial e fotos de comprovação"`; `dado:...` &rarr; `"Informado por Marketing Amarelo em 30/10/2026"` (mais `", pelo Atlas"` quando o valor veio do cartão); `calc:...` &rarr; a conta com os valores que entraram, `"pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200"`. As contas do P0 são as fórmulas fixas do modelo (`pct`); ficam gravadas em `atlas_calculo` e no snapshot.

**Erros:** 401; 403 (aba fechada, só para a marca); 404 (relatório que não existe ou é de outra parceria).

## 48. `GET /api/parcerias/{id}/atlas/relatorios`  · Atlas 2

> **Só existe com `atlas.relatorio.ligado=true`**; desligada, responde **404** como rota que não existe.

**Quem chama:** qualquer usuário da parceria e a RBS. A marca de outra parceria recebe **404**; com a aba `APLICACOES` fechada, a marca recebe **403** (a RBS lê sempre).

**200**: os relatórios do Atlas da parceria (de todos os usuários dela, em qualquer status), do mais novo para o mais antigo.

```json
[ { "id": 7, "modelo": "ATIVACAO_EVENTO", "titulo": "Ativação no evento", "status": "PRONTO",
    "criadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
    "criadoEm": "2026-10-30T10:00:00-03:00", "finalizadoEm": "2026-10-30T10:05:00-03:00" } ]
```

`finalizadoEm` é `null` enquanto o relatório não está pronto. Sem relatório nenhum: `[]`.

**Erros:** 401; 403 (aba fechada, só para a marca); 404 (parceria que não existe ou é de outra).

## Descarte do relatório parado

Um relatório `MONTANDO` ou `AGUARDANDO_RESPOSTA` sem nenhuma mensagem nova na conversa há **mais de 7 dias** vira `DESCARTADO` quando a pessoa **lista** (rota 43) ou **abre** (rota 45) as conversas. O cartão pendente dele vira `CANCELADA` e a chamada que ele deixou aberta é fechada com `cancel` no histórico (a conversa mostra o `aviso` de cartão fechado). O botão, depois disso, começa um relatório novo. Relatório pronto nunca é descartado. Descartar pela tela fica para depois.

**Histórico (rotas 42 a 46):** `CRIACAO` com a entidade `atlas_relatorio` quando o relatório nasce (`valorNovo` = `MONTANDO`), `EDICAO` quando fica pronto (`valorAnterior` = `MONTANDO`, `valorNovo` = `PRONTO`), e `CRIACAO` na entidade `dado_informado` por valor que o cartão gravou. Aparecem no histórico da parceria (rota 20) como as demais ações da marca.

---

# Diagnóstico do Atlas (marco M2)

Cada turno do Atlas (uma mensagem de texto da pessoa ou a resposta do cartão, até a resposta dele) deixa um **traço**: uma linha por etapa, com quando começou, quanto levou, como terminou e um detalhe. Serve para saber **por que** uma resposta saiu como saiu: a busca não achou, o modelo errou o caminho, a checagem recusou, a ferramenta deu erro, o tempo esgotou. A RBS lê o traço de todas as conversas (rotas 50 a 53); a marca, o das próprias (rota 49), se a propriedade permitir. As rotas 49 a 53 estão no fim desta seção. O traço fica em `atlas_traco` (V10, veja `docs/banco.md`).

## Propriedades

| Propriedade | Variável | Padrão | O que faz |
| --- | --- | --- | --- |
| `atlas.diagnostico.ligado` | `ATLAS_DIAGNOSTICO` | `false`; `true` no perfil `dev`; `false` no `test` (ligado por classe de teste) | Desligado, **nada é gravado** em `atlas_traco` e as rotas 49 a 53 respondem **404**, como uma rota que não existe (a ordem das conferências continua a de sempre: a rota da RBS dá 403 ao patrocinador antes). |
| `atlas.diagnostico.marca` | `ATLAS_DIAGNOSTICO_MARCA` | `false`; `true` no perfil `dev`; `false` no `test` | A marca vê o diagnóstico **das próprias conversas**: a rota 49 responde, `diagnosticoDisponivel` sai `true` e os itens do assistente trazem o `turno`. Só vale com o diagnóstico ligado. Desligada, a rota 49 responde 404 também ao dono da conversa. |
| `atlas.diagnostico.preco-por-milhao.entrada` e `.saida` | `ATLAS_PRECO_ENTRADA`, `ATLAS_PRECO_SAIDA` | `0.30` e `1.20` | Preço em dólares por milhão de tokens, **só** para a resposta que não traz o custo. Veja "Custo". |

## O traço de um turno

- **Um turno, um `turno`**: todas as etapas dele levam o mesmo UUID e uma `ordem` de 1 em diante (a ordem em que começaram). O traço é guardado na memória enquanto o turno anda e **gravado de uma vez no fim** (também quando o turno dá erro): nenhuma conexão com o banco fica presa durante a chamada ao modelo.
- **Etapas sequenciais** nunca se sobrepõem: cada uma começa depois de a anterior terminar, e a soma das durações delas não passa do tempo do turno (do começo da primeira ao fim da última). A única que corre em paralelo é o `RESUMO` do raciocínio, que fica fora dessa soma e pode terminar depois do turno: o traço o grava como **pendente** (`resultado` `PULADO`, `dados.pendente` `true`) e, se ele ficar pronto, atualiza a mesma linha.
- **`resultado`** de uma etapa: `OK`, `RECUSADO` (uma regra disse não), `ERRO`, `SOMBRA` (o JEV decidiu sem agir; ainda não existe) ou `PULADO` (a etapa não rodou, ou ainda não terminou). O `resultado` do **turno** é o da etapa `SAIDA`, sempre a última etapa sequencial.
- **Todo turno de texto deixa traço**, também o que nem começou: o teto diário atingido (429) e a conversa ocupada (409) deixam `G1_LIMITES` e `SAIDA` com `RECUSADO`. O botão "Montar relatório" (plano B, sem modelo) não deixa traço.
- **Nada sensível**: o `dados` de uma etapa **nunca** leva o raciocínio cru do modelo (`reasoning_content`), nem o resumo dele no `RESUMO`, nem chave de provedor, nem o prompt de sistema, nem as mensagens do histórico. Todo texto vai cortado em 20.000 caracteres, sem o caractere nulo e sem as chaves de provedor que a API conhece. Um teste procura um raciocínio, uma chave e uma frase do prompt falsos em todo JSON gravado e devolvido.

| Etapa | Quando | O que vem em `dados` |
| --- | --- | --- |
| `G1_LIMITES` | Começo do turno: o teto diário da parceria e a trava de um turno por vez | `tetoPorParceria`, `usadasHoje`, `retomada` (`true` na rota 46); `motivo` quando `RECUSADO`: `TETO_DIARIO` ou `TURNO_EM_ANDAMENTO` |
| `LLM_IDA` | Cada ida ao modelo | `ida` (1, 2, ...), `emFluxo`, `mensagens` (quantas foram ao modelo), `ferramentas` (os nomes oferecidos), `novaRedacao` (`true` na ida que refaz um texto recusado), `modelo`, `tokensEntrada`, `tokensSaida`, `motivoDoFim` (`stop`, `tool_calls`, `length`...), `latenciaMs` (da chamada ao provedor), `custoUsd` e `custoOrigem`, `chamadas` (pedidos de ferramenta da resposta), `comTexto`, `comRaciocinio` (se veio raciocínio, nunca o texto dele). Em falha: `erro` (o nome da exceção) e `detalhe` (a frase do provedor, já sem chave; **só a RBS lê**: a rota 49 não o traz) |
| `FERRAMENTA` | Cada pedido de ferramenta | `ferramenta`, `argumentos` e `resultado` (os JSON crus, como textos de até 20.000 caracteres), `pausa` (abriu o cartão), `pronto` (fechou o relatório). `ERRO` quando a ferramenta falhou (o `resultado` é o `{"erro": ...}` que voltou ao modelo). `PULADO` com `motivo` `AGUARDANDO_O_CARTAO` ou `RELATORIO_PRONTO`; `RECUSADO` com `motivo` `LIMITE_DE_CHAMADAS` |
| `CHECAGEM` | A checagem do texto do chat (número solto, termo interno, marcador) | `origem` (`RESPOSTA`, `TEXTO_COM_FERRAMENTAS` ou `FLUXO`), `caracteres`, `aprovado` e, se recusou, `motivo` (a frase da recusa). `FLUXO` é o filtro da resposta em fluxo: quando ele recusa um trecho (e a tela recebe `texto_recusado`), deixa uma `CHECAGEM` `RECUSADO` logo depois da ida, com `caracteres` = quanto do texto ele tinha conferido; a checagem do texto inteiro (`RESPOSTA` ou `TEXTO_COM_FERRAMENTAS`) vem depois e também fica no traço. Também sai quando o modelo cai depois de o filtro recusar um trecho |
| `RESUMO` | O resumo do raciocínio de uma mensagem, em paralelo | `modelo`, `tokensEntrada`, `tokensSaida`, `latenciaMs`, `custoUsd` da chamada do resumo; `caracteres` e `noPrazo` (`false` se terminou depois de o turno desistir de esperar); `motivo` `RECUSADO_PELA_CHECAGEM` (`RECUSADO`) ou `MENSAGEM_SUMIU` (`ERRO`); `erro`; `pendente` |
| `SAIDA` | O fim do turno | `tipo`: `TEXTO`, `FRASE_FIXA`, `CARTAO`, `CARTAO_ABERTO`, `RELATORIO_PRONTO`, `RECUSADO_ANTES_DO_TURNO` ou `INTERROMPIDO`; `motivo` (nas frases fixas: `TEXTO_RECUSADO`, `FALHA_DO_MODELO`, `SEM_RESPOSTA`, `PRAZO_DO_TURNO`, `LIMITE_DE_CHAMADAS`, `SEM_MODELO`); `idasAoModelo`, `chamadasDeFerramenta`, `caracteres`, `esperaDosResumosMs`, `resumosPendentes`; em fluxo, `fluxo`: `eventos` (quantos de cada tipo saíram), `primeiroTextoMs` e `clienteSaiu`. `RECUSADO` na frase de texto recusado duas vezes, no limite de chamadas e nas recusas antes do turno; `ERRO` na falha do modelo, no prazo, na resposta vazia e no turno interrompido |
| `ERRO` | Uma falha inesperada no meio do turno | `excecao` (o nome da exceção) e `mensagem` (curta e limpa, nunca a pilha; **só a RBS lê**: a rota 49 traz só `excecao`) |

As etapas `J1_INTENCAO`, `J1_CARTAO`, `BUSCA`, `R3_FONTES`, `R5_RESPOSTA`, `V2_COERENCIA`, `LACUNAS` e `DISJUNTOR` já existem no `CHECK` do banco e entram com os marcos seguintes.

## Custo

O custo de uma ida (e de um resumo) é o `usage.opencode.list_cost` que o OpenCode Go devolve em cada resposta, em dólares, a preço de lista: vai em `dados.custoUsd` da etapa, com `custoOrigem` `RESPOSTA`, e o diagnóstico o soma por turno e por conversa. O Go é uma assinatura com limite de uso, não cobra por token, e o preço dele não consta nas referências do projeto; por isso o valor que vale é o `list_cost` da própria resposta, e **não há preço fixo no código**. Se uma resposta vier sem o `list_cost`, vale o preço por milhão de tokens de `atlas.diagnostico.preco-por-milhao` (entrada e saída; os padrões, 0,30 e 1,20, são o preço de lista do mesmo modelo no OpenCode Zen, do quadro do Atlas), com `custoOrigem` `PRECO_CONFIGURADO`; com os dois em `0`, a ida fica sem `custoUsd`. É uma **estimativa**: serve para somar o gasto de uma rodada de testes, não para cobrar. O formato `usage.opencode.list_cost` foi conferido em 31/10/2026 contra uma chamada real do OpenCode Go (a etapa veio com `custoOrigem` `RESPOSTA`); uma resposta sem ele, ou num formato diferente, cai no preço configurado.

## 49. `GET /api/atlas/conversas/{id}/diagnostico`  · Atlas M2

**Quem chama:** Admin Patrocinador, **dono da conversa**, e só com `atlas.diagnostico.ligado` **e** `atlas.diagnostico.marca`. A RBS recebe **403** (`"Só o Admin Patrocinador da parceria conversa com o Atlas."`) antes de tudo; quem não entrou, 401.

**200**: os turnos da conversa, do mais antigo para o mais novo, com as etapas e os totais.

```json
{
  "conversaId": 12, "tokensEntrada": 4210, "tokensSaida": 380, "tokens": 4590, "custoUsd": 0.0031,
  "turnos": [
    {
      "turno": "7c0f2c0e-3f37-4a55-9d1e-0b6c0a8d3e11", "mensagemId": 88,
      "iniciadoEm": "2026-10-31T19:42:10-03:00", "duracaoMs": 4210, "resultado": "OK",
      "tokensEntrada": 4210, "tokensSaida": 380, "custoUsd": 0.0031,
      "etapas": [
        { "ordem": 1, "etapa": "G1_LIMITES", "resultado": "OK", "iniciadaEm": "2026-10-31T19:42:10-03:00", "duracaoMs": 3, "mensagemId": null,
          "dados": { "tetoPorParceria": 200, "usadasHoje": 12 } },
        { "ordem": 2, "etapa": "LLM_IDA", "resultado": "OK", "iniciadaEm": "2026-10-31T19:42:10-03:00", "duracaoMs": 1810, "mensagemId": 86,
          "dados": { "ida": 1, "emFluxo": true, "mensagens": 4, "ferramentas": ["parceria_consultar"], "modelo": "deepseek-v4.1-flash",
                     "tokensEntrada": 2100, "tokensSaida": 120, "motivoDoFim": "tool_calls", "latenciaMs": 1790,
                     "custoUsd": 0.0014, "custoOrigem": "RESPOSTA", "chamadas": 1, "comTexto": false, "comRaciocinio": true } },
        { "ordem": 3, "etapa": "RESUMO", "resultado": "OK", "iniciadaEm": "2026-10-31T19:42:12-03:00", "duracaoMs": 950, "mensagemId": 86,
          "dados": { "modelo": "deepseek-v4.1-flash", "tokensEntrada": 310, "tokensSaida": 40, "caracteres": 74, "noPrazo": true } },
        { "ordem": 4, "etapa": "FERRAMENTA", "resultado": "OK", "iniciadaEm": "2026-10-31T19:42:12-03:00", "duracaoMs": 42, "mensagemId": 86,
          "dados": { "ferramenta": "parceria_consultar", "argumentos": "{}", "resultado": "{\"abaFechada\":false,...}" } },
        { "ordem": 5, "etapa": "SAIDA", "resultado": "OK", "iniciadaEm": "2026-10-31T19:42:14-03:00", "duracaoMs": 12, "mensagemId": 88,
          "dados": { "tipo": "TEXTO", "idasAoModelo": 2, "chamadasDeFerramenta": 1, "caracteres": 96, "esperaDosResumosMs": 0, "resumosPendentes": 0 } }
      ]
    }
  ]
}
```

- **Turno.** `turno` é o UUID (o mesmo `turno` que os itens da conversa trazem: o botão "Diagnóstico" de uma resposta abre o turno dela). `iniciadoEm` e `duracaoMs` vão do começo da primeira etapa sequencial ao fim da última (o `RESUMO`, que corre em paralelo, fica de fora). `resultado` é o da etapa `SAIDA`: `OK`, `RECUSADO` ou `ERRO`. `mensagemId` é a última mensagem do assistente que o turno gravou. `tokensEntrada` e `tokensSaida` somam as etapas que os trazem (as idas ao modelo e os resumos do raciocínio); `custoUsd` soma o custo delas (`0` quando nenhuma resposta trouxe custo; veja "Custo"). Os totais da conversa somam os turnos.
- **Etapa.** `etapa`, `resultado` e `dados`: veja "O traço de um turno". `dados` é sempre um **objeto JSON** (não um texto) e nunca leva o raciocínio cru, chave nem o prompt de sistema. **Para a marca, o texto cru de erro também sai**: a mensagem de uma exceção pode ser SQL ou outro detalhe interno, e o corpo do erro de um provedor, o que ele quiser dizer. Na rota 49 a etapa `ERRO` traz só `excecao` (lista fechada) e as outras etapas não trazem `detalhe` (o erro do provedor em `LLM_IDA`) nem `mensagem`; o resto de cada etapa vem como foi gravado. As rotas 50 e 51, da RBS, trazem o texto, já sem chave. `mensagemId` é a mensagem do assistente que a etapa produziu ou pediu (`null` nas que não têm uma). `iniciadaEm` com o fuso de Brasília.
- **Conversa sem turno** (nunca falou, ou é de antes do diagnóstico): `turnos` vazio e os totais em `0`.

**Erros**

| Status | Quando |
| --- | --- |
| 401 | Sem sessão. |
| 403 | RBS (`"Só o Admin Patrocinador da parceria conversa com o Atlas."`); aba `APLICACOES` fechada para a marca, com as duas frases de "Aba" (como na rota 45). |
| 404 | Conversa que não existe, de outra parceria **ou de outro usuário da mesma parceria** (`"Não encontramos esta conversa."`, a mesma frase nos três casos, também para um id que não é número); ou diagnóstico desligado, ou `atlas.diagnostico.marca` desligada (a mesma frase, como se a rota não existisse para esta marca). |

## 50. `GET /api/atlas/diagnostico/turnos`  · Atlas M2

**Quem chama:** Admin RBS, com `atlas.diagnostico.ligado`. O patrocinador recebe **403** (`"Esta operação é restrita à equipe da RBS."`) com qualquer filtro; o diagnóstico desligado dá 404 (`"Não encontramos o que você pediu."`), **exceto** para `pagina` e `tamanho` inválidos, que a API confere antes de chegar à regra do diagnóstico: com o diagnóstico desligado, a RBS com `?tamanho=999` recebe 422, e não 404. Os filtros `de`, `ate` e `resultado` são conferidos depois, e aí o 404 vem primeiro.

**Filtros (todos opcionais)**

| Parâmetro | O que faz |
| --- | --- |
| `parceriaId` | Só os turnos desta parceria. Número; outro valor dá 400. |
| `de`, `ate` | O dia (inclusive, no horário de Brasília) em que o turno **começou**: `2026-10-31`. Formato errado, data que não existe ou ano fora de 0001 a 9999 (`+300000-01-01`) dá 422 em `campos.de` ou `campos.ate`; `ate` antes de `de` dá 422 em `campos.ate`. |
| `resultado` | `OK`, `RECUSADO`, `ERRO`, `SOMBRA` ou `PULADO` (em maiúsculas ou minúsculas): o resultado do **turno** (o da etapa `SAIDA`). Outro valor dá 422 em `campos.resultado`. |
| `pagina`, `tamanho` | Paginação do projeto: `pagina` de 0 em diante, `tamanho` de 1 a 200 (padrão 50); fora disso, 422. |

**200**: `{ "itens": [...], "pagina": 0, "tamanho": 50, "total": 137 }`, do turno mais novo para o mais antigo.

```json
{ "itens": [
    { "turno": "7c0f2c0e-3f37-4a55-9d1e-0b6c0a8d3e11", "conversaId": 12, "parceriaId": 2, "parceria": "Cliente Amarelo",
      "usuario": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
      "mensagemId": 88, "iniciadoEm": "2026-10-31T19:42:10-03:00", "duracaoMs": 4210, "resultado": "OK", "etapas": 6,
      "tokensEntrada": 4210, "tokensSaida": 380, "custoUsd": 0.0031 } ],
  "pagina": 0, "tamanho": 50, "total": 1 }
```

`etapas` é a quantidade (a lista não traz as etapas: abra o turno pela rota 51). O turno que nem começou (teto diário atingido, conversa ocupada) também aparece, com `resultado` `RECUSADO`. Os campos são os mesmos da rota 51.

**Erros:** 400 (`parceriaId` que não é número); 401; 403 (patrocinador); 404 (diagnóstico desligado); 422 (filtro ou paginação).

## 51. `GET /api/atlas/diagnostico/turnos/{turno}`  · Atlas M2

**Quem chama:** Admin RBS, com o diagnóstico ligado. O mesmo turno da rota 49, mais a parceria, a conversa e quem conversou.

**200**

```json
{ "turno": "7c0f2c0e-3f37-4a55-9d1e-0b6c0a8d3e11", "conversaId": 12, "parceriaId": 2, "parceria": "Cliente Amarelo",
  "usuario": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "mensagemId": 88, "iniciadoEm": "2026-10-31T19:42:10-03:00", "duracaoMs": 4210, "resultado": "OK",
  "tokensEntrada": 4210, "tokensSaida": 380, "custoUsd": 0.0031,
  "etapas": [ { "ordem": 1, "etapa": "G1_LIMITES", "resultado": "OK", "iniciadaEm": "...", "duracaoMs": 3, "mensagemId": null, "dados": {} } ] }
```

**Erros:** 401; 403 (patrocinador); 404 (`"Não encontramos este turno."`: turno que não existe ou id que não é um UUID; ou, com o diagnóstico desligado, `"Não encontramos o que você pediu."`).

## 52. `GET /api/atlas/provedores`  · Atlas M2

**Quem chama:** Admin RBS, com o diagnóstico ligado. Mostra o estado de cada provedor externo e o teto diário.

**200**

```json
{ "provedores": [
    { "nome": "llm", "configurado": true, "origem": "AMBIENTE", "modelo": "deepseek-v4.1-flash",
      "baseUrl": "https://opencode.ai/inference/go/openai/v1", "finalDaChave": "a1b2", "modo": null,
      "chamadas24h": 120, "erros24h": 2, "latenciaP50Ms": 1400, "latenciaP95Ms": 3100, "disjuntor": null },
    { "nome": "jev", "configurado": true, "origem": "AMBIENTE", "modelo": "jev-1.14.0", "baseUrl": "https://api.typesafe.ai",
      "finalDaChave": "c3d4", "modo": "SOMBRA", "chamadas24h": 0, "erros24h": 0, "latenciaP50Ms": null, "latenciaP95Ms": null, "disjuntor": null },
    { "nome": "gemini", "configurado": false, "origem": "NENHUM", "modelo": "gemini-embedding-2",
      "baseUrl": "https://generativelanguage.googleapis.com", "finalDaChave": null, "modo": null,
      "chamadas24h": 0, "erros24h": 0, "latenciaP50Ms": null, "latenciaP95Ms": null, "disjuntor": null } ],
  "tetoDiario": { "limite": 200, "parcerias": [ { "parceriaId": 2, "parceria": "Cliente Amarelo", "usadas": 12 } ] } }
```

- **Sempre os três, nesta ordem:** `llm`, `jev`, `gemini`.
- `configurado` é `true` quando há chave. `origem`: `AMBIENTE` (a chave vem de variável de ambiente ou do arquivo de segredos do perfil `dev`), `DEBUG` (só o `llm`: a chave posta pelas rotas de debug, que vale antes da do ambiente) ou `NENHUM`. A chave: `OPENCODE_API_KEY` (`llm`), `TYPESAFE_API_KEY` (`jev`) e `GEMINI_API_KEY` (`gemini`). As URLs base e os modelos são configuráveis (`ATLAS_LLM_BASE_URL`/`ATLAS_LLM_MODELO`, `ATLAS_JEV_BASE_URL`/`ATLAS_JEV_MODELO`, `ATLAS_GEMINI_BASE_URL`).
- **Nunca a chave inteira**: `finalDaChave` traz só os 4 últimos caracteres, e só de chave com 8 ou mais; senão `null`.
- `modo` é o do JEV (`ATLAS_JEV`): `DESLIGADO` (`none`, o padrão), `SOMBRA` ou `ATIVO`; `null` nos outros dois.
- `chamadas24h`, `erros24h`, `latenciaP50Ms` e `latenciaP95Ms` vêm do traço das últimas 24 horas: as etapas `LLM_IDA` (`llm`), `J1_INTENCAO` e `J1_CARTAO` (`jev`) e `BUSCA` (`gemini`). A latência é das que terminaram bem; sem nenhuma, `null`. Por enquanto só o `llm` deixa traço.
- `disjuntor` é **`null` até o M8** (o estado do disjuntor de cada provedor).
- `tetoDiario`: o `limite` (`atlas.teto-diario-por-parceria`) e, das parcerias que gastaram alguma mensagem do modelo **hoje** (dia de Brasília), quantas `usadas`, da que mais gastou para a que menos.

**Erros:** 401; 403 (patrocinador); 404 (diagnóstico desligado).

## 53. `POST /api/atlas/provedores/{nome}/teste`  · Atlas M2

**Quem chama:** Admin RBS, com o diagnóstico ligado (exige o token CSRF). `nome` é `llm`, `jev` ou `gemini` (em qualquer caixa). Sem corpo. Faz **uma chamada real mínima** ao provedor, só para provar que a conexão e a chave funcionam; não grava nada e não conta no teto diário. O cliente de verdade do JEV vem no M7 e o do Gemini no M3.

| Provedor | O que a API chama |
| --- | --- |
| `llm` | O modelo que vale agora (como o `POST /api/debug/llm/teste`): a mensagem `"Responda só com a palavra ok."`, sem ferramentas. |
| `jev` | `POST {base}/v1/systemone` com `Authorization: Bearer` e o modelo `jev-1.14.0` (`ATLAS_JEV_MODELO`): um estado curto em português e uma pergunta `noul` (`"A mensagem pergunta sobre um prazo?"`). Dá certo se a resposta traz `answers.relevante.noul` numérico. |
| `gemini` | `POST {base}/v1beta/models/gemini-embedding-2:embedContent` com o cabeçalho `x-goog-api-key` e `embedContentConfig.outputDimensionality` 768. Dá certo se a resposta traz o vetor de 768 números. |

**200** sempre que a rota existe: `ok` diz se deu certo.

```json
{ "provedor": "jev", "ok": true, "chamou": true, "milissegundos": 240, "modelo": "jev-1.14.0", "resposta": "noul 0.95" }
```

```json
{ "provedor": "gemini", "ok": false, "chamou": true, "milissegundos": 310, "modelo": "gemini-embedding-2",
  "erro": "O provedor respondeu 400: API key not valid. Please pass a valid API key." }
```

- Com `ok` `true` vem `resposta` (no `llm`, a resposta do modelo cortada em 200 caracteres; no `jev`, `noul` e o valor; no `gemini`, `768 dimensões`); com `false`, `erro`. Nunca os dois.
- **Sem chave**, nada é chamado: `{ "provedor": "jev", "ok": false, "chamou": false, "milissegundos": 0, "modelo": "jev-1.14.0", "erro": "Nenhuma chave configurada." }`.
- **O erro nunca traz a chave**, mesmo que o provedor a repita no corpo da resposta (ela vem como `[omitido]`). Falha de rede diz só o nome da exceção: `"Não consegui falar com o provedor (ConnectException)."`. Resposta que não é a esperada: `"O provedor respondeu 200, mas não no formato esperado."`.
- O prazo é de 5 segundos para conectar e 20 para a chamada; o HTTP não segue redirecionamento (a chave vai só ao endereço configurado).

**Erros:** 401; 403 (patrocinador, ou falta o token CSRF); 404 (`"Não encontramos este provedor."`, ou o diagnóstico desligado).

---

# Rotas de debug (temporárias)

Um recurso **temporário**, para a equipe ligar o modelo de linguagem do Atlas pela tela (o botão "Debug"), sem reiniciar a API e sem variável de ambiente. Não faz parte das 53 rotas do contrato e sai antes da entrega final.

- **Só existem com `atlas.debug.chaves=true`.** A propriedade vem `false` por padrão, `true` no perfil `dev` e `false` no `test`. Sem ela, as quatro rotas respondem **404**, como uma rota que não existe.
- **A chave fica só na memória da API.** Nunca vai ao banco, a arquivo ou ao log, e **some quando a API reinicia**. Nenhuma resposta devolve a chave inteira: só os 4 últimos caracteres (`finalDaChave`).
- **Precedência:** a chave posta aqui &rarr; a das variáveis de ambiente (`ATLAS_LLM=openai` e `OPENCODE_API_KEY`) &rarr; nenhuma (o Atlas fica no botão "Montar relatório"). Pôr ou apagar a chave muda na hora o `llmDisponivel` das rotas 42 a 46; um turno que já começou termina com o modelo com que começou.
- O modelo montado com a chave usa as mesmas opções do `application.yml` (temperatura 0.2, 1 nova tentativa, 60 segundos de limite, o `User-Agent` `PlanetaAtlas/0.1` e o `x-opencode-session` por conversa) e o raciocínio (`thinking`) do `atlas.raciocinio` (`ATLAS_RACIOCINIO`, ligado por padrão), que o `PUT` pode trocar.
- **Quem chama:** **só o Admin RBS** (é ferramenta da equipe em desenvolvimento). O patrocinador recebe **403** (`"Esta operação é restrita à equipe da RBS."`) em qualquer das quatro, com qualquer corpo, sem mexer na chave. PUT, DELETE e POST exigem o token CSRF. Erros em `ProblemDetail`.
- Cada troca grava uma linha de log (INFO) com quem trocou, a origem, a base URL, o modelo, o final da chave e o raciocínio.
- Existe também o **debug das ferramentas** (`atlas.debug.ferramentas`), que não é uma rota: veja "Debug das ferramentas", no fim desta seção.

## `GET /api/debug/llm`

**200**: o modelo que vale agora.

```json
{ "origem": "DEBUG", "baseUrl": "https://opencode.ai/inference/go/openai/v1", "modelo": "deepseek-v4.1-flash",
  "finalDaChave": "a1b2", "configuradoPor": { "id": 1, "nome": "Equipe B2B RBS", "perfil": "ADMIN_RBS" },
  "configuradoEm": "2026-10-29T21:00:00-03:00", "llmDisponivel": true, "raciocinio": true }
```

`raciocinio` diz se o modelo que vale agora vai com o raciocínio ligado (`"thinking": {"type": "enabled"}`): em `DEBUG`, o da chave posta; em `AMBIENTE` e `NENHUM`, o do `atlas.raciocinio`.

| `origem` | Quando | O resto |
| --- | --- | --- |
| `DEBUG` | Há uma chave posta por esta rota. | Todos os campos preenchidos; `llmDisponivel` `true`. |
| `AMBIENTE` | Sem chave de debug, mas a API subiu com o modelo das variáveis de ambiente. | `configuradoPor` e `configuradoEm` `null`; `baseUrl` e `modelo` os do `application.yml`; `llmDisponivel` `true`. |
| `NENHUM` | Nenhum modelo. | `baseUrl` e `modelo` trazem os padrões do `application.yml`; `finalDaChave`, `configuradoPor` e `configuradoEm` `null`; `llmDisponivel` `false`. |

**Erros:** 401; 403 (patrocinador).

## `PUT /api/debug/llm`

**Corpo:** `{ "chave": "...", "baseUrl": "...", "modelo": "...", "raciocinio": true }`. `baseUrl` e `modelo` são opcionais: ausentes, `null` ou vazios, valem os padrões do `application.yml`. `raciocinio` (`true` ou `false`) também: ausente ou `null`, fica o que vale agora (o da chave do debug já posta ou, sem ela, o `atlas.raciocinio`). Troca a chave anterior, se houver. A API não chama o provedor ao receber a chave (use `/teste` para isso).

**200**: o mesmo corpo do GET, já com `origem` `DEBUG`.

**Erros**

| Status | Quando |
| --- | --- |
| 400 | Corpo vazio ou que não é JSON; `raciocinio` que não é `true` nem `false`. |
| 403 | Patrocinador; falta o token CSRF. |
| 422 | `campos.chave`: ausente, com menos de 8 ou mais de 500 caracteres, ou com espaço (`"A chave precisa ter de 8 a 500 caracteres, sem espaços."`). `campos.baseUrl`: não começa com `https://` e não é `http://localhost` nem `http://127.0.0.1` (com porta e caminho opcionais), ou passa de 500 caracteres (`"A base URL precisa começar com https:// (ou ser http://localhost ou http://127.0.0.1)."`). `campos.modelo`: mais de 100 caracteres ou caractere fora de letras, números e `._:/-` (`"O modelo tem até 100 caracteres: letras, números e . _ : / -"`). |

## `DELETE /api/debug/llm`

Esquece a chave do debug: volta ao modelo das variáveis de ambiente, ou a nenhum. **204** sem corpo, também quando não havia chave. **Erros:** 401; 403 (patrocinador, ou falta o token CSRF).

## `POST /api/debug/llm/teste`

Manda ao modelo que vale agora uma mensagem mínima, sem ferramentas (`"Responda só com a palavra ok."`), e diz se ele respondeu. Sem corpo. Não grava nada e **não conta para o teto diário** do Atlas.

**200** sempre que a rota existe:

```json
{ "ok": true, "milissegundos": 812, "modelo": "deepseek-v4.1-flash", "resposta": "ok" }
```

```json
{ "ok": false, "milissegundos": 1500, "modelo": "deepseek-v4.1-flash", "erro": "O modelo de linguagem não respondeu (UnauthorizedException: 401: ...)." }
```

`resposta` vem cortada em 200 caracteres. `erro` é o motivo, sempre sem a chave. Sem modelo nenhum: `{ "ok": false, "milissegundos": 0, "modelo": "<o padrão>", "erro": "Nenhuma chave configurada." }`. Com `ok` `true` não vem `erro`; com `false` não vem `resposta`. É o mesmo teste da rota 53 para o provedor `llm`.

**Erros:** 401; 403 (patrocinador, ou falta o token CSRF).

## Debug das ferramentas (`atlas.debug.ferramentas`)

Também **temporário**: mostra os comandos que o agente usa, para quem desenvolve ver o que ele consultou. Vale para os dois modos do relatório.

| Propriedade | Variável | Padrão |
| --- | --- | --- |
| `atlas.debug.ferramentas` | `ATLAS_DEBUG_FERRAMENTAS` | `false`; `true` no perfil `dev`; `false` no `test` |

Ligada:

- **O objeto Conversa** (rotas 42, 44, 45 e 46, e o `fim` do fluxo) traz `"debugDeFerramentas": true`. Desligada: `false`.
- **Item `ferramenta`** nas rotas 42, 44, 45 e 46 e no `fim`, um por pedido de ferramenta, na ordem em que a ferramenta rodou, **depois** do `raciocinio` e do `texto` da mesma mensagem do assistente:

```json
{ "tipo": "ferramenta", "nome": "parceria_consultar", "argumentos": "{}", "resultado": "{\"abaFechada\":false,...}", "erro": false, "duracaoMs": 42 }
```

  - `argumentos` e `resultado` são **strings** com o JSON cru, como o modelo enviou e como o modelo recebeu (o que o histórico guarda), cortadas em **20.000 caracteres**. `resultado` é `null` se a ferramenta ainda não respondeu (o cartão que espera a resposta). Uma ferramenta que deu erro mostra o erro cru (`{"erro": "..."}`) e `erro` `true`.
  - `duracaoMs` vem da trilha `atlas_tool_call` (o mesmo tempo que a API grava); é `null` se a ferramenta não tem registro. As chamadas do botão (plano B) também aparecem, se estiverem no histórico da conversa.
  - Com o relatório desligado, as chamadas `relatorio_*` que estão no histórico de uma conversa antiga aparecem do mesmo jeito, mas o `cartao` e o `relatorio_pronto` continuam sem sair.
- **Evento `ferramenta` no fluxo** (rotas 44 e 46), com o mesmo corpo do item, sem o `tipo`: logo que a ferramenta termina e antes da próxima ida ao modelo. O evento `etapa` não muda.
- Desligada: nenhum item nem evento `ferramenta`, e nada disso muda o que vai ao modelo.

**O raciocínio cru continua nunca saindo do servidor**, também por aqui. O debug mostra só o que a própria ferramenta já devolveu para aquela conversa, que é só do usuário que a abriu, com a consulta limitada à parceria e ao perfil dele; a chave do modelo não aparece em lugar nenhum.

---

# Regras que valem para todas as rotas

## Isolamento entre parcerias

[ISOLAMENTO] A parceria é a unidade de isolamento: tudo o que um patrocinador vê pende dela.

- Toda rota que recebe o id de uma parceria (ou de algo que pertence a uma: entrega, arquivo, pergunta, relatório) confere, **a cada chamada**, se o usuário tem vínculo vigente com ela. A conferência vai ao banco toda vez e não é guardada na sessão. Por isso, quando a RBS revoga um e-mail, o acesso some na hora, mesmo para quem já estava logado.
- Quem não tem vínculo recebe **404**, o mesmo erro de um id que não existe. Assim um patrocinador não descobre, pelo código de erro, quais parcerias existem.
- A RBS passa em qualquer parceria que exista. Id que não existe é 404 para todos.
- O `403` é para outra situação: o usuário **tem** acesso à parceria, mas o perfil dele não pode fazer aquela operação (rota só da RBS), ou a aba está fechada para a parceria (fora da cota, ou ainda não liberada; veja "Aba"). Nas rotas só da RBS o perfil é conferido **primeiro**: o patrocinador recebe 403 com qualquer id (da parceria dele, de outra ou que não existe) e com **qualquer corpo ou filtro** (corpo vazio, JSON quebrado, campo inválido, `?tamanho=999`...). O 403 nunca revela se o id existe nem como é o formato do corpo da rota. As rotas só da RBS são as marcadas "Admin RBS" na tabela do início deste arquivo.

## Permissões por perfil

| Quem | Pode |
| --- | --- |
| **Admin RBS** | Quase tudo: criar parcerias, autorizar e revogar e-mails, criar entregas, mudar status, mandar retorno, aprovar perguntas, subir cargas (inclusive o plano comercial), anexar as fotos de comprovação das aplicações, informar os dados da ativação no lugar da marca, gerar e disponibilizar relatórios, ver o histórico geral e o painel. **Não** conversa com o Atlas (rotas 42 a 46 e 49 dão 403), mas lê os relatórios que ele montou (rotas 47 e 48) e, com o diagnóstico ligado, o traço de todos os turnos, o estado dos provedores e o teste deles (rotas 50 a 53). Usa as rotas de debug da chave do modelo (só dela). |
| **Admin Patrocinador** | Ver a própria parceria, as entregas das abas liberadas, enviar materiais, baixar arquivos da parceria, ver o histórico dela, enviar as perguntas da pesquisa, ver a consolidação, o plano de aplicações com as fotos e informar os dados da ativação (aba `APLICACOES` aberta), conversar com o Atlas e montar o relatório de ativação pelo botão ou, com o modelo de linguagem ligado, pelo texto livre (rotas 42 a 46, só ela; a conversa é de quem a abriu), ver os relatórios do Atlas da parceria (rotas 47 e 48), o diagnóstico das próprias conversas (rota 49, só com `atlas.diagnostico.marca` ligado) e o relatório disponibilizado. **Nunca** vê outra parceria nem as rotas da RBS. |

## O que a API grava no histórico

[HISTORICO] Toda alteração relevante grava uma linha com **quem** fez, **quando**, **de que IP e navegador**, a **parceria**, a **entidade** e, quando há, o valor anterior e o novo. O histórico só aceita inserir: o banco recusa alterar ou apagar uma linha. A linha é gravada na mesma transação da alteração, então ou grava as duas coisas ou nenhuma.

## Mudanças em relação ao que o front já usava

Nenhuma nas rotas de autenticação. Três observações:

- `cota.nome` vem como no banco (`"Master"`, `"Subcota G"`, `"Subcota"`), e não `"Cota Master"` como no exemplo do contrato do front. O `codigo` é o mesmo.
- `POST /api/auth/ativar` aceita um campo extra opcional, `nome` (rota 5). O front pode ignorá-lo.
- `GET /api/auth/csrf` devolve o token **bruto**, o mesmo valor do cookie `XSRF-TOKEN`, que é o que o cabeçalho `X-XSRF-TOKEN` aceita.

## Pendências e decisões a confirmar

- **[PENDENTE]** Lista de extensões aceitas no envio de material (rota 17) e o limite de tamanho (20 MB), com a RBS.
- **[PENDENTE]** Se a marca vê o previsto x exibido antes de o relatório ser validado (rota 29).
- **[PENDENTE]** Quem aprova as perguntas da pesquisa: qualquer Admin RBS, ou só o time de pesquisa (rota 25).
- **[PENDENTE]** Quem envia as fotos de comprovação das aplicações: só a RBS (como está, rota 38) ou a marca também.
- **[PENDENTE]** Se os dados que a marca informa (rota 41) precisam de aprovação da RBS antes de aparecer. Hoje entram direto, com autor, data e origem à vista.
- **[PENDENTE]** Limite de tentativas de login por IP e por e-mail. Hoje a falha só fica registrada no histórico.
- **[PENDENTE]** Em produção (HTTPS): marcar o cookie de sessão como `Secure` e enviar o token CSRF só por conexão segura.
- **[PENDENTE]** O modelo de linguagem do Atlas (OpenCode Go) é para o ambiente de testes, com dados fictícios: os termos de uso do serviço (uso interno, não em benefício de terceiros) e a retenção de dados do modelo escolhido precisam ser confirmados antes de qualquer uso além do teste. Os testes da suíte nunca chamam o provedor; o teste com a chave de verdade é manual.
- **[PENDENTE]** Se a RBS precisa aprovar o relatório do Atlas antes de a marca usá-lo (hoje é documento de trabalho da marca, com o selo "Não validado pela RBS") e se a RBS também conversa com o Atlas (hoje só lê).
