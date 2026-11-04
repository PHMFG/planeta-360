// Parcerias, eventos e e-mails autorizados (rotas 6 a 13 do docs/api.md). [ITEM-3]
import { pedir } from './cliente'
import type { AbaLiberada, CodigoAba, ParceriaResumo } from './tipos'

export type Evento = {
  id: number
  nome: string
  edicao: number
  dataInicio: string | null
  dataFim: string | null
  local: string | null
}

export type Cota = ParceriaResumo['cota']
export type Situacao = 'EM_NEGOCIACAO' | 'ATIVA' | 'ENCERRADA' | 'CANCELADA'
export type Vinculo = 'MARCA' | 'AGENCIA'

export type ResumoEntregas = {
  total: number
  aprovadas: number
  pendentes: number
  naoAplicaveis: number
  percentualConcluido: number | null
}

export type ParceriaNaLista = {
  id: number
  patrocinador: { id: number; nome: string }
  evento: { id: number; nome: string; edicao: number }
  cota: Cota
  nomeProduto: string | null
  situacao: Situacao
  entregas: ResumoEntregas
  usuarios: number | null
  relatorio: { id: number; versao: number; status: string } | null
}

export type EmailResumo = { id: number; email: string; vinculo: Vinculo; situacao: 'ATIVO' | 'PENDENTE' }

export type ParceriaDetalhe = {
  id: number
  patrocinador: { id: number; nome: string; segmento: string | null }
  evento: { id: number; nome: string; edicao: number }
  cota: Cota
  nomeProduto: string | null
  valorInvestimento: number | null
  baseValor: 'BRUTO_TABELA' | 'LIQUIDO' | null
  situacao: Situacao
  criadoEm: string
  abas: AbaLiberada[]
  emailsAutorizados: EmailResumo[]
}

export type UsuarioResumo = { id: number; nome: string; perfil: string }

export type EmailAutorizado = {
  id: number
  email: string
  vinculo: Vinculo
  situacao: 'ATIVO' | 'PENDENTE' | 'REVOGADO'
  usuarioId: number | null
  autorizadoPor: UsuarioResumo | null
  autorizadoEm: string
  revogadoPor: UsuarioResumo | null
  revogadoEm: string | null
  /** Só vem na resposta de quem acabou de autorizar; depois não há como ver de novo. */
  codigoAtivacao?: string | null
  codigoExpiraEm?: string | null
}

export type NovaParceria = {
  patrocinador: { id: number } | { nome: string; segmento?: string }
  eventoId: number
  cotaCodigo: Cota['codigo']
  nomeProduto?: string
  emails: { email: string; vinculo: Vinculo }[]
}

/** A parceria recém-criada traz, em cada e-mail, o código de ativação para a RBS repassar. */
export type ParceriaCriada = Omit<ParceriaDetalhe, 'emailsAutorizados'> & { emailsAutorizados: EmailAutorizado[] }

export type MudancaParceria = {
  patrocinador?: { nome?: string; segmento?: string | null }
  nomeProduto?: string | null
  situacao?: Situacao
  valorInvestimento?: number | null
  baseValor?: 'BRUTO_TABELA' | 'LIQUIDO' | null
  abas?: { codigo: CodigoAba; liberada?: boolean; visivel?: boolean }[]
}

export const listarEventos = () => pedir<Evento[]>('/eventos')

export function listarParcerias(filtros: { eventoId?: number; q?: string } = {}) {
  const busca = new URLSearchParams()
  if (filtros.eventoId) busca.set('eventoId', String(filtros.eventoId))
  if (filtros.q) busca.set('q', filtros.q)
  const texto = busca.toString()
  return pedir<ParceriaNaLista[]>(`/parcerias${texto ? `?${texto}` : ''}`)
}

export const obterParceria = (id: number) => pedir<ParceriaDetalhe>(`/parcerias/${id}`)

export const criarParceria = (dados: NovaParceria) =>
  pedir<ParceriaCriada>('/parcerias', { metodo: 'POST', corpo: dados })

export const alterarParceria = (id: number, mudanca: MudancaParceria) =>
  pedir<ParceriaDetalhe>(`/parcerias/${id}`, { metodo: 'PATCH', corpo: mudanca })

export const listarEmails = (id: number) => pedir<EmailAutorizado[]>(`/parcerias/${id}/emails`)

export const autorizarEmail = (id: number, email: string, vinculo: Vinculo) =>
  pedir<EmailAutorizado>(`/parcerias/${id}/emails`, { metodo: 'POST', corpo: { email, vinculo } })

export const revogarEmail = (id: number, emailId: number) =>
  pedir(`/parcerias/${id}/emails/${emailId}`, { metodo: 'DELETE' })

/** O detalhe da parceria tem o patrocinador como objeto; o menu usa o formato curto da sessão. */
export function paraResumo(detalhe: ParceriaDetalhe): ParceriaResumo {
  return {
    id: detalhe.id,
    patrocinador: detalhe.patrocinador.nome,
    cota: detalhe.cota,
    evento: detalhe.evento,
    abas: detalhe.abas,
  }
}
