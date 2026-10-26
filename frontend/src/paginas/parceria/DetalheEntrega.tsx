// Detalhe de uma entrega (Figma F01, F02 e R01): versões com autor e data, novo envio, retorno da RBS e status. [ITEM-4] [UPLOAD]
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ErroApi } from '../../api/cliente'
import {
  ACEITA_ENVIO,
  EXTENSOES_ACEITAS,
  LIMITE_BYTES,
  TRANSICOES,
  enderecoArquivo,
  enviarArquivo,
  mudarStatusEntrega,
  type Arquivo,
  type Entrega,
} from '../../api/materiais'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { Status, nomeStatus, type StatusEntrega } from '../../componentes/Status'
import { SECOES } from '../../componentes/secoes'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora, tamanho } from '../../util/formatar'

const CATEGORIAS_MARCA = [
  ['MATERIAL_MARCA', 'Material da marca'],
  ['MATERIAL_MIDIA', 'Material de mídia'],
  ['DOCUMENTO_INGRESSOS', 'Documento de ingressos'],
  ['CONTRATO', 'Contrato'],
  ['OUTRO', 'Outro'],
] as const

const CATEGORIAS_RBS = [
  ['RELATORIO_VEICULACAO', 'Relatório de veiculação e links'],
  ['COMPROVACAO', 'Comprovação (foto pós-evento)'],
  ['PLANO_COMERCIAL', 'Plano comercial'],
  ['OUTRO', 'Outro retorno'],
] as const

export const NOMES_CATEGORIA: Record<string, string> = Object.fromEntries([
  ...CATEGORIAS_MARCA,
  ...CATEGORIAS_RBS,
  ['PESQUISA', 'Pesquisa'],
  ['RELATORIO_PARCERIA', 'Relatório da parceria'],
])

/** Exige observação ao pedir ajuste e ao reabrir uma entrega aprovada (docs/api.md, rota 19). */
function precisaObservacao(de: StatusEntrega, para: StatusEntrega) {
  return para === 'AJUSTE_SOLICITADO' || (de === 'APROVADO' && para === 'EM_ANALISE')
}

function Versao({ arquivo }: { arquivo: Arquivo }) {
  const daRbs = arquivo.enviadoPor?.perfil === 'ADMIN_RBS'
  return (
    <li className="flex items-center gap-4 border-b border-borda py-3 last:border-b-0">
      <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-secao-tinta font-display text-lg text-secao-texto">
        v{arquivo.versao}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">{arquivo.nomeOriginal}</p>
        <p className="text-xs font-medium text-texto-2">
          {daRbs ? 'Retorno da RBS' : 'Envio da marca'} · {arquivo.enviadoPor?.nome ?? '—'} · {dataHora(arquivo.enviadoEm)} · {tamanho(arquivo.tamanhoBytes)}
          {NOMES_CATEGORIA[arquivo.categoria] && ` · ${NOMES_CATEGORIA[arquivo.categoria]}`}
        </p>
      </div>
      <a href={enderecoArquivo(arquivo.id)} className="flex items-center gap-1 text-xs font-bold text-secao-texto hover:underline" download>
        <i className="ri-download-line text-base" aria-hidden="true" /> Baixar
      </a>
    </li>
  )
}

function FormularioEnvio({ entrega, rbs, aoEnviar }: { entrega: Entrega; rbs: boolean; aoEnviar: (arquivo: Arquivo) => void }) {
  const categorias = rbs ? CATEGORIAS_RBS : CATEGORIAS_MARCA
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [categoria, setCategoria] = useState<string>(rbs ? 'RELATORIO_VEICULACAO' : entrega.abaCodigo === 'MIDIA' ? 'MATERIAL_MIDIA' : 'MATERIAL_MARCA')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!arquivo) return setFalha('Escolha o arquivo.')
    // [UPLOAD] A API confere de novo (extensão, conteúdo e tamanho); aqui só evita esperar um envio que vai falhar.
    if (arquivo.size > LIMITE_BYTES) return setFalha('O arquivo passa de 20 MB.')
    setFalha(null)
    setEnviando(true)
    try {
      aoEnviar(await enviarArquivo(entrega.id, arquivo, { categoria, observacao: observacao.trim() || undefined }))
      setArquivo(null)
      setObservacao('')
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível enviar o arquivo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={enviar} className="flex flex-col gap-4 rounded-2xl border border-borda p-4">
      <p className="text-sm font-bold">{rbs ? 'Publicar retorno da RBS' : entrega.arquivos.length ? 'Enviar nova versão' : 'Enviar material'}</p>
      <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-secao bg-secao-tinta px-4 py-3 text-sm">
        <i className="ri-attachment-2 text-xl text-secao" aria-hidden="true" />
        <span className="flex-1 truncate font-semibold">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher arquivo (até 20 MB)'}</span>
        <input type="file" className="sr-only" accept={EXTENSOES_ACEITAS} onChange={(evento) => setArquivo(evento.target.files?.[0] ?? null)} />
      </label>
      <div className="grid gap-4 sm:grid-cols-2">
        <Seletor rotulo="Tipo de conteúdo" value={categoria} onChange={(evento) => setCategoria(evento.target.value)}>
          {categorias.map(([codigo, nome]) => (
            <option key={codigo} value={codigo}>
              {nome}
            </option>
          ))}
        </Seletor>
        <AreaTexto rotulo="Observações (opcional)" rows={1} maxLength={500} value={observacao} onChange={(evento) => setObservacao(evento.target.value)} />
      </div>
      {!rbs && (
        <Aviso titulo="Antes de enviar">
          Cada envio vira uma nova versão; as anteriores continuam disponíveis. O status passa a "Enviado", que indica recebimento, não aprovação.
        </Aviso>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div>
        <Botao type="submit" carregando={enviando} disabled={!arquivo} icone="ri-upload-cloud-2-line">
          {rbs ? 'Publicar retorno' : 'Enviar material'}
        </Botao>
      </div>
    </form>
  )
}

function MudancaStatus({ entrega, aoMudar }: { entrega: Entrega; aoMudar: (entrega: Entrega) => void }) {
  const opcoes = TRANSICOES[entrega.status]
  const [para, setPara] = useState<StatusEntrega | ''>(opcoes[0] ?? '')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [salvando, setSalvando] = useState(false)
  if (!opcoes.length) return null
  const exige = para ? precisaObservacao(entrega.status, para) : false

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    if (!para) return
    if (exige && !observacao.trim()) return setFalha('Escreva a observação para a marca.')
    setFalha(null)
    setSalvando(true)
    try {
      aoMudar(await mudarStatusEntrega(entrega.id, para, observacao.trim() || undefined))
      setObservacao('')
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível mudar o status.')
    } finally {
      setSalvando(false)
    }
  }

  return (
    <form onSubmit={salvar} className="flex flex-col gap-4 rounded-2xl border border-borda p-4">
      <p className="text-sm font-bold">Análise da RBS</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <Seletor rotulo="Novo status" value={para} onChange={(evento) => setPara(evento.target.value as StatusEntrega)}>
          {opcoes.map((codigo) => (
            <option key={codigo} value={codigo}>
              {nomeStatus(codigo)}
            </option>
          ))}
        </Seletor>
        <AreaTexto
          rotulo={exige ? 'Observação para a marca' : 'Observação (opcional)'}
          rows={1}
          maxLength={500}
          value={observacao}
          onChange={(evento) => setObservacao(evento.target.value)}
        />
      </div>
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div>
        <Botao type="submit" carregando={salvando}>
          Registrar
        </Botao>
      </div>
    </form>
  )
}

