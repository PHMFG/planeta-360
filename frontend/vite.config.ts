// Configuração do Vite para o front do Planeta 360. [ITEM-7]
// Em desenvolvimento, as chamadas a /api vão para a API Spring Boot na porta 8080.
// Front e API ficam na mesma origem, então o cookie de sessão e o token CSRF funcionam sem CORS.
// PORTAL_API_URL aponta para outra API (os testes de ponta a ponta usam, quando a 8080 está ocupada).
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.PORTAL_API_URL ?? 'http://localhost:8080',
    },
    // As saídas dos testes de ponta a ponta ficam dentro de frontend/: sem isto, o Vite recarregava a página aberta a cada
    // relatório HTML que o Playwright gravava, no meio de outro teste.
    watch: { ignored: ['**/playwright-report/**', '**/test-results/**', '**/resultados-e2e/**', '**/e2e/capturas/**'] },
  },
})
