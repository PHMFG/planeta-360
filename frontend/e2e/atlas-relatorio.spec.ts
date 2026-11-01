// O relatório religado (M6): fotos na galeria, a impressão que espera as fotos, descartar pela tela, a planilha da agência e o caminho
// inteiro, do botão ao A4 em PDF.
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar, parceriaDa, sessaoDaApi } from './apoio.ts'

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

function relatorioComGaleria(parceriaId: number, status = 'PRONTO') {
  return {
    id: 555,
    parceriaId,
    patrocinador: 'Cliente Amarelo',
    titulo: 'Relatório da ativação',
    status,
    criadoPor: { id: 1, nome: 'Marketing' },
    criadoEm: '2026-11-01T12:00:00-03:00',
    atualizadoEm: '2026-11-01T12:00:00-03:00',
    finalizadoEm: status === 'PRONTO' ? '2026-11-01T12:10:00-03:00' : null,
    selo: 'Documento de trabalho',
    secoes: [
      {
        id: 'comprovacoes',
        titulo: 'Comprovações',
        blocos: [
          {
            id: 'galeria',
            tipo: 'galeria_comprovacoes',
            itens: [
              { arquivoId: 71, nomeOriginal: 'foto_arena.png', aplicacao: 'Arena', descricao: 'Painel na Arena', registradoEm: '2026-11-01T10:00:00-03:00' },
              { arquivoId: 72, nomeOriginal: 'comprovante.pdf', aplicacao: 'Camarote', descricao: null, registradoEm: '2026-11-01T11:00:00-03:00' },
            ],
          },
        ],
      },
    ],
  }
}

/** A foto 71 é uma imagem, e o arquivo 72 é um PDF. */
async function arquivosDaGaleria(page: Page, espera = 0) {
  await page.route(/\/api\/arquivos\/71$/, async (rota) => {
    if (espera) await new Promise((fim) => setTimeout(fim, espera))
    await rota.fulfill({ body: PNG, contentType: 'image/png', headers: { 'Content-Disposition': 'attachment; filename="foto_arena.png"' } })
  })
  await page.route(/\/api\/arquivos\/72$/, (rota) => rota.fulfill({ body: Buffer.from('%PDF-1.7'), contentType: 'application/pdf' }))
}

test('a galeria mostra as fotos, e o arquivo que não é imagem aparece como indisponível', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.route(/\/api\/atlas\/relatorios\/555$/, (rota) => rota.fulfill({ json: relatorioComGaleria(parceria) }))
  await arquivosDaGaleria(page)
  await page.goto(`/parceria/${parceria}/relatorios-atlas/555`)

  const foto = page.getByRole('img', { name: 'Painel na Arena' })
  await expect(foto).toBeVisible()
  await expect.poll(() => foto.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
  expect(await foto.getAttribute('src')).toMatch(/^blob:/)
  await expect(page.getByText('Foto indisponível')).toBeVisible()
  await expect(page.getByText('comprovante.pdf')).toBeVisible()
})

test('a impressão espera as fotos chegarem', async ({ page }) => {
  await page.addInitScript(() => {
    const janela = window as unknown as { impressoes: number; print: () => void }
    janela.impressoes = 0
    janela.print = () => {
      janela.impressoes += 1
    }
  })
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.route(/\/api\/atlas\/relatorios\/555$/, (rota) => rota.fulfill({ json: relatorioComGaleria(parceria) }))
  await arquivosDaGaleria(page, 1800)
  await page.goto(`/parceria/${parceria}/relatorios-atlas/555?imprimir=1`)

  const impressoes = () => page.evaluate(() => (window as unknown as { impressoes: number }).impressoes)
  await expect(page.getByText('Foto indisponível')).toBeVisible()
  await page.waitForTimeout(900)
  expect(await impressoes(), 'a foto ainda está a caminho').toBe(0)
  await expect(page.getByRole('img', { name: 'Painel na Arena' })).toBeVisible({ timeout: 5000 })
  await expect.poll(impressoes, { timeout: 5000 }).toBe(1)
})

