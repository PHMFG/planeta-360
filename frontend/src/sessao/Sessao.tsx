// Guarda quem está logado e protege as rotas: sem sessão, a pessoa volta para o login. [ITEM-3]
// O perfil (patrocinador ou RBS) vem da conta, nunca de uma escolha na tela.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { entrar as entrarNaApi, quemSouEu, sair as sairNaApi } from '../api/acesso'
import { quandoPerderSessao } from '../api/cliente'
import type { UsuarioLogado } from '../api/tipos'

type EstadoSessao = {
  usuario: UsuarioLogado | null
  carregando: boolean
  /** Verdadeiro depois de "Sair": aí o login não deve levar a pessoa seguinte à tela de quem saiu. */
  saiu: boolean
  entrar: (email: string, senha: string) => Promise<UsuarioLogado>
  sair: () => Promise<void>
}

const ContextoSessao = createContext<EstadoSessao | null>(null)

export function ProvedorSessao({ children }: { children: ReactNode }) {
  const [usuario, setUsuario] = useState<UsuarioLogado | null>(null)
  const [carregando, setCarregando] = useState(true)
  const [saiu, setSaiu] = useState(false)

  useEffect(() => {
    quandoPerderSessao(() => setUsuario(null))
    quemSouEu()
      .then(setUsuario)
      .catch(() => setUsuario(null))
      .finally(() => setCarregando(false))
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const logado = await entrarNaApi(email, senha)
    setSaiu(false)
    setUsuario(logado)
    return logado
  }, [])

  const sair = useCallback(async () => {
    try {
      await sairNaApi()
    } finally {
      setSaiu(true)
      setUsuario(null)
    }
  }, [])

  const valor = useMemo(() => ({ usuario, carregando, saiu, entrar, sair }), [usuario, carregando, saiu, entrar, sair])
  return <ContextoSessao.Provider value={valor}>{children}</ContextoSessao.Provider>
}

export function useSessao() {
  const sessao = useContext(ContextoSessao)
  if (!sessao) throw new Error('useSessao precisa estar dentro de ProvedorSessao')
  return sessao
}

/** Tela inicial de cada perfil: a RBS cai no painel, o patrocinador na própria parceria. */
export function telaInicial(usuario: UsuarioLogado): string {
  if (usuario.perfil === 'ADMIN_RBS') return '/rbs'
  const primeira = usuario.parcerias[0]
  return primeira ? `/parceria/${primeira.id}` : '/sem-parceria'
}

/** Só deixa passar quem está logado (e, se pedido, só a RBS). */
export function RotaProtegida({ children, somenteRbs = false }: { children: ReactNode; somenteRbs?: boolean }) {
  const { usuario, carregando, saiu } = useSessao()
  const local = useLocation()

  if (carregando) return <TelaCarregando />
  // Sessão vencida guarda a tela para voltar depois do login; quem clicou em "Sair" não deixa rastro.
  if (!usuario) return <Navigate to="/entrar" replace state={saiu ? null : { destino: local.pathname }} />
  // [PERMISSAO] Esconder a tela é só conforto: quem barra de verdade é a API (403).
  if (somenteRbs && usuario.perfil !== 'ADMIN_RBS') return <Navigate to={telaInicial(usuario)} replace />
  return <>{children}</>
}

export function TelaCarregando() {
  return (
    <div className="grid min-h-screen place-items-center text-texto-2" role="status">
      <span className="flex items-center gap-2 text-sm font-semibold">
        <i className="ri-loader-4-line animate-spin text-xl" aria-hidden="true" />
        Carregando…
      </span>
    </div>
  )
}
