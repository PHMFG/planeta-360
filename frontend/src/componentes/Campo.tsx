// Campo do Figma (P360/Campo): cartão branco com o rótulo em caixa alta e o valor logo abaixo. [ITEM-3] [VALIDACAO]
// O Figma só desenha o repouso; foco e erro seguem a regra do S00: a mensagem aparece junto ao campo.
import { useId, type InputHTMLAttributes } from 'react'

type PropsCampo = InputHTMLAttributes<HTMLInputElement> & {
  rotulo: string
  erro?: string
  ajuda?: string
}

export function Campo({ rotulo, erro, ajuda, className = '', id, ...resto }: PropsCampo) {
  const gerado = useId()
  const campoId = id ?? gerado
  const descricaoId = `${campoId}-descricao`
  const borda = erro
    ? 'border-estado-falha'
    : 'border-borda focus-within:border-secao'

  return (
    <div className={className}>
      <label
        htmlFor={campoId}
        className={`flex cursor-text flex-col gap-1 rounded-xl border-[1.5px] bg-white px-4 py-3 transition-colors ${borda}`}
      >
        <span className="texto-sobretitulo text-texto-2">{rotulo}</span>
        <input
          id={campoId}
          className="w-full bg-transparent text-sm font-semibold text-tinta outline-none placeholder:font-medium placeholder:text-cinza-400"
          aria-invalid={erro ? true : undefined}
          aria-describedby={erro || ajuda ? descricaoId : undefined}
          {...resto}
        />
      </label>
      {(erro || ajuda) && (
        <p id={descricaoId} className={`mt-1.5 px-1 text-xs font-medium ${erro ? 'text-[#c8203f]' : 'text-texto-2'}`}>
          {erro ?? ajuda}
        </p>
      )}
    </div>
  )
}
