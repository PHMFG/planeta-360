// A matriz executável dos requisitos da RBS: um teste por requisito que tem prova na tela, com o ID no título.
// O ID vem da matriz do plano final (seção 3); e2e/matriz.tsv liga cada ID aos testes que o provam, e o conferir-matriz
// lê o resultado desta execução (test-results/resultado.json).
//
// Enquanto a fase do requisito não chega, o teste fica como `fixme` e o corpo lança erro: tirar o `fixme` sem escrever o
// teste deixa a execução vermelha. Nada aqui muda número do Amarelo, do Roxo ou do Verde: quem precisa escrever cria uma
// marca de teste pela API, na pilha.
import fs from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { entrar, entrarComo, novaMarcaDeTeste, parceriaDa, sessaoDaApi, sessaoDaApiComo } from './apoio.ts'

const PDF_DE_EXEMPLO = '../backend/src/main/resources/dev/documentos/d13-circular-revogada.pdf'

const PNG_DE_EXEMPLO = '../backend/src/main/resources/dev/exemplos/exemplo.png'

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
  test('[R1.2] cortesias e credenciais da amostra iguais aos planos', async () => {
    // Os números dos PDFs da RBS: Roxo (p. 8) 4 na Arena e 2 no Camarote por dia, 5 credenciais; Verde (p. 2) 4, 2 e 10.
    const esperado = { roxo: { ARENA: 4, CAMAROTE: 2, credenciais: 5 }, verde: { ARENA: 4, CAMAROTE: 2, credenciais: 10 } } as const
    const { contexto } = await sessaoDaApi('rbs')
    for (const [persona, numeros] of Object.entries(esperado)) {
      const id = await parceriaDa(persona as keyof typeof esperado)
      const cortesias = await (await contexto.get(`/api/parcerias/${id}/cortesias`)).json()
      for (const setor of ['ARENA', 'CAMAROTE'] as const) {
        const celulas = cortesias.cotas.filter((c: { setor: string }) => c.setor === setor)
        expect(celulas.length, `${persona} tem cota em ${setor}`).toBeGreaterThan(0)
        for (const celula of celulas) expect(celula.cota, `${persona} ${setor} ${celula.dia}`).toBe(numeros[setor])
      }
      const credenciais = await (await contexto.get(`/api/parcerias/${id}/credenciais`)).json()
      expect(credenciais.totalPorDia, `credenciais por dia de ${persona}`).toBe(numeros.credenciais)
    }
    await contexto.dispose()
  })
  // R1.4 é provado no back (PlanoDoAmareloP07aP10Test): pela tela, a carga gravaria linhas no plano do Amarelo.
})

