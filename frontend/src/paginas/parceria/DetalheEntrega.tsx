// Detalhe de uma entrega (Figma F01, F02 e R01): versões com autor e data, novo envio, retorno da RBS e status. [ITEM-4] [UPLOAD]
import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ErroApi } from '../../api/cliente'
import {
  ACEITA_ENVIO,
  EXTENSOES_ACEITAS,
  TRANSICOES,
  baixarArquivo,
  enviarArquivo,
  enviarConteudo,
  listarEntregas,
  mudarStatusEntrega,
  problemaNoArquivo,
  type Arquivo,
  type Entrega,
  type VersaoDaEntrega,
} from '../../api/materiais'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { Campo, focarPrimeiroErro } from '../../componentes/Campo'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { enderecoSeguro, LinkExterno } from '../../componentes/LinkExterno'
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

/**
 * Ação da RBS em cada passagem de status, no lugar do nome do status de destino. [ITEM-4]
 * Ex.: de "Aprovado" para "Em análise" é reabrir a análise, não um status novo escolhido do zero.
 */
const ACOES: Partial<Record<StatusEntrega, Partial<Record<StatusEntrega, string>>>> = {
  NAO_INICIADO: { AGUARDANDO_ENVIO: 'Abrir para o envio da marca', NAO_APLICAVEL: 'Marcar como não aplicável' },
  AGUARDANDO_ENVIO: { NAO_APLICAVEL: 'Marcar como não aplicável' },
  ENVIADO: { EM_ANALISE: 'Colocar em análise', AJUSTE_SOLICITADO: 'Pedir ajuste à marca', APROVADO: 'Aprovar' },
  EM_ANALISE: { AJUSTE_SOLICITADO: 'Pedir ajuste à marca', APROVADO: 'Aprovar' },
  AJUSTE_SOLICITADO: { NAO_APLICAVEL: 'Marcar como não aplicável' },
  APROVADO: { EM_ANALISE: 'Reabrir a análise' },
  NAO_APLICAVEL: { NAO_INICIADO: 'Voltar a valer para a parceria' },
}

/** Estados em que a vez é da marca: a RBS espera o envio e só tem ações fora do fluxo normal. */
const ESPERANDO_A_MARCA: Partial<Record<StatusEntrega, string>> = {
  AGUARDANDO_ENVIO: 'Aguardando a marca enviar o material.',
  AJUSTE_SOLICITADO: 'Aguardando a marca enviar a versão corrigida.',
}

function Versao({ arquivo }: { arquivo: Arquivo }) {
  const daRbs = arquivo.enviadoPor?.perfil === 'ADMIN_RBS'
  const [falha, setFalha] = useState<string | null>(null)
  const [baixando, setBaixando] = useState(false)

  async function baixar() {
    setFalha(null)
    setBaixando(true)
    try {
      await baixarArquivo(arquivo)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar o arquivo.')
    } finally {
      setBaixando(false)
    }
  }

  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-borda py-3 last:border-b-0">
      <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-secao-tinta font-display text-lg text-secao-texto">
        v{arquivo.versao}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">{arquivo.nomeOriginal}</p>
        <p className="text-xs font-medium text-texto-2">
          {daRbs ? 'Retorno da RBS' : 'Envio da marca'} · {arquivo.enviadoPor?.nome ?? '—'} · {dataHora(arquivo.enviadoEm)} · {tamanho(arquivo.tamanhoBytes)}
          {NOMES_CATEGORIA[arquivo.categoria] && ` · ${NOMES_CATEGORIA[arquivo.categoria]}`}
        </p>
        {arquivo.observacao && <p className="mt-1 text-xs">“{arquivo.observacao}”</p>}
      </div>
      <button
        type="button"
        onClick={baixar}
        disabled={baixando}
        className="flex items-center gap-1 text-xs font-bold text-secao-texto hover:underline disabled:opacity-60"
      >
        <i className={`${baixando ? 'ri-loader-4-line animate-spin' : 'ri-download-line'} text-base`} aria-hidden="true" /> Baixar
      </button>
      {falha && <Aviso tipo="falha" titulo={falha} className="w-full" />}
    </li>
  )
}

