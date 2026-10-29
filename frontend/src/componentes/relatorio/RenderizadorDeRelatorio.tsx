// Desenha o relatório do Atlas a partir da especificação já resolvida pelo servidor: seções na ordem, blocos do catálogo e
// o selo de documento de trabalho. A cor vem da aba do modelo (data-secao). [ATLAS] [RELATORIO]
import type { RelatorioAtlas } from '../../api/atlas'
import type { ChaveSecao } from '../secoes'
import { BlocoDoRelatorio } from './Blocos'
import './relatorio.css'

/** A aba de cada modelo de relatório, para a cor do documento. */
const SECAO_DO_MODELO: Record<RelatorioAtlas['modelo'], ChaveSecao> = { ATIVACAO_EVENTO: 'aplicacoes' }

export function RenderizadorDeRelatorio({ relatorio }: { relatorio: RelatorioAtlas }) {
  return (
    <article
      data-secao={SECAO_DO_MODELO[relatorio.modelo] ?? 'parceria'}
      className="flex flex-col gap-8 rounded-[20px] border border-borda bg-white p-5 sm:p-10 print:border-0 print:p-0"
    >
      <p className="rel-bloco rounded-[10px] bg-secao-tinta px-4 py-2 text-xs font-semibold text-secao-texto">{relatorio.selo}</p>
      {relatorio.secoes.map((secao) => (
        <section key={secao.id} className="rel-secao flex flex-col gap-4">
          {secao.titulo && <h3 className="rel-bloco texto-titulo">{secao.titulo}</h3>}
          {secao.blocos.map((bloco) => (
            <BlocoDoRelatorio key={bloco.id} bloco={bloco} />
          ))}
        </section>
      ))}
    </article>
  )
}
