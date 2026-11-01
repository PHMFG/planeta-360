// Atlas na gestão da RBS: os turnos de todas as parcerias, com o mesmo traço da gaveta da marca, o estado dos provedores (rotas 50 a 53), os documentos, a busca de teste e os prazos. [ATLAS]
import { useEffect, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { estadoDosProvedores, listarTurnos, obterTurno, testarProvedor, type EstadoDoProvedor, type NomeDoProvedor, type TesteDoProvedor, type TurnoDaLista, type TurnoDoTraco } from '../../api/diagnostico'
import { listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { dolares, duracao, GavetaDoDiagnostico, SeloDoResultado, TracoDoTurno } from '../../atlas/Diagnostico'
import { LinhaBuscando } from '../../atlas/Elementos'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo } from '../../componentes/Campo'
import { Carregando } from '../../componentes/Carregando'
import { Cartao } from '../../componentes/Cartao'
import { Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { Tabela, type Coluna } from '../../componentes/Tabela'
import { dataHora, numero } from '../../util/formatar'
import { AbaBuscaDeTeste } from './AtlasBuscaDeTeste'
import { AbaConversa } from './AtlasConversa'
import { AbaDocumentos } from './AtlasDocumentos'
import { AbaPrazos } from './AtlasPrazos'

type Aba = 'turnos' | 'provedores' | 'documentos' | 'busca' | 'prazos' | 'conversa'

const ABAS: { codigo: Aba; nome: string }[] = [
  { codigo: 'turnos', nome: 'Turnos' },
  { codigo: 'provedores', nome: 'Provedores' },
  { codigo: 'documentos', nome: 'Documentos' },
  { codigo: 'busca', nome: 'Busca de teste' },
  { codigo: 'prazos', nome: 'Prazos' },
  { codigo: 'conversa', nome: 'Conversa' },
]

const NOME_DO_PROVEDOR: Record<NomeDoProvedor, string> = { llm: 'Modelo de linguagem', jev: 'JEV', gemini: 'Embeddings (Gemini)' }

const ORIGEM: Record<EstadoDoProvedor['origem'], string> = { AMBIENTE: 'Variáveis da API', DEBUG: 'Chave posta pela tela', NENHUM: 'Sem chave' }

const RESULTADOS = [
  { codigo: 'OK', nome: 'OK' },
  { codigo: 'RECUSADO', nome: 'Recusado' },
  { codigo: 'ERRO', nome: 'Erro' },
]

// A página usa o tema claro; o selo vem da gaveta escura, então fica sobre um fundo escuro.
function ResultadoNaTabela({ turno }: { turno: TurnoDaLista }) {
  return (
    <span className="inline-flex rounded-full bg-atlas p-0.5">
      <SeloDoResultado resultado={turno.resultado} />
    </span>
  )
}

function GavetaDoTurno({ turno, aoFechar }: { turno: string; aoFechar: () => void }) {
  const [traco, setTraco] = useState<TurnoDoTraco | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let ativo = true
    setTraco(null)
    setFalha(null)
    obterTurno(turno)
      .then((lido) => ativo && setTraco(lido))
      .catch((erro: unknown) => ativo && setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir o turno.'))
    return () => {
      ativo = false
    }
  }, [turno])

  const titulo = traco?.parceria ? `${traco.parceria} · ${traco.usuario?.nome ?? ''}` : 'Turno do Atlas'
  return (
    <GavetaDoDiagnostico titulo={titulo} aoFechar={aoFechar}>
      {falha ? <p className="text-sm text-[#ff8aa1]" role="alert">{falha}</p> : traco ? <TracoDoTurno turno={traco} /> : <LinhaBuscando texto="Abrindo o turno…" />}
    </GavetaDoDiagnostico>
  )
}

function AbaTurnos({ aoAbrir }: { aoAbrir: (turno: string) => void }) {
  const parcerias = useDados(() => listarParcerias(), [])
  const [parceriaId, setParceriaId] = useState('')
  const [resultado, setResultado] = useState('')
  const [de, setDe] = useState('')
  const [ate, setAte] = useState('')
  const [pagina, setPagina] = useState(0)
  const turnos = useDados(
    () => listarTurnos({ parceriaId: parceriaId ? Number(parceriaId) : undefined, resultado: resultado || undefined, de: de || undefined, ate: ate || undefined, pagina }),
    [parceriaId, resultado, de, ate, pagina],
  )
  const filtrar = (definir: (valor: string) => void) => (valor: string) => {
    definir(valor)
    setPagina(0)
  }

  const colunas: Coluna<TurnoDaLista>[] = [
    {
      titulo: 'Data',
      celula: (turno) => (
        <button type="button" onClick={() => aoAbrir(turno.turno)} className="text-left font-semibold whitespace-nowrap underline underline-offset-2">
          {dataHora(turno.iniciadoEm)}
        </button>
      ),
    },
    { titulo: 'Parceria', celula: (turno) => turno.parceria },
    { titulo: 'Usuário', celula: (turno) => turno.usuario.nome },
    { titulo: 'Etapas', celula: (turno) => numero(turno.etapas), classe: 'text-right' },
    { titulo: 'Duração', celula: (turno) => duracao(turno.duracaoMs), classe: 'text-right whitespace-nowrap' },
    { titulo: 'Resultado', celula: (turno) => <ResultadoNaTabela turno={turno} /> },
    {
      titulo: 'Tokens',
      celula: (turno) => (
        <span className="whitespace-nowrap">
          {numero(turno.tokensEntrada + turno.tokensSaida)} <span className="text-texto-2">· {dolares(turno.custoUsd)}</span>
        </span>
      ),
      classe: 'text-right',
    },
  ]

  return (
    <Cartao>
      <div className="mb-4 grid gap-4 md:grid-cols-4">
        <Seletor rotulo="Parceria" value={parceriaId} onChange={(evento) => filtrar(setParceriaId)(evento.target.value)}>
          <option value="">Todas</option>
          {(parcerias.dado ?? []).map((item) => (
            <option key={item.id} value={item.id}>
              {item.patrocinador.nome} · {item.evento.edicao}
            </option>
          ))}
        </Seletor>
        <Seletor rotulo="Resultado" value={resultado} onChange={(evento) => filtrar(setResultado)(evento.target.value)}>
          <option value="">Todos</option>
          {RESULTADOS.map((item) => (
            <option key={item.codigo} value={item.codigo}>
              {item.nome}
            </option>
          ))}
        </Seletor>
        <Campo rotulo="De" type="date" value={de} onChange={(evento) => filtrar(setDe)(evento.target.value)} />
        <Campo rotulo="Até" type="date" value={ate} onChange={(evento) => filtrar(setAte)(evento.target.value)} />
      </div>
      {turnos.erroStatus === 404 ? (
        <Aviso tipo="restrito" titulo="O diagnóstico está desligado nesta API">Ele liga com ATLAS_DIAGNOSTICO=true, e já vem ligado no perfil de desenvolvimento.</Aviso>
      ) : (
        <Carregando dados={turnos} vazio={(dado) => dado.itens.length === 0} textoVazio="Nenhum turno com esses filtros.">
          {(dado) => (
            <>
              <Tabela colunas={colunas} linhas={dado.itens} chave={(turno) => turno.turno} aoClicar={(turno) => aoAbrir(turno.turno)} />
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      )}
    </Cartao>
  )
}

function LinhaDoProvedor({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-4 border-b border-borda py-1.5 text-sm last:border-b-0">
      <dt className="text-texto-2">{rotulo}</dt>
      <dd className="min-w-0 text-right font-medium [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

function CartaoDoProvedor({ provedor }: { provedor: EstadoDoProvedor }) {
  const [testando, setTestando] = useState(false)
  const [teste, setTeste] = useState<TesteDoProvedor | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  async function testar() {
    setTestando(true)
    setTeste(null)
    setFalha(null)
    try {
      setTeste(await testarProvedor(provedor.nome))
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível testar.')
    } finally {
      setTestando(false)
    }
  }

  return (
    <Cartao titulo={NOME_DO_PROVEDOR[provedor.nome]} className="min-w-0">
      <dl className="mb-4">
        <LinhaDoProvedor rotulo="Situação">{provedor.configurado ? 'Configurado' : 'Não configurado'}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Origem">{ORIGEM[provedor.origem]}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Modelo">{provedor.modelo ?? '—'}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Endereço">{provedor.baseUrl ?? '—'}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Fim da chave">{provedor.finalDaChave ? `…${provedor.finalDaChave}` : '—'}</LinhaDoProvedor>
        {provedor.modo && <LinhaDoProvedor rotulo="Modo">{provedor.modo}</LinhaDoProvedor>}
        {provedor.pontos?.map((ponto) => (
          <LinhaDoProvedor key={ponto.ponto} rotulo={`Ponto ${ponto.ponto}`}>
            {ponto.modo} · limiar {ponto.limiar}
          </LinhaDoProvedor>
        ))}
        <LinhaDoProvedor rotulo="Chamadas em 24 h">{numero(provedor.chamadas24h)}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Erros em 24 h">{numero(provedor.erros24h)}</LinhaDoProvedor>
        <LinhaDoProvedor rotulo="Latência p50 e p95">
          {provedor.latenciaP50Ms === null ? '—' : `${duracao(provedor.latenciaP50Ms)} · ${duracao(provedor.latenciaP95Ms ?? provedor.latenciaP50Ms)}`}
        </LinhaDoProvedor>
        {provedor.disjuntor && <LinhaDoProvedor rotulo="Disjuntor">{provedor.disjuntor}</LinhaDoProvedor>}
      </dl>
      <Botao type="button" variante="secundario" carregando={testando} onClick={testar}>
        Testar conexão
      </Botao>
      {teste?.ok && <p role="status" className="mt-3 text-sm">Respondeu{teste.milissegundos === null ? '' : ` em ${duracao(teste.milissegundos)}`}.</p>}
      {teste && !teste.ok && <Aviso tipo={teste.chamou ? 'falha' : 'info'} titulo={teste.erro ?? 'Não respondeu.'} className="mt-3" />}
      {falha && <Aviso tipo="falha" titulo={falha} className="mt-3" />}
    </Cartao>
  )
}

function AbaProvedores() {
  const estado = useDados(() => estadoDosProvedores(), [])
  if (estado.erroStatus === 404) {
    return <Aviso tipo="restrito" titulo="O diagnóstico está desligado nesta API">Ele liga com ATLAS_DIAGNOSTICO=true, e já vem ligado no perfil de desenvolvimento.</Aviso>
  }
  return (
    <Carregando dados={estado}>
      {(dado) => (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-3">
            {dado.provedores.map((provedor) => <CartaoDoProvedor key={provedor.nome} provedor={provedor} />)}
          </div>
          <Cartao titulo={`Teto diário: ${numero(dado.tetoDiario.limite)} mensagens por parceria`}>
            <Tabela
              colunas={[
                { titulo: 'Parceria', celula: (linha) => linha.parceria },
                { titulo: 'Usadas hoje', celula: (linha) => numero(linha.usadas), classe: 'text-right' },
              ]}
              linhas={dado.tetoDiario.parcerias}
              chave={(linha) => linha.parceriaId}
              vazio="Nenhuma mensagem do Atlas hoje."
            />
          </Cartao>
        </div>
      )}
    </Carregando>
  )
}

export function AtlasRbs() {
  const [busca, setBusca] = useSearchParams()
  const aba: Aba = ABAS.find((item) => item.codigo === busca.get('aba'))?.codigo ?? 'turnos'
  const turno = busca.get('turno')

  function mudar(campo: 'aba' | 'turno', valor: string | null) {
    setBusca((atual) => {
      const nova = new URLSearchParams(atual)
      if (valor) nova.set(campo, valor)
      else nova.delete(campo)
      return nova
    })
  }

  return (
    <div data-secao="neutro" className="flex min-w-0 flex-col gap-4">
      <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo="Atlas" subtitulo="O que o assistente fez em cada turno, em todas as parcerias, o estado dos provedores e os documentos de que ele se alimenta." />
      <div className="flex flex-wrap gap-2" role="tablist" aria-label="Atlas na gestão">
        {ABAS.map((item) => (
          <button
            key={item.codigo}
            type="button"
            role="tab"
            aria-selected={aba === item.codigo}
            onClick={() => mudar('aba', item.codigo)}
            className={`h-10 rounded-full border px-4 text-sm font-semibold ${aba === item.codigo ? 'border-tinta bg-tinta text-white' : 'border-borda bg-white hover:bg-cinza-100'}`}
          >
            {item.nome}
          </button>
        ))}
      </div>
      {aba === 'turnos' && <AbaTurnos aoAbrir={(valor) => mudar('turno', valor)} />}
      {aba === 'provedores' && <AbaProvedores />}
      {aba === 'documentos' && <AbaDocumentos />}
      {aba === 'conversa' && <AbaConversa />}
      {aba === 'busca' && <AbaBuscaDeTeste />}
      {aba === 'prazos' && <AbaPrazos />}
      {turno && <GavetaDoTurno turno={turno} aoFechar={() => mudar('turno', null)} />}
    </div>
  )
}
