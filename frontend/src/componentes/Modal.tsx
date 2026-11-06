// Modal do Figma (F01): painel de 560 px sobre um véu escuro, com título e "Fechar". [ITEM-4]
// Entra com o véu em fade e o painel crescendo de 97%; ao fechar, os dois somem em 120 ms antes de sair da página.
import { useEffect, useState, type ReactNode } from 'react'
import { usarFocoPreso } from './usarFocoPreso'

type PropsModal = {
  titulo: string
  legenda?: string
  aberto: boolean
  aoFechar: () => void
  children: ReactNode
  largura?: string
}

const DURACAO_DA_SAIDA = 120

export function Modal({ titulo, legenda, aberto, aoFechar, children, largura = 'max-w-[560px]' }: PropsModal) {
  const painel = usarFocoPreso(aberto, aoFechar)
  // Depois de fechar, o modal fica na página o tempo da animação de saída, sem receber clique.
  const [saindo, setSaindo] = useState(false)
  const [montado, setMontado] = useState(aberto)

  useEffect(() => {
    if (aberto) {
      setMontado(true)
      setSaindo(false)
      return
    }
    if (!montado) return
    setSaindo(true)
    const espera = window.setTimeout(() => {
      setMontado(false)
      setSaindo(false)
    }, DURACAO_DA_SAIDA)
    return () => window.clearTimeout(espera)
  }, [aberto, montado])

  if (!montado && !aberto) return null
  return (
    <div
      className={`fixed inset-0 z-50 grid place-items-center overflow-y-auto bg-tinta/45 p-6 max-sm:p-3 ${saindo ? 'pointer-events-none animate-sumir' : 'animate-surgir'}`}
      onMouseDown={(evento) => evento.target === evento.currentTarget && aoFechar()}
    >
      <div
        ref={painel}
        role="dialog"
        aria-modal="true"
        aria-label={titulo}
        tabIndex={-1}
        className={`flex w-full ${largura} flex-col gap-4 rounded-3xl bg-white p-7 shadow-modal outline-none max-sm:gap-3 max-sm:p-5 ${saindo ? 'animate-encolher' : 'animate-crescer'}`}
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
