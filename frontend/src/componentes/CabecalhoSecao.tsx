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
    <header className="padrao-diagonal relative overflow-hidden rounded-3xl bg-secao px-9 pt-7 pb-[30px] text-white">
      <div className="flex items-end justify-between gap-6">
        <div className="flex flex-col gap-3.5">
          <p className="texto-sobretitulo">{trilha}</p>
          <h1 className="texto-display">{titulo}</h1>
          {subtitulo && <p className="text-sm font-medium">{subtitulo}</p>}
        </div>
        {acoes && <div className="flex shrink-0 gap-3">{acoes}</div>}
      </div>
    </header>
  )
}
