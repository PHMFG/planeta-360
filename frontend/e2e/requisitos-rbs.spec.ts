// A matriz executável dos requisitos da RBS: um teste por requisito que tem prova na tela, com o ID no título.
// O ID vem da matriz do plano final (seção 3); e2e/matriz.tsv liga cada ID aos testes que o provam, e o conferir-matriz
// lê o resultado desta execução (test-results/resultado.json).
//
// Enquanto a fase do requisito não chega, o teste fica como `fixme` e o corpo lança erro: tirar o `fixme` sem escrever o
// teste deixa a execução vermelha. Nada aqui muda número do Amarelo, do Roxo ou do Verde: quem precisa escrever cria uma
// marca de teste pela API, na pilha.
import { expect, test, type Page } from '@playwright/test'
import { entrar, entrarComo, novaMarcaDeTeste, parceriaDa, sessaoDaApi } from './apoio.ts'

const pendente = () => {
  throw new Error('pendente: escreva o teste ao implementar o requisito')
}

test.describe('R1 · planos comerciais', () => {
  test('[R1.1] cota e investimento dos três clientes como a RBS informou', async () => {
    const { contexto } = await sessaoDaApi('rbs')
    const esperado = {
      amarelo: { cota: 'MASTER', valor: 1_000_000, base: 'BRUTO_TABELA' },
      roxo: { cota: 'SUBCOTA_G', valor: 500_000, base: 'LIQUIDO' },
      verde: { cota: 'SUBCOTA', valor: 100_000, base: 'LIQUIDO' },
    } as const
    for (const [persona, e] of Object.entries(esperado)) {
      const id = await parceriaDa(persona as keyof typeof esperado)
      const parceria = await (await contexto.get(`/api/parcerias/${id}`)).json()
      expect(parceria.cota.codigo, `cota de ${persona}`).toBe(e.cota)
      expect(Number(parceria.valorInvestimento), `investimento de ${persona}`).toBe(e.valor)
      expect(parceria.baseValor, `base do valor de ${persona}`).toBe(e.base)
    }
    await contexto.dispose()
  })
  test.fixme('[R1.2] cortesias e credenciais da amostra iguais aos planos', pendente)
  // R1.4 é provado no back (PlanoDoAmareloP07aP10Test): pela tela, a carga gravaria linhas no plano do Amarelo.
})

test.describe('R3 e R4 · comprovação mensal e formatos', () => {
  test.fixme('[R3.2] a Master baixa a comprovação mensal por mês, tipo e unidade', pendente)
  test.fixme('[R3.3] a comprovação mensal não aparece para Subcota G nem Subcota', pendente)
  test.fixme('[R4.1] .doc, .ppt e .xls sem macro entram; com macro, não', pendente)
  test.fixme('[R4.3] o link do post abre em aba nova, e javascript: é recusado', pendente)
})

