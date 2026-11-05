// [TEMPORARIO] Botão de debug para a equipe pôr a chave do modelo de linguagem do Atlas sem reiniciar a API. Só aparece quando
// a API tem as rotas de debug (perfil dev). A chave vai uma vez para a API, fica só na memória dela e nunca volta inteira.
import { useEffect, useState, type FormEvent } from 'react'
import { ErroApi } from '../api/cliente'
import {
  EVENTO_LLM_MUDOU,
  definirChaveDoLlm,
  obterEstadoDoLlm,
  removerChaveDoLlm,
  testarLlm,
  type EstadoDoLlm,
  type TesteDoLlm,
} from '../api/debug'
import { useSessao } from '../sessao/Sessao'
import { dataHora } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Campo } from './Campo'
import { Seletor } from './Formulario'
import { Modal } from './Modal'

/** O provedor do teste (decidido em 29/10): DeepSeek V4.1 Flash pelo OpenCode Go, que é compatível com a API da OpenAI. */
const OPENCODE_GO_DEEPSEEK = { baseUrl: 'https://opencode.ai/inference/go/openai/v1', modelo: 'deepseek-v4.1-flash' }

type Provedor = 'go-deepseek' | 'personalizado'

function provedorDe(estado: EstadoDoLlm): Provedor {
  return estado.origem === 'NENHUM' || (estado.baseUrl === OPENCODE_GO_DEEPSEEK.baseUrl && estado.modelo === OPENCODE_GO_DEEPSEEK.modelo)
    ? 'go-deepseek'
    : 'personalizado'
}

function descrever(estado: EstadoDoLlm) {
  const final = estado.finalDaChave ? ` terminando em …${estado.finalDaChave}` : ''
  if (estado.origem === 'DEBUG')
    return `Chave${final}, posta por ${estado.configuradoPor?.nome ?? '—'} em ${dataHora(estado.configuradoEm)}.`
  if (estado.origem === 'AMBIENTE') return `Chave das variáveis de ambiente da API${final}.`
  return 'Nenhuma chave: o Atlas monta o relatório só pelo botão, sem chat.'
}

