// Pesquisa da ativação (Figma P06): as perguntas da marca (até o limite da pesquisa), a aprovação da RBS, o perfil do público e os resultados. [ITEM-5] [ITEM-7]
import { useState } from 'react'
import { ErroApi } from '../../api/cliente'
import {
  NOME_DA_DIMENSAO,
  NOME_DO_TIPO_DE_RESPOSTA,
  decidirPergunta,
  enviarPerguntas,
  obterPerfilDaPesquisa,
  obterPesquisa,
  type PesquisaDaParceria,
  type Pergunta,
  type ResultadoPesquisa,
  type TipoResposta,
} from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, numero } from '../../util/formatar'
import { useParceria } from './ContextoParceria'

const STATUS_PERGUNTA = {
  RASCUNHO: { nome: 'Ainda não enviada', cor: '#616161' },
  ENVIADA: { nome: 'Enviada · aguardando a RBS', cor: '#073c89' },
  APROVADA: { nome: 'Aprovada pela RBS', cor: '#007a2a' },
} as const

/** Pergunta que a RBS devolveu volta a RASCUNHO com a observação: para a marca, é "ajuste pedido", não um rascunho. */
function statusDa(pergunta: Pergunta) {
  if (pergunta.status === 'RASCUNHO' && pergunta.observacaoRbs) return { nome: 'Ajuste pedido pela RBS', cor: '#c8203f' }
  return STATUS_PERGUNTA[pergunta.status]
}

function valor(resultado: ResultadoPesquisa) {
  if (resultado.unidade === 'PERCENTUAL') return `${numero(resultado.valor)}%`
  if (resultado.unidade === 'NOTA') return `nota ${numero(resultado.valor)}`
  return numero(resultado.valor)
}

/**
 * Os resultados por pergunta, com o texto dela (U6, plano da base da RBS): cada alternativa numa barra (percentual) ou o número
 * (nota, contagem), e a base de respondentes uma vez só, no fim da pergunta. Nenhum texto interpretativo: só o dado.
 */
