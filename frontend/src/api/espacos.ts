// O plano comercial e os espaços de documento da marca (rotas 69 a 73 do docs/api.md): o plano que a RBS sobe no Perfil, o
// formulário e as solicitações de compra de ingressos, e o MIV e a logo em Aplicações.
import type { Arquivo, Entrega } from './materiais'
import { pedir } from './cliente'

export type PapelDoEspaco = 'DOCUMENTO_INGRESSOS' | 'MIV' | 'LOGO'

/** Uma versão de arquivo avulso (o plano comercial e o formulário em branco não são entregas). */
export type VersaoAvulsa = Omit<Arquivo, 'entregaId'> & { entregaId: number | null }

export type PlanoComercial = { parceriaId: number; versoes: VersaoAvulsa[] }

export type Espacos = {
  parceriaId: number
  formularioIngressos: { versoes: VersaoAvulsa[] }
  /** Cada solicitação é uma entrega própria, com a sua situação. */
  solicitacoesIngressos: Entrega[]
  miv: Entrega | null
  logo: Entrega | null
}

export const obterPlanoComercial = (parceriaId: number) => pedir<PlanoComercial>(`/parcerias/${parceriaId}/plano-comercial`)

function comArquivo(arquivo: File, observacao?: string) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  if (observacao?.trim()) formulario.append('observacao', observacao.trim())
  return formulario
}

export const enviarPlanoComercial = (parceriaId: number, arquivo: File, observacao?: string) =>
  pedir<VersaoAvulsa>(`/parcerias/${parceriaId}/plano-comercial`, { metodo: 'POST', formulario: comArquivo(arquivo, observacao) })

export const obterEspacos = (parceriaId: number, aba: 'PERFIL' | 'APLICACOES') =>
  pedir<Espacos>(`/parcerias/${parceriaId}/espacos?aba=${aba}`)

export const enviarAoEspaco = (parceriaId: number, papel: PapelDoEspaco, arquivo: File, observacao?: string) =>
  pedir<Arquivo>(`/parcerias/${parceriaId}/espacos/${papel}/arquivos`, { metodo: 'POST', formulario: comArquivo(arquivo, observacao) })

export const publicarFormularioDeIngressos = (parceriaId: number, arquivo: File) =>
  pedir<VersaoAvulsa>(`/parcerias/${parceriaId}/formulario-ingressos`, { metodo: 'POST', formulario: comArquivo(arquivo) })
