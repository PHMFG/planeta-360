// A tela inicial do Atlas (Figma AT00, 199:1693): a saudação sobre o fundo fluido, o campo, as sugestões e as conversas
// recentes. Só aparece o que funciona de verdade: o relatório da ativação sempre; o campo e as perguntas só com o modelo de
// linguagem ligado. Atalhos que são só links para outras telas não se passam por sugestão do assistente. [ATLAS]
import { Link } from 'react-router'
import { NOME_STATUS_ATLAS, type ConversaResumo } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import { Compositor } from './Compositor'
import { AvisoAtlas } from './Elementos'

const CARTAO =
  'flex w-full items-start gap-3 rounded-2xl border border-white/14 bg-white/8 p-3.5 text-left backdrop-blur-md transition-colors hover:bg-white/14 disabled:cursor-not-allowed disabled:opacity-60 sm:w-[232px]'

function ConteudoSugestao({ icone, titulo, assunto }: { icone: string; titulo: string; assunto: string }) {
  return (
    <>
      <span className="grid size-9 shrink-0 place-items-center rounded-[10px] bg-white/10">
        <i className={`${icone} text-lg text-white`} aria-hidden="true" />
      </span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm leading-[1.3] font-semibold text-white">{titulo}</span>
        <span className="text-xs font-medium text-white/60">{assunto}</span>
      </span>
    </>
  )
}

/** "hoje", "ontem" ou a data, como nas conversas recentes do Figma. */
function quando(iso: string) {
  const dia = (data: Date) => data.toLocaleDateString('pt-BR', { timeZone: 'America/Sao_Paulo' })
  const agora = new Date()
  const ontem = new Date(agora.getTime() - 86_400_000)
  const alvo = new Date(iso)
  if (dia(alvo) === dia(agora)) return 'hoje'
  if (dia(alvo) === dia(ontem)) return 'ontem'
  return dia(alvo)
}

type Props = {
  parceria: ParceriaResumo
  llm: boolean
  /** Falso com o relatório desligado na API: o cartão aparece, desabilitado. */
  relatorioDisponivel: boolean
  conversas: ConversaResumo[]
  texto: string
  aoMudarTexto: (texto: string) => void
  aoEnviar: () => void
  aoPerguntar: (pergunta: string) => void
  aoMontar: () => void
  pensando: boolean
  falha: string | null
}

export function Inicio({ parceria, llm, relatorioDisponivel, conversas, texto, aoMudarTexto, aoEnviar, aoPerguntar, aoMontar, pensando, falha }: Props) {
  const base = `/parceria/${parceria.id}`

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-4 py-8 sm:px-8">
      <div className="m-auto flex w-full max-w-[720px] flex-col items-center gap-4">
        <h1 className="text-center text-[30px] leading-tight font-semibold tracking-[-0.01em] text-white sm:text-[40px]">Olá! Como posso ajudar?</h1>
        <p className="text-center text-base text-white/80">
          {!relatorioDisponivel
            ? llm
              ? 'Pergunte sobre os números da sua parceria ou sobre os documentos do evento.'
              : 'O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.'
            : llm
              ? 'Monte o relatório da sua ativação ou pergunte sobre os números da parceria e os documentos do evento.'
              : 'Monte o relatório da sua ativação com os números do portal. As perguntas em texto voltam quando o modelo de linguagem estiver ligado.'}
        </p>
        {llm && (
          <div className="mt-4 w-full">
            <Compositor valor={texto} aoMudar={aoMudarTexto} aoEnviar={aoEnviar} rotuloEnviar="Iniciar chat" ocupado={pensando} habilitado focarAoAbrir />
          </div>
        )}
        {falha && (
          <div className="w-full">
            <AvisoAtlas tipo="falha" titulo={falha} />
          </div>
        )}

        {llm && (
          <div className="mt-6 text-center">
            <p className="text-[11px] font-bold tracking-[0.12em] text-white/72 uppercase">Por onde começamos?</p>
            <p className="mt-1 text-xs text-white/72">Escolha um assunto ou escreva sua pergunta.</p>
          </div>
        )}
        <div className={`flex w-full flex-wrap justify-center gap-3 ${llm ? '' : 'mt-4'}`}>
          <button type="button" className={CARTAO} onClick={aoMontar} disabled={pensando || !relatorioDisponivel}>
            <ConteudoSugestao icone="ri-file-chart-line" titulo="Gerar relatório da ativação" assunto={relatorioDisponivel ? 'Documento para revisão' : 'Em breve'} />
          </button>
          {llm && (
            <>
              <button type="button" className={CARTAO} onClick={() => aoPerguntar('Até quando preciso informar os geradores na Arena?')} disabled={pensando}>
                <ConteudoSugestao icone="ri-file-search-line" titulo="Até quando informo os geradores?" assunto="Documentos do evento" />
              </button>
              <button type="button" className={CARTAO} onClick={() => aoPerguntar('Quantas aplicações de marca já foram comprovadas?')} disabled={pensando}>
                <ConteudoSugestao icone="ri-checkbox-circle-line" titulo="Quantas aplicações foram comprovadas?" assunto="Aplicações da marca" />
              </button>
              {relatorioDisponivel ? (
                <button type="button" className={CARTAO} onClick={() => aoPerguntar('Que números ainda faltam para o relatório da nossa ativação?')} disabled={pensando}>
                  <ConteudoSugestao icone="ri-question-line" titulo="O que falta para o relatório?" assunto="Aplicações da marca" />
                </button>
              ) : (
                <button type="button" className={CARTAO} onClick={() => aoPerguntar('Que números da nossa ativação ainda não foram informados?')} disabled={pensando}>
                  <ConteudoSugestao icone="ri-question-line" titulo="Que números ainda faltam?" assunto="Aplicações da marca" />
                </button>
              )}
            </>
          )}
        </div>

        {conversas.length > 0 && (
          <div className="mt-4 flex w-full flex-col gap-3">
            <p className="text-[11px] font-bold tracking-[0.12em] text-white/72 uppercase">Conversas recentes</p>
            <div className="grid gap-3 sm:grid-cols-2">
              {conversas.slice(0, 4).map((conversa) => (
                <Link
                  key={conversa.id}
                  to={`${base}/atlas/${conversa.id}`}
                  className="flex items-center gap-3 rounded-[14px] border border-white/14 bg-white/8 px-3.5 py-3 transition-colors hover:bg-white/14"
                >
                  <i className="ri-chat-3-line text-lg text-white" aria-hidden="true" />
                  <span className="flex min-w-0 flex-col">
                    <span className="truncate text-[13px] font-semibold text-white">
                      {conversa.relatorio ? `Relatório da ativação · ${NOME_STATUS_ATLAS[conversa.relatorio.status].toLowerCase()}` : 'Conversa com o Atlas'}
                    </span>
                    <span className="truncate text-[11px] font-medium text-white/62">{quando(conversa.atualizadaEm)}</span>
                  </span>
                </Link>
              ))}
            </div>
          </div>
        )}
      </div>
      <p className="pt-6 text-center text-xs text-white/55">Só os dados desta parceria · todo número do Atlas vem de uma fonte</p>
    </div>
  )
}
