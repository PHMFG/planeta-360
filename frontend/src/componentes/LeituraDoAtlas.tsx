// A leitura de um arquivo pelo Atlas (plano da base da RBS, E3): a orbe pensando e as três etapas, enquanto a importação está
// LENDO. Serve a tela da RBS e a importação da planilha da agência pela marca.
import type { ImportacaoCompleta } from '../api/importacao'
import { Orbe } from '../atlas/Elementos'

const ETAPAS = [
  ['LENDO_ARQUIVO', 'Lendo o arquivo'],
  ['ENTENDENDO', 'Entendendo as colunas e os trechos'],
  ['CONFERINDO', 'Conferindo cada número na fonte'],
] as const

export function LeituraDoAtlas({ etapa, compacta = false }: { etapa: ImportacaoCompleta['etapa']; compacta?: boolean }) {
  const atual = Math.max(0, ETAPAS.findIndex(([codigo]) => codigo === etapa))
  return (
    <section className={`flex animate-entrar flex-col items-center gap-5 text-center ${compacta ? 'py-4' : 'rounded-[20px] border border-borda bg-white px-6 py-10'}`} aria-live="polite">
      <Orbe tamanho={compacta ? 56 : 72} estado="pensando" />
      <div>
        <h2 className="texto-titulo">O Atlas está lendo o arquivo</h2>
        <p className="mt-1 text-sm text-texto-2">Isso leva alguns segundos. Você pode sair desta tela: a importação continua.</p>
      </div>
      <ol className="flex flex-col gap-2 text-left text-sm">
        {ETAPAS.map(([codigo, nome], indice) => (
          <li key={codigo} className={`flex items-center gap-2 transition-colors duration-(--duracao-media) ${indice <= atual ? 'font-semibold text-tinta' : 'text-texto-2'}`}>
            <i
              className={
                indice < atual ? 'ri-checkbox-circle-fill text-aplicacoes' : indice === atual ? 'ri-loader-4-line animate-spin text-secao-texto' : 'ri-checkbox-blank-circle-line'
              }
              aria-hidden="true"
            />
            {nome}
          </li>
        ))}
      </ol>
    </section>
  )
}

