// Mostra o estado de uma busca com os avisos do S00: carregando, falha ou vazio; senão, o conteúdo. [ITEM-7]
// Carregando é um esqueleto (blocos cinza com um brilho passando), não um aviso: a página não pula quando o dado chega.
import type { ReactNode } from 'react'
import type { Dados } from '../api/useDados'
import { Aviso } from './Aviso'

type PropsCarregando<T> = {
  dados: Dados<T>
  vazio?: (dado: T) => boolean
  textoVazio?: string
  /** Quantas linhas o esqueleto mostra (padrão 3), para reservar mais ou menos a altura do que vem. */
  linhas?: number
  children: (dado: T) => ReactNode
}

export function Carregando<T>({ dados, vazio, textoVazio = 'Nada por aqui ainda.', linhas = 3, children }: PropsCarregando<T>) {
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
  if (!dados.pronto) return <Esqueleto linhas={linhas} />
  const dado = dados.dado as T
  if (vazio?.(dado)) return <Aviso tipo="vazio" titulo={textoVazio} />
  return <>{children(dado)}</>
}

/** Blocos no lugar do conteúdo enquanto ele carrega. O leitor de tela ouve "Carregando…". */
export function Esqueleto({ linhas = 3 }: { linhas?: number }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-2.5">
      <span className="sr-only">Carregando…</span>
      {Array.from({ length: linhas }, (_, i) => (
        <div key={i} aria-hidden="true" className="esqueleto h-4 rounded-md" style={{ width: `${[92, 76, 84, 64, 88][i % 5]}%` }} />
      ))}
    </div>
  )
}
