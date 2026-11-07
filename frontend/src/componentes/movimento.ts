// Ganchos do movimento (plano da base da RBS, F10): o movimento reduzido do sistema e a contagem até um número.
import { useEffect, useRef, useState } from 'react'

const CONSULTA_REDUZIDO = '(prefers-reduced-motion: reduce)'

/** Verdadeiro quando o sistema pede movimento reduzido; acompanha a troca sem recarregar. */
export function usarMovimentoReduzido() {
  const [reduzido, setReduzido] = useState(() => typeof window !== 'undefined' && window.matchMedia?.(CONSULTA_REDUZIDO).matches === true)
  useEffect(() => {
    const consulta = window.matchMedia?.(CONSULTA_REDUZIDO)
    if (!consulta) return
    const mudar = () => setReduzido(consulta.matches)
    consulta.addEventListener('change', mudar)
    return () => consulta.removeEventListener('change', mudar)
  }, [])
  return reduzido
}

/**
 * Conta de 0 (na primeira vez) ou do valor anterior até o valor novo, em `duracao` ms, com saída suave.
 * Com movimento reduzido, ou sem número, devolve o valor direto. O número final é sempre o exato.
 */
export function usarContagem(valor: number | null | undefined, duracao = 400) {
  const reduzido = usarMovimentoReduzido()
  const [mostrado, setMostrado] = useState<number | null | undefined>(reduzido ? valor : valor === null || valor === undefined ? valor : 0)
  const anterior = useRef<number>(0)

  useEffect(() => {
    if (valor === null || valor === undefined || reduzido || !Number.isFinite(valor)) {
      setMostrado(valor)
      if (typeof valor === 'number') anterior.current = valor
      return
    }
    const de = anterior.current
    const ate = valor
    if (de === ate) {
      setMostrado(ate)
      return
    }
    let quadro = 0
    const inicio = performance.now()
    const passo = (agora: number) => {
      const t = Math.min(1, (agora - inicio) / duracao)
      const suave = 1 - Math.pow(1 - t, 3)
      setMostrado(t >= 1 ? ate : de + (ate - de) * suave)
      if (t < 1) quadro = requestAnimationFrame(passo)
      else anterior.current = ate
    }
    quadro = requestAnimationFrame(passo)
    return () => {
      cancelAnimationFrame(quadro)
      anterior.current = ate
    }
  }, [valor, reduzido, duracao])

  return mostrado
}
