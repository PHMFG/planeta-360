// A conversa do Atlas (Figma AT01 e AT04): a pergunta de quem usa à direita, a resposta do modelo com o orbe à esquerda, o
// cartão de perguntas e o documento, e o campo de digitação embaixo. A mesma conversa ocupa a coluna inteira ou o painel ao
// lado da pré-visualização do documento (compacta). [ATLAS]
// Só o que o modelo de linguagem escreveu aparece como fala do Atlas. O que o portal faz sozinho (o botão "Montar relatório",
// sem modelo) aparece como passo do sistema, sem orbe; e, sem modelo, não há campo de chat.
import { useEffect, useRef } from 'react'
import type { Conversa, ItemDaConversa } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import { CartaoDocumento } from './CartaoDocumento'
import { CartaoSolicitacao } from './CartaoSolicitacao'
import { Compositor } from './Compositor'
import { AvisoAtlas, BlocoDoAtlas, BlocoDoSistema, Chip, LinhaBuscando, MensagemVoce, TextoDoAtlas, TextoDoSistema } from './Elementos'

type ItemDaResposta = Exclude<ItemDaConversa, { tipo: 'usuario' }>
type Grupo = { tipo: 'voce'; texto: string } | { tipo: 'resposta'; itens: ItemDaResposta[] }

/** Cada fala de quem usa abre um grupo; o que vem em seguida (texto, cartão, documento) é uma resposta só. */
function agrupar(itens: ItemDaConversa[]): Grupo[] {
  const grupos: Grupo[] = []
  for (const item of itens) {
    if (item.tipo === 'usuario') grupos.push({ tipo: 'voce', texto: item.texto })
    else {
      const ultimo = grupos.at(-1)
      if (ultimo?.tipo === 'resposta') ultimo.itens.push(item)
      else grupos.push({ tipo: 'resposta', itens: [item] })
    }
  }
  return grupos
}

/** Sem o campo, o servidor é de antes da marcação e todo texto conta como do modelo. */
const doModelo = (item: ItemDaResposta) => item.tipo === 'texto' && item.porModelo !== false

type Props = {
  parceria: ParceriaResumo
  itens: ItemDaConversa[]
  /** A mensagem que acabou de sair, mostrada enquanto a resposta não chega. */
  enviada: string | null
  pensando: boolean
  falha: string | null
  llm: boolean
  texto: string
  aoMudarTexto: (texto: string) => void
  aoEnviar: () => void
  aoMontar: () => void
  aoResponder: (turno: Conversa) => void
  aoAbrirDocumento: (aba: 'conversa' | 'fontes') => void
  compacta?: boolean
}

export function AreaDaConversa({
  parceria,
  itens,
  enviada,
  pensando,
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
  const cartaoAberto = itens.some((item) => item.tipo === 'cartao' && item.status === 'PENDENTE')

  // O fim da conversa fica à vista a cada item novo e enquanto a resposta não chega.
  useEffect(() => {
    fim.current?.scrollIntoView({ block: 'end' })
  }, [itens, enviada, pensando])

  const largura = compacta ? 'w-full' : 'mx-auto w-full max-w-[760px]'

  function conteudo(item: ItemDaResposta, posicao: number) {
    switch (item.tipo) {
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
      <div className={`min-h-0 flex-1 overflow-y-auto ${compacta ? '-mx-2 px-2' : 'px-4 pt-6 sm:px-8'}`} aria-live="polite">
        <div className={`${largura} flex flex-col ${compacta ? 'gap-5' : 'gap-[26px]'} pb-4`}>
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
          {enviada && <MensagemVoce texto={enviada} />}
          {pensando && <LinhaBuscando texto={llm ? 'O Atlas está respondendo…' : 'Montando o relatório…'} />}
          {falha && <AvisoAtlas tipo="falha" titulo={falha} />}
          <div ref={fim} />
        </div>
      </div>

      <div className={`${largura} flex flex-col gap-2.5 ${compacta ? 'pt-3' : 'px-4 pb-1 sm:px-0'}`}>
        <div>
          <Chip icone="ri-file-chart-line" carregando={pensando} disabled={cartaoAberto} onClick={aoMontar}>
            Montar relatório da ativação
          </Chip>
        </div>
        {llm && <Compositor valor={texto} aoMudar={aoMudarTexto} aoEnviar={aoEnviar} rotuloEnviar="Enviar" ocupado={pensando} habilitado compacto={compacta} />}
        <p className="pb-1 text-center text-xs text-white/55 max-sm:hidden">Só os dados desta parceria · o Atlas não escreve números por conta própria</p>
      </div>
    </div>
  )
}
