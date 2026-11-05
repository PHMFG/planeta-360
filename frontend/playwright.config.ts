// Testes de ponta a ponta. Sem filtro, roda tudo. `--grep-invert @vivo` deixa de fora os que usam o modelo de linguagem de verdade.
// Onde rodam é obrigatório, sem padrão, para nenhum teste escrever num banco que não é de teste:
//   PORTAL_E2E_PILHA=N    (1 a 9) a pilha N do .claude/pilhas-de-teste.sh: front na 518N, API na 808N, banco portal_qaN.
//   PORTAL_E2E_PILHA=dev  o Vite sobe na E2E_PORTA_FRONT (padrão 5190) e fala com a API do scripts/atlas-dev.sh
//                         (porta ATLAS_DEV_PORTA, padrão 8080).
//                         A preparação recusa se essa API estiver no banco `portal` (o de desenvolvimento de cada pessoa).
import { defineConfig, devices } from '@playwright/test'
import { destinoDosTestes } from './e2e/destino'

const destino = destinoDosTestes()

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  globalSetup: './e2e/preparacao-global.ts',
  // Um por vez: todos usam o mesmo banco, e fechar uma aba vale para a parceria inteira.
  workers: 1,
  // Teste que falha não vira verde na segunda tentativa, e um `test.only` esquecido reprova a execução.
  retries: 0,
  forbidOnly: true,
  timeout: 90_000,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    // Lido pelo conferir-matriz: o resultado de cada teste, pelo título. Fora do test-results, que é limpo a cada execução;
    // E2E_RESULTADO dá outro nome, para guardar execuções em perfis diferentes (o dev e o demo) lado a lado.
    ['json', { outputFile: `resultados-e2e/${process.env.E2E_RESULTADO ?? 'resultado'}.json` }],
  ],
  use: {
    baseURL: destino.front,
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Na pilha, o front já está no ar (recarga ao vivo do código desta pasta). No modo dev, o Vite sobe aqui.
  webServer: destino.subirFront
    ? {
        command: `npm run dev -- --port ${destino.portaDoFront} --strictPort`,
        url: destino.front,
        reuseExistingServer: false,
        env: { PORTAL_API_URL: destino.api },
      }
    : undefined,
})
