// A tela inicial do Atlas (Figma AT00, 199:1693): a saudação sobre o fundo fluido, o campo, "Por onde começamos?" e as
// conversas recentes. As sugestões seguem o desenho, mas só oferecem o que o Atlas faz de verdade nesta versão: o relatório
// da ativação, as perguntas sobre ele (com o modelo de linguagem ligado) e os atalhos para as telas do portal. [ATLAS]
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { NOME_STATUS_ATLAS, type ConversaResumo } from '../api/atlas'
import type { ParceriaResumo } from '../api/tipos'
import { Compositor } from './Compositor'
import { AvisoAtlas } from './Elementos'

const CARTAO =
  'flex w-full items-start gap-3 rounded-2xl border border-white/14 bg-white/8 p-3.5 text-left backdrop-blur-md transition-colors hover:bg-white/14 sm:w-[232px]'

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
  conversas: ConversaResumo[]
  texto: string
  aoMudarTexto: (texto: string) => void
  aoEnviar: () => void
  aoPerguntar: (pergunta: string) => void
  aoMontar: () => void
  pensando: boolean
  falha: string | null
  avisos?: ReactNode
}

export function Inicio({ parceria, llm, conversas, texto, aoMudarTexto, aoEnviar, aoPerguntar, aoMontar, pensando, falha, avisos }: Props) {
  const base = `/parceria/${parceria.id}`

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-4 py-8 sm:px-8">
      <div className="m-auto flex w-full max-w-[720px] flex-col items-center gap-4">
        <h1 className="text-center text-[30px] leading-tight font-semibold tracking-[-0.01em] text-white sm:text-[40px]">Olá! Como posso ajudar?</h1>
        <p className="text-center text-base text-white/80">Monte o relatório da sua ativação e entenda o que falta na sua parceria.</p>
        <div className="mt-4 w-full">
          <Compositor valor={texto} aoMudar={aoMudarTexto} aoEnviar={aoEnviar} rotuloEnviar="Iniciar chat" ocupado={pensando} habilitado={llm} focarAoAbrir />
        </div>
        {!llm && (
          <div className="w-full">
            <AvisoAtlas tipo="info" titulo="O chat está desligado agora">
              O relatório da ativação sai do mesmo jeito: use “Gerar relatório da ativação”, logo abaixo.
            </AvisoAtlas>
          </div>
        )}
        {avisos}
        {falha && (
          <div className="w-full">
            <AvisoAtlas tipo="falha" titulo={falha} />
          </div>
        )}

        <div className="mt-6 text-center">
          <p className="text-[11px] font-bold tracking-[0.12em] text-white/72 uppercase">Por onde começamos?</p>
          <p className="mt-1 text-xs text-white/72">Escolha um assunto ou escreva sua pergunta.</p>
        </div>
        <div className="flex w-full flex-wrap justify-center gap-3">
          <button type="button" className={CARTAO} onClick={aoMontar} disabled={pensando}>
            <ConteudoSugestao icone="ri-globe-fill" titulo="Gerar relatório da ativação" assunto="Documento para revisão" />
          </button>
          {llm && (
            <>
              <button type="button" className={CARTAO} onClick={() => aoPerguntar('O que falta para o relatório da nossa ativação?')} disabled={pensando}>
                <ConteudoSugestao icone="ri-palette-line" titulo="O que falta para o relatório?" assunto="Aplicações da marca" />
              </button>
              <button type="button" className={CARTAO} onClick={() => aoPerguntar('Quantas aplicações de marca já foram comprovadas?')} disabled={pensando}>
                <ConteudoSugestao icone="ri-checkbox-circle-line" titulo="Quantas aplicações foram comprovadas?" assunto="Aplicações da marca" />
              </button>
            </>
          )}
          <Link to={`${base}/aplicacoes`} className={CARTAO}>
            <ConteudoSugestao icone="ri-edit-box-line" titulo="Informar visitantes e cadastros" assunto="Números da ativação" />
          </Link>
          <Link to={`${base}/relatorio`} className={CARTAO}>
            <ConteudoSugestao icone="ri-file-text-line" titulo="Encontrar documentos" assunto="Relatórios do Atlas" />
          </Link>
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
                    <span className="truncate text-[11px] font-medium text-white/62">Aplicações da marca · {quando(conversa.atualizadaEm)}</span>
                  </span>
                </Link>
              ))}
            </div>
          </div>
        )}
      </div>
      <p className="pt-6 text-center text-xs text-white/55">Só os dados desta parceria · o Atlas não escreve números por conta própria</p>
    </div>
  )
}
