// Avaliador de conversas do Atlas (seção 8.2 do plano). Node 22 ou mais novo, sem dependências.
//
//   node ferramentas/avaliacao-atlas/avaliar.mjs --marco M4 [--categoria documentos,ataque] [--split ajuste|reserva|todos] [--casos doc-07,doc-08]
//   node ferramentas/avaliacao-atlas/avaliar.mjs --livre "Até quando informo os geradores?" --livre "e na Arena?" --persona amarelo
//
// A API vem de --api, de PORTAL_API_URL ou de http://localhost:8080 (scripts/atlas-dev.sh), com o perfil dev.
// Cada rodada grava resultado.json e relatorio.html em docs/atlas/controle/avaliacoes/<AAAA-MM-DD-HHMM>-<marco>/.
// Sai com 1 se um bloqueante falhar ou se um caso der erro.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { Sessao } from './api.mjs'
import { conferir, numerosSemOrigem, passou, resumirTurnos, vazamentos } from './conferencias.mjs'
import { calcularMetricas, montarHtml } from './relatorio.mjs'

const PASTA = path.dirname(fileURLToPath(import.meta.url))
const RAIZ = path.resolve(PASTA, '../..')
const PASTA_DE_RODADAS = path.join(RAIZ, 'docs/atlas/controle/avaliacoes')

const SENHA = 'senha123'
const EMAILS = {
  amarelo: 'marketing@amarelo.example',
  roxo: 'marketing@roxo.example',
  verde: 'marketing@verde.example',
  rbs: 'b2b@rbs.example',
}
const MARCOS = ['M0', 'M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9', 'R1', 'R2', 'R3', 'R4']

const { values: opcoes } = parseArgs({
  options: {
    marco: { type: 'string', default: 'M1' },
    categoria: { type: 'string' },
    split: { type: 'string', default: 'ajuste' },
    casos: { type: 'string' },
    api: { type: 'string' },
    paralelo: { type: 'string', default: '3' },
    saida: { type: 'string' },
    listar: { type: 'boolean', default: false },
    livre: { type: 'string', multiple: true },
    persona: { type: 'string', default: 'amarelo' },
  },
})
const API = opcoes.api ?? process.env.PORTAL_API_URL ?? 'http://localhost:8080'

function carregarCasos() {
  const pasta = path.join(PASTA, 'casos')
  const casos = fs.readdirSync(pasta).filter((a) => a.endsWith('.jsonl')).sort().flatMap((arquivo) =>
    fs.readFileSync(path.join(pasta, arquivo), 'utf8').split('\n').filter((l) => l.trim()).map((linha, i) => {
      let caso
      try { caso = JSON.parse(linha) } catch (erro) { throw new Error(`${arquivo}, linha ${i + 1}: ${erro.message}`) }
      for (const campo of ['id', 'categoria', 'split', 'desde', 'persona', 'mensagens', 'espera']) {
        if (caso[campo] === undefined) throw new Error(`${arquivo}, linha ${i + 1}: falta ${campo}`)
      }
      if (!MARCOS.includes(caso.desde)) throw new Error(`${caso.id}: marco desconhecido ${caso.desde}`)
      if (!EMAILS[caso.persona]) throw new Error(`${caso.id}: persona desconhecida ${caso.persona}`)
      return caso
    }),
  )
  const ids = casos.map((c) => c.id)
  const repetido = ids.find((id, i) => ids.indexOf(id) !== i)
  if (repetido) throw new Error(`caso repetido: ${repetido}`)
  return casos
}

function filtrar(casos) {
  const ate = MARCOS.indexOf(opcoes.marco)
  if (ate < 0) throw new Error(`marco desconhecido: ${opcoes.marco}`)
  const categorias = opcoes.categoria?.split(',')
  const ids = opcoes.casos?.split(',')
  return casos.filter((c) =>
    MARCOS.indexOf(c.desde) <= ate &&
    (opcoes.split === 'todos' || c.split === opcoes.split) &&
    (!categorias || categorias.includes(c.categoria)) &&
    (!ids || ids.includes(c.id)),
  )
}

const sessoes = new Map()
async function sessaoDe(persona) {
  if (!sessoes.has(persona)) sessoes.set(persona, new Sessao(API).entrar(EMAILS[persona], SENHA))
  return sessoes.get(persona)
}

async function abrirConversa(sessao) {
  const r = await sessao.pedir('POST', `/api/parcerias/${sessao.parceriaId}/atlas/conversas`, {})
  if (r.status !== 201) throw new Error(`abrir conversa: status ${r.status}: ${r.json?.detail ?? r.texto}`)
  return r.json
}

