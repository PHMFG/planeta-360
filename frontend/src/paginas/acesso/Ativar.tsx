// Primeiro acesso: o patrocinador usa o código que a RBS repassou e cria a própria senha. [ITEM-3]
// [PENDENTE] Na V1 não há e-mail automático; a RBS vê o código uma vez ao autorizar o e-mail.
import { useState, type FormEvent } from 'react'
import { useLocation } from 'react-router'
import { ativarAcesso } from '../../api/acesso'
import { ErroApi } from '../../api/cliente'
import { Aviso } from '../../componentes/Aviso'
import { Botao, LinkBotao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { MolduraAcesso } from './MolduraAcesso'

const EMAIL_VALIDO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const SENHA_MINIMA = 8

type Erros = Partial<Record<'email' | 'codigo' | 'senha' | 'confirmacao', string>>

export function Ativar() {
  const estado = useLocation().state as { email?: string } | null
  const [email, setEmail] = useState(estado?.email ?? '')
  const [codigo, setCodigo] = useState('')
  const [senha, setSenha] = useState('')
  const [confirmacao, setConfirmacao] = useState('')
  const [erros, setErros] = useState<Erros>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [ativado, setAtivado] = useState(false)

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    // [VALIDACAO] Mesmas regras que a API confere, para o erro aparecer junto ao campo.
    const novos: Erros = {}
    if (!EMAIL_VALIDO.test(email.trim())) novos.email = 'Informe o e-mail que a RBS autorizou.'
    if (!codigo.trim()) novos.codigo = 'Informe o código de ativação.'
    if (senha.length < SENHA_MINIMA) novos.senha = `A senha precisa de pelo menos ${SENHA_MINIMA} caracteres.`
    if (confirmacao !== senha) novos.confirmacao = 'As duas senhas precisam ser iguais.'
    setErros(novos)
    setFalha(null)
    if (Object.keys(novos).length > 0) return

    setEnviando(true)
    try {
      await ativarAcesso(email.trim(), codigo.trim(), senha)
      setAtivado(true)
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos as Erros)
        // Erro já mostrado junto do campo não se repete no aviso geral.
        setFalha(Object.values(erro.campos).includes(erro.message) ? null : erro.message)
      } else {
        setFalha('Não foi possível ativar o acesso agora.')
      }
    } finally {
      setEnviando(false)
    }
  }

  if (ativado) {
    return (
      <MolduraAcesso titulo="Acesso ativado">
        <div className="flex w-full max-w-[640px] flex-col items-center gap-6">
          <Aviso titulo="Tudo pronto" className="w-full">
            Sua senha foi criada. Entre com o e-mail {email.trim()} para ver a sua parceria.
          </Aviso>
          <LinkBotao to="/entrar" state={{ email: email.trim() }}>
            Ir para o login
          </LinkBotao>
        </div>
      </MolduraAcesso>
    )
  }

  return (
    <MolduraAcesso titulo="Ative seu acesso">
      <p className="max-w-[532px] text-center text-sm">
        Use o código que a RBS repassou junto com a liberação do seu e-mail. O código vale uma vez só.
      </p>
      <form className="flex w-full max-w-[640px] flex-col gap-6" onSubmit={enviar} noValidate>
        <Campo
          rotulo="E-mail corporativo"
          type="email"
          autoComplete="username"
          placeholder="seu@email.exemplo"
          value={email}
          onChange={(evento) => setEmail(evento.target.value)}
          erro={erros.email}
        />
        <Campo
          rotulo="Código de ativação"
          autoComplete="one-time-code"
          placeholder="Código enviado pela RBS"
          value={codigo}
          onChange={(evento) => setCodigo(evento.target.value)}
          erro={erros.codigo}
        />
        <div className="grid gap-6 sm:grid-cols-2">
          <Campo
            rotulo="Nova senha"
            type="password"
            autoComplete="new-password"
            value={senha}
            onChange={(evento) => setSenha(evento.target.value)}
            erro={erros.senha}
            ajuda={`Mínimo de ${SENHA_MINIMA} caracteres.`}
          />
          <Campo
            rotulo="Confirmar senha"
            type="password"
            autoComplete="new-password"
            value={confirmacao}
            onChange={(evento) => setConfirmacao(evento.target.value)}
            erro={erros.confirmacao}
          />
        </div>

        {falha && <Aviso tipo="falha" titulo={falha} />}

        <div className="flex flex-col items-center gap-6">
          <Botao type="submit" carregando={enviando}>
            Ativar acesso
          </Botao>
          <LinkBotao to="/entrar" variante="secundario" state={{ email: email.trim() }}>
            Voltar para o login
          </LinkBotao>
        </div>
      </form>
    </MolduraAcesso>
  )
}
