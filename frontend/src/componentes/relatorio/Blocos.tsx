// O catálogo fechado de blocos do relatório do Atlas, no estilo do documento do Figma (AT05, 202:1863): sóbrio, sem elementos
// da marca, tipografia neutra, divisores finos e referências numeradas. [ATLAS] [RELATORIO]
// Cada bloco só desenha o que o servidor mandou, já resolvido e formatado: nada é calculado aqui, e todo texto entra como texto
// (o React escapa), nunca como HTML. A única coisa que se busca é a imagem de uma foto da galeria, pela rota de download.
import { useEffect, useState } from 'react'
import type { Bloco, ItemKpi } from '../../api/atlas'
import { pedirArquivo } from '../../api/cliente'
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
          {item.selo && <span className="mt-0.5 block text-[10px] font-medium text-[#444]">{item.selo}</span>}
        </p>
      )}
    </div>
  )
}

type FotoDoRelatorio = Extract<Bloco, { tipo: 'galeria_comprovacoes' }>['itens'][number]

const FOTOS_AO_MESMO_TEMPO = 4
const fotosGuardadas = new Map<number, Promise<Blob>>()
const naFila: (() => void)[] = []
let baixando = 0

function proximaDaFila() {
  baixando -= 1
  naFila.shift()?.()
}

/**
 * Os bytes de uma foto: até 4 de cada vez, e uma vez só por tela aberta. A rota grava um DOWNLOAD por pedido, e reabrir o documento
 * não deve encher o histórico nem baixar de novo uma foto de até 20 MB. A falha não fica guardada: a próxima abertura tenta de novo.
 */
function bytesDaFoto(arquivoId: number) {
  let guardada = fotosGuardadas.get(arquivoId)
  if (!guardada) {
    guardada = new Promise<void>((liberar) => {
      if (baixando < FOTOS_AO_MESMO_TEMPO) liberar()
      else naFila.push(liberar)
    })
      .then(() => {
        baixando += 1
        return pedirArquivo(`/arquivos/${arquivoId}`).finally(proximaDaFila)
      })
      .catch((erro: unknown) => {
        fotosGuardadas.delete(arquivoId)
        throw erro
      })
    fotosGuardadas.set(arquivoId, guardada)
  }
  return guardada
}

/**
 * Uma foto da galeria. A rota de download só responde como anexo, então a imagem vem por fetch e vira um endereço blob:.
 * {@code data-foto-carregando} segura a impressão até a imagem chegar.
 */
function FotoDaGaleria({ item }: { item: FotoDoRelatorio }) {
  const [endereco, setEndereco] = useState<string | null>(null)
  const [situacao, setSituacao] = useState<'carregando' | 'pronta' | 'indisponivel'>('carregando')
  const legenda = item.descricao || item.nomeOriginal

  useEffect(() => {
    let saiu = false
    let criado: string | null = null
    bytesDaFoto(item.arquivoId)
      .then((arquivo) => {
        if (saiu) return
        if (!arquivo.type.startsWith('image/')) {
          setSituacao('indisponivel')
          return
        }
        criado = URL.createObjectURL(arquivo)
        setEndereco(criado)
      })
      .catch(() => {
        if (!saiu) setSituacao('indisponivel')
      })
    return () => {
      saiu = true
      if (criado) URL.revokeObjectURL(criado)
    }
  }, [item.arquivoId])

  return (
    <figure className="rel-bloco flex min-w-0 flex-col gap-1.5" data-foto-carregando={situacao === 'carregando' ? '' : undefined}>
      <div className="grid aspect-[4/3] place-items-center overflow-hidden rounded-sm bg-[#f2f2f2]">
        {situacao === 'indisponivel' ? (
          <span className="px-3 text-center text-[10px] text-[#6b6b6b]">Foto indisponível</span>
        ) : (
          endereco && (
            <img
              src={endereco}
              alt={legenda}
              decoding="sync"
              className="size-full object-cover"
              // A impressão só desenha a imagem já decodificada: sem isso, as últimas fotos saem em branco no A4.
              onLoad={(evento) => void evento.currentTarget.decode().catch(() => undefined).finally(() => setSituacao('pronta'))}
              onError={() => setSituacao('indisponivel')}
            />
          )
        )}
      </div>
      <figcaption className="text-[10px] leading-snug text-[#6b6b6b]">
        <span className="block break-words text-[#222]">{legenda}</span>
        {item.aplicacao} · {dataHora(item.registradoEm)}
      </figcaption>
    </figure>
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
      return bloco.itens.length === 0 ? (
        <p className="rel-bloco text-[11px] text-[#6b6b6b]">Nenhuma foto de comprovação registrada.</p>
      ) : (
        <div className="grid grid-cols-2 gap-3 @md:grid-cols-3 print:grid-cols-3">
          {bloco.itens.map((item) => (
            <FotoDaGaleria key={item.arquivoId} item={item} />
          ))}
        </div>
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
