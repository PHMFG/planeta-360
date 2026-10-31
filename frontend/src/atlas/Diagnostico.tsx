// O diagnóstico de um turno do Atlas: a gaveta com a linha do tempo das etapas, usada na conversa da marca e na página da RBS. [ATLAS]
// O servidor manda o traço já sanitizado: nunca o raciocínio cru, o prompt de sistema nem uma chave.
import { useEffect, useId, useState, type ReactNode } from 'react'
import { ErroApi } from '../api/cliente'
import { diagnosticoDaConversa, type EtapaDoTraco, type ResultadoDaEtapa, type TurnoDoTraco } from '../api/diagnostico'
import { usarFocoPreso } from '../componentes/usarFocoPreso'
import { dataHora, numero } from '../util/formatar'
import { LinhaBuscando, PainelLateral } from './Elementos'

const NOME_DA_ETAPA: Record<string, string> = {
  G1_LIMITES: 'Limites',
  J1_INTENCAO: 'JEV · intenção',
  J1_CARTAO: 'JEV · cartão aberto',
  LLM_IDA: 'Ida ao modelo',
  FERRAMENTA: 'Ferramenta',
  BUSCA: 'Busca nos documentos',
  R3_FONTES: 'JEV · trechos relevantes',
  CHECAGEM: 'Checagem do texto',
  R5_RESPOSTA: 'JEV · apoio da resposta',
  V2_COERENCIA: 'JEV · coerência',
  LACUNAS: 'JEV · lacunas',
  RESUMO: 'Resumo do raciocínio',
  SAIDA: 'Saída',
  DISJUNTOR: 'Disjuntor',
  ERRO: 'Erro',
}

const NOME_DO_RESULTADO: Record<ResultadoDaEtapa, string> = { OK: 'OK', RECUSADO: 'Recusado', ERRO: 'Erro', SOMBRA: 'Em sombra', PULADO: 'Pulado' }

const COR_DO_RESULTADO: Record<ResultadoDaEtapa, string> = {
  OK: 'text-[#7ee2a0] border-[#7ee2a0]/40',
  RECUSADO: 'text-[#ffd201] border-[#ffd201]/40',
  ERRO: 'text-[#ff8aa1] border-[#ff8aa1]/40',
  SOMBRA: 'text-atlas-lilas border-atlas-lilas/40',
  PULADO: 'text-white/64 border-white/24',
}

const COR_DA_BARRA: Record<ResultadoDaEtapa, string> = {
  OK: 'bg-[#7ee2a0]',
  RECUSADO: 'bg-[#ffd201]',
  ERRO: 'bg-[#ff8aa1]',
  SOMBRA: 'bg-atlas-lilas',
  PULADO: 'bg-white/40',
}

// Rodam ao lado do caminho crítico: ficam fora da soma do turno (seção 8.4 do plano) e aparecem numa faixa própria.
const EM_PARALELO = new Set(['RESUMO', 'R3_FONTES'])

