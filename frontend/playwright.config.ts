// Testes de ponta a ponta do Atlas. O Playwright sobe o Vite; a API vem do scripts/atlas-dev.sh.
// Sem filtro, roda tudo. `--grep-invert @vivo` deixa de fora os que usam o modelo de linguagem de verdade.
import { defineConfig, devices } from '@playwright/test'

const PORTA_DO_FRONT = process.env.E2E_PORTA_FRONT ?? '5190'
const API = process.env.PORTAL_API_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  // Um por vez: todos usam o mesmo banco, e fechar uma aba vale para a parceria inteira.
  workers: 1,
  timeout: 90_000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: `http://localhost:${PORTA_DO_FRONT}`,
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${PORTA_DO_FRONT} --strictPort`,
    url: `http://localhost:${PORTA_DO_FRONT}`,
    reuseExistingServer: false,
    env: { PORTAL_API_URL: API },
  },
})
