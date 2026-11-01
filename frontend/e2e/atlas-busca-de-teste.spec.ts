// A aba Busca de teste do Atlas: o que a busca nos documentos faria por uma parceria, sem o modelo.
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar } from './apoio.ts'

const semRolagemHorizontal = (page: Page) => expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)

test('a RBS vê as duas listas e o que a parceria enxerga na busca de teste', async ({ page }) => {
  await entrar(page, 'rbs')
  await page.goto('/rbs/atlas?aba=busca')
  await expect(page.getByRole('tab', { name: 'Busca de teste', selected: true })).toBeVisible()

  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect(page.getByText('Informe a parceria (parceriaId).')).toBeVisible()
  await expect(page.getByText('Escreva a consulta.')).toBeVisible()

  await page.getByLabel('Parceria', { exact: false }).first().selectOption({ label: 'Cliente Amarelo · 2027' })
  const consulta = page.getByLabel('Consulta')
  const texto = 'prazo para informar os geradores'
  await consulta.pressSequentially(texto)
  await expect(consulta).toHaveValue(texto)
  await expect(consulta).toBeFocused()
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'O que o modelo receberia' })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByRole('heading', { name: 'Lista vetorial' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Lista textual' })).toBeVisible()
  const enxerga = page.locator('section', { has: page.getByRole('heading', { name: 'O que esta parceria enxerga' }) })
  await expect(enxerga).toContainText('Manual de Operações 2027')
  await expect(enxerga).not.toContainText('Girassol')
  await capturar(page, 'rbs-atlas-busca')
})

test.describe('no celular', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('a busca de teste cabe na tela', async ({ page }) => {
    await entrar(page, 'rbs')
    await page.goto('/rbs/atlas?aba=busca')
    await page.getByLabel('Parceria', { exact: false }).first().selectOption({ label: 'Cliente Amarelo · 2027' })
    await page.getByLabel('Consulta').pressSequentially('tarifa de energia')
    await page.getByRole('button', { name: 'Buscar', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'O que o modelo receberia' })).toBeVisible({ timeout: 20_000 })
    await semRolagemHorizontal(page)
    await capturar(page, 'rbs-atlas-busca-celular')
  })
})
