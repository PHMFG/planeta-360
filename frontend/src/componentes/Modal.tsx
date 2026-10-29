// Modal do Figma (F01): painel de 560 px sobre um véu escuro, com título e "Fechar". [ITEM-4]
import { useEffect, useEffectEvent, useRef, type ReactNode } from 'react'

const FOCAVEIS = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

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
  // Esc fecha; Tab e Shift+Tab giram dentro do painel, sem cair no menu que fica atrás do véu (aria-modal).
  // Usa sempre o aoFechar mais recente, sem refazer o efeito abaixo a cada tecla digitada no formulário.
  const tratarTecla = useEffectEvent((evento: KeyboardEvent) => {
    if (evento.key === 'Escape') return aoFechar()
    if (evento.key !== 'Tab' || !painel.current) return
    const focaveis = [...painel.current.querySelectorAll<HTMLElement>(FOCAVEIS)]
    if (!focaveis.length) return
    const primeiro = focaveis[0]
    const ultimo = focaveis[focaveis.length - 1]
    const atual = document.activeElement
    if (evento.shiftKey && (atual === primeiro || atual === painel.current)) {
      evento.preventDefault()
      ultimo.focus()
    } else if (!evento.shiftKey && (atual === ultimo || !painel.current.contains(atual))) {
      evento.preventDefault()
      primeiro.focus()
    }
  })

  // O foco vai para o painel só ao abrir e volta para onde estava só ao fechar.
  // Depender do aoFechar (uma função nova a cada render) roubava o foco do campo depois da primeira letra.
  useEffect(() => {
    if (!aberto) return
    const anterior = document.activeElement as HTMLElement | null
    painel.current?.focus()
    const aoTeclar = (evento: KeyboardEvent) => tratarTecla(evento)
    document.addEventListener('keydown', aoTeclar)
    return () => {
      document.removeEventListener('keydown', aoTeclar)
      anterior?.focus()
    }
  }, [aberto])

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
