// O que os testes de ponta a ponta usam em comum: usuários da amostra, login e capturas.
import { expect, request, type APIRequestContext, type Page } from '@playwright/test'
import { destinoDosTestes } from './destino'

export const SENHA = 'senha123'
export const USUARIOS = {
  amarelo: 'marketing@amarelo.example',
  agencia: 'atendimento@agencia-x.example',
  roxo: 'marketing@roxo.example',
  verde: 'marketing@verde.example',
  rbs: 'b2b@rbs.example',
} as const
export type Persona = keyof typeof USUARIOS

// Sem padrão: a API é a do destino (PORTAL_E2E_PILHA), nunca a 8080 por acaso.
const API = destinoDosTestes().api

/** Entra pela tela e espera sair do login. */
export async function entrar(page: Page, persona: Persona) {
  await page.goto('/entrar')
  await page.getByLabel('E-mail corporativo').fill(USUARIOS[persona])
  await page.getByLabel('Senha').fill(SENHA)
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  await expect(page).not.toHaveURL(/\/entrar/)
}

/** Fala direto com a API, como o front: pega o CSRF, entra e devolve o token para os POST e PATCH. */
export async function sessaoDaApi(persona: Persona) {
  const contexto: APIRequestContext = await request.newContext({ baseURL: API })
  const csrf = await (await contexto.get('/api/auth/csrf')).json()
  const login = await contexto.post('/api/auth/login', {
    headers: { [csrf.cabecalho]: csrf.token },
    data: { email: USUARIOS[persona], senha: SENHA },
  })
  expect(login.status(), `login de ${persona}`).toBe(200)
  const usuario = await login.json()
  return { contexto, cabecalhos: { [csrf.cabecalho]: csrf.token }, usuario }
}

/** A parceria da marca, a partir do que o login devolve. */
export async function parceriaDa(persona: Persona): Promise<number> {
  const { contexto, usuario } = await sessaoDaApi(persona)
  await contexto.dispose()
  return usuario.parcerias[0].id
}

/** A captura fica em e2e/capturas (fora do Git), para o agente olhar depois. */
export async function capturar(page: Page, nome: string) {
  await page.screenshot({ path: `e2e/capturas/${nome}.png`, fullPage: true })
}

/** Entra pela tela com qualquer e-mail (uma marca de teste, por exemplo). */
export async function entrarComo(page: Page, email: string) {
  await page.goto('/entrar')
  await page.getByLabel('E-mail corporativo').fill(email)
  await page.getByLabel('Senha').fill(SENHA)
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  await expect(page).not.toHaveURL(/\/entrar/)
}

/**
 * Uma marca nova, só deste teste: a RBS cria a parceria na edição de 2027 pela API, autoriza um e-mail, e o e-mail faz o
 * primeiro acesso com a senha de sempre. Quem precisa gravar números ou destinar cortesias usa esta marca, nunca o
 * Amarelo, o Roxo ou o Verde, cujos números o Atlas, o avaliador e o roteiro conferem.
 */
export async function novaMarcaDeTeste(cotaCodigo: 'MASTER' | 'SUBCOTA_G' | 'SUBCOTA' = 'SUBCOTA') {
  const rbs = await sessaoDaApi('rbs')
  const sufixo = `${Date.now()}${Math.floor(Math.random() * 1000)}`
  const email = `teste-${sufixo}@marca-teste.example`
  const eventos: { id: number; edicao: number }[] = await (await rbs.contexto.get('/api/eventos')).json()
  const edicao = eventos.find((evento) => evento.edicao === 2027) ?? eventos[0]
  const criada = await rbs.contexto.post('/api/parcerias', {
    headers: rbs.cabecalhos,
    data: { patrocinador: { nome: `Marca de Teste ${sufixo}` }, eventoId: edicao.id, cotaCodigo, emails: [{ email, vinculo: 'MARCA' }] },
  })
  expect(criada.status(), 'a RBS cria a parceria de teste').toBe(201)
  const parceria = await criada.json()
  const codigo: string = parceria.emailsAutorizados.find((item: { email: string }) => item.email === email).codigoAtivacao
  await rbs.contexto.dispose()

  const publico = await request.newContext({ baseURL: API })
  const csrf = await (await publico.get('/api/auth/csrf')).json()
  const ativacao = await publico.post('/api/auth/ativar', { headers: { [csrf.cabecalho]: csrf.token }, data: { email, codigo, senha: SENHA } })
  expect(ativacao.status(), 'primeiro acesso da marca de teste').toBe(204)
  await publico.dispose()
  return { parceriaId: parceria.id as number, email }
}