// Os campos de cada tipo de etapa, na ordem de leitura. O que não estiver aqui aparece depois, com o nome cru, e o JSON inteiro fica como reserva.
const CAMPOS_DA_ETAPA: Record<string, [string, string][]> = {
  G1_LIMITES: [['usadasHoje', 'Usadas hoje'], ['tetoPorParceria', 'Teto por parceria']],
  LLM_IDA: [
    ['ida', 'Ida'],
    ['modelo', 'Modelo'],
    ['tokensEntrada', 'Tokens de entrada'],
    ['tokensSaida', 'Tokens de saída'],
    ['motivoDoFim', 'Motivo do fim'],
    ['latenciaMs', 'Latência (ms)'],
    ['mensagens', 'Mensagens enviadas'],
    ['chamadas', 'Ferramentas pedidas'],
    ['comTexto', 'Respondeu com texto'],
    ['emFluxo', 'Em fluxo'],
    ['custoUsd', 'Custo (US$)'],
    ['custoOrigem', 'Origem do custo'],
    ['ferramentas', 'Ferramentas oferecidas'],
  ],
  FERRAMENTA: [['ferramenta', 'Ferramenta'], ['motivo', 'Motivo'], ['erro', 'Com erro'], ['argumentos', 'Argumentos'], ['resultado', 'Resultado']],
  BUSCA: [['consulta', 'Consulta'], ['degradado', 'Só a lista textual'], ['vetorial', 'Lista vetorial'], ['textual', 'Lista textual'], ['fusao', 'Fusão (RRF)']],
  CHECAGEM: [['aprovado', 'Aprovado'], ['motivo', 'Motivo'], ['origem', 'Origem'], ['caracteres', 'Caracteres']],
  RESUMO: [['noPrazo', 'No prazo'], ['pendente', 'Pendente'], ['modelo', 'Modelo'], ['tokensEntrada', 'Tokens de entrada'], ['tokensSaida', 'Tokens de saída'], ['latenciaMs', 'Latência (ms)'], ['caracteres', 'Caracteres']],
  SAIDA: [['tipo', 'Tipo'], ['caracteres', 'Caracteres'], ['idasAoModelo', 'Idas ao modelo'], ['chamadasDeFerramenta', 'Chamadas de ferramenta'], ['resumosPendentes', 'Resumos pendentes'], ['esperaDosResumosMs', 'Espera dos resumos (ms)']],
}
const CAMPOS_DO_JEV: [string, string][] = [['modo', 'Modo'], ['modelo', 'Modelo'], ['pergunta', 'Pergunta'], ['resposta', 'Resposta'], ['confianca', 'Confiança'], ['limiar', 'Limiar'], ['acao', 'Ação']]

const camposDe = (etapa: string) => CAMPOS_DA_ETAPA[etapa] ?? (etapa.startsWith('J1_') || ['R3_FONTES', 'R5_RESPOSTA', 'V2_COERENCIA', 'LACUNAS'].includes(etapa) ? CAMPOS_DO_JEV : [])

export const duracao = (ms: number) => (ms < 1000 ? `${ms} ms` : `${(ms / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} s`)
export const dolares = (valor: number) => `US$ ${valor.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 })}`

/** O resultado de uma etapa ou de um turno, com a cor. */
export function SeloDoResultado({ resultado }: { resultado: ResultadoDaEtapa }) {
  return <span className={`shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${COR_DO_RESULTADO[resultado]}`}>{NOME_DO_RESULTADO[resultado]}</span>
}

/** Texto que é JSON sai com recuo; o que foi cortado em 20 mil caracteres sai como veio. */
function legivel(valor: unknown) {
  if (typeof valor !== 'string') return JSON.stringify(valor, null, 2)
  try {
    return JSON.stringify(JSON.parse(valor), null, 2)
  } catch {
    return valor
  }
}

const curto = (valor: unknown) => valor === null || ['number', 'boolean'].includes(typeof valor) || (typeof valor === 'string' && valor.length <= 80 && !valor.startsWith('{') && !valor.startsWith('['))
const paraLer = (valor: unknown) => (valor === true ? 'Sim' : valor === false ? 'Não' : valor === null ? '—' : String(valor))

