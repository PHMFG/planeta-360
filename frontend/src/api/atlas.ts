// Atlas: a conversa da marca, o cartão de perguntas e o relatório de trabalho (rotas 42 a 48 do docs/api.md). [ATLAS]
// O Atlas monta e redige, mas nunca escreve um número: todo valor que chega aqui já veio formatado pelo servidor, com a origem.
import { ErroApi, pedir, pedirEmFluxo } from './cliente'
import type { CampoCatalogo, ValorInformado, ValorVigente } from './dados'
import type { UsuarioResumo } from './parcerias'

export type ModeloRelatorio = 'ATIVACAO_EVENTO'

export type CampoDoCartao = CampoCatalogo & { obrigatorio: boolean; vigente: ValorVigente | null }

export type StatusCartao = 'PENDENTE' | 'RESPONDIDA' | 'RECUSADA' | 'CANCELADA'

/** Uma ferramenta que o modelo usou, para o debug: argumentos e resultado em JSON cru, como ele enviou e como recebeu. */
export type FerramentaUsada = {
  nome: string
  argumentos: string
  resultado: string | null
  erro: boolean
  duracaoMs: number | null
}

/**
 * De onde veio o que a resposta cita: o marcador [T1] do texto aponta para uma destas. Título, versão, página e seção são os do
 * momento da resposta; {@code substituido} e {@code revogado}, os de agora. O documento revogado não abre mais.
 */
export type FonteDaResposta = {
  marcador: string
  tipo: 'TRECHO' | 'REGISTRO' | 'CALCULO'
  documentoId?: number
  titulo?: string
  versao?: number
  pagina?: number
  secao?: string | null
  trechoId?: number
  substituido?: boolean
  revogado?: boolean
}

/** {@code turno}: o turno do diagnóstico que escreveu o item. Só vem com o diagnóstico ligado para quem está na tela. */
export type ItemDaConversa = { turno?: string } & (
  | { tipo: 'usuario'; texto: string }
  /** O resumo do raciocínio do modelo, escrito no servidor e conferido como o texto. O raciocínio cru não sai do servidor. */
  | { tipo: 'raciocinio'; resumo: string }
  /** {@code porModelo} falso: frase fixa do servidor (o botão, sem modelo), que a tela não apresenta como fala do Atlas. */
  | { tipo: 'texto'; texto: string; porModelo?: boolean; fontes?: FonteDaResposta[] }
  | { tipo: 'aviso'; texto: string }
  | { tipo: 'cartao'; solicitacaoId: number; relatorioId: number; mensagem: string; status: StatusCartao; campos: CampoDoCartao[] }
  | { tipo: 'relatorio_pronto'; relatorioId: number; titulo: string }
  /** Só com o debug das ferramentas ligado no servidor. */
  | ({ tipo: 'ferramenta' } & FerramentaUsada)
)

/** A conversa inteira (rota 45) ou só o que um turno produziu (rotas 42, 44 e 46). */
export type Conversa = {
  conversaId: number
  /** Falso quando a API sobe sem modelo de linguagem: aí não há chat. */
  llmDisponivel: boolean
  /** Falso com atlas.relatorio.ligado desligado: o relatório fica desabilitado, e a tela não o oferece. */
  relatorioDisponivel: boolean
  /** Verdadeiro com atlas.debug.ferramentas ligado no servidor: a tela mostra as ferramentas que o modelo usou. */
  debugDeFerramentas: boolean
  /** Verdadeiro quando esta pessoa pode abrir o diagnóstico de cada resposta (rota 49). */
  diagnosticoDisponivel?: boolean
  itens: ItemDaConversa[]
}

export type ConversaResumo = {
  id: number
  criadaEm: string
  atualizadaEm: string
  relatorio: { id: number; status: StatusRelatorioAtlas } | null
}

export type ConversasDaParceria = { llmDisponivel: boolean; relatorioDisponivel: boolean; conversas: ConversaResumo[] }

export type RespostaDoCartao = { acao: 'accept' | 'decline' | 'cancel'; valores?: ValorInformado[] }

const ATALHO = { atalho: 'MONTAR_RELATORIO', modelo: 'ATIVACAO_EVENTO' } as const

