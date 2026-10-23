# API do Planeta 360 · Portal do Patrocinador

Este documento é o **contrato entre o back-end e o front-end**. Quem faz a tela lê aqui o que cada rota pede, o que devolve e quais erros esperar, sem precisar abrir o código.

- As rotas marcadas com **etapa 3** já existem e estão testadas. As das **etapas 4 a 7** estão definidas aqui para o front poder trabalhar em paralelo; o formato pode receber ajustes pequenos, e toda mudança será avisada e registrada neste arquivo.
- O banco está descrito em [`docs/banco.md`](banco.md). Os nomes de status, cotas e abas são os mesmos do banco.

## Como falar com a API

| Assunto | Regra |
| --- | --- |
| Endereço | Todas as rotas começam com `/api`. Em desenvolvimento a API roda em `http://localhost:8080` e o Vite repassa `/api` para ela, então front e API ficam na **mesma origem** (o cookie de sessão não precisa de CORS). |
| Formato | JSON em UTF-8, nomes de campo em `camelCase`. Os únicos arquivos que não são JSON são os downloads, os CSV e os envios (`multipart/form-data`). |
| Datas | Data e hora em ISO 8601 **com fuso**: `"2026-10-18T10:15:30-03:00"`. Data sem hora (prazo, início do evento): `"2026-12-16"`. |
| Números | Números JSON. Dinheiro e quantidades têm até 2 casas decimais (`1000000.00`). Percentuais vão de 0 a 100, com 1 casa (`98.3`). |
| Campos vazios | Um campo sem valor vem como `null`. As listas vêm sempre como `[]`, nunca `null`. |
| Códigos | Cotas: `MASTER`, `SUBCOTA_G`, `SUBCOTA`. Abas: `PERFIL`, `APLICACOES`, `MIDIA`, `CORTESIAS`, `CREDENCIAMENTO`, `PESQUISA`, `CONTRATO`. Perfis: `ADMIN_RBS`, `ADMIN_PATROCINADOR`. |
| Listas longas | Histórico, fila de entregas, cargas e rejeições são paginadas: `?pagina=0&tamanho=50` (pagina começa em 0; tamanho padrão 50, máximo 200). A resposta é `{"itens": [...], "pagina": 0, "tamanho": 50, "total": 137}`. As outras listas vêm inteiras, em array. |
| Filtros de data | `de=2026-10-01&ate=2026-10-31` (datas, inclusive, no horário de Brasília). |

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
| 400 | Requisição inválida | Corpo vazio ou que não é JSON. |
| 401 | Não autenticado | Sem sessão (ou sessão vencida), ou e-mail/senha incorretos no login. |
| 403 | Acesso negado | Logado, mas o perfil não pode fazer a operação (rota só da RBS), a aba ainda não foi liberada para a parceria, ou falta o token CSRF. |
| 404 | Não encontrado | O recurso **não existe ou é de outra parceria**. Os dois casos têm a mesma resposta, de propósito. Vale também para rota inexistente. |
| 405 | Método não permitido | A rota existe, mas não aceita esse método HTTP. |
| 409 | Conflito | Uma regra do negócio recusou: regra do banco (SQLSTATE da classe 23, com a frase do gatilho), transição de status que não é permitida, registro duplicado. |
| 413 | Arquivo grande demais | Envio acima de 20 MB. |
| 415 | Tipo de conteúdo não aceito | Extensão ou tipo de arquivo fora da lista permitida, ou `Content-Type` errado. |
| 422 | Dados inválidos | Campo faltando ou fora do formato. Vem com `campos`. |
| 500 | Erro interno | Falha inesperada. O `detail` é sempre genérico. |

## Sessão e CSRF

O login abre uma **sessão no servidor**, identificada pelo cookie `JSESSIONID` (`HttpOnly`, `SameSite=Lax`, vale 8 horas sem uso). O JavaScript não lê esse cookie; o navegador o envia sozinho. Não existe token JWT.

Toda chamada que **altera** dados (`POST`, `PUT`, `PATCH`, `DELETE`) precisa de proteção contra CSRF:

1. O servidor grava o cookie `XSRF-TOKEN`, **legível pelo JavaScript**.
2. O front copia o valor desse cookie para o cabeçalho `X-XSRF-TOKEN` em cada chamada que altera dados.
3. Sem o cabeçalho, ou com valor diferente do cookie, a resposta é **403** com `detail` falando do token CSRF.

