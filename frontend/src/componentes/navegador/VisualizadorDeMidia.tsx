// Imagem, vídeo ou áudio aberto na tela do navegador de arquivos. Carregado à parte, junto com a visualização.
import { useEffect, useState } from 'react'
import { lerArquivo } from '../../api/cliente'

export default function VisualizadorDeMidia({ arquivoId, nome, mime }: { arquivoId: number; nome: string; mime: string }) {
  const [endereco, setEndereco] = useState<string | null>(null)
  const [falha, setFalha] = useState(false)

  useEffect(() => {
    let ativo = true
    let url: string | null = null
    setEndereco(null)
    setFalha(false)
    lerArquivo(`/arquivos/${arquivoId}`)
      .then((lido) => {
        if (!ativo) return
        url = URL.createObjectURL(lido)
        setEndereco(url)
      })
      .catch(() => ativo && setFalha(true))
    return () => {
      ativo = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [arquivoId])

  if (falha) return <p className="rounded-2xl bg-secao-tinta p-6 text-center text-sm text-texto-2">Não foi possível abrir o arquivo. Use “Baixar”.</p>
  if (!endereco) return <div className="esqueleto h-72 rounded-2xl" role="status"><span className="sr-only">Carregando…</span></div>
  if (mime.startsWith('video/')) return <video src={endereco} controls className="max-h-[70vh] w-full rounded-2xl bg-black" aria-label={nome} />
  if (mime.startsWith('audio/')) return <audio src={endereco} controls className="w-full" aria-label={nome} />
  return (
    <div className="grid place-items-center rounded-2xl bg-secao-tinta p-2">
      <img src={endereco} alt={nome} className="max-h-[70vh] max-w-full rounded-xl object-contain" />
    </div>
  )
}
