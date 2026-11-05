// Antes de qualquer teste: confere que o destino existe e que a API não está no banco `portal`.
import { execFileSync } from 'node:child_process'
import { destinoDosTestes } from './destino'

/** O banco da API que escuta na porta, lido do ambiente do processo (PORTAL_BD_URL). Nulo se não der para ler. */
function bancoDaApiNaPorta(porta: number): string | null {
  try {
    const pid = execFileSync('lsof', ['-tiTCP:' + porta, '-sTCP:LISTEN'], { encoding: 'utf8' }).trim().split('\n')[0]
    if (!pid) return null
    const comando = execFileSync('ps', ['eww', '-p', pid, '-o', 'command='], { encoding: 'utf8' })
    const url = /PORTAL_BD_URL=(\S+)/.exec(comando)?.[1]
    return url ? (url.split('/').pop()?.split('?')[0] ?? null) : null
  } catch {
    return null
  }
}

export default async function preparacaoGlobal() {
  const destino = destinoDosTestes()
  const csrf = await fetch(`${destino.api}/api/auth/csrf`).catch(() => null)
  if (!csrf?.ok) {
    throw new Error(
      `A API de ${destino.api} não respondeu. ` +
        (destino.pilha === 'dev' ? 'Suba com scripts/atlas-dev.sh start.' : 'Suba as pilhas com .claude/pilhas-de-teste.sh subir N.'),
    )
  }
  const porta = Number(new URL(destino.api).port)
  const banco = bancoDaApiNaPorta(porta)
  if (destino.pilha === 'dev') {
    // Sem PORTAL_BD_URL, a API usa o `portal`: é o banco de desenvolvimento de cada pessoa, e os testes escrevem nele.
    if (!banco || banco === 'portal') {
      throw new Error(
        `A API da porta ${porta} está no banco ${banco ?? 'portal (padrão)'}. ` +
          'Suba com PORTAL_BD_URL apontando para um banco de teste.',
      )
    }
  } else if (banco !== null && banco !== `portal_qa${destino.pilha}`) {
    throw new Error(`A API da porta ${porta} está no banco ${banco}, e a pilha ${destino.pilha} usa portal_qa${destino.pilha}.`)
  }
}
