// O cartão de anexo do Figma (AT04, 202:1769): o documento que o Atlas montou, dentro da conversa, com o atalho para a
// pré-visualização ao lado do chat. As fontes de verdade ficam na aba "Fontes" e no fim do documento, não num texto fixo. [ATLAS]
import type { ParceriaResumo } from '../api/tipos'
import { Chip, ChipLink, PONTO_RASCUNHO, Tag } from './Elementos'

type Props = {
  parceria: ParceriaResumo
  relatorioId: number
  titulo: string
  aoAbrir: (aba: 'conversa' | 'fontes') => void
}

export function CartaoDocumento({ parceria, relatorioId, titulo, aoAbrir }: Props) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-white/16 bg-white/8 p-4">
      {/* Sem espaço (no painel ao lado do documento), a tag desce para baixo do título em vez de espremê-lo. */}
      <div className="flex flex-wrap items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-white/10">
          <i className="ri-file-text-line text-xl text-white" aria-hidden="true" />
        </span>
        <div className="min-w-[180px] flex-1">
          <p className="text-base font-semibold text-white">{titulo}</p>
          <p className="text-[13px] text-white/64">
            {parceria.patrocinador} · {parceria.evento.nome} {parceria.evento.edicao}
          </p>
        </div>
        <Tag ponto={PONTO_RASCUNHO}>Documento de trabalho</Tag>
      </div>
      <p className="text-xs text-white/62">Somente registros desta parceria. A RBS não valida este documento.</p>
      <div className="flex flex-wrap gap-2.5">
        <Chip variante="primario" onClick={() => aoAbrir('conversa')}>
          Abrir documento
        </Chip>
        <Chip onClick={() => aoAbrir('fontes')}>Ver fontes usadas</Chip>
        <ChipLink to={`/parceria/${parceria.id}/relatorios-atlas/${relatorioId}`} icone="ri-external-link-line">
          Página inteira
        </ChipLink>
      </div>
    </div>
  )
}
