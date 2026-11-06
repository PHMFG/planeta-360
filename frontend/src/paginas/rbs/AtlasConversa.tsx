// O Atlas da RBS: conversa por edição, sem parceria, sobre os documentos gerais, com modelos em branco, contas e datas. [ATLAS]
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import {
  abrirConversaDaRbs,
  enviarMensagem,
  listarConversasDaRbs,
  obterConversa,
  type Conversa,
  type ConversaDaRbsResumo,
  type FonteDaResposta,
} from '../../api/atlas'
import { ErroApi } from '../../api/cliente'
import { listarEventos, type Evento } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { aplicar, SEM_RESPOSTA, type RespostaAoVivo } from '../../atlas/AoVivo'
import { AreaDaConversa } from '../../atlas/Conversa'
import { Chip } from '../../atlas/Elementos'
import { Aviso } from '../../componentes/Aviso'
import { Carregando } from '../../componentes/Carregando'

const Visualizador = lazy(() => import('../../atlas/Visualizador'))

const nada = () => undefined

function ConversaDaEdicao({
  evento,
  eventos,
  aoTrocar,
  atalhos,
}: {
  evento: Evento
  eventos: Evento[]
  aoTrocar: (eventoId: number) => void
  atalhos?: React.ReactNode
}) {
  const [lista, setLista] = useState<ConversaDaRbsResumo[]>([])
  const [llm, setLlm] = useState(false)
  const [conversa, setConversa] = useState<Conversa | null>(null)
  const [texto, setTexto] = useState('')
  const [enviada, setEnviada] = useState<string | null>(null)
  const [pensando, setPensando] = useState(false)
  const [aoVivo, setAoVivo] = useState<RespostaAoVivo | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [fechado, setFechado] = useState(false)
  const [fonte, setFonte] = useState<FonteDaResposta | null>(null)
  const turnoAtual = useRef<AbortController | null>(null)

  const atualizarLista = useCallback(async () => {
    const resposta = await listarConversasDaRbs(evento.id)
    setLista(resposta.conversas)
    setLlm(resposta.llmDisponivel)
    return resposta.conversas
  }, [evento.id])

  useEffect(() => {
    setConversa(null)
    setFechado(false)
    atualizarLista()
      .then((conversas) => {
        if (conversas[0]) return obterConversa(conversas[0].id).then(setConversa)
      })
      .catch((erro: unknown) => {
        // Com o Atlas da RBS desligado na API, a rota não existe: a aba diz isso.
        if (erro instanceof ErroApi && erro.status === 404) setFechado(true)
        else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir as conversas.')
      })
  }, [atualizarLista])

  useEffect(() => () => turnoAtual.current?.abort(), [])

  async function perguntar() {
    const mensagem = texto.trim()
    if (!mensagem || pensando) return
    const controle = new AbortController()
    turnoAtual.current = controle
    setFalha(null)
    setPensando(true)
    setAoVivo(SEM_RESPOSTA)
    setEnviada(mensagem)
    setTexto('')
    try {
      const atual = conversa ?? (await abrirConversaDaRbs(evento.id))
      setConversa(atual)
      await enviarMensagem(atual.conversaId, mensagem, (evento_) => setAoVivo((estado) => aplicar(estado ?? SEM_RESPOSTA, evento_)), controle.signal)
      setConversa(await obterConversa(atual.conversaId))
      await atualizarLista()
    } catch (erro) {
      if (controle.signal.aborted) return
      setTexto(mensagem)
      setFalha(erro instanceof ErroApi ? erro.message : 'O Atlas não respondeu. Tente de novo.')
    } finally {
      if (!controle.signal.aborted) {
        setPensando(false)
        setAoVivo(null)
        setEnviada(null)
      }
    }
  }

  if (fechado) return <Aviso tipo="ausente" titulo="O Atlas da RBS está desligado nesta versão" />

  const inicio = !conversa && !enviada
  const classeDoSeletor = 'h-9 max-w-full rounded-full border border-white/20 bg-white/8 px-3 text-[13px] font-semibold text-white outline-none focus:border-white/60 [&>option]:text-tinta'

  // Tela inteira, como o Atlas da marca (pedido do ph, 06/11): o fundo do Atlas cobre a tela por trás do menu, a barra do topo
  // tem a edição, as conversas e os atalhos da gestão, e a conversa ocupa o resto.
  return (
    <div className="flex h-[calc(100dvh-6rem)] min-h-[560px] gap-4 text-white lg:h-[calc(100dvh-2rem)]">
      <div aria-hidden="true" className={`fixed inset-0 -z-10 overflow-hidden print:hidden ${inicio ? '' : 'bg-atlas'}`}>
        {inicio && <div className="fundo-fluido absolute -inset-[6%] animate-fluir" />}
      </div>
      <section className={`flex min-w-0 flex-1 flex-col ${fonte ? 'max-lg:hidden' : ''}`} aria-label="Atlas da RBS">
        <header className="flex min-h-[72px] flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-white/10 py-3.5 pr-0 pl-1 sm:pr-5 sm:pl-7">
          <div className="min-w-0">
            <p className="truncate text-[11px] font-bold tracking-[0.12em] text-white/64 uppercase">Atlas&nbsp;&nbsp;/&nbsp;&nbsp;RBS · Gestão</p>
            <div className="mt-1 flex min-w-0 flex-wrap items-center gap-2.5">
              <h1 className="truncate text-base font-semibold text-white">Todas as marcas</h1>
              <select aria-label="Edição" className={classeDoSeletor} value={evento.id} disabled={pensando} onChange={(campo) => aoTrocar(Number(campo.target.value))}>
                {eventos.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.nome} {item.edicao}
                  </option>
                ))}
              </select>
              {lista.length > 0 && (
                <select
                  aria-label="Conversa"
                  className={`${classeDoSeletor} w-56`}
                  value={conversa?.conversaId ?? ''}
                  disabled={pensando}
                  onChange={(campo) => void obterConversa(Number(campo.target.value)).then(setConversa)}
                >
                  {!conversa && <option value="">Nova conversa</option>}
                  {lista.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.previa ?? 'Conversa sem mensagem'}
                    </option>
                  ))}
                </select>
              )}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2.5">
            {atalhos}
            <Chip
              icone="ri-add-line"
              disabled={pensando}
              onClick={() => {
                setConversa(null)
                setTexto('')
              }}
            >
              Nova conversa
            </Chip>
          </div>
        </header>
        <AreaDaConversa
          parceria={null}
          itens={conversa?.itens ?? []}
          enviada={enviada}
          pensando={pensando}
          aoVivo={aoVivo}
          falha={falha}
          llm={llm}
          relatorioDisponivel={false}
          debugDeFerramentas={conversa?.debugDeFerramentas ?? false}
          conversaDoDiagnostico={conversa?.diagnosticoDisponivel ? conversa.conversaId : null}
          texto={texto}
          aoMudarTexto={setTexto}
          aoEnviar={() => void perguntar()}
          aoMontar={nada}
          aoResponder={() => Promise.resolve()}
          aoAbrirDocumento={nada}
          aoAbrirFonte={setFonte}
        />
      </section>
      {fonte && (
        <Suspense fallback={null}>
          <Visualizador fonte={fonte} aoFechar={() => setFonte(null)} className="animate-deslizar lg:w-[46%] lg:max-w-[720px] lg:shrink-0" />
        </Suspense>
      )}
    </div>
  )
}

/** A conversa da RBS em tela inteira; `atalhos` são os links para Documentos, Prazos e o Diagnóstico, na barra do topo. */
export function AbaConversa({ atalhos }: { atalhos?: React.ReactNode }) {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState<number | null>(null)
  // O fundo do Atlas fica numa camada fixa por trás da página; a raiz ganha a mesma cor enquanto a conversa está aberta, para
  // que nada claro apareça nas bordas e a conferência de contraste leia o texto branco sobre o fundo escuro.
  useEffect(() => {
    const raiz = document.documentElement
    const antes = raiz.style.background
    raiz.style.background = 'var(--color-atlas)'
    return () => {
      raiz.style.background = antes
    }
  }, [])
  return (
    <Carregando dados={eventos} vazio={(lista) => lista.length === 0} textoVazio="Nenhuma edição cadastrada.">
      {(lista) => <ConversaDaEdicao evento={lista.find((item) => item.id === eventoId) ?? lista[0]} eventos={lista} aoTrocar={setEventoId} atalhos={atalhos} />}
    </Carregando>
  )
}
