// CSV gerado no navegador, no mesmo formato das exportações da API (`portal/comum/Csv.java`): BOM UTF-8, separador ";",
// aspas quando precisa, decimal com vírgula e apóstrofo antes de =, +, -, @, tab e retorno, para a planilha não virar fórmula.

function celula(valor: string | number | null | undefined) {
  if (valor === null || valor === undefined) return ''
  let texto = typeof valor === 'number' ? String(valor).replace('.', ',') : valor
  if (typeof valor === 'string' && /^[=+\-@\t\r]/.test(texto)) texto = `'${texto}`
  return /[";\n\r]/.test(texto) ? `"${texto.replace(/"/g, '""')}"` : texto
}

/** Monta o CSV e baixa no navegador, como anexo, com o nome dado. */
export function baixarCsv(nome: string, cabecalho: string[], linhas: (string | number | null | undefined)[][]) {
  const corpo = [cabecalho, ...linhas].map((linha) => linha.map(celula).join(';')).join('\r\n')
  const blob = new Blob(['﻿', corpo], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = nome
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
