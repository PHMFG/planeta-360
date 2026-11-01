// O debug das ferramentas do agente: o que o modelo pediu e o que recebeu de volta, numa caixa que abre e fecha. [ATLAS]
// Temporário, como o botão Debug da chave: só chega à tela com atlas.debug.ferramentas ligado no servidor (perfil dev).
import { useId, useState } from 'react'
import type { FerramentaUsada } from '../api/atlas'

/** O JSON com recuo, para ler; o que não é JSON (cortado em 20 mil caracteres, por exemplo) sai como veio. */
function legivel(json: string) {
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

function Dados({ rotulo, json }: { rotulo: string; json: string | null }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <p className="text-[11px] font-bold tracking-[0.08em] text-[#ffd201]/80 uppercase">{rotulo}</p>
      <pre className="max-h-72 overflow-y-auto rounded-lg bg-black/25 p-2.5 font-mono text-xs leading-[1.5] break-words whitespace-pre-wrap text-white/85">
        {json === null ? 'Sem resposta ainda.' : legivel(json)}
      </pre>
    </div>
  )
}

export function FerramentaDebug({ ferramenta }: { ferramenta: FerramentaUsada }) {
  const [aberto, setAberto] = useState(false)
  const id = useId()
  return (
    <div className="rounded-[14px] border border-[#ffd201]/30 bg-[#ffd201]/8">
      <button
        type="button"
        aria-expanded={aberto}
        aria-controls={id}
        onClick={() => setAberto(!aberto)}
        className="flex w-full items-center gap-2 rounded-[14px] px-3.5 py-2.5 text-left text-[13px] font-semibold text-white/85 hover:text-white"
      >
        <i className="ri-terminal-box-line text-base text-[#ffd201]" aria-hidden="true" />
        <span className="min-w-0 flex-1 break-words">
          <span className="text-[#ffd201]">Debug</span> · {ferramenta.nome}
          {ferramenta.duracaoMs !== null && <span className="font-medium text-white/50"> · {ferramenta.duracaoMs} ms</span>}
          {ferramenta.erro && <span className="font-semibold text-[#ff9c9c]"> · erro</span>}
        </span>
        <i className={`ri-arrow-down-s-line text-lg transition-transform ${aberto ? 'rotate-180' : ''}`} aria-hidden="true" />
      </button>
      <div id={id} hidden={!aberto} className="border-t border-[#ffd201]/20 px-3.5 py-3">
        <div className="flex flex-col gap-3">
          <Dados rotulo="O que o modelo pediu" json={ferramenta.argumentos} />
          <Dados rotulo="O que a ferramenta devolveu ao modelo" json={ferramenta.resultado} />
        </div>
      </div>
    </div>
  )
}

/** A resposta que não usou nenhuma ferramenta: no debug, dizer isso também é informação. */
export function SemFerramentaDebug() {
  return (
    <p className="flex items-center gap-2 text-xs font-medium text-white/55">
      <i className="ri-terminal-box-line text-sm text-[#ffd201]/70" aria-hidden="true" />
      <span>
        <span className="text-[#ffd201]/80">Debug</span> · nenhuma ferramenta usada nesta resposta
      </span>
    </p>
  )
}
