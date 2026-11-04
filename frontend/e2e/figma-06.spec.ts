// Fidelidade ao Figma 06 ("UI com a marca"): os elementos obrigatórios de cada tela que o plano final constrói (Apêndice A),
// por papel e nome acessível, e a captura em 1440×900 e 390×844 para comparar com o PNG do Figma. Cada tela fica como
// `fixme` até a fase dela; o corpo pendente lança erro, para o `fixme` não poder sair sem o teste.
import { expect, test } from '@playwright/test'
import { capturar, entrarComo, novaMarcaDeTeste, sessaoDaApi } from './apoio.ts'

const pendente = () => {
  throw new Error('pendente: escreva o teste ao construir a tela')
}

test.describe('Figma 06', () => {
  test.fixme('P01 Perfil da marca: plano comercial e compra de ingressos', pendente)
  test.fixme('R02 Perfil da RBS: permissões, direitos por contrato e as abas em três estados', pendente)
  test.fixme('P02 Aplicações: Selecionar logo e Selecionar MIV', pendente)
  test.fixme('P03 Mídia e conteúdo: publicados pela RBS, posts e links, comprovação mensal', pendente)
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
  test.fixme('P05 Credenciamento: sua liberação e Abrir Open Staff', pendente)
  test.fixme('P07 Contrato: visualizador, signatários e baixar PDF', pendente)
})
