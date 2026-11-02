// As fontes da resposta do Atlas: chip, lista e o visualizador na página citada, com o destaque.
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar, parceriaDa, sessaoDaApi } from './apoio.ts'

type DocumentoDaApi = { id: number; chave: string; versao: number; status: string; titulo: string; arquivo: { mimeType: string } }

/** Um documento do kit e um trecho dele, na página pedida, como a RBS os vê. */
async function trechoDoKit(filtro: (documento: DocumentoDaApi) => boolean, pagina?: number) {
  const { contexto } = await sessaoDaApi('rbs')
  const lista = (await (await contexto.get('/api/documentos?tamanho=50')).json()) as { itens: DocumentoDaApi[] }
  const documento = lista.itens.find(filtro)
  if (!documento) throw new Error('o kit não tem o documento pedido')
  const trechos = (await (await contexto.get(`/api/documentos/${documento.id}/trechos`)).json()) as { itens: { id: number; paginaInicio: number; paginaFim: number; secao: string | null }[] }
  const trecho = trechos.itens.find((item) => pagina === undefined || item.paginaInicio === pagina)
  await contexto.dispose()
  if (!trecho) throw new Error(`o documento ${documento.titulo} não tem trecho na página ${pagina}`)
  return { documento, trecho }
}

/**
 * Uma conversa gravada com a citação, sem chamar o modelo: a API de verdade responde o resto (a rota 59 e a 60). O conteúdo do
 * texto é fixo; o que se confere é a tela. A conversa com o modelo de verdade está no teste @vivo.
 */
async function conversaComFontes(page: Page, parceria: number, itens: unknown[]) {
  await page.route(/\/api\/parcerias\/\d+\/atlas\/conversas$/, (rota) => rota.fulfill({ json: { llmDisponivel: true, relatorioDisponivel: false, conversas: [] } }))
  await page.route(/\/api\/atlas\/conversas\/987654$/, (rota) => rota.fulfill({ json: { conversaId: 987654, llmDisponivel: true, relatorioDisponivel: false, debugDeFerramentas: false, itens } }))
  await page.goto(`/parceria/${parceria}/atlas/987654`)
}

const manualAtual = (documento: DocumentoDaApi) => documento.titulo === 'Manual de Operações 2027' && documento.status === 'INDEXADO'
const manualAntigo = (documento: DocumentoDaApi) => documento.titulo === 'Manual de Operações 2027' && documento.status === 'SUBSTITUIDO'

const semRolagemHorizontal = (page: Page) => expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)

test('o chip abre o PDF na página citada, com o trecho destacado, e o Esc devolve o foco ao chip', async ({ page }) => {
  const { documento, trecho } = await trechoDoKit(manualAtual, 3)
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await conversaComFontes(page, parceria, [
    { tipo: 'usuario', texto: 'Quais são os marcos?' },
    {
      tipo: 'texto',
      texto: 'Os marcos estão na tabela do manual [T1].',
      porModelo: true,
      fontes: [{ marcador: 'T1', tipo: 'TRECHO', documentoId: documento.id, titulo: documento.titulo, versao: documento.versao, pagina: 3, secao: trecho.secao, trechoId: trecho.id, substituido: false, revogado: false }],
    },
  ])
  const chip = page.getByRole('button', { name: /^Abrir a fonte T1/ })
  await expect(chip).toBeVisible()
  await expect(page.getByRole('region', { name: 'Fontes' }).or(page.getByLabel('Fontes'))).toContainText('Manual de Operações 2027')
  await capturar(page, 'atlas-fontes-chip')

  await chip.click()
  const painel = page.getByRole('region', { name: /^Fonte T1/ })
  await expect(painel).toBeVisible()
  await expect(painel.getByRole('status').filter({ hasText: 'Página 3 de 11' })).toBeVisible({ timeout: 20_000 })
  await expect(painel.locator('canvas')).toBeVisible()
  await expect(painel.locator('.textLayer mark').first()).toBeAttached({ timeout: 20_000 })
  await expect(painel.getByText('Esta versão foi substituída')).toHaveCount(0)
  expect(await painel.locator('.textLayer mark').count()).toBeGreaterThan(0)
  await capturar(page, 'atlas-visualizador-pagina')

  await painel.getByRole('button', { name: 'Próxima página' }).click()
  await expect(painel.getByRole('status').filter({ hasText: 'Página 4 de 11' })).toBeVisible()
  await painel.getByRole('button', { name: 'Página anterior' }).click()
  await expect(painel.getByRole('status').filter({ hasText: 'Página 3 de 11' })).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(painel).toHaveCount(0)
  await expect(chip).toBeFocused()
})

