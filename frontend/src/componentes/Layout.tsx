// Esqueleto das telas logadas: o menu flutuante à esquerda e o conteúdo com 1200 px úteis, como no Figma. [ITEM-7]
// Abaixo de 1024 px o menu vira uma gaveta aberta pelo botão da barra do topo, e o conteúdo usa a largura toda.
import { useState } from 'react'
import { Outlet } from 'react-router'
import type { ParceriaResumo } from '../api/tipos'
import { useSessao } from '../sessao/Sessao'
import { BotaoDebug } from './BotaoDebug'
import { Menu } from './Menu'

export function Layout({ parceria, contexto }: { parceria: ParceriaResumo | null; contexto?: unknown }) {
  const [menuAberto, setMenuAberto] = useState(false)
  const { usuario } = useSessao()
  // [ATLAS] Só a marca conversa com o Atlas, e sempre dentro de uma parceria: o botão do menu abre a janela dele.
  const atlas = parceria !== null && usuario?.perfil === 'ADMIN_PATROCINADOR' ? `/parceria/${parceria.id}/atlas` : undefined

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-30 flex items-center justify-between gap-4 border-b border-borda bg-white px-4 py-2.5 lg:hidden print:hidden">
        <p className="truncate font-display text-[28px] leading-none text-parceria uppercase">Planeta 360</p>
        <button
          type="button"
          aria-expanded={menuAberto}
          aria-controls="menu-principal"
          onClick={() => setMenuAberto(true)}
          className="flex h-10 shrink-0 items-center gap-1.5 rounded-[10px] border border-borda px-3 text-[13px] font-semibold"
        >
          <i className="ri-menu-line text-lg" aria-hidden="true" />
          Menu
        </button>
      </header>
      <Menu
        parceria={parceria}
        aberto={menuAberto}
        aoFechar={() => setMenuAberto(false)}
        atlas={atlas}
      />
      {/* [TEMPORARIO] Só aparece com as rotas de debug da API (perfil dev). */}
      <BotaoDebug />
      <main className="p-4 lg:pl-[296px] print:p-0">
        <div className="flex max-w-[1200px] min-w-0 flex-col gap-4">
          <Outlet context={contexto} />
        </div>
      </main>
    </div>
  )
}