test.describe('R2 · manual de operações', () => {
  test('[R2.1] a RBS vê a página que travou e a deixa fora do Atlas', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    const { contexto } = await sessaoDaApi('rbs')
    const eventos: { id: number; edicao: number }[] = await (await contexto.get('/api/eventos')).json()
    const edicao = eventos.find((evento) => evento.edicao === 2027) ?? eventos[0]
    await contexto.dispose()
    const sufixo = `${Date.now()}`
    const titulo = `Contatos de teste ${sufixo}`
    const pdf = pdfDeTeste([
      'Manual de teste. Horarios de montagem e regras de acesso ao Planeta.',
      'Contatos chave: (51) 3000-1111 (51) 3000-2222 (51) 3000-3333 (51) 3000-4444',
      'Regras de som e de seguranca para as marcas.',
    ])

    await entrar(page, 'rbs')
    await page.goto('/rbs/atlas')
    await page.getByRole('tab', { name: 'Documentos' }).click()
    const envio = page.locator('form').filter({ has: page.getByLabel('Chave') })
    await envio.locator('input[type=file]').setInputFiles({ name: `contatos-${sufixo}.pdf`, mimeType: 'application/pdf', buffer: pdf })
    await envio.getByLabel('Título').fill(titulo)
    await envio.getByLabel('Chave').fill(`contatos-${sufixo}`)
    await envio.getByLabel(/^Quem enxerga/).selectOption('PARCERIA')
    await envio.getByLabel(/^Edição/).selectOption(String(edicao.id))
    await envio.getByLabel(/^Parceria/).selectOption(String(marca.parceriaId))
    // Página além do total: o erro aparece junto do campo, e nada é gravado.
    const paginas = envio.getByLabel('Páginas fora do Atlas (opcional)')
    await paginas.click()
    await paginas.pressSequentially('9')
    await expect(paginas).toHaveValue('9')
    await envio.getByRole('button', { name: 'Enviar', exact: true }).click()
    await expect(envio.getByText(/vão de 1 a 3/)).toBeVisible()
    await paginas.fill('')
    await envio.getByRole('button', { name: 'Enviar', exact: true }).click()
    // Sem marcar, a aprovação trava, e a tela diz onde: a página e o tipo, nunca o número achado.
    await expect(envio.getByText(/Onde: p\. 2 \(lista de telefones ou e-mails\)/)).toBeVisible()
    await expect(page.getByText('3000-1111')).toHaveCount(0)

    await page.getByPlaceholder('Buscar pelo título').fill(titulo)
    const linha = page.getByRole('listitem', { name: `${titulo}, versão 1` })
    await expect(linha.getByText(/na p\. 2 \(lista de telefones ou e-mails\)/)).toBeVisible()
    await expect(linha.getByRole('button', { name: 'Aprovar' })).toHaveCount(0)
    await linha.getByRole('button', { name: 'Páginas fora do Atlas' }).click()
    const dialogo = page.getByRole('dialog', { name: 'Páginas fora do Atlas' })
    await expect(dialogo.getByLabel('Páginas')).toHaveValue('2')
    await dialogo.getByRole('button', { name: 'Salvar páginas' }).click()
    await expect(dialogo).toHaveCount(0)
    await expect(linha.getByText('Fora do Atlas: p. 2.')).toBeVisible()
    await expect(linha.getByRole('button', { name: 'Aprovar' })).toBeVisible()
  })
})

