// Problemas de interface da rodada de base (IDs U da matriz do plano final) que o layout.spec não cobre: foco, nomes
// acessíveis e estados. Cada teste leva o ID no título e foi escrito antes da correção, vermelho.
import { expect, test } from '@playwright/test'
import { entrar, parceriaDa } from './apoio.ts'

test.describe('no celular', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('[U08] a gaveta do menu recebe o foco, prende o Tab e o devolve ao botão Menu', async ({ page }) => {
    await entrar(page, 'roxo')
    const botaoMenu = page.getByRole('button', { name: 'Menu', exact: true })
    await botaoMenu.click()
    const gaveta = page.locator('#menu-principal')
    // O foco entra na gaveta ao abrir.
    await expect.poll(() => gaveta.evaluate((g) => g.contains(document.activeElement))).toBe(true)
    // Tab várias vezes não sai da gaveta (o conteúdo atrás do véu não recebe foco).
    for (let i = 0; i < 25; i++) {
      await page.keyboard.press('Tab')
      expect(await gaveta.evaluate((g) => g.contains(document.activeElement)), `Tab ${i + 1}`).toBe(true)
    }
    // Esc fecha e devolve o foco a quem abriu.
    await page.keyboard.press('Escape')
    await expect(gaveta).toBeHidden()
    await expect(botaoMenu).toBeFocused()
  })
})

test('[U16] os botões de cada e-mail autorizado dizem de qual e-mail são', async ({ page }) => {
  const amarelo = await parceriaDa('amarelo')
  await entrar(page, 'rbs')
  await page.goto(`/parceria/${amarelo}/perfil`)
  const revogar = page.getByRole('button', { name: /^Revogar/ })
  await expect(revogar.first()).toBeVisible()
  const nomes = await revogar.evaluateAll((botoes) => botoes.map((b) => b.getAttribute('aria-label') ?? b.textContent ?? ''))
  expect(nomes.length).toBeGreaterThan(1)
  expect(new Set(nomes).size, `nomes repetidos: ${nomes.join(' | ')}`).toBe(nomes.length)
  for (const nome of nomes) expect(nome).toMatch(/@/)
})

test('[U17] o botão do Atlas desabilitado da RBS não parece ativo e diz por quê', async ({ page }) => {
  await entrar(page, 'rbs')
  const atlas = page.getByRole('navigation', { name: 'Menu principal' }).locator('xpath=..').getByRole('button', { name: /atlas/i })
  await expect(atlas).toBeDisabled()
  const opacidade = await atlas.evaluate((b) => Number(getComputedStyle(b).opacity))
  expect(opacidade, 'o desabilitado precisa parecer desabilitado').toBeLessThan(0.8)
  await expect(page.locator('#atlas-legenda')).toContainText(/marca|patrocinador/i)
})
