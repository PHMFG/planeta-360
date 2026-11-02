// Situação de entregas e relatórios: texto em negrito com um ponto colorido, como nas telas do Figma. [ITEM-4] [ITEM-6]
// O Figma não tem pílula; as cores vêm do protótipo de 19/10 para separar um status do outro.

export type StatusEntrega =
  | 'NAO_INICIADO'
  | 'AGUARDANDO_ENVIO'
  | 'ENVIADO'
  | 'EM_ANALISE'
  | 'AJUSTE_SOLICITADO'
  | 'APROVADO'
  | 'NAO_APLICAVEL'

export type StatusRelatorio = 'RASCUNHO' | 'VALIDADO' | 'DISPONIBILIZADO'

const ROTULOS: Record<string, { nome: string; cor: string }> = {
  NAO_INICIADO: { nome: 'Não iniciado', cor: '#616161' },
  AGUARDANDO_ENVIO: { nome: 'Aguardando envio', cor: '#616161' },
  ENVIADO: { nome: 'Enviado', cor: '#073c89' },
  EM_ANALISE: { nome: 'Em análise', cor: '#8a6a00' },
  AJUSTE_SOLICITADO: { nome: 'Ajuste solicitado', cor: '#c8203f' },
  APROVADO: { nome: 'Aprovado', cor: '#007a2a' },
  NAO_APLICAVEL: { nome: 'Não aplicável', cor: '#9e9e9e' },
  RASCUNHO: { nome: 'Rascunho', cor: '#572781' },
  VALIDADO: { nome: 'Validado', cor: '#0a6e80' },
  DISPONIBILIZADO: { nome: 'Disponibilizado', cor: '#007a2a' },
  AGUARDANDO_APROVACAO: { nome: 'Aguardando aprovação', cor: '#8a6a00' },
  RECUSADO: { nome: 'Recusado', cor: '#c8203f' },
  PROCESSANDO: { nome: 'Processando', cor: '#073c89' },
  INDEXADO: { nome: 'Indexado', cor: '#007a2a' },
  ERRO: { nome: 'Erro', cor: '#c8203f' },
  SUBSTITUIDO: { nome: 'Substituído', cor: '#616161' },
  REVOGADO: { nome: 'Revogado', cor: '#424242' },
}

export const STATUS_ENTREGA: StatusEntrega[] = [
  'NAO_INICIADO',
  'AGUARDANDO_ENVIO',
  'ENVIADO',
  'EM_ANALISE',
  'AJUSTE_SOLICITADO',
  'APROVADO',
  'NAO_APLICAVEL',
]

export function nomeStatus(codigo: string) {
  return ROTULOS[codigo]?.nome ?? codigo
}

export function Status({ codigo, complemento }: { codigo: string; complemento?: string }) {
  const rotulo = ROTULOS[codigo] ?? { nome: codigo, cor: '#424242' }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-bold" style={{ color: rotulo.cor }}>
      <span className="size-2 rounded-full" style={{ background: rotulo.cor }} aria-hidden="true" />
      {rotulo.nome}
      {complemento && <span className="font-semibold text-secao-texto">· {complemento}</span>}
    </span>
  )
}
