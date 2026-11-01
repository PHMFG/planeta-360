// As respostas do M5 na tela: "Como calculei", o modelo em branco e o pedido de outro relatório, com a conversa fixa (sem o modelo).
import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar, parceriaDa } from './apoio.ts'

async function conversaFixa(page: Page, parceria: number, itens: unknown[]) {
  const conversa = { conversaId: 987654, llmDisponivel: true, relatorioDisponivel: false, debugDeFerramentas: false, itens }
  await page.route(/\/api\/parcerias\/\d+\/atlas\/conversas$/, (rota) => rota.fulfill({ json: { llmDisponivel: true, relatorioDisponivel: false, conversas: [] } }))
  await page.route(/\/api\/atlas\/conversas\/987654$/, (rota) => rota.fulfill({ json: conversa }))
  await page.goto(`/parceria/${parceria}/atlas/987654`)
}

const CONTA = {
  tipo: 'calculo',
  marcador: 'C1',
  ferramenta: 'calcular',
  nome: 'energia',
  script: 'kva * tarifa',
  variaveis: [
    { nome: 'kva', origem: 'usuario', valor: '34' },
    { nome: 'tarifa', origem: 'T1', valor: '312,00' },
  ],
  resultado: '10.608,00',
  regra: null,
  feriadosConsiderados: [],
}

const TEXTO_COM_CONTA = {
  tipo: 'texto',
  texto: 'A tarifa é R$ 312,00 por kVA [T1]. Para 34 kVA, dá R$ 10.608,00 [C1].',
  porModelo: true,
  fontes: [
    { marcador: 'T1', tipo: 'TRECHO', documentoId: 1, titulo: 'Manual de Operações 2027', versao: 2, pagina: 5, secao: 'Energia', trechoId: 1, substituido: false, revogado: false },
    { marcador: 'C1', tipo: 'CALCULO', titulo: 'energia', substituido: false, revogado: false },
  ],
}

test('Como calculei fica depois do texto, fechado, e abre com a origem de cada número', async ({ page }) => {
  await entrar(page, 'amarelo')
  await conversaFixa(page, await parceriaDa('amarelo'), [{ tipo: 'usuario', texto: 'quanto vou pagar por 34 kVA?' }, CONTA, TEXTO_COM_CONTA])

  const texto = page.getByText('Para 34 kVA, dá R$ 10.608,00')
  const resumo = page.getByText('Como calculei', { exact: false })
  await expect(texto).toBeVisible()
  await expect(resumo).toBeVisible()
  expect((await texto.boundingBox())!.y).toBeLessThan((await resumo.boundingBox())!.y)
  await expect(page.getByText('kva * tarifa')).toBeHidden()

  await resumo.click()
  await expect(page.getByText('kva * tarifa')).toBeVisible()
  const numeros = page.getByRole('list', { name: 'Números usados' })
  await expect(numeros).toContainText('você informou')
  await expect(numeros.getByRole('button', { name: /^Abrir a fonte T1/ })).toBeVisible()
  await expect(page.getByLabel('Fontes')).not.toContainText('energia')
  await capturar(page, 'atlas-como-calculei')
})

test('a contagem de dias mostra a regra e os feriados pulados', async ({ page }) => {
  await entrar(page, 'roxo')
  await conversaFixa(page, await parceriaDa('roxo'), [
    { tipo: 'usuario', texto: 'se levar 20 dias úteis a partir de 03/11/2026, fica pronto quando?' },
    {
      tipo: 'calculo',
      marcador: 'C1',
      ferramenta: 'contar_dias',
      nome: null,
      script: null,
      variaveis: [],
      resultado: '02/12/2026',
      regra: '20 dias úteis depois de 03/11/2026. O dia de início não conta.',
      feriadosConsiderados: [{ data: '2026-11-20', nome: 'Consciência Negra' }],
    },
    { tipo: 'texto', texto: 'Fica pronto em 02/12/2026 [C1].', porModelo: true, fontes: [{ marcador: 'C1', tipo: 'CALCULO', substituido: false, revogado: false }] },
  ])
  await page.getByText('Como calculei', { exact: false }).click()
  await expect(page.getByText('O dia de início não conta.')).toBeVisible()
  await expect(page.getByText('20/11/2026 · Consciência Negra')).toBeVisible()
})

test('o modelo em branco baixa como .md e copia', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await entrar(page, 'amarelo')
  const conteudo = '# Briefing de conteúdo\n\nObjetivo da ação:\n\nPúblico:\n'
  await conversaFixa(page, await parceriaDa('amarelo'), [
    { tipo: 'usuario', texto: 'me dá um modelo de briefing' },
    { tipo: 'modelo', titulo: 'Briefing de conteúdo', conteudo, nomeArquivo: 'briefing-de-conteudo.md' },
  ])
  await expect(page.getByLabel('Prévia do modelo Briefing de conteúdo')).toContainText('Objetivo da ação:')

  const [baixado] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Baixar .md' }).click()])
  expect(baixado.suggestedFilename()).toBe('briefing-de-conteudo.md')
  expect(await readFile(await baixado.path(), 'utf-8')).toBe(conteudo)

  await page.getByRole('button', { name: 'Copiar' }).click()
  await expect(page.getByText('Copiado.')).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(conteudo)
  await capturar(page, 'atlas-modelo')
})

test('o pedido de outro relatório leva ao oficial, quando existe, e o modelo só preenche o campo', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await conversaFixa(page, parceria, [
    { tipo: 'usuario', texto: 'me manda o relatório pós-evento' },
    {
      tipo: 'pedido_relatorio',
      texto: 'O relatório oficial é feito pela RBS. Posso passar um modelo em branco.',
      relatorioOficial: true,
      modelos: [{ tipo: 'BRIEFING_CONTEUDO', titulo: 'Briefing de conteúdo' }],
    },
  ])
  await expect(page.getByRole('link', { name: 'Abrir o relatório oficial' })).toHaveAttribute('href', `/parceria/${parceria}/relatorio`)
  await page.getByRole('button', { name: 'Modelo: Briefing de conteúdo' }).click()
  await expect(page.getByRole('textbox')).toHaveValue('Quero o modelo em branco: Briefing de conteúdo')
  await capturar(page, 'atlas-pedido-de-relatorio')
})

test('sem relatório oficial não há o atalho; no celular os cartões cabem na tela', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 780 })
  await entrar(page, 'amarelo')
  await conversaFixa(page, await parceriaDa('amarelo'), [
    { tipo: 'usuario', texto: 'quero o relatório de mídia' },
    { tipo: 'pedido_relatorio', texto: 'O relatório oficial é feito pela RBS.', relatorioOficial: false, modelos: [] },
    { tipo: 'modelo', titulo: 'Plano operacional da ativação', conteudo: 'Responsável:\n'.repeat(40), nomeArquivo: 'plano-operacional.md' },
    CONTA,
    TEXTO_COM_CONTA,
  ])
  await expect(page.getByRole('link', { name: 'Abrir o relatório oficial' })).toHaveCount(0)
  await page.getByText('Como calculei', { exact: false }).click()
  await expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)
  await capturar(page, 'atlas-respostas-celular')
})
