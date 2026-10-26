// Modal do Figma (F01): painel de 560 px sobre um véu escuro, com título e "Fechar". [ITEM-4]
import { useEffect, useRef, type ReactNode } from 'react'

type PropsModal = {
  titulo: string
  legenda?: string
  aberto: boolean
  aoFechar: () => void
  children: ReactNode
  largura?: string
}

export function Modal({ titulo, legenda, aberto, aoFechar, children, largura = 'max-w-[560px]' }: PropsModal) {
  const painel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!aberto) return
    const anterior = document.activeElement as HTMLElement | null
    painel.current?.focus()
    const aoTeclar = (evento: KeyboardEvent) => evento.key === 'Escape' && aoFechar()
    document.addEventListener('keydown', aoTeclar)
    return () => {
      document.removeEventListener('keydown', aoTeclar)
      anterior?.focus()
    }
  }, [aberto, aoFechar])

  if (!aberto) return null
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center overflow-y-auto bg-tinta/45 p-6"
      onMouseDown={(evento) => evento.target === evento.currentTarget && aoFechar()}
    >
      <div
        ref={painel}
        role="dialog"
        aria-modal="true"
        aria-label={titulo}
        tabIndex={-1}
        className={`flex w-full ${largura} flex-col gap-4 rounded-3xl bg-white p-7 shadow-modal outline-none`}
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="texto-titulo">{titulo}</h2>
            {legenda && <p className="mt-1 text-xs font-medium text-texto-2">{legenda}</p>}
          </div>
          <button type="button" onClick={aoFechar} className="flex items-center gap-1 text-[13px] font-semibold hover:text-secao-texto">
            Fechar <i className="ri-close-line text-lg" aria-hidden="true" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
