// Layout e acessibilidade de todas as telas, para cada persona, em três tamanhos: celular (390×844), projetor (1280×720)
// e computador (1440×900). Em cada tela confere que a página carregou de verdade, que não rola para o lado, que todo item
// do menu recebe o clique (nada por cima dele) e que o axe-core não acha violação fora da linha de base.
//
// Linha de base: e2e/layout-base.json, versionado. Guarda as violações que já existiam, cada uma com o ID da matriz que
// a corrige (U01...). Ela só pode diminuir: uma violação fora dela reprova. Para regravar (só ao corrigir): LAYOUT_BASE=gravar.
import { readFileSync, writeFileSync } from 'node:fs'
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { entrar, parceriaDa, type Persona } from './apoio.ts'

type Achado = { chave: string; u: string }
const ARQUIVO_BASE = 'e2e/layout-base.json'
const GRAVAR = process.env.LAYOUT_BASE === 'gravar'
const base: Achado[] = JSON.parse(readFileSync(ARQUIVO_BASE, 'utf8'))
const idDaBase = new Map(base.map((a) => [a.chave, a.u]))
const vistos = new Set<string>()

const TAMANHOS = [
  { nome: 'celular', width: 390, height: 844 },
  { nome: 'projetor', width: 1280, height: 720 },
  { nome: 'computador', width: 1440, height: 900 },
] as const

// As telas de cada persona. {p} vira o id da parceria da persona; a RBS abre o Perfil do Amarelo.
const TELAS_DA_MARCA = ['', 'perfil', 'aplicacoes', 'midia', 'cortesias', 'credenciamento', 'pesquisa', 'contrato', 'relatorio']
const TELAS: Record<Persona, string[]> = {
  amarelo: TELAS_DA_MARCA.map((t) => `/parceria/{p}/${t}`),
  agencia: ['/parceria/{p}/', '/parceria/{p}/perfil', '/parceria/{p}/aplicacoes'],
  roxo: TELAS_DA_MARCA.map((t) => `/parceria/{p}/${t}`),
  verde: TELAS_DA_MARCA.map((t) => `/parceria/{p}/${t}`),
  rbs: ['/rbs', '/rbs/patrocinadores', '/rbs/fila', '/rbs/cargas', '/rbs/pesquisa', '/rbs/relatorios', '/rbs/historico', '/rbs/atlas', '/parceria/{amarelo}/perfil'],
}

/** A página chegou ao conteúdo: tem um título visível, a rede parou (tabelas que carregam depois do título) e nenhum
 * aviso de falha. Uma rota que caiu em erro não passa. */
async function esperarConteudo(page: Page) {
  await expect(page.locator('main h1').first(), 'título da página').toBeVisible({ timeout: 15_000 })
  await page.waitForLoadState('networkidle')
  await expect(page.getByText(/não encontrada|Algo deu errado|Não foi possível carregar/i)).toHaveCount(0)
}

/**
 * Itens do menu cobertos por outro elemento, sem rolar nada: é o que a pessoa vê ao chegar. Um item que aparece dentro da
 * área visível do menu precisa receber o clique no centro dele (o elemento no ponto é o próprio item). Um item que só
 * aparece rolando o menu não conta aqui: a rolagem é legítima, desde que nada fique por cima do que está à vista.
 */
async function itensDoMenuCobertos(page: Page, largura: number, aceitaPista = false): Promise<{ cobertos: string[]; escondidos: string[] }> {
  if (largura < 1024) await page.getByRole('button', { name: 'Menu', exact: true }).click()
  const nav = page.getByRole('navigation', { name: 'Menu principal' })
  await expect(nav).toBeVisible()
  // A gaveta entra com transição: medir no meio dela daria tudo coberto.
  await page.waitForFunction(() =>
    document.getAnimations().filter((a) => a instanceof CSSTransition).every((a) => a.playState !== 'running'),
  )
  const resultado = await nav.evaluate((menu, aceita) => {
    // Com a pista "Role para ver mais" à vista, o item abaixo está escondido de propósito, e a pessoa sabe que pode rolar.
    // Só vale para a RBS, que tem os atalhos de gestão e as oito seções: o menu da marca tem de caber inteiro.
    const pista = menu.querySelector('[data-mais-abaixo]')
    const comPista = aceita && pista !== null && pista.getBoundingClientRect().height > 0
    const caixa = menu.getBoundingClientRect()
    const fim = Math.min(caixa.bottom, window.innerHeight)
    const cobertos: string[] = []
    const escondidos: string[] = []
    for (const item of menu.querySelectorAll('a')) {
      const nome = (item.textContent ?? '').trim()
      const r = item.getBoundingClientRect()
      const x = r.left + r.width / 2
      const y = r.top + r.height / 2
      if (y < caixa.top || y > fim) {
        if (!comPista) escondidos.push(nome)
        continue
      }
      const noPonto = document.elementFromPoint(x, y)
      if (!noPonto || !item.contains(noPonto)) cobertos.push(nome)
    }
    return { cobertos, escondidos }
  }, aceitaPista)
  if (largura < 1024) await page.keyboard.press('Escape')
  return resultado
}

