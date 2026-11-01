// O diagnóstico do Atlas: o traço de cada turno e o estado dos provedores (rotas 49 a 53 do docs/api.md). [ATLAS]
// O raciocínio cru, o prompt de sistema e as chaves nunca chegam aqui: o traço já vem sanitizado pelo servidor.
import { consulta, pedir, type Pagina } from './cliente'
import type { UsuarioResumo } from './parcerias'

export type ResultadoDaEtapa = 'OK' | 'RECUSADO' | 'ERRO' | 'SOMBRA' | 'PULADO'

export type EtapaDoTraco = {
  ordem: number
  etapa: string
  resultado: ResultadoDaEtapa
  iniciadaEm: string
  duracaoMs: number
  mensagemId: number | null
  dados: Record<string, unknown>
}

export type TurnoDoTraco = {
  turno: string
  mensagemId: number | null
  iniciadoEm: string
  duracaoMs: number
  resultado: ResultadoDaEtapa
  tokensEntrada: number
  tokensSaida: number
  custoUsd: number
  etapas: EtapaDoTraco[]
  /** Só na rota 51, em que a RBS olha um turno de qualquer parceria. */
  conversaId?: number
  parceriaId?: number
  parceria?: string
  usuario?: UsuarioResumo
}

export type DiagnosticoDaConversa = {
  conversaId: number
  tokensEntrada: number
  tokensSaida: number
  tokens: number
  custoUsd: number
  turnos: TurnoDoTraco[]
}

export type TurnoDaLista = {
  turno: string
  conversaId: number
  parceriaId: number
  parceria: string
  usuario: UsuarioResumo
  mensagemId: number | null
  iniciadoEm: string
  duracaoMs: number
  resultado: ResultadoDaEtapa
  etapas: number
  tokensEntrada: number
  tokensSaida: number
  custoUsd: number
}

export type FiltrosDeTurnos = { parceriaId?: number; de?: string; ate?: string; resultado?: string; pagina?: number; tamanho?: number }

export type NomeDoProvedor = 'llm' | 'jev' | 'gemini'

export type EstadoDoProvedor = {
  nome: NomeDoProvedor
  configurado: boolean
  origem: 'AMBIENTE' | 'DEBUG' | 'NENHUM'
  modelo: string | null
  baseUrl: string | null
  finalDaChave: string | null
  modo: string | null
  chamadas24h: number
  erros24h: number
  latenciaP50Ms: number | null
  latenciaP95Ms: number | null
  disjuntor: string | null
  /** Só no JEV: o modo e o limiar de cada ponto de decisão. */
  pontos?: { ponto: string; modo: string; limiar: number }[] | null
}

export type EstadoDosProvedores = {
  provedores: EstadoDoProvedor[]
  tetoDiario: { limite: number; parcerias: { parceriaId: number; parceria: string; usadas: number }[] }
}

export type TesteDoProvedor = { provedor: NomeDoProvedor; ok: boolean; chamou: boolean; milissegundos: number | null; modelo: string | null; resposta?: string; erro?: string }

export const diagnosticoDaConversa = (conversaId: number) => pedir<DiagnosticoDaConversa>(`/atlas/conversas/${conversaId}/diagnostico`)

export const listarTurnos = (filtros: FiltrosDeTurnos) => pedir<Pagina<TurnoDaLista>>(`/atlas/diagnostico/turnos${consulta(filtros)}`)

export const obterTurno = (turno: string) => pedir<TurnoDoTraco>(`/atlas/diagnostico/turnos/${encodeURIComponent(turno)}`)

export const estadoDosProvedores = () => pedir<EstadoDosProvedores>('/atlas/provedores')

export const testarProvedor = (nome: NomeDoProvedor) => pedir<TesteDoProvedor>(`/atlas/provedores/${nome}/teste`, { metodo: 'POST' })
