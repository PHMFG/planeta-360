// Os documentos do Atlas: envio, lista, aprovação e trechos (rotas 54 a 58 do docs/api.md). [ATLAS]
import { consulta, pedir, type Pagina } from './cliente'
import type { UsuarioResumo } from './parcerias'
import type { ParceriaResumo } from './tipos'

export type StatusDoDocumento = 'AGUARDANDO_APROVACAO' | 'RECUSADO' | 'PROCESSANDO' | 'INDEXADO' | 'ERRO' | 'SUBSTITUIDO' | 'REVOGADO'
export type TipoDoDocumento = 'MANUAL_OPERACOES' | 'PLANO_COMERCIAL' | 'ANEXO_PARCERIA' | 'FAQ' | 'COMUNICADO' | 'OUTRO'
export type EscopoDoDocumento = 'GLOBAL' | 'COTA' | 'PARCERIA'
export type AcaoDoDocumento = 'APROVAR' | 'RECUSAR' | 'REVOGAR' | 'REINDEXAR'
export type CodigoDaCota = ParceriaResumo['cota']['codigo']

export type Documento = {
  id: number
  titulo: string
  chave: string
  versao: number
  tipo: TipoDoDocumento
  escopo: EscopoDoDocumento
  evento: { id: number; nome: string; edicao: number }
  cota: { codigo: CodigoDaCota; nome: string } | null
  parceria: { id: number; patrocinador: string } | null
  status: StatusDoDocumento
  contemDadosPessoais: boolean
  paginas: number | null
  trechos: number
  modelo: string | null
  dimensoes: number | null
  /** Indexado com outro modelo de embeddings: a busca o ignora. */
  modeloDiferente: boolean
  extracaoMs: number | null
  embeddingsMs: number | null
  erro: string | null
  arquivo: { id: number; nomeOriginal: string; mimeType: string; tamanhoBytes: number }
  enviadoPor: UsuarioResumo
  enviadoEm: string
  aprovadoPor: UsuarioResumo | null
  aprovadoEm: string | null
  revogadoPor: UsuarioResumo | null
  revogadoEm: string | null
  /** O que a RBS pode fazer agora, no vocabulário da rota 57. */
  acoes: AcaoDoDocumento[]
}

export type TrechoDoDocumento = {
  id: number
  ordem: number
  paginaInicio: number
  paginaFim: number
  secao: string | null
  tokens: number
  ativo: boolean
  inicioDoTexto: string
}

export type NovoDocumento = {
  arquivo: File
  titulo: string
  chave: string
  tipo: TipoDoDocumento
  escopo: EscopoDoDocumento
  eventoId?: number
  cota?: CodigoDaCota
  parceriaId?: number
}

export type FiltrosDeDocumentos = {
  eventoId?: number
  escopo?: string
  status?: string
  tipo?: string
  q?: string
  pagina?: number
}

export function enviarDocumento(novo: NovoDocumento) {
  const formulario = new FormData()
  formulario.append('arquivo', novo.arquivo)
  formulario.append('titulo', novo.titulo)
  formulario.append('chave', novo.chave)
  formulario.append('tipo', novo.tipo)
  formulario.append('escopo', novo.escopo)
  if (novo.eventoId !== undefined) formulario.append('eventoId', String(novo.eventoId))
  if (novo.cota) formulario.append('cota', novo.cota)
  if (novo.parceriaId !== undefined) formulario.append('parceriaId', String(novo.parceriaId))
  return pedir<Documento>('/documentos', { metodo: 'POST', formulario })
}

export const listarDocumentos = (filtros: FiltrosDeDocumentos = {}) => pedir<Pagina<Documento>>(`/documentos${consulta(filtros)}`)

export const mudarStatusDoDocumento = (id: number, acao: AcaoDoDocumento, observacao?: string) =>
  pedir<Documento>(`/documentos/${id}/status`, { metodo: 'PATCH', corpo: { acao, observacao: observacao || undefined } })

export const listarTrechos = (id: number, pagina = 0) => pedir<Pagina<TrechoDoDocumento>>(`/documentos/${id}/trechos${consulta({ pagina })}`)
