// O destaque do visualizador e as citações do texto, sem navegador: função pura em .ts, pelo próprio Playwright.
import { expect, test } from '@playwright/test'
import { escaparHtml, faixasDoTrecho, htmlDoItem, partesDoTexto } from '../../src/atlas/destaque.ts'
import { partesComCitacoes } from '../../src/atlas/citacoes.ts'
import { origemParaLer } from '../../src/util/formatar.ts'

test('a origem de uma conta do relatório sai sem a notação interna', () => {
  expect(origemParaLer('pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200')).toBe('cadastros ÷ visitantes (em %) com cadastros = 1.072 e visitantes = 1.200')
  expect(origemParaLer('pct(distribuidos, disponiveis): Faltam dados para o cálculo.')).toBe('distribuidos ÷ disponiveis (em %): Faltam dados para o cálculo.')
  expect(origemParaLer('Consolidação do portal: plano comercial')).toBe('Consolidação do portal: plano comercial')
})

/** O que ficou marcado, pedaço a pedaço. */
function marcado(itens: string[], trecho: string) {
  const faixas = faixasDoTrecho(itens, trecho)
  return itens.flatMap((item, indice) => (faixas.get(indice) ?? []).map(([inicio, fim]) => item.slice(inicio, fim)))
}

test.describe('escape do texto do PDF', () => {
  test('um texto com <img onerror> sai escapado, com ou sem marca', () => {
    const ataque = '<img src=x onerror=alert(1)>'
    expect(htmlDoItem(ataque)).toBe('&lt;img src=x onerror=alert(1)&gt;')
    expect(htmlDoItem(ataque, [[0, ataque.length]])).toBe('<mark>&lt;img src=x onerror=alert(1)&gt;</mark>')
    expect(htmlDoItem(`antes ${ataque} depois`, [[6, 10]])).toBe('antes <mark>&lt;img</mark> src=x onerror=alert(1)&gt; depois')
  })

  test('o único < que sobra é o do próprio <mark>', () => {
    const html = htmlDoItem(`"><script>alert(1)</script>&'`, [[2, 10]])
    expect(html.replaceAll('<mark>', '').replaceAll('</mark>', '')).not.toMatch(/[<>"']/)
    expect(html).toContain('&amp;')
  })

  test('escaparHtml cobre os cinco caracteres', () => {
    expect(escaparHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;')
  })

  test('texto puro continua igual', () => {
    expect(htmlDoItem('Prazo de 04/12/2026')).toBe('Prazo de 04/12/2026')
  })
})

test.describe('o trecho na página', () => {
  test('acha o trecho quebrado em outros pontos pelo pdf.js', () => {
    const itens = ['Outra coisa. ', 'Os geradores devem', ' ser informados ', 'até 04/12/2026.', ' E depois.']
    expect(marcado(itens, 'Os geradores devem ser\ninformados até 04/12/2026.')).toEqual(['Os geradores devem', 'ser informados', 'até 04/12/2026.'])
  })

  test('marca só o que é do trecho dentro de um pedaço', () => {
    const itens = ['Antes. Os geradores até 04/12. Depois.']
    expect(marcado(itens, 'Os geradores até 04/12.')).toEqual(['Os geradores até 04/12.'])
  })

  test('pedaços vazios e marcas de conteúdo não atrapalham a posição', () => {
    const itens = ['', 'Tarifa de ', '', 'R$ 312,00', '', ' por kVA']
    expect(marcado(itens, 'Tarifa de R$ 312,00 por kVA')).toEqual(['Tarifa de', 'R$ 312,00', 'por kVA'])
  })

  test('ligatura no PDF e no trecho casam', () => {
    expect(marcado(['Certiﬁcado de seguro'], 'Certificado de seguro')).toEqual(['Certiﬁcado de seguro'])
  })

  test('acento em caractere composto casa com o pré-composto', () => {
    expect(marcado(['Ativação da marca'], 'Ativação da marca')).toEqual(['Ativação da marca'])
  })

  test('o trecho que atravessa a página marca as linhas que estão nela', () => {
    const itens = ['Fim da página anterior não citado. ', 'Linha que está nesta página.', ' Rodapé número 3']
    expect(marcado(itens, 'Linha que está nesta página.\nLinha que ficou na página seguinte.')).toEqual(['Linha que está nesta página.'])
  })

  test('o rodapé que saiu na indexação não impede o destaque', () => {
    const itens = ['Primeira linha do trecho.', ' Rodapé da página 3 ', 'Segunda linha do trecho.']
    expect(marcado(itens, 'Primeira linha do trecho.\nSegunda linha do trecho.')).toEqual(['Primeira linha do trecho.', 'Segunda linha do trecho.'])
  })

  test('as linhas ficam em ordem: uma linha repetida antes do lugar certo não volta a página', () => {
    const itens = ['Segunda linha do trecho. ', 'Primeira linha do trecho. ', 'Segunda linha do trecho.']
    const faixas = faixasDoTrecho(itens, 'Primeira linha do trecho.\nSegunda linha do trecho.\nTerceira que não existe.')
    expect([...faixas.keys()]).toEqual([1, 2])
  })

  test('linha curta sozinha não marca nada', () => {
    expect(faixasDoTrecho(['Prazos e mais prazos para ler aqui.'], 'Prazos\nLinha que não está na página.').size).toBe(0)
  })

  test('trecho que não está na página ou vazio não marca nada', () => {
    expect(faixasDoTrecho(['Um texto qualquer da página.'], 'Nada disso aparece nesta página.').size).toBe(0)
    expect(faixasDoTrecho(['Um texto qualquer.'], '').size).toBe(0)
    expect(faixasDoTrecho(['Um texto qualquer.'], '   \n  ').size).toBe(0)
    expect(faixasDoTrecho([], 'Qualquer coisa escrita aqui.').size).toBe(0)
  })

  test('emoji no meio do texto não desloca a marca', () => {
    expect(marcado(['Veja 🎉 o prazo final de entrega.'], 'o prazo final de entrega.')).toEqual(['o prazo final de entrega.'])
  })
})

test.describe('o TXT em partes', () => {
  test('monta o texto inteiro, com a parte marcada no lugar', () => {
    const texto = 'Antes.\nOs geradores até 04/12.\nDepois.'
    const partes = partesDoTexto(texto, faixasDoTrecho([texto], 'Os geradores até 04/12.').get(0))
    expect(partes.map((parte) => parte.texto).join('')).toBe(texto)
    expect(partes.filter((parte) => parte.marcado).map((parte) => parte.texto)).toEqual(['Os geradores até 04/12.'])
  })

  test('sem faixa, é uma parte só; marcação no fim não deixa parte vazia', () => {
    expect(partesDoTexto('abc')).toEqual([{ texto: 'abc', marcado: false }])
    expect(partesDoTexto('abc', [[1, 3]])).toEqual([
      { texto: 'a', marcado: false },
      { texto: 'bc', marcado: true },
    ])
  })

  test('marca que é HTML continua sendo texto', () => {
    const partes = partesDoTexto('<b>x</b>', [[0, 8]])
    expect(partes).toEqual([{ texto: '<b>x</b>', marcado: true }])
  })
})

test.describe('citações no texto', () => {
  test('separa o texto das citações, sem perder nada', () => {
    const texto = 'Até 04/12/2026 [T1]. E a tarifa [T2, T3] e [C1].'
    const partes = partesComCitacoes(texto)
    expect(partes.map((parte) => parte.texto).join('')).toBe(texto)
    expect(partes.filter((parte) => parte.tipo === 'citacao').map((parte) => parte.tipo === 'citacao' && parte.marcadores)).toEqual([['T1'], ['T2', 'T3'], ['C1']])
  })

  test('aceita espaços dentro do colchete, como o servidor', () => {
    expect(partesComCitacoes('[ T1 , T2 ]')).toEqual([{ tipo: 'citacao', texto: '[ T1 , T2 ]', marcadores: ['T1', 'T2'] }])
  })

  test('o que não é citação fica como texto', () => {
    for (const texto of ['[1]', '[T]', '[T1234]', '[X1]', '[T1,]', 'T1', '[t1]', '[nota]']) {
      expect(partesComCitacoes(texto), texto).toEqual([{ tipo: 'texto', texto }])
    }
  })

  test('texto vazio não vira parte nenhuma', () => {
    expect(partesComCitacoes('')).toEqual([])
  })
})