O cookie nasce na primeira resposta da API. Se ele não existir (primeira visita, ou depois do logout, que o apaga), o front chama `GET /api/auth/csrf`, que devolve o token e grava o cookie. A resposta já traz o valor pronto para usar no cabeçalho.

Roteiro de uso:

```text
1. GET  /api/auth/csrf        -> {"cabecalho":"X-XSRF-TOKEN","token":"..."} (e o cookie XSRF-TOKEN)
2. POST /api/auth/login       -> cabeçalho X-XSRF-TOKEN: <token>; corpo com e-mail e senha; responde o UsuarioLogado
3. (chamadas normais)         -> o navegador envia o cookie JSESSIONID sozinho; nos POST/PUT/PATCH/DELETE vai o X-XSRF-TOKEN
4. POST /api/auth/logout      -> 204; a sessão acaba
```

No `fetch`, use sempre `credentials: "same-origin"` (ou `"include"`, se front e API estiverem em origens diferentes). Se uma chamada voltar **401** no meio do uso, a sessão terminou (venceu, foi encerrada ou a conta perdeu o acesso): leve o usuário à tela de login.

O login mantém o cookie `XSRF-TOKEN`; o **logout o apaga** (junto com o `JSESSIONID`). Depois de sair, chame `GET /api/auth/csrf` de novo antes do próximo POST.

## As rotas em uma tabela

Etapa 3 = pronta e testada. Etapas 4 a 7 = definidas aqui, ainda por implementar.

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
| 9 | `GET /api/parcerias/{id}` | Patrocinador da parceria | Dados da parceria, abas e e-mails | 4 |
| 10 | `PATCH /api/parcerias/{id}` | Admin RBS | Edita o Perfil e libera abas | 4 |
| 11 | `GET /api/parcerias/{id}/emails` | Admin RBS | Lista os e-mails autorizados | 4 |
| 12 | `POST /api/parcerias/{id}/emails` | Admin RBS | Autoriza um e-mail e gera o código de ativação | 4 |
| 13 | `DELETE /api/parcerias/{id}/emails/{emailId}` | Admin RBS | Revoga o e-mail e tira o acesso | 4 |
| 14 | `GET /api/parcerias/{id}/entregas` | Patrocinador da parceria | Lista as entregas, por aba | 5 |
| 15 | `POST /api/parcerias/{id}/entregas` | Admin RBS | Cria uma entrega | 5 |
| 16 | `GET /api/entregas` | Admin RBS | Fila de entregas de todas as parcerias, com filtros | 5 |
| 17 | `POST /api/entregas/{id}/arquivos` | Patrocinador da parceria | Envia um arquivo (a RBS envia o retorno) | 5 |
| 18 | `GET /api/arquivos/{id}` | Patrocinador da parceria | Baixa um arquivo, como anexo | 5 |
| 19 | `PATCH /api/entregas/{id}/status` | Admin RBS | Muda o status da entrega, com observação | 5 |
| 20 | `GET /api/parcerias/{id}/historico` | Patrocinador da parceria | Histórico da parceria | 5 |
| 21 | `GET /api/historico` | Admin RBS | Histórico geral, com filtros | 5 |
| 22 | `GET /api/historico.csv` | Admin RBS | O mesmo histórico, em CSV | 5 |
| 23 | `GET /api/parcerias/{id}/pesquisa` | Patrocinador da parceria | Perguntas e resultados da pesquisa | 6 |
| 24 | `PUT /api/parcerias/{id}/pesquisa/perguntas` | Admin Patrocinador da parceria | Envia as até 3 perguntas | 6 |
| 25 | `PATCH /api/pesquisa/perguntas/{id}/status` | Admin RBS | Aprova a pergunta ou pede ajuste | 6 |
| 26 | `POST /api/cargas` | Admin RBS | Envia um CSV de veiculação ou de pesquisa | 6 |
| 27 | `GET /api/cargas` | Admin RBS | Lista as cargas feitas | 6 |
| 28 | `GET /api/cargas/{id}/rejeicoes` | Admin RBS | Linhas que não entraram na carga, com o motivo | 6 |
| 29 | `GET /api/parcerias/{id}/consolidacao` | Patrocinador da parceria | Previsto x exibido e faltantes | 6 |
| 30 | `GET /api/painel` | Admin RBS | Números do painel da RBS | 6 |
| 31 | `GET /api/relatorios` | Admin RBS | Situação do relatório de cada parceria | 7 |
| 32 | `GET /api/relatorios.csv` | Admin RBS | A mesma lista, em CSV | 7 |
| 33 | `POST /api/parcerias/{id}/relatorios` | Admin RBS | Gera uma nova versão do relatório | 7 |
| 34 | `PATCH /api/relatorios/{id}/status` | Admin RBS | Valida ou disponibiliza o relatório | 7 |
| 35 | `GET /api/parcerias/{id}/relatorio` | Patrocinador da parceria | Relatório da parceria (o patrocinador só vê o disponibilizado) | 7 |
| 36 | `GET /api/relatorios/{id}/csv` | Patrocinador da parceria | Um relatório em CSV | 7 |

