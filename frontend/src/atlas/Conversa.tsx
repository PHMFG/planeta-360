// A conversa do Atlas (Figma AT01 e AT04): a pergunta de quem usa à direita, a resposta do Atlas com o orbe à esquerda, o cartão
// de perguntas e o documento dentro da resposta, e o campo de digitação embaixo. A mesma conversa ocupa a coluna inteira ou o
// painel ao lado da pré-visualização do documento (compacta). [ATLAS]
import { useEffect, useRef, type ReactNode } from 'react'
import type { Conversa, ItemDaConversa } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import { CartaoDocumento } from './CartaoDocumento'
import { CartaoSolicitacao } from './CartaoSolicitacao'
import { Compositor } from './Compositor'
import { AvisoAtlas, BlocoDoAtlas, Chip, ChipLink, LinhaBuscando, MensagemVoce, PONTO_APLICACOES, TextoDoAtlas } from './Elementos'

type Grupo = { tipo: 'voce'; texto: string } | { tipo: 'atlas'; itens: Exclude<ItemDaConversa, { tipo: 'usuario' }>[] }

/** Cada fala de quem usa abre um grupo; o que o Atlas devolve em seguida (texto, cartão, documento) é uma resposta só. */
function agrupar(itens: ItemDaConversa[]): Grupo[] {
  const grupos: Grupo[] = []
  for (const item of itens) {
    if (item.tipo === 'usuario') grupos.push({ tipo: 'voce', texto: item.texto })
    else {
      const ultimo = grupos.at(-1)
      if (ultimo?.tipo === 'atlas') ultimo.itens.push(item)
      else grupos.push({ tipo: 'atlas', itens: [item] })
    }
  }
  return grupos
}

type Props = {
  parceria: ParceriaResumo
  itens: ItemDaConversa[]
  /** A mensagem que acabou de sair, mostrada enquanto o Atlas responde. */
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
  /** O que vai acima das mensagens (a linha de contexto na coluna inteira). */
  topo?: ReactNode
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
  topo,
}: Props) {
  const fim = useRef<HTMLDivElement>(null)
  const cartaoAberto = itens.some((item) => item.tipo === 'cartao' && item.status === 'PENDENTE')

  // O fim da conversa fica à vista a cada item novo e enquanto o Atlas responde.
  useEffect(() => {
    fim.current?.scrollIntoView({ block: 'end' })
  }, [itens, enviada, pensando])

  const largura = compacta ? 'w-full' : 'mx-auto w-full max-w-[760px]'

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={`min-h-0 flex-1 overflow-y-auto ${compacta ? '-mx-2 px-2' : 'px-4 pt-6 sm:px-8'}`} aria-live="polite">
        <div className={`${largura} flex flex-col ${compacta ? 'gap-5' : 'gap-[26px]'} pb-4`}>
          {topo}
          {agrupar(itens).map((grupo, indice) =>
            grupo.tipo === 'voce' ? (
              <MensagemVoce key={indice} texto={grupo.texto} />
            ) : (
              <BlocoDoAtlas key={indice} compacto={compacta}>
                {grupo.itens.map((item, posicao) => {
                  switch (item.tipo) {
                    case 'texto':
                      return <TextoDoAtlas key={posicao}>{item.texto}</TextoDoAtlas>
                    case 'aviso':
                      return <AvisoAtlas key={posicao} tipo="info" titulo={item.texto} />
                    case 'cartao':
                      return <CartaoSolicitacao key={posicao} item={item} aoResponder={aoResponder} />
                    case 'relatorio_pronto':
                      return (
                        <CartaoDocumento key={posicao} parceria={parceria} relatorioId={item.relatorioId} titulo={item.titulo} aoAbrir={aoAbrirDocumento} />
                      )
                  }
                })}
              </BlocoDoAtlas>
            ),
          )}
          {enviada && <MensagemVoce texto={enviada} />}
          {pensando && <LinhaBuscando />}
          {falha && <AvisoAtlas tipo="falha" titulo={falha} />}
          <div ref={fim} />
        </div>
      </div>

      <div className={`${largura} flex flex-col gap-2.5 ${compacta ? 'pt-3' : 'px-4 pb-1 sm:px-0'}`}>
        {/* No celular os atalhos ficam numa linha só, com rolagem lateral, para sobrar espaço para a conversa. */}
        <div className="flex gap-2.5 overflow-x-auto sm:flex-wrap">
          <Chip icone="ri-file-chart-line" carregando={pensando} disabled={cartaoAberto} onClick={aoMontar}>
            Montar relatório da ativação
          </Chip>
          {!compacta && (
            <ChipLink to={`/parceria/${parceria.id}/aplicacoes`} ponto={PONTO_APLICACOES}>
              Informar os números
            </ChipLink>
          )}
        </div>
        <Compositor
          valor={texto}
          aoMudar={aoMudarTexto}
          aoEnviar={aoEnviar}
          rotuloEnviar="Enviar"
          ocupado={pensando}
          habilitado={llm}
          compacto={compacta}
        />
        <p className="pb-1 text-center text-xs text-white/55 max-sm:hidden">Só os dados desta parceria · o Atlas não escreve números por conta própria</p>
      </div>
    </div>
  )
}