function registrar(chave: string) {
  vistos.add(chave)
}

for (const persona of Object.keys(TELAS) as Persona[]) {
  test.describe(`layout · ${persona}`, () => {
    let parceria = 0
    let parceriaDoAmarelo = 0
    test.beforeAll(async () => {
      if (persona !== 'rbs') parceria = await parceriaDa(persona)
      parceriaDoAmarelo = await parceriaDa('amarelo')
    })

    for (const tamanho of TAMANHOS) {
      test(`${persona} em ${tamanho.nome}`, async ({ page }) => {
        test.setTimeout(240_000)
        await page.setViewportSize({ width: tamanho.width, height: tamanho.height })
        await entrar(page, persona)
        for (const molde of TELAS[persona]) {
          const rota = molde.replace('{p}', String(parceria)).replace('{amarelo}', String(parceriaDoAmarelo))
          const onde = `${persona}|${molde}|${tamanho.nome}`
          await test.step(rota, async () => {
            await page.goto(rota)
            await esperarConteudo(page)
            const largura = await page.evaluate(() => document.documentElement.scrollWidth)
            if (largura > tamanho.width) registrar(`${onde}|rolagem-horizontal`)
            const menu = await itensDoMenuCobertos(page, tamanho.width, persona === 'rbs')
            for (const item of menu.cobertos) registrar(`${onde}|menu-coberto|${item}`)
            // No computador e no projetor o menu é fixo ao lado: um item que só aparece rolando o menu, sem pista nenhuma,
            // parece não existir (U01). No celular a gaveta pode rolar.
            if (tamanho.width >= 1024) for (const item of menu.escondidos) registrar(`${onde}|menu-escondido|${item}`)
            const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
            for (const v of axe.violations) {
              for (const no of v.nodes) registrar(`${onde}|axe|${v.id}|${no.target.join(' ')}`)
            }
          })
        }
      })
    }
  })
}

// Controle negativo: um elemento posto por cima de um item do menu precisa ser acusado. Se não for, a conferência é cega.
test('controle negativo: elemento por cima do menu é acusado', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await entrar(page, 'amarelo')
  await esperarConteudo(page)
  await page.evaluate(() => {
    const alvo = document.querySelector('nav[aria-label="Menu principal"] a')
    if (!alvo) throw new Error('sem item no menu')
    const r = alvo.getBoundingClientRect()
    const capa = document.createElement('div')
    Object.assign(capa.style, { position: 'fixed', left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px`, zIndex: '9999' })
    document.body.appendChild(capa)
  })
  expect((await itensDoMenuCobertos(page, 1440)).cobertos).not.toHaveLength(0)
})

// Este teste roda depois dos outros (um por vez, na ordem do arquivo) e só vale com o arquivo inteiro.
test('nenhuma violação fora da linha de base', () => {
  if (GRAVAR) {
    // Grava o que foi visto, com o ID que já estava na base; as entradas novas saem com "?" para alguém dar o ID.
    const gravados = [...vistos].sort().map((chave) => ({ chave, u: idDaBase.get(chave) ?? '?' }))
    writeFileSync(ARQUIVO_BASE, JSON.stringify(gravados, null, 2) + '\n')
    return
  }
  const novos = [...vistos].filter((chave) => !idDaBase.has(chave))
  const corrigidos = base.filter((a) => !vistos.has(a.chave))
  if (corrigidos.length > 0) {
    test.info().annotations.push({ type: 'corrigidos', description: `${corrigidos.length} entradas da base não apareceram: regrave a base` })
  }
  expect(novos, 'violações novas (corrija, ou registre na linha de base com o ID que a corrige)').toEqual([])
})
