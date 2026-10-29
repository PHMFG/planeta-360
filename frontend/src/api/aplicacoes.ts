// Aba Aplicações de marca: o plano linha a linha e as fotos de comprovação (rotas 37 e 38 do docs/api.md). [ITEM-5] [UPLOAD]
import { pedir } from './cliente'
import { baixarArquivo } from './materiais'
import type { UsuarioResumo } from './parcerias'

export type Comprovacao = {
  id: number
  arquivoId: number
  nomeOriginal: string
  descricao: string | null
  registradoPor: UsuarioResumo | null
  registradoEm: string
}

/** Uma linha de aplicação de marca do plano comercial. {@code comprovadas} é o mínimo entre o previsto e as fotos. */
export type LinhaAplicacao = {
  id: number
  bloco: string | null
  veiculo: string
  formato: string
  espacoPrograma: string | null
  janela: string | null
  previsto: number
  comprovadas: number
  fotos: number
  comprovacoes: Comprovacao[]
}

/** Os totais são os mesmos da consolidação (rota 29); sem plano, vêm todos nulos. */
export type PlanoDeAplicacoes = {
  parceriaId: number
  totais: { previstas: number | null; comprovadas: number | null; fotos: number | null; percentual: number | null }
  linhas: LinhaAplicacao[]
}

export const obterAplicacoes = (parceriaId: number) => pedir<PlanoDeAplicacoes>(`/parcerias/${parceriaId}/aplicacoes`)

/** Só a RBS anexa fotos (a API responde 403 à marca antes de ler o arquivo). */
export function enviarComprovacao(parceriaId: number, aproveitamentoId: number, arquivo: File, descricao?: string) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  if (descricao) formulario.append('descricao', descricao)
  return pedir<Comprovacao>(`/parcerias/${parceriaId}/aplicacoes/${aproveitamentoId}/comprovacoes`, { metodo: 'POST', formulario })
}

export const baixarComprovacao = (comprovacao: Comprovacao) =>
  baixarArquivo({ id: comprovacao.arquivoId, nomeOriginal: comprovacao.nomeOriginal })

export const EXTENSOES_FOTO = '.png,.jpg,.jpeg'
const LIMITE_FOTO = 20 * 1024 * 1024

/** O que a API vai recusar na foto (tipo e tamanho), para avisar antes do envio. A API confere de novo, pelo conteúdo. */
export function problemaNaFoto(arquivo: File): string | null {
  const extensao = arquivo.name.includes('.') ? `.${arquivo.name.split('.').pop()?.toLowerCase()}` : ''
  if (!EXTENSOES_FOTO.split(',').includes(extensao)) return 'Envie uma foto em PNG ou JPG.'
  if (arquivo.size > LIMITE_FOTO) return 'A foto passa do limite de 20 MB. Reduza o tamanho e escolha de novo.'
  return null
}
