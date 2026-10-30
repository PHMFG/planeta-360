// O catálogo fechado de blocos do relatório do Atlas, no estilo do documento do Figma (AT05, 202:1863): sóbrio, sem elementos
// da marca, tipografia neutra, divisores finos e referências numeradas. [ATLAS] [RELATORIO]
// Cada bloco só desenha o que o servidor mandou, já resolvido e formatado: nada é buscado nem calculado aqui, e todo texto
// entra como texto (o React escapa), nunca como HTML.
import type { Bloco, ItemKpi } from '../../api/atlas'
import { dataHora } from '../../util/formatar'

/** O número da referência de cada fonte ([1], [2]...), na ordem do bloco "fontes". */
export type Referencias = Map<string, number>

function Citacao({ ref_, referencias }: { ref_: string | null; referencias: Referencias }) {
  const numero = ref_ ? referencias.get(ref_) : undefined
  return numero ? <sup className="ml-0.5 text-[9px] font-medium text-[#8a8a8a]">[{numero}]</sup> : null
}

function Kpi({ item, referencias }: { item: ItemKpi; referencias: Referencias }) {
  return (
    <div className="rel-bloco flex flex-col gap-1 border-t border-[#d9d9d9] pt-2">
      <p className="text-[10px] font-medium tracking-[0.06em] text-[#6b6b6b] uppercase">{item.rotulo}</p>
      {item.valor === null ? (
        <p className="text-[11px] text-[#6b6b6b]">
          <span className="italic">Indisponível.</span> {item.indisponivel ?? 'Sem registro na base.'}
        </p>
      ) : (
        <p className="text-lg leading-tight font-semibold text-[#111]">
          {item.valor}
          <Citacao ref_={item.ref} referencias={referencias} />
        </p>
      )}
    </div>
  )
}

export function BlocoDoRelatorio({ bloco, referencias }: { bloco: Bloco; referencias: Referencias }) {
  switch (bloco.tipo) {
    case 'capa':
      // A capa vira o cabeçalho do documento (RenderizadorDeRelatorio); aqui não se repete.
      return null
    case 'titulo':
      return <h4 className="rel-bloco text-xs font-semibold text-[#111]">{bloco.texto}</h4>
    case 'paragrafo':
      return <p className="rel-bloco text-xs leading-[1.65] text-[#222]">{bloco.texto}</p>
    case 'kpi':
      return <Kpi item={bloco.item} referencias={referencias} />
    case 'kpi_grade':
      return (
        // A grade segue a largura do documento (@container), não a da tela: ao lado do chat, a página fica estreita.
        <div className="rel-bloco grid grid-cols-2 gap-x-4 gap-y-3 @md:grid-cols-4 print:grid-cols-4">
          {bloco.itens.map((item) => (
            <Kpi key={item.rotulo} item={item} referencias={referencias} />
          ))}
        </div>
      )
    case 'tabela':
      return (
        <div className="rel-bloco overflow-x-auto">
          <table className="w-full border-collapse text-[11px]">
            <thead>
              <tr>
                {bloco.colunas.map((coluna, indice) => (
                  <th
                    key={coluna}
                    scope="col"
                    className={`border-b border-[#bdbdbd] py-[7px] font-semibold text-[#444] ${indice === 0 ? 'text-left' : 'pl-3 text-right'}`}
                  >
                    {coluna}
                    {indice === bloco.colunas.length - 1 && <Citacao ref_={bloco.ref} referencias={referencias} />}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {bloco.linhas.length === 0 ? (
                <tr>
                  <td colSpan={bloco.colunas.length} className="border-b border-[#e6e6e6] py-[7px] text-[#6b6b6b]">
                    Sem linhas no plano comercial.
                  </td>
                </tr>
              ) : (
                bloco.linhas.map((linha, indiceLinha) => (
                  <tr key={indiceLinha}>
                    {linha.map((celula, indice) => (
                      <td key={indice} className={`border-b border-[#e6e6e6] py-[7px] text-[#222] ${indice === 0 ? '' : 'pl-3 text-right whitespace-nowrap'}`}>
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
      // A lista das fotos, sem a imagem: a foto em si fica na aba Aplicações (botão Baixar).
      return bloco.itens.length === 0 ? (
        <p className="rel-bloco text-[11px] text-[#6b6b6b]">Nenhuma foto de comprovação registrada.</p>
      ) : (
        <ul className="rel-bloco flex flex-col text-[11px]">
          {bloco.itens.map((item) => (
            <li key={item.arquivoId} className="flex gap-3 border-b border-[#e6e6e6] py-[7px] text-[#222]">
              <span className="min-w-0 flex-1 break-words">{item.descricao || item.nomeOriginal}</span>
              <span className="shrink-0 text-[#6b6b6b]">
                {item.aplicacao} · {dataHora(item.registradoEm)}
              </span>
            </li>
          ))}
        </ul>
      )
    case 'campo_informado':
      return (
        <div className="rel-bloco grid grid-cols-[90px_1fr] gap-x-3 text-[11px]">
          <span className="font-medium text-[#444]">{bloco.rotulo}</span>
          <span className="text-[#222]">
            {bloco.valor ?? <span className="italic text-[#6b6b6b]">Indisponível · não informado pela marca</span>}
            {bloco.origem && <span className="block text-[10px] text-[#8a8a8a]">{bloco.origem}</span>}
          </span>
        </div>
      )
    case 'aviso_indisponivel':
      return (
        <div className="rel-bloco rounded border border-[#bdbdbd] px-3 py-2.5 text-[11px] leading-normal text-[#222]">
          <p className="font-semibold text-[#111]">Indisponível.</p>
          <p>{bloco.motivo} O Atlas não estima valores para completar lacunas.</p>
        </div>
      )
    case 'fontes':
      return (
        <ol className="rel-bloco flex flex-col gap-1.5 text-[10px] leading-normal text-[#444]">
          {bloco.itens.map((item, indice) => (
            <li key={item.ref} className="flex gap-1.5">
              <span className="shrink-0 font-semibold">[{indice + 1}]</span>
              <span className="min-w-0">
                <span className="font-semibold text-[#222]">{item.rotulo}</span>
                {/* Uma lista (lista:aplicacoes) não tem um valor só: aparece com a origem, sem "indisponível". */}
                {item.valor !== null ? `: ${item.valor}` : item.ref.startsWith('lista:') ? '' : ': indisponível'} · {item.origem}
              </span>
            </li>
          ))}
        </ol>
      )
    default:
      // Catálogo fechado: tipo que este front não conhece não é desenhado.
      return null
  }
}
