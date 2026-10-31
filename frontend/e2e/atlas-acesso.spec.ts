// Login da marca, a primeira mensagem ao Atlas e a aba Aplicações fechada barrando o Atlas.
import { expect, test } from '@playwright/test'
import { capturar, entrar, parceriaDa, sessaoDaApi } from './apoio.ts'

test('a marca entra e cai na própria parceria', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await expect(page).toHaveURL(new RegExp(`/parceria/${parceria}`))
  await expect(page.getByRole('link', { name: 'atlas', exact: true })).toBeVisible()
  await capturar(page, 'marca-entrou')
})

test('a marca abre o Atlas e recebe resposta do modelo @vivo', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.goto(`/parceria/${parceria}/atlas`)

  const campo = page.getByLabel('Mensagem para o Atlas')
  await campo.pressSequentially('Como estão as aplicações da nossa marca?')
  await expect(campo).toHaveValue('Como estão as aplicações da nossa marca?')
  await campo.press('Enter')

  await expect(page.getByText('Como estão as aplicações da nossa marca?')).toBeVisible()
  // O turno termina quando o botão de enviar para de girar. Confere só a estrutura: o texto vem do modelo.
  await expect(page.locator('form .animate-spin')).toHaveCount(0, { timeout: 80_000 })
  await expect(page.getByText('atlas', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/^(Não consegui|Parei por aqui|Esta resposta demorou)/)).toHaveCount(0)
  await capturar(page, 'atlas-respondeu')
})

test('com a aba Aplicações fechada, o Atlas não abre para a marca', async ({ page }) => {
  const parceria = await parceriaDa('amarelo')
  const rbs = await sessaoDaApi('rbs')
  const abrirOuFechar = (liberada: boolean) =>
    rbs.contexto.patch(`/api/parcerias/${parceria}`, {
      headers: rbs.cabecalhos,
      data: { abas: [{ codigo: 'APLICACOES', liberada }] },
    })

  expect((await abrirOuFechar(false)).status()).toBe(200)
  try {
    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${parceria}/atlas`)
    await expect(page.getByText('ela está fechada para a sua parceria')).toBeVisible()
    await expect(page.getByLabel('Mensagem para o Atlas')).toHaveCount(0)
    await capturar(page, 'atlas-aba-fechada')
  } finally {
    // A amostra deixa a aba aberta nas três marcas: o teste devolve como encontrou, mesmo se falhar.
    expect((await abrirOuFechar(true)).status()).toBe(200)
    await rbs.contexto.dispose()
  }
})