function ResultadosPorPergunta({ resultados, perguntas }: { resultados: ResultadoPesquisa[]; perguntas: Pergunta[] }) {
  const ordens = [...new Set(resultados.map((item) => item.ordem))].sort((a, b) => a - b)
  return (
    <div className="flex flex-col gap-6">
      {ordens.map((ordem) => {
        const itens = resultados.filter((item) => item.ordem === ordem)
        const texto = perguntas.find((pergunta) => pergunta.ordem === ordem)?.texto
        const bases = [...new Set(itens.map((item) => item.baseRespondentes))]
        return (
          <section key={ordem} aria-label={`Pergunta ${ordem}`}>
            <p className="texto-sobretitulo text-texto-2">Pergunta {ordem}</p>
            <h3 className="mt-1 text-sm font-semibold">{texto ?? `Pergunta ${ordem}`}</h3>
            <ul className="mt-3 flex flex-col gap-2">
              {itens.map((item, indice) => (
                <li
                  key={`${item.perguntaId}-${item.alternativa}`}
                  className="entrar-em-cascata grid grid-cols-[minmax(110px,180px)_1fr_auto] items-center gap-3 text-sm"
                  style={{ '--ordem': indice } as React.CSSProperties}
                >
                  <span className="font-medium">{item.alternativa}</span>
                  {item.unidade === 'PERCENTUAL' ? (
                    <span className="h-3 overflow-hidden rounded-full bg-cinza-100" aria-hidden="true">
                      <span className="block h-full rounded-full bg-secao" style={{ width: `${Math.max(0, Math.min(100, item.valor))}%` }} />
                    </span>
                  ) : (
                    <span aria-hidden="true" />
                  )}
                  <span className="w-20 text-right font-semibold">{valor(item)}</span>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs font-medium text-texto-2">
              Base: {bases.map((base) => numero(base)).join(' / ')} respondentes
            </p>
          </section>
        )
      })}
    </div>
  )
}

function DecisaoRbs({ pergunta, aoDecidir }: { pergunta: Pergunta; aoDecidir: () => void }) {
  // Pergunta aprovada não muda pela marca; a RBS pode reabri-la pedindo ajuste (a observação diz o quê).
  const reabrir = pergunta.status === 'APROVADA'
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
      <AreaTexto
        rotulo={reabrir ? 'O que a marca precisa mudar' : 'Observação para a marca (obrigatória ao pedir ajuste)'}
        rows={1}
        value={observacao}
        onChange={(evento) => setObservacao(evento.target.value)}
      />
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div className="flex flex-wrap gap-3">
        {!reabrir && (
          <Botao carregando={decidindo} onClick={() => decidir('APROVAR')}>
            Aprovar pergunta
          </Botao>
        )}
        <Botao variante="secundario" disabled={decidindo} onClick={() => decidir('PEDIR_AJUSTE')}>
          {reabrir ? 'Reabrir para ajuste' : 'Pedir ajuste'}
        </Botao>
      </div>
    </div>
  )
}

type PropsPerguntas = {
  dados: PesquisaDaParceria
  rbs: boolean
  /** Quem enviou (a marca ou a RBS): a API devolve a pesquisa já atualizada. */
  aoEnviar: (novo: PesquisaDaParceria) => void
  /** A RBS decidiu sobre uma pergunta: a página busca de novo. */
  aoDecidir: () => void
}

type Rascunho = { chave: string; ordem: number; texto: string; tipoResposta: TipoResposta; alternativas: string[] }

const TIPOS = Object.keys(NOME_DO_TIPO_DE_RESPOSTA) as TipoResposta[]

function rascunhoDe(pergunta: Pergunta): Rascunho {
  const tipo = pergunta.tipoResposta ?? 'ESCOLHA'
  return {
    chave: `p${pergunta.id}`,
    ordem: pergunta.ordem,
    texto: pergunta.texto,
    tipoResposta: tipo,
    alternativas: tipo === 'ESCOLHA' ? [...(pergunta.alternativas ?? [])] : [],
  }
}

function igual(rascunho: Rascunho, pergunta: Pergunta | undefined) {
  if (!pergunta) return false
  const original = rascunhoDe(pergunta)
  return (
    rascunho.texto.trim() === original.texto &&
    rascunho.tipoResposta === original.tipoResposta &&
    JSON.stringify(rascunho.alternativas.map((item) => item.trim())) === JSON.stringify(original.alternativas)
  )
}

/** As alternativas de uma pergunta de escolha: de 2 a 10, cada uma até 80 caracteres (a mesma regra da API). */
function Alternativas({ valores, aoMudar, erro }: { valores: string[]; aoMudar: (valores: string[]) => void; erro?: string }) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="texto-sobretitulo mb-1 text-texto-2">Alternativas</legend>
      {valores.map((valor, indice) => (
        <div key={indice} className="flex items-center gap-2 animate-surgir">
          <input
            aria-label={`Alternativa ${indice + 1}`}
            className="min-w-0 flex-1 rounded-xl border-[1.5px] border-borda bg-white px-3 py-2 text-sm font-semibold outline-none focus:border-secao"
            maxLength={80}
            value={valor}
            onChange={(evento) => aoMudar(valores.map((item, posicao) => (posicao === indice ? evento.target.value : item)))}
          />
          {valores.length > 2 && (
            <button
              type="button"
              aria-label={`Tirar a alternativa ${indice + 1}`}
              className="grid size-8 place-items-center rounded-full text-texto-2 hover:bg-cinza-100"
              onClick={() => aoMudar(valores.filter((_, posicao) => posicao !== indice))}
            >
              <i className="ri-close-line" aria-hidden="true" />
            </button>
          )}
        </div>
      ))}
      {valores.length < 10 && (
        <button type="button" className="self-start text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => aoMudar([...valores, ''])}>
          + Alternativa
        </button>
      )}
      {erro && (
        <p role="alert" className="px-1 text-xs font-medium text-[#c8203f]">
          {erro}
        </p>
      )}
    </fieldset>
  )
}