São 36 operações (método + caminho). A rota 5 (`ativar`) faz parte do contrato de autenticação, mas só é implementada na etapa 4.

## Objetos que se repetem

Estes formatos aparecem em várias rotas.

**Usuário resumido** (quem fez algo): `{ "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" }`

**Cota**: `{ "codigo": "MASTER", "nome": "Master" }` (o `nome` é o do banco: Master, Subcota G, Subcota)

**Evento**: `{ "id": 2, "nome": "Planeta Atlântida", "edicao": 2027 }`

**Aba**: `{ "codigo": "PESQUISA", "nome": "Pesquisa da ativação", "ordem": 6, "liberada": true }`. Sempre as 7 abas, em ordem. `liberada: false` quando a cota ainda não libera a aba; o front mostra o cadeado. Uma aba só conta como liberada se estiver liberada **e** visível no banco.

**Arquivo** (um envio, uma versão):

```json
{
  "id": 12, "entregaId": 31, "nomeOriginal": "logo_final.pdf", "mimeType": "application/pdf",
  "tamanhoBytes": 482113, "versao": 2, "categoria": "MATERIAL_MARCA",
  "enviadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "enviadoEm": "2026-10-17T18:03:00-03:00"
}
```

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
        { "codigo": "PERFIL", "nome": "Perfil do cliente", "ordem": 1, "liberada": true },
        { "codigo": "APLICACOES", "nome": "Aplicações de marca no evento", "ordem": 2, "liberada": true },
        { "codigo": "MIDIA", "nome": "Mídia e conteúdo", "ordem": 3, "liberada": true },
        { "codigo": "CORTESIAS", "nome": "Cortesias", "ordem": 4, "liberada": true },
        { "codigo": "CREDENCIAMENTO", "nome": "Credenciamento", "ordem": 5, "liberada": true },
        { "codigo": "PESQUISA", "nome": "Pesquisa da ativação", "ordem": 6, "liberada": true },
        { "codigo": "CONTRATO", "nome": "Contrato", "ordem": 7, "liberada": true }
      ]
    }
  ]
}
```

- `perfil` é `ADMIN_PATROCINADOR` ou `ADMIN_RBS`.
- `parcerias` lista as parcerias a que o usuário tem vínculo, da edição mais nova para a mais antiga. Para o `ADMIN_RBS` vem **vazia**, porque a RBS enxerga todas (use `GET /api/parcerias`).
- `abas` traz sempre as 7 abas, em ordem; `liberada` é `false` quando a cota não libera a aba (por exemplo, a Pesquisa em cota que não é Master).
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
| 401 | Sem sessão: `{"title":"Não autenticado","detail":"Entre no portal para continuar."}`. Também quando a sessão existe, mas a conta foi desativada ou perdeu todos os vínculos (acesso revogado) depois do login: a sessão é encerrada na hora e o `detail` é `"Sua sessão terminou. Entre de novo."`. |

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

Depois de **5 códigos errados** para o mesmo e-mail, o código é bloqueado e a RBS precisa gerar outro (`POST /api/parcerias/{id}/emails`).

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

**201** (com o cabeçalho `Location: /api/parcerias/{id}`): o objeto da rota 9, em que cada e-mail autorizado traz também **`codigoAtivacao`** e **`codigoExpiraEm`**. O código só aparece **nesta resposta**; depois não há como ver de novo (só gerar outro). A RBS repassa o código ao patrocinador.

**Erros**

| Status | Quando |
| --- | --- |
| 403 | Não é Admin RBS, ou faltou o token CSRF. |
| 409 | O patrocinador já tem parceria nesta edição (`"Este patrocinador já tem parceria nesta edição do evento."`), ou já existe um patrocinador com este nome (use o `id`). |
| 422 | Campo faltando, evento/cota/patrocinador que não existem, valor sem base, e-mail inválido (`campos`). |

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
  "abas": [ { "codigo": "PERFIL", "nome": "Perfil do cliente", "ordem": 1, "liberada": true } ],
  "emailsAutorizados": [
    { "id": 5, "email": "marketing@amarelo.example", "vinculo": "MARCA", "situacao": "ATIVO" },
    { "id": 6, "email": "atendimento@agencia-x.example", "vinculo": "AGENCIA", "situacao": "ATIVO" }
  ]
}
```