type Props = {
  entrega: Entrega | null
  aoFechar: () => void
  /** Avisa a lista para buscar de novo depois de um envio ou mudança de status. */
  aoMudar: () => void
}

export function DetalheEntrega({ entrega: inicial, aoFechar, aoMudar }: Props) {
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const [entrega, setEntrega] = useState<Entrega | null>(inicial)
  const [recebido, setRecebido] = useState<Arquivo | null>(null)
  const [anterior, setAnterior] = useState<Entrega | null>(inicial)

  // Outra entrega foi aberta: recomeça o estado do modal (padrão do React para estado derivado de prop).
  if (inicial !== anterior) {
    setAnterior(inicial)
    setEntrega(inicial)
    setRecebido(null)
  }
  if (!entrega || !inicial) return null
  const secao = SECOES.find((item) => item.aba === entrega.abaCodigo) ?? SECOES[0]
  const podeEnviar = rbs || ACEITA_ENVIO.includes(entrega.status)

  return (
    <Modal titulo={entrega.titulo} legenda={`${secao.nome} · prazo ${data(entrega.prazo)}`} aberto aoFechar={aoFechar} largura="max-w-[760px]">
      <div data-secao={secao.chave} className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Status codigo={entrega.status} />
          <span className="text-xs font-medium text-texto-2">Atualizada em {dataHora(entrega.atualizadoEm)}</span>
        </div>
        {entrega.descricao && <p className="text-sm">{entrega.descricao}</p>}
        {entrega.ultimaObservacao && (
          <Aviso tipo={entrega.status === 'AJUSTE_SOLICITADO' ? 'falha' : 'info'} titulo={`Observação da RBS · ${entrega.ultimaObservacao.por}`}>
            {entrega.ultimaObservacao.texto} <span className="text-xs text-texto-2">({dataHora(entrega.ultimaObservacao.em)})</span>
          </Aviso>
        )}
        {recebido && (
          <Aviso titulo="Envio recebido">
            O arquivo {recebido.nomeOriginal} foi registrado como versão v{recebido.versao}. O status indica recebimento, não aprovação de conteúdo.
          </Aviso>
        )}

        <div>
          <p className="texto-sobretitulo mb-1 text-texto-2">Histórico de versões</p>
          {entrega.arquivos.length ? (
            <ul>
              {entrega.arquivos.map((arquivo) => (
                <Versao key={arquivo.id} arquivo={arquivo} />
              ))}
            </ul>
          ) : (
            <Aviso tipo="vazio" titulo="Nenhum arquivo enviado ainda" />
          )}
        </div>

        {podeEnviar ? (
          <FormularioEnvio
            entrega={entrega}
            rbs={rbs}
            aoEnviar={(arquivo) => {
              setRecebido(arquivo)
              setEntrega({
                ...entrega,
                status: arquivo.statusDaEntrega ?? entrega.status,
                arquivos: [arquivo, ...entrega.arquivos],
                atualizadoEm: arquivo.enviadoEm,
              })
              aoMudar()
            }}
          />
        ) : (
          <Aviso tipo="restrito" titulo="Esta entrega não recebe envios agora">
            Ela está como “{nomeStatus(entrega.status)}”. Se precisar mudar algo, fale com a RBS.
          </Aviso>
        )}

        {rbs && (
          <MudancaStatus
            key={entrega.status}
            entrega={entrega}
            aoMudar={(nova) => {
              setEntrega(nova)
              aoMudar()
            }}
          />
        )}

        <Link to={`/parceria/${entrega.parceriaId}/historico`} className="text-xs font-bold text-secao-texto hover:underline">
          Ver o histórico completo da parceria →
        </Link>
      </div>
    </Modal>
  )
}