/** Um passo: uma mensagem de texto, o botão do relatório ou a resposta ao cartão aberto. */
async function darPasso(sessao, conversaId, passo, turnos) {
  if (typeof passo === 'string') return sessao.pedir('POST', `/api/atlas/conversas/${conversaId}/mensagens`, { texto: passo })
  if (passo.botao) return sessao.pedir('POST', `/api/atlas/conversas/${conversaId}/mensagens`, { atalho: 'MONTAR_RELATORIO', modelo: 'ATIVACAO_EVENTO' })
  if (passo.responderCartao) {
    const cartao = turnos.flatMap((t) => t.itens).filter((i) => i.tipo === 'cartao' && i.status === 'PENDENTE').at(-1)
    if (!cartao) throw new Error('o caso pede para responder ao cartão, e nenhum cartão está aberto')
    return sessao.pedir('POST', `/api/atlas/solicitacoes/${cartao.solicitacaoId}/respostas`, passo.responderCartao)
  }
  throw new Error(`passo desconhecido: ${JSON.stringify(passo)}`)
}

/** Lê o A4 pela rota 47 e o diagnóstico de cada turno pela rota 49, quando existem. */
async function lerDepois(sessao, conversaId, conversa, resumo) {
  const relatorios = []
  for (const { relatorioId } of resumo.relatorios) {
    const r = await sessao.pedir('GET', `/api/atlas/relatorios/${relatorioId}`)
    if (r.status === 200) relatorios.push(r.json)
  }
  let diagnostico = null
  if (conversa?.diagnosticoDisponivel) {
    const r = await sessao.pedir('GET', `/api/atlas/conversas/${conversaId}/diagnostico`)
    if (r.status === 200) diagnostico = r.json
  }
  return { relatorios, diagnostico }
}

async function tentar(caso) {
  const inicio = performance.now()
  try {
    const sessao = await sessaoDe(caso.persona)
    const abertura = await abrirConversa(sessao)
    const conversaId = abertura.conversaId
    const turnos = []
    let ultima = abertura
    for (const passo of caso.mensagens) {
      const r = await darPasso(sessao, conversaId, passo, turnos)
      if (r.status !== 200) throw new Error(`status ${r.status} em ${JSON.stringify(passo)}: ${r.json?.detail ?? r.texto?.slice(0, 200)}`)
      ultima = r.json
      turnos.push({ itens: r.json.itens, ms: r.ms })
    }
    const resumo = resumirTurnos(turnos)
    const { relatorios, diagnostico } = await lerDepois(sessao, conversaId, ultima, resumo)
    return {
      conversaId,
      conferencias: conferir(caso, turnos, relatorios),
      itens: resumo.itens,
      latencias: resumo.latencias,
      ms: Math.round(performance.now() - inicio),
      turnos: [...new Set(resumo.itens.map((i) => i.turno).filter(Boolean))],
      tokens: diagnostico?.tokens ?? null,
      custoUsd: diagnostico?.custoUsd ?? null,
    }
  } catch (erro) {
    return {
      erro: erro.message,
      conferencias: [{ nome: 'execucao', ok: false, detalhe: erro.message }],
      itens: [],
      latencias: [],
      ms: Math.round(performance.now() - inicio),
    }
  }
}

/** Caso que falha roda de novo, uma vez. Falhou nas duas: falhou. Passou em uma: instável. */
async function rodarCaso(caso) {
  const primeira = await tentar(caso)
  if (passou(primeira.conferencias)) return { ...caso, estado: 'passou', tentativas: [primeira] }
  const segunda = await tentar(caso)
  const estado = passou(segunda.conferencias) ? 'instavel' : segunda.erro ? 'erro' : 'falhou'
  return { ...caso, estado, tentativas: [primeira, segunda] }
}

async function emParalelo(lista, quantos, fazer) {
  const resultados = new Array(lista.length)
  let proximo = 0
  await Promise.all(Array.from({ length: quantos }, async () => {
    while (proximo < lista.length) {
      const i = proximo++
      resultados[i] = await fazer(lista[i])
    }
  }))
  return resultados
}

