// Tabela do Figma: cabeçalho cinza com cantos arredondados e linhas com divisor fino. [ITEM-7]
import type { ReactNode } from 'react'

export type Coluna<T> = {
  titulo: string
  celula: (linha: T) => ReactNode
  /** Classe para alinhar ou fixar a largura da coluna. */
  classe?: string
}

type PropsTabela<T> = {
  colunas: Coluna<T>[]
  linhas: T[]
  chave: (linha: T) => string | number
  vazio?: string
  aoClicar?: (linha: T) => void
}

export function Tabela<T>({ colunas, linhas, chave, vazio = 'Nada para mostrar.', aoClicar }: PropsTabela<T>) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr>
            {colunas.map((coluna, indice) => (
              <th
                key={coluna.titulo}
                scope="col"
                className={`bg-cinza-100 px-3 py-2.5 text-left font-semibold ${indice === 0 ? 'rounded-l-[10px]' : ''} ${
                  indice === colunas.length - 1 ? 'rounded-r-[10px]' : ''
                } ${coluna.classe ?? ''}`}
              >
                {coluna.titulo}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {linhas.length === 0 ? (
            <tr>
              <td colSpan={colunas.length} className="px-3 py-6 text-center text-texto-2">
                {vazio}
              </td>
            </tr>
          ) : (
            linhas.map((linha) => (
              <tr
                key={chave(linha)}
                onClick={aoClicar ? () => aoClicar(linha) : undefined}
                className={aoClicar ? 'cursor-pointer hover:bg-fundo' : ''}
              >
                {colunas.map((coluna) => (
                  <td key={coluna.titulo} className={`border-b border-borda px-3 py-3 align-middle ${coluna.classe ?? ''}`}>
                    {coluna.celula(linha)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