export const listarConversas = (parceriaId: number) => pedir<ConversasDaParceria>(`/parcerias/${parceriaId}/atlas/conversas`)

/** Abre uma conversa nova; com {@code montarRelatorio}, já começa o relatório pelo botão (sem passar pelo modelo). */
export const abrirConversa = (parceriaId: number, montarRelatorio = false) =>
  pedir<Conversa>(`/parcerias/${parceriaId}/atlas/conversas`, { metodo: 'POST', corpo: montarRelatorio ? ATALHO : {} })

export const obterConversa = (conversaId: number) => pedir<Conversa>(`/atlas/conversas/${conversaId}`)

/** O que chega enquanto o Atlas responde, nas rotas 44 e 46 em fluxo. O texto já vem filtrado e com os números resolvidos. */
export type EventoDoTurno =
  /** O modelo começou a pensar nesta ida. */
  | { tipo: 'pensando' }
  /** O resumo do raciocínio de uma ida, quando fica pronto (pode chegar depois do texto dela). */
  | { tipo: 'resumo'; texto: string }
  | { tipo: 'etapa'; texto: string }
  | { tipo: 'texto'; delta: string }
  /** A checagem recusou o texto desta ida ao modelo: a tela o descarta e espera a nova redação ou a frase fixa. */
  | { tipo: 'texto_recusado' }
  /** Só com o debug das ferramentas ligado no servidor: uma ferramenta acabou de rodar. */
  | ({ tipo: 'ferramenta' } & FerramentaUsada)

type AoEvento = (evento: EventoDoTurno) => void

/** Um turno em fluxo: repassa os eventos e devolve o que o turno produziu, que chega no evento "fim". */
async function turnoEmFluxo(caminho: string, corpo: unknown, aoEvento: AoEvento, sinal?: AbortSignal): Promise<Conversa> {
  const desfecho: { fim?: Conversa; erro?: ErroApi } = {}
  const inteiro = await pedirEmFluxo(
    caminho,
    corpo,
    ({ nome, dados }) => {
      const campos = (dados ?? {}) as { delta?: string; texto?: string; status?: number; detail?: string }
      if (nome === 'fim') desfecho.fim = dados as Conversa
      else if (nome === 'erro') desfecho.erro = new ErroApi(campos.status ?? 500, campos.detail ?? 'O Atlas não terminou a resposta. Tente de novo.')
      else if (nome === 'texto') aoEvento({ tipo: 'texto', delta: campos.delta ?? '' })
      else if (nome === 'pensando') aoEvento({ tipo: 'pensando' })
      else if (nome === 'resumo' || nome === 'etapa') aoEvento({ tipo: nome, texto: campos.texto ?? '' })
      else if (nome === 'texto_recusado') aoEvento({ tipo: 'texto_recusado' })
      else if (nome === 'ferramenta') aoEvento({ tipo: 'ferramenta', ...(dados as FerramentaUsada) })
    },
    sinal,
  )
  if (inteiro !== undefined) return inteiro as Conversa
  if (desfecho.erro) throw desfecho.erro
  if (!desfecho.fim) throw new ErroApi(0, 'A resposta do Atlas parou no meio. Tente de novo.')
  return desfecho.fim
}

export const enviarMensagem = (conversaId: number, texto: string, aoEvento: AoEvento, sinal?: AbortSignal) =>
  turnoEmFluxo(`/atlas/conversas/${conversaId}/mensagens`, { texto }, aoEvento, sinal)

/** O botão "Montar relatório" numa conversa que já existe: continua o relatório que estiver montando, ou começa um. */
export const montarRelatorio = (conversaId: number) =>
  pedir<Conversa>(`/atlas/conversas/${conversaId}/mensagens`, { metodo: 'POST', corpo: ATALHO })

/** A resposta ao cartão retoma o turno do modelo, que continua em fluxo como uma mensagem. */
export const responderCartao = (solicitacaoId: number, resposta: RespostaDoCartao, aoEvento: AoEvento, sinal?: AbortSignal) =>
  turnoEmFluxo(`/atlas/solicitacoes/${solicitacaoId}/respostas`, resposta, aoEvento, sinal)

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
