// O selo "novo" (plano da base da RBS, S3; rota 120): a visita de cada pessoa a cada aba fica gravada, e a API diz o que mudou
// depois dela (veiculação ou comprovante, status de entrega, resultado da pesquisa, relatório disponibilizado). Sem e-mail (D7).
import { pedir } from './cliente'
import type { CodigoAba } from './tipos'

export type AbaComNovidade = CodigoAba | 'RELATORIO'

export type Novidades = { abas: Partial<Record<AbaComNovidade, { novo: boolean; desde: string | null }>> }

export const obterNovidades = (parceriaId: number) => pedir<Novidades>(`/parcerias/${parceriaId}/novidades`)

export const registrarVisita = (parceriaId: number, aba: AbaComNovidade) =>
  pedir<void>(`/parcerias/${parceriaId}/visitas/${aba}`, { metodo: 'POST' })
