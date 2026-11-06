// Cabeçalho de seção do Figma: faixa na cor da seção, título em Bebas e uma linha de subtítulo. [ITEM-7]
// Plano da base da RBS (F9): a faixa caiu de 180 para cerca de 96 px. A trilha ("Planeta Atlântida / Patrocinador") saiu da
// tela, porque repetia o menu; ela continua no prop e vai para o leitor de tela. O emblema de 2027 (R7.1) fica, menor.
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
    <header className="padrao-diagonal relative overflow-hidden rounded-3xl bg-secao px-5 py-4 text-white sm:px-8 sm:py-5">
      <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
        <div className="flex min-w-0 flex-col gap-1">
          <p className="sr-only">{trilha}</p>
          <h1 className="font-display text-[44px] leading-[0.95] tracking-[0.005em] break-words uppercase max-sm:text-[36px]">{titulo}</h1>
          {subtitulo && <p className="text-[13px] font-medium opacity-90">{subtitulo}</p>}
        </div>
        <div className="flex items-center gap-4 sm:shrink-0">
          {acoes && <div className="flex flex-wrap gap-3">{acoes}</div>}
          {/* R7.1: o emblema da edição onde o Figma tem a marca do evento. Decorativo; fica de fora no celular. */}
          <img
            src="/logo-planeta-atlantida-2027.png"
            alt=""
            aria-hidden="true"
            width={52}
            height={52}
            data-emblema="2027"
            className="pointer-events-none hidden size-[52px] drop-shadow-md sm:block"
          />
        </div>
      </div>
    </header>
  )
}
