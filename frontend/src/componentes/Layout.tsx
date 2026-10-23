// Esqueleto das telas logadas: o menu flutuante à esquerda e o conteúdo com 1200 px úteis, como no Figma. [ITEM-7]
import { Outlet } from 'react-router'
import type { ParceriaResumo } from '../api/tipos'
import { Menu } from './Menu'

export function Layout({ parceria, contexto }: { parceria: ParceriaResumo | null; contexto?: unknown }) {
  return (
    <div className="min-h-screen">
      <Menu parceria={parceria} />
      <main className="py-4 pr-4 pl-[296px]">
        <div className="flex max-w-[1200px] flex-col gap-4">
          <Outlet context={contexto} />
        </div>
      </main>
    </div>
  )
}
