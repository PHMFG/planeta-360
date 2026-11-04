// Contrato da parceria (rotas 74 a 76 do docs/api.md): quem assina, que a marca informa, e o contrato assinado, que a RBS sobe.
import { lerArquivo, pedir } from './cliente'
import type { UsuarioResumo } from './parcerias'

export type Signatario = {
  nome: string
  cargo: string | null
  email: string
  informadoPor: UsuarioResumo | null
  informadoEm: string | null
}

export type VersaoDoContrato = {
  id: number
  versao: number
  nomeOriginal: string
  mimeType: string
  tamanhoBytes: number
  enviadoPor: UsuarioResumo | null
  enviadoEm: string
  observacao: string | null
}

export type Contrato = {
  parceriaId: number
  /** Dado pessoal de terceiro (D8): só estas rotas o devolvem. */
  signatario: Signatario | null
  assinadoEm: string | null
  arquivo: VersaoDoContrato | null
  versoes: VersaoDoContrato[]
}

export const obterContrato = (parceriaId: number) => pedir<Contrato>(`/parcerias/${parceriaId}/contrato`)

export const informarSignatario = (parceriaId: number, signatario: { nome: string; email: string; cargo: string | null }) =>
  pedir<Contrato>(`/parcerias/${parceriaId}/contrato/signatario`, { metodo: 'PUT', corpo: signatario })

export function enviarContratoAssinado(parceriaId: number, arquivo: File, assinadoEm: string, observacao: string) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  if (assinadoEm) formulario.append('assinadoEm', assinadoEm)
  if (observacao.trim()) formulario.append('observacao', observacao.trim())
  return pedir<Contrato>(`/parcerias/${parceriaId}/contrato/arquivo`, { metodo: 'POST', formulario })
}

/** O PDF de uma versão, para o visualizador (a rota 18 confere a parceria e a aba). */
export const lerPdfDoContrato = (arquivoId: number) => lerArquivo(`/arquivos/${arquivoId}`)
