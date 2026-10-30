// "atlas · como posso ajudar?" (Figma P00, 173:219): a entrada do Atlas em Minha parceria, em grafite com o brilho fluido.
// O campo de pergunta aparece com o modelo de linguagem ligado; o relatório da ativação, desabilitado se estiver desligado na API.
// Com a aba Aplicações fechada para a marca (a API responde 403), a caixa não aparece. [ATLAS]
import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { listarConversas } from '../api/atlas'
import { EVENTO_LLM_MUDOU } from '../api/debug'
import type { PedidoAoAtlas } from './PaginaAtlas'
import { Orbe } from './Elementos'

export function CaixaDoAtlas({ parceriaId }: { parceriaId: number }) {
  const navegar = useNavigate()
  const [texto, setTexto] = useState('')
  // null enquanto não se sabe; falso também quando o Atlas não está disponível para a parceria.
  const [estado, setEstado] = useState<{ llm: boolean; relatorio: boolean } | null | 'indisponivel'>(null)
  const destino = `/parceria/${parceriaId}/atlas`

  useEffect(() => {
    let ativo = true
    const ler = () =>
      listarConversas(parceriaId)
        .then((lista) => ativo && setEstado({ llm: lista.llmDisponivel, relatorio: lista.relatorioDisponivel }))
        .catch(() => ativo && setEstado('indisponivel'))
    ler()
    window.addEventListener(EVENTO_LLM_MUDOU, ler)
    return () => {
      ativo = false
      window.removeEventListener(EVENTO_LLM_MUDOU, ler)
    }
  }, [parceriaId])

  if (estado === null || estado === 'indisponivel') return null

  function perguntar(evento: FormEvent) {
    evento.preventDefault()
    const pedido: PedidoAoAtlas | null = texto.trim() ? { mensagem: texto.trim() } : null
    navegar(destino, { state: pedido })
  }

  const montar = () => navegar(destino, { state: { montar: true } satisfies PedidoAoAtlas })

  return (
    <section className="relative overflow-hidden rounded-3xl bg-atlas p-5 text-white sm:p-6" aria-label="Atlas">
      {/* O brilho fluido do canto direito (azul, roxo e rosa), atrás do conteúdo. */}
      <span aria-hidden="true" className="absolute -top-10 right-40 size-48 rounded-full bg-[#073c89] opacity-60 blur-3xl" />
      <span aria-hidden="true" className="absolute right-10 -bottom-16 size-56 rounded-full bg-[#572781] opacity-70 blur-3xl" />
      <span aria-hidden="true" className="absolute -right-10 bottom-0 size-40 rounded-full bg-[#e1047d] opacity-40 blur-3xl" />
      <div className="relative flex flex-col gap-3.5">
        <h2 className="flex items-center gap-3 text-xl font-semibold tracking-[-0.01em]">
          <Orbe tamanho={36} />
          {estado.llm || !estado.relatorio ? 'atlas · como posso ajudar?' : 'atlas · relatório da ativação'}
        </h2>
        {estado.llm ? (
          <form onSubmit={perguntar} className="flex items-center gap-3 rounded-[14px] border border-white/16 bg-white/8 py-2 pr-4 pl-3 sm:pr-5">
            <label className="min-w-0 flex-1">
              <span className="sr-only">Pergunta para o Atlas</span>
              <input
                value={texto}
                onChange={(evento) => setTexto(evento.target.value)}
                maxLength={1000}
                placeholder={estado.relatorio ? 'Pergunte ao Atlas sobre o relatório da ativação…' : 'Pergunte ao Atlas sobre os números da sua parceria…'}
                className="w-full bg-transparent py-1 text-sm leading-normal text-white outline-none placeholder:text-white/64"
              />
            </label>
            <button type="submit" className="flex shrink-0 items-center gap-1 text-sm font-semibold whitespace-nowrap hover:underline">
              Iniciar chat <i className="ri-arrow-right-line" aria-hidden="true" />
            </button>
          </form>
        ) : (
          <p className="text-sm text-white/80">
            {estado.relatorio ? 'Monte o relatório da ativação com os números do portal; o que faltar, o Atlas pede num cartão.' : 'O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.'}
          </p>
        )}
        <div>
          <button
            type="button"
            onClick={montar}
            disabled={!estado.relatorio}
            title={estado.relatorio ? undefined : 'O relatório não está disponível nesta versão'}
            className="inline-flex h-[38px] items-center gap-2 rounded-full bg-white pr-[18px] pl-4 text-[13px] font-bold text-atlas-azul hover:bg-white/90 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <i className="ri-file-chart-line text-base" aria-hidden="true" />
            Gerar relatório da ativação{!estado.relatorio && ' · em breve'}
          </button>
        </div>
      </div>
    </section>
  )
}
