// Fidelidade ao Figma 06 ("UI com a marca"): os elementos obrigatórios de cada tela que o plano final constrói (Apêndice A),
// por papel e nome acessível, e a captura em 1440×900 e 390×844 para comparar com o PNG do Figma.
import fs from 'node:fs'
import { expect, test } from '@playwright/test'
import { capturar, entrar, entrarComo, novaMarcaDeTeste, parceriaDa, sessaoDaApi } from './apoio.ts'

test.describe('Figma 06', () => {
  test('P01 Perfil da marca: plano comercial e compra de ingressos', async ({ page }) => {
    const amarelo = await parceriaDa('amarelo')
    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${amarelo}/perfil`)
    await expect(page.getByRole('heading', { name: 'Plano comercial' })).toBeVisible()
    await expect(page.getByText('Cadastro divergente?')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Compra de ingressos com valor de patrocinador' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Enviar solicitação' })).toBeVisible()
    await capturar(page, 'figma-06/P01-perfil-1440')
  })
  test('R02 Perfil da RBS: permissões, direitos por contrato e as abas em três estados', async ({ page }) => {
    const amarelo = await parceriaDa('amarelo')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${amarelo}/perfil`)
    await expect(page.getByRole('heading', { name: 'Direitos por contrato' })).toBeVisible()
    for (const coluna of ['Benefício / setor / dia', 'Quantidade', 'Visibilidade']) await expect(page.getByRole('columnheader', { name: coluna })).toBeVisible()
    await expect(page.getByText('Isolamento por patrocinador')).toBeVisible()
    await expect(page.getByText(/Visibilidade das 7 abas/)).toBeVisible()
    await expect(page.getByLabel('Situação da aba Cortesias')).toBeVisible()
    await expect(page.getByLabel('Situação da aba Cortesias').locator('option')).toHaveText(['Liberada', 'Fechada', 'Oculta'])
    await capturar(page, 'figma-06/R02-perfil-rbs-1440')
  })
  test('P02 Aplicações: Selecionar logo e Selecionar MIV', async ({ page }) => {
    const amarelo = await parceriaDa('amarelo')
    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${amarelo}/aplicacoes`)
    await expect(page.getByRole('heading', { name: 'Enviar materiais da marca' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Selecionar logo' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Selecionar MIV' })).toBeVisible()
    await expect(page.getByText('Arquivos enviados')).toBeVisible()
    await capturar(page, 'figma-06/P02-aplicacoes-1440')
  })
  test('P03 Mídia e conteúdo: publicados pela RBS, posts e links, comprovação mensal', async ({ page }) => {
    // Uma Master de teste com o relatório de veiculação publicado pela RBS (o cartão "Relatório de veiculação e links" do Figma).
    const marca = await novaMarcaDeTeste('MASTER')
    const { contexto, cabecalhos } = await sessaoDaApi('rbs')
    const entrega = await contexto.post(`/api/parcerias/${marca.parceriaId}/entregas`, {
      headers: cabecalhos,
      data: { abaCodigo: 'MIDIA', titulo: 'Relatório de veiculação', descricao: 'Publicado pela RBS.', status: 'AGUARDANDO_ENVIO' },
    })
    expect(entrega.status()).toBe(201)
    const publicado = await contexto.post(`/api/entregas/${(await entrega.json()).id}/arquivos`, {
      headers: cabecalhos,
      multipart: {
        arquivo: { name: 'relatorio-de-veiculacao-e-links.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: fs.readFileSync('../backend/src/main/resources/dev/exemplos/exemplo.xlsx') },
        categoria: 'RELATORIO_VEICULACAO',
      },
    })
    expect(publicado.status()).toBe(201)
    await contexto.dispose()

    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/midia`)
    await expect(page.getByText('Largue seus documentos aqui')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Selecionar arquivos' })).toBeVisible()
    await expect(page.getByPlaceholder(/Buscar por nome/)).toBeVisible()
    const filtros = page.getByRole('group', { name: 'Filtros' })
    for (const filtro of [/^Todos/, /^Enviados pela marca/, /^Publicados pela RBS/]) await expect(filtros.getByRole('button', { name: filtro })).toBeVisible()
    await expect(page.getByText('relatorio-de-veiculacao-e-links.xlsx').first()).toBeVisible()
    await expect(page.getByText('Publicado pela RBS · abrir / baixar')).toBeVisible()
    await expect(page.getByText('A regra muda por tipo de conteúdo')).toBeVisible()
    // O que o Figma não desenha e a RBS pede: os links dos posts e a comprovação mensal (só da Master).
    await expect(page.getByRole('heading', { name: 'Posts e links' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Comprovação mensal', exact: true })).toBeVisible()
    await capturar(page, 'figma-06/P03-midia-1440')
  })
  test('P04 Cortesias: destinação de ingressos', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    const { contexto, cabecalhos } = await sessaoDaApi('rbs')
    await contexto.patch(`/api/parcerias/${marca.parceriaId}/cortesias/cotas`, {
      headers: cabecalhos,
      data: { cotas: [{ setor: 'ARENA', dia: 'SEXTA', quantidade: 4, comShuttle: false, acesso: 'Portão 6' }] },
    })
    await contexto.dispose()
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/cortesias`)
    await expect(page.getByRole('status').filter({ hasText: 'Quantidades por setor, tipo e dia' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Destinação de ingressos' })).toBeVisible()
    for (const coluna of ['Setor', 'Tipo / dia', 'Quantidade']) await expect(page.getByRole('columnheader', { name: coluna }).first()).toBeVisible()
    await expect(page.getByText('Orientações de distribuição')).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: 'Liberação e destinação' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Destinar cortesias' })).toBeVisible()
    await capturar(page, 'figma-06/P04-cortesias-1440')
    await page.getByRole('button', { name: 'Destinar cortesias' }).click()
    const dialogo = page.getByRole('dialog', { name: 'Destinar cortesias' })
    for (const campo of ['Setor', 'Tipo / dia', 'Quantidade', 'Destino / destinatário']) await expect(dialogo.getByLabel(campo)).toBeVisible()
    await expect(dialogo.getByText('Disponibilidade')).toBeVisible()
    await expect(dialogo.getByText('Validação')).toBeVisible()
    await expect(dialogo.getByRole('button', { name: 'Voltar' })).toBeVisible()
    await expect(dialogo.getByRole('button', { name: 'Revisar destinação' })).toBeVisible()
    await capturar(page, 'figma-06/F04-destinar-1440')
  })
  test('P05 Credenciamento: sua liberação e Abrir Open Staff', async ({ page }) => {
    const roxo = await parceriaDa('roxo')
    await entrar(page, 'roxo')
    await page.goto(`/parceria/${roxo}/credenciamento`)
    await expect(page.getByRole('heading', { name: 'Sua liberação' })).toBeVisible()
    for (const coluna of ['Setor autorizado', 'Quantidade por dia']) await expect(page.getByRole('columnheader', { name: coluna })).toBeVisible()
    await expect(page.getByText('Período de cadastro:')).toBeVisible()
    await expect(page.getByRole('link', { name: /Abrir Open Staff/ })).toBeVisible()
    await expect(page.getByText('Você será direcionado a um serviço externo')).toBeVisible()
    await expect(page.getByText('Guia de credenciamento')).toBeVisible()
    await capturar(page, 'figma-06/P05-credenciamento-1440')
  })
  test('P07 Contrato: visualizador, signatários e baixar PDF', async ({ page }) => {
    const amarelo = await parceriaDa('amarelo')
    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${amarelo}/contrato`)
    await expect(page.getByText('Contrato final assinado')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Signatários' })).toBeVisible()
    await expect(page.getByText('Documento final')).toBeVisible()
    await expect(page.getByText(/Página 1 de \d+/)).toBeVisible({ timeout: 20_000 })
    await expect(page.getByRole('button', { name: 'Baixar PDF' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Abrir em tela cheia' })).toBeVisible()
    await capturar(page, 'figma-06/P07-contrato-1440')
  })
})
