// Catálogo de campos e os dados que só a marca sabe informar (rotas 39 a 41 do docs/api.md). [ITEM-5] [VALIDACAO]
import { consulta, pedir } from './cliente'
import type { UsuarioResumo } from './parcerias'
import type { CodigoAba } from './tipos'

export type TipoCampo = 'INTEIRO' | 'DECIMAL' | 'DATA' | 'ARQUIVO'

/** Um campo do catálogo. {@code limiteCampo} é a chave do campo que este valor não pode passar (cadastros ≤ visitantes). */
export type CampoCatalogo = {
  chave: string
  aba: CodigoAba
  rotulo: string
  tipo: TipoCampo
  unidade: string | null
  minimo: number | null
  maximo: number | null
  ajuda: string | null
  limiteCampo: string | null
}

export type ValorVigente = {
  valor: number
  /** Só num campo do tipo ARQUIVO: o valor fica nulo, e a planilha sai pela rota de download. */
  arquivo?: { id: number; nomeOriginal: string } | null
  origem: 'PORTAL' | 'ATLAS'
  informadoPor: UsuarioResumo | null
  informadoEm: string
}

export type CampoComValor = CampoCatalogo & { vigente: ValorVigente | null }

export type DadosDaAba = { parceriaId: number; aba: CodigoAba; campos: CampoComValor[] }

export type ValorInformado = { chave: string; valor: number }

export const listarCampos = (aba?: CodigoAba) => pedir<CampoCatalogo[]>(`/campos${consulta({ aba })}`)

export const obterDados = (parceriaId: number, aba: CodigoAba) =>
  pedir<DadosDaAba>(`/parcerias/${parceriaId}/dados${consulta({ aba })}`)

/** Grava um valor novo por campo; a correção é uma linha nova, e a anterior fica no histórico. */
export const informarDados = (parceriaId: number, valores: ValorInformado[]) =>
  pedir<DadosDaAba>(`/parcerias/${parceriaId}/dados`, { metodo: 'POST', corpo: { valores } })

/** Envia o arquivo de um campo do tipo ARQUIVO (planilha da agência). Não passa pelo cartão do Atlas nem entra no relatório. */
export function enviarArquivoDoCampo(parceriaId: number, chave: string, arquivo: File) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  return pedir<ValorVigente>(`/parcerias/${parceriaId}/dados-informados/${chave}/arquivo`, { metodo: 'POST', formulario })
}
