// O que os testes de ponta a ponta usam em comum: usuários da amostra, login e capturas.
import { expect, request, type APIRequestContext, type Page } from '@playwright/test'

export const SENHA = 'senha123'
export const USUARIOS = {
  amarelo: 'marketing@amarelo.example',
  roxo: 'marketing@roxo.example',
  verde: 'marketing@verde.example',
  rbs: 'b2b@rbs.example',
} as const
export type Persona = keyof typeof USUARIOS

const API = process.env.PORTAL_API_URL ?? 'http://localhost:8080'

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
