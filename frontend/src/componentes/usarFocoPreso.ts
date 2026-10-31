// O foco de um painel sobre a tela (modal ou gaveta): Esc fecha, e Tab e Shift+Tab giram dentro dele. [ITEM-4]
import { useEffect, useEffectEvent, useRef } from 'react'

const FOCAVEIS = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function usarFocoPreso(aberto: boolean, aoFechar: () => void) {
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

  return painel
}