- `abas` traz as 7 abas, com `liberada` (o exemplo mostra uma só para ficar curto).
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

**200**: o objeto da rota 9, já com as mudanças. **Erros:** 403, 404, 409 (nome de patrocinador repetido), 422 (`valorInvestimento` sem `baseValor`, aba que não existe, situação inválida).

**Histórico:** `EDICAO` (com o valor anterior e o novo) e `LIBERACAO_ABA` para cada aba alterada.

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
- **E-mail revogado antes** (reautorizar): o acesso volta. Se a pessoa já tinha conta, o vínculo volta na hora e `codigoAtivacao` vem `null`; se não tinha, vem um código novo.

**Erros:** 403; 404 (parceria); 409 (`"Este e-mail já está autorizado nesta parceria."`, quando já está ativo); 422 (e-mail ou vínculo inválidos). **Histórico:** `AUTORIZACAO`.

## 13. `DELETE /api/parcerias/{id}/emails/{emailId}`  · etapa 4

**Quem chama:** Admin RBS (exige o token CSRF). **Corpo:** nenhum.

Revoga o e-mail. A linha **não é apagada**: fica marcada como revogada (a trilha é mantida), o banco remove o vínculo da pessoa com a parceria e invalida os códigos pendentes. O acesso some **na hora**: quem estava logado perde a parceria na chamada seguinte.

**204**, sem corpo. **Erros:** 403; 404 (parceria ou e-mail que não existe ou é de outra parceria); 409 (já estava revogado). **Histórico:** `REVOGACAO`.

---

# Entregas, arquivos e histórico (etapa 5)

## 14. `GET /api/parcerias/{id}/entregas`  · etapa 5

**Quem chama:** patrocinador da parceria, ou Admin RBS.

**Filtros (opcionais):** `aba` (código da aba, ex.: `MIDIA`) e `status`. A ordem é a da aba e, dentro dela, a de criação.

O patrocinador só enxerga as entregas das abas **liberadas** para a parceria dele. Pedir `aba=` de uma aba fechada devolve **403** (`"Esta aba ainda não foi liberada para a sua parceria."`). Sem `aba=`, vêm as de todas as abas liberadas. A RBS vê todas.

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

`abaCodigo` e `titulo` (até 160 caracteres) são obrigatórios. `status` inicial é opcional: `NAO_INICIADO`, `AGUARDANDO_ENVIO` (padrão) ou `NAO_APLICAVEL`.

**201**: a **Entrega** criada. **Erros:** 403, 404 (parceria), 422 (`campos`). **Histórico:** `CRIACAO`.

## 16. `GET /api/entregas`  · etapa 5

**Quem chama:** Admin RBS. É a **fila de trabalho** da RBS: as entregas de todas as parcerias.

**Filtros (todos opcionais):** `status` (um ou vários, separados por vírgula: `ENVIADO,EM_ANALISE`), `parceriaId`, `eventoId`, `abaCodigo`, `atrasadas=true` (prazo vencido e entrega ainda pendente), `q` (parte do título), mais `pagina` e `tamanho`.

**Ordem:** primeiro as que esperam análise (`ENVIADO` e `EM_ANALISE`), das mais antigas para as mais novas; depois as demais, pelo prazo mais próximo.

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
| `observacao` | Não | Recado de até 500 caracteres, gravado no histórico. |

**[UPLOAD] Regras do arquivo**

