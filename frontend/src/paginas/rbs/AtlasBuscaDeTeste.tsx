// Aba Busca de teste do Atlas na gestão da RBS: uma parceria e uma consulta, e o que a busca nos documentos faria por ela,
// sem o modelo: as duas listas, o RRF e o que a parceria enxerga (rota 61). É o mesmo caminho que o modelo usa. [ATLAS]
import { useState, type FormEvent, type ReactNode } from 'react'
import { ErroApi } from '../../api/cliente'
import { buscarComoTeste, type EscopoDoDocumento, type ItemDaBuscaDeTeste, type ResultadoDaBuscaDeTeste, type TipoDoDocumento } from '../../api/documentos'
import { listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { duracao } from '../../atlas/Diagnostico'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Seletor } from '../../componentes/Formulario'
import { Tabela, type Coluna } from '../../componentes/Tabela'
import { numero } from '../../util/formatar'
import { TIPOS } from './AtlasDocumentos'

const MOTIVO_DO_DEGRADADO: Record<NonNullable<ResultadoDaBuscaDeTeste['motivoDoDegradado']>, string> = {
  EMBEDDINGS_DESLIGADOS: 'Esta API está sem embeddings.',
  EMBEDDING_FALHOU: 'O embedding da consulta falhou.',
}

const NOME_DO_ESCOPO: Record<EscopoDoDocumento, string> = { GLOBAL: 'Toda a edição', COTA: 'A cota', PARCERIA: 'A parceria' }

const nomeDoTipo = (tipo: TipoDoDocumento) => TIPOS.find((item) => item.codigo === tipo)?.nome ?? tipo

const decimal = (valor: number | null, casas: number) => (valor === null ? '—' : valor.toLocaleString('pt-BR', { minimumFractionDigits: casas, maximumFractionDigits: casas }))

/** A posição numa lista e o que ela mediu. O trecho que não entrou nessa lista aparece com o traço. */
function NaLista({ posicao, medida }: { posicao: number | null; medida: string }) {
  if (posicao === null) return <span className="text-texto-2">—</span>
  return (
    <span className="whitespace-nowrap">
      {posicao}º <span className="text-texto-2">· {medida}</span>
    </span>
  )
}

function DoTrecho({ item }: { item: ItemDaBuscaDeTeste }) {
  return (
    <div className="min-w-[220px]">
      <p className="font-semibold">
        {item.documento} <span className="font-medium text-texto-2">· versão {item.versao} · página {item.pagina}</span>
      </p>
      {item.secao && <p className="text-xs font-medium text-texto-2">{item.secao}</p>}
      <p className="mt-1 line-clamp-2 text-xs text-texto-2">{item.inicioDoTexto}</p>
    </div>
  )
}

const COLUNAS_ESCOLHIDOS: Coluna<ItemDaBuscaDeTeste>[] = [
  { titulo: 'Trecho', celula: (item) => <DoTrecho item={item} /> },
  { titulo: 'Vetorial', celula: (item) => <NaLista posicao={item.posicaoVetorial} medida={decimal(item.distancia, 3)} /> },
  { titulo: 'Textual', celula: (item) => <NaLista posicao={item.posicaoTextual} medida={decimal(item.nota, 3)} /> },
  { titulo: 'RRF', celula: (item) => decimal(item.rrf, 4), classe: 'text-right' },
]

function ListaDaBusca({ titulo, itens, coluna, vazio }: { titulo: string; itens: ItemDaBuscaDeTeste[]; coluna: Coluna<ItemDaBuscaDeTeste>; vazio: string }) {
  return (
    <Cartao titulo={titulo} className="min-w-0">
      <Tabela colunas={[{ titulo: 'Trecho', celula: (item) => <DoTrecho item={item} /> }, coluna]} linhas={itens} chave={(item) => item.trechoId} vazio={vazio} />
    </Cartao>
  )
}

