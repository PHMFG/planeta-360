// [TEMPORARIO] Rotas de debug para pôr a chave do modelo de linguagem do Atlas pela tela. Só existem no perfil dev da API
// (atlas.debug.chaves); em qualquer outro ambiente respondem 404 e o botão não aparece. A chave nunca volta da API: só o final.
import { ErroApi, pedir } from './cliente'
import type { UsuarioResumo } from './parcerias'

export type EstadoDoLlm = {
  /** DEBUG: chave posta pela tela · AMBIENTE: variáveis de ambiente da API · NENHUM: sem chave (o Atlas fica no botão). */
  origem: 'DEBUG' | 'AMBIENTE' | 'NENHUM'
  baseUrl: string
  modelo: string
  finalDaChave: string | null
  configuradoPor: UsuarioResumo | null
  configuradoEm: string | null
  llmDisponivel: boolean
  /** A linha de pensamento do modelo (o raciocínio do DeepSeek), ligada por padrão. */
  raciocinio: boolean
}

export type TesteDoLlm = { ok: boolean; milissegundos: number; modelo: string; resposta?: string; erro?: string }

/** O estado do modelo, ou null quando a API não tem as rotas de debug (fora do perfil dev). */
export async function obterEstadoDoLlm(): Promise<EstadoDoLlm | null> {
  try {
    return await pedir<EstadoDoLlm>('/debug/llm')
  } catch (erro) {
    if (erro instanceof ErroApi && erro.status === 404) return null
    throw erro
  }
}

export const definirChaveDoLlm = (dados: { chave: string; baseUrl?: string; modelo?: string; raciocinio?: boolean }) =>
  pedir<EstadoDoLlm>('/debug/llm', { metodo: 'PUT', corpo: dados })

export const removerChaveDoLlm = () => pedir('/debug/llm', { metodo: 'DELETE' })

export const testarLlm = () => pedir<TesteDoLlm>('/debug/llm/teste', { metodo: 'POST' })

/** Avisa as telas (o painel do Atlas) que o modelo ligou ou desligou, para elas lerem o estado de novo. */
export const EVENTO_LLM_MUDOU = 'atlas:llm-mudou'
