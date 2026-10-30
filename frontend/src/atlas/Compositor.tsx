// Atlas/Compositor (179:1204): o campo de vidro com o botão branco de enviar. Enter envia; Shift+Enter quebra a linha. [ATLAS]
// Sem o modelo de linguagem, o campo fica desligado e diz o que fazer (o relatório sai pelo botão "Montar relatório").
import { useEffect, useRef, type FormEvent, type KeyboardEvent } from 'react'

type Props = {
  valor: string
  aoMudar: (valor: string) => void
  aoEnviar: () => void
  rotuloEnviar: string
  ocupado: boolean
  /** Falso quando a API sobe sem modelo: o chat fica desligado. */
  habilitado: boolean
  focarAoAbrir?: boolean
  compacto?: boolean
}

export function Compositor({ valor, aoMudar, aoEnviar, rotuloEnviar, ocupado, habilitado, focarAoAbrir = false, compacto = false }: Props) {
  const campo = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (focarAoAbrir && habilitado) campo.current?.focus()
  }, [focarAoAbrir, habilitado])

  function enviar(evento?: FormEvent) {
    evento?.preventDefault()
    if (!valor.trim() || ocupado || !habilitado) return
    aoEnviar()
  }

  function teclar(evento: KeyboardEvent<HTMLTextAreaElement>) {
    if (evento.key === 'Enter' && !evento.shiftKey) {
      evento.preventDefault()
      enviar()
    }
  }

  return (
    <form
      onSubmit={enviar}
      className={`flex items-end gap-3 rounded-[20px] border border-white/22 bg-white/10 shadow-compositor backdrop-blur-md ${compacto ? 'p-2 pl-3.5' : 'p-2.5 pl-[18px]'}`}
    >
      <label className="min-w-0 flex-1 self-center">
        <span className="sr-only">Mensagem para o Atlas</span>
        <textarea
          ref={campo}
          rows={1}
          maxLength={1000}
          value={valor}
          disabled={!habilitado}
          onChange={(evento) => aoMudar(evento.target.value)}
          onKeyDown={teclar}
          placeholder={habilitado ? 'Pergunte ao Atlas sobre sua parceria…' : 'Chat desligado: use “Montar relatório”.'}
          className="field-sizing-content max-h-40 min-h-6 w-full resize-none bg-transparent py-2 text-[15px] leading-normal text-white outline-none placeholder:text-white/66 disabled:cursor-not-allowed"
        />
      </label>
      <button
        type="submit"
        disabled={!habilitado || ocupado || !valor.trim()}
        className="flex h-11 shrink-0 items-center gap-1.5 rounded-full bg-white px-[18px] text-[13px] font-bold tracking-[0.06em] text-atlas-azul uppercase transition-opacity disabled:cursor-not-allowed disabled:opacity-50"
      >
        {ocupado ? <i className="ri-loader-4-line animate-spin text-lg" aria-hidden="true" /> : null}
        <span className={compacto ? 'sr-only sm:not-sr-only' : ''}>{rotuloEnviar}</span>
        <i className="ri-arrow-right-line text-lg" aria-hidden="true" />
      </button>
    </form>
  )
}
