// Credenciamento (rotas 77 a 79 do docs/api.md): as credenciais por setor e beneficiário que a RBS dá à parceria, e o
// endereço do Open Staff e o período de cadastro da edição. O cadastro das pessoas é feito no Open Staff, fora do portal.
import { pedir } from './cliente'

export type SetorCredencial = 'ARENA' | 'CAMAROTE' | 'BAIXO_CAMAROTE' | 'PREMIUM' | 'SALA_IMPRENSA' | 'SALA_CONTEUDO'
export type Beneficiario = 'MARCA' | 'AGENCIA' | 'EQUIPE_CONTEUDO' | 'GERAL'

export const SETORES_CREDENCIAL: SetorCredencial[] = ['ARENA', 'CAMAROTE', 'BAIXO_CAMAROTE', 'PREMIUM', 'SALA_IMPRENSA', 'SALA_CONTEUDO']
export const BENEFICIARIOS: Beneficiario[] = ['MARCA', 'AGENCIA', 'EQUIPE_CONTEUDO', 'GERAL']
export const NOME_SETOR_CREDENCIAL: Record<SetorCredencial, string> = {
  ARENA: 'Arena',
  CAMAROTE: 'Camarote',
  BAIXO_CAMAROTE: 'Baixo Camarote',
  PREMIUM: 'Premium',
  SALA_IMPRENSA: 'Sala de imprensa',
  SALA_CONTEUDO: 'Sala de conteúdo',
}
export const NOME_BENEFICIARIO: Record<Beneficiario, string> = {
  MARCA: 'Marca',
  AGENCIA: 'Agência',
  EQUIPE_CONTEUDO: 'Equipe de conteúdo',
  GERAL: 'Geral',
}

export type Credencial = { id: number; setor: SetorCredencial; beneficiario: Beneficiario; quantidadePorDia: number }

export type Credenciais = {
  parceriaId: number
  eventoId: number
  totalPorDia: number
  credenciais: Credencial[]
  openStaffUrl: string | null
  credenciamentoInicio: string | null
  credenciamentoFim: string | null
}

export const obterCredenciais = (parceriaId: number) => pedir<Credenciais>(`/parcerias/${parceriaId}/credenciais`)

export const gravarCredenciais = (
  parceriaId: number,
  credenciais: { setor: SetorCredencial; beneficiario: Beneficiario; quantidadePorDia: number }[],
) => pedir<Credenciais>(`/parcerias/${parceriaId}/credenciais`, { metodo: 'PATCH', corpo: { credenciais } })

export const definirCredenciamentoDaEdicao = (eventoId: number, corpo: { openStaffUrl: string | null; inicio: string | null; fim: string | null }) =>
  pedir(`/eventos/${eventoId}/credenciamento`, { metodo: 'PATCH', corpo })
