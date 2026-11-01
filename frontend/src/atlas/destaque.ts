// O destaque do trecho citado no visualizador, sem React, para o teste de unidade rodar sem navegador. [ATLAS]
// O trecho vem do PDFBox e o texto da página, do pdf.js: os dois quebram linhas e espaços em pontos diferentes. A comparação é
// sobre o texto corrido, sem nenhum espaço, e o que for achado volta para a posição em cada pedaço de texto da página.

export type Faixa = [inicio: number, fim: number]

/** Só os espaços e os caracteres invisíveis saem; ligaturas como "ﬁ" viram "fi". */
const normalizar = (texto: string) => texto.normalize('NFKC').replace(/[\s\p{Cf}]/gu, '')

/** Linha mais curta que isto não marca nada sozinha: "1." ou "Prazos" podem estar em qualquer lugar da página. */
const MENOR_LINHA = 8

// Grafema por grafema: "c" seguido da cedilha combinante é um "ç" só, como no texto do trecho.
const GRAFEMAS = new Intl.Segmenter('pt-BR', { granularity: 'grapheme' })

/** O texto corrido da página e, para cada posição dele, de onde veio. */
function corrido(itens: string[]) {
  let texto = ''
  const origem: { item: number; inicio: number; fim: number }[] = []
  itens.forEach((pedaco, item) => {
    for (const { segment, index } of GRAFEMAS.segment(pedaco)) {
      const normal = normalizar(segment)
      texto += normal
      for (let unidade = 0; unidade < normal.length; unidade++) origem.push({ item, inicio: index, fim: index + segment.length })
    }
  })
  return { texto, origem }
}

/**
 * O que marcar em cada pedaço de texto da página. O trecho inteiro, quando ele está na página; senão (ele atravessa a página
 * ou perdeu o rodapé na indexação), cada linha dele, em ordem. Mapa vazio: o trecho não foi achado nesta página.
 */
export function faixasDoTrecho(itens: string[], trecho: string): Map<number, Faixa[]> {
  const pagina = corrido(itens)
  const achados: Faixa[] = []
  const inteiro = normalizar(trecho)
  const direto = inteiro ? pagina.texto.indexOf(inteiro) : -1
  if (direto >= 0) achados.push([direto, direto + inteiro.length])
  else {
    let depois = 0
    for (const linha of trecho.split('\n')) {
      const alvo = normalizar(linha)
      if (alvo.length < MENOR_LINHA) continue
      const achado = pagina.texto.indexOf(alvo, depois)
      if (achado < 0) continue
      achados.push([achado, achado + alvo.length])
      depois = achado + alvo.length
    }
  }

  const faixas = new Map<number, Faixa[]>()
  for (const [inicio, fim] of achados) {
    let itemAtual = -1
    let faixa: Faixa = [0, 0]
    for (let posicao = inicio; posicao < fim; posicao++) {
      const { item, inicio: de, fim: ate } = pagina.origem[posicao]
      if (item === itemAtual) faixa[1] = ate
      else {
        faixa = [de, ate]
        itemAtual = item
        faixas.set(item, [...(faixas.get(item) ?? []), faixa])
      }
    }
  }
  return faixas
}

const ENTIDADES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

/** O texto do PDF é de quem fez o arquivo: nunca entra como HTML. */
export const escaparHtml = (texto: string) => texto.replace(/[&<>"']/g, (caractere) => ENTIDADES[caractere])

/** O HTML de um pedaço de texto da página, para o customTextRenderer: tudo escapado, e só o <mark> é nosso. */
export function htmlDoItem(texto: string, faixas: Faixa[] = []) {
  let saida = ''
  let cursor = 0
  for (const [inicio, fim] of faixas) {
    saida += `${escaparHtml(texto.slice(cursor, inicio))}<mark>${escaparHtml(texto.slice(inicio, fim))}</mark>`
    cursor = fim
  }
  return saida + escaparHtml(texto.slice(cursor))
}

/** O texto de um TXT em partes, para a tela montar em nós de texto, sem HTML nenhum. */
export function partesDoTexto(texto: string, faixas: Faixa[] = []) {
  const partes: { texto: string; marcado: boolean }[] = []
  let cursor = 0
  for (const [inicio, fim] of faixas) {
    if (inicio > cursor) partes.push({ texto: texto.slice(cursor, inicio), marcado: false })
    partes.push({ texto: texto.slice(inicio, fim), marcado: true })
    cursor = fim
  }
  if (cursor < texto.length) partes.push({ texto: texto.slice(cursor), marcado: false })
  return partes
}
