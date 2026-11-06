// "Números da edição" (requisito R8.2 da RBS): os números do festival que a RBS informa (público, marcas, horas de música),
// com unidade e fonte. Com ao menos um, o indicador "O festival em números" deixa de sair indisponível nos relatórios
// gerados depois; as versões já geradas não mudam (o relatório é uma fotografia).
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../api/cliente'
import { informarNumeroDaEdicao, listarNumerosDaEdicao, type NumeroDaEdicao } from '../api/posvenda'
import { useDados } from '../api/useDados'
import { numero } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Campo } from './Campo'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { Seletor } from './Formulario'
import { Modal } from './Modal'
import { Tabela } from './Tabela'
import { usarToast } from './Toast'

const VAZIO = { indicador: '', valor: '', qualificador: 'EXATO' as NumeroDaEdicao['qualificador'], unidade: '', fonte: '' }

const comoTexto = (linha: NumeroDaEdicao) => `${linha.qualificador === 'MAIS_DE' ? 'mais de ' : ''}${numero(linha.valor)} ${linha.unidade}`

export function NumerosDaEdicao({ eventoId, edicao }: { eventoId: number; edicao: number }) {
  const numeros = useDados(() => listarNumerosDaEdicao(eventoId), [eventoId])
  const [form, setForm] = useState(VAZIO)
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [aberto, setAberto] = useState(false)
  const avisar = usarToast()
  const [enviando, setEnviando] = useState(false)
  const mudar = (campo: keyof typeof VAZIO, valor: string) => setForm((atual) => ({ ...atual, [campo]: valor }))

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setErros({})
    setFalha(null)
    const valor = form.valor.trim().replace(/\./g, '').replace(',', '.')
    const faltam: Record<string, string> = {}
    if (!form.indicador.trim()) faltam.indicador = 'Informe o indicador.'
    if (!/^\d+(\.\d{1,2})?$/.test(valor) || Number(valor) === 0) faltam.valor = 'Use um número maior que zero, como 80000.'
    if (!form.unidade.trim()) faltam.unidade = 'Informe a unidade.'
    if (!form.fonte.trim()) faltam.fonte = 'Informe a fonte.'
    if (Object.keys(faltam).length) return setErros(faltam)
    setEnviando(true)
    try {
      const salvo = await informarNumeroDaEdicao(eventoId, {
        indicador: form.indicador.trim(),
        valor: Number(valor),
        qualificador: form.qualificador,
        unidade: form.unidade.trim(),
        fonte: form.fonte.trim(),
      })
      avisar(`${salvo.indicador}: ${comoTexto(salvo)}. Os relatórios gerados a partir de agora mostram este número.`)
      setForm(VAZIO)
      setAberto(false)
      numeros.recarregar()
    } catch (erro) {
      if (erro instanceof ErroApi && Object.keys(erro.campos).length) setErros(erro.campos)
      else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível salvar o número.')
    } finally {
      setEnviando(false)
    }
  }

  function abrir(inicial = VAZIO) {
    setForm(inicial)
    setErros({})
    setFalha(null)
    setAberto(true)
  }

  return (
    <Cartao
      titulo={`Números da edição ${edicao}`}
      extra={
        <Botao variante="secundario" icone="ri-add-line" onClick={() => abrir()}>
          Informar número
        </Botao>
      }
    >
      <p className="mb-4 text-sm font-medium text-texto-2">
        Os números do festival para “O festival em números” do relatório. Os relatórios já gerados não mudam: gere uma versão nova.
      </p>
      <Carregando dados={numeros} vazio={(lista) => lista.length === 0} textoVazio="Nenhum número informado: nos relatórios, “O festival em números” sai como indisponível.">
        {(lista) => (
          <Tabela<NumeroDaEdicao>
            rotulo="Números da edição"
            linhas={lista}
            chave={(linha) => linha.id}
            colunas={[
              { titulo: 'Indicador', celula: (linha) => <span className="font-semibold">{linha.indicador}</span> },
              { titulo: 'Número', celula: comoTexto },
              { titulo: 'Fonte', celula: (linha) => linha.fonte },
              {
                titulo: '',
                celula: (linha) => (
                  <button
                    type="button"
                    className="text-xs font-bold hover:underline"
                    aria-label={`Corrigir ${linha.indicador}`}
                    onClick={() => abrir({ indicador: linha.indicador, valor: String(linha.valor).replace('.', ','), qualificador: linha.qualificador, unidade: linha.unidade, fonte: linha.fonte })}
                  >
                    Corrigir
                  </button>
                ),
              },
            ]}
          />
        )}
      </Carregando>
      <Modal titulo="Informar um número" legenda={`Edição ${edicao} · o mesmo indicador informado de novo troca o valor`} aberto={aberto} aoFechar={() => setAberto(false)}>
        <form onSubmit={salvar} className="flex flex-col gap-4" noValidate aria-label="Informar um número da edição">
          <div className="grid grid-cols-[minmax(0,1fr)] gap-4 sm:grid-cols-2">
            <Campo rotulo="Indicador" value={form.indicador} onChange={(campo) => mudar('indicador', campo.target.value)} erro={erros.indicador} ajuda="Ex.: Público total" maxLength={80} />
            <div className="grid grid-cols-[minmax(0,1fr)] gap-4 sm:grid-cols-2">
              <Seletor rotulo="Qualificador" value={form.qualificador} onChange={(campo) => mudar('qualificador', campo.target.value)} erro={erros.qualificador}>
                <option value="EXATO">Exato</option>
                <option value="MAIS_DE">Mais de</option>
              </Seletor>
              <Campo rotulo="Valor" value={form.valor} onChange={(campo) => mudar('valor', campo.target.value)} erro={erros.valor} inputMode="decimal" maxLength={20} />
            </div>
            <Campo rotulo="Unidade" value={form.unidade} onChange={(campo) => mudar('unidade', campo.target.value)} erro={erros.unidade} ajuda="Ex.: pessoas" maxLength={20} />
            <Campo rotulo="Fonte" value={form.fonte} onChange={(campo) => mudar('fonte', campo.target.value)} erro={erros.fonte} ajuda="Ex.: RBS, balanço da edição" maxLength={200} />
          </div>
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div>
            <Botao type="submit" carregando={enviando} icone="ri-save-line">
              Salvar número
            </Botao>
          </div>
        </form>
      </Modal>
    </Cartao>
  )
}
