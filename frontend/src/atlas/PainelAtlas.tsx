// O Atlas: painel à direita em que a marca pede o relatório da ativação, responde ao cartão de perguntas e recebe o A4.
// [ATLAS] Sem streaming no MVP: cada pedido espera o turno inteiro. Só a marca conversa; a RBS vê os relatórios na tela Relatório.
// Com a API sem modelo de linguagem (llmDisponivel falso), o painel mostra só o botão "Montar relatório" (plano B).
import { useEffect, useEffectEvent, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { abrirConversa, enviarMensagem, listarConversas, montarRelatorio, obterConversa, type Conversa, type ItemDaConversa } from '../api/atlas'
import { ErroApi } from '../api/cliente'
import type { ParceriaResumo } from '../api/tipos'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { CartaoRelatorioPronto } from './CartaoRelatorioPronto'
import { CartaoSolicitacao } from './CartaoSolicitacao'

type Props = { parceria: ParceriaResumo; aberto: boolean; aoFechar: () => void }

function Item({ item, parceriaId, aoResponder, aoAbrirRelatorio }: {
  item: ItemDaConversa
  parceriaId: number
  aoResponder: (turno: Conversa) => void
  aoAbrirRelatorio: () => void
}) {
  switch (item.tipo) {
    case 'usuario':
      return <p className="ml-10 self-end rounded-2xl rounded-br-md bg-tinta px-4 py-2.5 text-sm whitespace-pre-wrap text-white">{item.texto}</p>
    case 'texto':
      return <p className="mr-10 self-start rounded-2xl rounded-bl-md bg-cinza-100 px-4 py-2.5 text-sm whitespace-pre-wrap">{item.texto}</p>
    case 'aviso':
      return <p className="text-center text-xs font-medium text-texto-2 italic">{item.texto}</p>
    case 'cartao':
      return <CartaoSolicitacao item={item} aoResponder={aoResponder} />
    case 'relatorio_pronto':
      return <CartaoRelatorioPronto parceriaId={parceriaId} item={item} aoAbrir={aoAbrirRelatorio} />
  }
}

export function PainelAtlas({ parceria, aberto, aoFechar }: Props) {
  const [conversa, setConversa] = useState<Conversa | null>(null)
  const [llm, setLlm] = useState<boolean | null>(null)
  const [carregada, setCarregada] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [pensando, setPensando] = useState(false)
  const [enviada, setEnviada] = useState<string | null>(null)
  const [texto, setTexto] = useState('')
  const fim = useRef<HTMLDivElement>(null)
  const painel = useRef<HTMLElement>(null)
  const fecharComEsc = useEffectEvent((evento: globalThis.KeyboardEvent) => evento.key === 'Escape' && aoFechar())

  // Na primeira abertura, retoma a conversa mais recente da pessoa nesta parceria (se houver).
  useEffect(() => {
    if (!aberto || carregada) return
    let ativo = true
    listarConversas(parceria.id)
      .then(async (lista) => {
        if (!ativo) return
        setLlm(lista.llmDisponivel)
        if (lista.conversas.length) {
          const atual = await obterConversa(lista.conversas[0].id)
          if (ativo) setConversa(atual)
        }
      })
      .catch((erro: unknown) => ativo && setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir o Atlas.'))
      .finally(() => ativo && setCarregada(true))
    return () => {
      ativo = false
    }
  }, [aberto, carregada, parceria.id])

  useEffect(() => {
    if (!aberto) return
    const aoTeclar = (evento: globalThis.KeyboardEvent) => fecharComEsc(evento)
    document.addEventListener('keydown', aoTeclar)
    painel.current?.focus()
    return () => document.removeEventListener('keydown', aoTeclar)
  }, [aberto])

  // O fim da conversa fica à vista a cada item novo (e enquanto o Atlas responde).
  useEffect(() => {
    fim.current?.scrollIntoView({ block: 'end' })
  }, [conversa, pensando, enviada])

  /** Roda um turno e relê a conversa inteira: um cartão aberto muda de status quando chega mensagem nova. */
  async function turno(fazer: () => Promise<Conversa>, mensagem?: string) {
    setFalha(null)
    setPensando(true)
    if (mensagem) setEnviada(mensagem)
    try {
      await aposTurno(await fazer())
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'O Atlas não respondeu. Tente de novo.')
      if (mensagem) setTexto(mensagem)
    } finally {
      setPensando(false)
      setEnviada(null)
    }
  }

  async function aposTurno(resultado: Conversa) {
    setLlm(resultado.llmDisponivel)
    setConversa(await obterConversa(resultado.conversaId))
  }

  function montar() {
    turno(() => (conversa ? montarRelatorio(conversa.conversaId) : abrirConversa(parceria.id, true)))
  }

  function enviar(evento?: FormEvent) {
    evento?.preventDefault()
    const mensagem = texto.trim()
    if (!mensagem || pensando) return
    setTexto('')
    turno(async () => enviarMensagem((conversa ?? (await abrirConversa(parceria.id))).conversaId, mensagem), mensagem)
  }

  function teclar(evento: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter envia; Shift+Enter quebra a linha.
    if (evento.key === 'Enter' && !evento.shiftKey) {
      evento.preventDefault()
      enviar()
    }
  }

  const itens = conversa?.itens ?? []
  const cartaoAberto = itens.some((item) => item.tipo === 'cartao' && item.status === 'PENDENTE')

  return (
    <div className={aberto ? '' : 'hidden'}>
      <div className="fixed inset-0 z-[60] bg-tinta/45 print:hidden" onClick={aoFechar} aria-hidden="true" />
      <aside
        ref={painel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="Atlas"
        className="fixed inset-y-0 right-0 z-[70] flex w-full flex-col bg-white shadow-menu outline-none sm:w-[460px] print:hidden"
      >
        <header className="flex items-center gap-3 bg-atlas px-5 py-4 text-white">
          <i className="ri-globe-fill text-2xl" aria-hidden="true" />
          <div className="min-w-0 flex-1">
            <p className="text-lg leading-tight font-semibold">atlas</p>
            <p className="truncate text-xs text-white/75">Relatório assistido · {parceria.patrocinador}</p>
          </div>
          {conversa && (
            <button type="button" onClick={() => setConversa(null)} disabled={pensando} className="text-xs font-semibold hover:underline disabled:opacity-60">
              Nova conversa
            </button>
          )}
          <button type="button" onClick={aoFechar} className="flex items-center gap-1 text-[13px] font-semibold">
            Fechar <i className="ri-close-line text-lg" aria-hidden="true" />
          </button>
        </header>

        <div className="flex flex-1 flex-col gap-3 overflow-y-auto px-5 py-4" aria-live="polite">
          {!carregada ? (
            <Aviso tipo="carregando" titulo="Abrindo o Atlas…" />
          ) : itens.length === 0 && !enviada ? (
            <div className="flex flex-col gap-3 text-sm">
              <p className="font-semibold">Eu monto o relatório da sua ativação no evento.</p>
              <p className="text-texto-2">
                Uso os números do portal (plano, fotos de comprovação) e pergunto num cartão o que só vocês sabem, como visitantes e
                cadastros. Não escrevo números por conta própria: cada número do relatório mostra de onde veio.
              </p>
              {llm === false && (
                <p className="text-xs text-texto-2">O modelo de linguagem está desligado agora, mas o botão abaixo monta o relatório do mesmo jeito.</p>
              )}
            </div>
          ) : (
            itens.map((item, indice) => (
              <Item key={indice} item={item} parceriaId={parceria.id} aoResponder={(resultado) => {
                  aposTurno(resultado).catch(() => setFalha('O cartão foi respondido, mas a conversa não recarregou. Feche e abra o Atlas.'))
                }} aoAbrirRelatorio={aoFechar} />
            ))
          )}
          {enviada && <p className="ml-10 self-end rounded-2xl rounded-br-md bg-tinta px-4 py-2.5 text-sm whitespace-pre-wrap text-white">{enviada}</p>}
          {pensando && <p className="text-xs font-medium text-texto-2">O Atlas está trabalhando…</p>}
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div ref={fim} />
        </div>

        <footer className="flex flex-col gap-3 border-t border-borda px-5 py-4">
          <Botao data-secao="aplicacoes" icone="ri-file-chart-line" className="w-full min-w-0" carregando={pensando} disabled={!carregada || cartaoAberto} onClick={montar}>
            Montar relatório da ativação
          </Botao>
          {llm && (
            <form onSubmit={enviar} className="flex items-end gap-2">
              <label className="flex-1">
                <span className="sr-only">Mensagem para o Atlas</span>
                <textarea
                  rows={2}
                  maxLength={1000}
                  value={texto}
                  onChange={(evento) => setTexto(evento.target.value)}
                  onKeyDown={teclar}
                  placeholder="Peça o relatório ou pergunte sobre ele…"
                  className="w-full resize-none rounded-xl border-[1.5px] border-borda px-3 py-2 text-sm outline-none focus:border-parceria"
                />
              </label>
              <button
                type="submit"
                disabled={!texto.trim() || pensando}
                aria-label="Enviar mensagem"
                className="grid size-11 shrink-0 place-items-center rounded-xl bg-tinta text-white disabled:opacity-50"
              >
                <i className="ri-send-plane-2-fill text-lg" aria-hidden="true" />
              </button>
            </form>
          )}
        </footer>
      </aside>
    </div>
  )
}
