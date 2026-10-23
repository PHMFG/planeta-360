import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const frontendRoot = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, host: '0.0.0.0' },
  build: {
    rollupOptions: {
      input: {
        main: resolve(frontendRoot, 'index.html'),
        reactApp: resolve(frontendRoot, 'app-react.html'),
      },
    },
  },
});
