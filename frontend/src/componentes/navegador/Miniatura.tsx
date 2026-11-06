// A miniatura de um arquivo no navegador: a própria imagem, a primeira página do PDF ou o ícone do tipo. Só baixa o arquivo
// quando a miniatura aparece na tela, e guarda o que já baixou para não pedir de novo ao voltar à pasta.
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { lerArquivo } from '../../api/cliente'
import type { ItemDoNavegador } from './NavegadorDeArquivos'

const MiniaturaPdf = lazy(() => import('./MiniaturaPdf'))

// Até 8 MB por miniatura: acima disso fica o ícone, para não baixar um vídeo inteiro só para enfeitar a pasta.
const LIMITE = 8 * 1024 * 1024
const guardados = new Map<number, Promise<Blob>>()

function lerUmaVez(id: number) {
  let pedido = guardados.get(id)
  if (!pedido) {
    pedido = lerArquivo(`/arquivos/${id}`)
    pedido.catch(() => guardados.delete(id))
    guardados.set(id, pedido)
  }
  return pedido
}

export function iconeDoTipo(mime: string, nome: string) {
  const extensao = nome.split('.').pop()?.toLowerCase() ?? ''
  if (mime.startsWith('image/')) return 'ri-image-2-line'
  if (mime.startsWith('video/')) return 'ri-movie-2-line'
  if (mime.startsWith('audio/')) return 'ri-music-2-line'
  if (mime === 'application/pdf') return 'ri-file-pdf-2-line'
  if (['xlsx', 'xls', 'csv'].includes(extensao)) return 'ri-file-excel-2-line'
  if (['docx', 'doc'].includes(extensao)) return 'ri-file-word-2-line'
  if (['pptx', 'ppt'].includes(extensao)) return 'ri-file-ppt-2-line'
  if (['zip', 'rar'].includes(extensao)) return 'ri-file-zip-line'
  return 'ri-file-3-line'
}

function Icone({ item }: { item: ItemDoNavegador }) {
  const extensao = item.nome.includes('.') ? item.nome.split('.').pop()!.toUpperCase() : ''
  return (
    <span className="flex size-full flex-col items-center justify-center gap-1 text-secao">
      <i className={`${iconeDoTipo(item.mime, item.nome)} text-3xl`} aria-hidden="true" />
      {extensao && <span className="text-[10px] font-bold tracking-wide">{extensao}</span>}
    </span>
  )
}

export function Miniatura({ item, className = '' }: { item: ItemDoNavegador; className?: string }) {
  const caixa = useRef<HTMLSpanElement>(null)
  const [visivel, setVisivel] = useState(false)
  const [arquivo, setArquivo] = useState<Blob | null>(null)
  const [endereco, setEndereco] = useState<string | null>(null)
  const [falhou, setFalhou] = useState(false)
  const [largura, setLargura] = useState(160)
  const imagem = item.mime.startsWith('image/')
  const pdf = item.mime === 'application/pdf'
  const video = item.mime.startsWith('video/')
  const cabe = !item.tamanhoBytes || item.tamanhoBytes <= LIMITE
  const quer = (imagem || pdf || video) && cabe

  useEffect(() => {
    if (!quer || !caixa.current) return
    setLargura(Math.round(caixa.current.clientWidth) || 160)
    const observador = new IntersectionObserver(([entrada]) => entrada.isIntersecting && setVisivel(true), { rootMargin: '200px' })
    observador.observe(caixa.current)
    return () => observador.disconnect()
  }, [quer])

  useEffect(() => {
    if (!visivel) return
    let ativo = true
    lerUmaVez(item.id)
      .then((lido) => ativo && setArquivo(lido))
      .catch(() => ativo && setFalhou(true))
    return () => {
      ativo = false
    }
  }, [visivel, item.id])

  useEffect(() => {
    if (!arquivo || !(imagem || video)) return
    const url = URL.createObjectURL(arquivo)
    setEndereco(url)
    return () => URL.revokeObjectURL(url)
  }, [arquivo, imagem, video])

  return (
    <span ref={caixa} className={`block overflow-hidden ${className}`}>
      {!quer || falhou ? (
        <Icone item={item} />
      ) : imagem && endereco ? (
        <img src={endereco} alt="" className="size-full object-cover" />
      ) : video && endereco ? (
        // O primeiro quadro do vídeo, sem som e sem tocar; o ícone por cima diz que é vídeo.
        <span className="relative block size-full">
          <video src={`${endereco}#t=0.1`} muted playsInline preload="metadata" className="size-full object-cover" aria-hidden="true" />
          <i className="ri-play-circle-fill absolute inset-0 m-auto size-fit text-4xl text-white drop-shadow" aria-hidden="true" />
        </span>
      ) : pdf && arquivo ? (
        <span className="block size-full overflow-hidden bg-white">
          <Suspense fallback={<Icone item={item} />}>
            <MiniaturaPdf arquivo={arquivo} largura={largura} />
          </Suspense>
        </span>
      ) : (
        <span className="esqueleto block size-full" aria-hidden="true" />
      )}
    </span>
  )
}
