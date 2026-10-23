// Formatos de data, número e tamanho de arquivo no padrão brasileiro. [ITEM-7]

const DATA_HORA = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Sao_Paulo' })
const DATA = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: 'America/Sao_Paulo' })
const NUMERO = new Intl.NumberFormat('pt-BR')

export function dataHora(iso: string | null | undefined) {
  return iso ? DATA_HORA.format(new Date(iso)) : '—'
}

export function data(iso: string | null | undefined) {
  if (!iso) return '—'
  // Datas sem hora ("2027-01-30") são lidas como dia local, para não voltar um dia por causa do fuso.
  return /^\d{4}-\d{2}-\d{2}$/.test(iso) ? iso.split('-').reverse().join('/') : DATA.format(new Date(iso))
}

export function numero(valor: number | null | undefined) {
  return valor === null || valor === undefined ? '—' : NUMERO.format(valor)
}

export function tamanho(bytes: number | null | undefined) {
  if (!bytes && bytes !== 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`
}

const MOEDA = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

export function moeda(valor: number | null | undefined) {
  return valor === null || valor === undefined ? '—' : MOEDA.format(valor)
}

export function porcentagem(valor: number | null | undefined) {
  return valor === null || valor === undefined ? '—' : `${NUMERO.format(Math.round(valor * 10) / 10)}%`
}
