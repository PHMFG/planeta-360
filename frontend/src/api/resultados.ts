// Pesquisa, cargas, consolidação, painel e relatórios (rotas 23 a 36 do docs/api.md). [ITEM-5] [ITEM-6]
import { consulta, pedir, type Pagina } from './cliente'
import type { Cota, ResumoEntregas, UsuarioResumo } from './parcerias'
import type { StatusEntrega } from '../componentes/Status'

// ---------- Pesquisa ----------
export type Pergunta = {
  id: number
  ordem: number
  texto: string
  status: 'RASCUNHO' | 'ENVIADA' | 'APROVADA'
  observacaoRbs: string | null
  enviadaPor: UsuarioResumo | null
  enviadaEm: string | null
}

export type ResultadoPesquisa = {
  perguntaId: number
  ordem: number
  alternativa: string
  valor: number
  unidade: 'PERCENTUAL' | 'NOTA' | 'CONTAGEM'
  baseRespondentes: number
}

export type PesquisaDaParceria = {
  parceriaId: number
  abaLiberada: boolean
  limitePerguntas: number
  pesquisa: {
    id: number
    nome: string
    escopo: string
    metodologia: string | null
    baseRespondentes: number | null
    nivelConfianca: number | null
  } | null
  perguntas: Pergunta[]
  resultados: ResultadoPesquisa[]
}

export const obterPesquisa = (parceriaId: number) => pedir<PesquisaDaParceria>(`/parcerias/${parceriaId}/pesquisa`)

export const enviarPerguntas = (parceriaId: number, perguntas: { ordem: number; texto: string }[]) =>
  pedir<PesquisaDaParceria>(`/parcerias/${parceriaId}/pesquisa/perguntas`, { metodo: 'PUT', corpo: { perguntas } })

export const decidirPergunta = (perguntaId: number, decisao: 'APROVAR' | 'PEDIR_AJUSTE', observacao?: string) =>
  pedir<Pergunta>(`/pesquisa/perguntas/${perguntaId}/status`, {
    metodo: 'PATCH',
    corpo: { decisao, observacao: observacao || undefined },
  })

// ---------- Pesquisa da edição (rotas 92 a 95; R5.6.d) ----------
export type EscopoDaPesquisa = 'ATIVACAO' | 'GERAL'

export type PesquisaDaEdicao = {
  id: number
  eventoId: number
  nome: string
  escopo: EscopoDaPesquisa
  metodologia: string | null
  baseRespondentes: number | null
  nivelConfianca: number | null
  perguntas: { rascunho: number; enviadas: number; aprovadas: number }
  resultados: number
  perguntasLigadas: number | null
}

export type DadosDaPesquisa = { nome?: string; escopo?: EscopoDaPesquisa; metodologia?: string; baseRespondentes?: number; nivelConfianca?: number }

export type PerguntaDaFila = {
  id: number
  parceriaId: number
  patrocinador: string
  eventoId: number
  edicao: number
  pesquisaId: number | null
  ordem: number
  texto: string
  status: Pergunta['status']
  enviadaPor: UsuarioResumo | null
  enviadaEm: string | null
}

export const listarPesquisasDaEdicao = (eventoId: number) => pedir<PesquisaDaEdicao[]>(`/eventos/${eventoId}/pesquisas`)

export const abrirPesquisa = (eventoId: number, dados: DadosDaPesquisa) =>
  pedir<PesquisaDaEdicao>(`/eventos/${eventoId}/pesquisas`, { metodo: 'POST', corpo: dados })

export const editarPesquisa = (pesquisaId: number, dados: DadosDaPesquisa) =>
  pedir<PesquisaDaEdicao>(`/pesquisas/${pesquisaId}`, { metodo: 'PATCH', corpo: dados })

export const listarPerguntasDaFila = (filtros: { status?: Pergunta['status']; eventoId?: number; pagina?: number }) =>
  pedir<Pagina<PerguntaDaFila>>(`/pesquisa/perguntas${consulta({ ...filtros, tamanho: 50 })}`)

// ---------- Cargas ----------
export type Carga = {
  id: number
  tipo: 'VEICULACAO' | 'PESQUISA' | 'PLANO'
  eventoId: number
  arquivoId: number | null
  executadaPor: UsuarioResumo | null
  executadaEm: string
  processadaEm: string | null
  linhasLidas: number
  linhasInseridas: number
  linhasDuplicadas: number
  linhasRejeitadas: number
}

export type Rejeicao = { linha: number; campo: string | null; motivo: string; conteudo: string | null }

export function enviarCarga(dados: { arquivo: File; tipo: Carga['tipo']; eventoId: number; escopo?: 'ATIVACAO' | 'GERAL' }) {
  const formulario = new FormData()
  formulario.append('arquivo', dados.arquivo)
  formulario.append('tipo', dados.tipo)
  formulario.append('eventoId', String(dados.eventoId))
  if (dados.tipo === 'PESQUISA' && dados.escopo) formulario.append('escopo', dados.escopo)
  return pedir<Carga>('/cargas', { metodo: 'POST', formulario })
}

