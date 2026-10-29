# API do Planeta 360 · Portal do Patrocinador

Este documento é o **contrato entre o back-end e o front-end**. Quem faz a tela lê aqui o que cada rota pede, o que devolve e quais erros esperar, sem precisar abrir o código.

- Todas as 36 rotas estão implementadas e cobertas por testes de integração (`cd backend && ./mvnw test`), inclusive o isolamento entre parcerias. A coluna "Etapa" diz em que etapa do plano cada rota entrou. Mudança de formato daqui em diante entra neste arquivo no mesmo commit do código.
- O banco está descrito em [`docs/banco.md`](banco.md). Os nomes de status, cotas e abas são os mesmos do banco.

## Como falar com a API

| Assunto | Regra |
| --- | --- |
| Endereço | Todas as rotas começam com `/api`. Em desenvolvimento a API roda em `http://localhost:8080` e o Vite repassa `/api` para ela, então front e API ficam na **mesma origem** (o cookie de sessão não precisa de CORS). |
| Formato | JSON em UTF-8, nomes de campo em `camelCase`. Os únicos arquivos que não são JSON são os downloads, os CSV e os envios (`multipart/form-data`). |
| Datas | Data e hora em ISO 8601 **com fuso**: `"2026-10-18T10:15:30-03:00"`. Data sem hora (prazo, início do evento): `"2026-12-16"`. |
| Números | Números JSON. Dinheiro e quantidades têm até 2 casas decimais (`1000000.00`). Percentuais vão de 0 a 100, com 1 casa (`98.3`). O percentual de entrega de mídia é **cortado** na primeira casa, e não arredondado (459.900 de 460.000 é `99.9`, nunca `100`); os outros percentuais arredondam, mas abaixo de 100 nunca passam de `99.9`. Nos textos (`baseCalculo`, `detalhe`) os números saem no padrão brasileiro: `459.900 de 460.000`. |
| Campos vazios | Um campo sem valor vem como `null`. As listas vêm sempre como `[]`, nunca `null`. |
| Códigos | Cotas: `MASTER`, `SUBCOTA_G`, `SUBCOTA`. Abas: `PERFIL`, `APLICACOES`, `MIDIA`, `CORTESIAS`, `CREDENCIAMENTO`, `PESQUISA`, `CONTRATO`. Perfis: `ADMIN_RBS`, `ADMIN_PATROCINADOR`. |
| Listas longas | Histórico, fila de entregas, cargas e rejeições são paginadas: `?pagina=0&tamanho=50` (pagina começa em 0; tamanho padrão 50, máximo 200; `pagina` negativa ou `tamanho` fora de 1 a 200 devolve 422). A resposta é `{"itens": [...], "pagina": 0, "tamanho": 50, "total": 137}`. As outras listas vêm inteiras, em array. |
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
| 400 | Requisição inválida | Corpo vazio ou que não é JSON, ou filtro da URL com o tipo errado (`?pagina=abc`, `?eventoId=abc`, `?atrasadas=talvez`): o `detail` diz qual, por exemplo `"O parâmetro pagina precisa ser um número inteiro."` ou `"O parâmetro atrasadas precisa ser true ou false."`. |
| 401 | Não autenticado | Sem sessão (ou sessão vencida), e-mail/senha incorretos no login, ou sessão de uma conta que perdeu o acesso (veja "Sessão e CSRF"). |
| 403 | Acesso negado | Logado, mas o perfil não pode fazer a operação (rota só da RBS), a aba ainda não foi liberada para a parceria, ou falta o token CSRF. |
| 404 | Não encontrado | O recurso **não existe ou é de outra parceria**. Os dois casos têm a mesma resposta, de propósito. Vale também para rota inexistente e para **id na URL que não é número** (`GET /api/parcerias/abc`): um id que não pode existir é um id que não existe, e o `detail` é a frase de "não encontrado" do recurso da rota (`"Não encontramos esta parceria."`, `"... esta entrega."`, `"... este arquivo."`, `"... esta pergunta."`, `"... este relatório."`, `"... esta carga."`, `"Não encontramos este e-mail nesta parceria."`), igual à de um id numérico sem registro. |
| 405 | Método não permitido | A rota existe, mas não aceita esse método HTTP. |
| 409 | Conflito | Uma regra do negócio recusou: regra do banco (SQLSTATE da classe 23, com a frase do gatilho), transição de status que não é permitida, registro duplicado. |
| 413 | Arquivo grande demais | Envio acima de 20 MB. |
| 415 | Tipo de conteúdo não aceito | Extensão ou tipo de arquivo fora da lista permitida, ou `Content-Type` errado. |
| 422 | Dados inválidos | Campo faltando ou fora do formato. Vem com `campos`. |
| 500 | Erro interno | Falha inesperada. O `detail` é sempre genérico. |

