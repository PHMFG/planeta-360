// Onde os testes de ponta a ponta rodam: lido da PORTAL_E2E_PILHA, sem padrão (veja playwright.config.ts).

export interface Destino {
  /** "1" a "9", ou "dev". */
  pilha: string
  front: string
  api: string
  portaDoFront: number
  /** No modo dev o Playwright sobe o Vite; na pilha, o front já está no ar. */
  subirFront: boolean
}

export function destinoDosTestes(): Destino {
  const pilha = (process.env.PORTAL_E2E_PILHA ?? '').trim()
  if (/^[1-9]$/.test(pilha)) {
    const portaDoFront = 5180 + Number(pilha)
    return {
      pilha,
      front: `http://localhost:${portaDoFront}`,
      api: `http://localhost:${8080 + Number(pilha)}`,
      portaDoFront,
      subirFront: false,
    }
  }
  if (pilha === 'dev') {
    const portaDoFront = Number(process.env.E2E_PORTA_FRONT ?? '5190')
    // A mesma variável do scripts/atlas-dev.sh: com a API noutra porta, as duas pontas combinam.
    const portaDaApi = Number(process.env.ATLAS_DEV_PORTA ?? '8080')
    return {
      pilha,
      front: `http://localhost:${portaDoFront}`,
      api: `http://localhost:${portaDaApi}`,
      portaDoFront,
      subirFront: true,
    }
  }
  throw new Error(
    'Diga onde os testes rodam: PORTAL_E2E_PILHA=N (1 a 9, uma pilha do .claude/pilhas-de-teste.sh) ' +
      'ou PORTAL_E2E_PILHA=dev (a API do scripts/atlas-dev.sh, fora do banco portal).',
  )
}
