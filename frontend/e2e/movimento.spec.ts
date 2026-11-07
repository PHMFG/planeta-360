// O movimento do portal (plano da base da RBS, F10), com a animação ligada: a orbe do Atlas respira, a troca de tela não
// quebra a navegação, e com "reduzir movimento" nada se mexe. Os outros specs rodam com o movimento reduzido.
import { expect, test } from '@playwright/test'
import { entrar, parceriaDa } from './apoio'

test.describe('com movimento', () => {
  test.use({ contextOptions: { reducedMotion: 'no-preference' } })

  test('a orbe do Atlas respira e a navegação pelo menu troca o conteúdo', async ({ page }) => {
    const parceria = await parceriaDa('amarelo')
    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${parceria}/atlas`)
    const orbe = page.locator('main [aria-hidden="true"] > span.animate-respirar').first()
    await expect(orbe).toBeAttached()
    expect(await orbe.evaluate((no) => getComputedStyle(no).animationName)).toBe('respirar')

    // A troca de tela usa a View Transitions API quando existe; o conteúdo novo chega do mesmo jeito.
    await page.getByRole('link', { name: 'Mídia e conteúdo' }).click()
    await expect(page).toHaveURL(new RegExp(`/parceria/${parceria}/midia$`))
    await expect(page.getByRole('heading', { name: 'Mídia e conteúdo' })).toBeVisible()
  })

  test('o modal cresce ao abrir e encolhe ao fechar', async ({ page }) => {
    await entrar(page, 'rbs')
    await page.goto('/rbs/relatorios')
    await page.getByRole('button', { name: 'Informar número' }).click()
    const dialogo = page.getByRole('dialog', { name: 'Informar um número' })
    await expect(dialogo).toBeVisible()
    expect(await dialogo.evaluate((no) => getComputedStyle(no).animationName)).toBe('crescer')
    await page.keyboard.press('Escape')
    await expect(dialogo).toHaveCount(0)
  })
})

test('com movimento reduzido, a orbe fica parada', async ({ page }) => {
  const parceria = await parceriaDa('amarelo')
  await entrar(page, 'amarelo')
  await page.goto(`/parceria/${parceria}/atlas`)
  const orbe = page.locator('main [aria-hidden="true"] > span.animate-respirar').first()
  await expect(orbe).toBeAttached()
  const duracao = await orbe.evaluate((no) => getComputedStyle(no).animationDuration)
  expect(parseFloat(duracao)).toBeLessThan(0.01)
})