/** Uma versão em texto ou em link (R5.3.a): o texto aparece inteiro, e o link abre em aba nova, só se for http(s). */
function VersaoDeConteudo({ versao }: { versao: VersaoDaEntrega }) {
  return (
    <li className="flex flex-wrap items-start gap-3 border-b border-borda py-3 last:border-b-0">
      <span className="grid size-9 shrink-0 place-items-center rounded-[10px] bg-secao-tinta text-secao-texto">
        <i className={`${versao.tipo === 'LINK' ? 'ri-link' : 'ri-text'} text-lg`} aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1 text-sm">
        <p className="font-semibold">
          v{versao.versao} · {versao.tipo === 'LINK' ? 'Link' : 'Texto'}
          {versao.titulo && ` · ${versao.titulo}`}
        </p>
        <p className="text-xs font-medium text-texto-2">
          {versao.enviadoPor?.nome ?? '—'} · {dataHora(versao.enviadoEm)}
        </p>
        {versao.tipo === 'LINK' && versao.url ? (
          <LinkExterno href={versao.url} className="mt-1 inline-flex text-sm font-semibold text-secao-texto hover:underline [overflow-wrap:anywhere]">
            {versao.url}
          </LinkExterno>
        ) : (
          <p className="mt-1 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">{versao.texto}</p>
        )}
        {versao.observacao && <p className="mt-1 text-xs">“{versao.observacao}”</p>}
      </div>
    </li>
  )
}

type Forma = 'ARQUIVO' | 'TEXTO' | 'LINK'

