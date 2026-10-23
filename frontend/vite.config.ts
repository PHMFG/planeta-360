// Configuração do Vite para o front do Planeta 360. [ITEM-7]
// Em desenvolvimento, as chamadas a /api vão para a API Spring Boot na porta 8080.
// Front e API ficam na mesma origem, então o cookie de sessão e o token CSRF funcionam sem CORS.
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
