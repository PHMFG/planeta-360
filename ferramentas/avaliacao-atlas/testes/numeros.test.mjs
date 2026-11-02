// node --test ferramentas/avaliacao-atlas/testes/*.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { casa, conferir, conferirTraco, numerosDoRelatorioSemFonte, numerosSemOrigem, resumirTurnos, vazamentos } from '../conferencias.mjs'
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

test('data logo depois de uma quebra de linha no trecho tem origem', () => {
  const resultado = JSON.stringify({ tipo: 'conteudo_de_documento', trechos: [{ marcador: 'T1', documento: 'Manual', versao: 2, pagina: 3, secao: 'Prazos', texto: 'Informar os geradores\n04/12/2026\nMarca' }] })
  const turnos = [turno([{ tipo: 'usuario', texto: 'Quando?' }, { tipo: 'ferramenta', nome: 'buscar_documentos', resultado }, { tipo: 'texto', texto: 'Até 04/12/2026 [T1].', porModelo: true }])]
  assert.deepEqual(numerosSemOrigem(turnos), [])
  const semCitar = [turno([{ tipo: 'usuario', texto: 'Quando?' }, { tipo: 'ferramenta', nome: 'buscar_documentos', resultado }, { tipo: 'texto', texto: 'Até 05/12/2026 [T1].', porModelo: true }])]
  assert.equal(numerosSemOrigem(semCitar).length, 1)
})

const comFerramenta = (nome, resultado, texto) => [turno([{ tipo: 'usuario', texto: 'Quanto?' }, { tipo: 'ferramenta', nome, resultado: JSON.stringify(resultado) }, { tipo: 'texto', texto, porModelo: true }])]

test('o nome e o script de uma conta não são origem de número', () => {
  const conta = { marcador: 'C1', ref: 'calc:energia_20kva', valor: 90, valorFormatado: '90', script: 'a * b * 7', variaveis: [{ nome: 'a', origem: 'T1', valor: '45' }, { nome: 'b', origem: 'usuario', valor: '2' }] }
  assert.deepEqual(numerosSemOrigem(comFerramenta('calcular', conta, 'Dá R$ 90 [C1], com 45 e 2.')), [])
  assert.equal(numerosSemOrigem(comFerramenta('calcular', conta, 'São 20 kVA [C1].')).length, 1)
  assert.equal(numerosSemOrigem(comFerramenta('calcular', conta, 'Multiplique por 7 [C1].')).length, 1)
})

test('data em pedaços só vale para trecho de documento', () => {
  const registro = { tipo: 'registros_do_portal', registros: [{ marcador: 'R1', titulo: 'Entrega', texto: 'Até 30 segundos. Atualizada em 27/10/2026.' }] }
  assert.equal(numerosSemOrigem(comFerramenta('listar_entregas', registro, 'Vale até 30/10/2026 [R1].')).length, 1)
  assert.deepEqual(numerosSemOrigem(comFerramenta('listar_entregas', registro, 'Atualizada em 27/10/2026 [R1].')), [])
  const trecho = { trechos: [{ marcador: 'T1', documento: 'Manual', texto: 'Na sexta, 29, e no sábado, 30 de janeiro de 2027.' }] }
  assert.deepEqual(numerosSemOrigem(comFerramenta('buscar_documentos', trecho, 'Vale em 29/01/2027 [T1].')), [])
})

test('o número solto do trecho só é dia depois de dia da semana ou de "dia"', () => {
  const trecho = (texto) => ({ trechos: [{ marcador: 'T1', documento: 'Manual', texto }] })
  assert.equal(numerosSemOrigem(comFerramenta('buscar_documentos', trecho('Até 04/12/2026 no Portão 5.'), 'Até 05/12/2026 [T1].')).length, 1)
  assert.equal(numerosSemOrigem(comFerramenta('buscar_documentos', trecho('Sexta, 4, e sábado, 5 de dezembro. Portão 6.'), 'Em 06/12 [T1].')).length, 1)
  assert.deepEqual(numerosSemOrigem(comFerramenta('buscar_documentos', trecho('Sexta, 4, e sábado, 5 de dezembro. Portão 6.'), 'Em 04/12 [T1].')), [])
})

