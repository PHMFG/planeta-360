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
const PALAVRA = Object.keys(EXTENSO).join('|')
const ESCALA = 'mil|milh(?:ão|ões)'
const UNIDADES = 'reais|real|dias?|horas?|minutos?|kva|kw|por cento|credenciais|credencial|cortesias?|inserç(?:ão|ões)|ingressos?|pulseiras?|metros?|semanas?|meses|aplicaç(?:ão|ões)|pessoas?|litros?|visitantes?|cadastros?|rodadas?|peças?|vezes'
const CORRIDA_POR_EXTENSO = new RegExp(`(?<![\\p{L}\\p{N}])((?:${PALAVRA}|${ESCALA})(?:\\s+(?:e\\s+)?(?:${PALAVRA}|${ESCALA}))*)(?![\\p{L}\\p{N}])(\\s+(?:de\\s+)?(?:${UNIDADES})(?![\\p{L}\\p{N}]))?`, 'giu')

/** "1.250,50" vira "1250.5"; "312" fica "312". Ponto só é milhar quando separa grupos de 3. */
export function normalizarNumero(bruto) {
  let t = bruto.replace(/\s/g, '')
  if (/^\d{1,3}(\.\d{3})+(,\d+)?$/.test(t)) t = t.replace(/\./g, '').replace(',', '.')
  else t = t.replace(',', '.')
  const valor = Number(t)
  return Number.isNaN(valor) ? t : String(valor)
}

const duasCasas = (n) => String(n).padStart(2, '0')
const umaData = (trecho, dia, mes, ano) => ({ trecho, data: { dia: `${duasCasas(mes)}-${duasCasas(dia)}`, ano: ano ?? null } })
const umValor = (trecho, ...valores) => ({ trecho, valores })
const umaHora = (trecho, h, m = 0) => umValor(trecho, `hora:${duasCasas(h)}:${duasCasas(m)}`)

function porExtenso(palavras) {
  let total = 0
  let parcial = 0
  let temEscala = false
  for (const palavra of palavras.toLowerCase().split(/\s+/).filter((p) => p !== 'e')) {
    if (palavra === 'mil') { total += (parcial || 1) * 1000; parcial = 0; temEscala = true }
    else if (palavra.startsWith('milh')) { total += (parcial || 1) * 1_000_000; parcial = 0; temEscala = true }
    else parcial += EXTENSO[palavra]
  }
  return { valor: total + parcial, temEscala }
}

/**
 * Devolve os números e datas do texto. Datas e horas são consumidas antes dos números, para "04/12/2026" não virar 3 números.
 * Numa fonte (`fonte: true`), todo numeral por extenso conta, porque "duas rodadas" é a origem de "2 rodadas".
 * Na resposta, só com unidade, e "um dia" não é número: é artigo.
 */
export function extrair(textoOriginal, { fonte = false } = {}) {
  const achados = []
  let texto = ` ${textoOriginal} `.replace(/Planeta 360/g, ' ')
  const consumir = (regex, montar) => {
    texto = texto.replace(regex, (...args) => {
      const novo = montar(...args)
      if (novo === null) return args[0]
      achados.push(...[novo].flat())
      return ' '.repeat(args[0].length)
    })
  }
  const nomeDoMes = (m) => MESES.indexOf(m.toLowerCase()) + 1

  consumir(/\b(\d{4})-(\d{2})-(\d{2})\b/g, (t, a, m, d) => umaData(t, +d, +m, a))
  consumir(/\b(\d{1,2})\/(\d{1,2})\/(\d{4})\b/g, (t, d, m, a) => umaData(t, +d, +m, a))
  consumir(/\b(\d{1,2})\/(\d{1,2})\/(\d{2})\b/g, (t, d, m, a) => umaData(t, +d, +m, `20${a}`))
  consumir(/\b(\d{1,2})\/(\d{1,2})\b/g, (t, d, m) => umaData(t, +d, +m))
  // "29 e 30 de janeiro", "de 25 a 28 de janeiro" e "4 de dezembro de 2026".
  consumir(new RegExp(`\\b(\\d{1,2})º?((?:\\s*(?:,|\\be\\b|\\ba\\b|\\baté\\b)\\s*\\d{1,2}º?)*)\\s+de\\s+(${NOME_DO_MES})(?:\\s+de\\s+(\\d{4}))?`, 'gi'), (t, primeiro, outros, m, a) =>
    [primeiro, ...(outros.match(/\d{1,2}/g) ?? [])].map((d) => umaData(t, +d, nomeDoMes(m), a)),
  )

  consumir(/\b(\d{1,2}):(\d{2})\b/g, (t, h, m) => umaHora(t, +h, +m))
  consumir(/\b(\d{1,2})h(\d{2})?\b/gi, (t, h, m) => umaHora(t, +h, m ? +m : 0))
  consumir(/\b(\d{1,3})\s*horas?\b/gi, (t, h) => (+h <= 24 ? umValor(t, `hora:${duasCasas(+h)}:00`, String(+h)) : umValor(t, String(+h))))

  consumir(CORRIDA_POR_EXTENSO, (t, palavras, unidade) => {
    const { valor, temEscala } = porExtenso(palavras)
    if (fonte || temEscala) return umValor(t, String(valor))
    return unidade && valor !== 1 ? umValor(t, String(valor)) : null
  })

  // O resto: números com milhar e decimal. A vírgula de uma lista ("19, 17") não é decimal, porque vem seguida de espaço.
  consumir(/\d+(?:[.,]\d+)*/g, (t) => (/^\d{4}$/.test(t) ? umValor(t, normalizarNumero(t), `ano:${t}`) : umValor(t, normalizarNumero(t))))
  return achados
}

