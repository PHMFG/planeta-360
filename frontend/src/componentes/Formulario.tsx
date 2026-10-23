// Variações do Campo do Figma para formulários: lista de opções, texto longo e a busca. [VALIDACAO]
import { useId, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react'

const MOLDURA = 'flex flex-col gap-1 rounded-xl border-[1.5px] bg-white px-4 py-3 transition-colors'

function Mensagem({ id, erro, ajuda }: { id: string; erro?: string; ajuda?: string }) {
  if (!erro && !ajuda) return null
  return (
    <p id={id} className={`mt-1.5 px-1 text-xs font-medium ${erro ? 'text-[#c8203f]' : 'text-texto-2'}`}>
      {erro ?? ajuda}
    </p>
  )
}

type PropsSeletor = SelectHTMLAttributes<HTMLSelectElement> & {
  rotulo: string
  erro?: string
  ajuda?: string
  children: ReactNode
}

export function Seletor({ rotulo, erro, ajuda, className = '', children, ...resto }: PropsSeletor) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className={`${MOLDURA} ${erro ? 'border-estado-falha' : 'border-borda focus-within:border-secao'}`}>
        <span className="texto-sobretitulo text-texto-2">{rotulo}</span>
        <select
          id={id}
          className="w-full bg-transparent text-sm font-semibold text-tinta outline-none"
          aria-invalid={erro ? true : undefined}
          aria-describedby={erro || ajuda ? `${id}-d` : undefined}
          {...resto}
        >
          {children}
        </select>
      </label>
      <Mensagem id={`${id}-d`} erro={erro} ajuda={ajuda} />
    </div>
  )
}

type PropsAreaTexto = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  rotulo: string
  erro?: string
  ajuda?: string
}

export function AreaTexto({ rotulo, erro, ajuda, className = '', ...resto }: PropsAreaTexto) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className={`${MOLDURA} ${erro ? 'border-estado-falha' : 'border-borda focus-within:border-secao'}`}>
        <span className="texto-sobretitulo text-texto-2">{rotulo}</span>
        <textarea
          id={id}
          rows={3}
          className="w-full resize-y bg-transparent text-sm font-semibold text-tinta outline-none placeholder:font-medium placeholder:text-cinza-400"
          aria-invalid={erro ? true : undefined}
          aria-describedby={erro || ajuda ? `${id}-d` : undefined}
          {...resto}
        />
      </label>
      <Mensagem id={`${id}-d`} erro={erro} ajuda={ajuda} />
    </div>
  )
}

type PropsBusca = {
  valor: string
  aoMudar: (valor: string) => void
  placeholder?: string
}

/** Busca do Figma (48 px, lupa à esquerda); filtra enquanto a pessoa digita. */
export function Busca({ valor, aoMudar, placeholder = 'Buscar…' }: PropsBusca) {
  return (
    <label className="flex h-12 items-center gap-3 rounded-xl border-[1.5px] border-borda bg-white px-4 focus-within:border-secao">
      <i className="ri-search-line text-lg text-texto-2" aria-hidden="true" />
      <input
        type="search"
        value={valor}
        onChange={(evento) => aoMudar(evento.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="flex-1 bg-transparent text-sm font-medium outline-none placeholder:text-texto-2"
      />
    </label>
  )
}

type Filtro<T extends string> = { valor: T; nome: string; total?: number }

/** Filtros em texto do Figma ("Todos (3) · Enviados pela marca (2) · ..."). */
export function Filtros<T extends string>({ opcoes, valor, aoMudar }: { opcoes: Filtro<T>[]; valor: T; aoMudar: (valor: T) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-medium" role="group" aria-label="Filtros">
      {opcoes.map((opcao, indice) => (
        <span key={opcao.valor} className="flex items-center gap-2">
          {indice > 0 && <span className="text-cinza-400">·</span>}
          <button
            type="button"
            aria-pressed={opcao.valor === valor}
            onClick={() => aoMudar(opcao.valor)}
            className={opcao.valor === valor ? 'font-bold text-secao-texto underline underline-offset-4' : 'text-texto-2 hover:text-tinta'}
          >
            {opcao.nome}
            {opcao.total !== undefined && ` (${opcao.total})`}
          </button>
        </span>
      ))}
    </div>
  )
}