test('a versão substituída abre com o aviso e o documento retirado não abre', async ({ page }) => {
  const antigo = await trechoDoKit(manualAntigo)
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await conversaComFontes(page, parceria, [
    { tipo: 'usuario', texto: 'E o manual antigo?' },
    {
      tipo: 'texto',
      texto: 'Estava no manual antigo [T1] e na circular [T2]. Isto [T9] não tem fonte.',
      porModelo: true,
      fontes: [
        { marcador: 'T1', tipo: 'TRECHO', documentoId: antigo.documento.id, titulo: antigo.documento.titulo, versao: antigo.documento.versao, pagina: antigo.trecho.paginaInicio, secao: null, trechoId: antigo.trecho.id, substituido: true, revogado: false },
        { marcador: 'T2', tipo: 'TRECHO', documentoId: 999999, titulo: 'Circular retirada', versao: 1, pagina: 1, secao: null, trechoId: 999999, substituido: false, revogado: true },
      ],
    },
  ])
  const fontes = page.getByLabel('Fontes')
  await expect(fontes.getByText('versão substituída')).toBeVisible()
  await expect(fontes.getByText('documento retirado')).toBeVisible()
  await expect(page.getByRole('button', { name: /^Abrir a fonte T2/ })).toHaveCount(0)
  await expect(page.getByText('[T9]')).toBeVisible()
  await page.getByRole('button', { name: /^Abrir a fonte T1/ }).click()
  await expect(page.getByRole('region', { name: /^Fonte T1/ }).getByText('Esta versão foi substituída')).toBeVisible()
  await capturar(page, 'atlas-visualizador-substituida')
})

test('o documento que sumiu mostra o erro da API, sem quebrar a conversa', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await conversaComFontes(page, parceria, [
    { tipo: 'usuario', texto: 'Pergunta' },
    { tipo: 'texto', texto: 'Resposta [T1].', porModelo: true, fontes: [{ marcador: 'T1', tipo: 'TRECHO', documentoId: 999999, titulo: 'Outro documento', versao: 1, pagina: 1, secao: null, trechoId: 1, substituido: false, revogado: false }] },
  ])
  await page.getByRole('button', { name: /^Abrir a fonte T1/ }).click()
  await expect(page.getByText('Não encontramos este documento.')).toBeVisible()
  await expect(page.getByText('Resposta')).toBeVisible()
})

test('a pergunta do gabarito vira chip, e o chip abre o documento na página citada @vivo', async ({ page }) => {
  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.goto(`/parceria/${parceria}/atlas`)
  const pergunta = 'Até quando preciso informar os geradores na Arena?'
  const campo = page.getByLabel('Mensagem para o Atlas')
  await campo.pressSequentially(pergunta)
  await expect(campo).toHaveValue(pergunta)
  await campo.press('Enter')
  await expect(page.getByText(pergunta).first()).toBeVisible()
  await expect(page.locator('form .animate-spin')).toHaveCount(0, { timeout: 85_000 })
  const chip = page.getByRole('button', { name: /^Abrir a fonte T\d/ }).first()
  await expect(chip).toBeVisible({ timeout: 20_000 })
  await expect(page.getByLabel('Fontes')).toContainText('Manual de Operações 2027')
  const nome = (await chip.getAttribute('aria-label')) ?? ''
  const citada = /página (\d+)/.exec(nome)?.[1]
  await chip.click()
  const painel = page.getByRole('region', { name: /^Fonte T\d/ })
  await expect(painel.getByRole('status').filter({ hasText: new RegExp(`Página ${citada} de \\d+`) })).toBeVisible({ timeout: 20_000 })
  await expect(painel.locator('.textLayer mark').first()).toBeAttached({ timeout: 20_000 })
  await capturar(page, 'atlas-fontes-vivo')
})

test.describe('no celular', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('o visualizador ocupa a tela, cabe nela e devolve a conversa ao fechar', async ({ page }) => {
    const { documento, trecho } = await trechoDoKit(manualAtual, 3)
    await entrar(page, 'amarelo')
    const parceria = await parceriaDa('amarelo')
    await conversaComFontes(page, parceria, [
      { tipo: 'usuario', texto: 'Quais são os marcos?' },
      {
        tipo: 'texto',
        texto: 'Os marcos estão na tabela do manual [T1].',
        porModelo: true,
        fontes: [{ marcador: 'T1', tipo: 'TRECHO', documentoId: documento.id, titulo: documento.titulo, versao: documento.versao, pagina: 3, secao: trecho.secao, trechoId: trecho.id, substituido: false, revogado: false }],
      },
    ])
    await semRolagemHorizontal(page)
    await capturar(page, 'atlas-fontes-celular')
    await page.getByRole('button', { name: /^Abrir a fonte T1/ }).click()
    const painel = page.getByRole('dialog', { name: /^Fonte T1/ })
    await expect(painel.getByRole('status').filter({ hasText: 'Página 3 de 11' })).toBeVisible({ timeout: 20_000 })
    await expect(painel.locator('.textLayer mark').first()).toBeAttached({ timeout: 20_000 })
    await semRolagemHorizontal(page)
    const caixa = await painel.boundingBox()
    expect(caixa?.width).toBeLessThanOrEqual(390)
    const canvas = await painel.locator('canvas').boundingBox()
    expect(canvas?.width).toBeLessThanOrEqual(390)
    await capturar(page, 'atlas-visualizador-celular')
    await painel.getByRole('button', { name: 'Fechar o painel' }).click()
    await expect(painel).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Abrir a fonte T1/ })).toBeVisible()
  })
})
