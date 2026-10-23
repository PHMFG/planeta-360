// Pesquisa da ativação (Figma P06): as até três perguntas da marca, a aprovação da RBS e os resultados. [ITEM-5] [ITEM-7]
import { useState } from 'react'
import { ErroApi } from '../../api/cliente'
import { decidirPergunta, enviarPerguntas, obterPesquisa, type PesquisaDaParceria, type Pergunta, type ResultadoPesquisa } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto } from '../../componentes/Formulario'
import { nomeCota } from '../../componentes/Menu'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, numero } from '../../util/formatar'
import { useParceria } from './ContextoParceria'

const STATUS_PERGUNTA = {
  RASCUNHO: { nome: 'Rascunho', cor: '#616161' },
  ENVIADA: { nome: 'Enviada · aguardando a RBS', cor: '#073c89' },
  APROVADA: { nome: 'Aprovada pela RBS', cor: '#007a2a' },
} as const

function valor(resultado: ResultadoPesquisa) {
  if (resultado.unidade === 'PERCENTUAL') return `${numero(resultado.valor)}%`
  if (resultado.unidade === 'NOTA') return `nota ${numero(resultado.valor)}`
  return numero(resultado.valor)
}

function DecisaoRbs({ pergunta, aoDecidir }: { pergunta: Pergunta; aoDecidir: () => void }) {
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [decidindo, setDecidindo] = useState(false)

  async function decidir(decisao: 'APROVAR' | 'PEDIR_AJUSTE') {
    if (decisao === 'PEDIR_AJUSTE' && !observacao.trim()) return setFalha('Escreva o que a marca precisa ajustar.')
    setFalha(null)
    setDecidindo(true)
    try {
      await decidirPergunta(pergunta.id, decisao, observacao.trim() || undefined)
      aoDecidir()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível registrar a decisão.')
    } finally {
      setDecidindo(false)
    }
  }

  return (
    <div className="mt-3 flex flex-col gap-3">
      <AreaTexto rotulo="Observação para a marca (obrigatória ao pedir ajuste)" rows={1} value={observacao} onChange={(evento) => setObservacao(evento.target.value)} />
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div className="flex flex-wrap gap-3">
        <Botao carregando={decidindo} onClick={() => decidir('APROVAR')}>
          Aprovar pergunta
        </Botao>
        <Botao variante="secundario" disabled={decidindo} onClick={() => decidir('PEDIR_AJUSTE')}>
          Pedir ajuste
        </Botao>
      </div>
    </div>
  )
}

type PropsPerguntas = {
  dados: PesquisaDaParceria
  rbs: boolean
  /** A marca enviou: a API devolve a pesquisa já atualizada. */
  aoEnviar: (novo: PesquisaDaParceria) => void
  /** A RBS decidiu sobre uma pergunta: a página busca de novo. */
  aoDecidir: () => void
}