/** Texto ou link como material (rota 86). O texto não vai ao histórico; o link precisa ser http(s). */
function EnvioDeConteudo({ entrega, forma, aoEnviar }: { entrega: Entrega; forma: 'TEXTO' | 'LINK'; aoEnviar: () => void }) {
  const [titulo, setTitulo] = useState('')
  const [texto, setTexto] = useState('')
  const [url, setUrl] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    const novos: Record<string, string> = {}
    if (forma === 'TEXTO' && !texto.trim()) novos.texto = 'Escreva o material.'
    if (forma === 'LINK' && !enderecoSeguro(url.trim())) novos.url = 'Use um endereço que comece com https:// (ou http://).'
    setErros(novos)
    if (Object.keys(novos).length) return focarPrimeiroErro()
    setEnviando(true)
    setFalha(null)
    try {
      await enviarConteudo(entrega.id, {
        tipo: forma,
        ...(forma === 'TEXTO' ? { texto: texto.trim() } : { url: url.trim() }),
        titulo: titulo.trim() || undefined,
      })
      setTitulo('')
      setTexto('')
      setUrl('')
      aoEnviar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        setFalha(erro.message)
      } else setFalha('Não foi possível enviar.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
      <Campo rotulo="Título (opcional)" maxLength={160} value={titulo} onChange={(evento) => setTitulo(evento.target.value)} erro={erros.titulo} />
      {forma === 'TEXTO' ? (
        <AreaTexto rotulo="Texto do material" rows={5} maxLength={5000} value={texto} onChange={(evento) => setTexto(evento.target.value)} erro={erros.texto} />
      ) : (
        <Campo rotulo="Endereço do post ou do material" type="url" value={url} onChange={(evento) => setUrl(evento.target.value)} erro={erros.url} ajuda="Instagram, TikTok, YouTube ou outro endereço com https://." />
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div>
        <Botao type="submit" carregando={enviando} icone={forma === 'LINK' ? 'ri-link' : 'ri-text'}>
          {forma === 'LINK' ? 'Enviar link' : 'Enviar texto'}
        </Botao>
      </div>
    </form>
  )
}

function FormularioEnvio({ entrega, rbs, aoEnviar, aoEnviarConteudo }: { entrega: Entrega; rbs: boolean; aoEnviar: (arquivo: Arquivo) => void; aoEnviarConteudo: () => void }) {
  const [forma, setForma] = useState<Forma>('ARQUIVO')
  const categorias = rbs ? CATEGORIAS_RBS : CATEGORIAS_MARCA
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [categoria, setCategoria] = useState<string>(rbs ? 'RELATORIO_VEICULACAO' : entrega.abaCodigo === 'MIDIA' ? 'MATERIAL_MIDIA' : 'MATERIAL_MARCA')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  // Outro arquivo escolhido: o erro do anterior não vale mais.
  function escolher(novo: File | null) {
    setArquivo(novo)
    setFalha(novo ? problemaNoArquivo(novo) : null)
  }

  const problema = arquivo ? problemaNoArquivo(arquivo) : null

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!arquivo) return setFalha('Escolha o arquivo.')
    // [UPLOAD] A API confere de novo (extensão, conteúdo e tamanho); aqui só evita esperar um envio que vai falhar.
    const problema = problemaNoArquivo(arquivo)
    if (problema) return setFalha(problema)
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
    <div className="flex flex-col gap-4 rounded-2xl border border-borda p-4">
      <p className="text-sm font-bold">{rbs ? 'Publicar retorno da RBS' : entrega.arquivos.length ? 'Enviar nova versão' : 'Enviar material'}</p>
      {/* R5.3.a: o material vai em arquivo, em texto ou em link. */}
      <div role="radiogroup" aria-label="Forma do material" className="flex gap-2">
        {(['ARQUIVO', 'TEXTO', 'LINK'] as Forma[]).map((opcao) => (
          <button
            key={opcao}
            type="button"
            role="radio"
            aria-checked={forma === opcao}
            onClick={() => setForma(opcao)}
            className={`rounded-full border px-3 py-1.5 text-xs font-bold ${forma === opcao ? 'border-secao bg-secao text-white' : 'border-borda text-tinta hover:bg-cinza-100'}`}
          >
            {opcao === 'ARQUIVO' ? 'Arquivo' : opcao === 'TEXTO' ? 'Texto' : 'Link'}
          </button>
        ))}
      </div>
      {forma !== 'ARQUIVO' ? (
        <EnvioDeConteudo entrega={entrega} forma={forma} aoEnviar={aoEnviarConteudo} />
      ) : (
        <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
      <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-secao bg-secao-tinta px-4 py-3 text-sm focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-secao">
        <i className="ri-attachment-2 text-xl text-secao" aria-hidden="true" />
        <span className="flex-1 truncate font-semibold">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher arquivo (até 20 MB)'}</span>
        <input type="file" className="sr-only" accept={EXTENSOES_ACEITAS} onChange={(evento) => escolher(evento.target.files?.[0] ?? null)} />
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
        <Botao type="submit" carregando={enviando} disabled={!arquivo || problema !== null} icone="ri-upload-cloud-2-line">
          {rbs ? 'Publicar retorno' : 'Enviar material'}
        </Botao>
      </div>
        </form>
      )}
    </div>
  )
}

