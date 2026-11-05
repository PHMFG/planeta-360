// Única porta de saída do front para a API: sessão por cookie, token CSRF e erros no formato do back. [ITEM-3]
// O front nunca fala com o banco; toda regra de acesso é conferida na API.

const BASE = '/api'
const COOKIE_CSRF = 'XSRF-TOKEN'
const CABECALHO_CSRF = 'X-XSRF-TOKEN'
const SEM_API = 'Sem conexão com o servidor. Confira se a API está rodando.'

/** Erro devolvido pela API (ProblemDetail do Spring), pronto para mostrar ao usuário. */
export class ErroApi extends Error {
  readonly status: number
  readonly campos: Record<string, string>

  constructor(status: number, mensagem: string, campos: Record<string, string> = {}) {
    super(mensagem)
    this.name = 'ErroApi'
    this.status = status
    this.campos = campos
  }
}

/** Listas longas (histórico, fila, cargas) vêm paginadas pela API. */
export type Pagina<T> = { itens: T[]; pagina: number; tamanho: number; total: number }

/** Monta a parte "?a=1&b=2" da URL, pulando filtros vazios. */
export function consulta(filtros: Record<string, string | number | boolean | undefined | null>) {
  const busca = new URLSearchParams()
  for (const [chave, valor] of Object.entries(filtros)) {
    if (valor !== undefined && valor !== null && valor !== '') busca.set(chave, String(valor))
  }
  const texto = busca.toString()
  return texto ? `?${texto}` : ''
}

let aoPerderSessao: (() => void) | null = null

/** A sessão registra aqui o que fazer quando a API responde 401 (sessão expirada ou encerrada). */
export function quandoPerderSessao(acao: () => void) {
  aoPerderSessao = acao
}

function lerCookie(nome: string): string | null {
  const par = document.cookie.split('; ').find((item) => item.startsWith(`${nome}=`))
  return par ? decodeURIComponent(par.slice(nome.length + 1)) : null
}

// [PERMISSAO] O Spring Security exige o token CSRF em todo pedido que altera dados.
// O token vem num cookie legível; se ele ainda não existe, a rota /auth/csrf o cria.
async function tokenCsrf(): Promise<string> {
  const existente = lerCookie(COOKIE_CSRF)
  if (existente) return existente
  const resposta = await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin' })
  const corpo = (await resposta.json().catch(() => null)) as { token?: string } | null
  return lerCookie(COOKIE_CSRF) ?? corpo?.token ?? ''
}

async function lerErro(resposta: Response): Promise<ErroApi> {
  const corpo = (await resposta.json().catch(() => null)) as {
    detail?: string
    title?: string
    campos?: Record<string, string>
  } | null
  const mensagem =
    corpo?.detail ??
    corpo?.title ??
    // 502 a 504 sem corpo da API: quem respondeu foi o proxy (o Vite, em desenvolvimento), porque a API está fora do ar.
    (resposta.status >= 502 && resposta.status <= 504
      ? SEM_API
      : resposta.status >= 500
        ? 'O servidor não respondeu como esperado. Tente de novo em instantes.'
        : 'Não foi possível concluir o pedido.')
  return new ErroApi(resposta.status, mensagem, corpo?.campos ?? {})
}

type Opcoes = {
  metodo?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  corpo?: unknown
  /** Para envio de arquivos: o navegador monta o multipart sozinho. */
  formulario?: FormData
  /** Rotas de login não devem derrubar a sessão em caso de 401. */
  ignorarSessao?: boolean
}

export async function pedir<T = void>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  const metodo = opcoes.metodo ?? 'GET'
  const cabecalhos: Record<string, string> = { Accept: 'application/json' }
  let corpo: BodyInit | undefined

  if (opcoes.formulario) {
    corpo = opcoes.formulario
  } else if (opcoes.corpo !== undefined) {
    cabecalhos['Content-Type'] = 'application/json'
    corpo = JSON.stringify(opcoes.corpo)
  }
  if (metodo !== 'GET') {
    cabecalhos[CABECALHO_CSRF] = await tokenCsrf()
  }

  let resposta: Response
  try {
    resposta = await fetch(`${BASE}${caminho}`, {
      method: metodo,
      headers: cabecalhos,
      body: corpo,
      credentials: 'same-origin',
    })
  } catch {
    throw new ErroApi(0, SEM_API)
  }

  if (resposta.status === 401 && !opcoes.ignorarSessao) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  if (resposta.status === 204) return undefined as T

  const tipo = resposta.headers.get('Content-Type') ?? ''
  return (tipo.includes('json') ? await resposta.json() : await resposta.text()) as T
}

