// A primeira página de um PDF como miniatura do navegador de arquivos. Carregada à parte (lazy), porque o leitor de PDF é pesado;
// como no visualizador do contrato, a camada de texto e a de anotações ficam desligadas: nada do PDF vira link. [SEGURANCA]
import { Document, Page, pdfjs } from 'react-pdf'
import urlDoWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjs.GlobalWorkerOptions.workerSrc = urlDoWorker

export default function MiniaturaPdf({ arquivo, largura }: { arquivo: Blob; largura: number }) {
  return (
    <Document file={arquivo} loading={null} error={null} className="flex justify-center">
      <Page pageNumber={1} width={largura} renderTextLayer={false} renderAnnotationLayer={false} loading={null} />
    </Document>
  )
}
