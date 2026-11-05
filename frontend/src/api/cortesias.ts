// Cortesias da parceria (rotas 80 a 85 do docs/api.md): a cota por setor e dia que a RBS grava, e a destinação pela marca.
import { pedir } from './cliente'

export type Setor = 'ARENA' | 'CAMAROTE' | 'PREMIUM' | 'LOUNGE'
export type DiaCortesia = 'PASSAPORTE' | 'SEXTA' | 'SABADO'

export const SETORES: Setor[] = ['ARENA', 'CAMAROTE', 'PREMIUM', 'LOUNGE']
export const DIAS: DiaCortesia[] = ['PASSAPORTE', 'SEXTA', 'SABADO']
export const NOME_SETOR: Record<Setor, string> = { ARENA: 'Arena', CAMAROTE: 'Camarote', PREMIUM: 'Premium', LOUNGE: 'Lounge' }
export const NOME_DIA: Record<DiaCortesia, string> = { PASSAPORTE: 'Passaporte', SEXTA: 'Sexta', SABADO: 'Sábado' }

export type Destinacao = {
  id: number
  /** Só um nome, em texto (D8 do plano): sem e-mail, CPF nem telefone. */
  destinatario: string
  quantidade: number
  registradoPor: { id: number; nome: string; perfil: string }
  registradoEm: string
}

export type CelulaCortesia = {
  id: number
  setor: Setor
  dia: DiaCortesia
  cota: number
  destinadas: number
  disponiveis: number
  acesso: string | null
  comShuttle: boolean
  destinacoes: Destinacao[]
}

export type Cortesias = {
  parceriaId: number
  cortesiasPrazo: string | null
  prazoEncerrado: boolean
  totais: { cota: number | null; destinadas: number | null; disponiveis: number | null; percentual: number | null }
  cotas: CelulaCortesia[]
}

export type CelulaDaRbs = { setor: Setor; dia: DiaCortesia; quantidade: number; comShuttle: boolean; acesso: string | null }

export const obterCortesias = (parceriaId: number) => pedir<Cortesias>(`/parcerias/${parceriaId}/cortesias`)

export const gravarCotasDeCortesia = (parceriaId: number, cotas: CelulaDaRbs[]) =>
  pedir<Cortesias>(`/parcerias/${parceriaId}/cortesias/cotas`, { metodo: 'PATCH', corpo: { cotas } })

export const definirPrazoDeCortesias = (eventoId: number, cortesiasPrazo: string | null) =>
  pedir<{ eventoId: number; edicao: number; cortesiasPrazo: string | null }>(`/eventos/${eventoId}/cortesias`, {
    metodo: 'PATCH',
    corpo: { cortesiasPrazo },
  })

export const destinarCortesias = (parceriaId: number, corpo: { setor: Setor; dia: DiaCortesia; quantidade: number; destinatario: string }) =>
  pedir<Cortesias>(`/parcerias/${parceriaId}/cortesias/destinacoes`, { metodo: 'POST', corpo })

export const editarDestinacao = (parceriaId: number, destinacaoId: number, corpo: { destinatario?: string; quantidade?: number }) =>
  pedir<Cortesias>(`/parcerias/${parceriaId}/cortesias/destinacoes/${destinacaoId}`, { metodo: 'PATCH', corpo })

export const retirarDestinacao = (parceriaId: number, destinacaoId: number) =>
  pedir(`/parcerias/${parceriaId}/cortesias/destinacoes/${destinacaoId}`, { metodo: 'DELETE' })
