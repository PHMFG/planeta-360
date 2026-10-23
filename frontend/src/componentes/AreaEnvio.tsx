// Área de envio do Figma (P360/Área de envio): caixa tracejada na cor da seção, que aceita arrastar ou selecionar. [ITEM-4] [UPLOAD]
import { useRef, useState, type DragEvent } from 'react'
import { Botao } from './Botao'

type PropsAreaEnvio = {
  titulo?: string
  texto?: string
  aceitar?: string
  aoEscolher: (arquivo: File) => void
  desabilitado?: boolean
}

export function AreaEnvio({
  titulo = 'Largue seus documentos aqui',
  texto = 'ou selecione no computador',
  aceitar,
  aoEscolher,
  desabilitado = false,
}: PropsAreaEnvio) {
  const entrada = useRef<HTMLInputElement>(null)
  const [arrastando, setArrastando] = useState(false)

  function soltar(evento: DragEvent) {
    evento.preventDefault()
    setArrastando(false)
    const arquivo = evento.dataTransfer.files[0]
    if (arquivo && !desabilitado) aoEscolher(arquivo)
  }

  return (
    <div
      onDragOver={(evento) => {
        evento.preventDefault()
        if (!desabilitado) setArrastando(true)
      }}
      onDragLeave={() => setArrastando(false)}
      onDrop={soltar}
      className={`flex flex-col items-center gap-4 rounded-[20px] border-2 border-dashed border-secao bg-secao-tinta px-6 py-8 text-center transition-[filter] ${
        arrastando ? 'brightness-95' : ''
      }`}
    >
      <span className="grid size-[52px] place-items-center rounded-full bg-secao text-white">
        <i className="ri-upload-cloud-2-line text-2xl" aria-hidden="true" />
      </span>
      <p className="font-display text-[32px] leading-none tracking-[0.01em] uppercase">{titulo}</p>
      <p className="text-sm">{texto}</p>
      <Botao onClick={() => entrada.current?.click()} disabled={desabilitado}>
        Selecionar arquivos
      </Botao>
      <input
        ref={entrada}
        type="file"
        accept={aceitar}
        className="hidden"
        onChange={(evento) => {
          const arquivo = evento.target.files?.[0]
          if (arquivo) aoEscolher(arquivo)
          evento.target.value = ''
        }}
      />
    </div>
  )
}
