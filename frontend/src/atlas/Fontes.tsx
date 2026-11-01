// As fontes de uma resposta do Atlas: o [T1] do texto vira um chip que abre o documento, e a lista "Fontes" fica embaixo. [ATLAS]
// Enquanto a resposta chega, as fontes ainda não existem (vêm no fim do turno): o chip aparece sem ação e, na conversa gravada,
// troca pelo que abre. A citação que não tem fonte fica como veio; só o que é documento e ainda está no ar abre. O registro do
// portal [R1] abre o documento só quando veio de um (um prazo do manual, na página dele), e a conta [C1] está em "Como calculei".
import { Fragment } from 'react'
import type { FonteDaResposta } from '../api/atlas'
import { partesComCitacoes } from './citacoes'

const abre = (fonte: FonteDaResposta) =>
  fonte.documentoId !== undefined && !fonte.revogado && (fonte.tipo === 'REGISTRO' || (fonte.tipo === 'TRECHO' && fonte.trechoId !== undefined))

const FORMA = 'mx-0.5 inline-flex h-6 min-w-6 items-center justify-center rounded-md px-1.5 align-baseline text-[11px] leading-none font-bold'
const CHIP_PARADO = `${FORMA} bg-white/8 text-white/64`

/** Título, versão e página da fonte, para o leitor de tela e a lista. */
function ondeEsta(fonte: FonteDaResposta) {
  return [fonte.versao !== undefined && `versão ${fonte.versao}`, fonte.pagina !== undefined && `página ${fonte.pagina}`].filter(Boolean).join(', ')
}

type AoAbrir = (fonte: FonteDaResposta) => void

/** O que o chip parado explica: o documento retirado, o registro do portal ou a conta. */
function dicaDoChip(fonte: FonteDaResposta) {
  if (fonte.revogado) return 'Este documento foi retirado pela RBS.'
  if (fonte.tipo === 'CALCULO') return 'Conta do Atlas: veja em Como calculei.'
  return fonte.titulo
}

function ChipDaFonte({ fonte, aoAbrir }: { fonte: FonteDaResposta; aoAbrir: AoAbrir }) {
  if (!abre(fonte)) {
    return (
      <span className={CHIP_PARADO} title={dicaDoChip(fonte)}>
        {fonte.marcador}
      </span>
    )
  }
  return (
    <button
      type="button"
      onClick={() => aoAbrir(fonte)}
      aria-label={`Abrir a fonte ${fonte.marcador}: ${fonte.titulo ?? 'documento'}, ${ondeEsta(fonte)}`}
      className={`${FORMA} bg-white/14 text-atlas-lilas transition-colors hover:bg-white/24`}
    >
      {fonte.marcador}
    </button>
  )
}

/** O texto da resposta com as citações. Sem {@code fontes}, a resposta ainda está chegando. */
export function TextoComCitacoes({ texto, fontes, aoAbrir }: { texto: string; fontes?: FonteDaResposta[]; aoAbrir?: AoAbrir }) {
  return partesComCitacoes(texto).map((parte, indice) => {
    if (parte.tipo === 'texto') return <Fragment key={indice}>{parte.texto}</Fragment>
    if (!fontes || !aoAbrir) {
      return parte.marcadores.map((marcador) => (
        <span key={`${indice}-${marcador}`} className={CHIP_PARADO}>
          {marcador}
        </span>
      ))
    }
    const ligadas = parte.marcadores.map((marcador) => fontes.find((fonte) => fonte.marcador === marcador))
    if (ligadas.some((fonte) => !fonte)) return <Fragment key={indice}>{parte.texto}</Fragment>
    return ligadas.map((fonte) => fonte && <ChipDaFonte key={`${indice}-${fonte.marcador}`} fonte={fonte} aoAbrir={aoAbrir} />)
  })
}

function SeloDaFonte({ children }: { children: string }) {
  return <span className="ml-1.5 inline-block rounded-full border border-[#ffd201]/40 px-2 py-px align-[1px] text-[11px] font-semibold whitespace-nowrap text-[#ffd201]">{children}</span>
}

function ConteudoDaLinha({ fonte }: { fonte: FonteDaResposta }) {
  return (
    <>
      <span className="grid h-6 min-w-6 shrink-0 place-items-center rounded-md bg-white/14 px-1.5 text-[11px] font-bold text-atlas-lilas">{fonte.marcador}</span>
      <span className="min-w-0 text-[13px] leading-[1.45]">
        <span className="font-semibold text-white">{fonte.titulo}</span>
        {fonte.revogado ? <SeloDaFonte>documento retirado</SeloDaFonte> : fonte.substituido && <SeloDaFonte>versão substituída</SeloDaFonte>}
        <span className="block text-xs font-medium text-white/64">
          {[ondeEsta(fonte), fonte.secao].filter(Boolean).join(' · ')}
        </span>
      </span>
    </>
  )
}

/** A lista "Fontes", embaixo da resposta: cada documento citado, com versão e página, e cada registro do portal. A conta tem "Como calculei". */
export function ListaDeFontes({ fontes, aoAbrir }: { fontes: FonteDaResposta[]; aoAbrir: AoAbrir }) {
  const citadas = fontes.filter((fonte) => fonte.tipo !== 'CALCULO')
  if (citadas.length === 0) return null
  const linha = 'flex w-full items-start gap-2.5 rounded-[12px] border border-white/12 bg-white/5 px-3 py-2 text-left'
  return (
    <section className="flex flex-col gap-2" aria-label="Fontes">
      <h3 className="text-[11px] font-bold tracking-[0.12em] text-white/64 uppercase">Fontes</h3>
      <ul className="flex flex-col gap-1.5">
        {citadas.map((fonte) => (
          <li key={fonte.marcador}>
            {abre(fonte) ? (
              <button type="button" onClick={() => aoAbrir(fonte)} className={`${linha} transition-colors hover:bg-white/12`}>
                <ConteudoDaLinha fonte={fonte} />
              </button>
            ) : (
              <div className={linha}>
                <ConteudoDaLinha fonte={fonte} />
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
