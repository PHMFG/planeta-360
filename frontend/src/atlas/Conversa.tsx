// A conversa do Atlas (Figma AT01 e AT04): a pergunta de quem usa à direita, a resposta do modelo com o orbe à esquerda, o
// cartão de perguntas e o documento, e o campo de digitação embaixo. A mesma conversa ocupa a coluna inteira ou o painel ao
// lado da pré-visualização do documento (compacta). [ATLAS]
// Só o que o modelo de linguagem escreveu aparece como fala do Atlas. O que o portal faz sozinho (o botão "Montar relatório",
// sem modelo) aparece como passo do sistema, sem orbe; e, sem modelo, não há campo de chat.
// A resposta do modelo chega em tempo real: o tempo pensando com o resumo do raciocínio, o texto e o que ele está fazendo; no
// fim do turno, a conversa gravada toma o lugar da versão ao vivo. Com o debug ligado no servidor, as ferramentas que o modelo
// usou aparecem na ordem em que rodaram. Com o relatório desligado na API, o botão dele aparece desabilitado.
import { useEffect, useRef } from 'react'
import type { ItemDaConversa, RespostaDoCartao } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import type { RespostaAoVivo } from './AoVivo'
import { CartaoDocumento } from './CartaoDocumento'
import { CartaoSolicitacao } from './CartaoSolicitacao'
import { Compositor } from './Compositor'
import { BotaoDiagnostico } from './Diagnostico'
import { AvisoAtlas, BlocoDoAtlas, BlocoDoSistema, Chip, LinhaBuscando, MensagemVoce, TextoDoAtlas, TextoDoSistema } from './Elementos'
import { FerramentaDebug, SemFerramentaDebug } from './FerramentaDebug'
import { Raciocinio } from './Raciocinio'

/** Na tela, pensamentos seguidos viram uma caixa só, com o resumo de cada um. */
type ItemDaResposta = Exclude<ItemDaConversa, { tipo: 'usuario' | 'raciocinio' }> | { tipo: 'raciocinio'; resumos: string[] }
type Grupo = { tipo: 'voce'; texto: string } | { tipo: 'resposta'; itens: ItemDaResposta[] }

/**
 * Cada fala de quem usa abre um grupo; o que vem em seguida (pensamento, texto, cartão, documento) é uma resposta só.
 * Pensamentos seguidos (o modelo pensou, consultou uma ferramenta e pensou de novo) viram uma caixa só.
 */
function agrupar(itens: ItemDaConversa[]): Grupo[] {
  const grupos: Grupo[] = []
  for (const item of itens) {
    if (item.tipo === 'usuario') {
      grupos.push({ tipo: 'voce', texto: item.texto })
      continue
    }
    const novo: ItemDaResposta = item.tipo === 'raciocinio' ? { tipo: 'raciocinio', resumos: [item.resumo] } : item
    const ultimo = grupos.at(-1)
    if (ultimo?.tipo !== 'resposta') {
      grupos.push({ tipo: 'resposta', itens: [novo] })
      continue
    }
    const anterior = ultimo.itens.at(-1)
    if (novo.tipo === 'raciocinio' && anterior?.tipo === 'raciocinio') ultimo.itens[ultimo.itens.length - 1] = { tipo: 'raciocinio', resumos: [...anterior.resumos, ...novo.resumos] }
    else ultimo.itens.push(novo)
  }
  return grupos
}

/** Sem o campo, o servidor é de antes da marcação e todo texto conta como do modelo. O pensamento só existe com o modelo. */
const doModelo = (item: ItemDaResposta) => item.tipo === 'raciocinio' || item.tipo === 'ferramenta' || (item.tipo === 'texto' && item.porModelo !== false)

type Props = {
  parceria: ParceriaResumo
  itens: ItemDaConversa[]
  /** A mensagem que acabou de sair, mostrada enquanto a resposta não chega. */
  enviada: string | null
  pensando: boolean
  /** A resposta do modelo enquanto chega; nula fora do turno. */
  aoVivo: RespostaAoVivo | null
  falha: string | null
  llm: boolean
  /** Falso com o relatório desligado na API: o botão aparece, desabilitado. */
  relatorioDisponivel: boolean
  /** Com o debug ligado no servidor, a resposta sem ferramenta também diz isso. */
  debugDeFerramentas: boolean
  /** A conversa, quando esta pessoa pode abrir o diagnóstico de cada resposta. */
  conversaDoDiagnostico: number | null
  texto: string
  aoMudarTexto: (texto: string) => void
  aoEnviar: () => void
  aoMontar: () => void
  aoResponder: (solicitacaoId: number, resposta: RespostaDoCartao) => Promise<void>
  aoAbrirDocumento: (aba: 'conversa' | 'fontes') => void
  compacta?: boolean
}

