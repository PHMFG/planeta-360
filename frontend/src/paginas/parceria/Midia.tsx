// Mídia e conteúdo (Figma P03, F01, F02): envio de material, arquivos da marca e da RBS e previsto x exibido. [ITEM-4] [ITEM-5]
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { ACEITA_ENVIO, LIMITE_BYTES, enviarArquivo, listarEntregas, type Arquivo, type Entrega } from '../../api/materiais'
import { obterConsolidacao, type LinhaMidia } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { CartaoArquivo } from '../../componentes/Arquivo'
import { AreaEnvio } from '../../componentes/AreaEnvio'
import { Aviso } from '../../componentes/Aviso'
import { Barra } from '../../componentes/Barra'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Grade3 } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto, Busca, Filtros, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { Status } from '../../componentes/Status'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora, numero, tamanho } from '../../util/formatar'
import { useParceria } from './ContextoParceria'
import { DetalheEntrega } from './DetalheEntrega'
import { ListaEntregas, NovaEntrega } from './EntregasDaAba'

type Origem = 'todos' | 'marca' | 'rbs'
type ArquivoDaEntrega = { arquivo: Arquivo; entrega: Entrega }

const UNIDADES: Record<string, string> = { INSERCAO: 'Inserções', CPM: 'CPM (mil impressões)', APLICACAO: 'Aplicações' }

function PreparoEnvio({
  arquivo,
  entregas,
  aoFechar,
  aoEnviar,
}: {
  arquivo: File | null
  entregas: Entrega[]
  aoFechar: () => void
  aoEnviar: (enviado: Arquivo, entrega: Entrega) => void
}) {
  const parceria = useParceria()
  const abertas = entregas.filter((entrega) => ACEITA_ENVIO.includes(entrega.status))
  const [entregaId, setEntregaId] = useState('')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const escolhida = abertas.find((entrega) => String(entrega.id) === entregaId) ?? abertas[0]

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!arquivo || !escolhida) return
    if (arquivo.size > LIMITE_BYTES) return setFalha('O arquivo passa de 20 MB.')
    setEnviando(true)
    setFalha(null)
    try {
      const enviado = await enviarArquivo(escolhida.id, arquivo, { categoria: 'MATERIAL_MIDIA', observacao: observacao.trim() || undefined })
      aoEnviar(enviado, escolhida)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível enviar o arquivo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal titulo="Preparar envio" legenda={`Mídia e conteúdo · ${parceria.patrocinador}`} aberto={arquivo !== null} aoFechar={aoFechar}>
      {arquivo && (
        <form onSubmit={enviar} className="flex flex-col gap-4" data-secao="midia">
          <div className="flex items-center gap-4 rounded-[14px] border border-borda p-4">
            <span className="grid size-10 place-items-center rounded-[10px] bg-secao-tinta text-secao-texto">
              <i className="ri-file-text-line text-xl" aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">{arquivo.name}</p>
              <p className="text-xs font-medium text-texto-2">
                Arquivo selecionado · {tamanho(arquivo.size)}
                {escolhida && ` · nova versão v${escolhida.arquivos.length + 1}`}
              </p>
            </div>
          </div>
          {abertas.length === 0 ? (
            <Aviso tipo="restrito" titulo="Nenhuma entrega de mídia aceita envio agora">
              As entregas desta aba estão em análise, aprovadas ou ainda não foram abertas pela RBS.
            </Aviso>
          ) : (
            <>
              <Seletor rotulo="Entrega vinculada" value={escolhida ? String(escolhida.id) : ''} onChange={(evento) => setEntregaId(evento.target.value)}>
                {abertas.map((entrega) => (
                  <option key={entrega.id} value={entrega.id}>
                    {entrega.titulo} · prazo {data(entrega.prazo)}
                  </option>
                ))}
              </Seletor>
              <AreaTexto rotulo="Observações (opcional)" maxLength={500} value={observacao} onChange={(evento) => setObservacao(evento.target.value)} />
              <Aviso titulo="Antes de enviar">
                Cada envio vira uma nova versão da entrega e as anteriores continuam disponíveis. O status passa a “Enviado”.
              </Aviso>
            </>
          )}
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div className="grid grid-cols-2 gap-4">
            <Botao variante="secundario" onClick={aoFechar} className="min-w-0">
              Cancelar
            </Botao>
            <Botao type="submit" carregando={enviando} disabled={!escolhida} className="min-w-0">
              Enviar material
            </Botao>
          </div>
        </form>
      )}
    </Modal>
  )
}

function PrevistoExibido() {
  const parceria = useParceria()
  const consolidacao = useDados(() => obterConsolidacao(parceria.id), [parceria.id])
  const colunas = (comVeiculo: boolean) => [
    ...(comVeiculo ? [{ titulo: 'Veículo', celula: (linha: LinhaMidia) => <span className="font-semibold">{linha.veiculo}</span> }] : []),
    { titulo: 'Unidade', celula: (linha: LinhaMidia) => UNIDADES[linha.unidade] ?? linha.unidade },
    { titulo: 'Previsto', celula: (linha: LinhaMidia) => numero(linha.previsto), classe: 'text-right' },
    { titulo: 'Exibido', celula: (linha: LinhaMidia) => numero(linha.exibido), classe: 'text-right' },
    { titulo: 'Entrega', celula: (linha: LinhaMidia) => <Barra valor={linha.percentual} rotulo="Exibido sobre o previsto" /> },
  ]

  return (
    <Cartao titulo="Previsto x exibido">
      <Carregando dados={consolidacao}>
        {(dado) =>
          dado.midia.porUnidade.length === 0 ? (
            <Aviso tipo="ausente" titulo="Ainda sem números de veiculação">
              Os números aparecem depois que a RBS carrega a planilha de veiculação dos veículos.
            </Aviso>
          ) : (
            <div className="flex flex-col gap-6">
              {/* [DUPLICIDADE] Unidades diferentes (inserções e CPM) nunca são somadas entre si. */}
              <Tabela linhas={dado.midia.porUnidade} chave={(linha) => linha.unidade} colunas={colunas(false)} />
              <Tabela linhas={dado.midia.porVeiculo} chave={(linha) => `${linha.veiculo}-${linha.unidade}`} colunas={colunas(true)} />
            </div>
          )
        }
      </Carregando>
    </Cartao>
  )
}

