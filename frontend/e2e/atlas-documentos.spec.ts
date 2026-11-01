// A aba Documentos da RBS: envio pela tela, aprovação, indexação, trechos, revogação, recusas e o celular.
// Roda na API com ATLAS_EMBEDDINGS=teste e o banco portal_e2e: cada envio usa uma chave nova, então pode repetir.
import { expect, test, type Page } from '@playwright/test'
import { capturar, entrar } from './apoio.ts'

const D08 = '../backend/src/main/resources/dev/documentos/d08-faq-patrocinador-2027.txt'

const semRolagemHorizontal = (page: Page) => expect(page.evaluate(() => document.documentElement.scrollWidth)).resolves.toBe(390)

async function abrirDocumentos(page: Page) {
  await entrar(page, 'rbs')
  await page.goto('/rbs/atlas?aba=documentos')
  await expect(page.getByRole('tab', { name: 'Documentos' })).toHaveAttribute('aria-selected', 'true')
}

/** Digita tecla por tecla, e confere que o valor ficou inteiro e o foco continuou no campo. */
async function digitar(page: Page, rotulo: string, texto: string) {
  const campo = page.getByLabel(rotulo, { exact: true })
  await campo.pressSequentially(texto)
  await expect(campo).toHaveValue(texto)
  await expect(campo).toBeFocused()
}

/** O nome vem do título e da versão, e a filtragem pelo título deixa a linha à vista em qualquer tamanho de lista. */
const linhaDe = (page: Page, titulo: string, versao = 1) => page.getByRole('listitem', { name: `${titulo}, versão ${versao}` })

test('a RBS sobe o FAQ pela tela, aprova, vê os trechos e revoga', async ({ page }) => {
  const titulo = `FAQ de ponta a ponta ${Date.now()}`
  await abrirDocumentos(page)

  await page.locator('input[type=file]').setInputFiles(D08)
  await digitar(page, 'Título', titulo)
  await digitar(page, 'Chave', `faq-e2e-${Date.now()}`)
  await page.getByLabel('Tipo').first().selectOption('FAQ')
  await page.getByRole('button', { name: 'Enviar', exact: true }).click()

  await expect(page.getByText(`${titulo}: versão 1 enviada`)).toBeVisible()
  await expect(page.getByLabel('Título', { exact: true })).toHaveValue('')
  const linha = linhaDe(page, titulo)
  await expect(linha.getByText('Aguardando aprovação')).toBeVisible()
  await expect(linha.getByRole('button', { name: 'Recusar' })).toBeVisible()
  await expect(linha.getByRole('button', { name: 'Ver trechos' })).toHaveCount(0)
  await capturar(page, 'atlas-documentos-enviado')

  await linha.getByRole('button', { name: 'Aprovar' }).click()
  const pedido = page.getByRole('dialog', { name: 'Aprovar o documento' })
  // A área de texto entra no texto do rótulo dela: aqui o rótulo não pode ser exato.
  const observacao = pedido.getByLabel('Observação (opcional)')
  await observacao.pressSequentially('Conferido no teste.')
  await expect(observacao).toHaveValue('Conferido no teste.')
  await expect(observacao).toBeFocused()
  await pedido.getByRole('button', { name: 'Aprovar', exact: true }).click()
  await expect(pedido).toHaveCount(0)

  // A tela não é em tempo real: o botão Atualizar busca de novo até a indexação terminar.
  await expect(async () => {
    await page.getByRole('button', { name: 'Atualizar' }).click()
    await expect(linha.getByText('Indexado')).toBeVisible({ timeout: 1000 })
  }).toPass({ timeout: 60_000 })
  await expect(linha.getByText('embeddings-de-teste · 768 dimensões')).toBeVisible()
  await capturar(page, 'atlas-documentos-indexado')

  await linha.getByRole('button', { name: 'Ver trechos' }).click()
  const trechos = page.getByRole('dialog', { name: 'Trechos do documento' })
  await expect(trechos.getByRole('listitem').first()).toContainText('tokens')
  await capturar(page, 'atlas-documentos-trechos')
  await trechos.getByRole('button', { name: 'Fechar' }).click()

  await linha.getByRole('button', { name: 'Revogar' }).click()
  const revogar = page.getByRole('dialog', { name: 'Revogar o documento' })
  await revogar.getByRole('button', { name: 'Revogar', exact: true }).click()
  await expect(linha.getByText('Revogado')).toBeVisible()
  await expect(linha.getByRole('button', { name: 'Revogar' })).toHaveCount(0)
})