test('os pedaços de uma data vêm de um trecho só', () => {
  const dois = { trechos: [{ marcador: 'T1', documento: 'Manual', texto: 'Na sexta-feira, 29, e no sábado, 30 de janeiro de 2027.' }, { marcador: 'T2', documento: 'Manual', texto: 'Laudo até 29/12/2026.' }] }
  assert.equal(numerosSemOrigem(comFerramenta('buscar_documentos', dois, 'O laudo vai até 30/12/2026 [T1, T2].')).length, 1)
  assert.deepEqual(numerosSemOrigem(comFerramenta('buscar_documentos', dois, 'É em 29/01/2027 [T1, T2].')), [])
  const plural = { trechos: [{ marcador: 'T1', documento: 'Manual', texto: 'Até 04/12/2026: 10 dias, 5 cópias.' }] }
  assert.equal(numerosSemOrigem(comFerramenta('buscar_documentos', plural, 'Até 05/12/2026 [T1].')).length, 1)
})

test('o dia de uma data inteira vale para registro e conta', () => {
  const registro = { tipo: 'registros_do_portal', registros: [{ marcador: 'R1', titulo: 'Entrega', texto: 'Prazo: 04/12/2026.' }] }
  assert.deepEqual(numerosSemOrigem(comFerramenta('listar_entregas', registro, 'O prazo é o dia 4 [R1].')), [])
  assert.equal(numerosSemOrigem(comFerramenta('listar_entregas', registro, 'O prazo é o dia 5 [R1].')).length, 1)
})

test('todo número do relatório está em Fontes e cálculos', () => {
  const relatorio = (texto, valor) => ({ secoes: [{ blocos: [
    { id: 'p', tipo: 'paragrafo', redigidoPor: 'ATLAS', texto },
    { id: 'k', tipo: 'kpi', item: { rotulo: 'Visitantes', valor } },
    { id: 'f', tipo: 'fontes', itens: [{ ref: 'dado:a', rotulo: 'Visitantes', valor: '1.200', origem: 'Marca' }] },
  ] }] })
  assert.deepEqual(numerosDoRelatorioSemFonte(relatorio('Passaram pelo espaço: 1.200.', '1.200')), [])
  assert.equal(numerosDoRelatorioSemFonte(relatorio('Passaram pelo espaço: 1.300.', '1.200')).length, 1)
  assert.equal(numerosDoRelatorioSemFonte(relatorio('Sem número.', '999')).length, 1)
  assert.deepEqual(numerosDoRelatorioSemFonte(relatorio('Sem número.', null)), [])
})

test('o que a pessoa escreveu vale nos turnos seguintes da conversa', () => {
  const turnos = [
    turno([{ tipo: 'usuario', texto: 'Tenho 34 kVA.' }, { tipo: 'texto', texto: 'Certo.', porModelo: true }]),
    turno([{ tipo: 'usuario', texto: 'E então?' }, { tipo: 'texto', texto: 'Para 34 kVA, veja o manual.', porModelo: true }]),
  ]
  assert.deepEqual(numerosSemOrigem(turnos), [])
})

test('o numeral por extenso é a palavra: o 17 do marcador não conta como ele', () => {
  assert.equal(casa('aplicações comprovadas: 17.', 'dezessete'), false)
  assert.equal(casa('foram dezessete aplicações', 'dezessete'), true)
})

const comTrecho = (texto, trecho, usuario = 'Quanto?') => [turno([
  { tipo: 'usuario', texto: usuario },
  { tipo: 'ferramenta', nome: 'buscar_documentos', resultado: JSON.stringify({ trechos: [{ marcador: 'T1', documento: 'Manual', versao: 2, pagina: 5, secao: 'S', texto: trecho }] }) },
  { tipo: 'texto', texto, porModelo: true },
])]

