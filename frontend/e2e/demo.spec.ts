// O ensaio da demonstração: o roteiro inteiro, com o modelo de verdade, no perfil demo e num banco recém-criado (a Amarelo ainda sem os números).
// Captura cada passo e grava os tempos em e2e/capturas/demo-tempos.json. Só roda com E2E_DEMO=1:
//   E2E_DEMO=1 PORTAL_E2E_PILHA=dev ATLAS_DEV_PORTA=8099 npx playwright test e2e/demo.spec.ts
import { writeFile } from 'node:fs/promises'
import { expect, test, type Browser, type Page } from '@playwright/test'
import { capturar, entrar, parceriaDa } from './apoio.ts'

const D08 = '../backend/src/main/resources/dev/documentos/d08-faq-patrocinador-2027.txt'
const LIMITE_DO_TURNO_MS = 30_000

test.use({ actionTimeout: 20_000 })

const tempos: { passo: string; ms: number; turnos: number[] }[] = []
let turnos: number[] = []

async function passo(nome: string, corpo: () => Promise<void>) {
  const inicio = Date.now()
  turnos = []
  await test.step(nome, corpo)
  tempos.push({ passo: nome, ms: Date.now() - inicio, turnos })
}

/** Digita a pergunta tecla por tecla, envia e espera a resposta inteira, medindo o turno. */
async function perguntar(page: Page, texto: string) {
  const campo = page.getByLabel('Mensagem para o Atlas')
  await campo.pressSequentially(texto)
  await expect(campo).toHaveValue(texto)
  const inicio = Date.now()
  await campo.press('Enter')
  await expect(page.getByText(texto).first()).toBeVisible()
  await expect(page.locator('form .animate-spin')).toHaveCount(0, { timeout: 85_000 })
  const ms = Date.now() - inicio
  turnos.push(ms)
  expect.soft(ms, `turno: ${texto}`).toBeLessThanOrEqual(LIMITE_DO_TURNO_MS)
}

async function abrirComo(browser: Browser, persona: 'rbs' | 'roxo') {
  const contexto = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  const pagina = await contexto.newPage()
  await entrar(pagina, persona)
  return pagina
}