test('o envio mostra a recusa junto do campo, e o arquivo falso no aviso', async ({ page }) => {
  await abrirDocumentos(page)

  await page.locator('input[type=file]').setInputFiles({ name: 'falso.pdf', mimeType: 'application/pdf', buffer: Buffer.from('MZ não é um PDF') })
  await page.getByRole('button', { name: 'Enviar', exact: true }).click()
  await expect(page.getByLabel('Título', { exact: true })).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByLabel('Chave')).toHaveAttribute('aria-invalid', 'true')

  await digitar(page, 'Título', 'Arquivo falso')
  await digitar(page, 'Chave', 'arquivo-falso')
  await page.getByRole('button', { name: 'Enviar', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'não é um PDF de verdade' })).toBeVisible()
  await expect(page.getByLabel('Título', { exact: true })).not.toHaveAttribute('aria-invalid', 'true')
})

test('o documento com dados pessoais não oferece Aprovar, e o que nasceu em erro não oferece Reindexar', async ({ page }) => {
  await abrirDocumentos(page)
  await page.getByLabel('Buscar pelo título').pressSequentially('Lista de credenciados')
  const lista = linhaDe(page, 'Lista de credenciados · Cliente Verde')
  await expect(lista.getByText('Parece ter dados pessoais')).toBeVisible()
  await expect(lista.getByRole('button', { name: 'Aprovar' })).toHaveCount(0)
  await expect(lista.getByRole('button', { name: 'Recusar' })).toBeVisible()

  await page.getByLabel('Buscar pelo título').fill('Aviso digitalizado')
  const aviso = linhaDe(page, 'Aviso digitalizado · pontos de apoio')
  await expect(aviso.getByText('Sem texto extraível; OCR fora do escopo.')).toBeVisible()
  await expect(aviso.getByRole('button', { name: 'Reindexar' })).toHaveCount(0)
})

test('a aba cabe no celular, com o formulário, a lista e os modais', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await abrirDocumentos(page)
  await expect(linhaDe(page, 'Manual de Operações 2027', 2)).toBeVisible()
  await semRolagemHorizontal(page)
  await capturar(page, 'atlas-documentos-390')

  await page.getByLabel('Quem enxerga').first().selectOption('PARCERIA')
  await expect(page.getByLabel('Parceria').first()).toBeVisible()
  await semRolagemHorizontal(page)

  const manual = linhaDe(page, 'Manual de Operações 2027', 2)
  await manual.getByRole('button', { name: 'Ver trechos' }).click()
  const trechos = page.getByRole('dialog', { name: 'Trechos do documento' })
  await expect(trechos.getByRole('listitem').first()).toBeVisible()
  await semRolagemHorizontal(page)
  await capturar(page, 'atlas-documentos-trechos-390')
  await trechos.getByRole('button', { name: 'Fechar' }).click()

  await manual.getByRole('button', { name: 'Revogar' }).click()
  const revogar = page.getByRole('dialog', { name: 'Revogar o documento' })
  await expect(revogar.getByRole('button', { name: 'Revogar', exact: true })).toBeInViewport()
  await semRolagemHorizontal(page)
  await revogar.getByRole('button', { name: 'Cancelar' }).click()
  await expect(manual.getByText('Indexado')).toBeVisible()
})
