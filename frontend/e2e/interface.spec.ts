// Problemas de interface da rodada de base (IDs U da matriz do plano final) que o layout.spec não cobre: foco, nomes
// acessíveis e estados. Cada teste leva o ID no título e foi escrito antes da correção, vermelho.
import { expect, test } from '@playwright/test'
import { entrar, entrarComo, novaMarcaDeTeste, parceriaDa, sessaoDaApi } from './apoio.ts'

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

test('[U04] sem entrega de mídia aberta, a marca sabe antes de escolher o arquivo', async ({ page }) => {
  const verde = await parceriaDa('verde')
  await entrar(page, 'verde')
  await page.goto(`/parceria/${verde}/midia`)
  await expect(page.getByRole('status').filter({ hasText: 'Nenhuma entrega de mídia aceita envio agora' })).toBeVisible()
  await expect(page.getByText(/A RBS abre cada entrega/)).toBeVisible()
  await expect(page.getByRole('button', { name: /Selecionar arquivos/i })).toBeDisabled()
})

test('[U09] erro no login é anunciado e o foco vai ao campo', async ({ page }) => {
  await page.goto('/entrar')
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  const email = page.getByLabel('E-mail corporativo')
  await expect(email).toBeFocused()
  await expect(page.getByRole('alert').filter({ hasText: 'Informe o e-mail' })).toBeVisible()
  await expect(email).toHaveAttribute('aria-invalid', 'true')
})

test('[U09] erro no primeiro acesso é anunciado e o foco vai ao campo', async ({ page }) => {
  await page.goto('/ativar')
  await page.getByRole('button', { name: /Criar|Ativar|Continuar/i }).first().click()
  const invalido = page.locator('form [aria-invalid="true"]').first()
  await expect(invalido).toBeFocused()
  await expect(page.getByRole('alert').first()).toBeVisible()
})

test('[U06] número inválido depois de um salvo não mostra "Números salvos" junto do erro', async ({ page }) => {
  const marca = await novaMarcaDeTeste('MASTER')
  await entrarComo(page, marca.email)
  await page.goto(`/parceria/${marca.parceriaId}/aplicacoes`)
  const campo = page.locator('form input[inputmode="numeric"], form input[type="text"]').first()
  await campo.fill('1200')
  await page.getByRole('button', { name: 'Salvar números' }).click()
  await expect(page.getByText('Números salvos')).toBeVisible()
  await campo.fill('doze')
  await page.getByRole('button', { name: 'Salvar números' }).click()
  await expect(page.getByRole('alert').filter({ hasText: /números inteiros/ })).toBeVisible()
  await expect(page.getByText('Números salvos')).toHaveCount(0)
})

test('[U07] arquivo que não é CSV é recusado ao escolher, sem resultado antigo na tela', async ({ page }) => {
  await entrar(page, 'rbs')
  await page.goto('/rbs/cargas')
  await page.locator('input[type=file]').setInputFiles({ name: 'veiculacao.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('PK') })
  await expect(page.getByRole('alert').filter({ hasText: 'não é CSV' })).toBeVisible()
  await expect(page.getByText(/processada ·/)).toHaveCount(0)
})

test('[U05] aba fechada pela RBS aparece marcada no menu e explica ao abrir', async ({ page }) => {
  const marca = await novaMarcaDeTeste('SUBCOTA')
  const rbs = await sessaoDaApi('rbs')
  const fechar = await rbs.contexto.patch(`/api/parcerias/${marca.parceriaId}`, {
    headers: rbs.cabecalhos,
    data: { abas: [{ codigo: 'CORTESIAS', liberada: false }] },
  })
  expect(fechar.status()).toBe(200)
  await rbs.contexto.dispose()
  await entrarComo(page, marca.email)
  const item = page.getByRole('navigation', { name: 'Menu principal' }).getByRole('link', { name: /Cortesias/ })
  // O motivo está à vista no item, não só no cadeado (que só fala no hover).
  await expect(item).toContainText(/Fechada/)
  await item.click()
  await expect(page).toHaveURL(new RegExp(`/parceria/${marca.parceriaId}/cortesias$`))
  await expect(page.getByText('Esta aba está fechada no momento')).toBeVisible()
})

test('[U12] a marca não chama a rota de debug do modelo', async ({ page }) => {
  const chamadas: string[] = []
  page.on('request', (pedido) => {
    if (pedido.url().includes('/api/debug/')) chamadas.push(pedido.url())
  })
  await entrar(page, 'roxo')
  await page.waitForLoadState('networkidle')
  expect(chamadas).toEqual([])
})

test('[U13] fora da cota, a aba diz como incluir, sem "contratar" e sem "A cota Subcota"', async ({ page }) => {
  const verde = await parceriaDa('verde')
  await entrar(page, 'verde')
  await page.goto(`/parceria/${verde}/pesquisa`)
  const aviso = page.getByRole('status').filter({ hasText: 'não faz parte da sua cota' })
  await expect(aviso).toContainText('A cota da sua parceria (Subcota)')
  await expect(aviso).not.toContainText('contratar')
})

test('[U14] na parceria aberta pela RBS, a próxima ação é da marca', async ({ page }) => {
  const roxo = await parceriaDa('roxo')
  await entrar(page, 'rbs')
  await page.goto(`/parceria/${roxo}`)
  await expect(page.getByRole('heading', { name: 'Próxima ação da marca' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Sua próxima ação' })).toHaveCount(0)
})

test('[U03] a lista de relatórios conta indicadores e explica as colunas', async ({ page }) => {
  await entrar(page, 'rbs')
  await page.goto('/rbs/relatorios')
  await expect(page.getByText(/com dado ·/).first()).toBeVisible()
  const contagens = await page.getByText(/\d+ com dado · \d+ indisponíveis/).allInnerTexts()
  expect(contagens.length).toBeGreaterThan(0)
  for (const texto of contagens) {
    const [com, sem] = texto.match(/\d+/g)!.map(Number)
    expect(com + sem, texto).toBe(17)
  }
  // A explicação das colunas fica recolhida em "Como ler as colunas" (F9).
  await page.getByText('Como ler as colunas').click()
  await expect(page.getByText(/Faltantes: o que impede/)).toBeVisible()
})

test('[U11] Ajuda abre um painel visível com o que cada aba faz e como falar com a RBS', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  await entrar(page, 'roxo')
  await page.getByRole('button', { name: 'Ajuda' }).click()
  const painel = page.getByRole('dialog', { name: /Ajuda/ })
  await expect(painel).toBeVisible()
  await expect(painel).toContainText('Cortesias')
  await expect(painel).toContainText(/atendimento|time B2B/)
  // O painel cabe na tela e não fica escondido atrás de nada.
  const caixa = await painel.boundingBox()
  expect(caixa && caixa.y >= 0 && caixa.y + caixa.height <= 720).toBe(true)
  await page.keyboard.press('Escape')
  await expect(painel).toBeHidden()
})

test('[U10] a agência vê que entra como agência do patrocinador', async ({ page }) => {
  await entrar(page, 'agencia')
  await expect(page.getByText('Agência do patrocinador').first()).toBeVisible()
})
