// O contrato assinado na tela (Figma P07): o PDF com a página atual, o total e o zoom. Carregado à parte (lazy), porque o
// leitor de PDF é pesado. Como no visualizador do Atlas, a camada de anotações fica desligada: nada do PDF vira link nem
// formulário. [SEGURANCA]
import { Suspense, useEffect, useState } from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import urlDoWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { lerPdfDoContrato } from '../../api/contrato'

pdfjs.GlobalWorkerOptions.workerSrc = urlDoWorker

export default function VisualizadorDeContrato({ arquivoId, nome, cheio = false }: { arquivoId: number; nome: string; cheio?: boolean }) {
  const [arquivo, setArquivo] = useState<Blob | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [paginas, setPaginas] = useState(0)
  const [pagina, setPagina] = useState(1)
  const [zoom, setZoom] = useState(1)
  const [largura, setLargura] = useState(0)

  useEffect(() => {
    let ativo = true
    setArquivo(null)
    setFalha(null)
    lerPdfDoContrato(arquivoId)
      .then((lido) => ativo && setArquivo(lido))
      .catch(() => ativo && setFalha('Não foi possível abrir o PDF. Use "Baixar PDF".'))
    return () => {
      ativo = false
    }
  }, [arquivoId])

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs font-medium text-texto-2">
        <span className="truncate font-semibold text-tinta">{nome}</span>
        <span className="flex items-center gap-2">
          <span aria-live="polite">{paginas ? `Página ${pagina} de ${paginas}` : 'Carregando…'}</span>
          <button type="button" aria-label="Página anterior" className="px-1 disabled:opacity-40" disabled={pagina <= 1} onClick={() => setPagina((p) => p - 1)}>
            <i className="ri-arrow-left-s-line" aria-hidden="true" />
          </button>
          <button type="button" aria-label="Próxima página" className="px-1 disabled:opacity-40" disabled={pagina >= paginas} onClick={() => setPagina((p) => p + 1)}>
            <i className="ri-arrow-right-s-line" aria-hidden="true" />
          </button>
          <span aria-hidden="true">·</span>
          <button type="button" aria-label="Diminuir o zoom" className="px-1 disabled:opacity-40" disabled={zoom <= 0.5} onClick={() => setZoom((z) => Math.max(0.5, z - 0.25))}>
            −
          </button>
          <span>{Math.round(zoom * 100)}%</span>
          <button type="button" aria-label="Aumentar o zoom" className="px-1 disabled:opacity-40" disabled={zoom >= 2} onClick={() => setZoom((z) => Math.min(2, z + 0.25))}>
            +
          </button>
        </span>
      </div>
      <div
        ref={(caixa) => {
          if (caixa && caixa.clientWidth !== largura) setLargura(caixa.clientWidth)
        }}
        className={`overflow-auto rounded-xl bg-white shadow-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-secao ${cheio ? 'max-h-[80vh]' : 'max-h-[560px]'}`}
        // A caixa rola: recebe o foco do teclado para quem não usa o mouse conseguir rolar o PDF.
        tabIndex={0}
        role="region"
        aria-label={`Contrato em PDF: ${nome}`}
      >
        {falha ? (
          <p className="p-6 text-sm text-texto-2" role="alert">
            {falha}
          </p>
        ) : arquivo ? (
          // O Document suspende na primeira renderização. Sem este Suspense próprio, a suspensão subia ao Suspense de fora,
          // que desmontava este componente, perdia o PDF já baixado e o baixava de novo, sem fim.
          <Suspense fallback={<p className="p-6 text-sm">Abrindo o PDF…</p>}>
            <Document file={arquivo} onLoadSuccess={({ numPages }) => setPaginas(numPages)} loading={<p className="p-6 text-sm">Abrindo o PDF…</p>}>
              <Page pageNumber={pagina} width={Math.max(240, largura - 16) * zoom} renderAnnotationLayer={false} renderTextLayer={false} />
            </Document>
          </Suspense>
        ) : (
          <p className="p-6 text-sm text-texto-2">Abrindo o PDF…</p>
        )}
      </div>
    </div>
  )
}