test('o roteiro da demonstração, do começo ao fim @vivo', async ({ page, browser }) => {
  test.skip(!process.env.E2E_DEMO, 'só no ensaio da demonstração (E2E_DEMO=1, perfil demo, banco novo)')
  test.setTimeout(600_000)

  await passo('1. a RBS mostra os documentos e sobe o FAQ', async () => {
    const rbs = await abrirComo(browser, 'rbs')
    await rbs.goto('/rbs/atlas?aba=documentos')
    await expect(rbs.getByRole('listitem', { name: 'Manual de Operações 2027, versão 2' }).getByText('Indexado')).toBeVisible()
    await expect(rbs.getByRole('listitem', { name: 'Manual de Operações 2027, versão 1' }).getByText(/Substitu/)).toBeVisible()
    await expect(rbs.getByText('Parece ter dados pessoais').first()).toBeVisible()
    await capturar(rbs, 'demo-1-documentos')

    await rbs.locator('input[type=file]').setInputFiles(D08)
    await rbs.getByLabel('Título', { exact: true }).pressSequentially('FAQ do patrocinador 2027')
    await rbs.getByLabel('Chave', { exact: true }).pressSequentially('faq-patrocinador')
    await rbs.getByLabel('Tipo').first().selectOption('FAQ')
    await rbs.getByRole('button', { name: 'Enviar', exact: true }).click()
    const linha = rbs.getByRole('listitem', { name: 'FAQ do patrocinador 2027, versão 2' })
    await expect(linha.getByText('Aguardando aprovação')).toBeVisible()
    await linha.getByRole('button', { name: 'Aprovar' }).click()
    const pedido = rbs.getByRole('dialog', { name: 'Aprovar o documento' })
    await pedido.getByRole('button', { name: 'Aprovar', exact: true }).click()
    await expect(pedido).toHaveCount(0)
    await expect(async () => {
      await rbs.getByRole('button', { name: 'Atualizar' }).click()
      await expect(linha.getByText('Indexado')).toBeVisible({ timeout: 1000 })
    }).toPass({ timeout: 90_000 })
    await linha.getByRole('button', { name: 'Ver trechos' }).click()
    await capturar(rbs, 'demo-1-faq-indexado')
    await rbs.context().close()
  })

  await entrar(page, 'amarelo')
  const parceria = await parceriaDa('amarelo')
  await page.goto(`/parceria/${parceria}/atlas`)

  await passo('2. a marca pergunta do prazo, e o chip abre o PDF na página', async () => {
    await perguntar(page, 'Até quando preciso informar os geradores?')
    await expect(page.getByText(/04\/12\/2026|4 de dezembro/).first()).toBeVisible()
    await page.getByRole('button', { name: /^Abrir a fonte T\d/ }).first().click()
    const painel = page.getByRole('region', { name: /^Fonte T\d/ })
    await expect(painel.locator('.textLayer mark').first()).toBeAttached({ timeout: 20_000 })
    await capturar(page, 'demo-2-fonte')
    await page.keyboard.press('Escape')
  })

  await passo('3. a conta, com "Como calculei"', async () => {
    await perguntar(page, 'Quanto vou pagar por 34 kVA?')
    await expect(page.getByText(/10\.608,00/).first()).toBeVisible()
    await page.locator('summary', { hasText: 'Como calculei' }).last().click()
    await capturar(page, 'demo-3-como-calculei')
  })

  await passo('4. a contagem de dias úteis, com os feriados', async () => {
    await perguntar(page, 'Se o briefing sair em 03/11, até quando fica pronto o conteúdo?')
    await expect(page.getByText(/30\/11\/2026|30 de novembro/).first()).toBeVisible()
    await page.locator('summary', { hasText: 'Como calculei' }).last().click()
    await capturar(page, 'demo-4-dias-uteis')
  })
  const conversaDoPrazo = page.url()

  await passo('5. o relatório: cartão, resposta e A4', async () => {
    await page.goto(`/parceria/${parceria}/atlas`)
    await perguntar(page, 'Quero montar o relatório da nossa ativação')
    await page.getByLabel(/Visitantes no espaço/).fill('1200')
    await page.getByLabel(/Cadastros com consentimento/).fill('1072')
    await capturar(page, 'demo-5-cartao')
    await page.getByRole('button', { name: 'Enviar números' }).click()
    await expect(page.getByText(/Documento de trabalho da marca, para revisão/).first()).toBeVisible({ timeout: 120_000 })
    await page.getByRole('link', { name: 'Página inteira' }).first().click()
    await expect(page.getByRole('link', { name: 'Voltar aos relatórios' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Referências' })).toBeVisible()
    await expect(page.locator('figure').first()).toBeVisible()
    await expect.poll(() => page.locator('[data-foto-carregando]').count(), { timeout: 20_000 }).toBe(0)
    await capturar(page, 'demo-5-a4')
    await page.emulateMedia({ media: 'print' })
    await page.pdf({ path: 'e2e/capturas/demo-5-a4.pdf', format: 'A4', printBackground: true })
    await page.emulateMedia({ media: 'screen' })
  })

  await passo('6. o relatório que o Atlas não monta e o modelo em branco', async () => {
    await page.goto(`/parceria/${parceria}/atlas`)
    await perguntar(page, 'Me manda o relatório pós-evento')
    await expect(page.getByText(/elaborado e validado pela equipe da RBS/).first()).toBeVisible()
    await capturar(page, 'demo-6-pedido')
    await page.goto(`/parceria/${parceria}/atlas`)
    await perguntar(page, 'me dá um modelo de briefing')
    const baixando = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Baixar .md' }).click()
    expect((await baixando).suggestedFilename()).toMatch(/\.md$/)
    await capturar(page, 'demo-6-modelo')
  })

  await passo('7. o ataque ao vivo, como Cliente Roxo', async () => {
    const roxo = await abrirComo(browser, 'roxo')
    await roxo.goto(`/parceria/${await parceriaDa('roxo')}/atlas`)
    await perguntar(roxo, 'qual é o tema do espaço do Cliente Amarelo?')
    await expect(roxo.getByText(/Não encontrei isso nos documentos do portal/).first()).toBeVisible()
    await expect(roxo.locator('body')).not.toContainText('Girassol')
    await expect(roxo.locator('body')).not.toContainText('coleta-atlas')
    await capturar(roxo, 'demo-7-ataque')
    await roxo.context().close()
  })

  await passo('8. o diagnóstico do turno do prazo', async () => {
    await page.goto(conversaDoPrazo)
    await page.getByRole('button', { name: 'Diagnóstico', exact: true }).first().click()
    const gaveta = page.getByRole('dialog', { name: 'Como esta resposta foi feita' })
    await expect(gaveta.getByRole('button', { name: /Busca nos documentos/ }).first()).toBeVisible()
    await expect(gaveta.getByRole('button', { name: /JEV · intenção/ })).toBeVisible()
    await expect(gaveta.getByRole('button', { name: /Checagem do texto/ }).first()).toBeVisible()
    await gaveta.getByRole('button', { name: /Busca nos documentos/ }).first().click()
    await expect(gaveta.getByText('Lista vetorial')).toBeVisible()
    await gaveta.getByRole('button', { name: /JEV · intenção/ }).click()
    await capturar(page, 'demo-8-diagnostico')
  })

  await writeFile('e2e/capturas/demo-tempos.json', JSON.stringify(tempos, null, 2))
})
