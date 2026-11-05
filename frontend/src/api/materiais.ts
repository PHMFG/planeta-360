// Entregas, arquivos e histórico (rotas 14 a 22 do docs/api.md). [ITEM-4]
import { baixar, consulta, pedir, type Pagina } from './cliente'
import type { UsuarioResumo } from './parcerias'
import type { CodigoAba } from './tipos'
import type { StatusEntrega } from '../componentes/Status'

export type Arquivo = {
  id: number
  entregaId: number
  nomeOriginal: string
  mimeType: string
  tamanhoBytes: number
  versao: number
  categoria: string
  enviadoPor: UsuarioResumo | null
  enviadoEm: string
  /** O que quem enviou escreveu junto com esta versão. */
  observacao?: string | null
  statusDaEntrega?: StatusEntrega
}

export type Entrega = {
  id: number
  parceriaId: number
  abaCodigo: CodigoAba
  titulo: string
  descricao: string | null
  status: StatusEntrega
  prazo: string | null
  criadoEm: string
  atualizadoEm: string
  /** Da versão mais nova para a mais antiga. */
  arquivos: Arquivo[]
  ultimaObservacao: { texto: string; por: string; em: string } | null
  /** O espaço próprio da entrega: as solicitações de ingressos no Perfil, o MIV e a logo em Aplicações (D5 do plano). */
  papel?: 'DOCUMENTO_INGRESSOS' | 'MIV' | 'LOGO' | null
  /** Todas as versões, de arquivo, texto ou link, da mais nova para a mais antiga (rota 86). */
  versoes?: VersaoDaEntrega[]
}

/** Uma versão da entrega: um arquivo, ou um material em texto ou em link (R5.3.a). Os campos do outro tipo vêm nulos. */
export type VersaoDaEntrega = {
  tipo: 'ARQUIVO' | 'TEXTO' | 'LINK'
  id: number
  versao: number
  enviadoPor: UsuarioResumo | null
  enviadoEm: string
  observacao: string | null
  nomeOriginal: string | null
  mimeType: string | null
  tamanhoBytes: number | null
  categoria: string | null
  titulo: string | null
  texto: string | null
  url: string | null
  host: string | null
}

export const enviarConteudo = (
  entregaId: number,
  conteudo: { tipo: 'TEXTO' | 'LINK'; texto?: string; url?: string; titulo?: string; observacao?: string },
) => pedir<VersaoDaEntrega & { entregaId: number; statusDaEntrega: StatusEntrega }>(`/entregas/${entregaId}/conteudos`, { metodo: 'POST', corpo: conteudo })

/** Um link de post da parceria (rota 87): da carga de veiculação da RBS ou enviado como conteúdo numa entrega de Mídia. */
export type LinkDeMidia = {
  origem: 'CARGA' | 'ENVIO'
  url: string
  host: string
  data: string
  veiculo?: string | null
  formato?: string | null
  espacoPrograma?: string | null
  entregaTitulo?: string | null
  titulo?: string | null
  enviadoPor?: UsuarioResumo | null
}

export const listarLinksDeMidia = (parceriaId: number) =>
  pedir<{ parceriaId: number; itens: LinkDeMidia[] }>(`/parcerias/${parceriaId}/links-de-midia`)

export type ItemFila = {
  id: number
  parceria: { id: number; patrocinador: string; cota: string }
  abaCodigo: CodigoAba
  titulo: string
  status: StatusEntrega
  prazo: string | null
  atrasada: boolean
  atualizadoEm: string
  ultimoEnvioEm: string | null
}

export type ItemHistorico = {
  id: number
  parceria?: { id: number; patrocinador: string } | null
  acao: string
  entidade: string
  entidadeId: number | null
  valorAnterior: string | null
  valorNovo: string | null
  detalhe: string | null
  usuario: UsuarioResumo | null
  ocorridoEm: string
  ip: string | null
  userAgent: string | null
}

export const listarEntregas = (parceriaId: number, aba?: CodigoAba) =>
  pedir<Entrega[]>(`/parcerias/${parceriaId}/entregas${consulta({ aba })}`)

export const criarEntrega = (
  parceriaId: number,
  dados: { abaCodigo: CodigoAba; titulo: string; descricao?: string; prazo?: string; status?: StatusEntrega },
) => pedir<Entrega>(`/parcerias/${parceriaId}/entregas`, { metodo: 'POST', corpo: dados })

export type FiltrosFila = { status?: string; abaCodigo?: string; q?: string; atrasadas?: boolean; pagina?: number; tamanho?: number }

export const listarFila = (filtros: FiltrosFila) => pedir<Pagina<ItemFila>>(`/entregas${consulta(filtros)}`)

export function enviarArquivo(entregaId: number, arquivo: File, opcoes: { categoria?: string; observacao?: string } = {}) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  if (opcoes.categoria) formulario.append('categoria', opcoes.categoria)
  if (opcoes.observacao) formulario.append('observacao', opcoes.observacao)
  return pedir<Arquivo>(`/entregas/${entregaId}/arquivos`, { metodo: 'POST', formulario })
}

