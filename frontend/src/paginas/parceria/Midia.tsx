// Mídia e conteúdo (Figma P03, F01, F02): envio de material, arquivos da marca e da RBS e previsto x exibido. [ITEM-4] [ITEM-5]
import { useMemo, useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { ACEITA_ENVIO, EXTENSOES_ACEITAS, REGRA_ENVIO, enviarArquivo, listarEntregas, problemaNoArquivo, type Arquivo, type Entrega } from '../../api/materiais'
import { useDados } from '../../api/useDados'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { AreaEnvio } from '../../componentes/AreaEnvio'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { useSessao } from '../../sessao/Sessao'
import { data, tamanho } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { DetalheEntrega } from './DetalheEntrega'
import { PostsELinks } from '../../componentes/PostsELinks'
import { ComprovacaoMensal } from '../../componentes/ComprovacaoMensal'
import { apelido } from '../../api/comprovacao'
import { BaseDeMidia } from '../../componentes/BaseDeMidia'
import { NavegadorDeArquivos, type Comprovante } from '../../componentes/navegador/NavegadorDeArquivos'
import { usarToast } from '../../componentes/Toast'
import { obterMidiaDaParceria } from '../../api/midia'
import { ListaEntregas, NovaEntrega } from './EntregasDaAba'

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
  // Tipo ou tamanho que a API vai recusar: avisa já, antes de a pessoa preencher o resto.
  const problema = arquivo ? problemaNoArquivo(arquivo) : null

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!arquivo || !escolhida || problema) return
    setEnviando(true)
    setFalha(null)
    try {
      const enviado = await enviarArquivo(escolhida.id, arquivo, { categoria: 'MATERIAL_MIDIA', observacao: observacao.trim() || undefined })
      aoEnviar(enviado, escolhida)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível enviar o arquivo.')
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
              As entregas desta aba estão em análise, aprovadas ou ainda não foram abertas pela RBS. Quando a RBS abrir uma entrega ou pedir um
              ajuste, ela aparece aqui. Dúvidas sobre prazos: fale com o time B2B da RBS.
            </Aviso>
          ) : (
            <>
              <Seletor rotulo="Entrega vinculada" value={escolhida ? String(escolhida.id) : ''} onChange={(evento) => setEntregaId(evento.target.value)}>
                {abertas.map((entrega) => (
                  <option key={entrega.id} value={entrega.id}>
                    {entrega.titulo} · {entrega.prazo ? `prazo ${data(entrega.prazo)}` : 'sem prazo definido'}
                  </option>
                ))}
              </Seletor>
              <AreaTexto rotulo="Observações (opcional)" maxLength={500} value={observacao} onChange={(evento) => setObservacao(evento.target.value)} />
              <Aviso titulo="Antes de enviar">
                Cada envio vira uma nova versão da entrega e as anteriores continuam disponíveis. O status passa a “Enviado”.
              </Aviso>
            </>
          )}
          {(problema ?? falha) && <Aviso tipo="falha" titulo={problema ?? falha ?? ''} />}
          {/* No celular os botões empilham (Enviar em cima): lado a lado, "Enviar" cobria parte de "Cancelar". */}
          <div className="flex flex-col-reverse gap-3 sm:grid sm:grid-cols-2 sm:gap-4">
            <Botao variante="secundario" onClick={aoFechar} className="min-w-0">
              Cancelar
            </Botao>
            <Botao type="submit" carregando={enviando} disabled={!escolhida || problema !== null} className="min-w-0">
              Enviar material
            </Botao>
          </div>
          {/* O botão desabilitado diz por quê (U20). */}
          {!escolhida && <p className="text-xs font-medium text-texto-2">O envio abre quando houver uma entrega aceitando material.</p>}
        </form>
      )}
    </Modal>
  )
}

export function Midia() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'MIDIA')
  // [PERMISSAO] Com a aba fechada, a marca não busca nada daqui (a API recusaria) e lê só o aviso.
  if (!rbs && estado !== 'LIBERADA') {
    return (
      <div data-secao="midia" className="flex flex-col gap-4">
        <CabecalhoSecao trilha="Planeta Atlântida / Patrocinador" titulo="Mídia e conteúdo" />
        <AvisoAbaFechada parceria={parceria} estado={estado} nome="Mídia e conteúdo" rbs={false} />
      </div>
    )
  }
  return <ConteudoMidia rbs={rbs} />
}

