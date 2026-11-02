// O visualizador da fonte de uma resposta do Atlas: o documento abre ao lado da conversa, na página citada, com o trecho
// destacado. No celular, ocupa a tela. Carrega só ao abrir a primeira fonte, porque o leitor de PDF é pesado. [ATLAS]
// O pdf.js 6.3 não usa eval nem new Function. A camada de anotações fica desligada: nada do PDF vira link nem formulário, e o
// texto do arquivo só entra na tela escapado (destaque.ts).
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import type { CustomTextRenderer } from 'react-pdf/dist/shared/types.js'
import 'react-pdf/dist/Page/TextLayer.css'
import urlDoWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import type { FonteDaResposta } from '../api/atlas'
import { ErroApi } from '../api/cliente'
import { lerConteudoDoDocumento, obterTrechoInteiro, type TrechoInteiro } from '../api/documentos'
import { usarFocoPreso } from '../componentes/usarFocoPreso'
import { faixasDoTrecho, htmlDoItem, partesDoTexto } from './destaque'
import { AvisoAtlas, Chip, LinhaBuscando, PainelLateral } from './Elementos'

pdfjs.GlobalWorkerOptions.workerSrc = urlDoWorker

const LARGO = '(min-width: 1024px)'

function assinarLargura(avisar: () => void) {
  const midia = window.matchMedia(LARGO)
  midia.addEventListener('change', avisar)
  return () => midia.removeEventListener('change', avisar)
}

/** O trecho é o citado na fonte aberta: outro chip do mesmo documento troca o trecho sem baixar o arquivo de novo. */
function usarTrecho(fonte: FonteDaResposta) {
  const [trecho, setTrecho] = useState<TrechoInteiro | null>(null)
  const { documentoId, trechoId } = fonte
  useEffect(() => {
    if (documentoId === undefined || trechoId === undefined) return
    let ativo = true
    setTrecho(null)
    obterTrechoInteiro(documentoId, trechoId)
      .then((lido) => ativo && setTrecho(lido))
      .catch(() => undefined)
    return () => {
      ativo = false
    }
  }, [documentoId, trechoId])
  return trecho
}

/** Sem a marca na página, a pessoa ainda lê o que foi citado; com ela, o texto fica guardado aqui. */
function TrechoCitado({ trecho, aberto }: { trecho: TrechoInteiro; aberto: boolean }) {
  return (
    <details open={aberto} className="shrink-0 text-[13px]">
      <summary className="cursor-pointer font-semibold text-atlas-lilas">Ler o trecho citado</summary>
      <p className="mt-1.5 max-h-32 overflow-y-auto rounded-lg bg-black/25 p-2.5 leading-[1.5] whitespace-pre-wrap text-white/85">{trecho.texto}</p>
    </details>
  )
}

function DocumentoEmPdf({ arquivo, fonte, trecho }: { arquivo: Blob; fonte: FonteDaResposta; trecho: TrechoInteiro | null }) {
  const area = useRef<HTMLDivElement>(null)
  const [largura, setLargura] = useState(0)
  const [total, setTotal] = useState<number | null>(null)
  const [pagina, setPagina] = useState(fonte.pagina ?? 1)
  const [textoDaPagina, setTextoDaPagina] = useState<{ pagina: number; itens: string[] } | null>(null)

  useEffect(() => setPagina(fonte.pagina ?? 1), [fonte])

  useEffect(() => {
    const elemento = area.current
    if (!elemento) return
    const medir = () => setLargura(elemento.clientWidth)
    medir()
    const observador = new ResizeObserver(medir)
    observador.observe(elemento)
    return () => observador.disconnect()
  }, [])

  // Só nas páginas do trecho: a mesma frase em outra página não é a citada.
  const faixas = useMemo(() => {
    if (!trecho || textoDaPagina?.pagina !== pagina || pagina < trecho.paginaInicio || pagina > trecho.paginaFim) return null
    return faixasDoTrecho(textoDaPagina.itens, trecho.texto)
  }, [trecho, textoDaPagina, pagina])

  const renderizar = useCallback<CustomTextRenderer>(({ str, itemIndex }) => htmlDoItem(str, faixas?.get(itemIndex)), [faixas])

  function rolarAteAMarca() {
    const caixa = area.current
    const marca = caixa?.querySelector('mark')
    if (!caixa || !marca) return
    caixa.scrollTop += marca.getBoundingClientRect().top - caixa.getBoundingClientRect().top - caixa.clientHeight / 3
  }

  const naoMarcou = faixas !== null && faixas.size === 0
  return (
    <>
      {trecho && <TrechoCitado trecho={trecho} aberto={naoMarcou} />}
      {naoMarcou && <p className="shrink-0 text-xs text-white/64">Nesta página não deu para marcar o trecho no arquivo.</p>}
      <div ref={area} className="min-h-0 flex-1 overflow-auto rounded-[12px] bg-black/25" data-testid="pagina-do-documento">
        {/* Sem o Suspense do react-pdf: o documento carrega dentro do painel, sem desmontar a conversa nem o próprio painel. */}
        <Document
          file={arquivo}
          suspense={false}
          onLoadSuccess={(documento) => setTotal(documento.numPages)}
          loading={<div className="p-4"><LinhaBuscando texto="Abrindo o documento…" /></div>}
          error={<div className="p-4"><AvisoAtlas tipo="falha" titulo="Não foi possível abrir este arquivo." /></div>}
          noData={null}
        >
          {largura > 0 && (
            <Page
              pageNumber={pagina}
              width={largura}
              renderAnnotationLayer={false}
              customTextRenderer={renderizar}
              onGetTextSuccess={(conteudo) => setTextoDaPagina({ pagina, itens: conteudo.items.map((item) => ('str' in item ? item.str : '')) })}
              onRenderTextLayerSuccess={rolarAteAMarca}
              loading={<div className="p-4"><LinhaBuscando texto="Abrindo a página…" /></div>}
              error={<div className="p-4"><AvisoAtlas tipo="falha" titulo="Não foi possível abrir esta página." /></div>}
              noData={null}
            />
          )}
        </Document>
      </div>
      {total !== null && (
        <div className="flex shrink-0 items-center justify-between gap-2">
          <Chip icone="ri-arrow-left-s-line" disabled={pagina <= 1} onClick={() => setPagina(pagina - 1)} aria-label="Página anterior">
            Anterior
          </Chip>
          <p className="text-[13px] font-medium text-white/80 tabular-nums" role="status">
            Página {pagina} de {total}
          </p>
          <Chip icone="ri-arrow-right-s-line" disabled={pagina >= total} onClick={() => setPagina(pagina + 1)} aria-label="Próxima página">
            Próxima
          </Chip>
        </div>
      )}
    </>
  )
}

