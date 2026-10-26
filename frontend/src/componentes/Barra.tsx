// Barra de andamento usada nas listas da RBS (entregas aprovadas, previsto x exibido). [ITEM-5]
import { porcentagem } from '../util/formatar'

export function Barra({ valor, rotulo }: { valor: number | null | undefined; rotulo?: string }) {
  const largura = valor === null || valor === undefined ? 0 : Math.max(0, Math.min(100, valor))
  return (
    <div className="flex min-w-[140px] items-center gap-2">
      <div
        className="h-2 flex-1 overflow-hidden rounded-full bg-cinza-100"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={valor ?? undefined}
        aria-label={rotulo ?? 'Andamento'}
      >
        <div className="h-full rounded-full bg-secao" style={{ width: `${largura}%` }} />
      </div>
      <span className="w-12 text-right text-xs font-semibold">{porcentagem(valor)}</span>
    </div>
  )
}