- Extensões aceitas: `pdf`, `png`, `jpg`, `jpeg`, `docx`, `xlsx`, `pptx`, `csv`. O servidor também confere o **conteúdo** (não só o nome): um `.pdf` que não é PDF é recusado. SVG fica de fora, porque pode carregar script. (Lista a confirmar com a RBS.)
- O servidor guarda o arquivo com um nome que ele mesmo gera. O nome original só aparece na resposta e no download.
- Categorias que o **patrocinador** pode usar: `MATERIAL_MARCA`, `MATERIAL_MIDIA`, `DOCUMENTO_INGRESSOS`, `CONTRATO`, `OUTRO`. As demais (`PLANO_COMERCIAL`, `RELATORIO_VEICULACAO`, `COMPROVACAO`, `PESQUISA`, `RELATORIO_PARCERIA`) são só da RBS.
- Cada envio vira uma **nova versão** da entrega (1, 2, 3...). As versões antigas continuam disponíveis.
- O patrocinador só envia em entrega de aba **liberada**, e só quando a entrega está em `NAO_INICIADO`, `AGUARDANDO_ENVIO`, `AJUSTE_SOLICITADO` ou `ENVIADO`. Depois de `EM_ANALISE`, `APROVADO` ou `NAO_APLICAVEL`, o envio é recusado com 409.
- O envio do patrocinador muda o status da entrega para `ENVIADO`. O retorno da RBS **não** muda o status.

**201**: o **Arquivo** criado, com o status atual da entrega.

```json
{
  "id": 12, "entregaId": 31, "nomeOriginal": "logo_final.pdf", "mimeType": "application/pdf",
  "tamanhoBytes": 482113, "versao": 2, "categoria": "MATERIAL_MARCA",
  "enviadoPor": { "id": 3, "nome": "Marketing Amarelo", "perfil": "ADMIN_PATROCINADOR" },
  "enviadoEm": "2026-10-17T18:03:00-03:00",
  "statusDaEntrega": "ENVIADO"
}
```

**Erros**

| Status | Quando |
| --- | --- |
| 403 | Aba ainda não liberada, ou categoria que o patrocinador não pode usar. |
| 404 | Entrega que não existe ou é de outra parceria. |
| 409 | A entrega não aceita mais envio no status em que está. |
| 413 | Arquivo acima de 20 MB. |
| 415 | Extensão ou conteúdo fora da lista. |
| 422 | Parte `arquivo` ausente ou vazia. |

**Histórico:** `ENVIO_ARQUIVO` no primeiro envio e `NOVA_VERSAO` nos seguintes, e `ALTERACAO_STATUS` quando o status muda.

## 18. `GET /api/arquivos/{id}`  · etapa 5

**Quem chama:** patrocinador da parceria dona do arquivo, ou Admin RBS. Arquivo de outra parceria, ou **arquivo geral da RBS** (sem parceria), devolve 404 para o patrocinador.

**200**: os bytes do arquivo, **sempre como anexo** (o navegador baixa, nunca abre na página):

```text
Content-Type: application/pdf
Content-Disposition: attachment; filename*=UTF-8''logo_final.pdf
X-Content-Type-Options: nosniff
```

**Erros:** 404 (não existe, é de outra parceria, ou o arquivo sumiu do armazenamento). **Histórico:** `DOWNLOAD`, com quem baixou, de que IP e navegador.

## 19. `PATCH /api/entregas/{id}/status`  · etapa 5

**Quem chama:** Admin RBS (exige o token CSRF). Só a RBS muda o status à mão. O patrocinador muda o status só de forma indireta, ao enviar um arquivo (rota 17).

**Corpo**

```json
{ "status": "AJUSTE_SOLICITADO", "observacao": "Enviar o logo em vetor." }
```

`observacao` tem até 500 caracteres e é **obrigatória** ao pedir ajuste e ao reabrir uma entrega aprovada. Ela aparece para o patrocinador em `ultimaObservacao` e no histórico.

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
- Máximo de **50 mil linhas**. Passou disso: 422 pedindo para filtrar por `de` e `ate`.

---

# Pesquisa, cargas e consolidação (etapa 6)

## 23. `GET /api/parcerias/{id}/pesquisa`  · etapa 6

**Quem chama:** patrocinador da parceria, ou Admin RBS. O patrocinador só acessa se a aba `PESQUISA` estiver **liberada** para a parceria dele; senão recebe **403** (`"Esta aba ainda não foi liberada para a sua parceria."`).

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

