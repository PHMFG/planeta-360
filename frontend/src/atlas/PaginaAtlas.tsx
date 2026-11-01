// O Atlas como janela própria dentro do portal (Figma AT00 a AT06): o menu lateral continua à vista, a coluna do Atlas é escura
// (fundo fluido na tela inicial, grafite depois do primeiro pedido) e o documento abre ao lado da conversa. [ATLAS]
// Quando a marca pede um documento (o botão, ou o modelo começando um relatório), a conversa vai para o painel à direita e o
// centro mostra a pré-visualização do documento, que se atualiza a cada turno. Só a marca conversa; a RBS vê os relatórios na
// tela Relatório. A resposta do modelo chega em fluxo (pensamento, texto e etapas); o botão do relatório, sem modelo, espera o
// turno inteiro. A fonte de uma resposta abre no visualizador, ao lado da conversa, ou no lugar do documento quando ele está aberto.
import { lazy, Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import {
  abrirConversa,
  enviarMensagem,
  listarConversas,
  montarRelatorio,
  obterConversa,
  obterRelatorioAtlas,
  responderCartao,
  type Conversa,
  type ConversasDaParceria,
  type EventoDoTurno,
  type FonteDaResposta,
  type RelatorioAtlas,
  type RespostaDoCartao,
  type StatusRelatorioAtlas,
} from '../api/atlas'
import { ErroApi } from '../api/cliente'
import { EVENTO_LLM_MUDOU } from '../api/debug'
import { Aviso } from '../componentes/Aviso'
import { LinkBotao } from '../componentes/Botao'
import { CabecalhoSecao } from '../componentes/CabecalhoSecao'
import { nomeCota } from '../componentes/Menu'
import { RenderizadorDeRelatorio } from '../componentes/relatorio/RenderizadorDeRelatorio'
import { useParceria } from '../paginas/parceria/ContextoParceria'
import { useSessao } from '../sessao/Sessao'
import { aplicar, SEM_RESPOSTA, type RespostaAoVivo } from './AoVivo'
import { AreaDaConversa } from './Conversa'
import { AvisoAtlas, Chip, ChipLink, LinhaBuscando, PainelLateral, PONTO_APLICACOES, PONTO_RASCUNHO, Tag } from './Elementos'
import { Inicio } from './Inicio'

// O leitor de PDF é pesado: só baixa quando alguém abre a primeira fonte.
const Visualizador = lazy(() => import('./Visualizador'))

/** O que a caixa do Atlas em Minha parceria pede ao abrir esta tela: uma pergunta para mandar ou o atalho do relatório. */
export type PedidoAoAtlas = { mensagem?: string; montar?: boolean }

type AbaLateral = 'conversa' | 'fontes'

/** Um turno: em fluxo, repassa cada evento; o botão do relatório, sem modelo, ignora os dois parâmetros. */
type Turno = (aoEvento: (evento: EventoDoTurno) => void, sinal: AbortSignal) => Promise<Conversa>

const SITUACAO: Record<StatusRelatorioAtlas, string> = {
  MONTANDO: 'Montando',
  AGUARDANDO_RESPOSTA: 'Esperando os números',
  PRONTO: 'Documento de trabalho',
  DESCARTADO: 'Descartado',
}

const EM_MONTAGEM: StatusRelatorioAtlas[] = ['MONTANDO', 'AGUARDANDO_RESPOSTA']

function mensagemDe(erro: unknown, padrao: string) {
  return erro instanceof ErroApi ? erro.message : padrao
}

/** A barra do topo da coluna do Atlas: caminho, título com a tag e as ações à direita. */
function BarraDoAtlas({ parceria, titulo, tag, acoes }: { parceria: { patrocinador: string }; titulo: string; tag?: ReactNode; acoes?: ReactNode }) {
  return (
    <header className="flex min-h-[72px] flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-white/10 py-3.5 pr-0 pl-1 sm:pr-5 sm:pl-7">
      <div className="min-w-0">
        <p className="truncate text-[11px] font-bold tracking-[0.12em] text-white/64 uppercase">
          Atlas&nbsp;&nbsp;/&nbsp;&nbsp;{parceria.patrocinador}&nbsp;&nbsp;/&nbsp;&nbsp;Patrocinador
        </p>
        <div className="mt-1 flex min-w-0 flex-wrap items-center gap-2.5">
          <h1 className="truncate text-base font-semibold text-white">{titulo}</h1>
          {tag}
        </div>
      </div>
      {acoes && <div className="flex flex-wrap items-center gap-2.5">{acoes}</div>}
    </header>
  )
}

/** O título da conversa: o pedido de relatório vira "Relatório da ativação"; senão, a primeira pergunta. */
function tituloDa(conversa: Conversa | null, relatorio: RelatorioAtlas | null) {
  if (relatorio) return 'Relatório da ativação'
  const primeira = conversa?.itens.find((item) => item.tipo === 'usuario')
  if (!primeira || primeira.tipo !== 'usuario') return 'Nova conversa'
  return primeira.texto.length > 60 ? `${primeira.texto.slice(0, 57)}…` : primeira.texto
}

export function PaginaAtlas() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const navegar = useNavigate()
  const local = useLocation()
  const { conversaId: idNaRota } = useParams()
  const conversaId = idNaRota && /^\d+$/.test(idNaRota) ? Number(idNaRota) : null
  const base = `/parceria/${parceria.id}`

  const [conversas, setConversas] = useState<ConversasDaParceria | null>(null)
  const [conversa, setConversa] = useState<Conversa | null>(null)
  const [relatorio, setRelatorio] = useState<RelatorioAtlas | null>(null)
  const [documentoAberto, setDocumentoAberto] = useState(false)
  const [abaLateral, setAbaLateral] = useState<AbaLateral>('conversa')
  // Abaixo de 1024 px não cabem as duas colunas: o modo documento alterna entre o documento e a conversa.
  const [vistaMovel, setVistaMovel] = useState<'documento' | 'conversa'>('documento')
  const [carregando, setCarregando] = useState(true)
  const [bloqueio, setBloqueio] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [pensando, setPensando] = useState(false)
  const [aoVivo, setAoVivo] = useState<RespostaAoVivo | null>(null)
  const [enviada, setEnviada] = useState<string | null>(null)
  const [texto, setTexto] = useState('')
  const [fonteAberta, setFonteAberta] = useState<FonteDaResposta | null>(null)
  /** O chip que abriu a fonte: o foco volta para ele ao fechar. */
  const origemDaFonte = useRef<HTMLElement | null>(null)
  /** A conversa que já está na tela: trocar a URL depois de um turno não a carrega de novo. */
  const carregada = useRef<number | null | undefined>(undefined)
  const pedidoAtendido = useRef(false)
  /** O turno em curso: sair da tela o cancela (o servidor termina e grava do mesmo jeito). */
  const turnoAtual = useRef<AbortController | null>(null)
  /** A conversa do turno em curso, que pode ter acabado de ser aberta. */
  const conversaDoTurno = useRef<number | null>(null)

  const llm = conversas?.llmDisponivel ?? false
  // Até a lista chegar, nada é oferecido; com o relatório desligado na API, os botões aparecem desabilitados.
  const relatorioDisponivel = conversas?.relatorioDisponivel ?? false

  /** Lê a conversa e o documento dela. Quem chama mostra os dois de uma vez, para a tela não piscar entre as leituras. */
  const lerConversa = useCallback(async (id: number, lista: ConversasDaParceria) => {
    const atual = await obterConversa(id)
    const resumo = lista.conversas.find((item) => item.id === id)?.relatorio ?? null
    return { atual, documento: resumo ? await obterRelatorioAtlas(resumo.id) : null }
  }, [])

  useEffect(() => {
    if (carregada.current === conversaId) return
    let ativo = true
    setCarregando(true)
    setFalha(null)
    setBloqueio(null)
    setFonteAberta(null)
    ;(async () => {
      try {
        const lista = await listarConversas(parceria.id)
        if (!ativo) return
        setConversas(lista)
        carregada.current = conversaId
        if (conversaId === null) {
          setConversa(null)
          setRelatorio(null)
          setDocumentoAberto(false)
          return
        }
        const { atual, documento } = await lerConversa(conversaId, lista)
        if (!ativo) return
        setConversa(atual)
        setRelatorio(documento)
        // O documento que ainda está sendo montado abre ao lado da conversa.
        if (documento && EM_MONTAGEM.includes(documento.status)) {
          setDocumentoAberto(true)
          setVistaMovel('documento')
        }
      } catch (erro) {
        if (!ativo) return
        if (erro instanceof ErroApi && erro.status === 403) setBloqueio(erro.message)
        else if (erro instanceof ErroApi && erro.status === 404) {
          carregada.current = undefined
          navegar(`${base}/atlas`, { replace: true })
        } else setFalha(mensagemDe(erro, 'Não foi possível abrir o Atlas.'))
      } finally {
        if (ativo) setCarregando(false)
      }
    })()
    return () => {
      ativo = false
    }
  }, [conversaId, parceria.id, base, navegar, lerConversa])

  // O botão de debug liga ou desliga o modelo: a tela relê se o chat está disponível.
  useEffect(() => {
    const aoMudar = () => {
      listarConversas(parceria.id)
        .then(setConversas)
        .catch(() => undefined)
    }
    window.addEventListener(EVENTO_LLM_MUDOU, aoMudar)
    return () => window.removeEventListener(EVENTO_LLM_MUDOU, aoMudar)
  }, [parceria.id])

  // Sair da tela no meio de uma resposta para de ler o fluxo.
  useEffect(() => () => turnoAtual.current?.abort(), [])

  /**
   * Depois de um turno: relê a conversa, a lista e o documento, e troca a resposta ao vivo pela gravada no mesmo passo.
   * Pedir um documento joga o chat para o lado.
   */
  async function aposTurno(id: number) {
    const antes = relatorio
    const lista = await listarConversas(parceria.id)
    const { atual, documento } = await lerConversa(id, lista)
    setConversas(lista)
    setConversa(atual)
    setRelatorio(documento)
    setAoVivo(null)
    setEnviada(null)
    if (documento && (documento.id !== antes?.id || documento.status !== antes?.status)) {
      setDocumentoAberto(true)
      setAbaLateral('conversa')
    }
    if (id !== conversaId) {
      carregada.current = id
      navegar(`${base}/atlas/${id}`, { replace: conversaId !== null })
    }
  }

  /**
   * Roda um turno e mostra a resposta enquanto ela chega. Com {@code devolverErro}, o erro de antes do fluxo volta a quem
   * chamou (o cartão o mostra junto dos campos); o que falha no meio do fluxo aparece na conversa, que é relida.
   */
  async function turno(fazer: Turno, { mensagem, devolverErro = false, emFluxo = true }: { mensagem?: string; devolverErro?: boolean; emFluxo?: boolean } = {}) {
    const controle = new AbortController()
    turnoAtual.current = controle
    conversaDoTurno.current = conversa?.conversaId ?? null
    let comecou = false
    setFalha(null)
    setPensando(true)
    // Sem resposta ao vivo, a conversa sabe que o turno é o do botão, que não passa pelo modelo.
    setAoVivo(emFluxo ? SEM_RESPOSTA : null)
    if (mensagem) setEnviada(mensagem)
    try {
      const resultado = await fazer((evento) => {
        comecou = true
        setAoVivo((atual) => aplicar(atual ?? SEM_RESPOSTA, evento))
      }, controle.signal)
      await aposTurno(resultado.conversaId)
    } catch (erro) {
      if (controle.signal.aborted) return
      if (devolverErro && !comecou) throw erro
      // Com o fluxo aberto, o servidor já gravou a pergunta: a conversa relida mostra o que ficou, e a pergunta não volta ao campo.
      const id = conversaDoTurno.current
      if (comecou && id !== null) await aposTurno(id).catch(() => undefined)
      else if (mensagem) setTexto(mensagem)
      setFalha(mensagemDe(erro, 'O Atlas não respondeu. Tente de novo.'))
    } finally {
      if (!controle.signal.aborted) {
        setPensando(false)
        setAoVivo(null)
        setEnviada(null)
      }
    }
  }

  function montar() {
    if (!relatorioDisponivel) return
    turno(() => (conversa ? montarRelatorio(conversa.conversaId) : abrirConversa(parceria.id, true)), { emFluxo: false })
  }

  function perguntar(pergunta: string) {
    const mensagem = pergunta.trim()
    if (!mensagem || pensando || !llm) return
    setTexto('')
    turno(
      async (aoEvento, sinal) => {
        const id = conversa?.conversaId ?? (await abrirConversa(parceria.id)).conversaId
        conversaDoTurno.current = id
        return enviarMensagem(id, mensagem, aoEvento, sinal)
      },
      { mensagem },
    )
  }

  /** O cartão respondido retoma o turno do Atlas, em fluxo como uma pergunta. */
  function responder(solicitacaoId: number, resposta: RespostaDoCartao) {
    return turno((aoEvento, sinal) => responderCartao(solicitacaoId, resposta, aoEvento, sinal), { devolverErro: true })
  }

  function abrirDocumento(aba: AbaLateral) {
    setDocumentoAberto(true)
    setAbaLateral(aba)
    setVistaMovel('documento')
  }

  function abrirFonte(fonte: FonteDaResposta) {
    origemDaFonte.current = document.activeElement as HTMLElement | null
    setFonteAberta(fonte)
  }

  const fecharFonte = useCallback(() => {
    setFonteAberta(null)
    origemDaFonte.current?.focus()
  }, [])

  // O pedido da caixa de Minha parceria roda uma vez, depois que a tela sabe se o chat está ligado.
  useEffect(() => {
    const pedido = local.state as PedidoAoAtlas | null
    if (!pedido || pedidoAtendido.current || carregando || !conversas) return
    pedidoAtendido.current = true
    navegar(local.pathname, { replace: true, state: null })
    if (pedido.montar) montar()
    else if (pedido.mensagem) {
      if (conversas.llmDisponivel) perguntar(pedido.mensagem)
      else setTexto(pedido.mensagem)
    }
    // As funções de turno mudam a cada render; o pedido só precisa da primeira leitura.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [carregando, conversas])

  if (usuario?.perfil === 'ADMIN_RBS') {
    return (
      <div data-secao="parceria" className="flex flex-col gap-4">
        <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo="Atlas" subtitulo="O assistente que monta o relatório de ativação da marca." />
        <Aviso tipo="restrito" titulo="O Atlas conversa com a marca">
          Os documentos que ele montou para {parceria.patrocinador} ficam em Relatório, abaixo do relatório oficial.
        </Aviso>
        <div>
          <LinkBotao to={`${base}/relatorio`} variante="secundario">
            Ver os relatórios do Atlas
          </LinkBotao>
        </div>
      </div>
    )
  }

  const inicio = conversa === null && !enviada
  const fluido = inicio && !carregando
  const conversaProps = {
    parceria,
    itens: conversa?.itens ?? [],
    enviada,
    pensando,
    aoVivo,
    falha,
    llm,
    relatorioDisponivel,
    debugDeFerramentas: conversa?.debugDeFerramentas ?? false,
    conversaDoDiagnostico: conversa?.diagnosticoDisponivel ? conversa.conversaId : null,
    texto,
    aoMudarTexto: setTexto,
    aoEnviar: () => perguntar(texto),
    aoMontar: montar,
    aoResponder: responder,
    aoAbrirDocumento: abrirDocumento,
    aoAbrirFonte: abrirFonte,
  }
  const acoesDaConversa = (
    <>
      {relatorioDisponivel && (
        <ChipLink to={`${base}/relatorio`} className="max-sm:hidden">
          Documentos
        </ChipLink>
      )}
      <ChipLink to={`${base}/atlas`} icone="ri-add-line" onClick={() => setTexto('')}>
        Nova conversa
      </ChipLink>
    </>
  )
  const modoDocumento = documentoAberto && relatorio !== null

  return (
    <div className="flex h-[calc(100dvh-6rem)] min-h-[560px] gap-4 text-white lg:h-[calc(100dvh-2rem)]">
      {/* O fundo cobre a tela toda, por trás do menu flutuante: fluido na tela inicial, grafite depois. */}
      <div aria-hidden="true" className={`fixed inset-0 -z-10 transition-colors duration-500 print:hidden ${fluido ? 'fundo-fluido' : 'bg-atlas'}`} />

      <section className={`flex min-w-0 flex-1 flex-col ${modoDocumento && vistaMovel === 'conversa' ? 'max-lg:hidden' : ''} ${modoDocumento && fonteAberta ? 'lg:hidden' : ''}`} aria-label="Atlas">
        {modoDocumento ? (
          <>
            <BarraDoAtlas
              parceria={parceria}
              titulo={relatorio.titulo}
              tag={<Tag ponto={PONTO_RASCUNHO}>{SITUACAO[relatorio.status]}</Tag>}
              acoes={
                <>
                  <Chip className="lg:hidden" icone="ri-chat-3-line" onClick={() => setVistaMovel('conversa')}>
                    Conversa
                  </Chip>
                  <Chip className="max-lg:hidden" icone="ri-arrow-left-line" onClick={() => setDocumentoAberto(false)}>
                    Voltar à conversa
                  </Chip>
                </>
              }
            />
            <div className="min-h-0 flex-1 overflow-y-auto px-1 py-6 sm:px-6">
              <div className="mx-auto flex w-full max-w-[680px] flex-col gap-4">
                <div className="flex flex-wrap justify-end gap-2.5">
                  <ChipLink to={`${base}/relatorios-atlas/${relatorio.id}`} icone="ri-external-link-line">
                    Página inteira
                  </ChipLink>
                  <ChipLink to={`${base}/relatorios-atlas/${relatorio.id}?imprimir=1`} variante="primario" icone="ri-printer-line">
                    Imprimir ou salvar PDF
                  </ChipLink>
                </div>
                {EM_MONTAGEM.includes(relatorio.status) && (
                  <AvisoAtlas tipo="info" titulo="O documento está sendo montado">
                    {relatorio.status === 'AGUARDANDO_RESPOSTA'
                      ? 'Responda ao cartão na conversa: os números entram no documento assim que você enviar.'
                      : 'Os números abaixo são os de agora e mudam até o Atlas terminar.'}
                  </AvisoAtlas>
                )}
                {pensando && <LinhaBuscando texto="Atualizando o documento…" />}
                <RenderizadorDeRelatorio relatorio={relatorio} compacto />
              </div>
            </div>
          </>
        ) : (
          <>
            <BarraDoAtlas
              parceria={parceria}
              titulo={inicio ? 'Nova conversa' : tituloDa(conversa, relatorio)}
              tag={
                inicio ? (
                  <Tag>
                    {parceria.patrocinador} · {nomeCota(parceria)}
                  </Tag>
                ) : (
                  <Tag ponto={PONTO_APLICACOES}>Aplicações da marca</Tag>
                )
              }
              acoes={inicio ? undefined : acoesDaConversa}
            />
            {carregando ? (
              <div className="grid flex-1 place-items-center">
                <LinhaBuscando texto="Abrindo o Atlas…" />
              </div>
            ) : bloqueio ? (
              <div className="mx-auto flex w-full max-w-[640px] flex-1 flex-col justify-center gap-4 px-4">
                <AvisoAtlas tipo="falha" titulo={bloqueio}>
                  O Atlas responde com os dados da aba Aplicações da marca, e ela está fechada para a sua parceria. Dúvidas: fale com o time B2B
                  da RBS.
                </AvisoAtlas>
                <Link to={base} className="self-start text-sm font-semibold text-white underline">
                  Voltar para Minha parceria
                </Link>
              </div>
            ) : inicio ? (
              <Inicio
                parceria={parceria}
                llm={llm}
                relatorioDisponivel={relatorioDisponivel}
                conversas={conversas?.conversas ?? []}
                texto={texto}
                aoMudarTexto={setTexto}
                aoEnviar={() => perguntar(texto)}
                aoPerguntar={perguntar}
                aoMontar={montar}
                pensando={pensando}
                falha={falha}
              />
            ) : (
              <AreaDaConversa {...conversaProps} />
            )}
          </>
        )}
      </section>

      {fonteAberta && (
        <Suspense fallback={<LinhaBuscando texto="Abrindo o documento…" />}>
          <Visualizador fonte={fonteAberta} aoFechar={fecharFonte} className={modoDocumento ? 'lg:flex-1' : 'lg:w-[46%] lg:max-w-[720px] lg:shrink-0'} />
        </Suspense>
      )}

      {modoDocumento && (
        <PainelLateral
          sobretitulo={abaLateral === 'conversa' ? 'Atlas' : 'Fontes citadas'}
          titulo={abaLateral === 'conversa' ? tituloDa(conversa, relatorio) : 'Documentos usados na análise'}
          aoFechar={() => setDocumentoAberto(false)}
          className={`w-full shrink-0 lg:w-[360px] xl:w-[420px] ${vistaMovel === 'documento' ? 'max-lg:hidden' : ''}`}
          acoes={
            <div className="flex flex-wrap gap-2" role="tablist" aria-label="Painel do Atlas">
              <Chip role="tab" aria-selected={abaLateral === 'conversa'} variante={abaLateral === 'conversa' ? 'primario' : 'acao'} onClick={() => setAbaLateral('conversa')}>
                Conversa
              </Chip>
              <Chip role="tab" aria-selected={abaLateral === 'fontes'} variante={abaLateral === 'fontes' ? 'primario' : 'acao'} onClick={() => setAbaLateral('fontes')}>
                Fontes
              </Chip>
              <Chip className="lg:hidden" icone="ri-file-text-line" onClick={() => setVistaMovel('documento')}>
                Documento
              </Chip>
            </div>
          }
        >
          {abaLateral === 'conversa' ? <AreaDaConversa {...conversaProps} compacta /> : <FontesDoDocumento relatorio={relatorio} aoVoltar={() => setAbaLateral('conversa')} />}
        </PainelLateral>
      )}
    </div>
  )
}

const ICONE_DA_FONTE: Record<string, string> = {
  cons: 'ri-database-2-line',
  lista: 'ri-list-check-2',
  dado: 'ri-user-line',
  calc: 'ri-calculator-line',
}

/** O painel "Documentos usados na análise" (Figma AT05 e AT06): cada fonte do documento, numerada como no texto. */
function FontesDoDocumento({ relatorio, aoVoltar }: { relatorio: RelatorioAtlas; aoVoltar: () => void }) {
  const fontes = relatorio.secoes.flatMap((secao) => secao.blocos).find((bloco) => bloco.tipo === 'fontes')
  const itens = fontes?.tipo === 'fontes' ? fontes.itens : []
  return (
    <div className="-mx-2 flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-2">
      {itens.length === 0 && <p className="text-sm text-white/64">O documento ainda não tem fontes.</p>}
      {itens.map((item, indice) => (
        <div key={item.ref} className="flex gap-3 rounded-[14px] border border-white/16 bg-white/8 py-3 pr-4 pl-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-white/10">
            <i className={`${ICONE_DA_FONTE[item.ref.split(':')[0]] ?? 'ri-file-text-line'} text-xl text-white`} aria-hidden="true" />
          </span>
          <div className="min-w-0 text-[13px]">
            <p className="font-semibold text-white">
              <span className="text-atlas-lilas">[{indice + 1}]</span> {item.rotulo}
              {item.valor !== null && <span className="font-medium text-white/90">: {item.valor}</span>}
            </p>
            <p className="mt-0.5 text-xs font-medium text-white/64">{item.origem}</p>
          </div>
        </div>
      ))}
      <div className="mt-auto flex flex-col gap-3 pt-2">
        <AvisoAtlas titulo="Documento de trabalho ≠ relatório oficial">A revisão e a disponibilização seguem o fluxo da RBS.</AvisoAtlas>
        <div>
          <Chip icone="ri-arrow-left-line" onClick={aoVoltar}>
            Voltar à conversa
          </Chip>
        </div>
      </div>
    </div>
  )
}