function MudancaStatus({ entrega, aoMudar }: { entrega: Entrega; aoMudar: (entrega: Entrega) => void }) {
  const opcoes = TRANSICOES[entrega.status]
  // Nada vem escolhido: "Registrar" sem escolher não pode virar um status que a RBS não pediu.
  const [para, setPara] = useState<StatusEntrega | ''>('')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [salvando, setSalvando] = useState(false)
  if (!opcoes.length) return null
  const exige = para ? precisaObservacao(entrega.status, para) : false

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    if (!para) return setFalha('Escolha o que fazer com esta entrega.')
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
      <div>
        <p className="text-sm font-bold">Análise da RBS</p>
        {ESPERANDO_A_MARCA[entrega.status] && <p className="mt-1 text-xs font-medium text-texto-2">{ESPERANDO_A_MARCA[entrega.status]}</p>}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Seletor rotulo="O que fazer" value={para} onChange={(evento) => setPara(evento.target.value as StatusEntrega)}>
          <option value="" disabled>
            Escolha uma ação
          </option>
          {opcoes.map((codigo) => (
            <option key={codigo} value={codigo}>
              {ACOES[entrega.status]?.[codigo] ?? nomeStatus(codigo)}
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
  // Com o detalhe aberto, voltar para a aba do navegador traz a versão e o status de agora: a outra ponta pode ter
  // enviado ou decidido nesse meio-tempo, e a RBS não deve registrar uma decisão sobre um dado velho.
  useEffect(() => {
    if (!inicial) return
    const { id, parceriaId, abaCodigo } = inicial
    const aoVoltar = () => {
      if (document.visibilityState !== 'visible') return
      listarEntregas(parceriaId, abaCodigo)
        .then((lista) => {
          const atual = lista.find((item) => item.id === id)
          if (atual) setEntrega(atual)
        })
        .catch(() => {})
    }
    document.addEventListener('visibilitychange', aoVoltar)
    return () => document.removeEventListener('visibilitychange', aoVoltar)
  }, [inicial])

  if (!entrega || !inicial) return null
  const secao = SECOES.find((item) => item.aba === entrega.abaCodigo) ?? SECOES[0]
  const podeEnviar = rbs || ACEITA_ENVIO.includes(entrega.status)
  // O recado vale enquanto o ajuste está pedido ou se é sobre a versão atual; depois do reenvio, ele fica no Histórico.
  const ultimoEnvio = entrega.arquivos[0]?.enviadoEm
  const recado =
    entrega.ultimaObservacao &&
    (entrega.status === 'AJUSTE_SOLICITADO' || !ultimoEnvio || new Date(entrega.ultimaObservacao.em) >= new Date(ultimoEnvio))
      ? entrega.ultimaObservacao
      : null

  return (
    <Modal titulo={entrega.titulo} legenda={`${secao.nome} · prazo ${data(entrega.prazo)}`} aberto aoFechar={aoFechar} largura="max-w-[760px]">
      <div data-secao={secao.chave} className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Status codigo={entrega.status} />
          <span className="text-xs font-medium text-texto-2">Atualizada em {dataHora(entrega.atualizadoEm)}</span>
        </div>
        {entrega.descricao && <p className="text-sm">{entrega.descricao}</p>}
        {recado && (
          <Aviso tipo={entrega.status === 'AJUSTE_SOLICITADO' ? 'falha' : 'info'} titulo={`Observação da RBS · ${recado.por}`}>
            {recado.texto} <span className="text-xs text-texto-2">({dataHora(recado.em)})</span>
          </Aviso>
        )}
        {recebido && (
          <Aviso titulo="Envio recebido">
            O arquivo {recebido.nomeOriginal} foi registrado como versão v{recebido.versao}. O status indica recebimento, não aprovação de conteúdo.
          </Aviso>
        )}

        <div>
          <p className="texto-sobretitulo mb-1 text-texto-2">Histórico de versões</p>
          {entrega.versoes?.length ? (
            // Arquivos, textos e links numa lista só, da versão mais nova para a mais antiga.
            <ul>
              {entrega.versoes.map((versao) => {
                const arquivo = versao.tipo === 'ARQUIVO' ? entrega.arquivos.find((item) => item.id === versao.id) : undefined
                return arquivo ? <Versao key={`a${versao.id}`} arquivo={arquivo} /> : <VersaoDeConteudo key={`${versao.tipo}${versao.id}`} versao={versao} />
              })}
            </ul>
          ) : entrega.arquivos.length ? (
            <ul>
              {entrega.arquivos.map((arquivo) => (
                <Versao key={arquivo.id} arquivo={arquivo} />
              ))}
            </ul>
          ) : (
            <Aviso tipo="vazio" titulo="Nenhum material enviado ainda" />
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
                // A lista de versões (arquivos, textos e links) vem de novo da API logo abaixo.
                versoes: undefined,
                atualizadoEm: arquivo.enviadoEm,
              })
              listarEntregas(entrega.parceriaId, entrega.abaCodigo)
                .then((lista) => {
                  const atual = lista.find((item) => item.id === entrega.id)
                  if (atual) setEntrega(atual)
                })
                .catch(() => {})
              aoMudar()
            }}
            aoEnviarConteudo={() => {
              setRecebido(null)
              listarEntregas(entrega.parceriaId, entrega.abaCodigo)
                .then((lista) => {
                  const atual = lista.find((item) => item.id === entrega.id)
                  if (atual) setEntrega(atual)
                })
                .catch(() => {})
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
