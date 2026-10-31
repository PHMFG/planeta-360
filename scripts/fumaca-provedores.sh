#!/bin/bash
# Fumaça dos provedores do Atlas: OpenCode Go, JEV (TypeSafe) e Gemini. [Planeta 360]
# As chaves vêm de backend/config/segredos.local.properties, e a variável de ambiente vence o arquivo. Nunca imprime um valor.
# Se existir o registro local do Atlas (docs/atlas/controle/progresso.md), acrescenta o resultado nele.
# Uso: scripts/fumaca-provedores.sh    sai com 1 se alguma conferência falhar

set -euo pipefail
RAIZ="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
export RAIZ

exec node --input-type=module <<'JS'
import fs from 'node:fs'
import path from 'node:path'

const RAIZ = process.env.RAIZ
const AMOSTRAS_JEV = 20
const LIMITE_JEV_MS = 800
const MODELO_LLM = 'deepseek-v4.1-flash'
const MODELO_EMBEDDING = 'gemini-embedding-2'
const DIMENSOES = 768

const arquivo = path.join(RAIZ, 'backend/config/segredos.local.properties')
const doArquivo = fs.existsSync(arquivo)
  ? Object.fromEntries(
      fs.readFileSync(arquivo, 'utf8').split('\n')
        .filter((linha) => linha.includes('=') && !linha.startsWith('#'))
        .map((linha) => [linha.slice(0, linha.indexOf('=')).trim(), linha.slice(linha.indexOf('=') + 1).trim()]),
    )
  : {}
const chave = (nome) => process.env[nome] || doArquivo[nome] || ''
const CHAVES = { opencode: chave('OPENCODE_API_KEY'), jev: chave('TYPESAFE_API_KEY'), gemini: chave('GEMINI_API_KEY') }

// Tudo o que sai passa por aqui: um erro do provedor pode repetir a chave.
const limpar = (texto) =>
  Object.values(CHAVES).filter(Boolean).reduce((t, k) => t.split(k).join('[chave]'), String(texto)).slice(0, 400)

const linhas = []
let falhas = 0
const conferir = (ok, titulo, detalhe = '') => {
  if (!ok) falhas++
  const linha = limpar(`- ${ok ? 'ok' : 'FALHOU'} · ${titulo}${detalhe ? ` · ${detalhe}` : ''}`)
  linhas.push(linha)
  console.log(linha)
}
const anotar = (texto) => {
  const linha = limpar(`- ${texto}`)
  linhas.push(linha)
  console.log(linha)
}

async function chamar(url, opcoes = {}) {
  const inicio = performance.now()
  try {
    const resposta = await fetch(url, { ...opcoes, signal: AbortSignal.timeout(60000) })
    const texto = await resposta.text()
    let json = null
    try { json = JSON.parse(texto) } catch { /* o fluxo não é JSON */ }
    return { status: resposta.status, ms: Math.round(performance.now() - inicio), json, texto }
  } catch (erro) {
    return { status: 0, ms: Math.round(performance.now() - inicio), json: null, texto: erro.name }
  }
}
const motivo = (r) => `status ${r.status}${r.status === 200 ? '' : `: ${r.json?.error?.message ?? r.json?.detail ?? r.json?.message ?? r.texto}`}`
const percentil = (valores, p) => [...valores].sort((a, b) => a - b)[Math.ceil((p / 100) * valores.length) - 1]