**Ordem das conferências.** Quando uma chamada tem mais de um problema, a API responde, em geral, pelo primeiro destes: 401 (sem sessão, ou conta que perdeu o acesso) → 403 por perfil (rota só da RBS) → 404 (não existe ou não é do usuário) → 403 por aba não liberada → 400 e 422 (formato do corpo, das partes ou dos filtros) → 409 (o estado atual não permite). A validação do corpo vem depois da permissão: quem não pode usar a rota nunca recebe um erro de campo. Regras de campo que dependem do estado atual (por exemplo, a observação obrigatória da rota 19) vêm depois do 409; cada rota diz.

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

**Aba**: `{ "codigo": "PESQUISA", "nome": "Pesquisa da ativação", "ordem": 6, "liberada": true, "incluidaNaCota": true }`. Sempre as 7 abas, em ordem. Dois booleanos dizem coisas diferentes:

- `liberada: false` = a aba está **fechada** para esta parceria (a RBS ainda não abriu, fechou ou escondeu do menu); o front mostra o cadeado. Uma aba só conta como liberada se estiver liberada **e** visível no banco.
- `incluidaNaCota: false` = a aba **não faz parte da cota** da parceria (vem de `cota_tipo_aba`; hoje a Pesquisa só está na cota Master). Aba fora da cota nunca abre: a RBS não consegue liberá-la (rota 10 devolve 422). Use este campo para mostrar "fora da sua cota" em vez de "aguardando liberação".

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
- O servidor também confere o **conteúdo**, pelos primeiros bytes (a "assinatura" do formato), e não só o nome: `pdf` começa com `%PDF`; `png` e `jpg`/`jpeg` com a assinatura de imagem; `zip`, `docx`, `xlsx` e `pptx` com `PK`; `mp4` tem `ftyp` logo no começo; `mp3` começa com `ID3` ou com um quadro de áudio; `csv` precisa ser texto UTF-8 válido, sem bytes nulos. Um executável renomeado para `.pdf` é recusado (415). SVG e executáveis ficam de fora, porque podem carregar script. O tipo (`mimeType`) gravado vem da extensão conferida, nunca do que o navegador informou.
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
| 403 | Aba ainda não liberada, ou categoria que o patrocinador não pode usar. |
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

**Erros:** 404 (não existe, é de outra parceria, ou o arquivo sumiu do armazenamento). **Histórico:** `DOWNLOAD`, com quem baixou, de que IP e navegador.

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

- `status` da pergunta: `RASCUNHO` (ainda não enviada, devolvida para ajuste ou reaberta pela RBS depois de aprovada), `ENVIADA` (espera a RBS) ou `APROVADA`. `observacaoRbs` é o recado da RBS na última decisão sobre a pergunta (pedido de ajuste ou aprovação com observação), enquanto a pergunta continua no status que aquela decisão gerou; quando a marca reenvia, o recado antigo sai e vem `null`.
- `pesquisa` é `null` enquanto a RBS não abriu a pesquisa da ativação daquela edição. A marca já pode enviar as perguntas nesse período; a carga de resultados liga cada uma à pesquisa depois.
- `abaLiberada` é sempre `true` para o patrocinador (senão ele recebe 403). Para a RBS mostra o estado real da aba. Aba só conta como liberada se estiver liberada **e** visível.
- As perguntas vêm em ordem (`ordem` 1 a 3); os resultados, por pergunta e depois na ordem da carga.
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

