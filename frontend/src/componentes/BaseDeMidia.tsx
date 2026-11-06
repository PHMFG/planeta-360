// A base de mídia na tela (plano da base da RBS, M3 a M6): os filtros (mês, veículo, formato e tipo; na RBS também a marca e a
// cota), o total por unidade e a tabela linha do plano × mês, com o contratado, o entregue, o impacto e os comprovantes.
// A mesma peça serve a aba Mídia da marca (rota 105) e a tela Dados da RBS (rota 107). O download leva os mesmos filtros.
import { Suspense, lazy, useEffect, useMemo, useState } from 'react'
import { ErroApi, lerArquivo } from '../api/cliente'
import { nomeDoMes } from '../api/comprovacao'
import { baixarArquivo } from '../api/materiais'
import {
  NOME_DO_TIPO_DE_MIDIA,
  baixarMidiaDaEdicao,
  baixarMidiaDaParceria,
  obterMidiaDaEdicao,
  obterMidiaDaParceria,
  type FiltrosDeMidia,
  type LinhaDeMidia,
} from '../api/midia'
import { useDados } from '../api/useDados'
import { numero } from '../util/formatar'
import { Aviso } from './Aviso'
import { Barra } from './Barra'
import { Botao } from './Botao'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { Busca, Seletor } from './Formulario'
import { LinkExterno } from './LinkExterno'
import { Modal } from './Modal'
import { Tabela } from './Tabela'

const VisualizadorDePdf = lazy(() => import('../paginas/parceria/VisualizadorDeContrato'))

const UNIDADES: Record<string, string> = { INSERCAO: 'Inserções', CPM: 'CPM (mil impressões)', APLICACAO: 'Aplicações' }

