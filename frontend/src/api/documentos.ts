// Os documentos do Atlas: envio, lista, aprovação, trechos, o visualizador e a busca de teste (rotas 54 a 61 do docs/api.md). [ATLAS]
import { consulta, pedir, pedirArquivo, type Pagina } from './cliente'
import type { UsuarioResumo } from './parcerias'
import type { ParceriaResumo } from './tipos'

export type StatusDoDocumento = 'AGUARDANDO_APROVACAO' | 'RECUSADO' | 'PROCESSANDO' | 'INDEXADO' | 'ERRO' | 'SUBSTITUIDO' | 'REVOGADO'
export type TipoDoDocumento = 'MANUAL_OPERACOES' | 'PLANO_COMERCIAL' | 'ANEXO_PARCERIA' | 'FAQ' | 'COMUNICADO' | 'OUTRO'
export type EscopoDoDocumento = 'GLOBAL' | 'COTA' | 'PARCERIA'
export type AcaoDoDocumento = 'APROVAR' | 'RECUSAR' | 'REVOGAR' | 'REINDEXAR'
export type CodigoDaCota = ParceriaResumo['cota']['codigo']
export type TipoDeSuspeita = 'CPF' | 'RG' | 'CONTATOS'

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
  /** R2.1: as páginas em que o detector achou dado pessoal (só a RBS recebe; nunca o valor achado). */
  paginasSuspeitas?: number[]
  suspeitas?: { pagina: number; tipo: TipoDeSuspeita }[]
  /** R2.1: as páginas que a RBS deixou fora do índice do Atlas. O PDF continua inteiro no visualizador. */
  paginasForaDoAtlas?: number[]
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

/** O trecho inteiro, para o destaque no visualizador (rota 60). O texto é o que foi indexado. */
export type TrechoInteiro = {
  id: number
  documentoId: number
  ordem: number
  paginaInicio: number
  paginaFim: number
  secao: string | null
  texto: string
}

/** Um trecho numa das listas da busca de teste. A posição, a distância e a nota ficam nulas na lista em que ele não entrou. */
export type ItemDaBuscaDeTeste = {
  trechoId: number
  documentoId: number
  documento: string
  versao: number
  tipo: TipoDoDocumento
  pagina: number
  secao: string | null
  posicaoVetorial: number | null
  distancia: number | null
  posicaoTextual: number | null
  nota: number | null
  rrf: number
  inicioDoTexto: string
}

export type ResultadoDaBuscaDeTeste = {
  parceria: { id: number; patrocinador: string; edicao: number; cota: CodigoDaCota }
  consulta: string
  tipo: TipoDoDocumento | null
  k: number
  degradado: boolean
  motivoDoDegradado: 'EMBEDDINGS_DESLIGADOS' | 'EMBEDDING_FALHOU' | null
  erroDoEmbedding: string | null
  semTermoUtil: boolean
  embeddingMs: number | null
  sqlMs: number | null
  trechosDeOutroModelo: number
  vetorial: ItemDaBuscaDeTeste[]
  textual: ItemDaBuscaDeTeste[]
  escolhidos: ItemDaBuscaDeTeste[]
  visiveis: { documentoId: number; titulo: string; versao: number; tipo: TipoDoDocumento; escopo: EscopoDoDocumento; trechos: number }[]
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
  /** Páginas fora do Atlas, separadas por vírgula ("4" ou "4,7"). */
  paginasForaDoAtlas?: string
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
  if (novo.paginasForaDoAtlas) formulario.append('paginasForaDoAtlas', novo.paginasForaDoAtlas)
  return pedir<Documento>('/documentos', { metodo: 'POST', formulario })
}

export const listarDocumentos = (filtros: FiltrosDeDocumentos = {}) => pedir<Pagina<Documento>>(`/documentos${consulta(filtros)}`)

export const mudarStatusDoDocumento = (id: number, acao: AcaoDoDocumento, observacao?: string) =>
  pedir<Documento>(`/documentos/${id}/status`, { metodo: 'PATCH', corpo: { acao, observacao: observacao || undefined } })

/** R2.1 (rota 103): troca a lista inteira das páginas fora do Atlas, enquanto o documento aguarda aprovação; [] limpa. */
export const marcarPaginasForaDoAtlas = (id: number, paginasForaDoAtlas: number[]) =>
  pedir<Documento>(`/documentos/${id}/paginas-fora-do-atlas`, { metodo: 'PATCH', corpo: { paginasForaDoAtlas } })

export const listarTrechos = (id: number, pagina = 0) => pedir<Pagina<TrechoDoDocumento>>(`/documentos/${id}/trechos${consulta({ pagina })}`)

/** O arquivo do documento, para o visualizador (rota 59). Cada abertura entra no histórico. */
export const lerConteudoDoDocumento = (id: number, sinal?: AbortSignal) => pedirArquivo(`/documentos/${id}/conteudo`, sinal)

export const obterTrechoInteiro = (documentoId: number, trechoId: number) => pedir<TrechoInteiro>(`/documentos/${documentoId}/trechos/${trechoId}`)

/** A busca como o modelo a faria para esta parceria, sem o modelo (rota 61). */
export const buscarComoTeste = (corpo: { parceriaId: number | null; consulta: string; tipo?: string }) =>
  pedir<ResultadoDaBuscaDeTeste>('/atlas/busca-teste', { metodo: 'POST', corpo })
