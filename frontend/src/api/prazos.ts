// Os prazos da edição, da cota e da parceria, que o Atlas consulta (rotas 62 a 64 do docs/api.md). Só a RBS os mantém. [ATLAS]
import { consulta, pedir } from './cliente'
import type { CodigoDaCota, EscopoDoDocumento, StatusDoDocumento } from './documentos'
import type { UsuarioResumo } from './parcerias'

export type Prazo = {
  id: number
  eventoId: number
  escopo: EscopoDoDocumento
  cota: { codigo: CodigoDaCota; nome: string } | null
  parceria: { id: number; patrocinador: string } | null
  titulo: string
  /** AAAA-MM-DD */
  data: string
  /** HH:mm */
  hora: string | null
  descricao: string | null
  /** De onde o prazo saiu. O prazo cujo documento deixou de valer não vai para o Atlas. */
  documento: { id: number; titulo: string; versao: number; status: StatusDoDocumento } | null
  pagina: number | null
  ativo: boolean
  criadoPor: UsuarioResumo | null
  criadoEm: string
}

export type NovoPrazo = {
  escopo: EscopoDoDocumento
  cota?: CodigoDaCota
  parceriaId?: number
  titulo: string
  data: string
  hora?: string
  descricao?: string
  documentoId?: number
  pagina?: number
}

export const listarPrazos = (eventoId: number, incluirInativos: boolean) =>
  pedir<Prazo[]>(`/eventos/${eventoId}/prazos${consulta({ incluirInativos: incluirInativos || undefined })}`)

export const criarPrazo = (eventoId: number, prazo: NovoPrazo) => pedir<Prazo>(`/eventos/${eventoId}/prazos`, { metodo: 'POST', corpo: prazo })

/** Desativa: o prazo some do Atlas e continua no histórico. */
export const desativarPrazo = (prazoId: number) => pedir<void>(`/prazos/${prazoId}`, { metodo: 'DELETE' })