test.describe('R5 · as abas', () => {
  test.fixme('[R5.1.b] a RBS sobe o plano comercial e a marca baixa a última versão', pendente)
  test.fixme('[R5.1.c] cada solicitação de ingressos tem a sua situação', pendente)
  test.fixme('[R5.2.a] a marca envia a logo e o MIV pelos botões próprios', pendente)
  test.fixme('[R5.3.a] a marca envia material em texto e em link', pendente)
  test.fixme('[R5.3.b] os links dos posts aparecem em Posts e links', pendente)
  test('[R5.4.a] a RBS grava a cota de cortesias com acesso e shuttle', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/cortesias`)
    await page.getByLabel('Quantidade de Arena · Sábado').fill('4')
    await page.getByLabel('Acesso de Arena · Sábado').pressSequentially('Portão 6')
    await page.getByLabel('Quantidade de Camarote · Sábado').fill('2')
    await page.getByLabel('Shuttle em Camarote · Sábado').check()
    await page.getByRole('button', { name: 'Salvar cota e prazo' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Cota e prazo salvos' })).toBeVisible()
    const { contexto } = await sessaoDaApi('rbs')
    const cortesias = await (await contexto.get(`/api/parcerias/${marca.parceriaId}/cortesias`)).json()
    await contexto.dispose()
    const arena = cortesias.cotas.find((c: { setor: string; dia: string }) => c.setor === 'ARENA' && c.dia === 'SABADO')
    const camarote = cortesias.cotas.find((c: { setor: string; dia: string }) => c.setor === 'CAMAROTE' && c.dia === 'SABADO')
    expect(arena).toMatchObject({ cota: 4, acesso: 'Portão 6', comShuttle: false })
    expect(camarote).toMatchObject({ cota: 2, comShuttle: true })
  })
  test('[R5.4.b] a marca vê cota, destinadas e disponíveis', async ({ page }) => {
    const marca = await marcaComCortesias()
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/cortesias`)
    const linha = tabelaDeCotas(page).getByRole('row').filter({ hasText: 'Arena' }).filter({ hasText: 'Sábado' })
    // Quantidade 4, destinadas 1, disponíveis 3: os mesmos números da rota 80.
    await expect(linha.getByRole('cell')).toHaveText(['Arena', 'Sábado', '4', '1', '3', 'Portão 6', '—'])
  })
  test('[R5.4.c] a marca destina dentro do disponível e é barrada acima dele', async ({ page }) => {
    const marca = await marcaComCortesias()
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/cortesias`)
    await page.getByRole('button', { name: 'Destinar cortesias' }).click()
    const dialogo = page.getByRole('dialog', { name: 'Destinar cortesias' })
    await dialogo.getByLabel('Setor').selectOption('ARENA')
    await dialogo.getByLabel('Tipo / dia').selectOption('SABADO')
    // Acima do disponível (3): o aviso de validação acusa, e o preenchimento fica.
    const quantidade = dialogo.getByLabel('Quantidade')
    await quantidade.pressSequentially('5')
    const destino = dialogo.getByLabel('Destino / destinatário')
    await destino.pressSequentially('Clientes VIP')
    await expect(destino).toHaveValue('Clientes VIP')
    await expect(dialogo.getByText(/passa do disponível \(3\)/)).toBeVisible()
    await dialogo.getByRole('button', { name: 'Revisar destinação' }).click()
    await expect(dialogo.getByRole('alert').filter({ hasText: 'Só restam 3' })).toBeVisible()
    await expect(quantidade).toHaveValue('5')
    await expect(destino).toHaveValue('Clientes VIP')
    // Dentro do disponível: revisa, confirma, e a tabela passa a 2 disponíveis.
    await quantidade.fill('1')
    await dialogo.getByRole('button', { name: 'Revisar destinação' }).click()
    await expect(dialogo.getByText(/sobram 2/)).toBeVisible()
    await dialogo.getByRole('button', { name: 'Confirmar destinação' }).click()
    await expect(page.getByRole('status').filter({ hasText: '1 cortesia destinada em Arena · Sábado' })).toBeVisible()
    const linha = tabelaDeCotas(page).getByRole('row').filter({ hasText: 'Arena' }).filter({ hasText: 'Sábado' })
    await expect(linha.getByRole('cell').nth(4)).toHaveText('2')
    await expect(page.getByRole('row').filter({ hasText: 'Clientes VIP' })).toBeVisible()
  })
  test.fixme('[R5.5.a] a marca vê setor, quantidade e período do credenciamento', pendente)
  test.fixme('[R5.5.b] o botão do Open Staff abre a URL da edição em aba nova', pendente)
  test.fixme('[R5.6.d] a RBS abre a pesquisa da edição pela tela', pendente)
  test.fixme('[R5.7.a] a marca informa quem assina o contrato', pendente)
  test.fixme('[R5.7.b] a RBS sobe o contrato assinado e a marca o abre no visualizador', pendente)
})

test.describe('R6 a R8 · admin, identidade e pós-venda', () => {
  test('[R6.a] a aba oculta some do menu da marca', async ({ page, browser }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    // A RBS oculta a aba Contrato pela tela do Perfil.
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/perfil`)
    await page.getByLabel('Situação da aba Contrato').selectOption('OCULTA')
    await expect(page.getByRole('status').filter({ hasText: 'foi ocultada' })).toBeVisible()
    // A marca não vê a aba no menu, e a rota dela mostra o aviso, sem o conteúdo.
    const outra = await browser.newPage()
    await entrarComo(outra, marca.email)
    const menu = outra.getByRole('navigation', { name: 'Menu principal' })
    await expect(menu.getByRole('link', { name: /Cortesias/ })).toBeVisible()
    await expect(menu.getByRole('link', { name: /Contrato/ })).toHaveCount(0)
    await outra.goto(`/parceria/${marca.parceriaId}/contrato`)
    await expect(outra.getByText(/fechada|não está disponível/i).first()).toBeVisible()
    await outra.close()
  })
  test('[R6.b] a aba fora da cota aparece com o motivo, sem liberar', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/perfil`)
    const pesquisa = page.getByLabel('Situação da aba Pesquisa')
    await expect(pesquisa.locator('option[value="LIBERADA"]')).toBeDisabled()
    await expect(page.getByText('A cota desta parceria não inclui esta aba.').first()).toBeVisible()
  })
  test('[R6.d1] a RBS edita o investimento e a base', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/perfil`)
    // Os campos de edição ficam no formulário da empresa, que abre no Editar.
    await page.getByRole('button', { name: 'Editar', exact: true }).click()
    const valor = page.getByLabel('Investimento (R$)')
    await valor.click()
    await valor.pressSequentially('250.000,50')
    await expect(valor).toHaveValue('250.000,50')
    await page.getByLabel('Base do valor').selectOption('BRUTO_TABELA')
    await page.getByRole('button', { name: 'Salvar empresa' }).click()
    const { contexto } = await sessaoDaApi('rbs')
    await expect
      .poll(async () => (await (await contexto.get(`/api/parcerias/${marca.parceriaId}`)).json()).valorInvestimento)
      .toBe(250000.5)
    expect((await (await contexto.get(`/api/parcerias/${marca.parceriaId}`)).json()).baseValor).toBe('BRUTO_TABELA')
    await contexto.dispose()
  })
  test.fixme('[R6.d2] Direitos por contrato mostra o mesmo que as abas', pendente)
  test.fixme('[R6.d3] a troca de cota mostra a prévia do impacto', pendente)
  test.fixme('[R7.1] o emblema de 2027 aparece no login e nos cabeçalhos', pendente)
  test.fixme('[R8.1] a marca vê o anexo de pós-venda com o relatório disponibilizado', pendente)
  test.fixme('[R8.2] os números da edição tiram o indicador de indisponível', pendente)
})