export function Midia() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const entregas = useDados(() => listarEntregas(parceria.id, 'MIDIA'), [parceria.id])
  const [selecionado, setSelecionado] = useState<File | null>(null)
  const [aberta, setAberta] = useState<Entrega | null>(null)
  const [criando, setCriando] = useState(false)
  const [recebido, setRecebido] = useState<{ arquivo: Arquivo; entrega: Entrega } | null>(null)
  const [busca, setBusca] = useState('')
  const [origem, setOrigem] = useState<Origem>('todos')

  const lista = entregas.dado ?? []
  const arquivos: ArquivoDaEntrega[] = lista
    .flatMap((entrega) => entrega.arquivos.map((arquivo) => ({ arquivo, entrega })))
    .sort((a, b) => b.arquivo.enviadoEm.localeCompare(a.arquivo.enviadoEm))
  const daRbs = (item: ArquivoDaEntrega) => item.arquivo.enviadoPor?.perfil === 'ADMIN_RBS'
  const termo = busca.trim().toLowerCase()
  const visiveis = arquivos.filter(
    (item) =>
      (origem === 'todos' || (origem === 'rbs') === daRbs(item)) &&
      (!termo || item.arquivo.nomeOriginal.toLowerCase().includes(termo) || item.entrega.titulo.toLowerCase().includes(termo)),
  )

  return (
    <div data-secao="midia" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Mídia e conteúdo"
        subtitulo="Envie os materiais da campanha e consulte as publicações disponibilizadas pela RBS."
      />

      {recebido && (
        <Aviso titulo="Envio recebido">
          {recebido.arquivo.nomeOriginal} foi registrado como versão v{recebido.arquivo.versao} de “{recebido.entrega.titulo}”. O status indica recebimento,
          não aprovação de conteúdo.
        </Aviso>
      )}

      {!rbs && (
        <AreaEnvio
          texto="ou selecione no computador · a entrega vinculada é escolhida antes do envio"
          aoEscolher={(arquivo) => {
            setRecebido(null)
            setSelecionado(arquivo)
          }}
          desabilitado={!entregas.dado}
        />
      )}

      <Busca valor={busca} aoMudar={setBusca} placeholder="Buscar por nome do arquivo ou entrega…" />
      <Filtros<Origem>
        valor={origem}
        aoMudar={setOrigem}
        opcoes={[
          { valor: 'todos', nome: 'Todos', total: arquivos.length },
          { valor: 'marca', nome: 'Enviados pela marca', total: arquivos.filter((item) => !daRbs(item)).length },
          { valor: 'rbs', nome: 'Publicados pela RBS', total: arquivos.filter(daRbs).length },
        ]}
      />

      <Carregando dados={entregas}>
        {() =>
          visiveis.length === 0 ? (
            <Aviso tipo="vazio" titulo={arquivos.length ? 'Nenhum arquivo com esse filtro' : 'Nenhum arquivo enviado ainda'} />
          ) : (
            <Grade3>
              {visiveis.map(({ arquivo, entrega }) => (
                <CartaoArquivo
                  key={arquivo.id}
                  nome={arquivo.nomeOriginal}
                  meta={`v${arquivo.versao} · ${arquivo.enviadoPor?.nome ?? '—'} · ${dataHora(arquivo.enviadoEm)}`}
                  rodape={
                    arquivo.enviadoPor?.perfil === 'ADMIN_RBS' ? (
                      <span className="font-bold text-secao-texto">Publicado pela RBS · abrir / baixar</span>
                    ) : (
                      <Status codigo={entrega.status} complemento="ver detalhe / histórico" />
                    )
                  }
                  onClick={() => setAberta(entrega)}
                />
              ))}
            </Grade3>
          )
        }
      </Carregando>

      <Cartao
        titulo="Entregas de mídia"
        extra={
          rbs && (
            <button type="button" className="flex items-center gap-1 text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => setCriando(true)}>
              <i className="ri-add-line" aria-hidden="true" /> Nova entrega
            </button>
          )
        }
      >
        <Carregando dados={entregas} vazio={(itens) => itens.length === 0} textoVazio="A RBS ainda não abriu entregas de mídia.">
          {(itens) => <ListaEntregas lista={itens} aoAbrir={setAberta} />}
        </Carregando>
      </Cartao>

      <PrevistoExibido />

      <Aviso titulo="A regra muda por tipo de conteúdo">
        Comercial de TV e conteúdo assinado: sem aprovação prévia. Branded content: consulte as regras específicas antes do envio.
      </Aviso>

      <PreparoEnvio
        arquivo={selecionado}
        entregas={lista}
        aoFechar={() => setSelecionado(null)}
        aoEnviar={(arquivo, entrega) => {
          setSelecionado(null)
          setRecebido({ arquivo, entrega })
          entregas.recarregar()
        }}
      />
      <DetalheEntrega entrega={aberta} aoFechar={() => setAberta(null)} aoMudar={entregas.recarregar} />
      {rbs && <NovaEntrega aba="MIDIA" aberto={criando} aoFechar={() => setCriando(false)} aoCriar={entregas.recarregar} />}
    </div>
  )
}
