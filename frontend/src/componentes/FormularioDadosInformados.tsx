// Os números que só a marca sabe (visitantes, cadastros, brindes): o mesmo formulário na aba Aplicações e no cartão do Atlas.
// [VALIDACAO] Aqui só se avisa antes do envio; a API confere tudo de novo (catálogo, limites e o limite entre campos).
import { useId, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from 'react'
import { ErroApi } from '../api/cliente'
import type { CampoCatalogo, ValorInformado, ValorVigente } from '../api/dados'
import { data, numero } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Campo } from './Campo'

export type CampoDoFormulario = CampoCatalogo & { vigente?: ValorVigente | null; obrigatorio?: boolean }

type Props = {
  campos: CampoDoFormulario[]
  textoEnviar: string
  aoEnviar: (valores: ValorInformado[]) => Promise<void>
  /** Chamado no começo de cada tentativa de enviar, antes da conferência dos campos. */
  aoTentar?: () => void
  /** Na aba, só vai o que mudou em relação ao valor atual; no cartão, vai tudo o que foi preenchido. */
  somenteMudados?: boolean
  /** Botões ao lado do enviar (ex.: "Agora não" no cartão do Atlas). */
  extra?: ReactNode
  desabilitado?: boolean
  /** No cartão do Atlas: campos de vidro sobre o grafite e o enviar como chip branco, como no Figma. */
  escuro?: boolean
}

/** Campo do cartão do Atlas: rótulo em caixa alta, vidro translúcido e a mensagem logo abaixo, em branco. */
function CampoEscuro({ rotulo, erro, ajuda, ...resto }: InputHTMLAttributes<HTMLInputElement> & { rotulo: string; erro?: string; ajuda?: string }) {
  const id = useId()
  return (
    <div>
      <label
        htmlFor={id}
        className={`flex cursor-text flex-col gap-1 rounded-[14px] border bg-white/8 px-4 py-2.5 transition-colors ${erro ? 'border-[#ff8aa1]' : 'border-white/16 focus-within:border-white/50'}`}
      >
        <span className="text-[11px] font-bold tracking-[0.12em] text-white/64 uppercase">{rotulo}</span>
        <input
          id={id}
          className="w-full bg-transparent text-[15px] font-semibold text-white outline-none placeholder:font-medium placeholder:text-white/45"
          aria-invalid={erro ? true : undefined}
          aria-describedby={erro || ajuda ? `${id}-d` : undefined}
          {...resto}
        />
      </label>
      {(erro || ajuda) && (
        <p id={`${id}-d`} className={`mt-1.5 px-1 text-xs font-medium ${erro ? 'text-[#ff8aa1]' : 'text-white/60'}`}>
          {erro ?? ajuda}
        </p>
      )}
    </div>
  )
}

/** "1.200" e "1200" valem 1200; vírgula, letra ou sinal não são número inteiro. */
function lerInteiro(texto: string): number | null {
  const limpo = texto.replace(/[\s.]/g, '')
  return /^\d+$/.test(limpo) ? Number(limpo) : null
}

function seloDoValor(vigente: ValorVigente) {
  const quem = vigente.informadoPor?.nome ?? 'alguém da parceria'
  const pelo = vigente.origem === 'ATLAS' ? ', pelo Atlas' : ''
  return `Atual: ${numero(vigente.valor)} · informado por ${quem} em ${data(vigente.informadoEm)}${pelo}`
}

