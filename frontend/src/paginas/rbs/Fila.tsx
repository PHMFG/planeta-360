// Materiais da RBS (Figma R01; plano da base da RBS, U8 e U9): duas abas.
// "Para analisar" é a fila de antes (as que esperam análise primeiro), agora com filtro de marca.
// "Falta receber" responde à pergunta da reunião de 05/11: o que a RBS ainda não recebeu de cada marca, por aba.
// As duas leem a rota 16 (a fila), que já filtra por marca e por status; nenhuma rota nova.
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEntregas, listarFila, type Entrega, type ItemFila } from '../../api/materiais'
import { listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Busca, Filtros, Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { SECOES } from '../../componentes/secoes'
import { Status } from '../../componentes/Status'
import { Tabela } from '../../componentes/Tabela'
import { DetalheEntrega } from '../parceria/DetalheEntrega'
import { baixarCsv } from '../../util/csv'
import { data, dataHora } from '../../util/formatar'

type Visao = 'analise' | 'ajuste' | 'aprovadas' | 'falta' | 'todas'

/** Falta receber: o que espera a marca (aberto, ajuste pedido) e o que a RBS ainda não abriu. */
const FALTA_RECEBER = 'NAO_INICIADO,AGUARDANDO_ENVIO,AJUSTE_SOLICITADO'

const STATUS_DA_VISAO: Record<Visao, string | undefined> = {
  analise: 'ENVIADO,EM_ANALISE',
  ajuste: 'AJUSTE_SOLICITADO',
  aprovadas: 'APROVADO',
  falta: FALTA_RECEBER,
  todas: undefined,
}

const VISOES: Visao[] = ['analise', 'ajuste', 'aprovadas', 'falta', 'todas']
const ABAS_COM_ENTREGA = SECOES.filter((secao) => secao.aba)

export function Fila() {
  const [parametros, setParametros] = useSearchParams()
  const aba = parametros.get('aba') === 'falta' ? 'falta' : 'analise'

  return (
    <div data-secao="midia" className="flex flex-col gap-4">
      <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo="Materiais" subtitulo="O que as marcas enviaram e o que ainda falta receber." />
      <div role="tablist" aria-label="Materiais" className="flex gap-2">
        {(
          [
            ['analise', 'Para analisar'],
            ['falta', 'Falta receber'],
          ] as const
        ).map(([valor, nome]) => (
          <button
            key={valor}
            type="button"
            role="tab"
            aria-selected={aba === valor}
            onClick={() => setParametros(valor === 'falta' ? { aba: 'falta' } : {})}
            className={`rounded-full px-4 py-2 text-[13px] font-semibold transition-colors ${aba === valor ? 'bg-tinta text-white' : 'bg-white text-texto-2 hover:text-tinta'}`}
          >
            {nome}
          </button>
        ))}
      </div>
      {aba === 'falta' ? <FaltaReceber /> : <ParaAnalisar visaoInicial={parametros.get('status')} />}
    </div>
  )
}

function ParaAnalisar({ visaoInicial }: { visaoInicial: string | null }) {
  const [visao, setVisao] = useState<Visao>(VISOES.includes(visaoInicial as Visao) ? (visaoInicial as Visao) : 'analise')
  const [aba, setAba] = useState('')
  const [parceriaId, setParceriaId] = useState('')
  const [busca, setBusca] = useState('')
  const [atrasadas, setAtrasadas] = useState(false)
  const [pagina, setPagina] = useState(0)
  const parcerias = useDados(() => listarParcerias(), [])
  const fila = useDados(
    () =>
      listarFila({
        status: STATUS_DA_VISAO[visao],
        abaCodigo: aba || undefined,
        parceriaId: parceriaId ? Number(parceriaId) : undefined,
        q: busca.trim() || undefined,
        atrasadas: atrasadas || undefined,
        pagina,
      }),
    [visao, aba, parceriaId, busca, atrasadas, pagina],
  )
  const [aberta, setAberta] = useState<Entrega | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  // O status que vem do Painel (?status=) vale também quando a pessoa clica em outro número do Painel sem sair da tela.
  useEffect(() => {
    if (VISOES.includes(visaoInicial as Visao)) setVisao(visaoInicial as Visao)
  }, [visaoInicial])

  // A fila traz o resumo; o detalhe (com as versões) vem das entregas da parceria.
  async function abrir(item: ItemFila) {
    setFalha(null)
    try {
      const entregas = await listarEntregas(item.parceria.id, item.abaCodigo)
      setAberta(entregas.find((entrega) => entrega.id === item.id) ?? null)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir a entrega.')
    }
  }

  const mudarFiltro = <T,>(definir: (valor: T) => void) => (valor: T) => {
    definir(valor)
    setPagina(0)
  }

  return (
    <Cartao>
      <div className="mb-4 grid gap-4 md:grid-cols-[1fr_220px_220px]">
        <Busca valor={busca} aoMudar={mudarFiltro(setBusca)} placeholder="Buscar pelo título da entrega…" />
        <Seletor rotulo="Marca" value={parceriaId} onChange={(evento) => mudarFiltro(setParceriaId)(evento.target.value)}>
          <option value="">Todas as marcas</option>
          {(parcerias.dado ?? []).map((parceria) => (
            <option key={parceria.id} value={parceria.id}>
              {parceria.patrocinador.nome} · {parceria.evento.edicao}
            </option>
          ))}
        </Seletor>
        <Seletor rotulo="Aba" value={aba} onChange={(evento) => mudarFiltro(setAba)(evento.target.value)}>
          <option value="">Todas as abas</option>
          {ABAS_COM_ENTREGA.map((secao) => (
            <option key={secao.chave} value={secao.aba!}>
              {secao.nome}
            </option>
          ))}
        </Seletor>
      </div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-4">
        <Filtros<Visao>
          valor={visao}
          aoMudar={mudarFiltro(setVisao)}
          opcoes={[
            { valor: 'analise', nome: 'Esperando análise' },
            { valor: 'ajuste', nome: 'Ajuste solicitado' },
            { valor: 'aprovadas', nome: 'Aprovadas' },
            { valor: 'falta', nome: 'Falta receber' },
            { valor: 'todas', nome: 'Todas' },
          ]}
        />
        <label className="flex items-center gap-2 text-xs font-semibold">
          <input type="checkbox" className="size-4 accent-[var(--secao-cor)]" checked={atrasadas} onChange={(evento) => mudarFiltro(setAtrasadas)(evento.target.checked)} />
          Só com prazo vencido
        </label>
      </div>
      {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
      <Carregando dados={fila} linhas={6}>
        {(dado) => (
          <>
            <Tabela<ItemFila>
              linhas={dado.itens}
              chave={(item) => item.id}
              vazio="Nada na fila com esses filtros."
              aoClicar={abrir}
              colunas={[
                {
                  titulo: 'Marca',
                  celula: (item) => (
                    <Link to={`/parceria/${item.parceria.id}`} viewTransition className="font-semibold hover:underline" onClick={(evento) => evento.stopPropagation()}>
                      {item.parceria.patrocinador}
                    </Link>
                  ),
                },
                { titulo: 'Aba', celula: (item) => SECOES.find((secao) => secao.aba === item.abaCodigo)?.nome ?? item.abaCodigo },
                { titulo: 'Entrega', celula: (item) => item.titulo },
                { titulo: 'Status', celula: (item) => <Status codigo={item.status} /> },
                { titulo: 'Último envio', celula: (item) => dataHora(item.ultimoEnvioEm) },
                {
                  titulo: 'Prazo',
                  celula: (item) => <span className={item.atrasada ? 'font-bold text-[#c8203f]' : ''}>{data(item.prazo)}{item.atrasada && ' · vencido'}</span>,
                },
              ]}
            />
            <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
          </>
        )}
      </Carregando>
      <DetalheEntrega entrega={aberta} aoFechar={() => setAberta(null)} aoMudar={fila.recarregar} />
    </Cartao>
  )
}

/** Todas as páginas da fila com os status de "falta receber" (até 2.000 entregas; a edição tem algumas centenas). */
async function tudoQueFalta() {
  const itens: ItemFila[] = []
  for (let pagina = 0; pagina < 10; pagina++) {
    const resposta = await listarFila({ status: FALTA_RECEBER, pagina, tamanho: 200 })
    itens.push(...resposta.itens)
    if (itens.length >= resposta.total || resposta.itens.length === 0) break
  }
  return itens
}

type LinhaDaMarca = { parceriaId: number; marca: string; cota: string; porAba: Record<string, ItemFila[]>; total: number; atrasadas: number }

/** U9: uma linha por marca, uma coluna por aba, com o que ainda não chegou; a linha abre as entregas. */
function FaltaReceber() {
  const falta = useDados(tudoQueFalta, [])
  const [aberta, setAberta] = useState<number | null>(null)

  return (
    <Cartao
      titulo="Falta receber"
      extra={
        falta.dado && falta.dado.length > 0 ? (
          <button
            type="button"
            className="flex items-center gap-1 text-[13px] font-semibold text-secao-texto hover:underline"
            onClick={() =>
              baixarCsv(
                'falta-receber.csv',
                ['Marca', 'Aba', 'Entrega', 'Status', 'Prazo', 'Vencido'],
                falta.dado!.map((item) => [
                  item.parceria.patrocinador,
                  SECOES.find((secao) => secao.aba === item.abaCodigo)?.nome ?? item.abaCodigo,
                  item.titulo,
                  item.status,
                  item.prazo ? data(item.prazo) : '',
                  item.atrasada ? 'sim' : 'não',
                ]),
              )
            }
          >
            <i className="ri-download-2-line" aria-hidden="true" /> Baixar CSV
          </button>
        ) : undefined
      }
    >
      <Carregando dados={falta} linhas={5} vazio={(itens) => itens.length === 0} textoVazio="Nada faltando: todas as entregas abertas já chegaram.">
        {(itens) => {
          const porMarca = new Map<number, LinhaDaMarca>()
          for (const item of itens) {
            const linha = porMarca.get(item.parceria.id) ?? {
              parceriaId: item.parceria.id,
              marca: item.parceria.patrocinador,
              cota: item.parceria.cota,
              porAba: {},
              total: 0,
              atrasadas: 0,
            }
            ;(linha.porAba[item.abaCodigo] ??= []).push(item)
            linha.total++
            if (item.atrasada) linha.atrasadas++
            porMarca.set(item.parceria.id, linha)
          }
          const linhas = [...porMarca.values()].sort((a, b) => b.atrasadas - a.atrasadas || b.total - a.total)
          return (
            <ul className="flex flex-col divide-y divide-borda">
              {linhas.map((linha, indice) => (
                <li key={linha.parceriaId} className="entrar-em-cascata py-3" style={{ '--ordem': indice } as React.CSSProperties}>
                  <button
                    type="button"
                    aria-expanded={aberta === linha.parceriaId}
                    onClick={() => setAberta(aberta === linha.parceriaId ? null : linha.parceriaId)}
                    className="flex w-full flex-wrap items-center gap-x-4 gap-y-2 text-left"
                  >
                    <span className="w-44 font-semibold">{linha.marca}</span>
                    <span className="flex flex-1 flex-wrap gap-2">
                      {ABAS_COM_ENTREGA.filter((secao) => linha.porAba[secao.aba!]).map((secao) => (
                        <span key={secao.chave} data-secao={secao.chave} className="rounded-full bg-secao-tinta px-2.5 py-1 text-xs font-semibold text-secao-texto">
                          {secao.nome} · {linha.porAba[secao.aba!].length}
                        </span>
                      ))}
                    </span>
                    <span className="text-xs font-semibold text-texto-2">
                      {linha.total} {linha.total === 1 ? 'entrega' : 'entregas'}
                      {linha.atrasadas > 0 && <span className="text-[#c8203f]"> · {linha.atrasadas} com prazo vencido</span>}
                    </span>
                    <i className={`ri-arrow-down-s-line text-lg transition-transform duration-(--duracao-media) ${aberta === linha.parceriaId ? 'rotate-180' : ''}`} aria-hidden="true" />
                  </button>
                  {aberta === linha.parceriaId && (
                    <ul className="mt-3 flex animate-entrar flex-col gap-1.5 pl-2 text-sm">
                      {Object.values(linha.porAba)
                        .flat()
                        .map((item) => (
                          <li key={item.id} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                            <span className="w-32 text-xs font-semibold text-texto-2">{SECOES.find((secao) => secao.aba === item.abaCodigo)?.nome}</span>
                            <span className="flex-1">{item.titulo}</span>
                            <Status codigo={item.status} />
                            <span className={`w-28 text-right text-xs ${item.atrasada ? 'font-bold text-[#c8203f]' : 'text-texto-2'}`}>
                              {item.prazo ? `prazo ${data(item.prazo)}` : 'sem prazo'}
                            </span>
                          </li>
                        ))}
                      <li>
                        <Link to={`/parceria/${linha.parceriaId}`} viewTransition className="text-xs font-bold text-secao-texto hover:underline">
                          Abrir a parceria →
                        </Link>
                      </li>
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )
        }}
      </Carregando>
    </Cartao>
  )
}