/** O comprovante aberto na tela (M5): imagem direto, PDF no leitor; o resto só baixa. */
function Comprovante({ item }: { item: { arquivoId: number; nome: string; tipo: string } }) {
  const tipo = item.tipo.toUpperCase()
  const ehImagem = ['PNG', 'JPG', 'JPEG', 'WEBP', 'GIF'].includes(tipo)
  const [imagem, setImagem] = useState<string | null>(null)
  useEffect(() => {
    if (!ehImagem) return
    let url: string | null = null
    let ativo = true
    lerArquivo(`/arquivos/${item.arquivoId}`)
      .then((blob) => {
        if (!ativo) return
        url = URL.createObjectURL(blob)
        setImagem(url)
      })
      .catch(() => ativo && setImagem(null))
    return () => {
      ativo = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [ehImagem, item.arquivoId])
  if (tipo === 'PDF')
    return (
      <Suspense fallback={<p className="text-sm text-texto-2">Abrindo o PDF…</p>}>
        <VisualizadorDePdf arquivoId={item.arquivoId} nome={item.nome} />
      </Suspense>
    )
  if (!ehImagem) return <p className="text-sm text-texto-2">Este tipo de arquivo não abre na tela. Use “Baixar”.</p>
  return imagem ? (
    <img src={imagem} alt={`Comprovante ${item.nome}`} className="max-h-[60vh] w-full rounded-xl bg-fundo object-contain animate-surgir" />
  ) : (
    <div className="esqueleto h-64 rounded-xl" role="status">
      <span className="sr-only">Carregando…</span>
    </div>
  )
}

type Agrupada = { chave: string; nome: string; unidade: string; previsto: number; exibido: number; impacto: number | null; percentual: number | null; linhas: number }

/** Soma as linhas por marca ou por veículo, sempre separando a unidade (inserção não se soma com CPM). */
function agrupar(linhas: LinhaDeMidia[], por: (linha: LinhaDeMidia) => { chave: string; nome: string }): Agrupada[] {
  const grupos = new Map<string, Agrupada>()
  for (const linha of linhas) {
    const { chave, nome } = por(linha)
    const id = `${chave}|${linha.unidade}`
    const grupo = grupos.get(id) ?? { chave, nome, unidade: linha.unidade, previsto: 0, exibido: 0, impacto: null, percentual: null, linhas: 0 }
    grupo.previsto += linha.previsto ?? 0
    grupo.exibido += linha.exibido
    if (linha.impacto !== null) grupo.impacto = (grupo.impacto ?? 0) + linha.impacto
    grupo.linhas += 1
    grupos.set(id, grupo)
  }
  return [...grupos.values()]
    .map((grupo) => ({ ...grupo, percentual: grupo.previsto > 0 ? Math.floor((grupo.exibido / grupo.previsto) * 1000) / 10 : null }))
    .sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR') || a.unidade.localeCompare(b.unidade))
}

type Props =
  | { modo: 'parceria'; parceriaId: number; apelido: string; comMes: boolean }
  | { modo: 'edicao'; eventoId?: number; parcerias: { id: number; nome: string }[] }

export function BaseDeMidia(props: Props) {
  const [mes, setMes] = useState('')
  const [veiculo, setVeiculo] = useState('')
  const [formato, setFormato] = useState('')
  const [tipoMidia, setTipoMidia] = useState('')
  const [parceriaId, setParceriaId] = useState('')
  const [cota, setCota] = useState('')
  const [baixando, setBaixando] = useState<'xlsx' | 'csv' | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [comprovantes, setComprovantes] = useState<LinhaDeMidia | null>(null)
  const [aberto, setAberto] = useState<number | null>(null)

  const filtros: FiltrosDeMidia = {
    mes: mes || undefined,
    veiculo: veiculo || undefined,
    formato: formato.trim() || undefined,
    tipoMidia: tipoMidia || undefined,
    ...(props.modo === 'edicao' ? { eventoId: props.eventoId, parceriaId: parceriaId ? Number(parceriaId) : undefined, cota: cota || undefined } : {}),
  }
  const chave = JSON.stringify(filtros)
  const dados = useDados(
    () => (props.modo === 'parceria' ? obterMidiaDaParceria(props.parceriaId, filtros) : obterMidiaDaEdicao(filtros)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [chave, props.modo === 'parceria' ? props.parceriaId : props.eventoId],
  )
  // As opções dos seletores vêm da base sem filtro, para não sumir veículo que o filtro de agora esconde.
  const semFiltro = useDados(
    () => (props.modo === 'parceria' ? obterMidiaDaParceria(props.parceriaId) : obterMidiaDaEdicao({ eventoId: props.eventoId })),
    [props.modo === 'parceria' ? props.parceriaId : props.eventoId],
  )
  const veiculos = useMemo(() => [...new Set((semFiltro.dado?.linhas ?? []).map((linha) => linha.veiculo))].sort((a, b) => a.localeCompare(b, 'pt-BR')), [semFiltro.dado])
  const tipos = useMemo(() => [...new Set((semFiltro.dado?.linhas ?? []).map((linha) => linha.tipoMidia).filter(Boolean) as string[])], [semFiltro.dado])
  const meses = semFiltro.dado?.meses ?? []

  async function baixar(formatoDoArquivo: 'xlsx' | 'csv') {
    setBaixando(formatoDoArquivo)
    setFalha(null)
    try {
      if (props.modo === 'parceria') await baixarMidiaDaParceria(props.parceriaId, props.apelido, filtros, formatoDoArquivo)
      else await baixarMidiaDaEdicao(filtros, formatoDoArquivo)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar a planilha.')
    } finally {
      setBaixando(null)
    }
  }

  const filtrando = Boolean(mes || veiculo || formato.trim() || tipoMidia || parceriaId || cota)
  // Sem veículo nem busca, a tabela resume (por marca na edição inteira, por veículo numa marca) e um toque abre o detalhe.
  const resumo: 'marca' | 'veiculo' | null = veiculo || formato.trim() ? null : props.modo === 'edicao' && !parceriaId ? 'marca' : 'veiculo'

  return (
    <Cartao
      titulo="Contratado x entregue"
      extra={
        <div className="flex flex-wrap gap-2">
          <Botao variante="secundario" className="min-w-0 px-4" icone="ri-file-excel-2-line" carregando={baixando === 'xlsx'} onClick={() => baixar('xlsx')}>
            Baixar {filtrando ? 'o filtro' : 'tudo'} (.xlsx)
          </Botao>
          <Botao variante="secundario" className="min-w-0 px-4" carregando={baixando === 'csv'} onClick={() => baixar('csv')}>
            CSV
          </Botao>
        </div>
      }
    >
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {props.modo === 'edicao' && (
          <>
            <Seletor rotulo="Marca" value={parceriaId} onChange={(evento) => setParceriaId(evento.target.value)}>
              <option value="">Todas as marcas</option>
              {props.parcerias.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
            <Seletor rotulo="Cota" value={cota} onChange={(evento) => setCota(evento.target.value)}>
              <option value="">Todas as cotas</option>
              <option value="MASTER">Master</option>
              <option value="SUBCOTA_G">Subcota G</option>
              <option value="SUBCOTA">Subcota</option>
            </Seletor>
          </>
        )}
        {(props.modo === 'edicao' || props.comMes) && (
          <Seletor rotulo="Mês" value={mes} onChange={(evento) => setMes(evento.target.value)}>
            <option value="">Todos os meses</option>
            {meses.map((item) => (
              <option key={item} value={item}>
                {nomeDoMes(item)}
              </option>
            ))}
          </Seletor>
        )}
        <Seletor rotulo="Veículo" value={veiculo} onChange={(evento) => setVeiculo(evento.target.value)}>
          <option value="">Todos os veículos ({veiculos.length})</option>
          {veiculos.map((nome) => (
            <option key={nome} value={nome}>
              {nome}
            </option>
          ))}
        </Seletor>
        {tipos.length > 1 && (
          <Seletor rotulo="Tipo de mídia" value={tipoMidia} onChange={(evento) => setTipoMidia(evento.target.value)}>
            <option value="">Todos os tipos</option>
            {tipos.map((tipo) => (
              <option key={tipo} value={tipo}>
                {NOME_DO_TIPO_DE_MIDIA[tipo] ?? tipo}
              </option>
            ))}
          </Seletor>
        )}
        <div className="sm:col-span-2 lg:col-span-2">
          <Busca valor={formato} aoMudar={setFormato} placeholder="Buscar formato, programa ou espaço (ex.: chamada, página dupla)…" />
        </div>
      </div>
      {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}

      <Carregando dados={dados} linhas={6}>
        {(base) =>
          base.linhas.length === 0 ? (
            <Aviso tipo={filtrando ? 'vazio' : 'ausente'} titulo={filtrando ? 'Nada com esses filtros.' : 'Ainda sem números de veiculação'}>
              {filtrando ? undefined : 'Os números aparecem quando a RBS importa as planilhas e os comprovantes dos veículos.'}
            </Aviso>
          ) : (
            <div className="flex flex-col gap-5">
              {/* [DUPLICIDADE] Unidades diferentes nunca se somam: um total por unidade. */}
              <ul className="flex flex-col gap-3" aria-label="Total por unidade">
                {base.totais.map((total) => (
                  <li key={total.unidade} className="grid items-center gap-x-6 gap-y-1 sm:grid-cols-[260px_1fr]">
                    <p className="text-sm">
                      <span className="font-semibold">{UNIDADES[total.unidade] ?? total.unidade}</span>
                      <span className="text-texto-2">
                        {' '}
                        · {numero(total.exibido)} de {numero(total.previsto)}
                        {total.impacto !== null && <> · {numero(total.impacto)} de impacto</>}
                      </span>
                    </p>
                    <Barra valor={total.percentual} rotulo={`Entregue sobre o contratado em ${UNIDADES[total.unidade] ?? total.unidade}`} />
                  </li>
                ))}
              </ul>
              {resumo ? (
                <>
                  <Tabela<Agrupada>
                    rotulo={resumo === 'marca' ? 'Contratado x entregue por marca' : 'Contratado x entregue por veículo'}
                    linhas={agrupar(base.linhas, (linha) =>
                      resumo === 'marca'
                        ? { chave: String(linha.parceria?.id ?? ''), nome: linha.parceria?.patrocinador ?? '—' }
                        : { chave: linha.veiculo, nome: linha.veiculo },
                    )}
                    chave={(grupo) => `${grupo.chave}-${grupo.unidade}`}
                    aoClicar={(grupo) => (resumo === 'marca' ? setParceriaId(grupo.chave) : setVeiculo(grupo.nome))}
                    colunas={[
                      { titulo: resumo === 'marca' ? 'Marca' : 'Veículo', celula: (grupo) => <span className="font-semibold">{grupo.nome}</span> },
                      { titulo: 'Unidade', celula: (grupo) => UNIDADES[grupo.unidade] ?? grupo.unidade },
                      { titulo: 'Contratado', celula: (grupo) => numero(grupo.previsto), classe: 'text-right' },
                      { titulo: 'Entregue', celula: (grupo) => numero(grupo.exibido), classe: 'text-right' },
                      { titulo: 'Entrega', celula: (grupo) => <Barra valor={grupo.percentual} rotulo="Entregue sobre o contratado" /> },
                      { titulo: 'Impacto', celula: (grupo) => (grupo.impacto === null ? '—' : numero(grupo.impacto)), classe: 'text-right' },
                    ]}
                  />
                  <p className="text-xs font-medium text-texto-2">
                    Toque {resumo === 'marca' ? 'numa marca para ver os veículos dela' : 'num veículo para ver os formatos, o impacto e os comprovantes'}.
                  </p>
                </>
              ) : (
              <Tabela<LinhaDeMidia>
                rotulo="Contratado x entregue por linha do plano"
                linhas={base.linhas}
                chave={(linha) => `${linha.parceria?.id ?? ''}-${linha.aproveitamentoId}-${linha.mes ?? ''}`}
                colunas={[
                  ...(props.modo === 'edicao' ? [{ titulo: 'Marca', celula: (linha: LinhaDeMidia) => <span className="font-semibold">{linha.parceria?.patrocinador}</span> }] : []),
                  // Com um mês escolhido, a coluna só repetiria o filtro.
                  ...(!mes && base.linhas.some((linha) => linha.mes) ? [{ titulo: 'Mês', celula: (linha: LinhaDeMidia) => (linha.mes ? nomeDoMes(linha.mes) : '—') }] : []),
                  { titulo: 'Veículo', celula: (linha) => <span className="font-semibold">{linha.veiculo}</span> },
                  {
                    titulo: 'Formato',
                    celula: (linha) => (
                      <span>
                        {linha.formato}
                        {linha.espacoPrograma && <span className="block text-xs text-texto-2">{linha.espacoPrograma}</span>}
                      </span>
                    ),
                  },
                  { titulo: 'Contratado', celula: (linha) => numero(linha.previsto), classe: 'text-right' },
                  { titulo: 'Entregue', celula: (linha) => numero(linha.exibido), classe: 'text-right' },
                  { titulo: 'Entrega', celula: (linha) => <Barra valor={linha.percentual} rotulo="Entregue sobre o contratado" /> },
                  { titulo: 'Impacto', celula: (linha) => (linha.impacto === null ? '—' : numero(linha.impacto)), classe: 'text-right' },
                  {
                    titulo: 'Comprovantes',
                    celula: (linha) =>
                      linha.comprovantes.length + linha.links.length === 0 ? (
                        <span className="text-xs text-texto-2">—</span>
                      ) : (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 text-xs font-bold text-secao-texto hover:underline"
                          onClick={(evento) => {
                            evento.stopPropagation()
                            setComprovantes(linha)
                            setAberto(linha.comprovantes[0]?.arquivoId ?? null)
                          }}
                        >
                          <i className="ri-image-line" aria-hidden="true" />
                          {linha.comprovantes.length + linha.links.length}
                        </button>
                      ),
                  },
                ]}
              />
              )}
            </div>
          )
        }
      </Carregando>

      {comprovantes && (
        <Modal titulo="Comprovantes" legenda={`${comprovantes.veiculo} · ${comprovantes.formato}${comprovantes.mes ? ` · ${nomeDoMes(comprovantes.mes)}` : ''}`} aberto aoFechar={() => setComprovantes(null)}>
          {(() => {
            const item = comprovantes.comprovantes.find((comprovante) => comprovante.arquivoId === aberto)
            return item ? (
              <div className="mb-3">
                <Comprovante key={item.arquivoId} item={item} />
              </div>
            ) : null
          })()}
          <ul className="flex flex-col gap-2 text-sm">
            {comprovantes.comprovantes.map((item) => (
              <li
                key={item.arquivoId}
                className={`flex items-center justify-between gap-3 rounded-xl border px-4 py-3 ${item.arquivoId === aberto ? 'border-secao bg-secao/5' : 'border-borda'}`}
              >
                <button type="button" className="truncate text-left font-semibold hover:underline" onClick={() => setAberto(item.arquivoId)} aria-pressed={item.arquivoId === aberto}>
                  {item.nome}
                </button>
                <button type="button" className="shrink-0 text-xs font-bold text-secao-texto hover:underline" onClick={() => baixarArquivo({ id: item.arquivoId, nomeOriginal: item.nome })}>
                  Baixar
                </button>
              </li>
            ))}
            {comprovantes.links.map((link) => (
              <li key={link} className="rounded-xl border border-borda px-4 py-3">
                <LinkExterno href={link} className="font-semibold text-secao-texto hover:underline">
                  {link}
                </LinkExterno>
              </li>
            ))}
          </ul>
        </Modal>
      )}
    </Cartao>
  )
}