function DocumentoEmTexto({ texto, trecho }: { texto: string; trecho: TrechoInteiro | null }) {
  const marca = useRef<HTMLElement>(null)
  const partes = useMemo(() => partesDoTexto(texto, trecho ? faixasDoTrecho([texto], trecho.texto).get(0) : undefined), [texto, trecho])
  const primeira = partes.findIndex((parte) => parte.marcado)

  useEffect(() => {
    const caixa = marca.current?.parentElement
    if (!caixa || !marca.current) return
    caixa.scrollTop += marca.current.getBoundingClientRect().top - caixa.getBoundingClientRect().top - caixa.clientHeight / 3
  }, [partes])

  return (
    <>
      {trecho && <TrechoCitado trecho={trecho} aberto={primeira < 0} />}
      <pre data-testid="pagina-do-documento" className="min-h-0 flex-1 overflow-auto rounded-[12px] bg-black/25 p-3.5 font-sans text-[13px] leading-[1.55] whitespace-pre-wrap text-white/90 [overflow-wrap:anywhere]">
        {partes.map((parte, indice) =>
          parte.marcado ? (
            <mark key={indice} ref={indice === primeira ? marca : undefined} className="rounded-[2px] bg-[#ffd201]/35 text-white">
              {parte.texto}
            </mark>
          ) : (
            parte.texto
          ),
        )}
      </pre>
    </>
  )
}

type Props = {
  fonte: FonteDaResposta
  aoFechar: () => void
  className?: string
}

export default function Visualizador({ fonte, aoFechar, className = '' }: Props) {
  const largo = useSyncExternalStore(assinarLargura, () => window.matchMedia(LARGO).matches)
  // No celular o painel cobre a tela e prende o foco. Ao lado da conversa, ela continua à mão, e o Esc fecha.
  const painel = usarFocoPreso(!largo, aoFechar)
  const [arquivo, setArquivo] = useState<{ pdf: Blob } | { texto: string } | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const trecho = usarTrecho(fonte)
  const { documentoId } = fonte

  useEffect(() => {
    if (!largo) return
    painel.current?.focus()
    const aoTeclar = (evento: KeyboardEvent) => {
      // Com a gaveta do diagnóstico por cima, o Esc é dela.
      if (evento.key === 'Escape' && !document.querySelector('[aria-modal="true"]')) aoFechar()
    }
    document.addEventListener('keydown', aoTeclar)
    return () => document.removeEventListener('keydown', aoTeclar)
  }, [largo, aoFechar, painel])

  useEffect(() => {
    if (documentoId === undefined) return
    const controle = new AbortController()
    setArquivo(null)
    setFalha(null)
    ;(async () => {
      try {
        const lido = await lerConteudoDoDocumento(documentoId, controle.signal)
        const conteudo = lido.type.includes('pdf') ? { pdf: lido } : { texto: await lido.text() }
        if (!controle.signal.aborted) setArquivo(conteudo)
      } catch (erro) {
        if (!controle.signal.aborted) setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir este documento.')
      }
    })()
    return () => controle.abort()
  }, [documentoId])

  const titulo = fonte.titulo ?? 'Documento'
  return (
    <div
      ref={painel}
      tabIndex={-1}
      role={largo ? 'region' : 'dialog'}
      aria-modal={largo ? undefined : true}
      aria-label={`Fonte ${fonte.marcador}: ${titulo}`}
      className={`flex min-w-0 flex-col outline-none max-lg:fixed max-lg:inset-0 max-lg:z-[90] ${className}`}
    >
      <PainelLateral sobretitulo={`Fonte ${fonte.marcador}`} titulo={titulo} aoFechar={aoFechar} className="h-full min-w-0 max-lg:rounded-none max-lg:p-4">
        <p className="-mt-2 shrink-0 text-[13px] font-medium text-white/72">{[fonte.versao !== undefined && `Versão ${fonte.versao}`, fonte.secao].filter(Boolean).join(' · ')}</p>
        {fonte.substituido && (
          <AvisoAtlas titulo="Esta versão foi substituída">Há uma versão mais nova deste documento. Aqui está o que valia quando o Atlas respondeu.</AvisoAtlas>
        )}
        {falha ? (
          <AvisoAtlas tipo="falha" titulo={falha} />
        ) : !arquivo ? (
          <LinhaBuscando texto="Abrindo o documento…" />
        ) : 'pdf' in arquivo ? (
          <DocumentoEmPdf arquivo={arquivo.pdf} fonte={fonte} trecho={trecho} />
        ) : (
          <DocumentoEmTexto texto={arquivo.texto} trecho={trecho} />
        )}
      </PainelLateral>
    </div>
  )
}
