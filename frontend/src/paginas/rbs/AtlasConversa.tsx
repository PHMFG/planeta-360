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
import { Seletor } from '../../componentes/Formulario'

const Visualizador = lazy(() => import('../../atlas/Visualizador'))

const nada = () => undefined

function ConversaDaEdicao({ evento, eventos, aoTrocar }: { evento: Evento; eventos: Evento[]; aoTrocar: (eventoId: number) => void }) {
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

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <Seletor rotulo="Edição" className="min-w-0 sm:w-72" value={evento.id} onChange={(campo) => aoTrocar(Number(campo.target.value))}>
          {eventos.map((item) => (
            <option key={item.id} value={item.id}>
              {item.nome} {item.edicao}
            </option>
          ))}
        </Seletor>
        {lista.length > 0 && (
          <Seletor
            rotulo="Conversa"
            className="min-w-0 flex-1"
            value={conversa?.conversaId ?? ''}
            disabled={pensando}
            onChange={(campo) => void obterConversa(Number(campo.target.value)).then(setConversa)}
          >
            {lista.map((item) => (
              <option key={item.id} value={item.id}>
                {item.previa ?? 'Conversa sem mensagem'}
              </option>
            ))}
          </Seletor>
        )}
      </div>
      <div className="flex h-[calc(100dvh-20rem)] min-h-[520px] gap-4 rounded-3xl bg-atlas p-4 text-white">
        <section className="flex min-w-0 flex-1 flex-col">
          <div className="flex justify-end pb-2">
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
            <Visualizador fonte={fonte} aoFechar={() => setFonte(null)} className="lg:w-[46%] lg:max-w-[720px] lg:shrink-0" />
          </Suspense>
        )}
      </div>
    </div>
  )
}

export function AbaConversa() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState<number | null>(null)
  return (
    <Carregando dados={eventos} vazio={(lista) => lista.length === 0} textoVazio="Nenhuma edição cadastrada.">
      {(lista) => <ConversaDaEdicao evento={lista.find((item) => item.id === eventoId) ?? lista[0]} eventos={lista} aoTrocar={setEventoId} />}
    </Carregando>
  )
}
