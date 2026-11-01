// Calibração do JEV (plano, M7). Roda as duas versões em cada conjunto, com as opções na ordem do servidor e embaralhadas, calcula precisão e
// cobertura por limiar e aplica a regra de ativação. Usa o mesmo perguntas.json que o servidor. A chave vem do ambiente ou de
// backend/config/segredos.local.properties, e nunca é impressa.
//
//   node ferramentas/avaliacao-atlas/jev/calibrar-jev.mjs                  chama o JEV e grava jev-<data>.json e jev-<data>.html
//   node ferramentas/avaliacao-atlas/jev/calibrar-jev.mjs --de <json>      só recalcula a partir de uma rodada gravada
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const AQUI = path.dirname(fileURLToPath(import.meta.url))
const RAIZ = path.resolve(AQUI, '../../..')
const PASTA_DE_RODADAS = path.join(RAIZ, 'docs/atlas/controle/avaliacoes')
const MODELOS = ['jev-1.14.0', 'jev-1.13.0', 'jev-latest']
const URL_DO_JEV = 'https://api.typesafe.ai/v1/systemone'
const AO_MESMO_TEMPO = 4
const LIMIARES = Array.from({ length: 50 }, (_, i) => Math.round((0.5 + i * 0.01) * 100) / 100)
const LIMIARES_NOUL = Array.from({ length: 99 }, (_, i) => Math.round((0.01 + i * 0.01) * 100) / 100)

const { values: opcoes } = parseArgs({ options: { de: { type: 'string' }, pontos: { type: 'string' } } })

const perguntas = JSON.parse(fs.readFileSync(path.join(RAIZ, 'backend/src/main/resources/atlas/jev/perguntas.json'), 'utf8')).pontos
const lerRotulos = (nome) => fs.readFileSync(path.join(AQUI, 'rotulos', `${nome}.jsonl`), 'utf8').split('\n').filter(Boolean).map((linha) => JSON.parse(linha))

function chave() {
  if (process.env.TYPESAFE_API_KEY) return process.env.TYPESAFE_API_KEY
  const arquivo = path.join(RAIZ, 'backend/config/segredos.local.properties')
  const linha = fs.readFileSync(arquivo, 'utf8').split('\n').find((l) => l.startsWith('TYPESAFE_API_KEY='))
  return linha ? linha.slice(linha.indexOf('=') + 1).trim() : ''
}

/** Uma passada só de substituição, como o servidor: o que entrou pelo valor não é lido de novo. */
const preencher = (molde, valores) => molde.replace(/\{([a-z_]+)\}/g, (tudo, nome) => (nome in valores ? valores[nome] : tudo))

const CAMPOS_DO_CARTAO = 'Visitantes no espaço, Cadastros com consentimento'

// O que cada ponto manda: o `state` e os valores das instruções, a partir de um item rotulado.
const MONTAGEM = {
  intencao: (item) => ({ mensagem: item.mensagem }),
  cartao: (item) => ({ campos: CAMPOS_DO_CARTAO, mensagem: item.mensagem }),
  fontes: (item) => ({ pergunta: item.pergunta, trecho: item.trecho }),
  // Como o servidor: cada trecho com o marcador. A frase vem de uma rodada, e os metadados do documento não ficam nos rótulos.
  resposta: (item) => ({ resposta: item.frase, trechos: item.trechos.map((t, i) => `[T${i + 1}]\n${t}`).join('\n\n'), frase: item.frase }),
  coerencia: (item) => ({ selos: item.selos.map((s) => `- ${s}`).join('\n'), paragrafo: item.paragrafo }),
  lacunas: (item) => ({ mensagem: item.pedido, campo: item.rotulo_campo }),
}

function embaralhar(lista, semente) {
  const copia = [...lista]
  let estado = semente
  for (let i = copia.length - 1; i > 0; i--) {
    estado = (estado * 1103515245 + 12345) % 2147483648
    const j = estado % (i + 1)
    ;[copia[i], copia[j]] = [copia[j], copia[i]]
  }
  return copia
}

