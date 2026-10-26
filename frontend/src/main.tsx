// Ponto de entrada do front: fontes e ícones locais (sem depender da internet), tema e rotas. [ITEM-7]
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import '@fontsource/bebas-neue'
import '@fontsource/montserrat/400.css'
import '@fontsource/montserrat/500.css'
import '@fontsource/montserrat/600.css'
import '@fontsource/montserrat/700.css'
import 'remixicon/fonts/remixicon.css'
import './estilos/tema.css'
import { App } from './App'
import { ProvedorSessao } from './sessao/Sessao'

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <BrowserRouter>
      <ProvedorSessao>
        <App />
      </ProvedorSessao>
    </BrowserRouter>
  </StrictMode>,
)
