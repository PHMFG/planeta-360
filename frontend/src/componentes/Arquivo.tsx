// Arquivo do Figma em duas formas: a linha (ícone, título, meta e status) e o cartão com pré-visualização. [ITEM-4]
import type { ReactNode } from 'react'

type PropsLinha = {
  titulo: ReactNode
  meta?: ReactNode
  rodape?: ReactNode
  icone?: string
  acoes?: ReactNode
  onClick?: () => void
}

export function LinhaArquivo({ titulo, meta, rodape, icone = 'ri-file-text-line', acoes, onClick }: PropsLinha) {
  const Corpo = onClick ? 'button' : 'div'
  return (
    <div className="flex items-center gap-4 rounded-[14px] border border-borda bg-white p-4">
      <Corpo
        type={onClick ? 'button' : undefined}
        onClick={onClick}
        className={`flex min-w-0 flex-1 items-center gap-4 text-left ${onClick ? 'cursor-pointer' : ''}`}
      >
        <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-secao-tinta text-secao-texto">
          <i className={`${icone} text-xl`} aria-hidden="true" />
        </span>
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="truncate text-sm font-semibold">{titulo}</span>
          {meta && <span className="text-xs font-medium text-texto-2">{meta}</span>}
          {rodape && <span className="text-xs">{rodape}</span>}
        </span>
      </Corpo>
      {acoes && <div className="flex shrink-0 items-center gap-2">{acoes}</div>}
    </div>
  )
}

/** Tipo do arquivo em letras grandes, como "PDF" ou "MP4", tirado da extensão. */
export function tipoDoArquivo(nome: string) {
  const extensao = nome.includes('.') ? nome.split('.').pop() : ''
  return (extensao || 'arq').slice(0, 4).toUpperCase()
}

type PropsCartaoArquivo = {
  nome: string
  meta?: ReactNode
  rodape?: ReactNode
  onClick?: () => void
}

export function CartaoArquivo({ nome, meta, rodape, onClick }: PropsCartaoArquivo) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex flex-col overflow-hidden rounded-2xl border border-borda bg-white text-left transition-shadow hover:shadow-menu"
    >
      <span className="relative grid h-[132px] place-items-center bg-secao-tinta">
        <span className="font-display text-[44px] tracking-[0.005em] text-secao-texto">{tipoDoArquivo(nome)}</span>
      </span>
      <span className="flex flex-col gap-0.5 p-4">
        <span className="truncate text-sm font-semibold">{nome}</span>
        {meta && <span className="text-xs font-medium text-texto-2">{meta}</span>}
        {rodape && <span className="mt-1 text-xs">{rodape}</span>}
      </span>
    </button>
  )
}