export function FormularioDadosInformados({
  campos,
  textoEnviar,
  aoEnviar,
  aoTentar,
  somenteMudados = false,
  extra,
  desabilitado = false,
  escuro = false,
}: Props) {
  const [textos, setTextos] = useState<Record<string, string>>({})
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  /** O valor que vale para o limite de outro campo: o digitado agora ou, sem ele, o atual. */
  function valorEfetivo(chave: string): number | null {
    const digitado = textos[chave]?.trim()
    if (digitado) return lerInteiro(digitado)
    return campos.find((campo) => campo.chave === chave)?.vigente?.valor ?? null
  }

  function conferir(): { valores: ValorInformado[]; problemas: Record<string, string> } {
    const valores: ValorInformado[] = []
    const problemas: Record<string, string> = {}
    for (const campo of campos) {
      const texto = textos[campo.chave]?.trim() ?? ''
      if (!texto) {
        if (campo.obrigatorio) problemas[campo.chave] = 'Informe este número.'
        continue
      }
      const valor = lerInteiro(texto)
      if (valor === null) problemas[campo.chave] = 'Use só números inteiros (ex.: 1200).'
      else if (campo.minimo !== null && valor < campo.minimo) problemas[campo.chave] = `Não pode ser menor que ${numero(campo.minimo)}.`
      else if (campo.maximo !== null && valor > campo.maximo) problemas[campo.chave] = `Não pode passar de ${numero(campo.maximo)}.`
      else {
        const limite = campo.limiteCampo ? campos.find((outro) => outro.chave === campo.limiteCampo) : undefined
        const teto = limite ? valorEfetivo(limite.chave) : null
        if (limite && teto !== null && valor > teto) problemas[campo.chave] = `Não pode passar de ${limite.rotulo} (${numero(teto)}).`
        else if (!somenteMudados || valor !== campo.vigente?.valor) valores.push({ chave: campo.chave, valor })
      }
    }
    return { valores, problemas }
  }

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    // Avisa quem usa o formulário que uma tentativa começou: um "salvo" de antes não pode ficar junto do erro desta (U06).
    aoTentar?.()
    const { valores, problemas } = conferir()
    setErros(problemas)
    setFalha(null)
    if (Object.keys(problemas).length) return
    if (!valores.length) return setFalha(somenteMudados ? 'Nenhum número mudou. Preencha o valor novo de algum campo.' : 'Preencha pelo menos um número.')
    setEnviando(true)
    try {
      await aoEnviar(valores)
      setTextos({})
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        // Quando o erro é de um campo, a frase dele já aparece junto ao campo; o aviso geral fica para o resto.
        if (!Object.keys(erro.campos).some((chave) => campos.some((campo) => campo.chave === chave))) setFalha(erro.message)
      } else setFalha('Não foi possível salvar os números.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
      <div className={escuro ? 'grid gap-3 @md:grid-cols-2' : 'grid gap-4 md:grid-cols-2'}>
        {campos.map((campo) => {
          const propriedades = {
            rotulo: campo.unidade ? `${campo.rotulo} (${campo.unidade})` : campo.rotulo,
            inputMode: 'numeric' as const,
            autoComplete: 'off',
            placeholder: campo.vigente ? numero(campo.vigente.valor) : 'Não informado',
            value: textos[campo.chave] ?? '',
            onChange: (evento: { target: { value: string } }) => setTextos((atuais) => ({ ...atuais, [campo.chave]: evento.target.value })),
            erro: erros[campo.chave],
            ajuda: campo.vigente ? seloDoValor(campo.vigente) : (campo.ajuda ?? undefined),
            disabled: desabilitado || enviando,
          }
          return escuro ? <CampoEscuro key={campo.chave} {...propriedades} /> : <Campo key={campo.chave} {...propriedades} />
        })}
      </div>
      {falha &&
        (escuro ? (
          <p className="text-sm font-semibold text-[#ff8aa1]" role="alert">
            {falha}
          </p>
        ) : (
          <Aviso tipo="falha" titulo={falha} />
        ))}
      {escuro ? (
        <div className="flex flex-wrap items-center gap-2.5">
          <button
            type="submit"
            disabled={desabilitado || enviando}
            className="inline-flex h-[38px] items-center gap-2 rounded-full bg-white pr-[18px] pl-4 text-[13px] font-bold text-atlas-azul hover:bg-white/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <i className={enviando ? 'ri-loader-4-line animate-spin text-base' : 'ri-send-plane-2-line text-base'} aria-hidden="true" />
            {textoEnviar}
          </button>
          {extra}
        </div>
      ) : (
        <div className="flex flex-col-reverse gap-3 sm:flex-row">
          {extra}
          <Botao type="submit" carregando={enviando} disabled={desabilitado} icone="ri-save-line">
            {textoEnviar}
          </Botao>
        </div>
      )}
    </form>
  )
}
