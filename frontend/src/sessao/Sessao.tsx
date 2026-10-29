// Guarda quem está logado e protege as rotas: sem sessão, a pessoa volta para o login. [ITEM-3]
// O perfil (patrocinador ou RBS) vem da conta, nunca de uma escolha na tela.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { entrar as entrarNaApi, quemSouEu, sair as sairNaApi } from '../api/acesso'
import { quandoPerderSessao } from '../api/cliente'
import type { UsuarioLogado } from '../api/tipos'

type EstadoSessao = {
  usuario: UsuarioLogado | null
  carregando: boolean
  /** Verdadeiro depois de "Sair": aí o login não deve levar a pessoa seguinte à tela de quem saiu. */
  saiu: boolean
  /** Verdadeiro quando a API encerrou a sessão no meio do uso (tempo esgotado ou acesso revogado pela RBS). */
  perdeu: boolean
  entrar: (email: string, senha: string) => Promise<UsuarioLogado>
  sair: () => Promise<void>
  /** Lê a conta de novo na API: parcerias e abas liberadas mudam quando a RBS mexe no Perfil. */
  atualizar: () => Promise<void>
}

const ContextoSessao = createContext<EstadoSessao | null>(null)

export function ProvedorSessao({ children }: { children: ReactNode }) {
  const [usuario, setUsuario] = useState<UsuarioLogado | null>(null)
  const [carregando, setCarregando] = useState(true)
  const [saiu, setSaiu] = useState(false)
  const [perdeu, setPerdeu] = useState(false)
  const logado = useRef(false)

  useEffect(() => {
    logado.current = usuario !== null
  }, [usuario])

  useEffect(() => {
    quandoPerderSessao(() => {
      // Um 401 antes de entrar é só "ainda sem sessão"; depois de entrar, a sessão terminou.
      if (logado.current) setPerdeu(true)
      setUsuario(null)
    })
    quemSouEu()
      .then(setUsuario)
      .catch(() => setUsuario(null))
      .finally(() => setCarregando(false))
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const conta = await entrarNaApi(email, senha)
    setSaiu(false)
    setPerdeu(false)
    setUsuario(conta)
    return conta
  }, [])

  const sair = useCallback(async () => {
    try {
      await sairNaApi()
    } finally {
      setSaiu(true)
      setUsuario(null)
    }
  }, [])

  const atualizar = useCallback(async () => {
    try {
      setUsuario(await quemSouEu())
    } catch {
      // Sem sessão, o 401 já levou a pessoa ao login (quandoPerderSessao).
    }
  }, [])

  const valor = useMemo(
    () => ({ usuario, carregando, saiu, perdeu, entrar, sair, atualizar }),
    [usuario, carregando, saiu, perdeu, entrar, sair, atualizar],
  )
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

/**
 * A tela guardada antes do login só vale se a conta que entrou pode abri-la. [ISOLAMENTO]
 * Sem isso, depois de uma sessão vencida, a próxima pessoa cairia na tela da parceria de quem estava antes.
 */
export function destinoPermitido(destino: string | undefined, usuario: UsuarioLogado): string | null {
  if (!destino) return null
  const rbs = usuario.perfil === 'ADMIN_RBS'
  if (destino.startsWith('/rbs')) return rbs ? destino : null
  const id = /^\/parceria\/(\d+)(\/|$)/.exec(destino)?.[1]
  if (!id) return null
  return rbs || usuario.parcerias.some((parceria) => parceria.id === Number(id)) ? destino : null
}

/** Só deixa passar quem está logado (e, se pedido, só a RBS). */
export function RotaProtegida({ children, somenteRbs = false }: { children: ReactNode; somenteRbs?: boolean }) {
  const { usuario, carregando, saiu, perdeu } = useSessao()
  const local = useLocation()

  if (carregando) return <TelaCarregando />
  // Sessão vencida guarda a tela para voltar depois do login (se a mesma conta entrar); quem clicou em "Sair" não deixa rastro.
  if (!usuario) return <Navigate to="/entrar" replace state={saiu ? null : { destino: local.pathname, sessaoTerminou: perdeu }} />
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
