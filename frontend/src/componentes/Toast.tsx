// O retorno curto de uma ação ("Números salvos", "Envio recebido"): sobe no canto de baixo e some sozinho em 3,5 s.
// Um só estilo de confirmação para o portal inteiro (plano da base da RBS, F10). O leitor de tela ouve pela região "status".
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'

type TipoDoToast = 'sucesso' | 'falha' | 'info'
type Toast = { id: number; texto: string; tipo: TipoDoToast; saindo: boolean }
type Avisar = (texto: string, tipo?: TipoDoToast) => void

const ContextoDoToast = createContext<Avisar>(() => {})

const DURACAO = 3500
const SAIDA = 120

const ICONE: Record<TipoDoToast, string> = {
  sucesso: 'ri-checkbox-circle-fill text-aplicacoes',
  falha: 'ri-error-warning-fill text-estado-falha',
  info: 'ri-information-fill text-estado-carregando',
}

export function ProvedorDeToast({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const proximo = useRef(1)
  const timers = useRef(new Set<number>())

  const tirar = useCallback((id: number) => {
    setToasts((lista) => lista.map((t) => (t.id === id ? { ...t, saindo: true } : t)))
    const timer = window.setTimeout(() => setToasts((lista) => lista.filter((t) => t.id !== id)), SAIDA)
    timers.current.add(timer)
  }, [])

  const avisar = useCallback<Avisar>(
    (texto, tipo = 'sucesso') => {
      const id = proximo.current++
      setToasts((lista) => [...lista.slice(-2), { id, texto, tipo, saindo: false }])
      const timer = window.setTimeout(() => tirar(id), DURACAO)
      timers.current.add(timer)
    },
    [tirar],
  )

  useEffect(() => {
    const ativos = timers.current
    return () => ativos.forEach((timer) => window.clearTimeout(timer))
  }, [])

  return (
    <ContextoDoToast.Provider value={avisar}>
      {children}
      <div role="status" aria-live="polite" className="pointer-events-none fixed right-4 bottom-4 z-[60] flex w-[min(360px,calc(100vw-32px))] flex-col gap-2 print:hidden">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`pointer-events-auto flex items-start gap-2.5 rounded-2xl bg-white px-4 py-3 text-sm font-semibold shadow-modal ${toast.saindo ? 'animate-sumir' : 'animate-subir-aviso'}`}
          >
            <i className={`${ICONE[toast.tipo]} text-lg leading-5`} aria-hidden="true" />
            <span className="flex-1">{toast.texto}</span>
            <button type="button" onClick={() => tirar(toast.id)} className="text-texto-2 hover:text-tinta" aria-label="Fechar aviso">
              <i className="ri-close-line" aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ContextoDoToast.Provider>
  )
}

/** Mostra um aviso curto: `avisar('Números salvos')`, ou `avisar('Não deu', 'falha')`. */
export function usarToast() {
  return useContext(ContextoDoToast)
}