function Perguntas({ dados, rbs, aoEnviar, aoDecidir }: PropsPerguntas) {
  const salvas = [...dados.perguntas].sort((a, b) => a.ordem - b.ordem)
  const porOrdem = (ordem: number) => dados.perguntas.find((pergunta) => pergunta.ordem === ordem)
  const [rascunhos, setRascunhos] = useState<Rascunho[]>(() =>
    salvas.length ? salvas.map(rascunhoDe) : [{ chave: 'n1', ordem: 1, texto: '', tipoResposta: 'ESCOLHA', alternativas: ['', ''] }],
  )
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const editaveis = rascunhos.filter((rascunho) => porOrdem(rascunho.ordem)?.status !== 'APROVADA')
  const retiradas = salvas.some((pergunta) => !rascunhos.some((rascunho) => rascunho.ordem === pergunta.ordem))
  const mudou = retiradas || editaveis.some((rascunho) => !igual(rascunho, porOrdem(rascunho.ordem)))
  const nuncaEnviadas = dados.perguntas.some((pergunta) => pergunta.status === 'RASCUNHO')
  const podeEnviar = mudou || nuncaEnviadas
  const cabeMais = rascunhos.length < dados.limitePerguntas

  function mudar(chave: string, mudanca: Partial<Rascunho>) {
    setRascunhos((lista) => lista.map((rascunho) => (rascunho.chave === chave ? { ...rascunho, ...mudanca } : rascunho)))
    setFalha(null)
  }

  function acrescentar() {
    const ordem = Math.max(0, ...rascunhos.map((rascunho) => rascunho.ordem)) + 1
    setRascunhos((lista) => [...lista, { chave: `n${ordem}-${Date.now()}`, ordem, texto: '', tipoResposta: 'ESCOLHA', alternativas: ['', ''] }])
  }

  async function enviar() {
    if (!podeEnviar) return
    // A pergunta que não mudou vai como veio da API (a antiga, de antes do tipo, não tem alternativas e continua valendo).
    const lista = rascunhos
      .filter((rascunho) => rascunho.texto.trim())
      .map((rascunho) => {
        const salva = porOrdem(rascunho.ordem)
        // Sem mudança, vai só a ordem e o texto: a API mantém o tipo e as alternativas que já tem. Mandar "ESCOLHA" sem lista numa
        // pergunta antiga (de antes do tipo) faria a API recusar o envio inteiro.
        if (salva && igual(rascunho, salva)) return { ordem: salva.ordem, texto: salva.texto, mudou: false }
        return {
          ordem: rascunho.ordem,
          texto: rascunho.texto.trim(),
          tipoResposta: rascunho.tipoResposta,
          alternativas: rascunho.tipoResposta === 'ESCOLHA' ? rascunho.alternativas.map((item) => item.trim()).filter(Boolean) : undefined,
          mudou: true,
        }
      })
    // [VALIDACAO] As regras da API: de 1 ao limite, texto de 10 a 300, escolha com 2 a 10 alternativas (na pergunta nova ou mudada).
    const novos: Record<string, string> = {}
    if (!lista.length) novos.geral = 'Escreva pelo menos uma pergunta.'
    lista.forEach((item) => {
      if (!item.mudou) return
      if (item.texto.length < 10) novos[item.ordem] = 'A pergunta precisa de pelo menos 10 caracteres.'
      if ('alternativas' in item && item.alternativas && item.alternativas.length < 2) novos[`alt${item.ordem}`] = 'Uma pergunta de escolha precisa de pelo menos 2 alternativas.'
    })
    setErros(novos)
    setFalha(novos.geral ?? null)
    if (Object.keys(novos).length) return
    setEnviando(true)
    try {
      aoEnviar(await enviarPerguntas(dados.parceriaId, lista.map(({ mudou: _mudou, ...pergunta }) => pergunta)))
    } catch (erro) {
      if (!(erro instanceof ErroApi)) return setFalha('Não foi possível enviar as perguntas.')
      // O erro de "perguntas[1].alternativas" vai para o campo da pergunta certa, e não para uma frase solta no fim.
      const doCampo: Record<string, string> = {}
      const soltos: string[] = []
      Object.entries(erro.campos).forEach(([chave, mensagem]) => {
        const encontrado = /^perguntas\[(\d+)\]\.(\w+)$/.exec(chave)
        const item = encontrado ? lista[Number(encontrado[1])] : undefined
        if (item && encontrado?.[2] === 'alternativas') doCampo[`alt${item.ordem}`] = mensagem
        else if (item && encontrado?.[2] === 'texto') doCampo[item.ordem] = mensagem
        else soltos.push(item ? `Pergunta ${rascunhos.findIndex((r) => r.ordem === item.ordem) + 1}: ${mensagem}` : mensagem)
      })
      setErros(doCampo)
      setFalha(soltos[0] ?? (Object.keys(doCampo).length ? null : erro.message))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Perguntas da marca" extra={<span className="text-xs font-semibold text-texto-2">{rascunhos.length} de {dados.limitePerguntas}</span>}>
      <p className="mb-4 text-sm">
        {rbs
          ? 'A RBS pode escrever as perguntas pela marca ou ajustar as enviadas. Cada uma passa pela aprovação antes de ir a campo.'
          : `Até ${dados.limitePerguntas} perguntas na pesquisa feita nos espaços de ativação. A RBS aprova cada uma antes de ir a campo.`}
      </p>
      <div className="flex flex-col gap-4">
        {rascunhos.map((rascunho, indice) => {
          const pergunta = porOrdem(rascunho.ordem)
          const status = pergunta ? statusDa(pergunta) : null
          const travada = pergunta?.status === 'APROVADA'
          return (
            <div key={rascunho.chave} className="entrar-em-cascata rounded-2xl border border-borda p-4" style={{ '--ordem': indice } as React.CSSProperties}>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <p className="texto-sobretitulo text-texto-2">
                  Pergunta {indice + 1}
                  {travada && ` · ${NOME_DO_TIPO_DE_RESPOSTA[rascunho.tipoResposta]}`}
                </p>
                <span className="flex items-center gap-3">
                  {status && (
                    <span className="text-xs font-bold" style={{ color: status.cor }}>
                      {status.nome}
                      {/* A API guarda só a data do envio: ela vale para "Enviada"; a data da decisão da RBS fica no Histórico. */}
                      {pergunta?.status === 'ENVIADA' && pergunta.enviadaEm && ` · ${dataHora(pergunta.enviadaEm)}`}
                    </span>
                  )}
                  {!travada && rascunhos.length > 1 && (
                    <button
                      type="button"
                      className="text-xs font-semibold text-texto-2 hover:text-[#c8203f] hover:underline"
                      onClick={() => {
                        setRascunhos((lista) => lista.filter((item) => item.chave !== rascunho.chave))
                        // O erro era da pergunta que saiu (ou do envio com ela): não fica preso na tela.
                        setFalha(null)
                        setErros({})
                      }}
                    >
                      Tirar
                    </button>
                  )}
                </span>
              </div>
              {pergunta?.observacaoRbs && pergunta.status === 'RASCUNHO' && (
                <Aviso tipo="falha" titulo="A RBS pediu ajuste" className="mb-3">
                  {pergunta.observacaoRbs}
                </Aviso>
              )}
              {travada ? (
                <>
                  <p className="text-sm font-semibold">{rascunho.texto}</p>
                  {rascunho.alternativas.length > 0 && <p className="mt-1 text-xs font-medium text-texto-2">{rascunho.alternativas.join(' · ')}</p>}
                </>
              ) : (
                <div className="flex flex-col gap-3">
                  <AreaTexto
                    rotulo="Texto da pergunta"
                    maxLength={300}
                    rows={2}
                    value={rascunho.texto}
                    onChange={(evento) => mudar(rascunho.chave, { texto: evento.target.value })}
                    erro={erros[rascunho.ordem]}
                    ajuda={`${rascunho.texto.length} de 300 caracteres · mínimo de 10.`}
                  />
                  <Seletor
                    rotulo="Tipo de resposta"
                    value={rascunho.tipoResposta}
                    onChange={(evento) => {
                      const tipo = evento.target.value as TipoResposta
                      mudar(rascunho.chave, { tipoResposta: tipo, alternativas: tipo === 'ESCOLHA' ? (rascunho.alternativas.length >= 2 ? rascunho.alternativas : ['', '']) : [] })
                    }}
                  >
                    {TIPOS.map((tipo) => (
                      <option key={tipo} value={tipo}>
                        {NOME_DO_TIPO_DE_RESPOSTA[tipo]}
                      </option>
                    ))}
                  </Seletor>
                  {rascunho.tipoResposta === 'ESCOLHA' && (
                    <Alternativas valores={rascunho.alternativas} aoMudar={(alternativas) => mudar(rascunho.chave, { alternativas })} erro={erros[`alt${rascunho.ordem}`]} />
                  )}
                </div>
              )}
              {rbs && (pergunta?.status === 'ENVIADA' || pergunta?.status === 'APROVADA') && igual(rascunho, pergunta) && (
                <DecisaoRbs pergunta={pergunta} aoDecidir={aoDecidir} />
              )}
            </div>
          )
        })}
      </div>
      <div className="mt-4 flex flex-col gap-3">
        {cabeMais && (
          <button type="button" className="self-start text-[13px] font-semibold text-secao-texto hover:underline" onClick={acrescentar}>
            + Nova pergunta
          </button>
        )}
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {editaveis.length > 0 || retiradas ? (
          <div className="flex flex-wrap items-center gap-3">
            <Botao carregando={enviando} disabled={!podeEnviar} onClick={enviar} icone="ri-send-plane-line">
              {rbs ? 'Salvar perguntas' : 'Enviar perguntas'}
            </Botao>
            {!podeEnviar && (
              <span className="text-xs font-medium text-texto-2">
                {dados.perguntas.some((pergunta) => pergunta.enviadaEm) ? 'Nada mudou desde o último envio.' : 'Escreva ao menos uma pergunta para enviar.'}
              </span>
            )}
          </div>
        ) : (
          !rbs && (
            <p className="text-xs font-medium text-texto-2">
              Pergunta aprovada não muda mais por aqui. Para trocar uma delas, fale com o time B2B da RBS, que pode reabri-la para ajuste.
            </p>
          )
        )}
      </div>
    </Cartao>
  )
}

/** O perfil do público da ativação (P3), lado a lado com o público geral do festival quando a RBS tiver a pesquisa geral. */
function PerfilDoPublico({ parceriaId, rbs }: { parceriaId: number; rbs: boolean }) {
  const perfil = useDados(() => obterPerfilDaPesquisa(parceriaId).catch(() => null), [parceriaId])
  const dado = perfil.dado
  if (!dado || (dado.perfil.length === 0 && dado.respostasTexto.length === 0)) return null
  const dimensoes = [...new Set(dado.perfil.map((linha) => linha.dimensao))]
  const temGeral = dado.geral.length > 0
  return (
    <Cartao titulo="Quem passou pelo seu espaço">
      {dimensoes.length > 0 && (
        <>
          {temGeral && (
            <p className="mb-4 flex flex-wrap items-center gap-4 text-xs font-semibold text-texto-2">
              <span className="flex items-center gap-1.5">
                <span className="size-2.5 rounded-full bg-secao" aria-hidden="true" /> Seu espaço
              </span>
              <span className="flex items-center gap-1.5">
                <span className="size-2.5 rounded-full bg-cinza-400" aria-hidden="true" /> Festival inteiro
              </span>
            </p>
          )}
          <div className="grid gap-6 md:grid-cols-2">
            {dimensoes.map((dimensao) => {
              const linhas = dado.perfil.filter((linha) => linha.dimensao === dimensao)
              const bases = [...new Set(linhas.map((linha) => linha.baseRespondentes))]
              return (
                <section key={dimensao} aria-label={NOME_DA_DIMENSAO[dimensao] ?? dimensao}>
                  <h3 className="texto-sobretitulo text-texto-2">{NOME_DA_DIMENSAO[dimensao] ?? dimensao}</h3>
                  <ul className="mt-2 flex flex-col gap-2">
                    {linhas.map((linha, indice) => {
                      const geral = dado.geral.find((item) => item.dimensao === dimensao && item.categoria === linha.categoria)
                      return (
                        <li key={linha.categoria} className="entrar-em-cascata grid grid-cols-[minmax(90px,140px)_1fr_auto] items-center gap-3 text-sm" style={{ '--ordem': indice } as React.CSSProperties}>
                          <span className="font-medium">{linha.categoria}</span>
                          <span className="flex flex-col gap-1" aria-hidden="true">
                            <span className="h-2.5 overflow-hidden rounded-full bg-cinza-100">
                              <span className="block h-full rounded-full bg-secao transition-[width] duration-(--duracao-longa)" style={{ width: `${Math.min(100, linha.valor)}%` }} />
                            </span>
                            {geral && (
                              <span className="h-1.5 overflow-hidden rounded-full bg-cinza-100">
                                <span className="block h-full rounded-full bg-cinza-400" style={{ width: `${Math.min(100, geral.valor)}%` }} />
                              </span>
                            )}
                          </span>
                          <span className="w-24 text-right">
                            <span className="font-semibold">{numero(linha.valor)}%</span>
                            {geral && <span className="block text-xs text-texto-2">festival {numero(geral.valor)}%</span>}
                          </span>
                        </li>
                      )
                    })}
                  </ul>
                  <p className="mt-2 text-xs font-medium text-texto-2">Base: {bases.map((base) => numero(base)).join(' / ')} respondentes</p>
                </section>
              )
            })}
          </div>
        </>
      )}
      {dado.respostasTexto.length > 0 && (
        <section className={dimensoes.length ? 'mt-6' : ''} aria-label="Respostas livres">
          <h3 className="texto-sobretitulo text-texto-2">Respostas livres</h3>
          <ul className="mt-2 flex flex-wrap gap-2">
            {dado.respostasTexto.map((resposta) => (
              <li
                key={`${resposta.perguntaId}-${resposta.texto}`}
                className={`rounded-full px-3 py-1.5 text-sm font-medium ${resposta.suspeita ? 'bg-estado-ausente/30' : 'bg-cinza-100'}`}
                title={rbs && resposta.suspeita ? 'Pode ter dado pessoal: a marca não vê esta resposta.' : undefined}
              >
                “{resposta.texto}”{resposta.contagem > 1 && <span className="text-texto-2"> ×{resposta.contagem}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </Cartao>
  )
}

export function Pesquisa() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'PESQUISA')
  const bloqueada = estado !== 'LIBERADA'
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
            : bloqueada
              ? 'A pesquisa nos espaços de ativação é das cotas Master.'
              : 'As perguntas da sua marca na pesquisa do festival e os resultados.'
        }
      />
      {bloqueada && !rbs ? (
        <AvisoAbaFechada parceria={parceria} estado={estado} nome="Pesquisa" rbs={false} />
      ) : (
        <Carregando dados={dados}>
          {(buscado) => {
            const dado = atual ?? buscado
            if (!dado) return null
            // O formulário recomeça só quando as perguntas mudam de fato (envio ou decisão da RBS).
            const assinatura = dado.perguntas.map((pergunta) => `${pergunta.id}:${pergunta.status}:${pergunta.texto}`).join('|')
            return (
              <>
                {enviadas && (
                  <Aviso titulo={rbs ? 'Perguntas salvas' : 'Perguntas enviadas para a RBS'}>Cada pergunta fica “Enviada” até a RBS aprovar ou pedir ajuste.</Aviso>
                )}
                {dado.pesquisa ? (
                  <Aviso titulo={dado.pesquisa.nome}>
                    {dado.pesquisa.metodologia ?? 'Metodologia a informar pela RBS.'}
                    {dado.pesquisa.baseRespondentes && <span className="mt-1 block">Base da pesquisa da edição: {numero(dado.pesquisa.baseRespondentes)} respondentes.</span>}
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
                <PerfilDoPublico parceriaId={parceria.id} rbs={rbs} />
                <Cartao titulo="Resultados">
                  {dado.resultados.length === 0 ? (
                    <Aviso tipo="ausente" titulo="Resultados ainda não carregados">
                      A RBS carrega os resultados da empresa de pesquisa depois do festival. Toda resposta vem com a base de respondentes.
                    </Aviso>
                  ) : (
                    <ResultadosPorPergunta resultados={dado.resultados} perguntas={dado.perguntas} />
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
