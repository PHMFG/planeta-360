// Acha números e datas num texto e os deixa numa forma comparável. É a mesma regra da checagem do servidor (M4):
// "1.250.000,00" e "1250000", "R$ 312" e "312", "04/12/2026", "4/12", "4 de dezembro" e "2026-12-04", "6h30" e "06:30",
// "89,4%" e "89,4" são iguais. Numeral por extenso conta como número, menos "um" e "uma", que são artigo, e "dois" sem unidade
// ("os dois manuais").

const MESES = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']
const NOME_DO_MES = MESES.join('|')

const EXTENSO = {
  zero: 0, um: 1, uma: 1, dois: 2, duas: 2, 'três': 3, tres: 3, quatro: 4, cinco: 5, seis: 6, sete: 7, oito: 8, nove: 9, dez: 10,
  onze: 11, doze: 12, treze: 13, quatorze: 14, catorze: 14, quinze: 15, dezesseis: 16, dezessete: 17, dezoito: 18, dezenove: 19,
  vinte: 20, trinta: 30, quarenta: 40, cinquenta: 50, sessenta: 60, setenta: 70, oitenta: 80, noventa: 90, cem: 100, cento: 100,
  duzentos: 200, duzentas: 200, trezentos: 300, trezentas: 300, quatrocentos: 400, quatrocentas: 400, quinhentos: 500, quinhentas: 500,
  seiscentos: 600, seiscentas: 600, setecentos: 700, setecentas: 700, oitocentos: 800, oitocentas: 800, novecentos: 900, novecentas: 900,
}
const PALAVRA = Object.keys(EXTENSO).join('|')
const ESCALA = 'mil|milh(?:ão|ões|ao|oes)'
const UNIDADES = 'reais|real|dias?|horas?|minutos?|kva|kw|por cento|porcento|%|credenciais|credencial|cortesias?|inserç(?:ão|ões)|ingressos?|pulseiras?|metros?|semanas?|meses|aplicaç(?:ão|ões)|pessoas?|litros?|visitantes?|cadastros?|rodadas?|peças?|vezes'
const PALAVRA_DE_ANO_ANTES = /(?<![\p{L}\p{N}])(?:edi[çc][ãa]o|edi[çc][õo]es|ano|em|de|desde|at[ée]|atl[âa]ntida)(?::\s*|\s+)$/iu
const DEPOIS_UNIDADE = new RegExp(`^\\s*(?:de\\s+)?(?:${UNIDADES})(?![\\p{L}\\p{N}])`, 'iu')
const DATA_COM_NUMERAL = new RegExp(`(?<![\\p{L}\\p{N}])((?:${PALAVRA})(?:\\s+(?:e\\s+)?(?:${PALAVRA}))*)\\s+de\\s+(${NOME_DO_MES})(?:\\s+de\\s+(\\d{4}))?(?![\\p{L}\\p{N}])`, 'giu')
const CORRIDA_POR_EXTENSO = new RegExp(`(?<![\\p{L}\\p{N}])(?:(?<!\\bpor\\s{1,8})|(?!cento(?![\\p{L}\\p{N}])))((?:${PALAVRA}|${ESCALA})(?:\\s+(?:e\\s+)?(?:${PALAVRA}|${ESCALA}))*)(?![\\p{L}\\p{N}])(\\s+(?:de\\s+)?(?:${UNIDADES})(?![\\p{L}\\p{N}]))?`, 'giu')

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

/** Centena, dezena e unidade: numerais só somam em ordem decrescente ("trezentos e doze"); "três e quatro" são dois números. */
const grandeza = (valor) => (valor >= 100 ? 3 : valor >= 20 ? 2 : 1)

function porExtenso(palavras) {
  const grupos = []
  let total = 0
  let parcial = 0
  let temEscala = false
  let ultima = 4
  for (const palavra of palavras.toLowerCase().split(/\s+/).filter((p) => p !== 'e')) {
    if (palavra === 'mil' || palavra.startsWith('milh')) {
      total += (parcial || 1) * (palavra === 'mil' ? 1000 : 1_000_000)
      parcial = 0
      temEscala = true
      ultima = 4
      continue
    }
    const valor = EXTENSO[palavra]
    if (grandeza(valor) >= ultima) {
      grupos.push({ valor: total + parcial, temEscala })
      total = 0
      parcial = 0
      temEscala = false
    }
    parcial += valor
    ultima = grandeza(valor)
  }
  grupos.push({ valor: total + parcial, temEscala })
  return grupos
}