test.describe('R3 e R4 · comprovação mensal e formatos', () => {
  test('[R3.2] a Master baixa a comprovação mensal por mês, tipo e unidade', async ({ page }) => {
    const amarelo = await parceriaDa('amarelo')
    const { contexto } = await sessaoDaApi('amarelo')
    const resposta = await contexto.get(`/api/parcerias/${amarelo}/comprovacao-mensal`)
    expect(resposta.status()).toBe(200)
    const comprovacao = (await resposta.json()) as { linhas: { mes: string; tipoMidiaNome: string; unidadeNome: string; exibido: number }[] }
    await contexto.dispose()
    expect(new Set(comprovacao.linhas.map((linha) => linha.mes)).size, 'a amostra tem exibição em mais de um mês').toBeGreaterThan(1)

    await entrar(page, 'amarelo')
    await page.goto(`/parceria/${amarelo}/midia`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Comprovação mensal', exact: true }) })
    const linhas = cartao.locator('tbody tr')
    await expect(linhas).toHaveCount(comprovacao.linhas.length)
    // O filtro do mês deixa só as linhas daquele mês, com o mesmo número da API (mês x tipo x unidade).
    const escolhida = comprovacao.linhas[comprovacao.linhas.length - 1]
    await cartao.getByLabel('Mês').selectOption(escolhida.mes)
    await expect(linhas).toHaveCount(comprovacao.linhas.filter((linha) => linha.mes === escolhida.mes).length)
    const linha = linhas.filter({ hasText: escolhida.tipoMidiaNome }).filter({ hasText: escolhida.unidadeNome })
    await expect(linha).toHaveCount(1)
    await expect(linha.locator('td').nth(3)).toHaveText(new Intl.NumberFormat('pt-BR').format(escolhida.exibido))

    const [xlsx] = await Promise.all([page.waitForEvent('download'), cartao.getByRole('button', { name: 'Baixar planilha (.xlsx)' }).click()])
    expect(xlsx.suggestedFilename()).toBe('comprovacao-mensal-cliente-amarelo.xlsx')
    expect(fs.readFileSync(await xlsx.path()).subarray(0, 2).toString('latin1'), 'um .xlsx é um zip').toBe('PK')
    const [csv] = await Promise.all([page.waitForEvent('download'), cartao.getByRole('button', { name: 'Baixar CSV' }).click()])
    const texto = fs.readFileSync(await csv.path(), 'utf8')
    expect(texto.charCodeAt(0), 'CSV com BOM, para o Excel ler os acentos').toBe(0xfeff)
    expect(texto.split('\n')[0]).toContain(';')
  })
  test('[R3.3] a comprovação mensal não aparece para Subcota G nem Subcota', async ({ page }) => {
    for (const persona of ['roxo', 'verde'] as const) {
      const id = await parceriaDa(persona)
      const { contexto } = await sessaoDaApi(persona)
      for (const sufixo of ['', '.xlsx', '.csv']) {
        const recusa = await contexto.get(`/api/parcerias/${id}/comprovacao-mensal${sufixo}`)
        expect(recusa.status(), `${persona}${sufixo}`).toBe(403)
        expect((await recusa.json()).detail).toBe('A comprovação mensal é das cotas Master.')
      }
      expect((await contexto.get('/api/eventos/1/comprovacao-mensal.xlsx')).status(), 'o lote é só da RBS').toBe(403)
      await contexto.dispose()
      await page.context().clearCookies()
      await entrar(page, persona)
      await page.goto(`/parceria/${id}/midia`)
      await expect(page.getByRole('heading', { name: 'Posts e links' })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Comprovação mensal', exact: true })).toHaveCount(0)
    }
    // A RBS baixa o lote da edição em Cargas: um .xlsx com uma aba por Master.
    await page.context().clearCookies()
    await entrar(page, 'rbs')
    await page.goto('/rbs/cargas')
    const [lote] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Baixar comprovação das Master' }).click()])
    expect(lote.suggestedFilename()).toMatch(/^comprovacao-mensal-edicao-\d{4}\.xlsx$/)
    expect(fs.readFileSync(await lote.path()).subarray(0, 2).toString('latin1')).toBe('PK')
  })
  test.fixme('[R4.1] .doc, .ppt e .xls sem macro entram; com macro, não', pendente)
  test('[R4.3] o link do post abre em aba nova, e javascript: é recusado', async ({ page }) => {
    const roxo = await parceriaDa('roxo')
    await entrar(page, 'roxo')
    await page.goto(`/parceria/${roxo}/midia`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Posts e links' }) })
    const link = cartao.getByRole('link', { name: /instagram\.com/ }).first()
    await expect(link).toHaveAttribute('href', /^https:\/\/www\.instagram\.com\//)
    await expect(link).toHaveAttribute('target', '_blank')
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    // A API recusa um link que não é http(s) no envio de material.
    const marca = await novaMarcaDeTeste('SUBCOTA')
    const entregaId = await entregaDeMidia(marca.parceriaId)
    const { contexto, cabecalhos } = await sessaoDaApi('rbs')
    const recusa = await contexto.post(`/api/entregas/${entregaId}/conteudos`, { headers: cabecalhos, data: { tipo: 'LINK', url: 'javascript:alert(1)' } })
    expect(recusa.status()).toBe(422)
    await contexto.dispose()
  })
})

test.describe('R5 · as abas', () => {
  test('[R5.1.b] a RBS sobe o plano comercial e a marca baixa a última versão', async ({ page, browser }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/perfil`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Plano comercial' }) })
    await cartao.locator('input[data-espaco="PLANO_COMERCIAL"]').setInputFiles(PDF_DE_EXEMPLO)
    await expect(cartao.getByRole('status').filter({ hasText: 'Plano enviado' })).toContainText('versão 1')
    await cartao.locator('input[data-espaco="PLANO_COMERCIAL"]').setInputFiles(PDF_DE_EXEMPLO)
    await expect(cartao.getByRole('status').filter({ hasText: 'Plano enviado' })).toContainText('versão 2')
    const outra = await browser.newPage()
    await entrarComo(outra, marca.email)
    await outra.goto(`/parceria/${marca.parceriaId}/perfil`)
    const daMarca = outra.locator('section').filter({ has: outra.getByRole('heading', { name: 'Plano comercial' }) })
    await expect(daMarca).toContainText('versão 2')
    await expect(daMarca.getByText('Somente consulta')).toBeVisible()
    await expect(daMarca.getByRole('button', { name: 'Baixar plano' })).toBeVisible()
    await expect(daMarca.getByText('Versões anteriores (1)')).toBeVisible()
    // A marca não envia o plano: não há botão, e a rota responde 403.
    await expect(daMarca.getByRole('button', { name: /Enviar/ })).toHaveCount(0)
    await outra.close()
  })
  test('[R5.1.c] cada solicitação de ingressos tem a sua situação', async ({ page, browser }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    const daMarca = await browser.newPage()
    await entrarComo(daMarca, marca.email)
    await daMarca.goto(`/parceria/${marca.parceriaId}/perfil`)
    const cartao = daMarca.locator('section').filter({ has: daMarca.getByRole('heading', { name: 'Compra de ingressos com valor de patrocinador' }) })
    for (const vez of [1, 2]) {
      await cartao.locator('input[data-espaco="DOCUMENTO_INGRESSOS"]').setInputFiles(PDF_DE_EXEMPLO)
      await expect(cartao.getByRole('row').filter({ hasText: `Solicitação de ingressos ${vez}` })).toContainText('Em análise pela RBS')
    }
    // A RBS confirma só a primeira.
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/perfil`)
    await page.getByRole('button', { name: 'Confirmar a Solicitação de ingressos 1' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'confirmada' })).toBeVisible()
    await daMarca.reload()
    await expect(cartao.getByRole('row').filter({ hasText: 'Solicitação de ingressos 1' })).toContainText('Confirmada')
    await expect(cartao.getByRole('row').filter({ hasText: 'Solicitação de ingressos 2' })).toContainText('Em análise pela RBS')
    // Depois de uma confirmada, a marca envia outra.
    await cartao.locator('input[data-espaco="DOCUMENTO_INGRESSOS"]').setInputFiles(PDF_DE_EXEMPLO)
    await expect(cartao.getByRole('row').filter({ hasText: 'Solicitação de ingressos 3' })).toBeVisible()
    await daMarca.close()
  })
  test('[R5.2.a] a marca envia a logo e o MIV pelos botões próprios', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/aplicacoes`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Enviar materiais da marca' }) })
    await expect(cartao.getByRole('button', { name: 'Selecionar logo' })).toBeVisible()
    await expect(cartao.getByRole('button', { name: 'Selecionar MIV' })).toBeVisible()
    await cartao.locator('input[data-espaco="LOGO"]').setInputFiles(PNG_DE_EXEMPLO)
    await expect(cartao.getByRole('status').filter({ hasText: 'recebido' })).toContainText('Logo recebido como versão 1')
    await cartao.locator('input[data-espaco="LOGO"]').setInputFiles(PNG_DE_EXEMPLO)
    await expect(cartao.getByRole('status').filter({ hasText: 'recebido' })).toContainText('Logo recebido como versão 2')
    await cartao.locator('input[data-espaco="MIV"]').setInputFiles(PDF_DE_EXEMPLO)
    await expect(cartao.getByRole('status').filter({ hasText: 'recebido' })).toContainText('MIV recebido como versão 1')
    await expect(cartao.getByText('Arquivos enviados')).toBeVisible()
    // .svg solto é recusado, com a frase que diz o caminho.
    await cartao.locator('input[data-espaco="LOGO"]').setInputFiles({ name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>') })
    await expect(cartao.getByRole('alert').filter({ hasText: /dentro de um \.zip/ })).toBeVisible()
  })
  test('[R5.3.a] a marca envia material em texto e em link', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entregaDeMidia(marca.parceriaId)
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/midia`)
    await page.getByText('Post de lançamento').first().click()
    const detalhe = page.getByRole('dialog', { name: 'Post de lançamento' })
    await detalhe.getByRole('radio', { name: 'Texto' }).click()
    const texto = detalhe.getByLabel('Texto do material')
    await texto.click()
    await texto.pressSequentially('Legenda: Planeta 2027 com a marca no palco.')
    await expect(texto).toHaveValue('Legenda: Planeta 2027 com a marca no palco.')
    await expect(texto).toBeFocused()
    await detalhe.getByRole('button', { name: 'Enviar texto' }).click()
    await expect(detalhe.getByText('v1 · Texto')).toBeVisible()
    await expect(detalhe.getByText('Legenda: Planeta 2027 com a marca no palco.')).toBeVisible()
    await detalhe.getByRole('radio', { name: 'Link' }).click()
    const endereco = detalhe.getByLabel('Endereço do post ou do material')
    await endereco.pressSequentially('nao-e-link')
    await detalhe.getByRole('button', { name: 'Enviar link' }).click()
    await expect(detalhe.getByRole('alert').filter({ hasText: 'https://' })).toBeVisible()
    await endereco.fill('https://www.tiktok.com/@marcateste/video/123')
    await detalhe.getByRole('button', { name: 'Enviar link' }).click()
    await expect(detalhe.getByText('v2 · Link')).toBeVisible()
    await expect(detalhe.getByRole('link', { name: /tiktok\.com\/@marcateste/ })).toHaveAttribute('rel', 'noopener noreferrer')
  })
  test('[R5.3.b] os links dos posts aparecem em Posts e links', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    const entregaId = await entregaDeMidia(marca.parceriaId)
    // A RBS envia o link do post publicado como material da entrega.
    const { contexto, cabecalhos } = await sessaoDaApi('rbs')
    const envio = await contexto.post(`/api/entregas/${entregaId}/conteudos`, {
      headers: cabecalhos,
      data: { tipo: 'LINK', url: 'https://www.instagram.com/p/TESTE123/', titulo: 'Post publicado' },
    })
    expect(envio.status()).toBe(201)
    await contexto.dispose()
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/midia`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Posts e links' }) })
    await expect(cartao.getByRole('link', { name: /instagram\.com/ })).toHaveAttribute('href', 'https://www.instagram.com/p/TESTE123/')
    await expect(cartao).toContainText('Enviado por')
    // A marca só vê e clica: não há como editar o link enviado.
    await expect(cartao.getByRole('button')).toHaveCount(0)
  })
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
  test('[R5.5.a] a marca vê setor, quantidade e período do credenciamento', async ({ page, browser }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    // A RBS grava as credenciais pela tela da aba.
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/credenciamento`)
    await page.getByLabel('Setor').selectOption('ARENA')
    await page.getByLabel('Para').selectOption('MARCA')
    await page.getByLabel('Quantidade por dia', { exact: true }).fill('7')
    await page.getByRole('button', { name: 'Salvar credenciais' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Credenciais salvas' })).toBeVisible()
    // A marca vê a liberação com o setor, a quantidade e o período da edição (01 a 20/12/2026 na amostra).
    const outra = await browser.newPage()
    await entrarComo(outra, marca.email)
    await outra.goto(`/parceria/${marca.parceriaId}/credenciamento`)
    const liberacao = outra.locator('section').filter({ has: outra.getByRole('heading', { name: 'Sua liberação' }) })
    await expect(liberacao.getByRole('row').filter({ hasText: 'Arena' }).getByRole('cell')).toHaveText(['Arena', 'Marca', '7'])
    await expect(liberacao).toContainText('Período de cadastro: de 01/12/2026 a 20/12/2026')
    await outra.close()
  })
  test('[R5.5.b] o botão do Open Staff abre a URL da edição em aba nova', async ({ page }) => {
    const verde = await parceriaDa('verde')
    await entrar(page, 'verde')
    await page.goto(`/parceria/${verde}/credenciamento`)
    const botao = page.getByRole('link', { name: /Abrir Open Staff/ })
    await expect(botao).toHaveAttribute('href', 'https://example.com/')
    await expect(botao).toHaveAttribute('target', '_blank')
    await expect(botao).toHaveAttribute('rel', 'noopener noreferrer')
    await expect(page.getByText('Você será direcionado a um serviço externo')).toBeVisible()
    // A API recusa endereço que não é https (o front também, antes de enviar).
    const { contexto, cabecalhos } = await sessaoDaApi('rbs')
    const eventos: { id: number; edicao: number }[] = await (await contexto.get('/api/eventos')).json()
    const recusa = await contexto.patch(`/api/eventos/${eventos.find((e) => e.edicao === 2027)!.id}/credenciamento`, {
      headers: cabecalhos,
      data: { openStaffUrl: 'javascript:alert(1)' },
    })
    expect(recusa.status()).toBe(422)
    await contexto.dispose()
  })
  test('[R5.6.d] a RBS abre a pesquisa da edição pela tela', async ({ page }) => {
    // Uma Master de teste envia as perguntas; a RBS decide na fila da tela, sem abrir a aba da marca.
    const marca = await novaMarcaDeTeste('MASTER')
    const daMarca = await sessaoDaApiComo(marca.email)
    const envio = await daMarca.contexto.put(`/api/parcerias/${marca.parceriaId}/pesquisa/perguntas`, {
      headers: daMarca.cabecalhos,
      data: { perguntas: [{ ordem: 1, texto: 'Você visitou o espaço da marca de teste?' }, { ordem: 2, texto: 'Você conhecia a marca de teste antes do festival?' }] },
    })
    expect(envio.status(), 'a marca de teste envia as perguntas').toBe(200)
    await daMarca.contexto.dispose()
    const { contexto } = await sessaoDaApi('rbs')
    const eventos: { id: number; edicao: number }[] = await (await contexto.get('/api/eventos')).json()
    const edicao2027 = eventos.find((evento) => evento.edicao === 2027)!
    const edicao2026 = eventos.find((evento) => evento.edicao === 2026)!
    const pesquisas2026: { escopo: string }[] = await (await contexto.get(`/api/eventos/${edicao2026.id}/pesquisas`)).json()
    await contexto.dispose()

    await entrar(page, 'rbs')
    await page.getByRole('navigation').getByRole('link', { name: 'Pesquisa' }).click()
    await expect(page).toHaveURL(/\/rbs\/pesquisa$/)
    await page.getByLabel(/^Edição/).selectOption(String(edicao2027.id))
    await expect(page.getByRole('listitem', { name: 'Pesquisa nos espaços de ativação 2027' })).toBeVisible()

    // A fila das perguntas de todas as marcas: aprovar uma e pedir ajuste na outra.
    const fila = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Perguntas das marcas' }) })
    await fila.getByRole('button', { name: `Aprovar a pergunta 1 de ${marca.nome}` }).click()
    await expect(page.getByRole('status').filter({ hasText: `Pergunta 1 de ${marca.nome} aprovada.` })).toBeVisible()
    await fila.getByRole('button', { name: `Pedir ajuste na pergunta 2 de ${marca.nome}` }).click()
    const ajuste = page.getByRole('dialog', { name: 'Pedir ajuste na pergunta' })
    const recado = ajuste.getByLabel('O que a marca precisa ajustar')
    await recado.click()
    await recado.pressSequentially('Troque "conhecia" por "já conhecia".')
    await expect(recado).toHaveValue('Troque "conhecia" por "já conhecia".')
    await expect(recado).toBeFocused()
    await ajuste.getByRole('button', { name: 'Pedir ajuste' }).click()
    await expect(page.getByRole('status').filter({ hasText: `Ajuste pedido na pergunta 2 de ${marca.nome}.` })).toBeVisible()
    await expect(fila.getByRole('row').filter({ hasText: marca.nome })).toHaveCount(0)

    // 2027 já tem a da ativação: ao abrir, ela vem desabilitada.
    await page.getByRole('button', { name: 'Abrir pesquisa' }).click()
    const dialogo = page.getByRole('dialog', { name: 'Abrir pesquisa' })
    await expect(dialogo.getByRole('option', { name: /Ativação \(por marca\) · já aberta/ })).toHaveAttribute('disabled', '')
    await dialogo.getByRole('button', { name: 'Cancelar' }).click()

    // 2026 só tem a geral: a RBS abre a da ativação pela tela (numa pilha já usada, abre outra geral, com nome novo).
    await page.getByLabel(/^Edição/).selectOption(String(edicao2026.id))
    const semAtivacao = !pesquisas2026.some((pesquisa) => pesquisa.escopo === 'ATIVACAO')
    if (semAtivacao) await expect(page.getByText('A pesquisa da ativação de 2026 ainda não foi aberta')).toBeVisible()
    await page.getByRole('button', { name: 'Abrir pesquisa' }).click()
    const nome = semAtivacao ? 'Pesquisa nos espaços de ativação 2026' : `Pesquisa geral de teste ${Date.now()}`
    const campoNome = dialogo.getByLabel('Nome')
    await campoNome.fill('')
    await campoNome.pressSequentially(nome)
    await expect(campoNome).toHaveValue(nome)
    if (!semAtivacao) await dialogo.getByLabel(/^Escopo/).selectOption('GERAL')
    await dialogo.getByLabel('Base de respondentes (opcional)').pressSequentially('380')
    await dialogo.getByLabel('Nível de confiança, em % (opcional)').pressSequentially('95')
    await dialogo.getByRole('button', { name: 'Abrir pesquisa' }).click()
    await expect(page.getByRole('status').filter({ hasText: /^Pesquisa aberta/ })).toBeVisible()
    await expect(page.getByRole('listitem', { name: nome })).toContainText('380')
  })
  test('[R5.7.a] a marca informa quem assina o contrato', async ({ page }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrarComo(page, marca.email)
    await page.goto(`/parceria/${marca.parceriaId}/contrato`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Signatários' }) })
    const nome = cartao.getByLabel('Nome de quem assina')
    await nome.click()
    await nome.pressSequentially('Fulana de Teste')
    await expect(nome).toHaveValue('Fulana de Teste')
    await expect(nome).toBeFocused()
    await cartao.getByLabel('Cargo').pressSequentially('Diretora de Marketing')
    await cartao.getByLabel('E-mail').pressSequentially('fulana@marca-teste')
    await cartao.getByRole('button', { name: 'Salvar signatário' }).click()
    await expect(cartao.getByRole('alert').filter({ hasText: 'formato do e-mail' })).toBeVisible()
    await cartao.getByLabel('E-mail').pressSequentially('.example')
    await cartao.getByRole('button', { name: 'Salvar signatário' }).click()
    await expect(cartao).toContainText('Patrocinador: Fulana de Teste · Diretora de Marketing')
    // A RBS vê o mesmo signatário.
    const { contexto } = await sessaoDaApi('rbs')
    const contrato = await (await contexto.get(`/api/parcerias/${marca.parceriaId}/contrato`)).json()
    await contexto.dispose()
    expect(contrato.signatario).toMatchObject({ nome: 'Fulana de Teste', email: 'fulana@marca-teste.example' })
  })
  test('[R5.7.b] a RBS sobe o contrato assinado e a marca o abre no visualizador', async ({ page, browser }) => {
    const marca = await novaMarcaDeTeste('SUBCOTA')
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${marca.parceriaId}/contrato`)
    await page.locator('input[type=file][accept*="pdf"]').setInputFiles(PDF_DE_EXEMPLO)
    await page.getByRole('button', { name: 'Enviar contrato' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Contrato enviado como versão 1' })).toBeVisible()
    const outra = await browser.newPage()
    await entrarComo(outra, marca.email)
    await outra.goto(`/parceria/${marca.parceriaId}/contrato`)
    await expect(outra.getByText(/Página 1 de \d+/)).toBeVisible({ timeout: 20_000 })
    await expect(outra.locator('canvas').first()).toBeVisible()
    await expect(outra.getByRole('button', { name: 'Baixar PDF' })).toBeVisible()
    // A marca não tem como enviar: nem o cartão, nem a rota.
    await expect(outra.getByRole('heading', { name: 'Enviar contrato assinado' })).toHaveCount(0)
    await outra.close()
  })
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
  test('[R6.d2] Direitos por contrato mostra o mesmo que as abas', async ({ page }) => {
    const verde = await parceriaDa('verde')
    const { contexto } = await sessaoDaApi('rbs')
    const cortesias = await (await contexto.get(`/api/parcerias/${verde}/cortesias`)).json()
    const credenciais = await (await contexto.get(`/api/parcerias/${verde}/credenciais`)).json()
    await contexto.dispose()
    await entrar(page, 'rbs')
    await page.goto(`/parceria/${verde}/perfil`)
    const cartao = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Direitos por contrato' }) })
    const linhas = cartao.getByRole('row')
    // Uma linha por cota de cortesia, uma por credencial, mais a pesquisa (cabeçalho à parte).
    await expect(linhas).toHaveCount(1 + cortesias.cotas.length + credenciais.credenciais.length + 1)
    await expect(linhas.filter({ hasText: 'Cortesias · Arena · Sábado' })).toContainText('4')
    await expect(linhas.filter({ hasText: 'Credenciais · Arena' })).toContainText(`${credenciais.credenciais[0].quantidadePorDia} por dia`)
    await expect(linhas.filter({ hasText: 'Pesquisa da ativação' })).toContainText('Somente Master')
    await cartao.getByRole('link', { name: /Editar Cortesias · Arena · Sábado/ }).click()
    await expect(page).toHaveURL(new RegExp(`/parceria/${verde}/cortesias$`))
  })
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

/** Uma entrega de Mídia aberta pela RBS numa marca de teste, aguardando envio. Devolve o id. */
async function entregaDeMidia(parceriaId: number) {
  const { contexto, cabecalhos } = await sessaoDaApi('rbs')
  const criada = await contexto.post(`/api/parcerias/${parceriaId}/entregas`, {
    headers: cabecalhos,
    data: { abaCodigo: 'MIDIA', titulo: 'Post de lançamento', descricao: 'Texto, link ou arquivo do post.', status: 'AGUARDANDO_ENVIO' },
  })
  expect(criada.status(), 'a RBS abre a entrega de mídia').toBe(201)
  const id = (await criada.json()).id as number
  await contexto.dispose()
  return id
}

/** Um PDF simples, uma página por texto (só ASCII), com a tabela de referências certa, para testar o envio de documentos. */
function pdfDeTeste(paginas: string[]) {
  const objetos: string[] = []
  const fonte = 3 + paginas.length * 2
  objetos[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objetos[2] = `<< /Type /Pages /Kids [${paginas.map((_, i) => `${3 + i * 2} 0 R`).join(' ')}] /Count ${paginas.length} >>`
  paginas.forEach((texto, i) => {
    const conteudo = `BT /F1 12 Tf 72 760 Td (${texto.replace(/[\\()]/g, (c) => `\\${c}`)}) Tj ET`
    objetos[3 + i * 2] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 ${fonte} 0 R >> >> /Contents ${4 + i * 2} 0 R >>`
    objetos[4 + i * 2] = `<< /Length ${conteudo.length} >>\nstream\n${conteudo}\nendstream`
  })
  objetos[fonte] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  let corpo = '%PDF-1.4\n'
  const posicoes: number[] = []
  for (let n = 1; n < objetos.length; n++) {
    posicoes[n] = corpo.length
    corpo += `${n} 0 obj\n${objetos[n]}\nendobj\n`
  }
  const xref = corpo.length
  corpo += `xref\n0 ${objetos.length}\n0000000000 65535 f \n`
  for (let n = 1; n < objetos.length; n++) corpo += `${String(posicoes[n]).padStart(10, '0')} 00000 n \n`
  corpo += `trailer\n<< /Size ${objetos.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(corpo, 'latin1')
}