- `status` da pergunta: `RASCUNHO` (ainda não enviada, ou devolvida para ajuste), `ENVIADA` (espera a RBS) ou `APROVADA`. `observacaoRbs` é o recado da RBS no último pedido de ajuste.
- `pesquisa` é `null` enquanto a RBS não abriu a pesquisa da ativação daquela edição.
- `resultados` só existem para perguntas `APROVADA` e sempre trazem a **base de respondentes**. `unidade`: `PERCENTUAL`, `NOTA` ou `CONTAGEM`.

## 24. `PUT /api/parcerias/{id}/pesquisa/perguntas`  · etapa 6

**Quem chama:** o Admin Patrocinador da parceria (exige o token CSRF). A RBS recebe 403. Só com a aba `PESQUISA` liberada (senão 403).

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
| O que acontece | Pergunta nova ou alterada fica `ENVIADA`, esperando a RBS. Pergunta já `APROVADA` e sem mudança continua como está. |
| Pergunta aprovada | Não pode ser alterada nem tirada da lista (409). Para mudar, a marca fala com a RBS. |

**200**: o mesmo objeto da rota 23.

**Erros:** 403; 404; 409 (`"A pergunta 2 já foi aprovada pela RBS e não pode mais ser alterada."`); 422 (mais de 3, ordem repetida, texto curto ou longo, em `campos`).

**Histórico:** `EDICAO` (entidade `pesquisa_pergunta`).

## 25. `PATCH /api/pesquisa/perguntas/{id}/status`  · etapa 6

**Quem chama:** Admin RBS (exige o token CSRF). A RBS decide sobre uma pergunta que está `ENVIADA`.

**Corpo**

```json
{ "decisao": "PEDIR_AJUSTE", "observacao": "Troque \"bom\" por uma escala de 0 a 10." }
```

| `decisao` | Efeito |
| --- | --- |
| `APROVAR` | A pergunta fica `APROVADA`. Só a partir daí a carga de resultados aceita linhas dela. `observacao` é opcional. |
| `PEDIR_AJUSTE` | A pergunta volta a `RASCUNHO` e a `observacao` (obrigatória) aparece para a marca em `observacaoRbs`. |

**200**: a pergunta (mesmo formato dos itens de `perguntas` da rota 23). **Erros:** 403; 404; 409 (a pergunta não está `ENVIADA`); 422 (decisão inválida, ou observação que falta).

**Histórico:** `ALTERACAO_STATUS`, com a observação em `detalhe`. Qualquer Admin RBS pode decidir (regra provisória: a RBS ainda vai confirmar se é só o time de pesquisa).

## 26. `POST /api/cargas`  · etapa 6

