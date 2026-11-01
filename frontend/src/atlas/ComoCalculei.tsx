// "Como calculei": a conta ou a contagem de dias de uma resposta, com a origem de cada número. Fechado, não atrapalha a leitura;
// aberto, mostra o que entrou, de onde veio e o que saiu. A origem que é documento abre pelo mesmo chip da resposta. [ATLAS]
import type { CalculoDaResposta, FonteDaResposta, VariavelDoCalculo } from '../api/atlas'
import { TextoComCitacoes } from './Fontes'

const MARCADOR = /^[TRC]\d{1,3}$/

/** A origem de uma entrada: o chip da fonte (o marcador), ou em palavras, quando veio da pessoa ou dos dados da parceria. */
function Origem({ origem, fontes, aoAbrir }: { origem: string; fontes: FonteDaResposta[]; aoAbrir: (fonte: FonteDaResposta) => void }) {
  if (MARCADOR.test(origem)) return <TextoComCitacoes texto={`[${origem}]`} fontes={fontes.some((fonte) => fonte.marcador === origem) ? fontes : undefined} aoAbrir={aoAbrir} />
  if (origem === 'usuario') return <span className="text-white/64">você informou</span>
  if (origem.startsWith('calc:')) return <span className="text-white/64">outra conta desta resposta</span>
  return <span className="text-white/64">dados da parceria</span>
}

function LinhaDaEntrada({ variavel, fontes, aoAbrir }: { variavel: VariavelDoCalculo; fontes: FonteDaResposta[]; aoAbrir: (fonte: FonteDaResposta) => void }) {
  return (
    <li className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
      <code className="font-mono text-xs text-white/80">{variavel.nome}</code>
      <span className="font-semibold text-white">{variavel.valor ?? 'indisponível'}</span>
      <span className="text-xs">
        <Origem origem={variavel.origem} fontes={fontes} aoAbrir={aoAbrir} />
      </span>
    </li>
  )
}

type Props = {
  calculo: CalculoDaResposta
  fontes: FonteDaResposta[]
  aoAbrir: (fonte: FonteDaResposta) => void
}

export function ComoCalculei({ calculo, fontes, aoAbrir }: Props) {
  return (
    <details className="rounded-[12px] border border-white/12 bg-white/5 text-[13px] leading-[1.5] text-white/88">
      <summary className="cursor-pointer rounded-[12px] px-3 py-2 font-semibold text-atlas-lilas">
        Como calculei <span className="font-medium text-white/64">· {calculo.marcador} · resultado: {calculo.resultado}</span>
      </summary>
      <div className="flex flex-col gap-2.5 px-3 pb-3">
        {calculo.script && (
          <p className="[overflow-wrap:anywhere]">
            Conta: <code className="font-mono text-xs text-white">{calculo.script}</code>
          </p>
        )}
        {calculo.regra && <p>{calculo.regra}</p>}
        {calculo.variaveis.length > 0 && (
          <ul className="flex flex-col gap-1.5" aria-label="Números usados">
            {calculo.variaveis.map((variavel) => (
              <LinhaDaEntrada key={variavel.nome} variavel={variavel} fontes={fontes} aoAbrir={aoAbrir} />
            ))}
          </ul>
        )}
        {calculo.feriadosConsiderados.length > 0 && (
          <div>
            <p className="font-semibold">Feriados que não contam</p>
            <ul className="mt-1 flex flex-col gap-0.5">
              {calculo.feriadosConsiderados.map((feriado) => (
                <li key={feriado.data}>
                  {feriado.data} · {feriado.nome}
                </li>
              ))}
            </ul>
          </div>
        )}
        <p>
          Resultado: <span className="font-semibold text-white">{calculo.resultado}</span>
        </p>
      </div>
    </details>
  )
}