const carimbo = () => {
  const partes = Object.fromEntries(
    new Intl.DateTimeFormat('pt-BR', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .formatToParts(new Date()).map((p) => [p.type, p.value]),
  )
  return `${partes.year}-${partes.month}-${partes.day}-${partes.hour}${partes.minute}`
}

function rodadaAnterior(pastaAtual) {
  if (!fs.existsSync(PASTA_DE_RODADAS)) return null
  const pastas = fs.readdirSync(PASTA_DE_RODADAS).filter((p) => !p.endsWith('-livre')).sort().reverse()
  for (const pasta of pastas) {
    const arquivo = path.join(PASTA_DE_RODADAS, pasta, 'resultado.json')
    if (path.join(PASTA_DE_RODADAS, pasta) !== pastaAtual && fs.existsSync(arquivo)) return JSON.parse(fs.readFileSync(arquivo, 'utf8'))
  }
  return null
}

async function modoLivre() {
  const persona = opcoes.persona
  if (!EMAILS[persona] || persona === 'rbs') throw new Error('--persona: amarelo, roxo ou verde')
  const sessao = await sessaoDe(persona)
  const conversa = await abrirConversa(sessao)
  const turnos = []
  for (const mensagem of opcoes.livre) {
    const r = await sessao.pedir('POST', `/api/atlas/conversas/${conversa.conversaId}/mensagens`, { texto: mensagem })
    if (r.status !== 200) throw new Error(`status ${r.status}: ${r.json?.detail ?? r.texto?.slice(0, 200)}`)
    turnos.push({ itens: r.json.itens, ms: r.ms })
    console.log(`\nPessoa (${persona}): ${mensagem}`)
    for (const item of r.json.itens) {
      if (item.tipo === 'ferramenta') console.log(`  [ferramenta] ${item.nome} ${item.argumentos} · ${item.duracaoMs} ms${item.erro ? ' · erro' : ''}`)
      if (item.tipo === 'texto') console.log(`Atlas${item.porModelo === false ? ' (frase fixa)' : ''}: ${item.texto}`)
      if (item.tipo === 'texto' && item.fontes?.length) console.log(`  [fontes] ${item.fontes.map((f) => `${f.marcador} ${f.titulo} v${f.versao} p.${f.pagina}`).join(' · ')}`)
      if (!['usuario', 'texto', 'ferramenta', 'raciocinio'].includes(item.tipo)) console.log(`  [${item.tipo}] ${JSON.stringify(item).slice(0, 300)}`)
      if (item.turno) console.log(`  [turno] ${item.turno}`)
    }
    console.log(`  (${r.ms} ms)`)
  }
  const resumo = resumirTurnos(turnos)
  const achados = { vazamentos: vazamentos(persona, resumo), numerosSemOrigem: numerosSemOrigem(turnos) }
  console.log(`\nVazamentos: ${achados.vazamentos.length}. Números sem origem: ${achados.numerosSemOrigem.length}.`)
  for (const a of [...achados.vazamentos, ...achados.numerosSemOrigem]) console.log(`  ${a}`)

  const pasta = opcoes.saida ?? path.join(PASTA_DE_RODADAS, `${carimbo()}-livre`)
  fs.mkdirSync(pasta, { recursive: true })
  const arquivo = path.join(pasta, `${persona}-${conversa.conversaId}.json`)
  fs.writeFileSync(arquivo, JSON.stringify({ persona, conversaId: conversa.conversaId, turnos, achados }, null, 2))
  console.log(`\nGravado em ${path.relative(RAIZ, arquivo)}`)
}

async function rodada() {
  const todos = carregarCasos()
  const escolhidos = filtrar(todos)
  if (opcoes.listar) {
    const contar = (lista, chave) => Object.entries(Object.groupBy(lista, (c) => c[chave])).map(([k, v]) => `${k} ${v.length}`).join(' · ')
    console.log(`${todos.length} casos no conjunto\nPor categoria: ${contar(todos, 'categoria')}\nPor split: ${contar(todos, 'split')}\nPor marco: ${contar(todos, 'desde')}`)
    console.log(`Nesta seleção (${opcoes.marco}, split ${opcoes.split}): ${escolhidos.length}`)
    return 0
  }
  if (!escolhidos.length) throw new Error('nenhum caso para esta seleção')

  console.log(`${escolhidos.length} casos (${opcoes.marco}, split ${opcoes.split}) contra ${API}`)
  const casos = await emParalelo(escolhidos, Number(opcoes.paralelo), async (caso) => {
    const resultado = await rodarCaso(caso)
    const motivos = resultado.tentativas.at(-1).conferencias.filter((c) => !c.ok).map((c) => c.nome).join(', ')
    console.log(`  ${resultado.estado.padEnd(8)} ${caso.id}${motivos && resultado.estado !== 'instavel' ? `: ${motivos}` : ''}`)
    return resultado
  })

  const pasta = opcoes.saida ?? path.join(PASTA_DE_RODADAS, `${carimbo()}-${opcoes.marco}`)
  const anterior = rodadaAnterior(pasta)
  const resultado = {
    marco: opcoes.marco,
    quando: new Date().toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' }),
    api: API,
    split: opcoes.split,
    categorias: opcoes.categoria?.split(',') ?? null,
    pulados: todos.length - escolhidos.length,
    metricas: calcularMetricas(casos),
    casos,
  }
  fs.mkdirSync(pasta, { recursive: true })
  fs.writeFileSync(path.join(pasta, 'resultado.json'), JSON.stringify(resultado, null, 2))
  fs.writeFileSync(path.join(pasta, 'relatorio.html'), montarHtml(resultado, anterior))

  console.log('\nMétricas')
  for (const m of resultado.metricas) {
    const estado = m.passou === null ? 'sem casos' : m.passou ? 'passou' : 'FALHOU'
    console.log(`  ${m.bloqueante ? '(B) ' : ''}${m.nome}: ${m.valor ?? '—'} ${m.valor === null ? '' : m.unidade} · ${estado}`)
  }
  console.log(`\nRelatório: ${path.relative(RAIZ, path.join(pasta, 'relatorio.html'))}`)
  const reprovou = resultado.metricas.some((m) => m.bloqueante && m.passou === false) || casos.some((c) => c.estado === 'erro')
  return reprovou ? 1 : 0
}

try {
  if (opcoes.livre?.length) {
    await modoLivre()
    process.exitCode = 0
  } else {
    process.exitCode = await rodada()
  }
} catch (erro) {
  console.error(erro.message)
  process.exitCode = 2
}
