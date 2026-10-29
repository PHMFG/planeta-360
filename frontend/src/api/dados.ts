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
