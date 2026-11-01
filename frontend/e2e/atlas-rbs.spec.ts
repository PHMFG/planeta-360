// O Atlas da RBS: conversa por edição, sem parceria. A marca não chega nele.
import { expect, test } from '@playwright/test'
import { capturar, entrar, sessaoDaApi } from './apoio.ts'

test('a RBS abre a conversa da edição, e a resposta vem com a fonte', async ({ page }) => {
  await entrar(page, 'rbs')
  let itens: unknown[] = []
  const conversa = () => ({ conversaId: 777, llmDisponivel: true, relatorioDisponivel: false, debugDeFerramentas: false, diagnosticoDisponivel: false, itens })
  await page.route(/\/api\/eventos\/\d+\/atlas\/conversas$/, (rota) =>
    rota.request().method() === 'POST'
      ? rota.fulfill({ status: 201, json: conversa() })
      : rota.fulfill({ json: { llmDisponivel: true, conversas: [] } }),
  )
  await page.route(/\/api\/atlas\/conversas\/777$/, (rota) => rota.fulfill({ json: conversa() }))
  await page.route(/\/api\/atlas\/conversas\/777\/mensagens$/, (rota) => {
    itens = [
      { tipo: 'usuario', texto: 'Até quando as marcas informam os geradores?' },
      { tipo: 'texto', texto: 'Os geradores são informados até 04/12/2026 [T1].', porModelo: true, fontes: [{ marcador: 'T1', tipo: 'TRECHO', titulo: 'Manual de Operações', versao: 2, pagina: 5, secao: '4.3', documentoId: 1, trechoId: 1 }] },
    ]
    return rota.fulfill({ json: conversa() })
  })

  await page.goto('/rbs/atlas?aba=conversa')
  await expect(page.getByLabel('Edição')).toBeVisible()
  await expect(page.getByRole('button', { name: /Montar relatório/ })).toHaveCount(0)
  await expect(page.getByText('Só os documentos gerais da edição')).toBeVisible()
  const campo = page.getByRole('textbox').last()
  await campo.pressSequentially('Até quando as marcas informam os geradores?')
  await expect(campo).toHaveValue('Até quando as marcas informam os geradores?')
  await page.getByRole('button', { name: 'Enviar' }).click()
  await expect(page.getByText(/Os geradores são informados até 04\/12\/2026/)).toBeVisible()
  await capturar(page, 'atlas-rbs-conversa')
})

test('a marca não abre a conversa da RBS', async () => {
  const { contexto, cabecalhos } = await sessaoDaApi('amarelo')
  expect((await contexto.get('/api/eventos/1/atlas/conversas')).status()).toBe(403)
  expect((await contexto.post('/api/eventos/1/atlas/conversas', { headers: cabecalhos })).status()).toBe(403)
  await contexto.dispose()
})
