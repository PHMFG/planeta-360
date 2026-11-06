// Barra de andamento usada nas listas da RBS (entregas aprovadas, previsto x exibido). [ITEM-5]
// Enche a partir de zero quando aparece e anima quando o valor muda (480 ms); com movimento reduzido, fica parada.
import { useEffect, useState } from 'react'
import { porcentagem } from '../util/formatar'

export function Barra({ valor, rotulo }: { valor: number | null | undefined; rotulo?: string }) {
  const largura = valor === null || valor === undefined ? 0 : Math.max(0, Math.min(100, valor))
  const [mostrada, setMostrada] = useState(0)
  useEffect(() => {
    const quadro = requestAnimationFrame(() => setMostrada(largura))
    return () => cancelAnimationFrame(quadro)
  }, [largura])
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
        <div className="h-full rounded-full bg-secao transition-[width] duration-(--duracao-longa) ease-(--curva-saida)" style={{ width: `${mostrada}%` }} />
      </div>
      <span className="w-12 text-right text-xs font-semibold">{porcentagem(valor)}</span>
    </div>
  )
}