function Campo({ rotulo, valor }: { rotulo: string; valor: unknown }) {
  if (curto(valor)) {
    return (
      <div className="flex justify-between gap-4 text-[13px]">
        <dt className="text-white/64">{rotulo}</dt>
        <dd className="min-w-0 text-right break-words text-white/90">{paraLer(valor)}</dd>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-1 text-[13px]">
      <dt className="text-white/64">{rotulo}</dt>
      <dd>
        <pre className="max-h-64 overflow-y-auto rounded-lg bg-black/25 p-2.5 font-mono text-xs leading-[1.5] break-words whitespace-pre-wrap text-white/85">{legivel(valor)}</pre>
      </dd>
    </div>
  )
}

function DetalheDaEtapa({ etapa }: { etapa: EtapaDoTraco }) {
  const conhecidos = camposDe(etapa.etapa)
  const nomes = new Set(conhecidos.map(([chave]) => chave))
  const outros = Object.entries(etapa.dados).filter(([chave]) => !nomes.has(chave))
  return (
    <div className="flex flex-col gap-2.5 border-t border-white/10 px-3 py-3">
      <dl className="flex flex-col gap-1.5">
        {conhecidos.filter(([chave]) => chave in etapa.dados).map(([chave, rotulo]) => <Campo key={chave} rotulo={rotulo} valor={etapa.dados[chave]} />)}
        {outros.map(([chave, valor]) => <Campo key={chave} rotulo={chave} valor={valor} />)}
      </dl>
      <details className="text-[13px]">
        <summary className="cursor-pointer font-semibold text-atlas-lilas">JSON desta etapa</summary>
        <pre className="mt-1.5 max-h-64 overflow-y-auto rounded-lg bg-black/25 p-2.5 font-mono text-xs leading-[1.5] break-words whitespace-pre-wrap text-white/85">{JSON.stringify(etapa, null, 2)}</pre>
      </details>
    </div>
  )
}

function LinhaDaEtapa({ etapa, inicioDoTurno, total }: { etapa: EtapaDoTraco; inicioDoTurno: number; total: number }) {
  const [aberta, setAberta] = useState(false)
  const id = useId()
  const inicio = Math.max(0, new Date(etapa.iniciadaEm).getTime() - inicioDoTurno)
  const esquerda = Math.min(100, (inicio / total) * 100)
  const largura = Math.max(0.8, Math.min(100 - esquerda, (etapa.duracaoMs / total) * 100))
  return (
    <li className="rounded-[12px] border border-white/10 bg-white/5">
      <button type="button" aria-expanded={aberta} aria-controls={id} onClick={() => setAberta(!aberta)} className="flex w-full flex-col gap-1.5 px-3 py-2.5 text-left hover:bg-white/5">
        <span className="flex items-center gap-2">
          <i className={`${aberta ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'} text-base text-white/64`} aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-white">{NOME_DA_ETAPA[etapa.etapa] ?? etapa.etapa}</span>
          <span className="shrink-0 text-xs text-white/72 tabular-nums">{duracao(etapa.duracaoMs)}</span>
          <SeloDoResultado resultado={etapa.resultado} />
        </span>
        <span className="relative block h-1.5 overflow-hidden rounded-full bg-white/10" aria-hidden="true">
          <span className={`absolute inset-y-0 rounded-full ${COR_DA_BARRA[etapa.resultado]}`} style={{ left: `${esquerda}%`, width: `${largura}%` }} />
        </span>
      </button>
      {aberta && (
        <div id={id}>
          <DetalheDaEtapa etapa={etapa} />
        </div>
      )}
    </li>
  )
}

function Resumo({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  return (
    <div className="min-w-0 rounded-[12px] bg-white/6 px-3 py-2">
      <p className="text-[11px] text-white/64">{rotulo}</p>
      <p className="truncate text-sm font-semibold text-white tabular-nums">{children}</p>
    </div>
  )
}

function ListaDeEtapas({ etapas, inicio, total }: { etapas: EtapaDoTraco[]; inicio: number; total: number }) {
  return (
    <ol className="flex flex-col gap-2">
      {etapas.map((etapa) => <LinhaDaEtapa key={etapa.ordem} etapa={etapa} inicioDoTurno={inicio} total={total} />)}
    </ol>
  )
}

/** O traço de um turno: o resumo, a linha do tempo, com as etapas paralelas à parte, e o botão de copiar. */
export function TracoDoTurno({ turno }: { turno: TurnoDoTraco }) {
  const [copiado, setCopiado] = useState<'sim' | 'falhou' | null>(null)
  const inicio = new Date(turno.iniciadoEm).getTime()
  const fimDaUltima = Math.max(turno.duracaoMs, ...turno.etapas.map((etapa) => new Date(etapa.iniciadaEm).getTime() - inicio + etapa.duracaoMs))
  const total = Math.max(1, fimDaUltima)
  const paralelas = turno.etapas.filter((etapa) => EM_PARALELO.has(etapa.etapa))

  async function copiar() {
    try {
      await navigator.clipboard.writeText(JSON.stringify(turno, null, 2))
      setCopiado('sim')
    } catch {
      setCopiado('falhou')
    }
  }

  return (
    <div className="flex min-h-0 flex-col gap-3 overflow-y-auto">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Resumo rotulo="Resultado">{NOME_DO_RESULTADO[turno.resultado]}</Resumo>
        <Resumo rotulo="Duração">{duracao(turno.duracaoMs)}</Resumo>
        <Resumo rotulo="Tokens">{numero(turno.tokensEntrada)} + {numero(turno.tokensSaida)}</Resumo>
        <Resumo rotulo="Custo">{dolares(turno.custoUsd)}</Resumo>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-white/64">Começou em {dataHora(turno.iniciadoEm)}</p>
        <button type="button" onClick={copiar} className="inline-flex items-center gap-1.5 rounded-full border border-white/20 bg-white/8 px-3 py-1.5 text-xs font-semibold text-white hover:bg-white/14">
          <i className="ri-file-copy-line text-sm" aria-hidden="true" />
          Copiar JSON
        </button>
      </div>
      <p className="sr-only" role="status">{copiado === 'sim' ? 'JSON copiado.' : copiado === 'falhou' ? 'Não foi possível copiar.' : ''}</p>
      {copiado === 'falhou' && <p className="text-xs text-[#ff8aa1]">Não foi possível copiar: o navegador bloqueou a área de transferência.</p>}
      <ListaDeEtapas etapas={turno.etapas.filter((etapa) => !EM_PARALELO.has(etapa.etapa))} inicio={inicio} total={total} />
      {paralelas.length > 0 && (
        <section className="flex flex-col gap-2" aria-label="Em paralelo">
          <h3 className="text-[11px] font-bold tracking-[0.12em] text-atlas-lilas uppercase">Em paralelo, fora da soma do turno</h3>
          <ListaDeEtapas etapas={paralelas} inicio={inicio} total={total} />
        </section>
      )}
    </div>
  )
}

/** A gaveta à direita, por cima da tela. No celular, ocupa a largura toda. */
export function GavetaDoDiagnostico({ titulo, aoFechar, children }: { titulo: string; aoFechar: () => void; children: ReactNode }) {
  const painel = usarFocoPreso(true, aoFechar)
  return (
    <div className="fixed inset-0 z-[90] flex justify-end bg-black/50" onClick={aoFechar}>
      <div
        ref={painel}
        role="dialog"
        aria-modal="true"
        aria-label={titulo}
        tabIndex={-1}
        onClick={(evento) => evento.stopPropagation()}
        className="flex h-full w-full max-w-[620px] min-w-0 flex-col p-2 outline-none sm:p-3"
      >
        <PainelLateral sobretitulo="Diagnóstico do turno" titulo={titulo} aoFechar={aoFechar} className="h-full">
          {children}
        </PainelLateral>
      </div>
    </div>
  )
}

/** O botão embaixo de cada resposta do Atlas, com a gaveta do turno dela. */
export function BotaoDiagnostico({ conversaId, turno }: { conversaId: number; turno: string }) {
  const [aberta, setAberta] = useState(false)
  const [traco, setTraco] = useState<TurnoDoTraco | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    if (!aberta) return
    let ativo = true
    setTraco(null)
    setFalha(null)
    diagnosticoDaConversa(conversaId)
      .then((diagnostico) => {
        if (!ativo) return
        const achado = diagnostico.turnos.find((item) => item.turno === turno)
        if (achado) setTraco(achado)
        else setFalha('Este turno ainda não tem traço. Tente de novo em instantes.')
      })
      .catch((erro: unknown) => ativo && setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir o diagnóstico.'))
    return () => {
      ativo = false
    }
  }, [aberta, conversaId, turno])

  return (
    <>
      <button type="button" onClick={() => setAberta(true)} className="inline-flex items-center gap-1.5 self-start rounded-full px-1 py-0.5 text-xs font-semibold text-white/64 hover:text-white">
        <i className="ri-pulse-line text-sm" aria-hidden="true" />
        Diagnóstico
      </button>
      {aberta && (
        <GavetaDoDiagnostico titulo="Como esta resposta foi feita" aoFechar={() => setAberta(false)}>
          {falha ? <p className="text-sm text-[#ff8aa1]" role="alert">{falha}</p> : traco ? <TracoDoTurno turno={traco} /> : <LinhaBuscando texto="Abrindo o diagnóstico…" />}
        </GavetaDoDiagnostico>
      )}
    </>
  )
}
