// Pesquisa da RBS (requisito R5.6.d): abrir e editar a pesquisa da edição pela tela, e decidir as perguntas que as marcas
// Master enviaram, sem abrir a aba Pesquisa de cada parceria. Os resultados continuam entrando pela carga, em Cargas.
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEventos } from '../../api/parcerias'
import {
  abrirPesquisa,
  decidirPergunta,
  editarPesquisa,
  listarPerguntasDaFila,
  listarPesquisasDaEdicao,
  type DadosDaPesquisa,
  type EscopoDaPesquisa,
  type Pergunta,
  type PerguntaDaFila,
  type PesquisaDaEdicao,
} from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto, Filtros, Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { Modal } from '../../componentes/Modal'
import { Tabela } from '../../componentes/Tabela'
import { dataHora, numero } from '../../util/formatar'

const NOME_DO_ESCOPO: Record<EscopoDaPesquisa, string> = { ATIVACAO: 'Ativação (por marca)', GERAL: 'Geral do festival' }

const NOME_DO_STATUS: Record<Pergunta['status'], string> = { ENVIADA: 'Enviadas', APROVADA: 'Aprovadas', RASCUNHO: 'Rascunhos' }

/** O formulário de abrir (sem pesquisa) ou de editar (com pesquisa). O escopo só se escolhe ao abrir. */
function ModalDaPesquisa({
  eventoId,
  edicao,
  pesquisa,
  ativacaoAberta,
  aoFechar,
  aoConcluir,
}: {
  eventoId: number
  edicao: number
  pesquisa: PesquisaDaEdicao | null
  ativacaoAberta: boolean
  aoFechar: () => void
  aoConcluir: (mensagem: string) => void
}) {
  const [nome, setNome] = useState(pesquisa?.nome ?? `Pesquisa nos espaços de ativação ${edicao}`)
  const [escopo, setEscopo] = useState<EscopoDaPesquisa>(pesquisa?.escopo ?? (ativacaoAberta ? 'GERAL' : 'ATIVACAO'))
  const [metodologia, setMetodologia] = useState(pesquisa?.metodologia ?? '')
  const [base, setBase] = useState(pesquisa?.baseRespondentes ? String(pesquisa.baseRespondentes) : '')
  const [confianca, setConfianca] = useState(pesquisa?.nivelConfianca ? String(pesquisa.nivelConfianca) : '')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setErros({})
    setFalha(null)
    const locais: Record<string, string> = {}
    if (base.trim() && !/^\d+$/.test(base.trim())) locais.baseRespondentes = 'Use um número inteiro, como 412.'
    if (confianca.trim() && !/^\d+([.,]\d{1,2})?$/.test(confianca.trim())) locais.nivelConfianca = 'Use um número, como 95.'
    if (Object.keys(locais).length) return setErros(locais)
    const dados: DadosDaPesquisa = {
      nome: nome.trim(),
      metodologia: metodologia.trim() || undefined,
      baseRespondentes: base.trim() ? Number(base.trim()) : undefined,
      nivelConfianca: confianca.trim() ? Number(confianca.trim().replace(',', '.')) : undefined,
    }
    setEnviando(true)
    try {
      if (pesquisa) {
        // Na edição vai só o que mudou; a metodologia apagada vai como espaço, que a API entende como limpar.
        const mudou: DadosDaPesquisa = {}
        if (dados.nome !== pesquisa.nome) mudou.nome = dados.nome
        if ((dados.metodologia ?? null) !== pesquisa.metodologia) mudou.metodologia = dados.metodologia ?? ' '
        if ((dados.baseRespondentes ?? null) !== pesquisa.baseRespondentes && dados.baseRespondentes !== undefined) mudou.baseRespondentes = dados.baseRespondentes
        if ((dados.nivelConfianca ?? null) !== pesquisa.nivelConfianca && dados.nivelConfianca !== undefined) mudou.nivelConfianca = dados.nivelConfianca
        if (Object.keys(mudou).length === 0) return aoConcluir('Nada mudou na pesquisa.')
        await editarPesquisa(pesquisa.id, mudou)
        aoConcluir('Pesquisa salva.')
      } else {
        const aberta = await abrirPesquisa(eventoId, { ...dados, escopo })
        const ligadas = aberta.perguntasLigadas ?? 0
        aoConcluir(
          ligadas > 0
            ? `Pesquisa aberta. ${ligadas === 1 ? '1 pergunta que uma marca já tinha enviado foi ligada' : `${ligadas} perguntas que as marcas já tinham enviado foram ligadas`} a ela.`
            : 'Pesquisa aberta.',
        )
      }
    } catch (erro) {
      if (erro instanceof ErroApi && Object.keys(erro.campos).length) setErros(erro.campos)
      else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível salvar a pesquisa.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo={pesquisa ? 'Editar pesquisa' : 'Abrir pesquisa'} legenda={`Edição ${edicao}`} aoFechar={aoFechar}>
      <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
        <Campo rotulo="Nome" value={nome} onChange={(campo) => setNome(campo.target.value)} erro={erros.nome} maxLength={120} />
        {pesquisa ? (
          <p className="text-sm">
            Escopo: <span className="font-semibold">{NOME_DO_ESCOPO[pesquisa.escopo]}</span>. O escopo não muda depois de aberta.
          </p>
        ) : (
          <Seletor rotulo="Escopo" value={escopo} erro={erros.escopo} onChange={(campo) => setEscopo(campo.target.value as EscopoDaPesquisa)}>
            <option value="ATIVACAO" disabled={ativacaoAberta}>
              {NOME_DO_ESCOPO.ATIVACAO}
              {ativacaoAberta ? ' · já aberta' : ''}
            </option>
            <option value="GERAL">{NOME_DO_ESCOPO.GERAL}</option>
          </Seletor>
        )}
        <Campo
          rotulo="Metodologia (opcional)"
          value={metodologia}
          onChange={(campo) => setMetodologia(campo.target.value)}
          erro={erros.metodologia}
          ajuda="Aparece no relatório, junto dos resultados."
          maxLength={160}
        />
        <div className="grid grid-cols-[minmax(0,1fr)] gap-4 sm:grid-cols-2">
          <Campo rotulo="Base de respondentes (opcional)" value={base} onChange={(campo) => setBase(campo.target.value)} erro={erros.baseRespondentes} inputMode="numeric" maxLength={9} />
          <Campo rotulo="Nível de confiança, em % (opcional)" value={confianca} onChange={(campo) => setConfianca(campo.target.value)} erro={erros.nivelConfianca} inputMode="decimal" maxLength={6} />
        </div>
        {erros.corpo && <Aviso tipo="falha" titulo={erros.corpo} />}
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0 max-sm:flex-1">
            {pesquisa ? 'Salvar' : 'Abrir pesquisa'}
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

function CartaoDaPesquisa({ pesquisa, aoEditar }: { pesquisa: PesquisaDaEdicao; aoEditar: () => void }) {
  return (
    <li className="flex flex-col gap-2 border-b border-borda py-4 first:pt-0 last:border-b-0 last:pb-0" aria-label={pesquisa.nome}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-semibold [overflow-wrap:anywhere]">{pesquisa.nome}</p>
          <p className="text-xs text-texto-2">
            {NOME_DO_ESCOPO[pesquisa.escopo]}
            {pesquisa.metodologia ? ` · ${pesquisa.metodologia}` : ''}
          </p>
        </div>
        <Botao variante="secundario" className="h-10 min-w-0 px-4" onClick={aoEditar} aria-label={`Editar ${pesquisa.nome}`}>
          Editar
        </Botao>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs sm:grid-cols-4">
        <div>
          <dt className="text-texto-2">Base de respondentes</dt>
          <dd className="font-semibold">{numero(pesquisa.baseRespondentes)}</dd>
        </div>
        <div>
          <dt className="text-texto-2">Nível de confiança</dt>
          <dd className="font-semibold">{pesquisa.nivelConfianca === null ? '—' : `${numero(pesquisa.nivelConfianca)}%`}</dd>
        </div>
        <div>
          <dt className="text-texto-2">Perguntas</dt>
          <dd className="font-semibold">
            {numero(pesquisa.perguntas.enviadas)} enviadas · {numero(pesquisa.perguntas.aprovadas)} aprovadas
          </dd>
        </div>
        <div>
          <dt className="text-texto-2">Resultados carregados</dt>
          <dd className="font-semibold">{numero(pesquisa.resultados)}</dd>
        </div>
      </dl>
    </li>
  )
}

function ModalDoAjuste({ pergunta, aoFechar, aoConcluir }: { pergunta: PerguntaDaFila; aoFechar: () => void; aoConcluir: () => void }) {
  const [observacao, setObservacao] = useState('')
  const [erro, setErro] = useState<string | undefined>()
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function pedir(evento: FormEvent) {
    evento.preventDefault()
    setErro(undefined)
    setFalha(null)
    setEnviando(true)
    try {
      await decidirPergunta(pergunta.id, 'PEDIR_AJUSTE', observacao.trim())
      aoConcluir()
    } catch (falhou) {
      if (falhou instanceof ErroApi && falhou.campos.observacao) setErro(falhou.campos.observacao)
      else setFalha(falhou instanceof ErroApi ? falhou.message : 'Não foi possível pedir o ajuste.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo="Pedir ajuste na pergunta" legenda={`${pergunta.patrocinador} · pergunta ${pergunta.ordem}`} aoFechar={aoFechar}>
      <form onSubmit={pedir} className="flex flex-col gap-4" noValidate>
        <p className="text-sm [overflow-wrap:anywhere]">“{pergunta.texto}”</p>
        <AreaTexto rotulo="O que a marca precisa ajustar" value={observacao} onChange={(campo) => setObservacao(campo.target.value)} erro={erro} ajuda="A marca vê este recado na aba Pesquisa." maxLength={500} />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0 max-sm:flex-1">
            Pedir ajuste
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

export function PesquisaRbs() {
  const eventos = useDados(listarEventos, [])
  const [eventoEscolhido, setEventoEscolhido] = useState('')
  const evento = (eventos.dado ?? []).find((item) => String(item.id) === eventoEscolhido) ?? eventos.dado?.[0]
  const pesquisas = useDados(() => (evento ? listarPesquisasDaEdicao(evento.id) : Promise.resolve([])), [evento?.id])
  const [status, setStatus] = useState<Pergunta['status']>('ENVIADA')
  const [pagina, setPagina] = useState(0)
  const perguntas = useDados(() => (evento ? listarPerguntasDaFila({ status, eventoId: evento.id, pagina }) : Promise.resolve(null)), [evento?.id, status, pagina])
  const [editando, setEditando] = useState<{ pesquisa: PesquisaDaEdicao | null } | null>(null)
  const [ajuste, setAjuste] = useState<PerguntaDaFila | null>(null)
  const [aprovando, setAprovando] = useState<number | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const ativacao = pesquisas.dado?.find((pesquisa) => pesquisa.escopo === 'ATIVACAO') ?? null

  async function aprovar(pergunta: PerguntaDaFila) {
    setAprovando(pergunta.id)
    setFalha(null)
    setFeito(null)
    try {
      await decidirPergunta(pergunta.id, 'APROVAR')
      setFeito(`Pergunta ${pergunta.ordem} de ${pergunta.patrocinador} aprovada.`)
      perguntas.recarregar()
      pesquisas.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível aprovar a pergunta.')
    } finally {
      setAprovando(null)
    }
  }

  return (
    <div data-secao="pesquisa" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Pesquisa"
        subtitulo="Abra a pesquisa de cada edição, decida as perguntas das marcas Master e acompanhe o que já foi carregado."
      />

      <Cartao titulo="Pesquisas da edição">
        <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
          <div className="w-full sm:w-64">
            <Seletor
              rotulo="Edição"
              value={evento ? String(evento.id) : ''}
              onChange={(campo) => {
                setEventoEscolhido(campo.target.value)
                setPagina(0)
                setFeito(null)
              }}
            >
              {(eventos.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome} {item.edicao}
                </option>
              ))}
            </Seletor>
          </div>
          <Botao icone="ri-add-line" disabled={!evento} onClick={() => setEditando({ pesquisa: null })}>
            Abrir pesquisa
          </Botao>
        </div>
        {feito && (
          <p className="mb-4 text-sm font-semibold text-secao-texto" role="status">
            {feito}
          </p>
        )}
        <Carregando dados={pesquisas}>
          {(lista) => (
            <div className="flex flex-col gap-4">
              {!ativacao && (
                <Aviso tipo="ausente" titulo={`A pesquisa da ativação de ${evento?.edicao ?? 'esta edição'} ainda não foi aberta`}>
                  As perguntas que as marcas Master enviarem esperam por ela. Ao abrir, as que já chegaram são ligadas na hora.
                </Aviso>
              )}
              {lista.length > 0 && (
                <ul className="flex flex-col">
                  {lista.map((pesquisa) => (
                    <CartaoDaPesquisa key={pesquisa.id} pesquisa={pesquisa} aoEditar={() => setEditando({ pesquisa })} />
                  ))}
                </ul>
              )}
              <p className="text-xs text-texto-2">
                Os resultados entram pela planilha de pesquisa, em <Link to="/rbs/cargas" className="font-semibold underline">Cargas</Link>.
              </p>
            </div>
          )}
        </Carregando>
      </Cartao>

      <Cartao titulo="Perguntas das marcas">
        <div className="mb-4">
          <Filtros<Pergunta['status']>
            valor={status}
            aoMudar={(valor) => {
              setStatus(valor)
              setPagina(0)
            }}
            opcoes={(['ENVIADA', 'APROVADA', 'RASCUNHO'] as const).map((valor) => ({ valor, nome: NOME_DO_STATUS[valor] }))}
          />
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
        <Carregando dados={perguntas} vazio={(dado) => !dado || dado.itens.length === 0} textoVazio={status === 'ENVIADA' ? 'Nenhuma pergunta esperando decisão.' : 'Nenhuma pergunta com esse filtro.'}>
          {(dado) =>
            dado && (
              <>
                <Tabela<PerguntaDaFila>
                  rotulo="Perguntas das marcas"
                  linhas={dado.itens}
                  chave={(pergunta) => pergunta.id}
                  colunas={[
                    { titulo: 'Marca', celula: (pergunta) => <span className="font-semibold">{pergunta.patrocinador}</span> },
                    { titulo: 'Pergunta', celula: (pergunta) => <span className="[overflow-wrap:anywhere]">{pergunta.ordem}. {pergunta.texto}</span> },
                    { titulo: 'Enviada por', celula: (pergunta) => pergunta.enviadaPor?.nome ?? '—' },
                    { titulo: 'Em', celula: (pergunta) => dataHora(pergunta.enviadaEm) },
                    {
                      titulo: '',
                      celula: (pergunta) =>
                        pergunta.status === 'ENVIADA' ? (
                          <span className="flex flex-wrap gap-2">
                            <Botao className="h-9 min-w-0 px-3" carregando={aprovando === pergunta.id} onClick={() => aprovar(pergunta)} aria-label={`Aprovar a pergunta ${pergunta.ordem} de ${pergunta.patrocinador}`}>
                              Aprovar
                            </Botao>
                            <Botao variante="secundario" className="h-9 min-w-0 px-3" onClick={() => setAjuste(pergunta)} aria-label={`Pedir ajuste na pergunta ${pergunta.ordem} de ${pergunta.patrocinador}`}>
                              Pedir ajuste
                            </Botao>
                          </span>
                        ) : null,
                    },
                  ]}
                />
                <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
              </>
            )
          }
        </Carregando>
      </Cartao>

      {editando && evento && (
        <ModalDaPesquisa
          eventoId={evento.id}
          edicao={evento.edicao}
          pesquisa={editando.pesquisa}
          ativacaoAberta={!!ativacao}
          aoFechar={() => setEditando(null)}
          aoConcluir={(mensagem) => {
            setEditando(null)
            setFeito(mensagem)
            pesquisas.recarregar()
            perguntas.recarregar()
          }}
        />
      )}
      {ajuste && (
        <ModalDoAjuste
          pergunta={ajuste}
          aoFechar={() => setAjuste(null)}
          aoConcluir={() => {
            setFeito(`Ajuste pedido na pergunta ${ajuste.ordem} de ${ajuste.patrocinador}.`)
            setAjuste(null)
            perguntas.recarregar()
          }}
        />
      )}
    </div>
  )
}
