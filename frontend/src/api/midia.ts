// A base de mídia (plano da base da RBS, M1 a M7): contratado × entregue por mês, veículo e formato, com impacto e
// comprovantes. Rotas 105 a 108 (contrato em docs/atlas/extracao/contrato-onda0.md; vão para o api.md com o back).
import { baixar, consulta, pedir } from './cliente'

export type FiltrosDeMidia = {
  mes?: string
  veiculo?: string
  formato?: string
  tipoMidia?: string
  unidade?: string
  /** Só na visão da RBS (rota 107). */
  eventoId?: number
  parceriaId?: number
  cota?: string
}

export type LinhaDeMidia = {
  aproveitamentoId: number
  parceria?: { id: number; patrocinador: string; cota: string }
  veiculo: string
  formato: string
  espacoPrograma: string | null
  tipoMidia: string | null
  unidade: string
  mes: string | null
  previsto: number | null
  exibido: number
  percentual: number | null
  impacto: number | null
  exibicoes: number
  comprovantes: { arquivoId: number; nome: string; tipo: string }[]
  links: string[]
}

export type TotalDeMidia = { unidade: string; previsto: number | null; exibido: number; percentual: number | null; impacto: number | null }

export type BaseDeMidia = {
  parceriaId?: number
  filtros: FiltrosDeMidia
  meses?: string[]
  totais: TotalDeMidia[]
  linhas: LinhaDeMidia[]
}

const filtrosNaUrl = (filtros: FiltrosDeMidia) => consulta(filtros as Record<string, string | number | undefined>)

export const obterMidiaDaParceria = (parceriaId: number, filtros: FiltrosDeMidia = {}) =>
  pedir<BaseDeMidia>(`/parcerias/${parceriaId}/midia${filtrosNaUrl(filtros)}`)

export const obterMidiaDaEdicao = (filtros: FiltrosDeMidia = {}) => pedir<BaseDeMidia>(`/midia${filtrosNaUrl(filtros)}`)

function nomeDoArquivo(base: string, filtros: FiltrosDeMidia, formato: 'xlsx' | 'csv') {
  return `${base}${filtros.mes ? `-${filtros.mes}` : ''}.${formato}`
}

export const baixarMidiaDaParceria = (parceriaId: number, apelido: string, filtros: FiltrosDeMidia, formato: 'xlsx' | 'csv') =>
  baixar(`/parcerias/${parceriaId}/midia.${formato}${filtrosNaUrl(filtros)}`, nomeDoArquivo(`midia-${apelido}`, filtros, formato))

/** O nome leva o ano da edição ("midia-edicao-2027-2026-10.xlsx"): sem ele, o mês parecia a edição (rodada H1). */
export const baixarMidiaDaEdicao = (filtros: FiltrosDeMidia, formato: 'xlsx' | 'csv', edicao?: number) =>
  baixar(`/midia.${formato}${filtrosNaUrl(filtros)}`, nomeDoArquivo(edicao ? `midia-edicao-${edicao}` : 'midia-da-edicao', filtros, formato))

export const NOME_DO_TIPO_DE_MIDIA: Record<string, string> = { ASSINADA: 'Assinada', PROPRIA: 'Própria', CONTEUDO_DE_MARCA: 'Conteúdo de marca' }
