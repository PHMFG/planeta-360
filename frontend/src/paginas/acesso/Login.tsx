// Tela de login (Figma A01): e-mail corporativo e senha; o perfil vem da conta, não de uma escolha. [ITEM-3]
import { focarPrimeiroErro } from '../../componentes/Campo'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { Aviso } from '../../componentes/Aviso'
import { Botao, LinkBotao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { destinoPermitido, telaInicial, useSessao } from '../../sessao/Sessao'
import { MolduraAcesso } from './MolduraAcesso'

const EMAIL_VALIDO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Estado = { destino?: string; email?: string; sessaoTerminou?: boolean } | null

export function Login() {
  const { usuario, entrar } = useSessao()
  const estado = useLocation().state as Estado

  const [email, setEmail] = useState(estado?.email ?? '')
  const [senha, setSenha] = useState('')
  const [erros, setErros] = useState<{ email?: string; senha?: string }>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  // Quem já está logado (ou acabou de entrar) segue para a tela que tentou abrir, ou para a inicial do perfil.
  if (usuario) return <Navigate to={destinoPermitido(estado?.destino, usuario) ?? telaInicial(usuario)} replace />

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    // [VALIDACAO] Confere o básico antes de chamar a API; a regra que vale é a do servidor.
    const novos: typeof erros = {}
    if (!email.trim()) novos.email = 'Informe o e-mail liberado pela RBS.'
    else if (!EMAIL_VALIDO.test(email.trim())) novos.email = 'Confira o formato do e-mail.'
    if (!senha) novos.senha = 'Informe a senha.'
    setErros(novos)
    focarPrimeiroErro()
    setFalha(null)
    if (Object.keys(novos).length > 0) return

    setEnviando(true)
    try {
      // Ao gravar a sessão, a própria tela redireciona (veja o início do componente).
      await entrar(email.trim(), senha)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível entrar agora.')
      setEnviando(false)
    }
  }

  return (
    <MolduraAcesso
      titulo={
        <>
          Tudo da sua parceria
          <br />
          em um só lugar
        </>
      }
    >
      <form className="flex w-full max-w-[640px] flex-col gap-6" onSubmit={enviar} noValidate>
        {estado?.sessaoTerminou && (
          <Aviso tipo="restrito" titulo="Sua sessão terminou">
            Entre de novo para continuar. Se o seu acesso foi revogado pela RBS, fale com o time B2B.
          </Aviso>
        )}
        <Campo
          rotulo="E-mail corporativo"
          type="email"
          autoComplete="username"
          placeholder="seu@email.exemplo"
          value={email}
          onChange={(evento) => setEmail(evento.target.value)}
          erro={erros.email}
          autoFocus={!estado?.email}
        />
        {/* Vindo do primeiro acesso, o e-mail já está preenchido: o cursor começa na senha. */}
        <Campo
          rotulo="Senha"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          value={senha}
          onChange={(evento) => setSenha(evento.target.value)}
          erro={erros.senha}
          autoFocus={Boolean(estado?.email)}
        />

        {falha && (
          <Aviso tipo="falha" titulo={falha}>
            Acesso não liberado ou revogado? Fale com o time B2B da RBS.
          </Aviso>
        )}

        <div className="flex flex-col items-center gap-6">
          <Botao type="submit" carregando={enviando}>
            Entrar
          </Botao>
          <LinkBotao to="/ativar" variante="secundario" state={{ email: email.trim() }}>
            Primeiro acesso
          </LinkBotao>
        </div>
      </form>

      <p className="max-w-[640px] text-center text-xs font-medium text-texto-2">
        O perfil autorizado determina a visão patrocinador ou RBS; não é escolhido livremente no login.
      </p>
    </MolduraAcesso>
  )
}