test('a marca descarta o relatório em montagem e pode pedir outro; a RBS não tem o botão', async ({ page, browser }) => {
  await entrar(page, 'verde')
  const parceria = await parceriaDa('verde')
  await page.goto(`/parceria/${parceria}/atlas`)
  await page.getByRole('button', { name: /Gerar relatório da ativação/ }).click()

  const descartar = page.getByRole('button', { name: 'Descartar', exact: true })
  await expect(descartar).toBeVisible({ timeout: 30_000 })
  await descartar.click()
  const modal = page.getByRole('dialog', { name: 'Descartar este relatório?' })
  await expect(modal).toBeVisible()
  await capturar(page, 'atlas-descartar-modal')
  await modal.getByRole('button', { name: 'Agora não' }).click()
  await expect(modal).toBeHidden()

  await descartar.click()
  await modal.getByRole('button', { name: 'Descartar relatório' }).click()
  await expect(modal).toBeHidden()
  await expect(page.getByRole('button', { name: /Montar relatório da ativação/ })).toBeEnabled({ timeout: 15_000 })
  await capturar(page, 'atlas-descartado')

  const { contexto } = await sessaoDaApi('verde')
  const lista = await (await contexto.get(`/api/parcerias/${parceria}/atlas/relatorios`)).json()
  expect(lista[0].status).toBe('DESCARTADO')
  await contexto.dispose()

  // A RBS abre o mesmo relatório de um relatório em montagem e não tem como descartar pela marca.
  const outra = await browser.newContext()
  const rbs = await outra.newPage()
  await entrar(rbs, 'rbs')
  await rbs.route(/\/api\/atlas\/relatorios\/555$/, (rota) => rota.fulfill({ json: relatorioComGaleria(parceria, 'MONTANDO') }))
  await arquivosDaGaleria(rbs)
  await rbs.goto(`/parceria/${parceria}/relatorios-atlas/555`)
  await expect(rbs.getByText('Relatório da ativação').first()).toBeVisible()
  await expect(rbs.getByRole('button', { name: /Descartar/ })).toHaveCount(0)
  await outra.close()
})

test('o relatório em montagem se descarta também na página inteira, e o aviso muda', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  let status = 'MONTANDO'
  await page.route(/\/api\/atlas\/relatorios\/555$/, (rota) => rota.fulfill({ json: relatorioComGaleria(parceria, status) }))
  await page.route(/\/api\/atlas\/relatorios\/555\/descartar$/, (rota) => {
    status = 'DESCARTADO'
    return rota.fulfill({ json: relatorioComGaleria(parceria, status) })
  })
  await arquivosDaGaleria(page)
  await page.goto(`/parceria/${parceria}/relatorios-atlas/555`)

  await page.getByRole('button', { name: 'Descartar relatório' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Descartar relatório' }).click()
  await expect(page.getByText('Este relatório não segue mais.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Descartar relatório' })).toHaveCount(0)
})

test('a planilha da agência vai pelo formulário da aba, cabe no celular e não vira número', async ({ page }) => {
  await entrar(page, 'roxo')
  const parceria = await parceriaDa('roxo')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`/parceria/${parceria}/aplicacoes`)

  await expect(page.getByLabel(/Planilha da agência \(/)).toHaveCount(0)
  await page.locator('input[type=file][aria-label="Planilha da agência"]').setInputFiles({
    name: 'contagem-agencia.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('dia;visitantes\n29/01/2027;1200\n'),
  })
  await expect(page.getByText('contagem-agencia.csv')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Enviar outra planilha' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await capturar(page, 'atlas-planilha-celular')

  const baixando = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Baixar', exact: true }).last().click()
  expect((await baixando).suggestedFilename()).toBe('contagem-agencia.csv')

  await page.locator('input[type=file][aria-label="Planilha da agência"]').setInputFiles({ name: 'foto.png', mimeType: 'image/png', buffer: PNG })
  await expect(page.getByRole('alert').or(page.getByText(/xlsx/))).toBeVisible()
})

test('do botão ao A4: cartão, números e o PDF do relatório @vivo', async ({ page }) => {
  test.setTimeout(240_000)
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.goto(`/parceria/${parceria}/atlas`)
  await page.getByRole('button', { name: /Gerar relatório da ativação/ }).click()

  // Com os números já informados neste banco, o relatório segue sem cartão.
  const visitantes = page.getByLabel(/Visitantes no espaço/)
  if (await visitantes.waitFor({ timeout: 20_000 }).then(() => true, () => false)) {
    await visitantes.fill('1200')
    await page.getByLabel(/Cadastros com consentimento/).fill('1072')
    await capturar(page, 'atlas-relatorio-cartao')
    await page.getByRole('button', { name: 'Enviar números' }).click()
  }

  await expect(page.getByText(/Documento de trabalho da marca, para revisão/).first()).toBeVisible({ timeout: 200_000 })
  await page.getByRole('link', { name: 'Página inteira' }).first().click()
  await expect(page.getByRole('link', { name: 'Voltar aos relatórios' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Referências' })).toBeVisible()
  await expect(page.locator('figure').first()).toBeVisible()
  await expect.poll(() => page.locator('[data-foto-carregando]').count(), { timeout: 15_000 }).toBe(0)
  expect(await page.locator('figure img').count(), 'as fotos da amostra').toBeGreaterThan(0)
  await expect(page.getByRole('button', { name: /Descartar/ })).toHaveCount(0)
  await capturar(page, 'atlas-relatorio-a4')
  await page.emulateMedia({ media: 'print' })
  await page.pdf({ path: 'e2e/capturas/atlas-relatorio-a4.pdf', format: 'A4', printBackground: true })
})
