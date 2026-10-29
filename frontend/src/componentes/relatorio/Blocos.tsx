// O catálogo fechado de blocos do relatório do Atlas. [ATLAS] [RELATORIO]
// Cada bloco só desenha o que o servidor mandou, já resolvido e formatado: nada é buscado nem calculado aqui, e todo texto
// entra como texto (o React escapa), nunca como HTML.
import type { Bloco, ItemKpi } from '../../api/atlas'
import { dataHora } from '../../util/formatar'

function Indisponivel({ motivo }: { motivo: string }) {
  return <span className="text-sm font-medium text-texto-2 italic">Indisponível · {motivo}</span>
}

function Kpi({ item }: { item: ItemKpi }) {
  return (
    <div className="rel-bloco flex flex-col gap-1 rounded-2xl border border-borda p-4">
      <p className="texto-sobretitulo text-texto-2">{item.rotulo}</p>
      {item.valor === null ? (
        <Indisponivel motivo={item.indisponivel ?? 'sem registro na base'} />
      ) : (
        <p className="font-display text-[44px] leading-[0.95] text-secao-texto">{item.valor}</p>
      )}
    </div>
  )
}

export function BlocoDoRelatorio({ bloco }: { bloco: Bloco }) {
  switch (bloco.tipo) {
    case 'capa':
      return (
        <header className="rel-bloco flex flex-col gap-2 border-b-4 border-secao pb-6">
          <p className="texto-sobretitulo text-texto-2">Planeta 360 · relatório do Atlas</p>
          <h2 className="font-display text-[52px] leading-none uppercase">{bloco.titulo}</h2>
          {(bloco.subtitulo || bloco.edicao) && (
            <p className="text-base font-semibold">{[bloco.subtitulo, bloco.edicao].filter(Boolean).join(' · ')}</p>
          )}
        </header>
      )
    case 'titulo':
      return <h4 className="rel-bloco text-base font-bold">{bloco.texto}</h4>
    case 'paragrafo':
      return <p className="rel-bloco text-sm leading-relaxed">{bloco.texto}</p>
    case 'kpi':
      return <Kpi item={bloco.item} />
    case 'kpi_grade':
      return (
        <div className="rel-bloco grid grid-cols-2 gap-3 md:grid-cols-4 print:grid-cols-4">
          {bloco.itens.map((item) => (
            <Kpi key={item.rotulo} item={item} />
          ))}
        </div>
      )
    case 'tabela':
      return (
        <div className="rel-bloco overflow-x-auto">
          <table className="w-full border-separate border-spacing-0 text-sm">
            <thead>
              <tr>
                {bloco.colunas.map((coluna, indice) => (
                  <th
                    key={coluna}
                    scope="col"
                    className={`bg-cinza-100 px-3 py-2 text-left font-semibold ${indice === 0 ? 'rounded-l-[10px]' : 'text-right'} ${
                      indice === bloco.colunas.length - 1 ? 'rounded-r-[10px]' : ''
                    }`}
                  >
                    {coluna}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {bloco.linhas.length === 0 ? (
                <tr>
                  <td colSpan={bloco.colunas.length} className="px-3 py-4 text-center text-texto-2">
                    Sem linhas no plano.
                  </td>
                </tr>
              ) : (
                bloco.linhas.map((linha, indiceLinha) => (
                  <tr key={indiceLinha}>
                    {linha.map((celula, indice) => (
                      <td key={indice} className={`border-b border-borda px-3 py-2 ${indice === 0 ? 'font-semibold' : 'text-right whitespace-nowrap'}`}>
                        {celula}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )
    case 'galeria_comprovacoes':
      // P0: a lista das fotos, sem a imagem. A foto em si fica na aba Aplicações (botão Baixar).
      return bloco.itens.length === 0 ? (
        <p className="rel-bloco text-sm text-texto-2">Nenhuma foto de comprovação registrada.</p>
      ) : (
        <ul className="rel-bloco flex flex-col gap-2">
          {bloco.itens.map((item) => (
            <li key={item.arquivoId} className="flex items-start gap-3 rounded-[10px] border border-borda px-3 py-2 text-sm">
              <i className="ri-image-line mt-0.5 text-lg text-secao-texto" aria-hidden="true" />
              <span className="flex min-w-0 flex-col">
                <span className="font-semibold break-words">{item.descricao || item.nomeOriginal}</span>
                <span className="text-xs text-texto-2">
                  {item.aplicacao} · {dataHora(item.registradoEm)}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )
    case 'campo_informado':
      return (
        <div className="rel-bloco flex flex-col gap-0.5 text-sm">
          <span className="texto-sobretitulo text-texto-2">{bloco.rotulo}</span>
          {bloco.valor === null ? <Indisponivel motivo="não informado pela marca" /> : <span className="font-semibold">{bloco.valor}</span>}
          {bloco.origem && <span className="text-xs text-texto-2">{bloco.origem}</span>}
        </div>
      )
    case 'aviso_indisponivel':
      return (
        <p className="rel-bloco rounded-[10px] bg-estado-ausente/14 px-4 py-3 text-sm">
          <span className="font-bold">Indisponível.</span> {bloco.motivo}
        </p>
      )
    case 'fontes':
      return (
        <dl className="rel-bloco flex flex-col gap-3 text-sm">
          {bloco.itens.map((item) => (
            <div key={item.ref} className="border-b border-borda pb-3">
              <dt className="font-semibold">
                {item.rotulo}
                {/* Uma lista (lista:aplicacoes) não tem um valor só: aparece com a origem, sem "indisponível". */}
                {item.valor !== null ? `: ${item.valor}` : item.ref.startsWith('lista:') ? '' : ': indisponível'}
              </dt>
              <dd className="text-xs text-texto-2">{item.origem}</dd>
              <dd className="font-mono text-[11px] text-cinza-400">{item.ref}</dd>
            </div>
          ))}
        </dl>
      )
    default:
      // Catálogo fechado: tipo que este front não conhece não é desenhado.
      return null
  }
}