async function opencode() {
  if (!CHAVES.opencode) return conferir(false, 'OpenCode: OPENCODE_API_KEY ausente')
  const cabecalhos = {
    Authorization: `Bearer ${CHAVES.opencode}`,
    'Content-Type': 'application/json',
    'User-Agent': 'PlanetaAtlas/0.1',
    'x-opencode-session': `fumaca-${Date.now()}`,
  }
  const modelos = await chamar('https://opencode.ai/inference/go/v1/models', { headers: cabecalhos })
  conferir((modelos.json?.data ?? []).some((m) => m.id === MODELO_LLM), `OpenCode: /models lista ${MODELO_LLM}`, `${motivo(modelos)}, ${modelos.ms} ms`)

  const pedido = {
    model: MODELO_LLM,
    max_tokens: 1500,
    messages: [{ role: 'user', content: 'Quantos dias úteis há de 03/11/2026 a 10/11/2026? Use a ferramenta.' }],
    tools: [{
      type: 'function',
      function: {
        name: 'contar_dias',
        description: 'Conta dias úteis entre duas datas',
        parameters: { type: 'object', properties: { inicio: { type: 'string' }, fim: { type: 'string' } }, required: ['inicio', 'fim'] },
      },
    }],
  }
  const url = 'https://opencode.ai/inference/go/openai/v1/chat/completions'
  const json = await chamar(url, { method: 'POST', headers: cabecalhos, body: JSON.stringify(pedido) })
  const escolha = json.json?.choices?.[0]
  conferir(json.status === 200 && escolha?.message?.tool_calls?.[0]?.function?.name === 'contar_dias', 'OpenCode: chamada com ferramenta', `${motivo(json)}, motivo do fim ${escolha?.finish_reason}, ${json.ms} ms`)
  const uso = json.json?.usage
  conferir(Boolean(uso?.prompt_tokens && uso?.completion_tokens), 'OpenCode: uso de tokens no JSON', `entrada ${uso?.prompt_tokens}, saída ${uso?.completion_tokens}, raciocínio ${uso?.completion_tokens_details?.reasoning_tokens}`)

  const fluxo = await chamar(url, { method: 'POST', headers: cabecalhos, body: JSON.stringify({ ...pedido, stream: true, stream_options: { include_usage: true } }) })
  const pedacos = fluxo.texto.split('\n').filter((l) => l.startsWith('data: {')).map((l) => JSON.parse(l.slice(6)))
  const usoDoFluxo = pedacos.map((p) => p.usage).filter(Boolean).at(-1)
  const motivoDoFluxo = pedacos.map((p) => p.choices?.[0]?.finish_reason).filter(Boolean).at(-1)
  conferir(fluxo.status === 200 && Boolean(usoDoFluxo?.completion_tokens), 'OpenCode: uso de tokens no fluxo (stream_options.include_usage)', `${motivo(fluxo)}, ${pedacos.length} pedaços, motivo do fim ${motivoDoFluxo}, saída ${usoDoFluxo?.completion_tokens}, ${fluxo.ms} ms`)
}

async function jev() {
  if (!CHAVES.jev) return conferir(false, 'JEV: TYPESAFE_API_KEY ausente')
  const cabecalhos = { Authorization: `Bearer ${CHAVES.jev}`, 'Content-Type': 'application/json' }
  const url = 'https://api.typesafe.ai/v1/systemone'
  const lista = await chamar('https://api.typesafe.ai/v1/models', { headers: cabecalhos })
  anotar(`JEV: /v1/models ${motivo(lista)}: ${(lista.json?.models ?? []).map((m) => m.name).join(', ')} (só informação)`)

  const escolha = {
    type: 'choice',
    instructions: 'Sobre o que é a mensagem do patrocinador?',
    criteria: { prazo: 'Pergunta sobre um prazo do festival', relatorio: 'Pedido de relatório', outro: 'Qualquer outro assunto' },
  }
  const nota = { type: 'noul', instructions: 'A mensagem pergunta sobre um prazo?' }
  const estado = 'Até quando preciso informar os geradores na Arena?'
  for (const modelo of ['jev-1.14.0', 'jev-1.13.0']) {
    const r = await chamar(url, { method: 'POST', headers: cabecalhos, body: JSON.stringify({ model: modelo, state: estado, questions: { assunto: escolha, relevante: nota } }) })
    const a = r.json?.answers?.assunto
    const n = r.json?.answers?.relevante
    conferir(r.status === 200 && typeof a?.choice === 'string' && typeof a?.confidence === 'number' && Boolean(a?.probabilities), `JEV ${modelo}: choice em português`, `${motivo(r)}, choice ${a?.choice}, confidence ${a?.confidence}`)
    conferir(r.status === 200 && typeof n?.noul === 'number' && n?.confidence === undefined, `JEV ${modelo}: noul sem confidence`, `noul ${n?.noul}`)

    const tempos = []
    for (let i = 0; i < AMOSTRAS_JEV; i++) {
      const amostra = await chamar(url, { method: 'POST', headers: cabecalhos, body: JSON.stringify({ model: modelo, state: `${estado} (${i})`, questions: { relevante: nota } }) })
      if (amostra.status === 200) tempos.push(amostra.ms)
    }
    const p50 = percentil(tempos, 50)
    const p95 = percentil(tempos, 95)
    conferir(tempos.length === AMOSTRAS_JEV, `JEV ${modelo}: ${AMOSTRAS_JEV} chamadas seguidas`, `${tempos.length} com 200`)
    anotar(`JEV ${modelo}: latência p50 ${p50} ms, p95 ${p95} ms${p95 > LIMITE_JEV_MS ? ` (acima dos ${LIMITE_JEV_MS} ms: ajustar o timeout do M7)` : ''}`)
  }
}