function ConteudoMidia({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const estado = estadoAbaNaParceria(parceria, 'MIDIA')
  const entregas = useDados(() => listarEntregas(parceria.id, 'MIDIA'), [parceria.id])
  const semEntregaAberta = Boolean(entregas.dado) && !entregas.dado?.some((entrega) => ACEITA_ENVIO.includes(entrega.status))
  useRecarregarSeAbaFechou(entregas)
  const [selecionado, setSelecionado] = useState<File | null>(null)
  // Cada arquivo escolhido abre um "Preparar envio" novo: a observação e o erro do envio anterior não passam para o próximo.
  const [envios, setEnvios] = useState(0)
  const [aberta, setAberta] = useState<Entrega | null>(null)
  const [criando, setCriando] = useState(false)
  // Cada envio pelo detalhe (arquivo, texto ou link) relê Posts e links: o link novo aparece na hora.
  const [versaoDosLinks, setVersaoDosLinks] = useState(0)
  const [recebido, setRecebido] = useState<{ arquivo: Arquivo; entrega: Entrega } | null>(null)
  const avisar = usarToast()
  // Os comprovantes da base (prints e PDFs das veiculações) viram a pasta "Comprovantes de veiculação" do navegador.
  const base = useDados(() => obterMidiaDaParceria(parceria.id), [parceria.id])
  const comprovantes = useMemo<Comprovante[]>(() => {
    const vistos = new Set<number>()
    return (base.dado?.linhas ?? []).flatMap((linha) =>
      linha.comprovantes
        .filter((item) => !vistos.has(item.arquivoId) && vistos.add(item.arquivoId))
        .map((item) => ({ ...item, legenda: [linha.veiculo, linha.formato, linha.mes].filter(Boolean).join(' · ') })),
    )
  }, [base.dado])

  const lista = entregas.dado ?? []

  async function enviarParaPasta(arquivo: File, entrega: Entrega) {
    const problema = problemaNoArquivo(arquivo)
    if (problema) {
      avisar(problema, 'falha')
      return
    }
    try {
      const enviado = await enviarArquivo(entrega.id, arquivo, { categoria: 'MATERIAL_MIDIA' })
      setRecebido({ arquivo: enviado, entrega })
      avisar(`${arquivo.name} enviado para “${entrega.titulo}”`)
      entregas.recarregar()
    } catch (erro) {
      avisar(erro instanceof ErroApi ? erro.message : 'Não foi possível enviar o arquivo.', 'falha')
    }
  }

  return (
    <div data-secao="midia" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Mídia e conteúdo"
        subtitulo={
          rbs
            ? `Os materiais que ${parceria.patrocinador} enviou, os retornos publicados pela RBS e o previsto x exibido da mídia.`
            : 'O que a RBS já veiculou da sua campanha, e o envio dos seus materiais.'
        }
      />

      {rbs && <AvisoAbaFechada parceria={parceria} estado={estado} nome="Mídia e conteúdo" rbs />}

      {recebido && (
        <Aviso titulo="Envio recebido">
          {recebido.arquivo.nomeOriginal} foi registrado como versão v{recebido.arquivo.versao} de “{recebido.entrega.titulo}”. O status indica recebimento,
          não aprovação de conteúdo.
        </Aviso>
      )}

      {/* Os números primeiro (U5 e M6, plano da base da RBS): é o que a marca vem acompanhar; o envio de material vem depois.
          O mês é só das Master (M7); a API recusa o filtro para as outras cotas. */}
      <BaseDeMidia modo="parceria" parceriaId={parceria.id} apelido={apelido(parceria.patrocinador)} comMes={parceria.cota.codigo === 'MASTER'} />

      {/* R3.2 e R3.3: só a cota Master (e a RBS, na parceria Master); as outras cotas não veem o cartão. */}
      {parceria.cota.codigo === 'MASTER' && <ComprovacaoMensal parceriaId={parceria.id} patrocinador={parceria.patrocinador} />}

      {/* R4.3 e R5.3.b: os links dos posts, clicáveis, da carga da RBS e dos materiais enviados. */}
      <PostsELinks key={`links-${versaoDosLinks}`} parceriaId={parceria.id} />
      {!rbs && (
        <section className="flex flex-col gap-3" aria-label="Enviar material">
          <h2 className="texto-titulo">Enviar material</h2>
          {/* Sem nenhuma entrega aberta, a marca sabe antes de escolher o arquivo, e não depois (U04). */}
          {semEntregaAberta && (
            <Aviso tipo="restrito" titulo="Nenhuma entrega de mídia aceita envio agora">
              A RBS abre cada entrega (ou pede um ajuste) quando é a hora de enviar.
            </Aviso>
          )}
          <AreaEnvio
            texto={`ou selecione no computador · ${REGRA_ENVIO} · a entrega é escolhida na hora do envio`}
            aceitar={EXTENSOES_ACEITAS}
            aoEscolher={(arquivo) => {
              setRecebido(null)
              setEnvios((total) => total + 1)
              setSelecionado(arquivo)
            }}
            desabilitado={!entregas.dado || semEntregaAberta}
            compacta={semEntregaAberta}
          />
          <p className="text-xs font-medium text-texto-2">
            <span className="font-semibold text-tinta">A regra muda por tipo de conteúdo:</span> comercial de TV e conteúdo assinado não passam por
            aprovação prévia; branded content segue regras específicas.
          </p>
        </section>
      )}
      <Carregando dados={entregas}>
        {(itens) => (
          <NavegadorDeArquivos entregas={itens} comprovantes={comprovantes} podeEnviar={!rbs} aoEnviar={enviarParaPasta} aoAbrirEntrega={setAberta} />
        )}
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

      <PreparoEnvio
        key={envios}
        arquivo={selecionado}
        entregas={lista}
        aoFechar={() => setSelecionado(null)}
        aoEnviar={(arquivo, entrega) => {
          setSelecionado(null)
          setRecebido({ arquivo, entrega })
          entregas.recarregar()
        }}
      />
      <DetalheEntrega
        entrega={aberta}
        aoFechar={() => setAberta(null)}
        aoMudar={() => {
          // O aviso "Envio recebido" do topo é do envio anterior; o novo aparece dentro do detalhe.
          setRecebido(null)
          entregas.recarregar()
          setVersaoDosLinks((versao) => versao + 1)
        }}
      />
      {rbs && <NovaEntrega aba="MIDIA" aberto={criando} aoFechar={() => setCriando(false)} aoCriar={entregas.recarregar} />}
    </div>
  )
}
