// Acha números e datas num texto e os deixa numa forma comparável. É a mesma regra da checagem do servidor (M4):
// "1.250.000,00" e "1250000", "R$ 312" e "312", "04/12/2026", "4/12", "4 de dezembro" e "2026-12-04", "6h30" e "06:30",
// "89,4%" e "89,4" são iguais. Número por extenso seguido de unidade conta como número; "os dois manuais" não.

const MESES = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']
const NOME_DO_MES = MESES.join('|')

const EXTENSO = {
  um: 1, uma: 1, dois: 2, duas: 2, 'três': 3, tres: 3, quatro: 4, cinco: 5, seis: 6, sete: 7, oito: 8, nove: 9, dez: 10,
  onze: 11, doze: 12, treze: 13, quatorze: 14, catorze: 14, quinze: 15, dezesseis: 16, dezessete: 17, dezoito: 18, dezenove: 19,
  vinte: 20, trinta: 30, quarenta: 40, cinquenta: 50, sessenta: 60, setenta: 70, oitenta: 80, noventa: 90, cem: 100, cento: 100,
  duzentos: 200, duzentas: 200, trezentos: 300, trezentas: 300, quatrocentos: 400, quatrocentas: 400, quinhentos: 500, quinhentas: 500,
  seiscentos: 600, seiscentas: 600, setecentos: 700, setecentas: 700, oitocentos: 800, oitocentas: 800, novecentos: 900, novecentas: 900,
}
const PALAVRA_DE_NUMERO = Object.keys(EXTENSO).join('|')
const UNIDADES = 'reais|real|dias?|horas?|minutos?|kva|kw|por cento|mil|milh(?:ão|ões)|credenciais|credencial|cortesias?|inserç(?:ão|ões)|ingressos?|pulseiras?|metros?|semanas?|meses'

const digitos = (texto) => texto.replace(/\s/g, '')

/** "1.250,50" vira "1250.5"; "312" fica "312". Ponto só é milhar quando separa grupos de 3. */
export function normalizarNumero(bruto) {
  let t = digitos(bruto)
  if (/^\d{1,3}(\.\d{3})+(,\d+)?$/.test(t)) t = t.replace(/\./g, '').replace(',', '.')
  else t = t.replace(',', '.')
  const valor = Number(t)
  return Number.isNaN(valor) ? t : String(valor)
}

const data = (dia, mes) => `data:${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`
const hora = (h, m) => `hora:${String(h).padStart(2, '0')}:${String(m ?? 0).padStart(2, '0')}`

/**
 * Devolve os números e datas do texto, cada um como { valor, trecho, ehAno }.
 * Datas e horas são consumidas antes dos números, para "04/12/2026" não virar 3 números.
 */
export function extrair(textoOriginal) {
  const achados = []
  let texto = ` ${textoOriginal} `
  const consumir = (regex, montar) => {
    texto = texto.replace(regex, (...args) => {
      const valores = montar(...args)
      for (const valor of [valores].flat()) achados.push({ valor, trecho: args[0].trim() })
      return ' '.repeat(args[0].length)
    })
  }

  // Datas: 2026-12-04, 04/12/2026, 4/12 e "4 de dezembro de 2026". O ano entra à parte, para "2026" também valer sozinho.
  consumir(/\b(\d{4})-(\d{2})-(\d{2})\b/g, (_, a, m, d) => [data(+d, +m), `ano:${a}`])
  consumir(/\b(\d{1,2})\/(\d{1,2})\/(\d{4})\b/g, (_, d, m, a) => [data(+d, +m), `ano:${a}`])
  consumir(/\b(\d{1,2})\/(\d{1,2})\/(\d{2})\b/g, (_, d, m) => data(+d, +m))
  consumir(/\b(\d{1,2})\/(\d{1,2})\b/g, (_, d, m) => data(+d, +m))
  consumir(new RegExp(`\\b(\\d{1,2})º?\\s+de\\s+(${NOME_DO_MES})(?:\\s+de\\s+(\\d{4}))?`, 'gi'), (_, d, m, a) =>
    [data(+d, MESES.indexOf(m.toLowerCase()) + 1), ...(a ? [`ano:${a}`] : [])],
  )

  // Horas: 06:30, 6h30, 6h.
  consumir(/\b(\d{1,2}):(\d{2})\b/g, (_, h, m) => hora(+h, +m))
  consumir(/\b(\d{1,2})h(\d{2})?\b/gi, (_, h, m) => hora(+h, m ? +m : 0))

  // Por extenso, só seguido de unidade.
  consumir(new RegExp(`\\b(${PALAVRA_DE_NUMERO})(?:\\s+e\\s+(${PALAVRA_DE_NUMERO}))?\\s+(?:${UNIDADES})\\b`, 'gi'), (_, a, b) =>
    String(EXTENSO[a.toLowerCase()] + (b ? EXTENSO[b.toLowerCase()] : 0)),
  )

  // O resto: números com milhar e decimal. A vírgula de uma lista ("19, 17") não é decimal, porque vem seguida de espaço.
  consumir(/\d+(?:[.,]\d+)*/g, (trecho) => {
    if (/^\d{4}$/.test(trecho)) return [`ano:${trecho}`, normalizarNumero(trecho)]
    return normalizarNumero(trecho)
  })
  return achados
}

/** O conjunto de valores de um texto de fonte. Aceita "ano:2026" e o número 2026 como a mesma coisa. */
export function valoresDe(texto) {
  return new Set(extrair(String(texto ?? '')).map((a) => a.valor))
}

/** Numeração de lista no começo da linha ("1. ", "2) ") não conta como número da resposta. */
export function semNumeracaoDeLista(texto) {
  return texto.replace(/^(\s*)\d{1,3}[.)]\s/gm, '$1')
}

/** As citações [T1], [R2] e [C3] não são números da resposta. */
export function semCitacoes(texto) {
  return texto.replace(/\[[TRC]\d+\]/g, ' ')
}

/** Divide em frases como o servidor: pontuação seguida de espaço, ou quebra de linha. A citação logo depois do ponto fica com a frase. */
export function frases(texto) {
  return texto
    .split(/(?<=[.!?:;](?:\s*\[[TRC]\d+\])*)\s+(?!\[[TRC]\d+\])|\n+/)
    .map((f) => f.trim())
    .filter(Boolean)
}
