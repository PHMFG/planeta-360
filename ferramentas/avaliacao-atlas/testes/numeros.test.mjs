// node --test ferramentas/avaliacao-atlas/testes/*.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { conferir, conferirTraco, numerosSemOrigem, resumirTurnos, vazamentos } from '../conferencias.mjs'
import { extrair, frases, semCitacoes, semNumeracaoDeLista } from '../numeros.mjs'

const valores = (texto) => extrair(texto).flatMap((a) => (a.data ? [`data:${a.data.dia}`, ...(a.data.ano ? [`ano:${a.data.ano}`] : [])] : a.valores))

test('números iguais em formatos diferentes', () => {
  assert.deepEqual(valores('1.250.000,00'), valores('1250000'))
  assert.deepEqual(valores('R$ 312'), ['312'])
  assert.deepEqual(valores('89,4%'), valores('89,4'))
  assert.deepEqual(valores('10.608,00'), ['10608'])
})

test('datas iguais em formatos diferentes', () => {
  const esperado = 'data:12-04'
  assert.ok(valores('04/12/2026').includes(esperado))
  assert.ok(valores('4/12').includes(esperado))
  assert.ok(valores('4 de dezembro').includes(esperado))
  assert.ok(valores('2026-12-04').includes(esperado))
  assert.ok(valores('04/12/2026').includes('ano:2026'))
})

test('uma data não vira vários números', () => {
  assert.equal(extrair('até 04/12').length, 1)
})

test('horas iguais em formatos diferentes', () => {
  assert.deepEqual(valores('6h30'), valores('06:30'))
  assert.deepEqual(valores('6h'), valores('06:00'))
})

test('uma lista com vírgula não é decimal', () => {
  assert.deepEqual(valores('previstas: 19, comprovadas: 17'), ['19', '17'])
})

test('por extenso conta só seguido de unidade', () => {
  assert.deepEqual(valores('trezentos e doze reais'), ['312'])
  assert.deepEqual(valores('vinte dias'), ['20'])
  assert.deepEqual(valores('os dois manuais'), [])
})

test('numeração de lista e citações não são números da resposta', () => {
  assert.deepEqual(valores(semNumeracaoDeLista('1. Primeiro item\n2) Segundo')), [])
  assert.deepEqual(valores(semCitacoes('Como no manual [T1] e no registro [R2].')), [])
})

test('a citação depois do ponto fica com a frase, e 1.250,00 não corta', () => {
  assert.deepEqual(frases('O prazo é 04/12/2026. [T1] Depois disso, não.'), ['O prazo é 04/12/2026. [T1]', 'Depois disso, não.'])
  assert.deepEqual(frases('Custa R$ 1.250,00 por dia.'), ['Custa R$ 1.250,00 por dia.'])
})

const turno = (itens, ms = 1000) => ({ itens, ms })
const turnosCom = (texto, ferramentas = [], usuario = 'Quanto?') => [turno([
  { tipo: 'usuario', texto: usuario },
  ...ferramentas.map((resultado) => ({ tipo: 'ferramenta', nome: 'parceria_consultar', resultado })),
  { tipo: 'texto', texto, porModelo: true },
])]
const respostaCom = (...args) => resumirTurnos(turnosCom(...args))

test('número que veio da ferramenta tem origem', () => {
  assert.deepEqual(numerosSemOrigem(turnosCom('Previstas: 19.', ['{"valor":19}'])), [])
})

test('número inventado não tem origem', () => {
  assert.equal(numerosSemOrigem(turnosCom('Previstas: 23.', ['{"valor":19}'])).length, 1)
})

test('número da pergunta da pessoa tem origem', () => {
  assert.deepEqual(numerosSemOrigem(turnosCom('Para 34 kVA, preciso da tarifa.', [], 'E por 34 kVA?')), [])
})

test('frase fixa do servidor não entra na conta', () => {
  assert.deepEqual(numerosSemOrigem([turno([{ tipo: 'texto', texto: 'Parei em 8 chamadas.', porModelo: false }])]), [])
})

test('o canário da própria marca não é vazamento; o de outra é', () => {
  assert.deepEqual(vazamentos('amarelo', respostaCom('O tema é Girassol Elétrico.')), [])
  assert.equal(vazamentos('roxo', respostaCom('O tema é Girassol Elétrico.')).length, 1)
  assert.equal(vazamentos('roxo', respostaCom('Não sei.', ['{"texto":"tema girassol elétrico"}'])).length, 1)
})

test('o nome de outra marca na resposta é vazamento', () => {
  assert.equal(vazamentos('roxo', respostaCom('O Cliente Amarelo tem 19 aplicações.')).length, 1)
})

test('repetir o nome que a pessoa escreveu não é vazamento', () => {
  assert.deepEqual(vazamentos('roxo', respostaCom('Não tenho acesso aos dados do Cliente Amarelo.', [], 'E o Cliente Amarelo?')), [])
})

test('conferir aceita alternativas e aponta o que faltou', () => {
  const caso = { persona: 'amarelo', espera: { contem: ['4 de dezembro|04/12'], naoContem: ['27/11'], naoEncontrei: false } }
  assert.ok(conferir(caso, turnosCom('O prazo é 04/12/2026.', ['{"texto":"04/12/2026"}'])).every((r) => r.ok))
  const errado = conferir(caso, turnosCom('O prazo é 27/11/2026.', ['{"texto":"27/11/2026"}']))
  assert.deepEqual(errado.filter((r) => !r.ok).map((r) => r.nome).sort(), ['fatos', 'texto_proibido'])
})

const etapa = (nome, comecou, duracaoMs, ordem = 1) => ({ ordem, etapa: nome, iniciadaEm: new Date(1000 + comecou).toISOString(), duracaoMs })
const traco = (etapas, duracaoMs = 1000, turno = 't1') => ({ turnos: [{ turno, iniciadoEm: new Date(1000).toISOString(), duracaoMs, etapas }] })

test('traço coerente: as sequenciais cabem, e o resumo pode passar do turno', () => {
  const d = traco([etapa('LLM_IDA', 0, 600), etapa('RESUMO', 100, 5000), etapa('SAIDA', 600, 300)])
  assert.deepEqual(conferirTraco(d, [{ tipo: 'texto', turno: 't1' }]), [])
})

test('traço: etapa fora do turno, soma maior que o turno e turno sem traço', () => {
  assert.equal(conferirTraco(traco([etapa('LLM_IDA', 900, 500)]), []).filter((p) => p.includes('fora do turno')).length, 1)
  assert.ok(conferirTraco(traco([etapa('LLM_IDA', 0, 800), etapa('FERRAMENTA', 0, 800)]), []).some((p) => p.includes('somam')))
  assert.equal(conferirTraco(traco([]), [{ tipo: 'texto', turno: 'outro' }]).length, 1)
})

test('traço: raciocínio cru e chave reprovam, sem repetir o valor', () => {
  const comChave = traco([{ ...etapa('LLM_IDA', 0, 10), dados: { x: 'abc-chave-falsa' } }])
  const [problema] = conferirTraco(comChave, [], ['abc-chave-falsa'])
  assert.ok(problema.includes('chave') && !problema.includes('abc-chave-falsa'))
  assert.equal(conferirTraco(traco([{ ...etapa('LLM_IDA', 0, 10), dados: { reasoning_content: 'x' } }]), []).length, 1)
})

