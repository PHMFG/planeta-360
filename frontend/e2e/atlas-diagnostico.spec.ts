// A gaveta do diagnóstico na conversa da marca, a página Atlas da RBS e nenhum botão Debug na tela.
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar, parceriaDa } from './apoio.ts'

/** Pergunta ao Atlas e espera o turno terminar. */
async function perguntar(page: Page, parceria: number, pergunta: string) {
  await page.goto(`/parceria/${parceria}/atlas`)
  const campo = page.getByLabel('Mensagem para o Atlas')
  await campo.pressSequentially(pergunta)
  await expect(campo).toHaveValue(pergunta)
  await campo.press('Enter')
  await expect(page.getByText(pergunta)).toBeVisible()
  await expect(page.locator('form .animate-spin')).toHaveCount(0, { timeout: 80_000 })
}

const semRolagemHorizontal = (page: Page) => expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)

test('a marca abre o diagnóstico da resposta, e a RBS vê o mesmo turno @vivo', async ({ page, browser }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await perguntar(page, parceria, 'Quantas aplicações temos previstas?')

  const botao = page.getByRole('button', { name: 'Diagnóstico', exact: true }).last()
  await botao.click()
  const gaveta = page.getByRole('dialog', { name: 'Como esta resposta foi feita' })
  await expect(gaveta.getByRole('button', { name: /Ida ao modelo/ }).first()).toBeVisible()
  await expect(gaveta.getByRole('button', { name: /Checagem do texto/ })).toBeVisible()
  await expect(gaveta.getByRole('button', { name: /Saída/ })).toBeVisible()
  await gaveta.getByRole('button', { name: /Ida ao modelo/ }).first().click()
  await expect(gaveta.getByText('Tokens de entrada').first()).toBeVisible()
  await expect(gaveta.getByText('Motivo do fim').first()).toBeVisible()
  await expect(gaveta.getByRole('button', { name: 'Copiar JSON' })).toBeVisible()
  await expect(gaveta).not.toContainText('reasoning_content')
  await capturar(page, 'diagnostico-gaveta-marca')

  await page.keyboard.press('Escape')
  await expect(gaveta).toHaveCount(0)
  await expect(botao).toBeFocused()

  const contexto = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  const rbs = await contexto.newPage()
  await entrar(rbs, 'rbs')
  await rbs.goto('/rbs/atlas?aba=turnos')
  await expect(rbs.getByRole('tab', { name: 'Turnos', selected: true })).toBeVisible()
  const primeira = rbs.locator('tbody tr').first()
  await expect(primeira).toContainText('Cliente Amarelo')
  await capturar(rbs, 'rbs-atlas-turnos')
  await primeira.getByRole('button').first().click()
  const gavetaRbs = rbs.getByRole('dialog')
  await expect(gavetaRbs.getByRole('button', { name: /Ida ao modelo/ }).first()).toBeVisible()
  await expect(rbs).toHaveURL(/turno=/)
  await capturar(rbs, 'rbs-atlas-gaveta')
  await rbs.keyboard.press('Escape')
  await expect(gavetaRbs).toHaveCount(0)
  await contexto.close()
})

test('a RBS vê os provedores e testa a conexão @vivo', async ({ page }) => {
  await entrar(page, 'rbs')
  await page.goto('/rbs/atlas?aba=turnos')
  await page.getByRole('tab', { name: 'Provedores' }).click()
  await expect(page.getByRole('heading', { name: 'Modelo de linguagem' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'JEV' })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Embeddings/ })).toBeVisible()
  await expect(page.getByText(/Teto diário/)).toBeVisible()
  await page.getByRole('button', { name: 'Testar conexão' }).nth(1).click()
  await expect(page.getByText(/^Respondeu/)).toBeVisible({ timeout: 20_000 })
  await capturar(page, 'rbs-atlas-provedores')
})

test('o item Atlas aparece no menu da RBS', async ({ page }) => {
  await entrar(page, 'rbs')
  await page.getByRole('link', { name: 'Atlas', exact: true }).click()
  await expect(page).toHaveURL(/\/rbs\/atlas/)
  await expect(page.getByRole('heading', { name: 'Todas as marcas' })).toBeVisible()
})

test('não há botão Debug na tela, para ninguém', async ({ page }) => {
  // Desde 06/11 (pedido do ph) a chave do modelo vem só do servidor (variável de ambiente ou config/segredos.local.properties).
  await entrar(page, 'amarelo')
  await expect(page.getByRole('link', { name: 'atlas', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /^Debug/ })).toHaveCount(0)
  await page.getByRole('button', { name: 'Sair' }).click()
  await entrar(page, 'rbs')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByRole('button', { name: /^Debug/ })).toHaveCount(0)
})

test.describe('no celular', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('a página da RBS e a gaveta cabem na tela', async ({ page }) => {
    await entrar(page, 'rbs')
    await page.goto('/rbs/atlas?aba=turnos')
    await expect(page.getByRole('tab', { name: 'Turnos' })).toBeVisible()
    await semRolagemHorizontal(page)
    await capturar(page, 'rbs-atlas-celular')
    await page.getByRole('tab', { name: 'Provedores' }).click()
    await expect(page.getByRole('heading', { name: 'JEV' })).toBeVisible()
    await semRolagemHorizontal(page)
    await page.getByRole('tab', { name: 'Turnos' }).click()
    const primeira = page.locator('tbody tr').first().getByRole('button').first()
    await primeira.click()
    const gaveta = page.getByRole('dialog')
    await expect(gaveta.getByRole('button', { name: /Saída/ })).toBeVisible()
    await semRolagemHorizontal(page)
    const caixa = await gaveta.boundingBox()
    expect(caixa?.width).toBeLessThanOrEqual(390)
    await capturar(page, 'rbs-atlas-gaveta-celular')
  })
})
