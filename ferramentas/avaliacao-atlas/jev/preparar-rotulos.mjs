// Gera os conjuntos de `fontes` e `resposta` da calibração do JEV (plano, M7), a partir do gabarito e de uma rodada do avaliador.
//
//   node ferramentas/avaliacao-atlas/jev/preparar-rotulos.mjs --api http://localhost:8095 --banco portal_e2e --rodada 2026-11-01-1403-M6
//
// `fontes`: o trecho que tem o fato é relevante; os outros dos 10 primeiros da busca são distratores.
// `resposta`: frases certas das rodadas e frases erradas feitas a partir delas (número trocado, data trocada, citação de outro trecho).
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { Sessao } from '../api.mjs'
import { citacoes, extrair, frases, Origens, semCitacoes } from '../numeros.mjs'

const AQUI = path.dirname(fileURLToPath(import.meta.url))
const RAIZ = path.resolve(AQUI, '../../..')
const { values: opcoes } = parseArgs({ options: { api: { type: 'string', default: 'http://localhost:8095' }, banco: { type: 'string', default: 'portal_e2e' }, rodada: { type: 'string', multiple: true }, fatos: { type: 'string', default: '60' } } })

const semAcento = (t) => String(t ?? '').normalize('NFD').replace(/\p{M}/gu, '').replace(/\s+/g, ' ').trim().toLowerCase()
const split = (i) => (i % 10 === 2 || i % 10 === 5 || i % 10 === 8 ? 'reserva' : 'ajuste')
const gravar = (nome, linhas) => fs.writeFileSync(path.join(AQUI, 'rotulos', nome), linhas.map((l) => JSON.stringify(l)).join('\n') + '\n')

function trechos(ids) {
  const saida = execFileSync('psql', ['-d', opcoes.banco, '-At', '-c', `SELECT json_agg(json_build_object('id', id, 'texto', texto)) FROM documento_trecho WHERE id IN (${ids.map(Number).join(',')})`], { encoding: 'utf8' })
  return new Map(JSON.parse(saida).map((t) => [t.id, t.texto]))
}

async function prepararFontes() {
  const gabarito = JSON.parse(fs.readFileSync(path.join(RAIZ, 'backend/src/main/resources/dev/documentos/gabarito.json'), 'utf8'))
  const rbs = await new Sessao(opcoes.api).entrar('b2b@rbs.example', 'senha123')
  const amarelo = await new Sessao(opcoes.api).entrar('marketing@amarelo.example', 'senha123')
  const parceriaId = amarelo.parceriaId
  const fatos = gabarito.filter((f) => f.visivelPara?.includes('amarelo') && f.trecho && f.perguntas?.length).slice(0, Number(opcoes.fatos))
  const linhas = []
  for (const [i, fato] of fatos.entries()) {
    const { json: busca } = await rbs.pedir('POST', '/api/atlas/busca-teste', { parceriaId, consulta: fato.perguntas[0] })
    const lista = [...new Map([...busca.escolhidos, ...busca.vetorial, ...busca.textual].map((t) => [t.trechoId, t])).values()].slice(0, 10)
    if (!lista.length) continue
    const textos = trechos(lista.map((t) => t.trechoId))
    const alvo = semAcento(fato.trecho).slice(0, 50)
    for (const t of lista) {
      linhas.push({ fato: fato.fato, pergunta: fato.perguntas[0], trecho: textos.get(t.trechoId), rotulo: semAcento(textos.get(t.trechoId)).includes(alvo), split: split(i) })
    }
  }
  gravar('fontes.jsonl', linhas)
  console.log(`fontes: ${linhas.length} pares, ${linhas.filter((l) => l.rotulo).length} relevantes`)
}

/** Um número da frase trocado por outro: o primeiro vira esse número mais 7 (a data muda de dia). */
function comNumeroTrocado(frase) {
  return frase.replace(/\d+/, (n) => String(Number(n) + 7).padStart(n.length, '0'))
}

function prepararResposta() {
  if (!opcoes.rodada) return console.log('resposta: sem --rodada, nada a fazer')
  const casos = opcoes.rodada.flatMap((rodada) => JSON.parse(fs.readFileSync(path.join(RAIZ, 'docs/atlas/controle/avaliacoes', rodada, 'resultado.json'), 'utf8')).casos)
  const certas = []
  for (const caso of casos) {
    const tentativa = caso.tentativas.at(-1)
    if (caso.categoria !== 'documentos' && caso.categoria !== 'contas_datas' && caso.categoria !== 'misto') continue
    if (!tentativa.conferencias?.every((c) => c.ok)) continue
    // Os marcadores recomeçam a cada turno: a mensagem da pessoa zera o que valia.
    let porMarcador = new Map()
    for (const item of tentativa.itens ?? []) {
      if (item.tipo === 'usuario') porMarcador = new Map()
      if (item.tipo === 'ferramenta' && item.nome === 'buscar_documentos' && !item.erro) {
        try { for (const t of JSON.parse(item.resultado).trechos ?? []) porMarcador.set(t.marcador, t.texto) } catch { /* resultado cortado no debug */ }
      }
      if (item.tipo !== 'texto' || item.porModelo === false) continue
      for (const frase of frases(item.texto)) {
        const citadas = citacoes(frase).filter((m) => porMarcador.has(m))
        if (!citadas.length || !/\d/.test(semCitacoes(frase))) continue
        // Só entra como certa a frase em que cada número está mesmo no trecho citado.
        const origens = new Origens()
        citadas.forEach((m) => origens.adicionarFonteCitada(porMarcador.get(m)))
        if (extrair(semCitacoes(frase)).every((achado) => origens.aceita(achado))) certas.push({ frase, trechos: citadas.map((m) => porMarcador.get(m)), caso: caso.id })
      }
    }
  }
  const vistas = new Set()
  const unicas = certas.filter((c) => !vistas.has(c.frase) && vistas.add(c.frase)).slice(0, 60)
  const linhas = unicas.map((c, i) => ({ ...c, rotulo: 'apoia', origem: 'rodada', split: split(i) }))
  const erradas = []
  unicas.slice(0, 24).forEach((c, i) => {
    if (i % 2 === 0) erradas.push({ frase: comNumeroTrocado(c.frase), trechos: c.trechos, caso: c.caso, rotulo: 'errada', origem: 'numero_trocado', split: split(i) })
    else erradas.push({ frase: c.frase, trechos: unicas[(i + 7) % unicas.length].trechos, caso: c.caso, rotulo: 'errada', origem: 'citacao_de_outro', split: split(i) })
  })
  // Afirmação que o trecho não faz, com número: uma frase certa com um acréscimo que nenhum documento diz.
  unicas.slice(24, 34).forEach((c, i) => {
    const sem = semCitacoes(c.frase).trim().replace(/[.!]$/, '')
    erradas.push({ frase: `${sem}, com desconto de 10% para quem pagar até 10/12/2026 [T1].`, trechos: c.trechos, caso: c.caso, rotulo: 'errada', origem: 'afirmacao_sem_apoio', split: split(i + 24) })
  })
  gravar('resposta.jsonl', [...linhas, ...erradas])
  console.log(`resposta: ${linhas.length} certas (conferir à mão) e ${erradas.length} erradas`)
}

if (!fs.existsSync(path.join(AQUI, 'rotulos/fontes.jsonl'))) await prepararFontes()
prepararResposta()
