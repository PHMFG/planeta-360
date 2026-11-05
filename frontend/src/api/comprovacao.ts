// A comprovação mensal da mídia (rotas 88 a 91 do docs/api.md; requisitos R3.2 e R3.3 da RBS): só das cotas Master e da RBS.
import { baixar, pedir } from './cliente'

export type LinhaDaComprovacao = {
  mes: string
  tipoMidia: string
  tipoMidiaNome: string
  unidade: string
  unidadeNome: string
  exibido: number
  previsto: number | null
}

export type TotalDoMes = { mes: string; unidade: string; unidadeNome: string; exibido: number }

export type ComprovacaoMensal = { parceriaId: number; patrocinador: string; linhas: LinhaDaComprovacao[]; totais: TotalDoMes[] }

export const obterComprovacaoMensal = (parceriaId: number) => pedir<ComprovacaoMensal>(`/parcerias/${parceriaId}/comprovacao-mensal`)

/** "Cliente Amarelo" → "cliente-amarelo", para o nome do arquivo baixado. */
function apelido(nome: string) {
  return (
    nome
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '') || 'parceria'
  )
}

export const baixarComprovacaoMensal = (parceriaId: number, patrocinador: string, formato: 'xlsx' | 'csv') =>
  baixar(`/parcerias/${parceriaId}/comprovacao-mensal.${formato}`, `comprovacao-mensal-${apelido(patrocinador)}.${formato}`)

export const baixarLoteDaComprovacao = (eventoId: number, edicao: string | number) =>
  baixar(`/eventos/${eventoId}/comprovacao-mensal.xlsx`, `comprovacao-mensal-edicao-${apelido(String(edicao))}.xlsx`)

/** "2026-09" → "setembro de 2026". */
export function nomeDoMes(mes: string) {
  const [ano, numeroDoMes] = mes.split('-').map(Number)
  if (!ano || !numeroDoMes) return mes
  return new Date(Date.UTC(ano, numeroDoMes - 1, 1)).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' })
}
