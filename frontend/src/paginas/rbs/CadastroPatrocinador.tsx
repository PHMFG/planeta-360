// Cadastro de patrocinador, cota e parceria pela RBS (protótipo R04), com os e-mails que poderão entrar. [ITEM-3] [VALIDACAO]
// O código de ativação de cada e-mail aparece só aqui, uma vez; a RBS repassa ao patrocinador.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { criarParceria, type Evento, type ParceriaCriada, type ParceriaNaLista, type Vinculo } from '../../api/parcerias'
import { Aviso } from '../../componentes/Aviso'
import { Botao, LinkBotao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { Tabela } from '../../componentes/Tabela'
import { dataHora } from '../../util/formatar'

const EMAIL_VALIDO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const LIMITE_EMAILS = 10

type Props = {
  aberto: boolean
  aoFechar: () => void
  aoCriar: () => void
  eventos: Evento[]
  parcerias: ParceriaNaLista[]
}

function linhas(texto: string) {
  return texto
    .split(/[\n,;]+/)
    .map((linha) => linha.trim().toLowerCase())
    .filter(Boolean)
}

export function CadastroPatrocinador({ aberto, aoFechar, aoCriar, eventos, parcerias }: Props) {
  const existentes = [...new Map(parcerias.map((item) => [item.patrocinador.id, item.patrocinador])).values()].sort((a, b) =>
    a.nome.localeCompare(b.nome),
  )
  const [patrocinadorId, setPatrocinadorId] = useState('novo')
  const [nome, setNome] = useState('')
  const [segmento, setSegmento] = useState('')
  const [eventoId, setEventoId] = useState(String(eventos[0]?.id ?? ''))
  const [cota, setCota] = useState('MASTER')
  const [produto, setProduto] = useState('')
  const [emailsMarca, setEmailsMarca] = useState('')
  const [emailsAgencia, setEmailsAgencia] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [criada, setCriada] = useState<ParceriaCriada | null>(null)

  function fechar() {
    setCriada(null)
    setFalha(null)
    setErros({})
    aoFechar()
  }

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    const marca = linhas(emailsMarca)
    const agencia = linhas(emailsAgencia)
    const novos: Record<string, string> = {}
    // [VALIDACAO] As mesmas regras da API, para o erro aparecer junto ao campo antes de enviar.
    if (patrocinadorId === 'novo' && nome.trim().length < 2) novos.nome = 'Informe o nome do patrocinador.'
    if (!eventoId) novos.eventoId = 'Escolha a edição.'
    const invalidos = [...marca, ...agencia].filter((email) => !EMAIL_VALIDO.test(email))
    if (invalidos.length) novos.emails = `Confira: ${invalidos.join(', ')}`
    if (marca.length + agencia.length > LIMITE_EMAILS) novos.emails = `No máximo ${LIMITE_EMAILS} e-mails por parceria.`
    setErros(novos)
    setFalha(null)
    if (Object.keys(novos).length) return

    setEnviando(true)
    try {
      const resposta = await criarParceria({
        patrocinador:
          patrocinadorId === 'novo'
            ? { nome: nome.trim(), ...(segmento.trim() ? { segmento: segmento.trim() } : {}) }
            : { id: Number(patrocinadorId) },
        eventoId: Number(eventoId),
        cotaCodigo: cota as ParceriaCriada['cota']['codigo'],
        ...(produto.trim() ? { nomeProduto: produto.trim() } : {}),
        emails: [
          ...marca.map((email) => ({ email, vinculo: 'MARCA' as Vinculo })),
          ...agencia.map((email) => ({ email, vinculo: 'AGENCIA' as Vinculo })),
        ],
      })
      setCriada(resposta)
      aoCriar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setFalha(erro.message)
        setErros(erro.campos)
      } else setFalha('Não foi possível cadastrar agora.')
    } finally {
      setEnviando(false)
    }
  }

  if (criada) {
    return (
      <Modal titulo="Parceria cadastrada" legenda={`${criada.patrocinador.nome} · ${criada.cota.nome} · ${criada.evento.edicao}`} aberto={aberto} aoFechar={fechar} largura="max-w-[720px]">
        <Aviso titulo="Repasse os códigos de ativação agora">
          Cada pessoa usa o código no primeiro acesso para criar a própria senha. Os códigos não aparecem de novo; se perder,
          gere outro em Perfil.
        </Aviso>
        <Tabela
          colunas={[
            { titulo: 'E-mail', celula: (linha) => linha.email },
            { titulo: 'Vínculo', celula: (linha) => (linha.vinculo === 'MARCA' ? 'Marca' : 'Agência') },
            { titulo: 'Código', celula: (linha) => <code className="font-bold">{linha.codigoAtivacao ?? '—'}</code> },
            { titulo: 'Vale até', celula: (linha) => dataHora(linha.codigoExpiraEm) },
          ]}
          linhas={criada.emailsAutorizados}
          chave={(linha) => linha.id}
          vazio="Nenhum e-mail autorizado ainda. Dá para autorizar depois, no Perfil da parceria."
        />
        <div className="flex flex-wrap justify-end gap-4">
          <Botao variante="secundario" onClick={fechar}>
            Fechar
          </Botao>
          <LinkBotao to={`/parceria/${criada.id}/perfil`}>Abrir a parceria</LinkBotao>
        </div>
      </Modal>
    )
  }

  return (
    <Modal titulo="Cadastrar patrocinador" legenda="Patrocinador, cota, edição e e-mails autorizados" aberto={aberto} aoFechar={fechar} largura="max-w-[720px]">
      <form className="flex flex-col gap-4" onSubmit={enviar} noValidate>
        <Seletor rotulo="Patrocinador" value={patrocinadorId} onChange={(evento) => setPatrocinadorId(evento.target.value)}>
          <option value="novo">Novo patrocinador</option>
          {existentes.map((item) => (
            <option key={item.id} value={item.id}>
              {item.nome}
            </option>
          ))}
        </Seletor>
        {patrocinadorId === 'novo' && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Campo rotulo="Nome do patrocinador" value={nome} onChange={(evento) => setNome(evento.target.value)} erro={erros.nome ?? erros['patrocinador.nome']} />
            <Campo rotulo="Segmento (opcional)" value={segmento} onChange={(evento) => setSegmento(evento.target.value)} />
          </div>
        )}
        <div className="grid gap-4 sm:grid-cols-3">
          <Seletor rotulo="Edição" value={eventoId} onChange={(evento) => setEventoId(evento.target.value)} erro={erros.eventoId}>
            {eventos.map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
          <Seletor rotulo="Cota" value={cota} onChange={(evento) => setCota(evento.target.value)} erro={erros.cotaCodigo}>
            <option value="MASTER">Master</option>
            <option value="SUBCOTA_G">Subcota G</option>
            <option value="SUBCOTA">Subcota</option>
          </Seletor>
          <Campo rotulo="Produto (opcional)" value={produto} onChange={(evento) => setProduto(evento.target.value)} erro={erros.nomeProduto} />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <AreaTexto
            rotulo="E-mails da marca"
            placeholder={'marketing@marca.com.br\numa linha por e-mail'}
            value={emailsMarca}
            onChange={(evento) => setEmailsMarca(evento.target.value)}
            erro={erros.emails}
          />
          <AreaTexto
            rotulo="E-mails da agência"
            placeholder="atendimento@agencia.com.br"
            value={emailsAgencia}
            onChange={(evento) => setEmailsAgencia(evento.target.value)}
            ajuda={`Até ${LIMITE_EMAILS} e-mails no total. Pode autorizar depois.`}
          />
        </div>
        <Aviso titulo="Abas que abrem com a cota">
          Todas as cotas liberam as sete abas; a Pesquisa da ativação só abre para a cota Master. Dá para ajustar depois no Perfil.
        </Aviso>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-4">
          <Botao variante="secundario" onClick={fechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando}>
            Cadastrar
          </Botao>
        </div>
      </form>
    </Modal>
  )
}