test('numeral por extenso sem origem, com ou sem unidade', () => {
  for (const texto of ['O prazo é vinte e cinco de dezembro.', 'A tarifa é de trezentos e doze por kVA.', 'São doze convites.', 'O limite é vinte %.', 'A multa é de dois milhoes de reais.']) {
    assert.equal(numerosSemOrigem(turnosCom(texto)).length, 1, texto)
  }
  assert.equal(numerosSemOrigem(comTrecho('O prazo é dez de dezembro [T1].', 'até 04/12/2026')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('O prazo é quatro de dezembro [T1].', 'até 04/12/2026')), [])
})

test('"um", "uma" e "os dois manuais" não são números; "por cento" depois de algarismo não vira 100', () => {
  assert.deepEqual(numerosSemOrigem(turnosCom('Os dois manuais tratam de uma regra.')), [])
  assert.deepEqual(numerosSemOrigem(turnosCom('São 89,4 por cento.', ['{"valor":"89,4"}'])), [])
})

test('"uma" não é a origem de 1, e numerais só somam em ordem decrescente', () => {
  assert.equal(numerosSemOrigem(turnosCom('A multa é de R$ 1,00.', [], 'Qual é a multa por uma credencial?')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('São 7 lotes [T1].', 'Os lotes três e quatro abrem antes')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('O lote 3 [T1].', 'Os lotes três e quatro abrem antes')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('São 312 [T1].', 'São trezentos e doze')), [])
})

test('a lista só é lista em sequência', () => {
  for (const texto of ['312. É o valor da tarifa em reais.', '99) kVA é o limite.', '**500.** reais é a multa.', '2. Sem o primeiro item.']) {
    assert.equal(numerosSemOrigem(turnosCom(texto)).length, 1, texto)
  }
  assert.deepEqual(numerosSemOrigem(turnosCom('1. Primeiro.\n2. Segundo.\n1. Outra lista.\n### 2. Com título\n3 - Com hífen')), [])
})

test('o ano livre é só ano: nunca valor', () => {
  assert.deepEqual(numerosSemOrigem(turnosCom('A edição 2027 é a próxima.')), [])
  for (const texto of ['A multa é de R$ 2.027,00.', 'A multa é de R$ 2027.', 'São 2026 credenciais.']) {
    assert.equal(numerosSemOrigem(turnosCom(texto)).length, 1, texto)
  }
})

test('data sem ano na mensagem não libera outro ano contra a fonte citada', () => {
  assert.equal(numerosSemOrigem(comTrecho('O prazo é 04/12/2099 [T1].', 'até 04/12/2026', 'É em 04/12?')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('O prazo é 04/12/2026 [T1].', 'até 04/12/2026', 'É em 04/12?')), [])
  assert.equal(numerosSemOrigem(turnosCom('É em 04/12/2099.', [], 'É em 04/12?')).length, 1)
})

test('o negrito fecha a frase: a citação da outra frase não apoia o número', () => {
  assert.equal(numerosSemOrigem(comTrecho('**A multa é 09/09/2026.** A tarifa é R$ 312,00 [T1].', 'R$ 312,00 e 09/09/2026')).length, 1)
})

test('dado da parceria num turno seguinte: o servidor já resolveu o marcador', () => {
  const consulta = { tipo: 'ferramenta', nome: 'parceria_consultar', resultado: '{"dados":[{"valor":"19"}]}' }
  const turnos = [turno([{ tipo: 'usuario', texto: 'Quantas?' }, consulta, { tipo: 'texto', texto: 'Previstas: 19.', porModelo: true }]),
    turno([{ tipo: 'usuario', texto: 'E faltam?' }, { tipo: 'texto', texto: 'Previstas: 19.', porModelo: true }])]
  assert.deepEqual(numerosSemOrigem(turnos), [])
})

