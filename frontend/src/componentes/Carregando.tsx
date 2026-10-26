// Mostra o estado de uma busca com os avisos do S00: carregando, falha ou vazio; senão, o conteúdo. [ITEM-7]
import type { ReactNode } from 'react'
import type { Dados } from '../api/useDados'
import { Aviso } from './Aviso'

type PropsCarregando<T> = {
  dados: Dados<T>
  vazio?: (dado: T) => boolean
  textoVazio?: string
  children: (dado: T) => ReactNode
}

export function Carregando<T>({ dados, vazio, textoVazio = 'Nada por aqui ainda.', children }: PropsCarregando<T>) {
  if (dados.erro) {
    return (
      <Aviso tipo="falha" titulo="Não foi possível carregar">
        {dados.erro}{' '}
        <button type="button" className="font-semibold underline" onClick={dados.recarregar}>
          Tentar de novo
        </button>
      </Aviso>
    )
  }
  if (!dados.pronto) return <Aviso tipo="carregando" titulo="Carregando…" />
  const dado = dados.dado as T
  if (vazio?.(dado)) return <Aviso tipo="vazio" titulo={textoVazio} />
  return <>{children(dado)}</>
}
