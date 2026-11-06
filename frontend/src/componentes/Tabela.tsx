// Tabela do Figma: cabeçalho cinza com cantos arredondados e linhas com divisor fino. [ITEM-7]
// Quando não cabe (no celular), ela rola para os lados dentro da própria caixa: a caixa avisa que rola e recebe o foco do
// teclado, para quem não usa o dedo também conseguir rolar (U02).
import { useEffect, useRef, useState, type ReactNode } from 'react'

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
  /** O nome da tabela para quem usa leitor de tela, dito quando a caixa rolável recebe o foco. */
  rotulo?: string
  /** A linha que acabou de chegar ou mudar (F10): o fundo pisca na tinta da seção e some em 2 s. */
  destaque?: (linha: T) => boolean
}

/** A caixa tem mais conteúdo do que a largura dela. Relê quando o tamanho muda. */
function usarRolaParaOsLados() {
  const caixa = useRef<HTMLDivElement>(null)
  const [rola, setRola] = useState(false)
  useEffect(() => {
    const elemento = caixa.current
    if (!elemento) return
    const medir = () => setRola(elemento.scrollWidth - elemento.clientWidth > 2)
    medir()
    const observador = new ResizeObserver(medir)
    observador.observe(elemento)
    if (elemento.firstElementChild) observador.observe(elemento.firstElementChild)
    return () => observador.disconnect()
  }, [])
  return { caixa, rola }
}

export function Tabela<T>({ colunas, linhas, chave, vazio = 'Nada para mostrar.', aoClicar, rotulo = 'Tabela', destaque }: PropsTabela<T>) {
  const { caixa, rola } = usarRolaParaOsLados()
  return (
    <div>
      {rola && (
        <p className="mb-1.5 flex items-center justify-end gap-1 text-[11px] font-semibold text-texto-2" aria-hidden="true">
          Deslize para ver todas as colunas <i className="ri-arrow-right-line" />
        </p>
      )}
      <div
        ref={caixa}
        className="overflow-x-auto rounded-[10px] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-secao"
        {...(rola ? { tabIndex: 0, role: 'region', 'aria-label': `${rotulo}, role para os lados` } : {})}
      >
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
                className={`${aoClicar ? 'cursor-pointer hover:bg-fundo' : ''} ${destaque?.(linha) ? 'animate-destacar' : ''}`}
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
    </div>
  )
}