export function BotaoDebug() {
  const [estado, setEstado] = useState<EstadoDoLlm | null>(null)
  const [aberto, setAberto] = useState(false)
  const [chave, setChave] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [modelo, setModelo] = useState('')
  const [provedor, setProvedor] = useState<Provedor>('go-deepseek')
  const [raciocinio, setRaciocinio] = useState(true)
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [teste, setTeste] = useState<TesteDoLlm | null>(null)
  const [ocupado, setOcupado] = useState<'salvar' | 'testar' | 'remover' | null>(null)

  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'

  useEffect(() => {
    let ativo = true
    // Só a RBS usa o debug: para a marca a chamada nem sai, e o console fica sem o 403 em toda tela (U12).
    // Fora do perfil dev a rota não existe (404) e o botão some; qualquer outra falha também o esconde.
    if (!rbs) return
    obterEstadoDoLlm()
      .then((lido) => {
        if (!ativo) return
        setEstado(lido)
        if (!lido) return
        setProvedor(provedorDe(lido))
        // Uma API de antes do campo não o manda: o padrão é ligado.
        setRaciocinio(lido.raciocinio ?? true)
      })
      .catch(() => ativo && setEstado(null))
    return () => {
      ativo = false
    }
  }, [rbs])

  if (!estado) return null

  function mudou(novo: EstadoDoLlm) {
    setEstado(novo)
    window.dispatchEvent(new Event(EVENTO_LLM_MUDOU))
  }

  async function executar(qual: 'salvar' | 'testar' | 'remover', fazer: () => Promise<void>) {
    setOcupado(qual)
    setFalha(null)
    setErros({})
    try {
      await fazer()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        if (!Object.keys(erro.campos).length) setFalha(erro.message)
      } else setFalha('A API não respondeu.')
    } finally {
      setOcupado(null)
    }
  }

  function salvar(evento: FormEvent) {
    evento.preventDefault()
    if (!chave.trim()) return setErros({ chave: 'Cole a chave do provedor.' })
    executar('salvar', async () => {
      const destino =
        provedor === 'go-deepseek' ? OPENCODE_GO_DEEPSEEK : { baseUrl: baseUrl.trim() || undefined, modelo: modelo.trim() || undefined }
      mudou(await definirChaveDoLlm({ chave: chave.trim(), ...destino, raciocinio }))
      // A chave não fica na tela depois de enviada.
      setChave('')
      setTeste(null)
    })
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setAberto(true)}
        className="fixed top-2 left-1/2 z-[80] flex -translate-x-1/2 items-center gap-1.5 rounded-full border border-[#8a6a00]/40 bg-[#fff3c4] px-3 py-1.5 text-xs font-bold text-[#6b5200] shadow-menu print:hidden"
        title="Debug temporário: chaves de IA"
      >
        <i className="ri-bug-line text-sm" aria-hidden="true" />
        Debug
        <span className={`size-2 rounded-full ${estado.llmDisponivel ? 'bg-[#1a9e4a]' : 'bg-cinza-400'}`} aria-hidden="true" />
        <span className="sr-only">{estado.llmDisponivel ? 'modelo ligado' : 'modelo desligado'}</span>
      </button>

      <Modal titulo="Debug · chaves de IA" legenda="Temporário, só no ambiente de desenvolvimento" aberto={aberto} aoFechar={() => setAberto(false)}>
        <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
          <Aviso tipo={estado.llmDisponivel ? 'info' : 'ausente'} titulo={estado.llmDisponivel ? 'Modelo de linguagem ligado' : 'Modelo de linguagem desligado'}>
            {descrever(estado)} Modelo {estado.modelo}, {estado.raciocinio === false ? 'sem' : 'com'} raciocínio.
          </Aviso>
          <Seletor rotulo="Provedor e modelo" value={provedor} onChange={(evento) => setProvedor(evento.target.value as Provedor)}>
            <option value="go-deepseek">OpenCode Go · DeepSeek V4.1 Flash</option>
            <option value="personalizado">Outro serviço compatível com a OpenAI</option>
          </Seletor>
          <Campo
            rotulo={provedor === 'go-deepseek' ? 'Chave do OpenCode Go' : 'Chave do provedor'}
            type="password"
            autoComplete="off"
            spellCheck={false}
            placeholder={estado.finalDaChave ? `atual termina em …${estado.finalDaChave}` : 'cole a chave aqui'}
            value={chave}
            onChange={(evento) => setChave(evento.target.value)}
            erro={erros.chave}
          />
          {provedor === 'go-deepseek' ? (
            <p className="text-xs font-medium text-texto-2">
              Endereço <code>{OPENCODE_GO_DEEPSEEK.baseUrl}</code> e modelo <code>{OPENCODE_GO_DEEPSEEK.modelo}</code>. No console do OpenCode, o
              dono da conta precisa liberar a região Global (Settings › Privacy › Regions), que o DeepSeek exige.
            </p>
          ) : (
            <>
              <Campo
                rotulo="Endereço do provedor"
                autoComplete="off"
                spellCheck={false}
                placeholder={estado.baseUrl}
                value={baseUrl}
                onChange={(evento) => setBaseUrl(evento.target.value)}
                erro={erros.baseUrl}
              />
              <Campo
                rotulo="Modelo"
                autoComplete="off"
                spellCheck={false}
                placeholder={estado.modelo}
                value={modelo}
                onChange={(evento) => setModelo(evento.target.value)}
                erro={erros.modelo}
              />
            </>
          )}
          <label className="flex items-start gap-2.5 text-sm font-semibold">
            <input type="checkbox" className="mt-0.5 size-4 shrink-0 accent-[var(--secao-cor)]" checked={raciocinio} onChange={(evento) => setRaciocinio(evento.target.checked)} />
            <span>
              Raciocínio do modelo
              <span className="block text-xs font-medium text-texto-2">
                O modelo pensa antes de responder. O raciocínio fica só no servidor; a conversa mostra um resumo curto, numa caixa que abre e fecha.
                Cada resposta demora uns segundos a mais e gasta mais do limite do provedor (o resumo é uma chamada curta a mais). Vale ao salvar a
                chave.
              </span>
            </span>
          </label>
          <p className="text-xs font-medium text-texto-2">
            A chave vai uma vez para a API, fica só na memória dela e some quando a API reinicia. Nunca volta inteira para a tela. Use só com
            dados fictícios.
          </p>
          {teste && (
            <Aviso tipo={teste.ok ? 'info' : 'falha'} titulo={teste.ok ? `Respondeu em ${teste.milissegundos} ms` : 'O provedor não respondeu'}>
              {teste.ok ? `${teste.modelo}: “${teste.resposta ?? ''}”` : teste.erro}
            </Aviso>
          )}
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div className="flex flex-col gap-3 sm:flex-row">
            <Botao type="submit" carregando={ocupado === 'salvar'} disabled={ocupado !== null} icone="ri-key-2-line" className="min-w-0 flex-1">
              Salvar chave
            </Botao>
            <Botao
              variante="secundario"
              carregando={ocupado === 'testar'}
              disabled={ocupado !== null}
              icone="ri-pulse-line"
              className="min-w-0 flex-1"
              onClick={() => executar('testar', async () => setTeste(await testarLlm()))}
            >
              Testar
            </Botao>
          </div>
          {estado.origem === 'DEBUG' && (
            <button
              type="button"
              disabled={ocupado !== null}
              onClick={() =>
                executar('remover', async () => {
                  await removerChaveDoLlm()
                  const lido = await obterEstadoDoLlm()
                  if (lido) mudou(lido)
                  setTeste(null)
                })
              }
              className="self-start text-xs font-semibold text-[#c8203f] hover:underline disabled:opacity-60"
            >
              Remover a chave posta pela tela
            </button>
          )}
        </form>
      </Modal>
    </>
  )
}