/** Tudo o que um conjunto de textos permite afirmar. Data com ano só vale no ano certo, se a fonte disser o ano. */
export class Origens {
  constructor(...textos) {
    this.valores = new Set()
    for (const texto of textos) this.adicionar(texto)
  }

  adicionar(texto) {
    for (const achado of extrair(String(texto ?? ''), { fonte: true })) {
      if (achado.data) {
        const { dia, ano } = achado.data
        this.valores.add(`data:${dia}`)
        if (ano) {
          this.valores.add(`data:${ano}-${dia}`)
          this.valores.add(`dataComAno:${dia}`)
          this.valores.add(`ano:${ano}`)
          this.valores.add(ano)
        }
      } else {
        for (const valor of achado.valores) this.valores.add(valor)
      }
    }
    return this
  }

  aceita(achado) {
    if (!achado.data) return achado.valores.some((v) => this.valores.has(v))
    const { dia, ano } = achado.data
    if (!ano) return this.valores.has(`data:${dia}`)
    return this.valores.has(`data:${ano}-${dia}`) || (this.valores.has(`data:${dia}`) && !this.valores.has(`dataComAno:${dia}`))
  }
}

/** O que sobra do texto sem os números: "Portão 5" vira "Portão". Serve para conferir as palavras em volta do número. */
export function palavrasFora(texto) {
  let resto = texto
  for (const { trecho } of extrair(texto, { fonte: true })) resto = resto.replace(trecho, ' ')
  return resto
}

/** Numeração de lista no começo da linha ("1. ", "2) ", "**1.** ") não conta como número da resposta. */
export function semNumeracaoDeLista(texto) {
  return texto.replace(/^(\s*)(\*\*)?\d{1,3}[.)](\*\*)?\s/gm, '$1')
}

const CITACAO = /\[[TRC]\d+(?:\s*,\s*[TRC]\d+)*\]/g

/** As citações [T1], [R2], [C3] e [T1, T2] não são números da resposta. */
export function semCitacoes(texto) {
  return texto.replace(CITACAO, ' ')
}

/** Os marcadores citados num texto: "[T1, T2] e [R3]" dá T1, T2 e R3. */
export function citacoes(texto) {
  return (texto.match(CITACAO) ?? []).flatMap((c) => c.match(/[TRC]\d+/g))
}

/**
 * Divide em frases como o servidor: pontuação seguida de espaço (`FIM_DE_FRASE`), ou quebra de linha.
 * As citações logo depois do ponto ficam com a frase.
 */
export function frases(texto) {
  return texto
    .split(/(?<=[.!?…](?:\s*\[[TRC]\d+(?:\s*,\s*[TRC]\d+)*\])*)\s+(?!\[[TRC]\d+)|\n+/)
    .map((f) => f.trim())
    .filter(Boolean)
}
