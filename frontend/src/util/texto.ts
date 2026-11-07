/** Busca que acha "teste pequeno" em "teste-pequeno.pdf": sem acento, sem maiúscula e com hífen, ponto e sublinhado valendo espaço. */
export function normalizar(texto: string) {
  return texto
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[-_.\s]+/g, ' ')
    .trim()
}
