// Link para fora do portal (Open Staff, posts das marcas): só http e https, em aba nova, sem dar ao site aberto acesso à
// janela do portal, e com o domínio à vista para a pessoa saber para onde vai. Qualquer outro endereço vira texto. [SEGURANCA]
import type { ReactNode } from 'react'

/** O endereço, se for http(s) com um site; senão, null. */
export function enderecoSeguro(endereco: string | null | undefined): URL | null {
  if (!endereco) return null
  try {
    const url = new URL(endereco)
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') || !url.hostname || url.username || url.password) return null
    return url
  } catch {
    return null
  }
}

type Props = {
  href: string
  children: ReactNode
  className?: string
  /** Mostra o domínio ao lado do texto (padrão: sim). */
  mostrarDominio?: boolean
}

export function LinkExterno({ href, children, className = '', mostrarDominio = true }: Props) {
  const url = enderecoSeguro(href)
  if (!url) return <span className={className}>{children}</span>
  return (
    <a href={url.href} target="_blank" rel="noopener noreferrer" className={className}>
      {children}
      <i className="ri-external-link-line ml-1" aria-hidden="true" />
      <span className="sr-only"> (abre em outra aba)</span>
      {mostrarDominio && <span className="ml-1 text-xs font-medium opacity-80">· {url.hostname.replace(/^www\./, '')}</span>}
    </a>
  )
}
