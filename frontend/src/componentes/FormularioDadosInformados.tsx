// Os números que só a marca sabe (visitantes, cadastros, brindes): o mesmo formulário na aba Aplicações e no cartão do Atlas.
// [VALIDACAO] Aqui só se avisa antes do envio; a API confere tudo de novo (catálogo, limites e o limite entre campos).
import { useState, type FormEvent, type ReactNode } from 'react'
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
  /** Na aba, só vai o que mudou em relação ao valor atual; no cartão, vai tudo o que foi preenchido. */
  somenteMudados?: boolean
  /** Botões ao lado do enviar (ex.: "Agora não" no cartão do Atlas). */
  extra?: ReactNode
  desabilitado?: boolean
  /** Uma coluna e botões na largura toda, para caber no painel do Atlas. */
  compacto?: boolean
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
  somenteMudados = false,
  extra,
  desabilitado = false,
  compacto = false,
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
      <div className={compacto ? 'grid gap-3' : 'grid gap-4 md:grid-cols-2'}>
        {campos.map((campo) => (
          <Campo
            key={campo.chave}
            rotulo={campo.unidade ? `${campo.rotulo} (${campo.unidade})` : campo.rotulo}
            inputMode="numeric"
            autoComplete="off"
            placeholder={campo.vigente ? numero(campo.vigente.valor) : 'Não informado'}
            value={textos[campo.chave] ?? ''}
            onChange={(evento) => setTextos((atuais) => ({ ...atuais, [campo.chave]: evento.target.value }))}
            erro={erros[campo.chave]}
            ajuda={campo.vigente ? seloDoValor(campo.vigente) : (campo.ajuda ?? undefined)}
            disabled={desabilitado || enviando}
          />
        ))}
      </div>
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div className={compacto ? 'flex flex-col-reverse gap-2' : 'flex flex-col-reverse gap-3 sm:flex-row'}>
        {extra}
        <Botao type="submit" carregando={enviando} disabled={desabilitado} icone="ri-save-line" className={compacto ? 'w-full min-w-0' : ''}>
          {textoEnviar}
        </Botao>
      </div>
    </form>
  )
}
