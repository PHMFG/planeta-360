// Moldura das telas de acesso (Figma A01): cartão azul com o selo à esquerda e o formulário à direita. [ITEM-3]
import type { ReactNode } from 'react'

export function MolduraAcesso({ titulo, children }: { titulo: ReactNode; children: ReactNode }) {
  return (
    <div data-secao="parceria" className="flex min-h-screen flex-col gap-6 p-8">
      <p className="texto-sobretitulo text-texto-2">Planeta 360 / Acesso</p>

      <div className="grid flex-1 gap-6 lg:grid-cols-[736fr_688fr]">
        <section className="padrao-diagonal flex flex-col justify-center gap-5 overflow-hidden rounded-[28px] bg-secao p-12 text-white">
          <div className="flex flex-col gap-[18px]">
            <span className="grid size-[140px] place-items-center rounded-full bg-white">
              <img src="/logo-planeta-atlantida-2027.png" alt="Planeta Atlântida 2027" width={120} height={120} />
            </span>
            <p className="font-display text-[104px] leading-[0.92] tracking-[0.005em] uppercase">Planeta 360</p>
            <p className="text-sm font-bold tracking-[0.16em] uppercase">Portal do Patrocinador</p>
          </div>
          <p className="text-sm leading-normal">
            Materiais, prazos, resultados e documentos.
            <br />
            Cada patrocinador visualiza apenas a sua empresa.
          </p>
          <p className="text-sm leading-normal">Sem acesso? Confirme com a RBS qual e-mail foi liberado.</p>
        </section>

        <section className="flex flex-col items-center justify-center gap-6 p-6">
          <h1 className="max-w-[532px] text-center font-display text-[56px] leading-[0.92] tracking-[0.005em] text-secao uppercase">
            {titulo}
          </h1>
          {children}
        </section>
      </div>
    </div>
  )
}
