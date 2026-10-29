// Cadastro de patrocinador, cota e parceria pela RBS (protótipo R04), com os e-mails que poderão entrar. [ITEM-3] [VALIDACAO]
// O código de ativação de cada e-mail aparece só aqui, uma vez; a RBS repassa ao patrocinador.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { criarParceria, listarParcerias, type Evento, type ParceriaCriada, type Vinculo } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao, LinkBotao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { CodigoAtivacao } from '../../componentes/CodigoAtivacao'
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
}

function linhas(texto: string) {
  return texto
    .split(/[\n,;]+/)
    .map((linha) => linha.trim().toLowerCase())
    .filter(Boolean)
}

// Fechado, o formulário sai da tela inteiro: cada abertura começa vazia, sem o cadastro anterior.
export function CadastroPatrocinador({ aberto, ...resto }: Props) {
  return aberto ? <Formulario {...resto} /> : null
}

function Formulario({ aoFechar, aoCriar, eventos }: Omit<Props, 'aberto'>) {
  // A lista "Patrocinador" traz todas as marcas cadastradas, sem depender do filtro de edição da tela.
  const todas = useDados(() => listarParcerias(), [])
  const existentes = [...new Map((todas.dado ?? []).map((item) => [item.patrocinador.id, item.patrocinador])).values()].sort((a, b) =>
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

  const fechar = aoFechar

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    const marca = linhas(emailsMarca)
    const agencia = linhas(emailsAgencia)
    const novos: Record<string, string> = {}
    // [VALIDACAO] As mesmas regras da API, para o erro aparecer junto ao campo antes de enviar.
    if (patrocinadorId === 'novo' && nome.trim().length < 2) novos.nome = 'Informe o nome do patrocinador.'
    if (!eventoId) novos.eventoId = 'Escolha a edição.'
    const invalidos = [...marca, ...agencia].filter((email) => !EMAIL_VALIDO.test(email))
    if (invalidos.length) novos.emails = `E-mail inválido: ${invalidos.join(', ')}. Use o formato nome@empresa.com.br.`
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
      <Modal titulo="Parceria cadastrada" legenda={`${criada.patrocinador.nome} · ${criada.cota.nome} · ${criada.evento.edicao}`} aberto aoFechar={fechar} largura="max-w-[720px]">
        {criada.emailsAutorizados.length ? (
          <Aviso titulo="Repasse os códigos de ativação agora">
            Cada pessoa usa o código no primeiro acesso para criar a própria senha. Os códigos não aparecem de novo; se perder,
            gere outro em Perfil.
          </Aviso>
        ) : (
          <Aviso tipo="ausente" titulo="Nenhum e-mail autorizado ainda">
            Autorize os e-mails da marca e da agência no Perfil da parceria; o código de cada um aparece lá.
          </Aviso>
        )}
        <Tabela
          colunas={[
            { titulo: 'E-mail', celula: (linha) => linha.email },
            { titulo: 'Vínculo', celula: (linha) => (linha.vinculo === 'MARCA' ? 'Marca' : 'Agência') },
            { titulo: 'Código', celula: (linha) => <CodigoAtivacao codigo={linha.codigoAtivacao} /> },
            { titulo: 'Vale até', celula: (linha) => dataHora(linha.codigoExpiraEm) },
          ]}
          linhas={criada.emailsAutorizados}
          chave={(linha) => linha.id}
          vazio="Nenhum e-mail autorizado ainda. Dá para autorizar depois, no Perfil da parceria."
        />
        <Aviso titulo="Próximo passo: abrir as entregas">
          A parceria nasce sem entregas, e a marca só consegue enviar material para uma entrega aberta pela RBS. Abra as entregas da
          cota em cada aba da parceria, pelo botão “Nova entrega”.
        </Aviso>
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
    <Modal titulo="Cadastrar patrocinador" legenda="Patrocinador, cota, edição e e-mails autorizados" aberto aoFechar={fechar} largura="max-w-[720px]">
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
          Cada cota abre as abas que ela inclui: a Pesquisa da ativação é só da cota Master. Dá para fechar e reabrir abas depois, no Perfil.
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