/** A API responde como anexo; se recusar, a mensagem dela volta como ErroApi para a tela mostrar. */
export const baixarArquivo = (arquivo: Pick<Arquivo, 'id' | 'nomeOriginal'>) => baixar(`/arquivos/${arquivo.id}`, arquivo.nomeOriginal)

export const mudarStatusEntrega = (entregaId: number, status: StatusEntrega, observacao?: string) =>
  pedir<Entrega>(`/entregas/${entregaId}/status`, { metodo: 'PATCH', corpo: { status, observacao: observacao || undefined } })

export const historicoDaParceria = (parceriaId: number, pagina = 0) =>
  pedir<Pagina<ItemHistorico>>(`/parcerias/${parceriaId}/historico${consulta({ pagina, tamanho: 50 })}`)

export type FiltrosHistorico = { parceriaId?: number; acao?: string; de?: string; ate?: string; q?: string; pagina?: number }

export const historicoGeral = (filtros: FiltrosHistorico) =>
  pedir<Pagina<ItemHistorico>>(`/historico${consulta({ ...filtros, tamanho: 50 })}`)

export const enderecoHistoricoCsv = (filtros: FiltrosHistorico) =>
  `/api/historico.csv${consulta({ ...filtros, pagina: undefined })}`

// [PERMISSAO] Mudanças de status que a API aceita (docs/api.md, rota 19); a tela só oferece estas.
export const TRANSICOES: Record<StatusEntrega, StatusEntrega[]> = {
  NAO_INICIADO: ['AGUARDANDO_ENVIO', 'NAO_APLICAVEL'],
  AGUARDANDO_ENVIO: ['NAO_APLICAVEL'],
  ENVIADO: ['EM_ANALISE', 'AJUSTE_SOLICITADO', 'APROVADO'],
  EM_ANALISE: ['AJUSTE_SOLICITADO', 'APROVADO'],
  AJUSTE_SOLICITADO: ['NAO_APLICAVEL'],
  APROVADO: ['EM_ANALISE'],
  NAO_APLICAVEL: ['NAO_INICIADO'],
}

/** Status em que o patrocinador ainda pode enviar arquivo (rota 17). */
export const ACEITA_ENVIO: StatusEntrega[] = ['NAO_INICIADO', 'AGUARDANDO_ENVIO', 'AJUSTE_SOLICITADO', 'ENVIADO']

/** Extensões aceitas no envio; a API confere de novo, inclusive o conteúdo do arquivo. */
export const EXTENSOES_ACEITAS = '.pdf,.png,.jpg,.jpeg,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.csv,.mp4,.mp3,.zip'
export const LIMITE_BYTES = 20 * 1024 * 1024
export const REGRA_ENVIO = 'PDF, PNG, JPG, Word, Excel, PowerPoint (sem macro), CSV, MP4, MP3 ou ZIP, até 20 MB'

/**
 * Confere no navegador o que a API vai recusar de qualquer jeito (tipo e tamanho), para avisar antes do envio. [UPLOAD]
 * A API confere de novo, inclusive o conteúdo do arquivo.
 */
export function problemaNoArquivo(arquivo: File): string | null {
  const extensao = arquivo.name.includes('.') ? `.${arquivo.name.split('.').pop()?.toLowerCase()}` : ''
  if (!EXTENSOES_ACEITAS.split(',').includes(extensao)) return `Este tipo de arquivo não é aceito. Envie ${REGRA_ENVIO}.`
  if (arquivo.size === 0) return 'O arquivo está vazio. Escolha o arquivo de novo.'
  if (arquivo.size > LIMITE_BYTES) return 'O arquivo passa do limite de 20 MB. Reduza o tamanho e escolha de novo.'
  return null
}

export const NOMES_ACAO: Record<string, string> = {
  CRIACAO: 'Criação',
  ENVIO_ARQUIVO: 'Envio de arquivo',
  NOVA_VERSAO: 'Nova versão',
  ALTERACAO_STATUS: 'Mudança de status',
  EDICAO: 'Edição',
  LIBERACAO_ABA: 'Liberação de aba',
  EXCLUSAO: 'Exclusão',
  GERACAO_RELATORIO: 'Geração do relatório',
  VALIDACAO: 'Validação do relatório',
  DISPONIBILIZACAO: 'Relatório disponibilizado',
  LOGIN: 'Entrada no portal',
  LOGIN_FALHOU: 'Tentativa de entrada recusada',
  LOGOUT: 'Saída do portal',
  DOWNLOAD: 'Download',
  AUTORIZACAO: 'E-mail autorizado',
  REVOGACAO: 'E-mail revogado',
  ATIVACAO: 'Primeiro acesso',
  CARGA: 'Carga de planilha',
}
