// Cartões do Figma: cartão de seção (título em Bebas) e Indicador (rótulo, número grande e legenda). [ITEM-7]
import type { ReactNode } from 'react'
import { usarContagem } from './movimento'

type PropsCartao = {
  titulo?: string
  /** Texto ou ações à direita do título. */
  extra?: ReactNode
  children: ReactNode
  className?: string
}

export function Cartao({ titulo, extra, children, className = '' }: PropsCartao) {
  return (
    <section className={`rounded-[20px] border border-borda bg-white p-6 ${className}`}>
      {(titulo || extra) && (
        <div className="mb-4 flex items-center justify-between gap-4">
          {titulo && <h2 className="texto-titulo">{titulo}</h2>}
          {extra}
        </div>
      )}
      {children}
    </section>
  )
}

type PropsIndicador = {
  rotulo: string
  valor: ReactNode
  legenda?: string
}

export function Indicador({ rotulo, valor, legenda }: PropsIndicador) {
  return (
    <div className="flex flex-col gap-1 rounded-2xl border border-borda bg-white p-5">
      <p className="texto-sobretitulo text-texto-2">{rotulo}</p>
      <p className="font-display text-[56px] leading-[0.95] tracking-[0.005em] text-secao-texto">
        {typeof valor === 'string' && /^\d+$/.test(valor) ? <NumeroQueConta texto={valor} /> : valor}
      </p>
      {legenda && <p className="text-xs font-medium text-texto-2">{legenda}</p>}
    </div>
  )
}

/** Um inteiro que conta até o valor quando aparece, mantendo os zeros à esquerda ("07"). O leitor de tela lê o valor final. */
function NumeroQueConta({ texto }: { texto: string }) {
  const mostrado = usarContagem(Number(texto))
  return (
    <>
      <span aria-hidden="true">{String(Math.round(mostrado ?? 0)).padStart(texto.length, '0')}</span>
      <span className="sr-only">{texto}</span>
    </>
  )
}

/** Grade de três colunas de 384 px do Figma (indicadores e cartões de arquivo). */
export function Grade3({ children }: { children: ReactNode }) {
  return <div className="grid gap-6 md:grid-cols-3">{children}</div>
}

/** Grade de duas colunas de 588 px do Figma (campos e arquivos). */
export function Grade2({ children }: { children: ReactNode }) {
  return <div className="grid gap-6 md:grid-cols-2">{children}</div>
}