/** Um arquivo que a tela mostra ela mesma, como o documento no visualizador do Atlas. Os erros vêm como os de {@link pedir}. */
export async function pedirArquivo(caminho: string, sinal?: AbortSignal): Promise<Blob> {
  let resposta: Response
  try {
    resposta = await fetch(`${BASE}${caminho}`, { credentials: 'same-origin', signal: sinal })
  } catch (erro) {
    if (sinal?.aborted) throw erro
    throw new ErroApi(0, SEM_API)
  }
  if (resposta.status === 401) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  return resposta.blob()
}

/** Um evento de uma resposta em fluxo (text/event-stream): o nome ("event:") e os dados ("data:"), já lidos do JSON. */
export type EventoDoFluxo = { nome: string; dados: unknown }

/** Lê um evento do fluxo: as linhas "event:" e "data:" até a linha em branco. Comentários e "id:" ficam de fora. */
function lerEvento(bloco: string): EventoDoFluxo | null {
  let nome = 'message'
  const dados: string[] = []
  for (const linha of bloco.split('\n')) {
    const separador = linha.indexOf(':')
    if (separador <= 0) continue
    const campo = linha.slice(0, separador)
    const valor = linha.slice(separador + 1).replace(/^ /, '')
    if (campo === 'event') nome = valor
    else if (campo === 'data') dados.push(valor)
  }
  if (!dados.length) return null
  const texto = dados.join('\n')
  try {
    return { nome, dados: JSON.parse(texto) }
  } catch {
    return { nome, dados: texto }
  }
}

/**
 * POST que a API responde em fluxo (text/event-stream): cada evento chega a aoEvento assim que o servidor o manda. [ATLAS]
 * Os erros de antes do fluxo (404, 409, 422, 429, 503) chegam como em pedir(), em ErroApi. Se a API responder em JSON, o
 * corpo volta inteiro e nenhum evento é chamado; num fluxo, a função devolve undefined quando o servidor fecha a conexão.
 */
export async function pedirEmFluxo(caminho: string, corpo: unknown, aoEvento: (evento: EventoDoFluxo) => void, sinal?: AbortSignal): Promise<unknown> {
  let resposta: Response
  try {
    resposta = await fetch(`${BASE}${caminho}`, {
      method: 'POST',
      // O JSON também vale: é como chegam os erros de antes do fluxo (ProblemDetail) e a resposta de uma API sem fluxo.
      headers: { Accept: 'text/event-stream, application/json', 'Content-Type': 'application/json', [CABECALHO_CSRF]: await tokenCsrf() },
      body: JSON.stringify(corpo),
      credentials: 'same-origin',
      signal: sinal,
    })
  } catch (erro) {
    if (sinal?.aborted) throw erro
    throw new ErroApi(0, SEM_API)
  }

  if (resposta.status === 401) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  const tipo = resposta.headers.get('Content-Type') ?? ''
  if (!tipo.includes('text/event-stream') || !resposta.body) return tipo.includes('json') ? await resposta.json() : undefined

  const leitor = resposta.body.pipeThrough(new TextDecoderStream()).getReader()
  let resto = ''
  for (;;) {
    let parte: ReadableStreamReadResult<string>
    try {
      parte = await leitor.read()
    } catch (erro) {
      if (sinal?.aborted) throw erro
      throw new ErroApi(0, 'A conexão caiu no meio da resposta.')
    }
    if (parte.done) return undefined
    // Um evento termina numa linha em branco; o pedaço que sobra espera o resto chegar.
    resto = (resto + parte.value).replace(/\r\n/g, '\n')
    let fim = resto.indexOf('\n\n')
    while (fim >= 0) {
      const evento = lerEvento(resto.slice(0, fim))
      resto = resto.slice(fim + 2)
      if (evento) aoEvento(evento)
      fim = resto.indexOf('\n\n')
    }
  }
}

/**
 * Baixa um anexo da API e entrega ao navegador com o nome do arquivo. [UPLOAD]
 * Um link comum falharia calado quando a API recusa (ex.: arquivo que não está mais no armazenamento); aqui o erro volta para a tela.
 */
export async function baixar(caminho: string, nomeDoArquivo: string) {
  let resposta: Response
  try {
    resposta = await fetch(`${BASE}${caminho}`, { credentials: 'same-origin' })
  } catch {
    throw new ErroApi(0, SEM_API)
  }
  if (resposta.status === 401) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  const endereco = URL.createObjectURL(await resposta.blob())
  const link = Object.assign(document.createElement('a'), { href: endereco, download: nomeDoArquivo })
  link.click()
  setTimeout(() => URL.revokeObjectURL(endereco), 1000)
}

/** O arquivo inteiro, como Blob, para mostrar na tela (o visualizador do contrato), com o mesmo tratamento de erro. */
export async function lerArquivo(caminho: string): Promise<Blob> {
  let resposta: Response
  try {
    resposta = await fetch(`${BASE}${caminho}`, { credentials: 'same-origin' })
  } catch {
    throw new ErroApi(0, SEM_API)
  }
  if (resposta.status === 401) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  return resposta.blob()
}
