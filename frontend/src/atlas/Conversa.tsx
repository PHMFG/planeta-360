// A conversa do Atlas (Figma AT01 e AT04): a pergunta de quem usa à direita, a resposta do modelo com o orbe à esquerda, o
// cartão de perguntas e o documento, e o campo de digitação embaixo. A mesma conversa ocupa a coluna inteira ou o painel ao
// lado da pré-visualização do documento (compacta). [ATLAS]
// Só o que o modelo de linguagem escreveu aparece como fala do Atlas. O que o portal faz sozinho (o botão "Montar relatório",
// sem modelo) aparece como passo do sistema, sem orbe; e, sem modelo, não há campo de chat.
// A resposta do modelo chega em tempo real: o tempo pensando com o resumo do raciocínio, o texto e o que ele está fazendo; no
// fim do turno, a conversa gravada toma o lugar da versão ao vivo.
import { useEffect, useRef } from 'react'
import type { ItemDaConversa, RespostaDoCartao } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import type { RespostaAoVivo } from './AoVivo'
import { CartaoDocumento } from './CartaoDocumento'
import { CartaoSolicitacao } from './CartaoSolicitacao'
import { Compositor } from './Compositor'
import { AvisoAtlas, BlocoDoAtlas, BlocoDoSistema, Chip, LinhaBuscando, MensagemVoce, TextoDoAtlas, TextoDoSistema } from './Elementos'
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
const doModelo = (item: ItemDaResposta) => item.tipo === 'raciocinio' || (item.tipo === 'texto' && item.porModelo !== false)

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
                </BlocoDoAtlas>
              ) : (
                <BlocoDoSistema key={indice}>{grupo.itens.map(conteudo)}</BlocoDoSistema>
              ),
            )}
          </div>
          {enviada && <MensagemVoce texto={enviada} />}
          {pensando && aoVivo && aoVivo.partes.length > 0 ? (
            <BlocoDoAtlas compacto={compacta}>
              {aoVivo.partes.map((parte, posicao) =>
                parte.tipo === 'raciocinio' ? (
                  <Raciocinio key={posicao} resumos={parte.resumos} desde={parte.desde} ate={parte.ate} />
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
          <Chip icone="ri-file-chart-line" carregando={pensando && !aoVivo} disabled={pensando || cartaoAberto} onClick={aoMontar}>
            Montar relatório da ativação
          </Chip>
        </div>
        {llm && <Compositor valor={texto} aoMudar={aoMudarTexto} aoEnviar={aoEnviar} rotuloEnviar="Enviar" ocupado={pensando} habilitado compacto={compacta} />}
        <p className="pb-1 text-center text-xs text-white/55 max-sm:hidden">Só os dados desta parceria · o Atlas não escreve números por conta própria</p>
      </div>
    </div>
  )
}
