// Botões do Figma (P360/Botão): primário na cor da seção e secundário com contorno preto. [ITEM-7]
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'

type Variante = 'primario' | 'secundario'

const BASE =
  'texto-botao inline-flex h-12 items-center justify-center gap-2 rounded-[10px] px-6 transition-[filter,opacity] disabled:cursor-not-allowed disabled:opacity-60'

/** 220 px é a largura do Figma; quem passa outro min-w (ex.: min-w-0 num modal estreito) fica só com o seu. */
const largura = (className: string) => (/(^|\s)min-w-/.test(className) ? '' : 'min-w-[220px]')

const VARIANTES: Record<Variante, string> = {
  primario: 'bg-secao text-white hover:brightness-110',
  secundario: 'border-[1.5px] border-tinta bg-white text-tinta hover:bg-cinza-100',
}

type PropsBotao = ButtonHTMLAttributes<HTMLButtonElement> & {
  variante?: Variante
  carregando?: boolean
  icone?: string
  children: ReactNode
}

export function Botao({ variante = 'primario', carregando = false, icone, className = '', children, disabled, ...resto }: PropsBotao) {
  return (
    <button
      type="button"
      className={`${BASE} ${largura(className)} ${VARIANTES[variante]} ${className}`}
      disabled={disabled || carregando}
      aria-busy={carregando || undefined}
      {...resto}
    >
      {carregando ? (
        <i className="ri-loader-4-line animate-spin text-lg" aria-hidden="true" />
      ) : (
        icone && <i className={`${icone} text-lg`} aria-hidden="true" />
      )}
      {children}
    </button>
  )
}

type PropsLinkBotao = LinkProps & { variante?: Variante; icone?: string }

export function LinkBotao({ variante = 'primario', icone, className = '', children, ...resto }: PropsLinkBotao) {
  return (
    <Link className={`${BASE} ${largura(className)} ${VARIANTES[variante]} ${className}`} {...resto}>
      {icone && <i className={`${icone} text-lg`} aria-hidden="true" />}
      {children}
    </Link>
  )
}