async function gemini() {
  if (!CHAVES.gemini) return conferir(false, 'Gemini: GEMINI_API_KEY ausente')
  const cabecalhos = { 'x-goog-api-key': CHAVES.gemini, 'Content-Type': 'application/json' }
  const base = `https://generativelanguage.googleapis.com/v1beta/models/${MODELO_EMBEDDING}`
  const conteudo = (texto) => ({ parts: [{ text: `task: search result | query: ${texto}` }] })
  const norma = (v) => Math.sqrt(v.reduce((soma, x) => soma + x * x, 0))

  const formatos = {
    'output_dimensionality no nível de cima': { output_dimensionality: DIMENSOES },
    'embedContentConfig.outputDimensionality': { embedContentConfig: { outputDimensionality: DIMENSOES } },
  }
  const validos = []
  for (const [nome, extra] of Object.entries(formatos)) {
    const r = await chamar(`${base}:embedContent`, { method: 'POST', headers: cabecalhos, body: JSON.stringify({ content: conteudo('prazo dos geradores'), ...extra }) })
    const v = r.json?.embedding?.values ?? []
    if (v.length === DIMENSOES) validos.push(extra)
    anotar(`Gemini, ${nome}: ${motivo(r)}, ${v.length} dimensões, norma ${norma(v).toFixed(4)}, ${r.ms} ms`)
  }
  conferir(validos.length > 0, `Gemini: embedContent com ${DIMENSOES} dimensões`, `${validos.length} de ${Object.keys(formatos).length} formatos`)

  const extra = validos.at(-1) ?? {}
  const pedidos = ['prazo dos geradores', 'tarifa de energia', 'horário de abastecimento'].map((texto) => ({ model: `models/${MODELO_EMBEDDING}`, content: conteudo(texto), ...extra }))
  const lote = await chamar(`${base}:batchEmbedContents`, { method: 'POST', headers: cabecalhos, body: JSON.stringify({ requests: pedidos }) })
  const vetores = lote.json?.embeddings ?? []
  const normasOk = vetores.every((e) => Math.abs(norma(e.values) - 1) <= 0.01)
  conferir(vetores.length === 3 && vetores.every((e) => e.values.length === DIMENSOES), 'Gemini: batchEmbedContents com 3 itens', `${motivo(lote)}, ${vetores.length} vetores, normas perto de 1: ${normasOk}, ${lote.ms} ms`)
}

await opencode()
await jev()
await gemini()

const registro = path.join(RAIZ, 'docs/atlas/controle/progresso.md')
if (fs.existsSync(registro)) {
  const quando = new Date().toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', dateStyle: 'short', timeStyle: 'short' })
  fs.appendFileSync(registro, `\n### Fumaça dos provedores (${quando})\n\n${linhas.join('\n')}\n`)
}
console.log(falhas === 0 ? 'fumaça ok' : `fumaça com ${falhas} falha(s)`)
process.exit(falhas === 0 ? 0 : 1)
JS
