// Área de envio do Figma (P360/Área de envio): caixa tracejada na cor da seção, que aceita arrastar ou selecionar. [ITEM-4] [UPLOAD]
// Reage ao arquivo por cima (cresce um pouco e escurece a borda) e, com `compacta`, vira uma faixa de uma linha: é o caso de
// quando nada aceita envio agora, para a área não ocupar a primeira dobra da tela (plano da base da RBS, U5).
import { useRef, useState, type DragEvent } from 'react'
import { Botao } from './Botao'

type PropsAreaEnvio = {
  titulo?: string
  texto?: string
  aceitar?: string
  aoEscolher: (arquivo: File) => void
  desabilitado?: boolean
  compacta?: boolean
}

export function AreaEnvio({
  titulo = 'Largue seus documentos aqui',
  texto = 'ou selecione no computador',
  aceitar,
  aoEscolher,
  desabilitado = false,
  compacta = false,
}: PropsAreaEnvio) {
  const entrada = useRef<HTMLInputElement>(null)
  const [arrastando, setArrastando] = useState(false)

  function soltar(evento: DragEvent) {
    evento.preventDefault()
    setArrastando(false)
    const arquivo = evento.dataTransfer.files[0]
    if (arquivo && !desabilitado) aoEscolher(arquivo)
  }

  const seletor = (
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
  )

  if (compacta) {
    return (
      <div className="flex flex-wrap items-center gap-4 rounded-[20px] border-2 border-dashed border-borda bg-white px-5 py-4">
        <span className="grid size-10 shrink-0 place-items-center rounded-full bg-cinza-100 text-texto-2">
          <i className="ri-upload-cloud-2-line text-xl" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-display text-[22px] leading-none tracking-[0.01em] text-texto-2 uppercase">{titulo}</p>
          <p className="mt-1 text-xs font-medium text-texto-2">{texto}</p>
        </div>
        <Botao variante="secundario" onClick={() => entrada.current?.click()} disabled={desabilitado}>
          Selecionar arquivos
        </Botao>
        {seletor}
      </div>
    )
  }

  return (
    <div
      onDragOver={(evento) => {
        evento.preventDefault()
        if (!desabilitado) setArrastando(true)
      }}
      onDragLeave={() => setArrastando(false)}
      onDrop={soltar}
      className={`flex flex-col items-center gap-4 rounded-[20px] border-2 border-dashed bg-secao-tinta px-6 py-8 text-center transition-[transform,filter,border-color] duration-(--duracao-media) ease-(--curva-saida) ${
        arrastando ? 'scale-[1.01] border-secao-texto brightness-95' : 'border-secao'
      }`}
    >
      <span className={`grid size-[52px] place-items-center rounded-full bg-secao text-white transition-transform duration-(--duracao-media) ${arrastando ? '-translate-y-1' : ''}`}>
        <i className="ri-upload-cloud-2-line text-2xl" aria-hidden="true" />
      </span>
      <p className="font-display text-[32px] leading-none tracking-[0.01em] uppercase">{arrastando ? 'Pode soltar' : titulo}</p>
      <p className="text-sm">{texto}</p>
      <Botao onClick={() => entrada.current?.click()} disabled={desabilitado}>
        Selecionar arquivos
      </Botao>
      {seletor}
    </div>
  )
}