/** Uma marca de teste com cota de cortesias (Arena · sábado 4, Portão 6) e uma destinação de 1, gravadas pela API como a RBS. */
async function marcaComCortesias() {
  const marca = await novaMarcaDeTeste('SUBCOTA')
  const { contexto, cabecalhos } = await sessaoDaApi('rbs')
  const cotas = await contexto.patch(`/api/parcerias/${marca.parceriaId}/cortesias/cotas`, {
    headers: cabecalhos,
    data: { cotas: [{ setor: 'ARENA', dia: 'SABADO', quantidade: 4, comShuttle: false, acesso: 'Portão 6' }] },
  })
  expect(cotas.status()).toBe(200)
  const destino = await contexto.post(`/api/parcerias/${marca.parceriaId}/cortesias/destinacoes`, {
    headers: cabecalhos,
    data: { setor: 'ARENA', dia: 'SABADO', quantidade: 1, destinatario: 'Imprensa' },
  })
  expect(destino.status()).toBe(201)
  await contexto.dispose()
  return marca
}

/** Só a tabela "Destinação de ingressos": a das destinações também tem linhas com "Arena · Sábado". */
function tabelaDeCotas(page: Page) {
  return page.locator('section').filter({ has: page.getByRole('heading', { name: 'Destinação de ingressos' }) })
}