/** "um" e "uma" são artigo e nunca contam, nem na fonte. "dois" e "duas" só com unidade, na resposta. O resto conta sempre. */
const contaComoNumero = ({ valor, temEscala }, unidade, fonte) => temEscala || (valor !== 1 && (fonte || valor !== 2 || Boolean(unidade)))

/**
 * Devolve os números e datas do texto. Datas e horas são consumidas antes dos números, para "04/12/2026" não virar 3 números.
 * Numa fonte (`fonte: true`), "duas rodadas" é a origem de "2 rodadas". Na resposta, "os dois manuais" e "um dia" não são números.
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

  // "2 milhões", "1,5 milhão", "3 mil": o algarismo com a escala vale o número inteiro.
  consumir(/(?<![\p{L}\p{N}.,])(\d+(?:,\d+)?)\s+(mil|milh(?:ão|ões|ao|oes))(?![\p{L}\p{N}])/giu, (t, numero, escala) =>
    umValor(t, String(Number(numero.replace(',', '.')) * (escala.toLowerCase() === 'mil' ? 1000 : 1_000_000))))
  // "dez de dezembro" é uma data; "sete e oito de dezembro", duas.
  consumir(DATA_COM_NUMERAL, (t, palavras, m, a) => porExtenso(palavras).map(({ valor }) => umaData(t, valor, nomeDoMes(m), a)))
  consumir(CORRIDA_POR_EXTENSO, (t, palavras, unidade) => {
    const contam = porExtenso(palavras).filter((grupo) => contaComoNumero(grupo, unidade, fonte))
    return contam.length ? contam.map(({ valor }) => umValor(t, String(valor))) : null
  })

  // O resto: números com milhar e decimal. A vírgula de uma lista ("19, 17") não é decimal, porque vem seguida de espaço.
  // Quatro dígitos só são um ano depois de uma palavra de ano, sem "R$" antes nem unidade depois.
  consumir(/(?<![\d.,])\d{1,3}(?:[ \u00A0\u202F]\d{3})+(?!\d)(?:,\d+)?|\d+(?:[.,]\d+)*/g, (t, posicao, todo) => {
    if (/[ \u00A0\u202F]/.test(t)) {
      // "2 220,00" é o inteiro. Numa fonte, as partes também valem, porque tabela tem colunas de números.
      const partes = fonte ? t.split(/[ \u00A0\u202F]/).map(normalizarNumero) : []
      return umValor(t, normalizarNumero(t.replace(/[ \u00A0\u202F]/g, '')), ...partes)
    }
    const antes = todo.slice(0, posicao)
    const ehAno = /^\d{4}$/.test(t) && !/R\$\s*$/.test(antes) && PALAVRA_DE_ANO_ANTES.test(antes) && !DEPOIS_UNIDADE.test(todo.slice(posicao + t.length))
    return ehAno ? umValor(t, normalizarNumero(t), `ano:${t}`) : umValor(t, normalizarNumero(t))
  })
  return achados
}

/**
 * Tudo o que um conjunto de textos permite afirmar. Data com ano só vale no ano certo, se a fonte disser o ano. Se ela não disser, com
 * {@code anosAceitos} a resposta só pode pôr um desses anos; sem ele, qualquer um.
 */
