// As peças do Atlas no Figma (página 06): orbe, chips, mensagens, aviso, linha de carregando e painel lateral. [ATLAS]
// Tudo é texto branco sobre o grafite (#2b2d33) ou sobre o fundo fluido, com superfícies de vidro em branco translúcido.
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'

/** P360/Atlas · Orbe (167:8): o globo sobre o degradê azul, roxo e rosa. */
export type EstadoDaOrbe = 'parada' | 'pensando' | 'escrevendo'

/**
 * A orbe tem três estados (plano da base da RBS, F10): parada, ela respira devagar; pensando (o modelo trabalha, ou a importação
 * lê o arquivo), um anel gira em volta; escrevendo (o texto chega), ela brilha. Com movimento reduzido, fica parada.
 * O nome de transição "atlas-orbe" deixa a orbe do menu crescer até a da página quando o Atlas abre.
 */
export function Orbe({ tamanho = 32, estado, transicao = false }: { tamanho?: 24 | 32 | 36 | 40 | 56 | 72; estado?: EstadoDaOrbe; transicao?: boolean }) {
  const icone = tamanho >= 56 ? 'text-3xl' : tamanho >= 36 ? 'text-xl' : tamanho === 32 ? 'text-lg' : 'text-sm'
  const animacao = estado === 'parada' ? 'animate-respirar' : estado === 'escrevendo' ? 'animate-escrever' : ''
  return (
    <span aria-hidden="true" className="relative grid shrink-0 place-items-center" style={{ width: tamanho, height: tamanho, viewTransitionName: transicao ? 'atlas-orbe' : undefined }}>
      {estado === 'pensando' && (
        <span className="absolute -inset-1 animate-pensar rounded-full bg-[conic-gradient(from_0deg,transparent_0deg,#c9a4e7_120deg,#e1047d_200deg,transparent_280deg)] opacity-90" />
      )}
      <span
        style={{ width: tamanho, height: tamanho }}
        className={`relative grid place-items-center rounded-full border border-white/25 bg-[linear-gradient(135deg,#0a2a6e_14%,#572781_54%,#e1047d_86%)] text-white ${animacao}`}
      >
        <i className={`ri-globe-fill ${icone}`} />
      </span>
    </span>
  )
}

type VarianteChip = 'acao' | 'primario'

const CHIP: Record<VarianteChip, string> = {
  acao: 'border border-white/20 bg-white/8 font-semibold text-white hover:bg-white/14',
  primario: 'bg-white font-bold text-atlas-azul hover:bg-white/90',
}

const BASE_CHIP =
  'inline-flex h-[38px] shrink-0 items-center gap-2 rounded-full pr-[18px] pl-4 text-[13px] whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-50'

/** Ponto de 8 px na cor da seção ligada ao chip (ex.: verde de Aplicações). */
function Ponto({ cor }: { cor: string }) {
  return <span aria-hidden="true" className="size-2 shrink-0 rounded-full" style={{ background: cor }} />
}

type PropsChip = ButtonHTMLAttributes<HTMLButtonElement> & { variante?: VarianteChip; icone?: string; ponto?: string; carregando?: boolean }

/** Atlas/Chip (179:1199), variantes Ação e Primário, como botão. */
export function Chip({ variante = 'acao', icone, ponto, carregando = false, className = '', children, disabled, ...resto }: PropsChip) {
  return (
    <button type="button" className={`${BASE_CHIP} ${CHIP[variante]} ${className}`} disabled={disabled || carregando} aria-busy={carregando || undefined} {...resto}>
      {carregando ? <i className="ri-loader-4-line animate-spin text-base" aria-hidden="true" /> : icone && <i className={`${icone} text-base`} aria-hidden="true" />}
      {ponto && <Ponto cor={ponto} />}
      {children}
    </button>
  )
}

/** O mesmo chip, como link para outra tela do portal. */
export function ChipLink({ variante = 'acao', icone, ponto, className = '', children, ...resto }: LinkProps & { variante?: VarianteChip; icone?: string; ponto?: string }) {
  return (
    <Link className={`${BASE_CHIP} ${CHIP[variante]} ${className}`} {...resto}>
      {icone && <i className={`${icone} text-base`} aria-hidden="true" />}
      {ponto && <Ponto cor={ponto} />}
      {children}
    </Link>
  )
}

/** Atlas/Chip · Tag: rótulo pequeno e sem ação (contexto, situação do documento). */
export function Tag({ children, ponto }: { children: ReactNode; ponto?: string }) {
  return (
    <span className="inline-flex h-[27px] max-w-full shrink-0 items-center gap-1.5 truncate rounded-full bg-white/12 pr-3 pl-2.5 text-xs font-medium text-white/82">
      {ponto && <Ponto cor={ponto} />}
      <span className="truncate">{children}</span>
    </span>
  )
}

/** Verde de Aplicações da marca (#00BB40 no Figma): o Atlas monta o relatório dessa aba. */
export const PONTO_APLICACOES = '#00bb40'
/** Amarelo da tag "Rascunho" do documento. */
export const PONTO_RASCUNHO = '#ffd201'

