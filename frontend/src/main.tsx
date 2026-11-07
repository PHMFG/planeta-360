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
import { ProvedorDeToast } from './componentes/Toast'
import { ProvedorSessao } from './sessao/Sessao'

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <BrowserRouter>
      <ProvedorSessao>
        {/* O aviso curto (toast) mora acima das rotas: recarregar a parceria remonta o layout, e o aviso não pode sumir junto. */}
        <ProvedorDeToast>
          <App />
        </ProvedorDeToast>
      </ProvedorSessao>
    </BrowserRouter>
  </StrictMode>,
)