export class Origens {
  constructor(...textos) {
    this.valores = new Set()
    this.anosAceitos = null
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

  /** O ano da edição ou de hoje: vale como ano ("em 2027"), nunca como valor ("R$ 2.027,00", "2026 credenciais"). */
  adicionarAnoLivre(ano) {
    this.valores.add(`ano:${ano}`)
    return this
  }

  /** A data de hoje, em qualquer formato, sem liberar o ano como valor. */
  adicionarData(dia, mes, ano) {
    this.valores.add(`data:${duasCasas(mes)}-${duasCasas(dia)}`)
    this.valores.add(`data:${ano}-${duasCasas(mes)}-${duasCasas(dia)}`)
    this.valores.add(`dataComAno:${duasCasas(mes)}-${duasCasas(dia)}`)
    return this
  }

  aceita(achado) {
    if (!achado.data) return achado.valores.some((v) => this.valores.has(v))
    const { dia, ano } = achado.data
    if (!ano) return this.valores.has(`data:${dia}`)
    if (this.valores.has(`data:${ano}-${dia}`)) return true
    return this.valores.has(`data:${dia}`) && !this.valores.has(`dataComAno:${dia}`) && (!this.anosAceitos || this.anosAceitos.has(ano))
  }
}

/** O que sobra do texto sem os números: "Portão 5" vira "Portão". Serve para conferir as palavras em volta do número. */
export function palavrasFora(texto) {
  let resto = texto
  for (const { trecho } of extrair(texto, { fonte: true })) resto = resto.replace(trecho, ' ')
  return resto
}

/**
 * Numeração de lista no começo da linha ("1. ", "2) ", "**1.** ", "### 1. ", "1 - ") não conta como número da resposta, se seguir a
 * sequência: começa em 1 e cresce de um em um. "312. É o valor" é um número.
 */
export function semNumeracaoDeLista(texto) {
  let esperado = 1
  return texto.replace(/^([ \t]*)(?:#{1,6}[ \t]+)?(?:\*\*)?(\d{1,3})(?:[.)]|[ \t]+-)(?:\*\*)?[ \t]/gm, (tudo, recuo, numero) => {
    const n = Number(numero)
    if (n === 1) esperado = 1
    if (n !== esperado) return tudo
    esperado++
    return recuo
  })
}

const CITACAO = /\[\s*[TRC]\d{1,3}(?:\s*,\s*[TRC]\d{1,3})*\s*\]/g

/** As citações [T1], [R2], [C3] e [T1, T2] não são números da resposta. */
export function semCitacoes(texto) {
  return texto.replace(CITACAO, ' ')
}

/** Os marcadores citados num texto: "[T1, T2] e [R3]" dá T1, T2 e R3. */
export function citacoes(texto) {
  return (texto.match(CITACAO) ?? []).flatMap((c) => c.match(/[TRC]\d+/g))
}

const ABREVIATURAS = new Set(['p', 'pp', 'pág', 'págs', 'pag', 'pags', 'art', 'arts', 'sr', 'sra', 'dr', 'dra', 'prof', 'av', 'tel', 'cel', 'ex', 'obs', 'cf', 'vol', 'fl', 'fls', 'cap', 'inc', 'sec', 'seç', 'nº', 'n'])
const PONTUACAO = '.!?…'
const FECHAMENTOS = '"\')»”’*_'
const CITACAO_AQUI = /\[\s*[TRC]\d{1,3}(?:\s*,\s*[TRC]\d{1,3})*\s*\]/y

function ehAbreviatura(texto, inicio, ponto) {
  let comeco = ponto
  while (comeco > inicio && /[\p{L}º]/u.test(texto[comeco - 1])) comeco--
  return ABREVIATURAS.has(texto.slice(comeco, ponto).toLowerCase())
}

/**
 * Divide em frases como o servidor (`Frases.java`): pontuação seguida de espaço, ou quebra de linha. O ponto de abreviatura ("p. 6") e o
 * de dentro de um marcador não cortam, o negrito fecha a frase, e as citações logo depois do ponto ficam com ela.
 */
export function frases(texto) {
  const lista = []
  let inicio = 0
  const fechar = (fim) => {
    const frase = texto.slice(inicio, fim).trim()
    if (frase) lista.push(frase)
    inicio = fim
  }
  for (let i = 0; i < texto.length; i++) {
    const c = texto[i]
    if (c === '{' && texto.startsWith('{{', i)) {
      const fecha = texto.indexOf('}}', i + 2)
      if (fecha < 0) break
      i = fecha + 1
    } else if (c === '\n') {
      fechar(i)
    } else if (PONTUACAO.includes(c)) {
      let depois = i + 1
      while (depois < texto.length && PONTUACAO.includes(texto[depois])) depois++
      while (depois < texto.length && FECHAMENTOS.includes(texto[depois])) depois++
      if (depois >= texto.length) break
      if (!/\s/.test(texto[depois]) || (c === '.' && ehAbreviatura(texto, inicio, i))) {
        i = depois - 1
        continue
      }
      let fim = depois
      let j = depois
      for (;;) {
        while (j < texto.length && /[^\S\n]/.test(texto[j])) j++
        CITACAO_AQUI.lastIndex = j
        const citacao = CITACAO_AQUI.exec(texto)
        if (!citacao) break
        j += citacao[0].length
        fim = j
      }
      fechar(fim)
      i = fim - 1
    }
  }
  fechar(texto.length)
  return lista
}