/** Atlas/Mensagem · Você (179:1200): rótulo "VOCÊ" e a bolha à direita, com o canto de baixo mais fechado. */
export function MensagemVoce({ texto }: { texto: string }) {
  return (
    <div className="flex flex-col items-end gap-1.5 pl-10">
      <span className="text-[11px] font-bold tracking-[0.12em] text-white/64 uppercase">Você</span>
      <p className="max-w-full rounded-[20px_20px_6px_20px] bg-white/16 px-[18px] py-3.5 text-[15px] leading-normal font-medium break-words whitespace-pre-wrap text-white">
        {texto}
      </p>
    </div>
  )
}

/** A resposta do modelo de linguagem: orbe, "atlas" e o conteúdo (texto, cartão, documento). */
export function BlocoDoAtlas({ children, compacto = false }: { children: ReactNode; compacto?: boolean }) {
  return (
    <div className={`flex ${compacto ? 'gap-2.5' : 'gap-3.5'}`}>
      <Orbe tamanho={compacto ? 24 : 32} />
      <div className="flex min-w-0 flex-1 flex-col gap-3.5">
        <span className="text-[15px] font-semibold tracking-[-0.01em] text-white">atlas</span>
        {children}
      </div>
    </div>
  )
}

/** O que o portal faz sozinho, sem o modelo (o botão "Montar relatório"): sem orbe nem "atlas", para não parecer IA. */
export function BlocoDoSistema({ children }: { children: ReactNode }) {
  return <div className="flex flex-col gap-3.5">{children}</div>
}

/** Frase fixa do servidor: discreta, sem cara de resposta do assistente. */
export function TextoDoSistema({ children }: { children: ReactNode }) {
  return (
    <p className="flex items-start gap-2 text-[13px] leading-normal text-white/64">
      <i className="ri-information-line mt-px text-base" aria-hidden="true" />
      <span>{children}</span>
    </p>
  )
}

/** Texto do Atlas: Regular 15, altura 1.6, branco a 90%. {@code digitando}: o texto ainda está chegando, com o cursor no fim. */
export function TextoDoAtlas({ children, digitando = false }: { children: ReactNode; digitando?: boolean }) {
  return (
    <p className="text-[15px] leading-[1.6] break-words whitespace-pre-wrap text-white/90">
      {children}
      {digitando && <span aria-hidden="true" className="ml-0.5 inline-block h-[1.05em] w-0.5 translate-y-[3px] animate-pulse bg-white/70" />}
    </p>
  )
}

/** O aviso do Figma (199:1876): vidro suave, ícone e título; sem enfeite nas laterais. */
export function AvisoAtlas({ titulo, children, tipo = 'alerta' }: { titulo: string; children?: ReactNode; tipo?: 'alerta' | 'falha' | 'info' }) {
  const icone = tipo === 'falha' ? 'ri-error-warning-line text-[#ff8aa1]' : tipo === 'info' ? 'ri-information-line text-atlas-lilas' : 'ri-alert-line text-[#ffd201]'
  return (
    <div className="flex gap-3 rounded-[14px] border border-white/12 bg-white/8 py-3.5 pr-[18px] pl-4" role={tipo === 'falha' ? 'alert' : 'status'}>
      <i className={`${icone} mt-0.5 text-xl leading-none`} aria-hidden="true" />
      <div className="min-w-0">
        <p className="text-sm font-semibold text-white">{titulo}</p>
        {children && <div className="mt-0.5 text-sm leading-[1.55] text-white/80">{children}</div>}
      </div>
    </div>
  )
}

/** Estado "carregando" do AT03: o Atlas está trabalhando no turno. */
export function LinhaBuscando({ texto = 'Buscando nos dados da parceria…' }: { texto?: string }) {
  return (
    <p className="flex items-center gap-2.5 text-sm font-medium text-white/72" role="status">
      <i className="ri-loader-4-line animate-spin text-base" aria-hidden="true" />
      {texto}
    </p>
  )
}

/** O painel à direita (AT02, AT05): cartão grafite com raio de 20, sobretítulo lilás, título e fechar. */
export function PainelLateral({
  sobretitulo,
  titulo,
  aoFechar,
  acoes,
  children,
  className = '',
}: {
  sobretitulo: string
  titulo: string
  aoFechar?: () => void
  acoes?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <aside className={`flex min-h-0 flex-col gap-4 rounded-[20px] border border-white/8 bg-atlas-painel p-6 text-white ${className}`} aria-label={titulo}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold tracking-[0.12em] text-atlas-lilas uppercase">{sobretitulo}</p>
          <h2 className="mt-1 text-[22px] leading-tight font-semibold tracking-[-0.01em]">{titulo}</h2>
        </div>
        {aoFechar && (
          <button
            type="button"
            onClick={aoFechar}
            aria-label="Fechar o painel"
            className="grid size-9 shrink-0 place-items-center rounded-full border border-white/20 bg-white/8 hover:bg-white/14"
          >
            <i className="ri-close-line text-lg" aria-hidden="true" />
          </button>
        )}
      </div>
      {acoes}
      {children}
    </aside>
  )
}