function Medida({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  return (
    <p className="text-sm">
      <span className="text-texto-2">{rotulo}:</span> <span className="font-semibold">{children}</span>
    </p>
  )
}

function ResultadoDaBusca({ resultado }: { resultado: ResultadoDaBuscaDeTeste }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Cartao titulo={`${resultado.parceria.patrocinador} · ${resultado.parceria.edicao}`} className="min-w-0">
        <div className="flex flex-wrap gap-x-6 gap-y-1">
          <Medida rotulo="Embedding">{resultado.embeddingMs === null ? '—' : duracao(resultado.embeddingMs)}</Medida>
          <Medida rotulo="Busca no banco">{resultado.sqlMs === null ? '—' : duracao(resultado.sqlMs)}</Medida>
          <Medida rotulo="Trechos escolhidos">{numero(resultado.escolhidos.length)} de até {numero(resultado.k)}</Medida>
        </div>
        {resultado.degradado && resultado.motivoDoDegradado && (
          <Aviso tipo="ausente" titulo="A busca seguiu só com a lista textual" className="mt-3">
            {MOTIVO_DO_DEGRADADO[resultado.motivoDoDegradado]}
            {resultado.erroDoEmbedding && ` (${resultado.erroDoEmbedding})`}
          </Aviso>
        )}
        {resultado.semTermoUtil && (
          <Aviso tipo="vazio" titulo="A consulta só tem palavras vazias" className="mt-3">
            Sem um termo para buscar, as listas ficam vazias. Escreva o assunto da dúvida.
          </Aviso>
        )}
        {resultado.trechosDeOutroModelo > 0 && (
          <Aviso tipo="ausente" titulo={`${numero(resultado.trechosDeOutroModelo)} trechos estão indexados com outro modelo`} className="mt-3">
            Eles ficam fora da lista vetorial até serem reindexados.
          </Aviso>
        )}
      </Cartao>

      <Cartao titulo="O que o modelo receberia" className="min-w-0">
        <Tabela colunas={COLUNAS_ESCOLHIDOS} linhas={resultado.escolhidos} chave={(item) => item.trechoId} vazio="Nenhum trecho para esta consulta." />
      </Cartao>

      <div className="grid grid-cols-[minmax(0,1fr)] gap-4 xl:grid-cols-2">
        <ListaDaBusca
          titulo="Lista vetorial"
          itens={resultado.vetorial}
          vazio="A lista vetorial está vazia."
          coluna={{ titulo: 'Posição', celula: (item) => <NaLista posicao={item.posicaoVetorial} medida={decimal(item.distancia, 3)} /> }}
        />
        <ListaDaBusca
          titulo="Lista textual"
          itens={resultado.textual}
          vazio="A lista textual está vazia."
          coluna={{ titulo: 'Posição', celula: (item) => <NaLista posicao={item.posicaoTextual} medida={decimal(item.nota, 3)} /> }}
        />
      </div>

      <Cartao titulo="O que esta parceria enxerga" className="min-w-0">
        <Tabela
          colunas={[
            { titulo: 'Documento', celula: (item) => <span className="font-semibold">{item.titulo}</span> },
            { titulo: 'Versão', celula: (item) => numero(item.versao), classe: 'text-right' },
            { titulo: 'Tipo', celula: (item) => nomeDoTipo(item.tipo) },
            { titulo: 'Vale para', celula: (item) => NOME_DO_ESCOPO[item.escopo] },
            { titulo: 'Trechos', celula: (item) => numero(item.trechos), classe: 'text-right' },
          ]}
          linhas={resultado.visiveis}
          chave={(item) => item.documentoId}
          vazio="Esta parceria não enxerga nenhum documento."
        />
      </Cartao>
    </div>
  )
}

export function AbaBuscaDeTeste() {
  const parcerias = useDados(() => listarParcerias(), [])
  const [parceriaId, setParceriaId] = useState('')
  const [consulta, setConsulta] = useState('')
  const [tipo, setTipo] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [buscando, setBuscando] = useState(false)
  const [resultado, setResultado] = useState<ResultadoDaBuscaDeTeste | null>(null)

  async function buscar(evento: FormEvent) {
    evento.preventDefault()
    setErros({})
    setFalha(null)
    setBuscando(true)
    try {
      setResultado(await buscarComoTeste({ parceriaId: parceriaId ? Number(parceriaId) : null, consulta, tipo: tipo || undefined }))
    } catch (erro) {
      setResultado(null)
      if (erro instanceof ErroApi && Object.keys(erro.campos).length > 0) setErros(erro.campos)
      else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível buscar.')
    } finally {
      setBuscando(false)
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Cartao className="min-w-0">
        <form onSubmit={buscar} className="flex flex-col gap-4" noValidate>
          <div className="grid gap-4 md:grid-cols-2">
            <Seletor rotulo="Parceria" value={parceriaId} onChange={(evento) => setParceriaId(evento.target.value)} erro={erros.parceriaId}>
              <option value="">Escolha a parceria</option>
              {(parcerias.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.patrocinador.nome} · {item.evento.edicao}
                </option>
              ))}
            </Seletor>
            <Seletor rotulo="Tipo de documento" value={tipo} onChange={(evento) => setTipo(evento.target.value)} erro={erros.tipo}>
              <option value="">Todos</option>
              {TIPOS.map((item) => (
                <option key={item.codigo} value={item.codigo}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
          </div>
          <Campo rotulo="Consulta" value={consulta} onChange={(evento) => setConsulta(evento.target.value)} erro={erros.consulta} ajuda="Como o modelo escreveria: até 300 caracteres." autoComplete="off" />
          {parcerias.erro && <Aviso tipo="falha" titulo={parcerias.erro} />}
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div>
            <Botao type="submit" carregando={buscando}>
              Buscar
            </Botao>
          </div>
        </form>
      </Cartao>
      {resultado && <ResultadoDaBusca resultado={resultado} />}
    </div>
  )
}