export function AreaDaConversa({
  parceria,
  itens,
  enviada,
  pensando,
  aoVivo,
  falha,
  llm,
  relatorioDisponivel,
  debugDeFerramentas,
  conversaDoDiagnostico,
  texto,
  aoMudarTexto,
  aoEnviar,
  aoMontar,
  aoResponder,
  aoAbrirDocumento,
  compacta = false,
}: Props) {
  const fim = useRef<HTMLDivElement>(null)
  const rolagem = useRef<HTMLDivElement>(null)
  /** Falso quando a pessoa subiu para ler: a resposta que chega não puxa a tela para baixo. */
  const noFim = useRef(true)
  const cartaoAberto = itens.some((item) => item.tipo === 'cartao' && item.status === 'PENDENTE')

  // O fim da conversa fica à vista a cada item novo e enquanto a resposta chega.
  useEffect(() => {
    if (noFim.current) fim.current?.scrollIntoView({ block: 'end' })
  }, [itens, pensando, aoVivo])

  // Uma pergunta nova sempre leva ao fim.
  useEffect(() => {
    if (!enviada) return
    noFim.current = true
    fim.current?.scrollIntoView({ block: 'end' })
  }, [enviada])

  function aoRolar() {
    const area = rolagem.current
    if (area) noFim.current = area.scrollHeight - area.scrollTop - area.clientHeight < 80
  }

  const largura = compacta ? 'w-full' : 'mx-auto w-full max-w-[760px]'
  const espaco = compacta ? 'gap-5' : 'gap-[26px]'

  function conteudo(item: ItemDaResposta, posicao: number) {
    switch (item.tipo) {
      case 'raciocinio':
        return <Raciocinio key={posicao} resumos={item.resumos} />
      case 'ferramenta':
        return <FerramentaDebug key={posicao} ferramenta={item} />
      case 'texto':
        return doModelo(item) ? <TextoDoAtlas key={posicao}>{item.texto}</TextoDoAtlas> : <TextoDoSistema key={posicao}>{item.texto}</TextoDoSistema>
      case 'aviso':
        return <TextoDoSistema key={posicao}>{item.texto}</TextoDoSistema>
      case 'cartao':
        return <CartaoSolicitacao key={posicao} item={item} aoResponder={aoResponder} />
      case 'relatorio_pronto':
        return <CartaoDocumento key={posicao} parceria={parceria} relatorioId={item.relatorioId} titulo={item.titulo} aoAbrir={aoAbrirDocumento} />
    }
  }

  /** O botão do diagnóstico da resposta. Um grupo pode ter mais de um turno (a resposta de um cartão): vale o último. */
  function diagnosticoDe(grupo: ItemDaResposta[]) {
    const turno = grupo.map((item) => ('turno' in item ? item.turno : undefined)).filter(Boolean).at(-1)
    return conversaDoDiagnostico !== null && turno ? <BotaoDiagnostico conversaId={conversaDoDiagnostico} turno={turno} /> : null
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div ref={rolagem} onScroll={aoRolar} className={`min-h-0 flex-1 overflow-y-auto ${compacta ? '-mx-2 px-2' : 'px-4 pt-6 sm:px-8'}`}>
        <div className={`${largura} flex flex-col ${espaco} pb-4`}>
          {/* Só a conversa gravada é anunciada ao leitor de tela; o texto ao vivo, pedaço a pedaço, não. */}
          <div className={`flex flex-col ${espaco} empty:hidden`} aria-live="polite">
            {agrupar(itens).map((grupo, indice) =>
              grupo.tipo === 'voce' ? (
                <MensagemVoce key={indice} texto={grupo.texto} />
              ) : grupo.itens.some(doModelo) ? (
                <BlocoDoAtlas key={indice} compacto={compacta}>
                  {grupo.itens.map(conteudo)}
                  {debugDeFerramentas && !grupo.itens.some((item) => item.tipo === 'ferramenta') && <SemFerramentaDebug />}
                  {diagnosticoDe(grupo.itens)}
                </BlocoDoAtlas>
              ) : (
                <BlocoDoSistema key={indice}>
                  {grupo.itens.map(conteudo)}
                  {diagnosticoDe(grupo.itens)}
                </BlocoDoSistema>
              ),
            )}
          </div>
          {enviada && <MensagemVoce texto={enviada} />}
          {pensando && aoVivo && aoVivo.partes.length > 0 ? (
            <BlocoDoAtlas compacto={compacta}>
              {aoVivo.partes.map((parte, posicao) =>
                parte.tipo === 'raciocinio' ? (
                  <Raciocinio key={posicao} resumos={parte.resumos} desde={parte.desde} ate={parte.ate} />
                ) : parte.tipo === 'ferramenta' ? (
                  <FerramentaDebug key={posicao} ferramenta={parte} />
                ) : (
                  <TextoDoAtlas key={posicao} digitando={posicao === aoVivo.partes.length - 1 && !aoVivo.etapa}>
                    {parte.texto}
                  </TextoDoAtlas>
                ),
              )}
              {aoVivo.etapa && <LinhaBuscando texto={aoVivo.etapa} />}
            </BlocoDoAtlas>
          ) : (
            pensando && <LinhaBuscando texto={aoVivo ? (aoVivo.etapa ?? (llm ? 'O Atlas está respondendo…' : 'Montando o relatório…')) : 'Montando o relatório…'} />
          )}
          {falha && <AvisoAtlas tipo="falha" titulo={falha} />}
          <div ref={fim} />
        </div>
      </div>

      <div className={`${largura} flex flex-col gap-2.5 ${compacta ? 'pt-3' : 'px-4 pb-1 sm:px-0'}`}>
        <div>
          <Chip
            icone="ri-file-chart-line"
            carregando={pensando && !aoVivo}
            disabled={!relatorioDisponivel || pensando || cartaoAberto}
            title={relatorioDisponivel ? undefined : 'O relatório não está disponível nesta versão'}
            onClick={aoMontar}
          >
            Montar relatório da ativação{!relatorioDisponivel && ' · em breve'}
          </Chip>
        </div>
        {llm && <Compositor valor={texto} aoMudar={aoMudarTexto} aoEnviar={aoEnviar} rotuloEnviar="Enviar" ocupado={pensando} habilitado compacto={compacta} />}
        {!llm && !relatorioDisponivel && <AvisoAtlas titulo="O Atlas está sem o modelo de linguagem agora">Dá para ler a conversa, mas não enviar mensagens. Tente de novo mais tarde.</AvisoAtlas>}
        <p className="pb-1 text-center text-xs text-white/55 max-sm:hidden">Só os dados desta parceria · o Atlas não escreve números por conta própria</p>
      </div>
    </div>
  )
}
