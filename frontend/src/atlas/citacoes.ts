// As citações do texto do Atlas ([T1], [T1, T2]), sem React, para o teste de unidade rodar sem navegador. [ATLAS]

// A mesma forma que o servidor confere (FontesDoTurno): T de trecho, R de registro e C de conta.
const CITACAO = /\[\s*[TRC]\d{1,3}(?:\s*,\s*[TRC]\d{1,3})*\s*\]/g

export type ParteDoTexto = { tipo: 'texto'; texto: string } | { tipo: 'citacao'; texto: string; marcadores: string[] }

/** O texto em pedaços: o que é texto continua igual, e cada colchete vira uma lista de marcadores. */
export function partesComCitacoes(texto: string): ParteDoTexto[] {
  const partes: ParteDoTexto[] = []
  let cursor = 0
  for (const achado of texto.matchAll(CITACAO)) {
    if (achado.index > cursor) partes.push({ tipo: 'texto', texto: texto.slice(cursor, achado.index) })
    partes.push({ tipo: 'citacao', texto: achado[0], marcadores: achado[0].slice(1, -1).split(',').map((marcador) => marcador.trim()) })
    cursor = achado.index + achado[0].length
  }
  if (cursor < texto.length) partes.push({ tipo: 'texto', texto: texto.slice(cursor) })
  return partes
}