**Quem chama:** Admin RBS (exige o token CSRF). Envia uma planilha CSV de **veiculação** (mídia exibida) ou de **pesquisa** (resultados agregados). Os formatos das colunas estão em [`docs/banco.md`](banco.md#formatos-dos-csv), e há exemplos em `docs/exemplos/`.

**Corpo:** `multipart/form-data`

| Parte | Obrigatória | O que é |
| --- | --- | --- |
| `arquivo` | Sim | O CSV (UTF-8, vírgula como separador, cabeçalho na primeira linha). Até 20 MB. |
| `tipo` | Sim | `VEICULACAO` ou `PESQUISA`. |
| `eventoId` | Sim | A edição a que a planilha se refere. |
| `escopo` | Só em `PESQUISA` | `ATIVACAO` (padrão) ou `GERAL` (pesquisa do festival, sem patrocinador). |

**Como funciona** [CARGA] [DUPLICIDADE]

1. A API confere o cabeçalho (todas as colunas esperadas) e guarda o arquivo.
2. Grava as linhas numa área de passagem e chama a função do banco, que rejeita o que não tem campo obrigatório ou não bate com nenhuma parceria, e **insere sem duplicar**.
3. A chave da veiculação é a **linha do plano comercial + data e hora**. Reenviar o mesmo arquivo não insere nada: tudo conta como duplicada. Reenviar com quantidade corrigida também conta como duplicada (a primeira versão prevalece).
4. Na pesquisa da ativação, só pergunta `APROVADA` recebe resultado; a linha de pergunta ainda não aprovada é rejeitada.

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

**Erros:** 403; 413 (acima de 20 MB); 415 (não é CSV); 422 (parte ausente, `tipo` inválido, evento que não existe, ou cabeçalho com coluna faltando: `campos.arquivo` lista as colunas que faltam).

**Histórico:** `CARGA`. No banco o `tipo` `VEICULACAO` é gravado como `MIDIA`.

## 27. `GET /api/cargas`  · etapa 6

**Quem chama:** Admin RBS. **Filtros (opcionais):** `tipo`, `eventoId`, `pagina`, `tamanho`. A ordem é da carga mais nova para a mais antiga.

**200**: uma página de cargas, cada item no formato da resposta da rota 26.

## 28. `GET /api/cargas/{id}/rejeicoes`  · etapa 6

**Quem chama:** Admin RBS. **Filtros (opcionais):** `pagina`, `tamanho`. A ordem é pela linha do arquivo.

**200**: uma página.

```json
{
  "itens": [
    { "linha": 12, "campo": "exibido_em", "motivo": "Data e hora obrigatória", "conteudo": "Cliente Verde,Rede Atlântida RS,Citação 5\",,,1," },
    { "linha": 1301, "campo": "patrocinador", "motivo": "Patrocinador sem parceria nesta edição", "conteudo": "Cliente Azul,atl.la,Post,,2026-11-20T10:15:30-03:00,1," }
  ],
  "pagina": 0, "tamanho": 50, "total": 2
}
```

`linha` é o número da linha no arquivo enviado. **Erros:** 403; 404 (carga que não existe).

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

- `percentual` é exibido ÷ previsto × 100 (pode passar de 100). Seções sem plano comercial (`midia` sem linhas, por exemplo) vêm com listas vazias e `null` nos totais.
- `faltantes` é o que impede ou enfraquece o relatório. Tipos: `MIDIA_SEM_EXIBICAO`, `MIDIA_ABAIXO_DO_PREVISTO`, `APLICACAO_SEM_COMPROVACAO`, `PESQUISA_SEM_PERGUNTAS`, `PERGUNTA_SEM_RESULTADO`, `ENTREGA_PENDENTE`, `PARCERIA_SEM_USUARIO`, `FESTIVAL_SEM_NUMEROS`.
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
  "relatorios": { "semRelatorio": 0, "rascunho": 3, "validado": 0, "disponibilizado": 0 },
  "ultimaCarga": { "id": 3, "tipo": "VEICULACAO", "executadaEm": "2026-11-21T09:00:00-03:00", "linhasInseridas": 1483, "linhasRejeitadas": 4 },
  "pendencias": [
    { "parceriaId": 2, "patrocinador": "Cliente Amarelo", "tipo": "ENTREGA_PENDENTE", "detalhe": "Peça de 30\" para mídia própria (em analise)" }
  ]
}
```

`pendencias` são os faltantes (rota 29) de todas as parcerias da edição, limitados aos 20 mais importantes. `ultimaCarga` é `null` se ainda não houve nenhuma.

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
      "indicadoresDisponiveis": 25, "indicadoresIndisponiveis": 3
    },
    "faltantes": 4
  }
]
```

`relatorio` é a **versão mais nova** (ou `null`, com `situacao: "SEM_RELATORIO"`). `faltantes` é quantos itens a consolidação (rota 29) ainda aponta para a parceria.

## 32. `GET /api/relatorios.csv`  · etapa 7

**Quem chama:** Admin RBS. Os mesmos filtros e as mesmas linhas da rota 31, em CSV (mesmo formato de arquivo da rota 22: ponto e vírgula, UTF-8 com BOM, `Content-Disposition: attachment; filename="relatorios-2026-11-22.csv"`).

Colunas: `parceria_id;patrocinador;cota;edicao;situacao;versao;gerado_em;validado_em;disponibilizado_em;indicadores_disponiveis;indicadores_indisponiveis;faltantes`.

## 33. `POST /api/parcerias/{id}/relatorios`  · etapa 7

**Quem chama:** Admin RBS (exige o token CSRF). **Corpo:** nenhum.

Gera uma **nova versão** do relatório da parceria, em `RASCUNHO`, copiando os números da base (a função `fn_gerar_relatorio` do banco). Duas gerações ao mesmo tempo ganham versões diferentes.

**201** (com `Location: /api/relatorios/{id}`): o relatório completo (formato da rota 35).

**Erros:** 403; 404 (parceria). **Histórico:** `GERACAO_RELATORIO` (`"versão 2 (RASCUNHO)"`).

## 34. `PATCH /api/relatorios/{id}/status`  · etapa 7

**Quem chama:** Admin RBS (exige o token CSRF).

**Corpo:** `{ "status": "VALIDADO" }` ou `{ "status": "DISPONIBILIZADO" }`

