// Aviso do Figma (P360/Aviso) com as cinco variações de estado do S00. [ITEM-7]
import type { ReactNode } from 'react'

export type TipoAviso = 'info' | 'vazio' | 'carregando' | 'falha' | 'restrito' | 'ausente'

const ESTILOS: Record<TipoAviso, { fundo: string; icone: string; corIcone: string }> = {
  info: { fundo: 'bg-secao-tinta', icone: 'ri-information-line', corIcone: 'text-secao' },
  vazio: { fundo: 'bg-estado-vazio/14', icone: 'ri-inbox-line', corIcone: 'text-estado-vazio' },
  carregando: { fundo: 'bg-estado-carregando/14', icone: 'ri-loader-4-line animate-spin', corIcone: 'text-estado-carregando' },
  falha: { fundo: 'bg-estado-falha/14', icone: 'ri-error-warning-line', corIcone: 'text-estado-falha' },
  restrito: { fundo: 'bg-estado-restrito/14', icone: 'ri-lock-line', corIcone: 'text-estado-restrito' },
  ausente: { fundo: 'bg-estado-ausente/14', icone: 'ri-time-line', corIcone: 'text-[#8a6a00]' },
}

type PropsAviso = {
  tipo?: TipoAviso
  titulo: string
  children?: ReactNode
  className?: string
}

export function Aviso({ tipo = 'info', titulo, children, className = '' }: PropsAviso) {
  const estilo = ESTILOS[tipo]
  return (
    <div
      className={`relative rounded-[14px] py-3.5 pr-5 pl-[52px] text-sm ${estilo.fundo} ${className}`}
      role={tipo === 'falha' ? 'alert' : 'status'}
    >
      <i className={`${estilo.icone} ${estilo.corIcone} absolute top-3 left-[18px] text-[22px] leading-none`} aria-hidden="true" />
      <p className="font-bold">{titulo}</p>
      {children && <div className="mt-0.5 leading-normal">{children}</div>}
    </div>
  )
}
