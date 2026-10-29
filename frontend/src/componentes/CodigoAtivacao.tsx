// Código de ativação que a RBS repassa à marca: aparece uma vez só, então copiar precisa ser fácil. [ITEM-3]
import { useState } from 'react'

/** O código inteiro num clique: "Copiar" e, para quem preferir selecionar, o código todo de uma vez (select-all). */
export function CodigoAtivacao({ codigo }: { codigo?: string | null }) {
  const [copiado, setCopiado] = useState(false)
  if (!codigo) return <>—</>

  async function copiar() {
    try {
      await navigator.clipboard.writeText(codigo ?? '')
      setCopiado(true)
      setTimeout(() => setCopiado(false), 2000)
    } catch {
      setCopiado(false)
    }
  }

  return (
    <span className="flex items-center gap-2 whitespace-nowrap">
      <code className="font-bold select-all">{codigo}</code>
      <button type="button" onClick={copiar} className="flex items-center gap-1 text-xs font-bold text-secao-texto hover:underline">
        <i className={copiado ? 'ri-check-line' : 'ri-file-copy-line'} aria-hidden="true" />
        {copiado ? 'Copiado' : 'Copiar'}
      </button>
    </span>
  )
}