export const listarCargas = (pagina = 0) => pedir<Pagina<Carga>>(`/cargas${consulta({ pagina, tamanho: 20 })}`)

export const listarRejeicoes = (cargaId: number, pagina = 0) =>
  pedir<Pagina<Rejeicao>>(`/cargas/${cargaId}/rejeicoes${consulta({ pagina, tamanho: 50 })}`)

// ---------- Consolidação ----------
export type LinhaMidia = {
  aproveitamentoId?: number
  veiculo?: string
  formato?: string
  espacoPrograma?: string | null
  unidade: string
  previsto: number | null
  exibido: number | null
  percentual: number | null
}

export type Consolidacao = {
  parceriaId: number
  midia: { porUnidade: LinhaMidia[]; porVeiculo: LinhaMidia[]; linhas: LinhaMidia[] }
  aplicacoes: { previstas: number | null; comprovadas: number | null; fotos: number; percentual: number | null }
  entregas: ResumoEntregas
  cortesias: { disponiveis: number | null; destinadas: number | null; percentual: number | null }
  faltantes: { tipo: string; detalhe: string }[]
}

export const obterConsolidacao = (parceriaId: number) => pedir<Consolidacao>(`/parcerias/${parceriaId}/consolidacao`)

// ---------- Painel da RBS ----------
export type Painel = {
  evento: { id: number; nome: string; edicao: number }
  parcerias: { total: number; ativas: number; semUsuario: number }
  entregas: { porStatus: Partial<Record<StatusEntrega, number>>; esperandoAnalise: number; atrasadas: number }
  /** marcaVe: parcerias com alguma versão disponibilizada, mesmo que a mais nova ainda seja rascunho. */
  relatorios: { semRelatorio: number; rascunho: number; validado: number; disponibilizado: number; marcaVe?: number }
  ultimaCarga: { id: number; tipo: string; executadaEm: string; linhasInseridas: number; linhasRejeitadas: number } | null
  pendencias: { parceriaId: number; patrocinador: string; tipo: string; detalhe: string }[]
}

export const obterPainel = (eventoId?: number) => pedir<Painel>(`/painel${consulta({ eventoId })}`)

// ---------- Relatórios ----------
export type StatusRelatorio = 'RASCUNHO' | 'VALIDADO' | 'DISPONIBILIZADO'

export type LinhaRelatorios = {
  parceriaId: number
  patrocinador: string
  cota: Cota
  evento: { id: number; nome: string; edicao: number }
  situacao: 'SEM_RELATORIO' | StatusRelatorio
  relatorio: {
    id: number
    versao: number
    status: StatusRelatorio
    geradoEm: string
    validadoEm: string | null
    disponibilizadoEm: string | null
    indicadoresDisponiveis: number
    indicadoresIndisponiveis: number
  } | null
  /** A versão disponibilizada mais nova, que é a que a marca vê (pode ser anterior à mais nova). */
  disponibilizada?: { id: number; versao: number; disponibilizadoEm: string } | null
  faltantes: number
}

export type Indicador = {
  codigo: string
  nome: string
  recorte: string
  valor: number | null
  unidade: string | null
  baseCalculo: string | null
  disponivel: boolean
}

export type Relatorio = {
  id: number
  parceriaId: number
  versao: number
  status: StatusRelatorio
  geradoEm: string
  geradoPor: UsuarioResumo | null
  validadoEm: string | null
  disponibilizadoEm: string | null
  secoes: { secao: string; indicadores: Indicador[] }[]
}

export const listarRelatorios = (filtros: { eventoId?: number; status?: string } = {}) =>
  pedir<LinhaRelatorios[]>(`/relatorios${consulta(filtros)}`)

export const enderecoRelatoriosCsv = (filtros: { eventoId?: number; status?: string } = {}) =>
  `/api/relatorios.csv${consulta(filtros)}`

export const gerarRelatorio = (parceriaId: number) =>
  pedir<Relatorio>(`/parcerias/${parceriaId}/relatorios`, { metodo: 'POST' })

export const mudarStatusRelatorio = (relatorioId: number, status: 'VALIDADO' | 'DISPONIBILIZADO') =>
  pedir<Relatorio>(`/relatorios/${relatorioId}/status`, { metodo: 'PATCH', corpo: { status } })

export const obterRelatorio = (parceriaId: number, versao?: number) =>
  pedir<Relatorio>(`/parcerias/${parceriaId}/relatorio${consulta({ versao })}`)

export const enderecoRelatorioCsv = (relatorioId: number) => `/api/relatorios/${relatorioId}/csv`
