// A aba Prazos do Atlas na gestão da RBS: criar, ver os erros junto dos campos e desativar, contra a API de verdade (rotas 62 a 64).
import { expect, test } from '@playwright/test'
import { capturar, entrar, sessaoDaApi } from './apoio.ts'

const semRolagemHorizontal = (page: import('@playwright/test').Page) => expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)

test('a RBS cria um prazo, vê o erro junto do campo e desativa sem apagar', async ({ page }) => {
  const titulo = `Prazo de teste ${Date.now()}`
  await entrar(page, 'rbs')
  await page.goto('/rbs/atlas?aba=prazos')
  await expect(page.getByRole('tab', { name: 'Prazos' })).toHaveAttribute('aria-selected', 'true')

  const novo = page.getByRole('heading', { name: 'Novo prazo' }).locator('xpath=ancestor::*[self::section or self::div][1]')
  await page.getByRole('button', { name: 'Criar prazo' }).click()
  await expect(page.getByText(/título/i).first()).toBeVisible()

  await page.getByLabel('Título').pressSequentially(titulo)
  await expect(page.getByLabel('Título')).toHaveValue(titulo)
  await expect(page.getByLabel('Título')).toBeFocused()
  await page.getByLabel('Data').fill('2027-01-15')
  await page.getByLabel('Hora (opcional)').fill('18:00')
  await page.getByRole('button', { name: 'Criar prazo' }).click()
  await expect(page.getByText('Prazo criado. O Atlas já o enxerga.')).toBeVisible()
  await expect(novo).toBeVisible()

  const linha = page.getByRole('listitem').filter({ hasText: titulo })
  await expect(linha).toContainText('15/01/2027 às 18:00')
  await expect(linha).toContainText('Todas as parcerias')
  await capturar(page, 'atlas-prazos')

  await linha.getByRole('button', { name: `Desativar o prazo ${titulo}` }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Desativar' }).click()
  await expect(linha).toHaveCount(0)

  await page.getByLabel('Mostrar os desativados').check()
  await expect(page.getByRole('listitem').filter({ hasText: titulo })).toContainText('Desativado')
})

test('a parceria é pedida só no alcance dela, e a tela cabe no celular', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 780 })
  await entrar(page, 'rbs')
  await page.goto('/rbs/atlas?aba=prazos')
  const parceria = page.getByRole('combobox', { name: /^Parceria/ })
  await expect(page.getByRole('button', { name: 'Criar prazo' })).toBeVisible()
  await expect(parceria).toHaveCount(0)
  await page.getByRole('combobox', { name: /^Vale para/ }).selectOption('PARCERIA')
  await expect(parceria).toBeVisible()
  await semRolagemHorizontal(page)
})

test('a marca não enxerga os prazos da gestão', async () => {
  const { contexto } = await sessaoDaApi('amarelo')
  expect((await contexto.get('/api/eventos/1/prazos')).status()).toBe(403)
  await contexto.dispose()
})