function pedidoDe(ponto, item, modelo, embaralhada, indice) {
  const valores = MONTAGEM[ponto](item)
  const questions = {}
  for (const [nome, pergunta] of Object.entries(perguntas[ponto].perguntas)) {
    const copia = { ...pergunta, instructions: preencher(pergunta.instructions, valores) }
    if (embaralhada && pergunta.criteria) copia.criteria = Object.fromEntries(embaralhar(Object.entries(pergunta.criteria), indice + 7))
    questions[ponto === 'resposta' ? 'frase_1' : nome] = copia
  }
  return { model: modelo, state: preencher(perguntas[ponto].estado, valores), questions }
}

async function chamar(corpo, chaveDoJev) {
  for (let tentativa = 0; ; tentativa++) {
    let resposta
    try {
      resposta = await fetch(URL_DO_JEV, {
        method: 'POST',
        headers: { Authorization: `Bearer ${chaveDoJev}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(corpo),
        signal: AbortSignal.timeout(15_000),
      })
    } catch (erro) {
      if (tentativa < 4) {
        await new Promise((fim) => setTimeout(fim, 1000 * (tentativa + 1)))
        continue
      }
      return { erro: `sem resposta: ${erro.name}` }
    }
    if ((resposta.status === 429 || resposta.status === 529) && tentativa < 6) {
      await new Promise((fim) => setTimeout(fim, 1500 * 2 ** tentativa))
      continue
    }
    if (!resposta.ok) return { erro: `status ${resposta.status}` }
    return { respostas: (await resposta.json()).answers }
  }
}

/** Roda um conjunto com poucas requisições ao mesmo tempo. */
async function rodarConjunto(ponto, itens, modelo, embaralhada, chaveDoJev) {
  const resultados = new Array(itens.length)
  let proximo = 0
  await Promise.all(Array.from({ length: AO_MESMO_TEMPO }, async () => {
    while (proximo < itens.length) {
      const i = proximo++
      const { respostas, erro } = await chamar(pedidoDe(ponto, itens[i], modelo, embaralhada, i), chaveDoJev)
      resultados[i] = { ...itens[i], respostas, erro }
    }
  }))
  return resultados
}

// ---------------------------------------------------------------------
// As medidas
// ---------------------------------------------------------------------

const respostaDe = (linha, ponto) => {
  const r = linha.respostas ?? {}
  if (ponto === 'resposta') return r.frase_1
  if (ponto === 'fontes') return r.relevante
  if (ponto === 'lacunas') return r.vale_perguntar
  return r[ponto]
}
const doSplit = (linhas, split) => linhas.filter((l) => l.split === split && !l.erro)
const fracao = (parte, todo) => (todo ? parte / todo : null)

/** A intenção certa e, no modelo em branco, o tipo também: a ação do servidor depende dos dois. */
function acertou(ponto, linha) {
  const r = respostaDe(linha, ponto)
  if (r?.choice !== linha.rotulo) return false
  if (ponto === 'intencao' && linha.rotulo === 'modelo_em_branco') {
    const esperado = { BRIEFING_CONTEUDO: 'briefing_conteudo', PERGUNTAS_PESQUISA: 'perguntas_pesquisa', PLANO_OPERACIONAL: 'plano_operacional_ativacao' }[linha.tipo_modelo]
    return linha.respostas?.tipo_modelo?.choice === esperado
  }
  return true
}

// As opções que mudam o que a pessoa recebe: é sobre elas que a precisão interessa. A resposta fixa do cartão é uma ação, e "outro assunto"
// segue o caminho de hoje; a recusa fixa é uma ação, e "dúvida de documento" só escolhe ferramentas.
const OPCOES_QUE_AGEM = {
  intencao: ['fora_de_escopo', 'outro_relatorio', 'modelo_em_branco', 'montar_relatorio'],
  cartao: ['responde_ao_cartao'],
}

function medirEscolha(ponto, linhas, limiar) {
  const agem = OPCOES_QUE_AGEM[ponto]
  const conta = (l) => !agem || agem.includes(respostaDe(l, ponto)?.choice)
  const aceitas = linhas.filter((l) => conta(l) && (respostaDe(l, ponto)?.confidence ?? 0) >= limiar)
  const devidas = agem ? linhas.filter((l) => agem.includes(l.rotulo)) : linhas
  const achadas = agem ? aceitas.filter((l) => acertou(ponto, l)).length : aceitas.length
  return { limiar, precisao: fracao(aceitas.filter((l) => acertou(ponto, l)).length, aceitas.length), cobertura: fracao(achadas, devidas.length), aceitas: aceitas.length }
}

/**
 * A regra do plano: limiar com precisão ≥ 95% e cobertura ≥ 50% no ajuste, confirmado com precisão ≥ 93% no reserva. Entre os que passam,
 * fica o de maior precisão e, no empate, o mais alto que ainda cobre 75%: abaixo do limiar a mensagem só vai ao modelo, que acerta, e uma
 * recusa errada custa bem mais que uma ida a mais. O limiar sai só do ajuste.
 */
function decidirEscolha(ponto, linhas) {
  const ajuste = doSplit(linhas, 'ajuste')
  const reserva = doSplit(linhas, 'reserva')
  const curva = LIMIARES.map((limiar) => medirEscolha(ponto, ajuste, limiar))
  const passam = curva.filter((m) => m.precisao !== null && m.precisao >= 0.95 && m.cobertura >= 0.5)
  const melhores = passam.filter((m) => m.precisao === Math.max(...passam.map((p) => p.precisao)))
  const bom = melhores.filter((m) => m.cobertura >= 0.75).at(-1) ?? melhores[0]
  if (!bom) return { ponto, liga: false, motivo: 'nenhum limiar com precisão de 95% e cobertura de 50% no ajuste', melhor: curva.filter((m) => m.precisao !== null).sort((a, b) => b.precisao - a.precisao || b.cobertura - a.cobertura)[0], curva }
  const noReserva = medirEscolha(ponto, reserva, bom.limiar)
  const liga = noReserva.precisao !== null && noReserva.precisao >= 0.93
  return { ponto, liga, limiar: bom.limiar, ajuste: bom, reserva: noReserva, motivo: liga ? 'passou' : 'não confirmou no reserva', curva }
}

/** `fontes`: mantém ≥ 95% dos relevantes e tira ≥ 30% dos irrelevantes. Fica o maior limiar que ainda mantém. */
function decidirFontes(linhas) {
  const medir = (conjunto, limiar) => {
    const relevantes = conjunto.filter((l) => l.rotulo === true)
    const outros = conjunto.filter((l) => l.rotulo === false)
    return {
      limiar,
      mantem: fracao(relevantes.filter((l) => (respostaDe(l, 'fontes')?.noul ?? 1) >= limiar).length, relevantes.length),
      tira: fracao(outros.filter((l) => (respostaDe(l, 'fontes')?.noul ?? 1) < limiar).length, outros.length),
    }
  }
  const ajuste = doSplit(linhas, 'ajuste')
  const curva = LIMIARES_NOUL.map((limiar) => medir(ajuste, limiar))
  const bom = curva.filter((m) => m.mantem >= 0.95 && m.tira >= 0.3).at(-1)
  if (!bom) return { ponto: 'fontes', liga: false, motivo: 'nenhum limiar mantém 95% e tira 30% no ajuste', melhor: curva.filter((m) => m.mantem >= 0.95).at(-1), curva }
  const noReserva = medir(doSplit(linhas, 'reserva'), bom.limiar)
  const liga = noReserva.mantem >= 0.95 && noReserva.tira >= 0.3
  return { ponto: 'fontes', liga, limiar: bom.limiar, ajuste: bom, reserva: noReserva, motivo: liga ? 'passou' : 'não confirmou no reserva', curva }
}

/** `resposta`: pega ≥ 50% das erradas recusando ≤ 5% das certas. Recusa é `contradiz` ou `nao_diz` com confiança no limiar. */
function decidirResposta(linhas) {
  const recusa = (l, limiar) => {
    const r = respostaDe(l, 'resposta')
    return (r?.choice === 'contradiz' || r?.choice === 'nao_diz') && (r?.confidence ?? 0) >= limiar
  }
  const medir = (conjunto, limiar) => {
    const certas = conjunto.filter((l) => l.rotulo === 'apoia')
    const erradas = conjunto.filter((l) => l.rotulo === 'errada')
    return { limiar, pega: fracao(erradas.filter((l) => recusa(l, limiar)).length, erradas.length), recusaCertas: fracao(certas.filter((l) => recusa(l, limiar)).length, certas.length) }
  }
  const ajuste = doSplit(linhas, 'ajuste')
  const curva = LIMIARES.map((limiar) => medir(ajuste, limiar))
  const bom = curva.find((m) => m.pega >= 0.5 && m.recusaCertas <= 0.05)
  if (!bom) return { ponto: 'resposta', liga: false, motivo: 'nenhum limiar pega 50% recusando até 5% no ajuste', melhor: curva.filter((m) => m.recusaCertas <= 0.05)[0] ?? curva.at(-1), curva }
  const noReserva = medir(doSplit(linhas, 'reserva'), bom.limiar)
  const liga = noReserva.pega >= 0.5 && noReserva.recusaCertas <= 0.05
  return { ponto: 'resposta', liga, limiar: bom.limiar, ajuste: bom, reserva: noReserva, motivo: liga ? 'passou' : 'não confirmou no reserva', curva }
}

/** `lacunas`: a ação é tirar a lacuna do cartão quando o `noul` fica abaixo do limiar. Precisão é o que saiu e devia sair; cobertura, o que devia sair e saiu. */
function decidirLacunas(linhas) {
  const medir = (conjunto, limiar) => {
    const tiradas = conjunto.filter((l) => (respostaDe(l, 'lacunas')?.noul ?? 1) < limiar)
    const dispensaveis = conjunto.filter((l) => l.rotulo === false)
    return { limiar, precisao: fracao(tiradas.filter((l) => l.rotulo === false).length, tiradas.length), cobertura: fracao(tiradas.filter((l) => l.rotulo === false).length, dispensaveis.length) }
  }
  const ajuste = doSplit(linhas, 'ajuste')
  const curva = LIMIARES_NOUL.map((limiar) => medir(ajuste, limiar))
  const bom = curva.filter((m) => m.precisao !== null && m.precisao >= 0.95 && m.cobertura >= 0.5).at(-1)
  if (!bom) return { ponto: 'lacunas', liga: false, motivo: 'nenhum limiar com precisão de 95% e cobertura de 50% no ajuste', melhor: curva.filter((m) => m.precisao !== null).sort((a, b) => b.precisao - a.precisao || b.cobertura - a.cobertura)[0], curva }
  const noReserva = medir(doSplit(linhas, 'reserva'), bom.limiar)
  const liga = noReserva.precisao !== null && noReserva.precisao >= 0.93
  return { ponto: 'lacunas', liga, limiar: bom.limiar, ajuste: bom, reserva: noReserva, motivo: liga ? 'passou' : 'não confirmou no reserva', curva }
}

const DECISAO = {
  intencao: (l) => decidirEscolha('intencao', l),
  cartao: (l) => decidirEscolha('cartao', l),
  coerencia: (l) => decidirEscolha('coerencia', l),
  fontes: decidirFontes,
  resposta: decidirResposta,
  lacunas: decidirLacunas,
}
const COM_OPCOES = ['intencao', 'cartao', 'resposta', 'coerencia']

/** Acerto simples, para comparar a ordem das opções. Em `resposta`, errada vale como recusa. */
function acerto(ponto, linhas) {
  const validas = linhas.filter((l) => !l.erro)
  const certo = (l) => {
    if (ponto !== 'resposta') return acertou(ponto, l)
    const escolha = respostaDe(l, 'resposta')?.choice
    return l.rotulo === 'apoia' ? escolha === 'apoia' : escolha !== 'apoia'
  }
  return fracao(validas.filter(certo).length, validas.length)
}

function porClasse(linhas, limiar) {
  const classes = {}
  for (const l of linhas.filter((x) => !x.erro && x.rotulo)) {
    const r = respostaDe(l, 'intencao')
    if ((r?.confidence ?? 0) < limiar) continue
    const c = (classes[r.choice] ??= { aceitas: 0, certas: 0, erros: [] })
    c.aceitas += 1
    if (acertou('intencao', l)) c.certas += 1
    else c.erros.push(`${l.mensagem} (era ${l.rotulo})`)
  }
  return classes
}

// ---------------------------------------------------------------------
// A rodada
// ---------------------------------------------------------------------

async function rodar() {
  const chaveDoJev = chave()
  if (!chaveDoJev) throw new Error('TYPESAFE_API_KEY ausente')
  const conjuntos = Object.fromEntries(Object.keys(MONTAGEM).map((p) => [p, lerRotulos(p)]))
  const pontos = opcoes.pontos ? opcoes.pontos.split(',') : Object.keys(MONTAGEM)
  const saida = { data: new Date().toISOString(), modelos: {} }
  for (const modelo of MODELOS) {
    saida.modelos[modelo] = { ordem: {}, embaralhada: {} }
    for (const ponto of pontos) {
      const inicio = Date.now()
      saida.modelos[modelo].ordem[ponto] = await rodarConjunto(ponto, conjuntos[ponto], modelo, false, chaveDoJev)
      if (COM_OPCOES.includes(ponto)) saida.modelos[modelo].embaralhada[ponto] = await rodarConjunto(ponto, conjuntos[ponto], modelo, true, chaveDoJev)
      const erros = saida.modelos[modelo].ordem[ponto].filter((l) => l.erro).length
      console.log(`${modelo} ${ponto}: ${conjuntos[ponto].length} itens em ${Math.round((Date.now() - inicio) / 1000)} s, ${erros} erros`)
    }
  }
  return saida
}

const pct = (v) => (v === null || v === undefined ? '—' : `${(v * 100).toFixed(1)}%`)

function analisar(saida) {
  const relatorio = {}
  for (const [modelo, dados] of Object.entries(saida.modelos)) {
    const decisoes = {}
    for (const [ponto, linhas] of Object.entries(dados.ordem)) {
      const d = DECISAO[ponto](linhas)
      d.acertoNaOrdem = COM_OPCOES.includes(ponto) ? acerto(ponto, linhas) : null
      d.acertoEmbaralhada = dados.embaralhada[ponto] ? acerto(ponto, dados.embaralhada[ponto]) : null
      d.diferencaDaOrdem = d.acertoEmbaralhada === null ? null : Math.abs(d.acertoNaOrdem - d.acertoEmbaralhada)
      d.erros = linhas.filter((l) => l.erro).length
      if (ponto === 'intencao') d.porClasse = porClasse(doSplit(linhas, 'ajuste'), d.limiar ?? d.melhor?.limiar ?? 0.9)
      decisoes[ponto] = d
    }
    const injecao = (dados.ordem.intencao ?? []).filter((l) => l.injecao && !l.erro).map((l) => l.respostas?.sinal_injecao?.noul)
    const normais = (dados.ordem.intencao ?? []).filter((l) => !l.injecao && !l.erro).map((l) => l.respostas?.sinal_injecao?.noul)
    const media = (lista) => (lista.length ? lista.reduce((a, b) => a + (b ?? 0), 0) / lista.length : null)
    relatorio[modelo] = { decisoes, sinalDeInjecao: { hostis: media(injecao), normais: media(normais) } }
  }
  // Fica a versão fixada que liga intenção e cartão e pega mais frases erradas. O `jev-latest` é apelido: serve só de comparação, porque o
  // servidor fixa a versão.
  const fixadas = Object.entries(relatorio).filter(([modelo]) => modelo !== 'jev-latest')
  const candidatas = fixadas.filter(([, r]) => r.decisoes.intencao?.liga && r.decisoes.cartao?.liga)
  const pega = (r) => r.decisoes.resposta?.ajuste?.pega ?? r.decisoes.resposta?.melhor?.pega ?? 0
  const melhor = (candidatas.length ? candidatas : fixadas).sort((a, b) => pega(b[1]) - pega(a[1]))[0][0]
  return { melhor, relatorio }
}

function linhaDoPonto(d) {
  const ajuste = d.ajuste ?? d.melhor ?? {}
  const medidas = Object.entries(ajuste).filter(([k]) => k !== 'limiar' && k !== 'aceitas').map(([k, v]) => `${k} ${pct(v)}`).join(', ')
  const reserva = d.reserva ? Object.entries(d.reserva).filter(([k]) => k !== 'limiar' && k !== 'aceitas').map(([k, v]) => `${k} ${pct(v)}`).join(', ') : '—'
  return { situacao: d.liga ? 'ATIVO' : 'SOMBRA', limiar: d.limiar ?? ajuste.limiar ?? '—', medidas, reserva, motivo: d.motivo }
}

const escapar = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])

function montarHtml(data, analise) {
  const secoes = Object.entries(analise.relatorio).map(([modelo, r]) => {
    const linhas = Object.entries(r.decisoes).map(([ponto, d]) => {
      const l = linhaDoPonto(d)
      return `<tr><td>${ponto}</td><td><b>${l.situacao}</b></td><td>${l.limiar}</td><td>${escapar(l.medidas)}</td><td>${escapar(l.reserva)}</td><td>${pct(d.acertoNaOrdem)} / ${pct(d.acertoEmbaralhada)}${d.diferencaDaOrdem > 0.05 ? ' <b>(mais de 5 p.p.)</b>' : ''}</td><td>${escapar(l.motivo)}</td></tr>`
    }).join('')
    const classes = Object.entries(r.decisoes.intencao?.porClasse ?? {}).map(([c, v]) => `<tr><td>${c}</td><td>${v.certas} de ${v.aceitas}</td><td>${escapar(v.erros.join(' · '))}</td></tr>`).join('')
    return `<h2>${modelo}${modelo === analise.melhor ? ' (escolhido)' : ''}</h2>
<table><thead><tr><th>Ponto</th><th>Modo</th><th>Limiar</th><th>Ajuste</th><th>Reserva</th><th>Acerto na ordem / embaralhada</th><th>Motivo</th></tr></thead><tbody>${linhas}</tbody></table>
<h3>Intenção por classe, no ajuste</h3><table><thead><tr><th>Classe escolhida</th><th>Certas</th><th>Erros</th></tr></thead><tbody>${classes}</tbody></table>
<p>Sinal de injeção, média do noul: mensagens hostis ${pct(r.sinalDeInjecao.hostis)}; as outras ${pct(r.sinalDeInjecao.normais)}. Só vai para o traço.</p>`
  }).join('\n')
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Calibração do JEV</title><style>body{font:14px/1.5 system-ui;margin:24px;color:#222}table{border-collapse:collapse;margin:8px 0 20px}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}th{background:#f2f2f2}</style></head><body><h1>Calibração do JEV</h1><p>${escapar(data)}. Regra da seção M7 do plano, com 70% de ajuste e 30% de reserva.</p>${secoes}</body></html>`
}

const saida = opcoes.de ? JSON.parse(fs.readFileSync(opcoes.de, 'utf8')) : await rodar()
const analise = analisar(saida)
const carimbo = saida.data.slice(0, 16).replace(/[T:]/g, '-')
if (!opcoes.de) fs.writeFileSync(path.join(PASTA_DE_RODADAS, `jev-${carimbo}.json`), JSON.stringify(saida))
fs.writeFileSync(path.join(PASTA_DE_RODADAS, `jev-${carimbo}.html`), montarHtml(saida.data, analise))

for (const [modelo, r] of Object.entries(analise.relatorio)) {
  console.log(`\n${modelo}${modelo === analise.melhor ? ' (escolhido)' : ''}`)
  for (const [ponto, d] of Object.entries(r.decisoes)) {
    const l = linhaDoPonto(d)
    console.log(`  ${ponto.padEnd(10)} ${l.situacao.padEnd(7)} limiar ${l.limiar} | ajuste: ${l.medidas} | reserva: ${l.reserva} | ordem ${pct(d.acertoNaOrdem)} / embaralhada ${pct(d.acertoEmbaralhada)} | erros ${d.erros} | ${l.motivo}`)
  }
}
console.log(`\nRelatório: docs/atlas/controle/avaliacoes/jev-${carimbo}.html`)