test('grupos separados por espaço, zero e o ano simples', () => {
  assert.equal(numerosSemOrigem(comTrecho('O custo total é R$ 2 220,00 [T1].', 'R$ 220,00 e 2 geradores')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('O custo total é R$ 2\u2009220,00 [T1].', 'R$ 220,00 e 2 geradores')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('São R$ 2 220,00 [T1].', 'Total de R$ 2 220,00')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('O valor é 220 [T1].', 'Colunas 2 220')), [])
  assert.equal(numerosSemOrigem(turnosCom('São zero geradores.')).length, 1)
  for (const texto of ['Vale para o Planeta Atlântida 2027 e desde 2026.', 'Para 2027, o prazo muda.', 'Nas edições 2026 e 2027.', 'Consulte o Manual de Operações 2027.']) {
    assert.deepEqual(numerosSemOrigem(turnosCom(texto)), [], texto)
  }
})

test('o ano da data ou do título não vira valor', () => {
  assert.equal(numerosSemOrigem(comTrecho('Custa R$ 2.026,00 [T1].', 'até 04/12/2026')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('São 2026 credenciais [T1].', 'até 04/12/2026')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('Custa R$ 2.027,00 [T1].', 'Manual de Operações 2027')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('São 1250 lugares [T1].', 'Capacidade de 1250 lugares')), [])
})

test('data em pedaços e "dia 4" só com a fonte citada', () => {
  const festival = 'sexta-feira, 29, e no sábado, 30 de janeiro de 2027'
  assert.deepEqual(numerosSemOrigem(comTrecho('O festival é nos dias 29 e 30 de janeiro de 2027 [T1].', festival)), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('29/01/2027 e 30/01/2027 [T1].', festival)), [])
  assert.equal(numerosSemOrigem(comTrecho('É em 28/01/2027 [T1].', festival)).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('É no dia 4 [T1].', 'até 04/12/2026')), [])
  assert.equal(numerosSemOrigem(comTrecho('São 4 geradores [T1].', 'até 04/12/2026')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('Custa R$ 4 [T1].', 'até 04/12/2026')).length, 1)
  assert.equal(numerosSemOrigem(comTrecho('É no dia 9 [T1].', 'até 04/12/2026')).length, 1)
})

test('horas, formatos e a data inteira que a pessoa escreveu', () => {
  assert.deepEqual(numerosSemOrigem(comTrecho('Leva 48h [T1].', 'Leva 48 horas')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('Até 18h [T1].', 'Até 18h00min')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('Até 05h [T1].', 'Até 05:00h')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('Das 5 às 8h [T1].', 'das 05h às 08h')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('São 40 cortesias [T1].', 'Master 40/20')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('É em 15-01-2027 [T1].', 'até 15/01/2027')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('Não é 04/12/2027: é 04/12/2026 [T1].', 'até 04/12/2026', 'É 04/12/2027?')), [])
  assert.deepEqual(numerosSemOrigem(comTrecho('São 36 m² [T1].', 'São 36 m²')), [])
  assert.equal(numerosSemOrigem(comTrecho('São 3\u034F12 reais [T1].', '3 ou 12')).length, 1)
})

test('"2 milhões" vale o inteiro, "por doze horas" conta e "por cento" não', () => {
  assert.deepEqual(numerosSemOrigem(comTrecho('São 2 milhões de reais [T1].', 'R$ 2.000.000,00')), [])
  assert.equal(numerosSemOrigem(turnosCom('Fica aberto por doze horas.')).length, 1)
  assert.deepEqual(numerosSemOrigem(comTrecho('São 89,4 por cento [T1].', '89,4%')), [])
})

test('abreviatura não corta a frase; o ponto de "ver" corta', () => {
  assert.deepEqual(numerosSemOrigem(comTrecho('Está na p. 5 do manual [T1].', 'S')), [])
  assert.deepEqual(frases('Veja a p. 6 [T1]. E depois.'), ['Veja a p. 6 [T1].', 'E depois.'])
  assert.deepEqual(frases('Como dá para ver. A tarifa [T1].'), ['Como dá para ver.', 'A tarifa [T1].'])
  assert.deepEqual(frases('**Primeira.** Segunda. [T1] [T2]\nTerceira'), ['**Primeira.**', 'Segunda. [T1] [T2]', 'Terceira'])
  assert.deepEqual(frases('O valor é {{cons:a.b}}. Fim.'), ['O valor é {{cons:a.b}}.', 'Fim.'])
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

