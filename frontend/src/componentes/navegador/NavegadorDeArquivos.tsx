// O navegador de arquivos da Mídia (pedido do ph, 06/11): no lugar da grade de cartões soltos, uma pasta por entrega, mais as
// pastas "Publicados pela RBS" e "Comprovantes de veiculação". Dentro da pasta, a versão atual de cada arquivo com a miniatura;
// as anteriores ficam em "Versões anteriores". Clicar abre o arquivo na tela; arrastar um arquivo para a pasta de uma entrega
// aberta já envia para ela (só a marca). Só usa as rotas que já existem: as entregas, os comprovantes da base e o download.
import { lazy, Suspense, useMemo, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { ACEITA_ENVIO, EXTENSOES_ACEITAS, baixarArquivo, type Arquivo, type Entrega } from '../../api/materiais'
import { normalizar } from '../../util/texto'
import { data, dataHora, tamanho } from '../../util/formatar'
import { Aviso } from '../Aviso'
import { Botao } from '../Botao'
import { Busca } from '../Formulario'
import { Modal } from '../Modal'
import { Status } from '../Status'
import { Miniatura, iconeDoTipo } from './Miniatura'

const VisualizadorDePdf = lazy(() => import('../../paginas/parceria/VisualizadorDeContrato'))
const VisualizadorDeImagemOuVideo = lazy(() => import('./VisualizadorDeMidia'))

/** Um arquivo como o navegador mostra: o da entrega ou o comprovante de uma veiculação. */
export type ItemDoNavegador = {
  id: number
  nome: string
  mime: string
  tamanhoBytes?: number
  versao?: number
  autor?: string
  daRbs?: boolean
  enviadoEm?: string
  observacao?: string | null
  entrega?: Entrega
  legenda?: string
}

export type Comprovante = { arquivoId: number; nome: string; tipo: string; legenda: string }

type Pasta = { chave: string; nome: string; icone: string; atuais: ItemDoNavegador[]; anteriores: ItemDoNavegador[]; entrega?: Entrega; legenda?: string }

type Props = {
  entregas: Entrega[]
  comprovantes: Comprovante[]
  /** A marca envia arrastando para a pasta; a RBS publica pelo detalhe da entrega. */
  podeEnviar: boolean
  aoEnviar: (arquivo: File, entrega: Entrega) => Promise<void>
  aoAbrirEntrega: (entrega: Entrega) => void
  acoes?: ReactNode
}

const MIME_DO_TIPO: Record<string, string> = { PNG: 'image/png', WEBP: 'image/webp', GIF: 'image/gif', JPG: 'image/jpeg', JPEG: 'image/jpeg', PDF: 'application/pdf', MP4: 'video/mp4' }

function itemDoArquivo(arquivo: Arquivo, entrega: Entrega): ItemDoNavegador {
  return {
    id: arquivo.id,
    nome: arquivo.nomeOriginal,
    mime: arquivo.mimeType,
    tamanhoBytes: arquivo.tamanhoBytes,
    versao: arquivo.versao,
    autor: arquivo.enviadoPor?.nome,
    daRbs: arquivo.enviadoPor?.perfil === 'ADMIN_RBS',
    enviadoEm: arquivo.enviadoEm,
    observacao: arquivo.observacao,
    entrega,
  }
}

/** As pastas: uma por entrega (a versão mais nova da marca e os retornos da RBS à vista; as versões antigas guardadas), e as duas fixas. */
function montarPastas(entregas: Entrega[], comprovantes: Comprovante[]): Pasta[] {
  const pastas: Pasta[] = entregas.map((entrega) => {
    const itens = entrega.arquivos.map((arquivo) => itemDoArquivo(arquivo, entrega))
    const daMarca = itens.filter((item) => !item.daRbs)
    const maisNova = Math.max(0, ...daMarca.map((item) => item.versao ?? 0))
    return {
      chave: `e${entrega.id}`,
      nome: entrega.titulo,
      icone: 'ri-folder-3-fill',
      entrega,
      atuais: itens.filter((item) => item.daRbs || item.versao === maisNova),
      anteriores: daMarca.filter((item) => item.versao !== maisNova),
    }
  })
  const daRbs = entregas.flatMap((entrega) => entrega.arquivos.filter((arquivo) => arquivo.enviadoPor?.perfil === 'ADMIN_RBS').map((arquivo) => itemDoArquivo(arquivo, entrega)))
  if (daRbs.length) pastas.push({ chave: 'rbs', nome: 'Publicados pela RBS', icone: 'ri-folder-shield-2-fill', atuais: daRbs, anteriores: [], legenda: 'Retornos e relatórios da RBS' })
  if (comprovantes.length)
    pastas.push({
      chave: 'comprovantes',
      nome: 'Comprovantes de veiculação',
      icone: 'ri-folder-image-fill',
      atuais: comprovantes.map((item) => ({ id: item.arquivoId, nome: item.nome, mime: MIME_DO_TIPO[item.tipo.toUpperCase()] ?? '', legenda: item.legenda })),
      anteriores: [],
      legenda: 'Os prints e PDFs que comprovam a mídia',
    })
  return pastas
}

function ordenar(itens: ItemDoNavegador[], ordem: 'data' | 'nome') {
  return [...itens].sort((a, b) => (ordem === 'nome' ? a.nome.localeCompare(b.nome, 'pt-BR') : (b.enviadoEm ?? '').localeCompare(a.enviadoEm ?? '')))
}

function metaDo(item: ItemDoNavegador) {
  return [item.versao ? `v${item.versao}` : null, item.autor, item.enviadoEm ? dataHora(item.enviadoEm) : item.legenda].filter(Boolean).join(' · ')
}

/** Aceita arrastar um arquivo do computador para cima: devolve os manipuladores e se há um arquivo por cima agora. */
function usarSoltar(ativo: boolean, aoSoltar: (arquivo: File) => void) {
  const [porCima, setPorCima] = useState(false)
  const temArquivo = (evento: DragEvent) => Array.from(evento.dataTransfer.types).includes('Files')
  return {
    porCima,
    manipuladores: ativo
      ? {
          onDragOver: (evento: DragEvent) => {
            if (!temArquivo(evento)) return
            evento.preventDefault()
            setPorCima(true)
          },
          onDragLeave: () => setPorCima(false),
          onDrop: (evento: DragEvent) => {
            evento.preventDefault()
            setPorCima(false)
            const arquivo = evento.dataTransfer.files[0]
            if (arquivo) aoSoltar(arquivo)
          },
        }
      : {},
  }
}

function CartaoDaPasta({ pasta, podeEnviar, aoAbrir, aoEnviar }: { pasta: Pasta; podeEnviar: boolean; aoAbrir: () => void; aoEnviar: (arquivo: File) => void }) {
  const aceita = podeEnviar && Boolean(pasta.entrega && ACEITA_ENVIO.includes(pasta.entrega.status))
  const { porCima, manipuladores } = usarSoltar(aceita, aoEnviar)
  const capa = ordenar(pasta.atuais, 'data')[0]
  const total = pasta.atuais.length + pasta.anteriores.length
  return (
    <li {...manipuladores}>
      <button
        type="button"
        onClick={aoAbrir}
        aria-label={`Abrir a pasta ${pasta.nome}`}
        className={`group flex w-full flex-col overflow-hidden rounded-2xl border bg-white text-left transition-[border-color,box-shadow] duration-(--duracao-curta) hover:border-secao hover:shadow-sm ${porCima ? 'border-secao ring-2 ring-secao/40' : 'border-borda'}`}
      >
        <span className="relative grid h-28 place-items-center bg-secao-tinta">
          {capa ? (
            <Miniatura item={capa} className="absolute inset-0" />
          ) : (
            <i className={`${pasta.icone} text-5xl text-secao/70`} aria-hidden="true" />
          )}
          <span className="absolute top-2 left-2 grid size-8 place-items-center rounded-lg bg-white/90 shadow-sm">
            <i className={`${pasta.icone} text-lg text-secao`} aria-hidden="true" />
          </span>
          {porCima && <span className="absolute inset-0 grid place-items-center bg-secao/80 text-sm font-bold text-white">Soltar para enviar</span>}
        </span>
        <span className="flex flex-col gap-1 p-3">
          <span className="truncate text-sm font-semibold">{pasta.nome}</span>
          <span className="text-xs font-medium text-texto-2">
            {total === 0 ? 'Vazia' : `${total} ${total === 1 ? 'arquivo' : 'arquivos'}`}
            {pasta.entrega?.prazo && ` · prazo ${data(pasta.entrega.prazo)}`}
            {!pasta.entrega && pasta.legenda && ` · ${pasta.legenda}`}
          </span>
          {pasta.entrega && <Status codigo={pasta.entrega.status} />}
        </span>
      </button>
    </li>
  )
}

function CartaoDoArquivo({ item, vista, aoAbrir }: { item: ItemDoNavegador; vista: 'grade' | 'lista'; aoAbrir: () => void }) {
  const selo = item.daRbs ? <span className="rounded-full bg-tinta px-2 py-0.5 text-[10px] font-bold tracking-wide text-white uppercase">RBS</span> : null
  if (vista === 'lista')
    return (
      <li>
        <button type="button" onClick={aoAbrir} className="flex w-full items-center gap-3 rounded-xl border border-borda bg-white p-2 text-left hover:border-secao">
          <span className="relative size-12 shrink-0 overflow-hidden rounded-lg bg-secao-tinta">
            <Miniatura item={item} className="absolute inset-0" />
          </span>
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="flex items-center gap-2">
              <span className="truncate text-sm font-semibold">{item.nome}</span>
              {selo}
            </span>
            <span className="truncate text-xs text-texto-2">{metaDo(item)}</span>
          </span>
          {item.tamanhoBytes ? <span className="shrink-0 text-xs text-texto-2 max-sm:hidden">{tamanho(item.tamanhoBytes)}</span> : null}
        </button>
      </li>
    )
  return (
    <li>
      <button
        type="button"
        onClick={aoAbrir}
        aria-label={`Abrir ${item.nome}`}
        className="group flex w-full flex-col overflow-hidden rounded-2xl border border-borda bg-white text-left transition-[border-color,box-shadow] duration-(--duracao-curta) hover:border-secao hover:shadow-sm"
      >
        <span className="relative block aspect-[4/3] bg-secao-tinta">
          <Miniatura item={item} className="absolute inset-0" />
          {selo && <span className="absolute top-2 right-2">{selo}</span>}
        </span>
        <span className="flex flex-col gap-0.5 p-3">
          <span className="truncate text-sm font-semibold">{item.nome}</span>
          <span className="truncate text-xs text-texto-2">{metaDo(item)}</span>
        </span>
      </button>
    </li>
  )
}

/** O arquivo aberto na tela: imagem, PDF, vídeo ou áudio; o resto, só o download. */
function Visualizacao({ item, aoFechar, aoAbrirEntrega }: { item: ItemDoNavegador; aoFechar: () => void; aoAbrirEntrega: (entrega: Entrega) => void }) {
  const [baixando, setBaixando] = useState(false)
  const tipo = item.mime
  const corpo = tipo === 'application/pdf' ? (
    <VisualizadorDePdf arquivoId={item.id} nome={item.nome} />
  ) : tipo.startsWith('image/') || tipo.startsWith('video/') || tipo.startsWith('audio/') ? (
    <VisualizadorDeImagemOuVideo arquivoId={item.id} nome={item.nome} mime={tipo} />
  ) : (
    <div className="flex flex-col items-center gap-3 rounded-2xl bg-secao-tinta px-6 py-10 text-center">
      <i className={`${iconeDoTipo(item.mime, item.nome)} text-5xl text-secao`} aria-hidden="true" />
      <p className="text-sm text-texto-2">Este tipo de arquivo não abre na tela. Use “Baixar”.</p>
    </div>
  )
  return (
    <Modal titulo={item.nome} legenda={metaDo(item)} aberto aoFechar={aoFechar} largura="max-w-[880px]">
      <div className="flex flex-col gap-4">
        <Suspense fallback={<div className="esqueleto h-72 rounded-2xl" role="status"><span className="sr-only">Carregando…</span></div>}>{corpo}</Suspense>
        {item.observacao && <p className="text-sm"><span className="font-semibold">Observação:</span> {item.observacao}</p>}
        <div className="flex flex-wrap gap-3">
          <Botao
            icone="ri-download-2-line"
            carregando={baixando}
            onClick={async () => {
              setBaixando(true)
              try {
                await baixarArquivo({ id: item.id, nomeOriginal: item.nome })
              } finally {
                setBaixando(false)
              }
            }}
          >
            Baixar
          </Botao>
          {item.entrega && (
            <Botao variante="secundario" onClick={() => aoAbrirEntrega(item.entrega!)}>
              Ver a entrega e o histórico
            </Botao>
          )}
        </div>
      </div>
    </Modal>
  )
}

export function NavegadorDeArquivos({ entregas, comprovantes, podeEnviar, aoEnviar, aoAbrirEntrega, acoes }: Props) {
  const pastas = useMemo(() => montarPastas(entregas, comprovantes), [entregas, comprovantes])
  const [chave, setChave] = useState<string | null>(null)
  const [anteriores, setAnteriores] = useState(false)
  const [vista, setVista] = useState<'grade' | 'lista'>('grade')
  const [ordem, setOrdem] = useState<'data' | 'nome'>('data')
  const [busca, setBusca] = useState('')
  const [aberto, setAberto] = useState<ItemDoNavegador | null>(null)
  const [enviando, setEnviando] = useState(false)
  const entradaRef = useRef<HTMLInputElement>(null)
  const pasta = pastas.find((item) => item.chave === chave) ?? null
  const termo = normalizar(busca)
  const aceitaNaPasta = podeEnviar && Boolean(pasta?.entrega && ACEITA_ENVIO.includes(pasta.entrega.status))

  async function enviar(arquivo: File, entrega: Entrega) {
    setEnviando(true)
    try {
      await aoEnviar(arquivo, entrega)
    } finally {
      setEnviando(false)
    }
  }
  const { porCima, manipuladores } = usarSoltar(aceitaNaPasta && !anteriores, (arquivo) => pasta?.entrega && void enviar(arquivo, pasta.entrega))

  // Com busca, os arquivos de todas as pastas numa lista só (as versões anteriores também), pelo nome do arquivo ou da entrega.
  const resultados = termo
    ? ordenar(
        pastas.filter((item) => item.chave !== 'rbs').flatMap((item) => [...item.atuais, ...item.anteriores]).filter(
          (item) => normalizar(item.nome).includes(termo) || normalizar(item.entrega?.titulo ?? '').includes(termo),
        ),
        ordem,
      )
    : null
  const itens = pasta ? ordenar(anteriores ? pasta.anteriores : pasta.atuais, ordem) : []

  function irPara(nova: string | null) {
    setChave(nova)
    setAnteriores(false)
  }

  const botaoDaVista = (valor: 'grade' | 'lista', icone: string, nome: string) => (
    <button
      type="button"
      aria-pressed={vista === valor}
      aria-label={nome}
      title={nome}
      onClick={() => setVista(valor)}
      className={`grid size-9 place-items-center rounded-lg ${vista === valor ? 'bg-tinta text-white' : 'text-texto-2 hover:bg-cinza-100'}`}
    >
      <i className={`${icone} text-lg`} aria-hidden="true" />
    </button>
  )

  const listaDeArquivos = (lista: ItemDoNavegador[]) => (
    <ul className={vista === 'grade' ? 'grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4' : 'flex flex-col gap-2'}>
      {lista.map((item, indice) => (
        <div key={`${item.id}-${indice}`} className="entrar-em-cascata contents" style={{ '--ordem': indice } as React.CSSProperties}>
          <CartaoDoArquivo item={item} vista={vista} aoAbrir={() => setAberto(item)} />
        </div>
      ))}
    </ul>
  )

  return (
    <section className="rounded-[20px] border border-borda bg-white p-6 max-sm:p-4" aria-label="Arquivos">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        {/* O caminho: Arquivos › pasta › Versões anteriores. Cada parte volta para ela. */}
        <nav aria-label="Caminho" className="flex min-w-0 flex-wrap items-center gap-1 text-sm">
          <button type="button" onClick={() => irPara(null)} className={`texto-titulo ${pasta ? 'text-texto-2 hover:text-tinta' : ''}`}>
            Arquivos
          </button>
          {pasta && (
            <>
              <i className="ri-arrow-right-s-line text-texto-2" aria-hidden="true" />
              <button type="button" onClick={() => setAnteriores(false)} className={`truncate font-semibold ${anteriores ? 'text-texto-2 hover:text-tinta' : ''}`}>
                {pasta.nome}
              </button>
            </>
          )}
          {anteriores && (
            <>
              <i className="ri-arrow-right-s-line text-texto-2" aria-hidden="true" />
              <span className="font-semibold">Versões anteriores</span>
            </>
          )}
        </nav>
        <div className="flex items-center gap-1">
          {acoes}
          <select
            aria-label="Ordem"
            value={ordem}
            onChange={(evento) => setOrdem(evento.target.value as 'data' | 'nome')}
            className="h-9 rounded-lg border border-borda bg-white px-2 text-xs font-semibold"
          >
            <option value="data">Mais novos</option>
            <option value="nome">Nome</option>
          </select>
          {botaoDaVista('grade', 'ri-layout-grid-line', 'Ver em grade')}
          {botaoDaVista('lista', 'ri-list-check', 'Ver em lista')}
        </div>
      </div>
      <div className="mb-4">
        <Busca valor={busca} aoMudar={setBusca} placeholder="Buscar por nome do arquivo ou entrega…" />
      </div>

      {resultados ? (
        resultados.length === 0 ? (
          <Aviso tipo="vazio" titulo="Nenhum arquivo encontrado com essa busca" />
        ) : (
          listaDeArquivos(resultados)
        )
      ) : !pasta ? (
        pastas.length === 0 ? (
          <Aviso tipo="vazio" titulo="Nenhuma pasta ainda">
            Cada entrega de mídia que a RBS abre vira uma pasta aqui.
          </Aviso>
        ) : (
          <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {pastas.map((item, indice) => (
              <div key={item.chave} className="entrar-em-cascata contents" style={{ '--ordem': indice } as React.CSSProperties}>
                <CartaoDaPasta pasta={item} podeEnviar={podeEnviar} aoAbrir={() => irPara(item.chave)} aoEnviar={(arquivo) => item.entrega && void enviar(arquivo, item.entrega)} />
              </div>
            ))}
          </ul>
        )
      ) : (
        <div
          {...manipuladores}
          className={`relative flex flex-col gap-4 rounded-2xl transition-[box-shadow] duration-(--duracao-curta) ${porCima ? 'ring-2 ring-secao ring-offset-4' : ''}`}
        >
          {pasta.entrega && !anteriores && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl bg-secao-tinta px-4 py-3 text-sm">
              <Status codigo={pasta.entrega.status} />
              {pasta.entrega.prazo && <span className="text-texto-2">prazo {data(pasta.entrega.prazo)}</span>}
              <span className="flex-1" />
              {aceitaNaPasta && (
                <>
                  <span className="text-xs text-texto-2 max-sm:hidden">Arraste um arquivo para cá ou</span>
                  <Botao className="min-w-0 px-4" icone="ri-upload-cloud-2-line" carregando={enviando} onClick={() => entradaRef.current?.click()}>
                    Enviar
                  </Botao>
                  <input
                    ref={entradaRef}
                    type="file"
                    className="hidden"
                    accept={EXTENSOES_ACEITAS}
                    aria-label={`Enviar arquivo para ${pasta.nome}`}
                    onChange={(evento) => {
                      const arquivo = evento.target.files?.[0]
                      if (arquivo && pasta.entrega) void enviar(arquivo, pasta.entrega)
                      evento.target.value = ''
                    }}
                  />
                </>
              )}
              <Botao variante="secundario" className="min-w-0 px-4" onClick={() => aoAbrirEntrega(pasta.entrega!)}>
                Detalhe e histórico
              </Botao>
            </div>
          )}
          {itens.length === 0 && (!pasta.anteriores.length || anteriores) ? (
            <Aviso tipo="vazio" titulo="Nenhum arquivo nesta pasta ainda" />
          ) : (
            <>
              {!anteriores && pasta.anteriores.length > 0 && (
                <ul className={vista === 'grade' ? 'grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4' : 'flex flex-col gap-2'}>
                  <li>
                    <button
                      type="button"
                      onClick={() => setAnteriores(true)}
                      className={`flex w-full items-center gap-3 rounded-2xl border border-dashed border-borda bg-white text-left hover:border-secao ${vista === 'grade' ? 'h-full flex-col justify-center p-4' : 'p-3'}`}
                    >
                      <i className="ri-folder-history-line text-3xl text-texto-2" aria-hidden="true" />
                      <span className="text-sm font-semibold">
                        Versões anteriores <span className="font-medium text-texto-2">({pasta.anteriores.length})</span>
                      </span>
                    </button>
                  </li>
                </ul>
              )}
              {itens.length > 0 && listaDeArquivos(itens)}
            </>
          )}
          {porCima && <p className="text-center text-sm font-bold text-secao-texto">Soltar para enviar a “{pasta.nome}”</p>}
        </div>
      )}

      {aberto && <Visualizacao item={aberto} aoFechar={() => setAberto(null)} aoAbrirEntrega={(entrega) => { setAberto(null); aoAbrirEntrega(entrega) }} />}
    </section>
  )
}
