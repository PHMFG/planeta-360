// Atlas: a conversa da marca, o cartão de perguntas e o relatório de trabalho (rotas 42 a 48 do docs/api.md). [ATLAS]
// O Atlas monta e redige, mas nunca escreve um número: todo valor que chega aqui já veio formatado pelo servidor, com a origem.
import { pedir } from './cliente'
import type { CampoCatalogo, ValorInformado, ValorVigente } from './dados'
import type { UsuarioResumo } from './parcerias'

export type ModeloRelatorio = 'ATIVACAO_EVENTO'

export type CampoDoCartao = CampoCatalogo & { obrigatorio: boolean; vigente: ValorVigente | null }

export type StatusCartao = 'PENDENTE' | 'RESPONDIDA' | 'RECUSADA' | 'CANCELADA'

export type ItemDaConversa =
  | { tipo: 'usuario'; texto: string }
  | { tipo: 'texto'; texto: string }
  | { tipo: 'aviso'; texto: string }
  | { tipo: 'cartao'; solicitacaoId: number; relatorioId: number; mensagem: string; status: StatusCartao; campos: CampoDoCartao[] }
  | { tipo: 'relatorio_pronto'; relatorioId: number; titulo: string }

/** A conversa inteira (rota 45) ou só o que um turno produziu (rotas 42, 44 e 46). */
export type Conversa = {
  conversaId: number
  /** Falso quando a API sobe sem modelo de linguagem: aí o Atlas só monta o relatório pelo botão (plano B). */
  llmDisponivel: boolean
  itens: ItemDaConversa[]
}

export type ConversaResumo = {
  id: number
  criadaEm: string
  atualizadaEm: string
  relatorio: { id: number; status: StatusRelatorioAtlas } | null
}

export type ConversasDaParceria = { llmDisponivel: boolean; conversas: ConversaResumo[] }

export type RespostaDoCartao = { acao: 'accept' | 'decline' | 'cancel'; valores?: ValorInformado[] }

const ATALHO = { atalho: 'MONTAR_RELATORIO', modelo: 'ATIVACAO_EVENTO' } as const

export const listarConversas = (parceriaId: number) => pedir<ConversasDaParceria>(`/parcerias/${parceriaId}/atlas/conversas`)

/** Abre uma conversa nova; com {@code montarRelatorio}, já começa o relatório pelo botão (sem passar pelo modelo). */
export const abrirConversa = (parceriaId: number, montarRelatorio = false) =>
  pedir<Conversa>(`/parcerias/${parceriaId}/atlas/conversas`, { metodo: 'POST', corpo: montarRelatorio ? ATALHO : {} })

export const obterConversa = (conversaId: number) => pedir<Conversa>(`/atlas/conversas/${conversaId}`)

export const enviarMensagem = (conversaId: number, texto: string) =>
  pedir<Conversa>(`/atlas/conversas/${conversaId}/mensagens`, { metodo: 'POST', corpo: { texto } })

/** O botão "Montar relatório" numa conversa que já existe: continua o relatório que estiver montando, ou começa um. */
export const montarRelatorio = (conversaId: number) =>
  pedir<Conversa>(`/atlas/conversas/${conversaId}/mensagens`, { metodo: 'POST', corpo: ATALHO })

export const responderCartao = (solicitacaoId: number, resposta: RespostaDoCartao) =>
  pedir<Conversa>(`/atlas/solicitacoes/${solicitacaoId}/respostas`, { metodo: 'POST', corpo: resposta })

// ---------- Relatório do Atlas ----------
export type StatusRelatorioAtlas = 'MONTANDO' | 'AGUARDANDO_RESPOSTA' | 'PRONTO' | 'DESCARTADO'

/** Um número do relatório: já formatado, ou indisponível com o motivo. Nunca zero no lugar de "sem dado". */
export type ItemKpi = { rotulo: string; valor: string | null; indisponivel: string | null; ref: string | null }

export type Bloco =
  | { id: string; tipo: 'capa'; titulo: string; subtitulo: string | null; edicao: string | null }
  | { id: string; tipo: 'titulo'; texto: string }
  | { id: string; tipo: 'paragrafo'; texto: string; redigidoPor: 'ATLAS' | 'MODELO' }
  | { id: string; tipo: 'kpi'; item: ItemKpi }
  | { id: string; tipo: 'kpi_grade'; itens: ItemKpi[] }
  | { id: string; tipo: 'tabela'; colunas: string[]; linhas: string[][]; ref: string | null }
  | {
      id: string
      tipo: 'galeria_comprovacoes'
      itens: { arquivoId: number; nomeOriginal: string; aplicacao: string; descricao: string | null; registradoEm: string }[]
    }
  | { id: string; tipo: 'campo_informado'; rotulo: string; valor: string | null; origem: string | null }
  | { id: string; tipo: 'aviso_indisponivel'; motivo: string }
  | { id: string; tipo: 'fontes'; itens: { ref: string; rotulo: string; valor: string | null; origem: string }[] }

export type SecaoDoRelatorio = { id: string; titulo: string | null; blocos: Bloco[] }

export type RelatorioAtlas = {
  id: number
  parceriaId: number
  patrocinador: string
  modelo: ModeloRelatorio
  titulo: string
  status: StatusRelatorioAtlas
  criadoPor: UsuarioResumo | null
  criadoEm: string
  finalizadoEm: string | null
  /** "Documento de trabalho da marca. Não validado pela RBS." */
  selo: string
  secoes: SecaoDoRelatorio[]
}

export type RelatorioAtlasResumo = Omit<RelatorioAtlas, 'parceriaId' | 'patrocinador' | 'selo' | 'secoes'>

export const obterRelatorioAtlas = (relatorioId: number) => pedir<RelatorioAtlas>(`/atlas/relatorios/${relatorioId}`)

export const listarRelatoriosAtlas = (parceriaId: number) => pedir<RelatorioAtlasResumo[]>(`/parcerias/${parceriaId}/atlas/relatorios`)

export const NOME_STATUS_ATLAS: Record<StatusRelatorioAtlas, string> = {
  MONTANDO: 'Montando',
  AGUARDANDO_RESPOSTA: 'Esperando os números',
  PRONTO: 'Pronto',
  DESCARTADO: 'Descartado',
}
