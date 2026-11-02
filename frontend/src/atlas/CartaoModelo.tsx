// Um modelo em branco pedido ao Atlas: a prévia, baixar como .md e copiar. O arquivo é gerado aqui no navegador, a partir do que o
// servidor mandou, e nenhum campo vem com valor. [ATLAS]
import { useState } from 'react'
import { Chip } from './Elementos'

type Props = {
  titulo: string
  conteudo: string
  nomeArquivo: string
}

function baixar(conteudo: string, nomeArquivo: string) {
  const endereco = URL.createObjectURL(new Blob([conteudo], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = endereco
  link.download = nomeArquivo
  document.body.append(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(endereco)
}

export function CartaoModelo({ titulo, conteudo, nomeArquivo }: Props) {
  const [copia, setCopia] = useState<'copiou' | 'falhou' | null>(null)

  async function copiar() {
    try {
      await navigator.clipboard.writeText(conteudo)
      setCopia('copiou')
    } catch {
      setCopia('falhou')
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-white/16 bg-white/8 p-4">
      <div className="flex items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-white/10">
          <i className="ri-draft-line text-xl text-white" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-base font-semibold text-white">{titulo}</p>
          <p className="text-[13px] text-white/64">Modelo em branco, para você preencher.</p>
        </div>
      </div>
      <pre tabIndex={0} aria-label={`Prévia do modelo ${titulo}`} className="max-h-64 overflow-y-auto rounded-[12px] bg-black/25 p-3 font-sans text-[13px] leading-[1.55] whitespace-pre-wrap text-white/88 [overflow-wrap:anywhere]">
        {conteudo}
      </pre>
      <div className="flex flex-wrap items-center gap-2.5">
        <Chip variante="primario" icone="ri-download-2-line" onClick={() => baixar(conteudo, nomeArquivo)}>
          Baixar .md
        </Chip>
        <Chip icone="ri-file-copy-line" onClick={copiar}>
          Copiar
        </Chip>
        <span className="text-xs text-white/64" role="status">
          {copia === 'copiou' ? 'Copiado.' : copia === 'falhou' ? 'Não foi possível copiar: selecione o texto da prévia.' : ''}
        </span>
      </div>
    </div>
  )
}
