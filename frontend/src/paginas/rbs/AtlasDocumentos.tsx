// Aba Documentos do Atlas na gestão da RBS: envio, estado da indexação, aprovar, recusar, revogar, reindexar e os trechos (rotas 54 a 58). [ATLAS]
// A indexação roda em segundo plano e a tela não é em tempo real: ela busca de novo ao voltar à aba do navegador ou no botão Atualizar.
import { useState, type FormEvent, type ReactNode } from 'react'
import { ErroApi } from '../../api/cliente'
import {
  enviarDocumento,
  listarDocumentos,
  listarTrechos,
  mudarStatusDoDocumento,
  type AcaoDoDocumento,
  type CodigoDaCota,
  type Documento,
  type EscopoDoDocumento,
  type StatusDoDocumento,
  type TipoDoDocumento,
} from '../../api/documentos'
import { listarEventos, listarParcerias, type Evento } from '../../api/parcerias'
import { useDados, type Dados } from '../../api/useDados'
import { duracao } from '../../atlas/Diagnostico'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { Carregando } from '../../componentes/Carregando'
import { Cartao } from '../../componentes/Cartao'
import { AreaTexto, Busca, Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { Modal } from '../../componentes/Modal'
import { nomeStatus, Status } from '../../componentes/Status'
import { dataHora, numero, tamanho } from '../../util/formatar'

const TIPOS: { codigo: TipoDoDocumento; nome: string }[] = [
  { codigo: 'MANUAL_OPERACOES', nome: 'Manual de operações' },
  { codigo: 'PLANO_COMERCIAL', nome: 'Plano comercial' },
  { codigo: 'ANEXO_PARCERIA', nome: 'Anexo da parceria' },
  { codigo: 'FAQ', nome: 'Perguntas frequentes' },
  { codigo: 'COMUNICADO', nome: 'Comunicado' },
  { codigo: 'OUTRO', nome: 'Outro' },
]

const ESCOPOS: { codigo: EscopoDoDocumento; nome: string }[] = [
  { codigo: 'GLOBAL', nome: 'Todas as parcerias da edição' },
  { codigo: 'COTA', nome: 'Uma cota da edição' },
  { codigo: 'PARCERIA', nome: 'Uma parceria' },
]

const COTAS: { codigo: CodigoDaCota; nome: string }[] = [
  { codigo: 'MASTER', nome: 'Master' },
  { codigo: 'SUBCOTA_G', nome: 'Subcota G' },
  { codigo: 'SUBCOTA', nome: 'Subcota' },
]

const STATUS: StatusDoDocumento[] = ['AGUARDANDO_APROVACAO', 'PROCESSANDO', 'INDEXADO', 'ERRO', 'SUBSTITUIDO', 'REVOGADO', 'RECUSADO']

const ACOES: Record<AcaoDoDocumento, { nome: string; titulo: string; texto: (documento: Documento) => string }> = {
  APROVAR: {
    nome: 'Aprovar',
    titulo: 'Aprovar o documento',
    texto: (documento) =>
      documento.versao > 1
        ? 'A indexação começa agora. Quando terminar, esta versão entra no Atlas e a anterior sai ao mesmo tempo.'
        : 'A indexação começa agora. Quando terminar, o documento entra no Atlas para quem está no escopo dele.',
  },
  RECUSAR: {
    nome: 'Recusar',
    titulo: 'Recusar o documento',
    texto: () => 'O documento não entra no Atlas, e a recusa é final. Para corrigir, envie o arquivo de novo.',
  },
  REVOGAR: {
    nome: 'Revogar',
    titulo: 'Revogar o documento',
    texto: (documento) =>
      documento.status === 'SUBSTITUIDO'
        ? 'Esta versão já não entra nas respostas. Ao revogar, ela também deixa de abrir para a marca, e a revogação é final.'
        : 'Os trechos saem do Atlas na hora, e a revogação é final. Para voltar a usar o documento, envie de novo.',
  },
  REINDEXAR: {
    nome: 'Reindexar',
    titulo: 'Reindexar o documento',
    texto: () => 'A indexação recomeça do início. Uma versão anterior que esteja no ar continua até esta terminar.',
  },
}

function alcance(documento: Documento) {
  const edicao = documento.evento.edicao
  if (documento.escopo === 'COTA') return `Cota ${documento.cota?.nome ?? ''} · ${edicao}`
  if (documento.escopo === 'PARCERIA') return `${documento.parceria?.patrocinador ?? 'Parceria'} · ${edicao}`
  return `Todas as parcerias · ${edicao}`
}

function EnvioDeDocumento({ eventos, aoEnviar }: { eventos: Dados<Evento[]>; aoEnviar: () => void }) {
  const [arquivo, setArquivo] = useState<File | null>(null)
  // O campo é recriado depois de cada envio: sem isso, escolher de novo o mesmo arquivo não dispara o onChange.
  const [campoDoArquivo, setCampoDoArquivo] = useState(0)
  const [titulo, setTitulo] = useState('')
  const [chave, setChave] = useState('')
  const [tipo, setTipo] = useState<TipoDoDocumento>('MANUAL_OPERACOES')
  const [escopo, setEscopo] = useState<EscopoDoDocumento>('GLOBAL')
  const [eventoId, setEventoId] = useState('')
  const [cota, setCota] = useState<CodigoDaCota>('MASTER')
  const [parceriaId, setParceriaId] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [enviado, setEnviado] = useState<Documento | null>(null)
  const edicao = eventoId || String(eventos.dado?.[0]?.id ?? '')
  const parcerias = useDados(() => (escopo === 'PARCERIA' && edicao ? listarParcerias({ eventoId: Number(edicao) }) : Promise.resolve([])), [escopo, edicao])

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!arquivo) return setFalha('Escolha o documento.')
    setFalha(null)
    setErros({})
    setEnviado(null)
    setEnviando(true)
    try {
      const criado = await enviarDocumento({
        arquivo,
        titulo: titulo.trim(),
        chave: chave.trim(),
        tipo,
        escopo,
        eventoId: escopo !== 'PARCERIA' && edicao ? Number(edicao) : undefined,
        cota: escopo === 'COTA' ? cota : undefined,
        parceriaId: escopo === 'PARCERIA' && parceriaId ? Number(parceriaId) : undefined,
      })
      setEnviado(criado)
      setArquivo(null)
      setCampoDoArquivo((atual) => atual + 1)
      setTitulo('')
      setChave('')
      aoEnviar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        // O erro de um campo aparece junto dele; o do arquivo inteiro, ou sem campo (413, 415), fica no aviso.
        setFalha(erro.campos.arquivo ?? (Object.keys(erro.campos).length > 0 ? null : erro.message))
      } else setFalha('Não foi possível enviar o documento.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Enviar documento" className="min-w-0">
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-tinta bg-cinza-100 px-4 py-4 text-sm focus-within:border-secao">
          <i className="ri-file-text-line text-2xl" aria-hidden="true" />
          <span className="min-w-0 flex-1 font-semibold [overflow-wrap:anywhere]">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher o documento (PDF ou TXT, até 20 MB)'}</span>
          <input
            key={campoDoArquivo}
            type="file"
            accept=".pdf,.txt,application/pdf,text/plain"
            className="sr-only"
            onChange={(campo) => {
              setArquivo(campo.target.files?.[0] ?? null)
              setFalha(null)
            }}
          />
        </label>
        <div className="grid grid-cols-[minmax(0,1fr)] gap-4 md:grid-cols-2">
          <Campo rotulo="Título" value={titulo} onChange={(campo) => setTitulo(campo.target.value)} erro={erros.titulo} ajuda="É o nome que a marca vê." maxLength={200} />
          <Campo
            rotulo="Chave"
            value={chave}
            onChange={(campo) => setChave(campo.target.value)}
            erro={erros.chave}
            ajuda="Versões do mesmo documento usam a mesma chave. Minúsculas sem acento, números e hífen: manual-operacoes."
            maxLength={80}
            autoCapitalize="none"
            spellCheck={false}
          />
          <Seletor rotulo="Tipo" value={tipo} erro={erros.tipo} onChange={(campo) => setTipo(campo.target.value as TipoDoDocumento)}>
            {TIPOS.map((item) => (
              <option key={item.codigo} value={item.codigo}>
                {item.nome}
              </option>
            ))}
          </Seletor>
          <Seletor rotulo="Quem enxerga" value={escopo} erro={erros.escopo} onChange={(campo) => setEscopo(campo.target.value as EscopoDoDocumento)}>
            {ESCOPOS.map((item) => (
              <option key={item.codigo} value={item.codigo}>
                {item.nome}
              </option>
            ))}
          </Seletor>
          <Seletor
            rotulo="Edição"
            value={edicao}
            erro={erros.eventoId}
            onChange={(campo) => {
              setEventoId(campo.target.value)
              setParceriaId('')
            }}
          >
            {(eventos.dado ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
          {escopo === 'COTA' && (
            <Seletor rotulo="Cota" value={cota} erro={erros.cota} onChange={(campo) => setCota(campo.target.value as CodigoDaCota)}>
              {COTAS.map((item) => (
                <option key={item.codigo} value={item.codigo}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
          )}
          {escopo === 'PARCERIA' && (
            <Seletor rotulo="Parceria" value={parceriaId} erro={erros.parceriaId} onChange={(campo) => setParceriaId(campo.target.value)}>
              <option value="">Escolha a parceria</option>
              {(parcerias.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.patrocinador.nome} · {item.cota.nome}
                </option>
              ))}
            </Seletor>
          )}
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {enviado && <ResultadoDoEnvio documento={enviado} />}
        <div>
          <Botao type="submit" carregando={enviando} disabled={!arquivo} icone="ri-upload-cloud-2-line" className="max-sm:w-full">
            Enviar
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}

function ResultadoDoEnvio({ documento }: { documento: Documento }) {
  if (documento.status === 'ERRO') {
    return (
      <Aviso tipo="falha" titulo={`${documento.titulo}: não foi possível ler o arquivo`}>
        {documento.erro} Corrija o arquivo e envie de novo.
      </Aviso>
    )
  }
  if (documento.contemDadosPessoais) {
    return (
      <Aviso tipo="restrito" titulo={`${documento.titulo}: parece ter dados pessoais`}>
        Ele não pode ser aprovado. Tire os dados pessoais e envie de novo.
      </Aviso>
    )
  }
  return <Aviso titulo={`${documento.titulo}: versão ${documento.versao} enviada`}>Agora ele espera a aprovação, na lista abaixo.</Aviso>
}

function Dado({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-texto-2">{rotulo}</dt>
      <dd className="font-semibold [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

function LinhaDoDocumento({ documento, aoAgir, aoVerTrechos }: { documento: Documento; aoAgir: (acao: AcaoDoDocumento) => void; aoVerTrechos: () => void }) {
  return (
    <li className="flex flex-col gap-3 border-b border-borda py-4 first:pt-0 last:border-b-0 last:pb-0" aria-label={`${documento.titulo}, versão ${documento.versao}`}>
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1">
        <div className="min-w-0">
          <p className="text-sm font-semibold [overflow-wrap:anywhere]">{documento.titulo}</p>
          <p className="text-xs text-texto-2 [overflow-wrap:anywhere]">
            {documento.chave} · versão {documento.versao} · {alcance(documento)}
          </p>
        </div>
        <Status codigo={documento.status} />
      </div>
      {documento.erro && <p className="text-xs font-semibold text-[#c8203f]">{documento.erro}</p>}
      {documento.contemDadosPessoais && <p className="text-xs font-semibold text-[#c8203f]">Parece ter dados pessoais: não pode ser aprovado. Tire os dados e envie de novo.</p>}
      {documento.modeloDiferente && <p className="text-xs font-semibold text-[#8a6a00]">Indexado com outro modelo de embeddings: a busca o ignora.</p>}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs sm:grid-cols-3 lg:grid-cols-6">
        <Dado rotulo="Páginas">{documento.paginas === null ? '—' : numero(documento.paginas)}</Dado>
        <Dado rotulo="Trechos">{numero(documento.trechos)}</Dado>
        <Dado rotulo="Modelo">{documento.modelo ? `${documento.modelo} · ${documento.dimensoes ?? '—'} dimensões` : '—'}</Dado>
        <Dado rotulo="Extração">{documento.extracaoMs === null ? '—' : duracao(documento.extracaoMs)}</Dado>
        <Dado rotulo="Embeddings">{documento.embeddingsMs === null ? '—' : duracao(documento.embeddingsMs)}</Dado>
        <Dado rotulo="Enviado em">{dataHora(documento.enviadoEm)}</Dado>
      </dl>
      {(documento.acoes.length > 0 || documento.trechos > 0) && (
        <div className="flex flex-wrap gap-2">
          {documento.acoes.map((acao) => (
            <Botao key={acao} variante={acao === 'APROVAR' ? 'primario' : 'secundario'} className="h-10 min-w-0 px-4" onClick={() => aoAgir(acao)}>
              {ACOES[acao].nome}
            </Botao>
          ))}
          {documento.trechos > 0 && (
            <Botao variante="secundario" className="h-10 min-w-0 px-4" onClick={aoVerTrechos}>
              Ver trechos
            </Botao>
          )}
        </div>
      )}
    </li>
  )
}

function ModalDaAcao({ documento, acao, aoFechar, aoConcluir }: { documento: Documento; acao: AcaoDoDocumento; aoFechar: () => void; aoConcluir: () => void }) {
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [erroDoCampo, setErroDoCampo] = useState<string | undefined>()
  const [enviando, setEnviando] = useState(false)

  async function confirmar(evento: FormEvent) {
    evento.preventDefault()
    setFalha(null)
    setErroDoCampo(undefined)
    setEnviando(true)
    try {
      await mudarStatusDoDocumento(documento.id, acao, observacao.trim())
      aoConcluir()
    } catch (erro) {
      if (erro instanceof ErroApi && erro.campos.observacao) setErroDoCampo(erro.campos.observacao)
      else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível concluir o pedido.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo={ACOES[acao].titulo} legenda={`${documento.titulo} · versão ${documento.versao}`} aoFechar={aoFechar}>
      <form onSubmit={confirmar} className="flex flex-col gap-4" noValidate>
        <p className="text-sm">{ACOES[acao].texto(documento)}</p>
        <AreaTexto rotulo="Observação (opcional)" value={observacao} onChange={(campo) => setObservacao(campo.target.value)} erro={erroDoCampo} ajuda="Fica no histórico do documento." maxLength={500} />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0 max-sm:flex-1">
            {ACOES[acao].nome}
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

function ModalDosTrechos({ documento, aoFechar }: { documento: Documento; aoFechar: () => void }) {
  const [pagina, setPagina] = useState(0)
  const trechos = useDados(() => listarTrechos(documento.id, pagina), [documento.id, pagina])
  return (
    <Modal aberto titulo="Trechos do documento" legenda={`${documento.titulo} · versão ${documento.versao}`} aoFechar={aoFechar} largura="max-w-[720px] min-w-0">
      <Carregando dados={trechos} vazio={(dado) => dado.itens.length === 0} textoVazio="Este documento não tem trechos.">
        {(dado) => (
          <>
            <ol className="flex flex-col">
              {dado.itens.map((trecho) => (
                <li key={trecho.id} className="flex flex-col gap-1 border-b border-borda py-3 first:pt-0 last:border-b-0">
                  <p className="flex flex-wrap gap-x-2 text-xs font-semibold text-texto-2">
                    <span>Trecho {numero(trecho.ordem)}</span>
                    <span>· {trecho.paginaInicio === trecho.paginaFim ? `página ${trecho.paginaInicio}` : `páginas ${trecho.paginaInicio} a ${trecho.paginaFim}`}</span>
                    <span>· {numero(trecho.tokens)} tokens</span>
                    {!trecho.ativo && <span className="text-[#c8203f]">· fora do ar</span>}
                  </p>
                  {trecho.secao && <p className="text-sm font-semibold [overflow-wrap:anywhere]">{trecho.secao}</p>}
                  <p className="text-sm whitespace-pre-line [overflow-wrap:anywhere]">
                    {trecho.inicioDoTexto}
                    {trecho.inicioDoTexto.length >= 240 && '…'}
                  </p>
                </li>
              ))}
            </ol>
            <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
          </>
        )}
      </Carregando>
    </Modal>
  )
}

export function AbaDocumentos() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const [escopo, setEscopo] = useState('')
  const [status, setStatus] = useState('')
  const [tipo, setTipo] = useState('')
  const [titulo, setTitulo] = useState('')
  const [pagina, setPagina] = useState(0)
  const [pedido, setPedido] = useState<{ documento: Documento; acao: AcaoDoDocumento } | null>(null)
  const [trechosDe, setTrechosDe] = useState<Documento | null>(null)
  const documentos = useDados(
    () => listarDocumentos({ eventoId: eventoId ? Number(eventoId) : undefined, escopo: escopo || undefined, status: status || undefined, tipo: tipo || undefined, q: titulo.trim() || undefined, pagina }),
    [eventoId, escopo, status, tipo, titulo, pagina],
  )
  const filtrar = (definir: (valor: string) => void) => (valor: string) => {
    definir(valor)
    setPagina(0)
  }
  const itens = documentos.dado?.itens ?? []
  const processando = itens.filter((documento) => documento.status === 'PROCESSANDO').length

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <EnvioDeDocumento eventos={eventos} aoEnviar={documentos.recarregar} />
      <Cartao titulo="Documentos" className="min-w-0">
        <div className="mb-4 flex flex-col gap-4">
          <div className="grid grid-cols-[minmax(0,1fr)] gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Seletor rotulo="Edição" value={eventoId} onChange={(campo) => filtrar(setEventoId)(campo.target.value)}>
              <option value="">Todas</option>
              {(eventos.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome} {item.edicao}
                </option>
              ))}
            </Seletor>
            <Seletor rotulo="Quem enxerga" value={escopo} onChange={(campo) => filtrar(setEscopo)(campo.target.value)}>
              <option value="">Todos</option>
              {ESCOPOS.map((item) => (
                <option key={item.codigo} value={item.codigo}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
            <Seletor rotulo="Situação" value={status} onChange={(campo) => filtrar(setStatus)(campo.target.value)}>
              <option value="">Todas</option>
              {STATUS.map((codigo) => (
                <option key={codigo} value={codigo}>
                  {nomeStatus(codigo)}
                </option>
              ))}
            </Seletor>
            <Seletor rotulo="Tipo" value={tipo} onChange={(campo) => filtrar(setTipo)(campo.target.value)}>
              <option value="">Todos</option>
              {TIPOS.map((item) => (
                <option key={item.codigo} value={item.codigo}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="min-w-0 flex-1">
              <Busca valor={titulo} aoMudar={filtrar(setTitulo)} placeholder="Buscar pelo título" />
            </div>
            <Botao variante="secundario" className="min-w-0" icone="ri-refresh-line" carregando={documentos.carregando} onClick={documentos.recarregar}>
              Atualizar
            </Botao>
          </div>
          {processando > 0 && (
            <Aviso tipo="carregando" titulo={processando === 1 ? '1 documento em indexação' : `${numero(processando)} documentos em indexação`}>
              Leva de poucos segundos a um minuto. Use Atualizar, ou volte a esta aba do navegador depois.
            </Aviso>
          )}
          {itens.some((documento) => documento.modeloDiferente) && (
            <Aviso tipo="ausente" titulo="Há documentos indexados com outro modelo de embeddings">
              A busca do Atlas os ignora. Esta entrega não reindexa sozinha: envie esses documentos de novo ou recrie o banco.
            </Aviso>
          )}
        </div>
        <Carregando dados={documentos} vazio={(dado) => dado.itens.length === 0} textoVazio="Nenhum documento com esses filtros.">
          {(dado) => (
            <>
              <ul className="flex flex-col">
                {dado.itens.map((documento) => (
                  <LinhaDoDocumento key={documento.id} documento={documento} aoAgir={(acao) => setPedido({ documento, acao })} aoVerTrechos={() => setTrechosDe(documento)} />
                ))}
              </ul>
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      </Cartao>
      {pedido && (
        <ModalDaAcao
          documento={pedido.documento}
          acao={pedido.acao}
          aoFechar={() => {
            setPedido(null)
            documentos.recarregar()
          }}
          aoConcluir={() => {
            setPedido(null)
            documentos.recarregar()
          }}
        />
      )}
      {trechosDe && <ModalDosTrechos documento={trechosDe} aoFechar={() => setTrechosDe(null)} />}
    </div>
  )
}
