// Cabeçalho de seção do Figma: faixa na cor da seção, trilha, título em Bebas e subtítulo. [ITEM-7]
import type { ReactNode } from 'react'

type PropsCabecalho = {
  trilha: string
  titulo: string
  subtitulo?: string
  /** Ações à direita do título, como um botão de exportar. */
  acoes?: ReactNode
}

export function CabecalhoSecao({ trilha, titulo, subtitulo, acoes }: PropsCabecalho) {
  return (
    <header className="padrao-diagonal relative overflow-hidden rounded-3xl bg-secao px-5 pt-5 pb-6 text-white sm:px-9 sm:pt-7 sm:pb-[30px]">
      <div className="flex flex-col items-start gap-4 sm:flex-row sm:items-end sm:justify-between sm:gap-6">
        <div className="flex min-w-0 flex-col gap-3.5">
          <p className="texto-sobretitulo">{trilha}</p>
          <h1 className="texto-display break-words max-sm:text-[44px]">{titulo}</h1>
          {subtitulo && <p className="text-sm font-medium">{subtitulo}</p>}
        </div>
        {acoes && <div className="flex flex-wrap gap-3 sm:shrink-0">{acoes}</div>}
      </div>
    </header>
  )
}