**Quem chama:** Admin RBS (exige o token CSRF). Envia uma planilha CSV de **veiculação** (mídia exibida) ou de **pesquisa** (resultados agregados). Os formatos das colunas estão em [`docs/banco.md`](banco.md#formatos-dos-csv), e há exemplos em `docs/exemplos/`.

**Corpo:** `multipart/form-data`

| Parte | Obrigatória | O que é |
| --- | --- | --- |
| `arquivo` | Sim | O CSV, com extensão `.csv` (UTF-8, vírgula como separador, cabeçalho na primeira linha; o BOM do Excel no começo é aceito). Até 20 MB. |
| `tipo` | Sim | `VEICULACAO` ou `PESQUISA`. |
| `eventoId` | Sim | A edição a que a planilha se refere. |
| `escopo` | Só em `PESQUISA` | `ATIVACAO` (padrão) ou `GERAL` (pesquisa do festival, sem patrocinador). Em `VEICULACAO` a parte é ignorada. |

**Como funciona** [CARGA] [DUPLICIDADE]

1. A API confere o cabeçalho e guarda o arquivo como arquivo geral da RBS (sem parceria; categoria `RELATORIO_VEICULACAO` ou `PESQUISA`). O cabeçalho precisa ter **todas** as colunas do tipo, em qualquer ordem e em maiúsculas ou minúsculas; coluna a mais é ignorada. Veiculação: `patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link`. Pesquisa: `patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes`. Célula vazia vale como nulo.
2. Grava as linhas numa área de passagem e chama a função do banco, que rejeita o que não tem campo obrigatório ou não bate com nenhuma parceria, e **insere sem duplicar**. Data ou número que não dá para ler (por exemplo `exibido_em` = "ontem", ou decimal com vírgula) não derruba o arquivo: a linha é rejeitada, com o campo e o motivo. Data sem fuso vale no horário de Brasília. Tudo corre numa transação: se algo falhar, não ficam a carga, as linhas nem o arquivo.
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

`linha`, nas rejeições, é o número da linha no arquivo (o cabeçalho é a linha 1; linha em branco conta no número, mas não é lida).

**Erros:** 403 (conferido antes de qualquer outra coisa); 413 (acima de 20 MB); 415 (não é CSV: extensão diferente de `.csv`, ou conteúdo que não é texto UTF-8); 422 com `campos`: parte ausente ou inválida (`arquivo`, `tipo`, `eventoId`, `escopo`), evento que não existe (`campos.eventoId`), edição sem a pesquisa pedida cadastrada (`campos.escopo`), e problemas do arquivo em `campos.arquivo`: cabeçalho com coluna faltando (lista as que faltam), texto maior que a coluna do banco (diz a linha e a coluna), CSV mal formado (aspas abertas) e planilha sem nenhuma linha de dados. Nesses casos nada é gravado.

**Histórico:** `CARGA`. No banco o `tipo` `VEICULACAO` é gravado como `MIDIA`.

## 27. `GET /api/cargas`  · etapa 6

**Quem chama:** Admin RBS. **Filtros (opcionais):** `tipo` (`VEICULACAO` ou `PESQUISA`; outro valor dá 422), `eventoId`, `pagina`, `tamanho`. A ordem é da carga mais nova para a mais antiga (pelo id da carga).

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

`linha` é o número da linha no arquivo enviado. `conteudo` é a linha reescrita pelo banco, com os campos separados por ` | ` (a data em horário de Brasília). **Erros:** 403 (também para carga que não existe: a permissão é conferida primeiro); 404 (carga que não existe, para a RBS).

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

- `percentual` é exibido ÷ previsto × 100 (pode passar de 100). Na mídia ele é **cortado** na primeira casa decimal, não arredondado: 459.900 de 460.000 vale `99.9`, porque `100` diria "completo" quando faltam exibições. Seções sem plano comercial (`midia` sem linhas, por exemplo) vêm com listas vazias e `null` nos totais: `aplicacoes`, `entregas` e `cortesias` continuam como objeto, com todos os campos `null` quando a parceria não tem dado naquela seção.
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
- `ultimaCarga` é a carga mais nova **da edição**; o `tipo` usa o nome da API (`VEICULACAO`, e não `MIDIA` do banco).
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
      "indicadoresDisponiveis": 25, "indicadoresIndisponiveis": 3
    },
    "disponibilizada": { "id": 1, "versao": 1, "disponibilizadoEm": "2026-11-23T11:00:00-03:00" },
    "faltantes": 4
  }
]
```

`relatorio` é a **versão mais nova** (ou `null`, com `situacao: "SEM_RELATORIO"`). `faltantes` é quantos itens a consolidação (rota 29) ainda aponta para a parceria.

`disponibilizada` é a **versão que a marca vê**: a disponibilizada mais nova, com o `id`, a `versao` e quando ela foi liberada (`disponibilizadoEm`). Fica `null` se nenhuma versão foi disponibilizada. Ela pode ser mais antiga que `relatorio`: com a v3 em rascunho e a v2 disponibilizada, a linha traz `relatorio.versao` 3 (`situacao` `RASCUNHO`) e `disponibilizada.versao` 2, e a tela da RBS deve mostrar as duas ("Rascunho · v3, a marca vê a v2"). Quando a versão mais nova é a própria disponibilizada, `disponibilizada` repete o mesmo `id` e `versao`.

## 32. `GET /api/relatorios.csv`  · etapa 7

**Quem chama:** Admin RBS. Os mesmos filtros e as mesmas linhas da rota 31, em CSV (mesmo formato de arquivo da rota 22: ponto e vírgula, UTF-8 com BOM, `Content-Disposition: attachment; filename="relatorios-2026-11-22.csv"`).

Colunas: `parceria_id;patrocinador;cota;edicao;situacao;versao;gerado_em;validado_em;disponibilizado_em;indicadores_disponiveis;indicadores_indisponiveis;faltantes;marca_ve_versao;marca_ve_disponibilizado_em`. As duas últimas são a versão que a marca vê e quando foi liberada (`disponibilizada` da rota 31), em branco se nenhuma foi disponibilizada.

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
- Pedir o status que o relatório já tem devolve **200** sem alterar nada e sem gravar histórico.

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

- As seções seguem o catálogo de 17 indicadores do banco: Cumprimento das entregas, Mídia: previsto x exibido, Ativação no evento, Pesquisa da ativação, Cortesias e credenciamento, O festival em números.
- `recorte` separa um indicador em vários números (por unidade, por veículo, por pergunta). Vem `""` quando é um número só.
- `baseCalculo` é o texto "como foi calculado", com os números no padrão brasileiro: milhar com ponto e decimal com vírgula (`"459.900 de 460.000"`, `"1.105 de 1.113"`). O percentual de entrega de mídia (`MIDIA_ENTREGA_PCT`, `MIDIA_VEICULO_PCT`) é cortado na primeira casa: 459.900 de 460.000 sai `99.9`, e só chega a `100` quando está completo. Os relatórios já gerados antes desta regra mantêm o texto que tinham (cada versão é uma fotografia); a regra vale para as versões novas.
- `valor` é `null` quando `disponivel` é `false`; o front mostra "indisponível", nunca um zero.
- Para o patrocinador, `geradoPor` vem `null` (dado interno).

**Erros:** 404 (parceria de outra, ou sem relatório disponível para o patrocinador).

## 36. `GET /api/relatorios/{id}/csv`  · etapa 7

**Quem chama:** Admin RBS (qualquer versão) ou patrocinador da parceria (só se o relatório estiver `DISPONIBILIZADO`; senão **404**).

**200**: CSV do relatório, um número por linha, no mesmo formato de arquivo da rota 22 (`filename="relatorio-cliente-amarelo-v1.csv"`: o nome do patrocinador, sem acento e com hífens, e a versão).

Colunas: `secao;indicador_codigo;indicador;recorte;valor;unidade;base_calculo;disponivel`. Número indisponível sai com `valor` em branco e `disponivel` igual a `nao`.

**Histórico:** `DOWNLOAD` (entidade `relatorio`).

---

# Regras que valem para todas as rotas

## Isolamento entre parcerias

[ISOLAMENTO] A parceria é a unidade de isolamento: tudo o que um patrocinador vê pende dela.

- Toda rota que recebe o id de uma parceria (ou de algo que pertence a uma: entrega, arquivo, pergunta, relatório) confere, **a cada chamada**, se o usuário tem vínculo vigente com ela. A conferência vai ao banco toda vez e não é guardada na sessão. Por isso, quando a RBS revoga um e-mail, o acesso some na hora, mesmo para quem já estava logado.
- Quem não tem vínculo recebe **404**, o mesmo erro de um id que não existe. Assim um patrocinador não descobre, pelo código de erro, quais parcerias existem.
- A RBS passa em qualquer parceria que exista. Id que não existe é 404 para todos.
- O `403` é para outra situação: o usuário **tem** acesso à parceria, mas o perfil dele não pode fazer aquela operação (rota só da RBS), ou a aba ainda não foi liberada. Nas rotas só da RBS o perfil é conferido **primeiro**: o patrocinador recebe 403 com qualquer id (da parceria dele, de outra ou que não existe) e com **qualquer corpo ou filtro** (corpo vazio, JSON quebrado, campo inválido, `?tamanho=999`...). O 403 nunca revela se o id existe nem como é o formato do corpo da rota. As rotas só da RBS são as marcadas "Admin RBS" na tabela do início deste arquivo.

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
