// Única porta de saída do front para a API: sessão por cookie, token CSRF e erros no formato do back. [ITEM-3]
// O front nunca fala com o banco; toda regra de acesso é conferida na API.

const BASE = '/api'
const COOKIE_CSRF = 'XSRF-TOKEN'
const CABECALHO_CSRF = 'X-XSRF-TOKEN'

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
    (resposta.status >= 500
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
    throw new ErroApi(0, 'Sem conexão com o servidor. Confira se a API está rodando.')
  }

  if (resposta.status === 401 && !opcoes.ignorarSessao) aoPerderSessao?.()
  if (!resposta.ok) throw await lerErro(resposta)
  if (resposta.status === 204) return undefined as T

  const tipo = resposta.headers.get('Content-Type') ?? ''
  return (tipo.includes('json') ? await resposta.json() : await resposta.text()) as T
}
