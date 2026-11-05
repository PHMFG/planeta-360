// O pós-venda da edição e os números do festival (rotas 96 a 101 do docs/api.md; requisitos R8.1, R8.2 e R5.3.d da RBS).
import { pedir } from './cliente'
import type { UsuarioResumo } from './parcerias'

export type TipoDeLink = 'AFTERMOVIE' | 'CLIPPING' | 'OUTRO'
export type LinkDoAnexo = { tipo: TipoDeLink; titulo: string; url: string }
export type Audiencia = { veiculo: string; metrica: string; numero: number; unidade: string }

export type VersaoDoAnexo = {
  id: number
  versao: number
  texto: string | null
  links: LinkDoAnexo[]
  audiencias: Audiencia[]
  publicadoEm: string
  publicadoPor: UsuarioResumo | null
}

/** Um link de post da parceria: os da veiculação carregada pela RBS e, se houver, os enviados como material. */
export type LinkDePost = { origem: 'CARGA' | 'ENVIO'; url: string; host: string; data: string | null; veiculo?: string | null; formato?: string | null; titulo?: string | null }

export type PosVendaDaParceria = {
  parceriaId: number
  eventoId: number
  relatorioDisponibilizado: boolean
  anexo: VersaoDoAnexo | null
  linksDePosts: LinkDePost[]
}

export type NovoAnexo = { texto?: string; links: LinkDoAnexo[]; audiencias: Audiencia[] }

export type NumeroDaEdicao = { id: number; indicador: string; valor: number; qualificador: 'EXATO' | 'MAIS_DE'; unidade: string; fonte: string }

export const NOME_DO_LINK: Record<TipoDeLink, string> = { AFTERMOVIE: 'Aftermovie', CLIPPING: 'Clipping', OUTRO: 'Outro' }

export const obterPosVenda = (parceriaId: number) => pedir<PosVendaDaParceria>(`/parcerias/${parceriaId}/posvenda`)

export const publicarPosVenda = (parceriaId: number, anexo: NovoAnexo) =>
  pedir<VersaoDoAnexo>(`/parcerias/${parceriaId}/posvenda`, { metodo: 'POST', corpo: anexo })

export const retirarPosVenda = (versaoId: number) => pedir<void>(`/posvenda/${versaoId}`, { metodo: 'DELETE' })

export const listarNumerosDaEdicao = (eventoId: number) => pedir<NumeroDaEdicao[]>(`/eventos/${eventoId}/resultados`)

export const informarNumeroDaEdicao = (eventoId: number, numero: Omit<NumeroDaEdicao, 'id'>) =>
  pedir<NumeroDaEdicao>(`/eventos/${eventoId}/resultados`, { metodo: 'PUT', corpo: numero })