- `VALIDADO`: a RBS conferiu os números. Grava quem validou e quando; os números ficam congelados.
- `DISPONIBILIZADO`: libera o relatório para o patrocinador. Grava quem liberou e quando. Só vale a partir de `VALIDADO`.

**200**: o relatório (formato da rota 35).

**Erros:** 403; 404; 409 (`"O relatório não pode ir de RASCUNHO para DISPONIBILIZADO. A ordem é RASCUNHO, VALIDADO, DISPONIBILIZADO."`, a frase do banco); 422 (status que não existe).

**Histórico:** `VALIDACAO` ou `DISPONIBILIZACAO`.

## 35. `GET /api/parcerias/{id}/relatorio`  · etapa 7

**Quem chama:** patrocinador da parceria, ou Admin RBS.

- **Patrocinador:** recebe a versão **disponibilizada** mais nova. Se a RBS ainda não disponibilizou nenhuma, **404** (`"O relatório desta parceria ainda não está disponível."`).
- **RBS:** recebe a versão mais nova de qualquer status, ou a pedida em `?versao=2`.

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

- As seções seguem o catálogo de 17 indicadores do banco: Cumprimento das entregas, Mídia: previsto x exibido, Ativação no evento, Pesquisa da ativação, Cortesias e credenciamento, O festival em números.
- `recorte` separa um indicador em vários números (por unidade, por veículo, por pergunta). Vem `""` quando é um número só.
- `valor` é `null` quando `disponivel` é `false`; o front mostra "indisponível", nunca um zero.
- Para o patrocinador, `geradoPor` vem `null` (dado interno).

**Erros:** 404 (parceria de outra, ou sem relatório disponível para o patrocinador).

## 36. `GET /api/relatorios/{id}/csv`  · etapa 7

**Quem chama:** Admin RBS (qualquer versão) ou patrocinador da parceria (só se o relatório estiver `DISPONIBILIZADO`; senão **404**).

**200**: CSV do relatório, um número por linha, no mesmo formato de arquivo da rota 22 (`filename="relatorio-amarelo-v1.csv"`).

Colunas: `secao;indicador_codigo;indicador;recorte;valor;unidade;base_calculo;disponivel`. Número indisponível sai com `valor` em branco e `disponivel` igual a `nao`.

**Histórico:** `DOWNLOAD` (entidade `relatorio`).

---

# Regras que valem para todas as rotas

## Isolamento entre parcerias

[ISOLAMENTO] A parceria é a unidade de isolamento: tudo o que um patrocinador vê pende dela.

- Toda rota que recebe o id de uma parceria (ou de algo que pertence a uma: entrega, arquivo, pergunta, relatório) confere, **a cada chamada**, se o usuário tem vínculo vigente com ela. A conferência vai ao banco toda vez e não é guardada na sessão. Por isso, quando a RBS revoga um e-mail, o acesso some na hora, mesmo para quem já estava logado.
- Quem não tem vínculo recebe **404**, o mesmo erro de um id que não existe. Assim um patrocinador não descobre, pelo código de erro, quais parcerias existem.
- A RBS passa em qualquer parceria que exista. Id que não existe é 404 para todos.
- O `403` é para outra situação: o usuário **tem** acesso à parceria, mas o perfil dele não pode fazer aquela operação (rota só da RBS), ou a aba ainda não foi liberada.

## Permissões por perfil

| Quem | Pode |
| --- | --- |
| **Admin RBS** | Tudo: criar parcerias, autorizar e revogar e-mails, criar entregas, mudar status, mandar retorno, aprovar perguntas, subir cargas, gerar e disponibilizar relatórios, ver o histórico geral e o painel. |
| **Admin Patrocinador** | Ver a própria parceria, as entregas das abas liberadas, enviar materiais, baixar arquivos da parceria, ver o histórico dela, enviar as perguntas da pesquisa, ver a consolidação e o relatório disponibilizado. **Nunca** vê outra parceria nem as rotas da RBS. |

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
- **[PENDENTE]** Limite de tentativas de login por IP e por e-mail. Hoje a falha só fica registrada no histórico.
- **[PENDENTE]** Em produção (HTTPS): marcar o cookie de sessão como `Secure` e enviar o token CSRF só por conexão segura.
- **[ATLAS-DEPOIS]** O assistente com IA (Atlas) fica fora desta versão: não há rota para ele aqui.
