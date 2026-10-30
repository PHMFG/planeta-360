// "atlas · como posso ajudar?" (Figma P00, 173:219): a entrada do Atlas em Minha parceria, em grafite com o brilho fluido.
// A pergunta e os atalhos abrem a janela do Atlas; quem decide o que fazer com eles é a PaginaAtlas. [ATLAS]
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import type { PedidoAoAtlas } from './PaginaAtlas'
import { Orbe } from './Elementos'

export function CaixaDoAtlas({ parceriaId }: { parceriaId: number }) {
  const navegar = useNavigate()
  const [texto, setTexto] = useState('')
  const destino = `/parceria/${parceriaId}/atlas`

  function iniciar(evento: FormEvent) {
    evento.preventDefault()
    const pedido: PedidoAoAtlas | null = texto.trim() ? { mensagem: texto.trim() } : null
    navegar(destino, { state: pedido })
  }

  return (
    <section className="relative overflow-hidden rounded-3xl bg-atlas p-5 text-white sm:p-6" aria-label="Atlas">
      {/* O brilho fluido do canto direito (azul, roxo e rosa), atrás do conteúdo. */}
      <span aria-hidden="true" className="absolute -top-10 right-40 size-48 rounded-full bg-[#073c89] opacity-60 blur-3xl" />
      <span aria-hidden="true" className="absolute -bottom-16 right-10 size-56 rounded-full bg-[#572781] opacity-70 blur-3xl" />
      <span aria-hidden="true" className="absolute -right-10 bottom-0 size-40 rounded-full bg-[#e1047d] opacity-40 blur-3xl" />
      <div className="relative flex flex-col gap-3.5">
        <h2 className="flex items-center gap-3 text-xl font-semibold tracking-[-0.01em]">
          <Orbe tamanho={36} />
          atlas · como posso ajudar?
        </h2>
        <form onSubmit={iniciar} className="flex items-center gap-3 rounded-[14px] border border-white/16 bg-white/8 py-2 pr-4 pl-3 sm:pr-5">
          <label className="min-w-0 flex-1">
            <span className="sr-only">Pergunta para o Atlas</span>
            <input
              value={texto}
              onChange={(evento) => setTexto(evento.target.value)}
              maxLength={1000}
              placeholder="Pergunte ao Atlas sobre sua parceria…"
              className="w-full bg-transparent py-1 text-sm leading-normal text-white outline-none placeholder:text-white/64"
            />
          </label>
          <button type="submit" className="flex shrink-0 items-center gap-1 text-sm font-semibold whitespace-nowrap hover:underline">
            Iniciar chat <i className="ri-arrow-right-line" aria-hidden="true" />
          </button>
        </form>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs font-medium text-white/72">
          <Link to={destino} state={{ montar: true } satisfies PedidoAoAtlas} className="hover:text-white hover:underline">
            Gerar relatório da ativação
          </Link>
          <span aria-hidden="true">·</span>
          <Link to={`/parceria/${parceriaId}/aplicacoes`} className="hover:text-white hover:underline">
            Informar os números da ativação
          </Link>
          <span aria-hidden="true">·</span>
          <Link to={`/parceria/${parceriaId}/relatorio`} className="hover:text-white hover:underline">
            Relatórios do Atlas
          </Link>
        </p>
      </div>
    </section>
  )
}
