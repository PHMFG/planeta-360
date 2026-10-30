// A linha de pensamento do modelo (o raciocínio do DeepSeek), numa caixa que abre e fecha. [ATLAS]
// Ela é crua: não passa pela checagem que tira números e nomes internos do texto do Atlas, e a caixa diz isso.
// Ao vivo, fica aberta enquanto o modelo pensa e se fecha quando a resposta começa, a não ser que a pessoa a tenha mexido.
import { useEffect, useId, useRef, useState } from 'react'

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

/** Sem {@code desde}, é o pensamento gravado de uma resposta antiga: começa fechado e não mostra o tempo. */
export function Raciocinio({ texto, desde, ate = null }: { texto: string; desde?: number; ate?: number | null }) {
  const aoVivo = desde !== undefined
  const pensando = aoVivo && ate === null
  const [escolha, setEscolha] = useState<boolean | null>(null)
  const aberto = escolha ?? pensando
  const segundos = useSegundos(desde, ate)
  const corpo = useRef<HTMLDivElement>(null)
  const id = useId()

  // Enquanto o pensamento chega, a caixa acompanha a última linha.
  useEffect(() => {
    if (pensando && aberto && corpo.current) corpo.current.scrollTop = corpo.current.scrollHeight
  }, [texto, pensando, aberto])

  return (
    <div className="rounded-[14px] border border-white/12 bg-white/5">
      <button
        type="button"
        aria-expanded={aberto}
        aria-controls={id}
        onClick={() => setEscolha(!aberto)}
        className="flex w-full items-center gap-2 rounded-[14px] px-3.5 py-2.5 text-left text-[13px] font-semibold text-white/72 hover:text-white"
      >
        <i className={`${pensando ? 'ri-loader-4-line animate-spin' : 'ri-brain-2-line'} text-base`} aria-hidden="true" />
        <span className="min-w-0 flex-1">
          {pensando ? 'Pensando…' : 'Linha de pensamento'}
          {aoVivo && <span className="font-medium text-white/50"> · {segundos} s</span>}
        </span>
        <i className={`ri-arrow-down-s-line text-lg transition-transform ${aberto ? 'rotate-180' : ''}`} aria-hidden="true" />
      </button>
      <div id={id} hidden={!aberto} className="border-t border-white/8">
        <div ref={corpo} className="max-h-60 overflow-y-auto px-3.5 py-3 text-[13px] leading-[1.6] break-words whitespace-pre-wrap text-white/62">
          {texto}
        </div>
        <p className="px-3.5 pb-2.5 text-[11px] leading-normal text-white/45">
          Rascunho do modelo, sem a checagem do texto: pode ter números e nomes internos. O que vale é a resposta do Atlas.
        </p>
      </div>
    </div>
  )
}
