// O aviso de que o relatório do Atlas ficou pronto, com o atalho para o documento A4. [ATLAS]
import { Link } from 'react-router'
import type { ItemDaConversa } from '../api/atlas'

type ItemPronto = Extract<ItemDaConversa, { tipo: 'relatorio_pronto' }>

export function CartaoRelatorioPronto({ parceriaId, item, aoAbrir }: { parceriaId: number; item: ItemPronto; aoAbrir: () => void }) {
  return (
    <div data-secao="aplicacoes" className="flex items-center gap-3 rounded-2xl bg-secao-tinta p-4">
      <i className="ri-file-chart-line text-2xl text-secao-texto" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-bold">{item.titulo}</p>
        <p className="text-xs font-medium">Relatório pronto. A origem de cada número está em “Fontes e cálculos”.</p>
      </div>
      <Link
        to={`/parceria/${parceriaId}/relatorios-atlas/${item.relatorioId}`}
        onClick={aoAbrir}
        className="texto-botao shrink-0 rounded-[10px] bg-secao px-4 py-2.5 text-white hover:brightness-110"
      >
        Abrir
      </Link>
    </div>
  )
}