function Perguntas({ dados, rbs, aoEnviar, aoDecidir }: PropsPerguntas) {
  const ordens = Array.from({ length: dados.limitePerguntas }, (_, indice) => indice + 1)
  const porOrdem = (ordem: number) => dados.perguntas.find((pergunta) => pergunta.ordem === ordem)
  const [textos, setTextos] = useState<Record<number, string>>(() => Object.fromEntries(ordens.map((ordem) => [ordem, porOrdem(ordem)?.texto ?? ''])))
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar() {
    const lista = ordens.map((ordem) => ({ ordem, texto: (textos[ordem] ?? '').trim() })).filter((item) => item.texto)
    // [VALIDACAO] Mesmas regras da API: de 1 a 3 perguntas, de 10 a 300 caracteres.
    const novos: Record<string, string> = {}
    if (!lista.length) novos.geral = 'Escreva pelo menos uma pergunta.'
    lista.forEach((item) => {
      if (item.texto.length < 10) novos[item.ordem] = 'A pergunta precisa de pelo menos 10 caracteres.'
    })
    setErros(novos)
    setFalha(novos.geral ?? null)
    if (Object.keys(novos).length) return
    setEnviando(true)
    try {
      aoEnviar(await enviarPerguntas(dados.parceriaId, lista))
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível enviar as perguntas.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Perguntas da marca">
      <p className="mb-4 text-sm">
        A marca pode incluir até {dados.limitePerguntas} perguntas na pesquisa feita nos espaços de ativação. A RBS aprova cada uma antes de ir a campo.
      </p>
      <div className="flex flex-col gap-4">
        {ordens.map((ordem) => {
          const pergunta = porOrdem(ordem)
          const status = pergunta ? STATUS_PERGUNTA[pergunta.status] : null
          const travada = pergunta?.status === 'APROVADA'
          return (
            <div key={ordem} className="rounded-2xl border border-borda p-4">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <p className="texto-sobretitulo text-texto-2">Pergunta {ordem}</p>
                {status && (
                  <span className="text-xs font-bold" style={{ color: status.cor }}>
                    {status.nome}
                    {pergunta?.enviadaEm && ` · ${dataHora(pergunta.enviadaEm)}`}
                  </span>
                )}
              </div>
              {pergunta?.observacaoRbs && pergunta.status === 'RASCUNHO' && (
                <Aviso tipo="falha" titulo="A RBS pediu ajuste" className="mb-3">
                  {pergunta.observacaoRbs}
                </Aviso>
              )}
              {rbs || travada ? (
                <p className="text-sm font-semibold">{pergunta?.texto ?? <span className="font-medium text-texto-2">Sem pergunta.</span>}</p>
              ) : (
                <AreaTexto
                  rotulo="Texto da pergunta"
                  maxLength={300}
                  value={textos[ordem] ?? ''}
                  onChange={(evento) => setTextos({ ...textos, [ordem]: evento.target.value })}
                  erro={erros[ordem]}
                  ajuda="Entre 10 e 300 caracteres."
                />
              )}
              {rbs && pergunta?.status === 'ENVIADA' && <DecisaoRbs pergunta={pergunta} aoDecidir={aoDecidir} />}
            </div>
          )
        })}
      </div>
      {!rbs && (
        <div className="mt-4 flex flex-col gap-3">
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div>
            <Botao carregando={enviando} onClick={enviar} icone="ri-send-plane-line">
              Enviar perguntas
            </Botao>
          </div>
        </div>
      )}
    </Cartao>
  )
}

export function Pesquisa() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const aba = parceria.abas.find((item) => item.codigo === 'PESQUISA')
  const bloqueada = !aba?.liberada
  const dados = useDados(() => (bloqueada && !rbs ? Promise.resolve(null) : obterPesquisa(parceria.id)), [parceria.id, bloqueada, rbs])
  // Depois do envio vale a resposta da API; depois de uma decisão da RBS, a página busca de novo.
  const [atual, setAtual] = useState<PesquisaDaParceria | null>(null)
  const [enviadas, setEnviadas] = useState(false)

  return (
    <div data-secao="pesquisa" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Pesquisa"
        subtitulo={
          rbs
            ? `As perguntas de ${parceria.patrocinador} na pesquisa do festival, para aprovar, e os resultados.`
            : 'As três perguntas da sua marca na pesquisa do festival e os resultados.'
        }
      />
      {bloqueada && !rbs ? (
        <Aviso tipo="restrito" titulo="Esta aba não faz parte da sua cota">
          A pesquisa da ativação é um benefício da cota Master; a cota {nomeCota(parceria)} não inclui esta aba. Para contratar, fale com o time B2B da RBS.
        </Aviso>
      ) : (
        <Carregando dados={dados}>
          {(buscado) => {
            const dado = atual ?? buscado
            if (!dado) return null
            // O formulário recomeça só quando as perguntas mudam de fato (envio ou decisão da RBS).
            const assinatura = dado.perguntas.map((pergunta) => `${pergunta.id}:${pergunta.status}:${pergunta.texto}`).join('|')
            return (
              <>
                {enviadas && <Aviso titulo="Perguntas enviadas para a RBS">Cada pergunta fica “Enviada” até a RBS aprovar ou pedir ajuste.</Aviso>}
                {dado.pesquisa ? (
                  <Aviso titulo={dado.pesquisa.nome}>
                    {dado.pesquisa.metodologia ?? 'Metodologia a informar pela RBS.'}
                    {dado.pesquisa.baseRespondentes && ` Base: ${numero(dado.pesquisa.baseRespondentes)} respondentes.`}
                  </Aviso>
                ) : (
                  <Aviso tipo="ausente" titulo="A RBS ainda não abriu a pesquisa desta edição">
                    As perguntas já podem ser enviadas; elas entram na pesquisa quando a RBS a abrir.
                  </Aviso>
                )}
                <Perguntas
                  key={assinatura}
                  dados={dado}
                  rbs={rbs}
                  aoEnviar={(novo) => {
                    setAtual(novo)
                    setEnviadas(true)
                  }}
                  aoDecidir={() => {
                    setAtual(null)
                    dados.recarregar()
                  }}
                />
                <Cartao titulo="Resultados">
                  {dado.resultados.length === 0 ? (
                    <Aviso tipo="ausente" titulo="Resultados ainda não carregados">
                      A RBS carrega os resultados da empresa de pesquisa depois do festival. Toda resposta vem com a base de respondentes.
                    </Aviso>
                  ) : (
                    <Tabela
                      linhas={dado.resultados}
                      chave={(item) => `${item.perguntaId}-${item.alternativa}`}
                      colunas={[
                        { titulo: 'Pergunta', celula: (item) => `Pergunta ${item.ordem}` },
                        { titulo: 'Resposta', celula: (item) => <span className="font-semibold">{item.alternativa}</span> },
                        { titulo: 'Resultado', celula: (item) => valor(item), classe: 'text-right' },
                        { titulo: 'Base', celula: (item) => `${numero(item.baseRespondentes)} respondentes`, classe: 'text-right' },
                      ]}
                    />
                  )}
                </Cartao>
              </>
            )
          }}
        </Carregando>
      )}
    </div>
  )
}
