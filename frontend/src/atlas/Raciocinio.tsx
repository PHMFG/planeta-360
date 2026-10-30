// O resumo do raciocínio do modelo, numa caixa que abre e fecha. [ATLAS]
// O raciocínio cru fica só no servidor (volta ao modelo, nunca à tela); o servidor escreve um resumo curto de cada ida e o
// confere como o texto do Atlas, sem números nem nomes internos. Ao vivo, a caixa conta o tempo e fica aberta enquanto o modelo
// pensa; fecha quando a resposta começa, a não ser que a pessoa a tenha mexido.
import { useEffect, useId, useState } from 'react'

/** Segundos desde {@code desde}, contando a cada segundo enquanto o pensamento não termina. */
function useSegundos(desde: number | undefined, ate: number | null) {
  const [agora, setAgora] = useState(() => Date.now())
  useEffect(() => {
    if (desde === undefined || ate !== null) return
    const relogio = setInterval(() => setAgora(Date.now()), 1000)
    return () => clearInterval(relogio)
  }, [desde, ate])
  return desde === undefined ? 0 : Math.max(1, Math.round(((ate ?? agora) - desde) / 1000))
}

/** Sem {@code desde}, é o resumo gravado de uma resposta antiga: começa fechado e não mostra o tempo. */
export function Raciocinio({ resumos, desde, ate = null }: { resumos: string[]; desde?: number; ate?: number | null }) {
  const aoVivo = desde !== undefined
  const pensando = aoVivo && ate === null
  const [escolha, setEscolha] = useState<boolean | null>(null)
  const temResumo = resumos.length > 0
  const aberto = temResumo && (escolha ?? pensando)
  const segundos = useSegundos(desde, ate)
  const id = useId()

  const titulo = (
    <>
      <i className={`${pensando ? 'ri-loader-4-line animate-spin' : 'ri-brain-2-line'} text-base`} aria-hidden="true" />
      <span className="min-w-0 flex-1">
        {pensando || temResumo ? (
          <>
            {pensando ? 'Pensando…' : 'Resumo do raciocínio'}
            {aoVivo && <span className="font-medium text-white/50"> · {segundos} s</span>}
          </>
        ) : (
          // O resumo foi recusado ou ainda não chegou: só o tempo, sem prometer o que não há.
          `Pensou por ${segundos} s`
        )}
      </span>
    </>
  )
  const cabecalho = 'flex w-full items-center gap-2 rounded-[14px] px-3.5 py-2.5 text-left text-[13px] font-semibold text-white/72'

  return (
    <div className="rounded-[14px] border border-white/12 bg-white/5">
      {/* Sem resumo ainda, não há o que abrir: o cabeçalho só conta o tempo. */}
      {temResumo ? (
        <button type="button" aria-expanded={aberto} aria-controls={id} onClick={() => setEscolha(!aberto)} className={`${cabecalho} hover:text-white`}>
          {titulo}
          <i className={`ri-arrow-down-s-line text-lg transition-transform ${aberto ? 'rotate-180' : ''}`} aria-hidden="true" />
        </button>
      ) : (
        <p className={cabecalho}>{titulo}</p>
      )}
      {temResumo && (
        <div id={id} hidden={!aberto} className="flex flex-col gap-2 border-t border-white/8 px-3.5 py-3 text-[13px] leading-[1.6] text-white/70">
          {resumos.map((resumo, posicao) => (
            <p key={posicao} className="break-words">
              {resumo}
            </p>
          ))}
          <p className="text-[11px] leading-normal text-white/45">Resumo escrito pelo modelo. O raciocínio completo fica só no servidor.</p>
        </div>
      )}
    </div>
  )
}
