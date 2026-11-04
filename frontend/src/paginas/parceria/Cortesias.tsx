// Aba Cortesias (Figma P04 e F04; requisito R5.4 da RBS): a RBS informa a cota por setor e tipo/dia, com o acesso e o
// shuttle, e o prazo de destinação da edição; a marca vê a cota, as destinadas e as disponíveis, e destina dentro do
// disponível. O destinatário é só um nome (D8 do plano). As entregas da aba continuam embaixo.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import {
  DIAS,
  NOME_DIA,
  NOME_SETOR,
  SETORES,
  definirPrazoDeCortesias,
  destinarCortesias,
  editarDestinacao,
  gravarCotasDeCortesia,
  obterCortesias,
  retirarDestinacao,
  type CelulaCortesia,
  type CelulaDaRbs,
  type Cortesias as DadosDeCortesias,
  type DiaCortesia,
  type Destinacao,
  type Setor,
} from '../../api/cortesias'
import { useDados } from '../../api/useDados'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo, focarPrimeiroErro } from '../../componentes/Campo'
import { Cartao, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { SECOES } from '../../componentes/secoes'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora, numero, porcentagem } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

const SECAO = SECOES.find((secao) => secao.chave === 'cortesias')!

function nomeDaCelula(setor: Setor, dia: DiaCortesia) {
  return `${NOME_SETOR[setor]} · ${NOME_DIA[dia]}`
}

/** "1.200" vale 1200; vazio, letra ou sinal não são quantidade. */
function lerQuantidade(texto: string): number | null {
  const limpo = texto.replace(/[\s.]/g, '')
  return /^\d+$/.test(limpo) ? Number(limpo) : null
}

export function Cortesias() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'CORTESIAS')
  const podeVer = rbs || estado === 'LIBERADA'

  return (
    <div data-secao="cortesias" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Cortesias"
        subtitulo={rbs ? 'Cota por setor e dia, prazo de destinação e o que a marca já destinou.' : 'Destinação conforme a sua cota e o contrato.'}
      />
      <AvisoAbaFechada parceria={parceria} estado={estado} nome={SECAO.nome} rbs={rbs} />
      {podeVer && <ConteudoDeCortesias rbs={rbs} />}
      {podeVer && <EntregasDaAba aba="CORTESIAS" />}
    </div>
  )
}

function ConteudoDeCortesias({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const dados = useDados(() => obterCortesias(parceria.id), [parceria.id])
  useRecarregarSeAbaFechou(dados)
  const [destinando, setDestinando] = useState(false)
  const [aviso, setAviso] = useState<string | null>(null)

  return (
    <Carregando dados={dados}>
      {(cortesias) => (
        <>
          <Aviso titulo="Quantidades por setor, tipo e dia">
            Consulte cada categoria separadamente. A disponibilidade não é uma hierarquia entre cotas: o passaporte vale os dois dias e
            conta à parte da sexta e do sábado.
          </Aviso>

          {rbs && <GradeDaRbs cortesias={cortesias} aoGravar={dados.recarregar} />}

          <Cartao titulo="Destinação de ingressos">
            {cortesias.cotas.length === 0 ? (
              <Aviso tipo="ausente" titulo="A RBS ainda não informou a cota de cortesias desta parceria">
                {rbs ? 'Preencha a cota acima.' : 'Quando a RBS informar, as quantidades aparecem aqui.'}
              </Aviso>
            ) : (
              <>
                <div className="mb-4 grid gap-3 sm:grid-cols-3">
                  <Indicador rotulo="Cota" valor={numero(cortesias.totais.cota)} legenda="Somando setores e dias" />
                  <Indicador rotulo="Destinadas" valor={numero(cortesias.totais.destinadas)} legenda={porcentagem(cortesias.totais.percentual)} />
                  <Indicador rotulo="Disponíveis" valor={numero(cortesias.totais.disponiveis)} legenda="Ainda para destinar" />
                </div>
                <Tabela<CelulaCortesia>
                  rotulo="Destinação de ingressos"
                  linhas={cortesias.cotas}
                  chave={(celula) => celula.id}
                  colunas={[
                    { titulo: 'Setor', celula: (celula) => <span className="font-semibold">{NOME_SETOR[celula.setor]}</span> },
                    { titulo: 'Tipo / dia', celula: (celula) => NOME_DIA[celula.dia] },
                    { titulo: 'Quantidade', celula: (celula) => numero(celula.cota), classe: 'text-right' },
                    { titulo: 'Destinadas', celula: (celula) => numero(celula.destinadas), classe: 'text-right' },
                    { titulo: 'Disponíveis', celula: (celula) => <span className="font-bold">{numero(celula.disponiveis)}</span>, classe: 'text-right' },
                    { titulo: 'Acesso', celula: (celula) => celula.acesso ?? '—' },
                    { titulo: 'Shuttle', celula: (celula) => (celula.comShuttle ? 'Com shuttle' : '—') },
                  ]}
                />
              </>
            )}
          </Cartao>

          {/* Figma P04: as orientações são publicadas pela RBS como entregas desta aba, na lista do fim da página. */}
          <Cartao>
            <p className="text-sm font-semibold">Orientações de distribuição</p>
            <p className="text-xs font-medium text-texto-2">Publicadas pela RBS na lista de entregas desta aba, no fim da página · somente consulta</p>
          </Cartao>

          <Aviso titulo="Liberação e destinação">
            A RBS libera as quantidades contratadas. Você destina as cortesias por setor, tipo e dia, respeitando o disponível.{' '}
            {cortesias.cortesiasPrazo
              ? cortesias.prazoEncerrado
                ? `O prazo para destinar terminou em ${data(cortesias.cortesiasPrazo)}: para mudar, peça o ajuste à RBS.`
                : `O prazo para destinar vai até ${data(cortesias.cortesiasPrazo)}.`
              : 'A RBS ainda não definiu um prazo para destinar.'}
          </Aviso>

          {aviso && (
            <p className="text-sm font-semibold text-secao-texto" role="status">
              {aviso}
            </p>
          )}

          {cortesias.cotas.length > 0 && (
            <div>
              <Botao
                onClick={() => {
                  setAviso(null)
                  setDestinando(true)
                }}
                disabled={!rbs && cortesias.prazoEncerrado}
              >
                Destinar cortesias
              </Botao>
              {!rbs && cortesias.prazoEncerrado && (
                <p className="mt-2 text-xs font-medium text-texto-2">O prazo terminou: a destinação agora é com a RBS.</p>
              )}
            </div>
          )}

          <DestinacoesFeitas cortesias={cortesias} rbs={rbs} aoMudar={dados.recarregar} />

          <FormularioDeDestinacao
            aberto={destinando}
            cortesias={cortesias}
            aoFechar={() => setDestinando(false)}
            aoDestinar={(texto) => {
              setDestinando(false)
              setAviso(texto)
              dados.recarregar()
            }}
          />
        </>
      )}
    </Carregando>
  )
}

/** Figma F04: setor, tipo/dia, quantidade e destinatário, com a disponibilidade da combinação e a revisão antes de gravar. */
function FormularioDeDestinacao({
  aberto,
  cortesias,
  aoFechar,
  aoDestinar,
}: {
  aberto: boolean
  cortesias: DadosDeCortesias
  aoFechar: () => void
  aoDestinar: (aviso: string) => void
}) {
  const parceria = useParceria()
  const setores = SETORES.filter((setor) => cortesias.cotas.some((celula) => celula.setor === setor))
  const [setor, setSetor] = useState<Setor>(setores[0] ?? 'ARENA')
  const dias = DIAS.filter((dia) => cortesias.cotas.some((celula) => celula.setor === setor && celula.dia === dia))
  const [dia, setDia] = useState<DiaCortesia>(dias[0] ?? 'SEXTA')
  const [quantidade, setQuantidade] = useState('')
  const [destinatario, setDestinatario] = useState('')
  const [revisando, setRevisando] = useState(false)
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  const celula = cortesias.cotas.find((item) => item.setor === setor && item.dia === (dias.includes(dia) ? dia : dias[0]))
  const quantos = lerQuantidade(quantidade)
  const passa = celula !== undefined && quantos !== null && quantos > celula.disponiveis

  function conferir(): boolean {
    const novos: Record<string, string> = {}
    if (!celula) novos.dia = 'Escolha um setor e um tipo/dia com cota.'
    if (quantos === null || quantos < 1) novos.quantidade = 'Informe uma quantidade inteira, a partir de 1.'
    else if (passa) novos.quantidade = `Só restam ${numero(celula!.disponiveis)} para ${nomeDaCelula(celula!.setor, celula!.dia)}.`
    if (!destinatario.trim()) novos.destinatario = 'Informe o destino ou o destinatário.'
    setErros(novos)
    if (Object.keys(novos).length) focarPrimeiroErro()
    return Object.keys(novos).length === 0
  }

  async function confirmar(evento: FormEvent) {
    evento.preventDefault()
    if (!celula || quantos === null) return
    setEnviando(true)
    setFalha(null)
    try {
      await destinarCortesias(parceria.id, { setor: celula.setor, dia: celula.dia, quantidade: quantos, destinatario: destinatario.trim() })
      aoDestinar(`${numero(quantos)} ${quantos === 1 ? 'cortesia destinada' : 'cortesias destinadas'} em ${nomeDaCelula(celula.setor, celula.dia)}.`)
      setQuantidade('')
      setDestinatario('')
      setRevisando(false)
    } catch (erro) {
      // A API não grava nada; o que a pessoa digitou fica, e o erro aponta o campo (Figma F04: "manter o preenchimento").
      setRevisando(false)
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        setFalha(erro.message)
      } else setFalha('Não foi possível destinar agora.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal titulo="Destinar cortesias" legenda="O cliente distribui dentro dos limites liberados pela RBS." aberto={aberto} aoFechar={aoFechar}>
      <form onSubmit={revisando ? confirmar : (evento) => (evento.preventDefault(), conferir() && setRevisando(true))} className="flex flex-col gap-4" noValidate>
        {revisando && celula && quantos !== null ? (
          <Aviso titulo="Revise a destinação">
            {numero(quantos)} {quantos === 1 ? 'cortesia' : 'cortesias'} de {nomeDaCelula(celula.setor, celula.dia)} para {destinatario.trim()}. Depois de
            confirmar, sobram {numero(celula.disponiveis - quantos)}.
          </Aviso>
        ) : (
          <>
            <div className="grid gap-4 sm:grid-cols-2">
              <Seletor rotulo="Setor" value={setor} onChange={(evento) => setSetor(evento.target.value as Setor)}>
                {setores.map((item) => (
                  <option key={item} value={item}>
                    {NOME_SETOR[item]}
                  </option>
                ))}
              </Seletor>
              <Seletor rotulo="Tipo / dia" value={celula?.dia ?? dia} onChange={(evento) => setDia(evento.target.value as DiaCortesia)} erro={erros.dia}>
                {dias.map((item) => (
                  <option key={item} value={item}>
                    {NOME_DIA[item]}
                  </option>
                ))}
              </Seletor>
              <Campo rotulo="Quantidade" inputMode="numeric" value={quantidade} onChange={(evento) => setQuantidade(evento.target.value)} erro={erros.quantidade} />
              <Campo
                rotulo="Destino / destinatário"
                maxLength={120}
                value={destinatario}
                onChange={(evento) => setDestinatario(evento.target.value)}
                erro={erros.destinatario}
                ajuda="Só o nome de quem recebe, ou o destino (ex.: Clientes VIP)."
              />
            </div>
            {celula && (
              <Aviso titulo="Disponibilidade">
                Quantidade liberada: {numero(celula.cota)} · já destinada: {numero(celula.destinadas)} · disponível: {numero(celula.disponiveis)}.
              </Aviso>
            )}
            <Aviso tipo={passa ? 'falha' : 'info'} titulo="Validação">
              {passa
                ? `A quantidade passa do disponível (${numero(celula!.disponiveis)}). O que você preencheu fica: ajuste a quantidade e revise de novo.`
                : 'Se exceder o disponível, o preenchimento fica e o saldo aparece aqui. Confira os dados antes de registrar a destinação.'}
            </Aviso>
          </>
        )}
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-col-reverse gap-3 sm:grid sm:grid-cols-2 sm:gap-4">
          <Botao variante="secundario" onClick={revisando ? () => setRevisando(false) : aoFechar} className="min-w-0">
            Voltar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0">
            {revisando ? 'Confirmar destinação' : 'Revisar destinação'}
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

/** As destinações feitas, com editar e retirar (a marca e a RBS mexem em qualquer uma da parceria). */
function DestinacoesFeitas({ cortesias, rbs, aoMudar }: { cortesias: DadosDeCortesias; rbs: boolean; aoMudar: () => void }) {
  const parceria = useParceria()
  const [editando, setEditando] = useState<{ celula: CelulaCortesia; destinacao: Destinacao } | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const linhas = cortesias.cotas.flatMap((celula) => celula.destinacoes.map((destinacao) => ({ celula, destinacao })))
  const travada = !rbs && cortesias.prazoEncerrado

  async function retirar(celula: CelulaCortesia, destinacao: Destinacao) {
    if (!window.confirm(`Retirar ${numero(destinacao.quantidade)} de ${nomeDaCelula(celula.setor, celula.dia)} destinadas a ${destinacao.destinatario}?`)) return
    setFalha(null)
    try {
      await retirarDestinacao(parceria.id, destinacao.id)
      aoMudar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível retirar a destinação.')
    }
  }

  if (linhas.length === 0) return null
  return (
    <Cartao titulo="Destinações registradas">
      {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
      <Tabela<(typeof linhas)[number]>
        rotulo="Destinações registradas"
        linhas={linhas}
        chave={(linha) => linha.destinacao.id}
        colunas={[
          { titulo: 'Destino / destinatário', celula: (linha) => <span className="font-semibold">{linha.destinacao.destinatario}</span> },
          { titulo: 'Setor e dia', celula: (linha) => nomeDaCelula(linha.celula.setor, linha.celula.dia) },
          { titulo: 'Quantidade', celula: (linha) => numero(linha.destinacao.quantidade), classe: 'text-right' },
          { titulo: 'Registrada', celula: (linha) => `${linha.destinacao.registradoPor.nome} · ${dataHora(linha.destinacao.registradoEm)}` },
          {
            titulo: 'Ações',
            celula: (linha) =>
              travada ? (
                <span className="text-xs text-texto-2">Com a RBS</span>
              ) : (
                <div className="flex justify-end gap-3 text-xs font-bold">
                  <button type="button" className="hover:underline" aria-label={`Editar a destinação de ${linha.destinacao.destinatario}`} onClick={() => setEditando(linha)}>
                    Editar
                  </button>
                  <button
                    type="button"
                    className="text-[#c8203f] hover:underline"
                    aria-label={`Retirar a destinação de ${linha.destinacao.destinatario}`}
                    onClick={() => retirar(linha.celula, linha.destinacao)}
                  >
                    Retirar
                  </button>
                </div>
              ),
          },
        ]}
      />
      {editando && <EdicaoDeDestinacao {...editando} aoFechar={() => setEditando(null)} aoSalvar={() => (setEditando(null), aoMudar())} />}
    </Cartao>
  )
}

function EdicaoDeDestinacao({
  celula,
  destinacao,
  aoFechar,
  aoSalvar,
}: {
  celula: CelulaCortesia
  destinacao: Destinacao
  aoFechar: () => void
  aoSalvar: () => void
}) {
  const parceria = useParceria()
  const [destinatario, setDestinatario] = useState(destinacao.destinatario)
  const [quantidade, setQuantidade] = useState(String(destinacao.quantidade))
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    const quantos = lerQuantidade(quantidade)
    if (quantos === null || quantos < 1) {
      setErros({ quantidade: 'Informe uma quantidade inteira, a partir de 1.' })
      focarPrimeiroErro()
      return
    }
    setEnviando(true)
    setFalha(null)
    try {
      await editarDestinacao(parceria.id, destinacao.id, { destinatario: destinatario.trim(), quantidade: quantos })
      aoSalvar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        setFalha(erro.message)
      } else setFalha('Não foi possível salvar.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal titulo="Editar destinação" legenda={`${nomeDaCelula(celula.setor, celula.dia)} · disponível agora: ${numero(celula.disponiveis)}`} aberto aoFechar={aoFechar}>
      <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
        <Campo rotulo="Destino / destinatário" maxLength={120} value={destinatario} onChange={(evento) => setDestinatario(evento.target.value)} erro={erros.destinatario} />
        <Campo rotulo="Quantidade" inputMode="numeric" value={quantidade} onChange={(evento) => setQuantidade(evento.target.value)} erro={erros.quantidade} />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-col-reverse gap-3 sm:grid sm:grid-cols-2 sm:gap-4">
          <Botao variante="secundario" onClick={aoFechar} className="min-w-0">
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0">
            Salvar destinação
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

/** A grade da RBS (D10 do plano): a cota de cada setor e tipo/dia, com o acesso e o shuttle, e o prazo da edição. */
function GradeDaRbs({ cortesias, aoGravar }: { cortesias: DadosDeCortesias; aoGravar: () => void }) {
  const parceria = useParceria()
  const inicial = () =>
    Object.fromEntries(
      SETORES.flatMap((setor) =>
        DIAS.map((dia) => {
          const celula = cortesias.cotas.find((item) => item.setor === setor && item.dia === dia)
          return [`${setor}|${dia}`, { quantidade: celula ? String(celula.cota) : '', acesso: celula?.acesso ?? '', comShuttle: celula?.comShuttle ?? false }]
        }),
      ),
    )
  const [linhas, setLinhas] = useState<Record<string, { quantidade: string; acesso: string; comShuttle: boolean }>>(inicial)
  const [prazo, setPrazo] = useState(cortesias.cortesiasPrazo ?? '')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  function mudar(chave: string, campo: 'quantidade' | 'acesso' | 'comShuttle', valor: string | boolean) {
    setLinhas((atuais) => ({ ...atuais, [chave]: { ...atuais[chave], [campo]: valor } }))
  }

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setFalha(null)
    setFeito(null)
    const novos: Record<string, string> = {}
    const mudancas: CelulaDaRbs[] = []
    for (const setor of SETORES) {
      for (const dia of DIAS) {
        const chave = `${setor}|${dia}`
        const linha = linhas[chave]
        const atual = cortesias.cotas.find((item) => item.setor === setor && item.dia === dia)
        if (!linha.quantidade.trim()) {
          // Vazio numa célula que existia retira a cota (quantidade 0); vazio numa que não existia não muda nada.
          if (atual) mudancas.push({ setor, dia, quantidade: 0, comShuttle: false, acesso: null })
          continue
        }
        const quantidade = lerQuantidade(linha.quantidade)
        if (quantidade === null) {
          novos[chave] = 'Use um número inteiro.'
          continue
        }
        const acesso = linha.acesso.trim() || null
        if (!atual || atual.cota !== quantidade || (atual.acesso ?? null) !== acesso || atual.comShuttle !== linha.comShuttle) {
          mudancas.push({ setor, dia, quantidade, comShuttle: linha.comShuttle, acesso })
        }
      }
    }
    setErros(novos)
    if (Object.keys(novos).length) return focarPrimeiroErro()
    setEnviando(true)
    try {
      if (mudancas.length) await gravarCotasDeCortesia(parceria.id, mudancas)
      if ((cortesias.cortesiasPrazo ?? '') !== prazo) await definirPrazoDeCortesias(parceria.evento.id, prazo || null)
      setFeito(mudancas.length || (cortesias.cortesiasPrazo ?? '') !== prazo ? 'Cota e prazo salvos. A marca vê ao abrir ou voltar à aba.' : 'Nada mudou.')
      aoGravar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setFalha(erro.message)
        // As frases da API vêm por célula (cotas[i].quantidade); a tela mostra a do primeiro campo, no aviso geral.
        const primeira = Object.values(erro.campos)[0]
        if (primeira) setFalha(primeira)
      } else setFalha('Não foi possível salvar a cota.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Cota de cortesias da parceria">
      <p className="mb-4 text-sm font-medium text-texto-2">
        Preencha a quantidade de cada setor e tipo/dia que o contrato dá. Deixe em branco o que a cota não tem. A cota não pode ficar abaixo do que a
        marca já destinou.
      </p>
      <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
        <div className="overflow-x-auto">
          <table className="w-full border-separate border-spacing-0 text-sm">
            <thead>
              <tr>
                {['Setor e tipo/dia', 'Quantidade', 'Acesso (portão)', 'Shuttle'].map((titulo) => (
                  <th key={titulo} scope="col" className="bg-cinza-100 px-3 py-2.5 text-left font-semibold first:rounded-l-[10px] last:rounded-r-[10px]">
                    {titulo}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {SETORES.flatMap((setor) =>
                DIAS.map((dia) => {
                  const chave = `${setor}|${dia}`
                  const nome = nomeDaCelula(setor, dia)
                  return (
                    <tr key={chave}>
                      <th scope="row" className="border-b border-borda px-3 py-2 text-left font-semibold">
                        {nome}
                      </th>
                      <td className="border-b border-borda px-3 py-2">
                        <input
                          aria-label={`Quantidade de ${nome}`}
                          inputMode="numeric"
                          className="w-20 rounded-lg border border-borda px-2 py-1.5"
                          value={linhas[chave].quantidade}
                          aria-invalid={erros[chave] ? true : undefined}
                          onChange={(evento) => mudar(chave, 'quantidade', evento.target.value)}
                        />
                        {erros[chave] && (
                          <span className="block text-xs text-[#c8203f]" role="alert">
                            {erros[chave]}
                          </span>
                        )}
                      </td>
                      <td className="border-b border-borda px-3 py-2">
                        <input
                          aria-label={`Acesso de ${nome}`}
                          maxLength={80}
                          className="w-32 rounded-lg border border-borda px-2 py-1.5"
                          value={linhas[chave].acesso}
                          onChange={(evento) => mudar(chave, 'acesso', evento.target.value)}
                        />
                      </td>
                      <td className="border-b border-borda px-3 py-2">
                        <input
                          type="checkbox"
                          aria-label={`Shuttle em ${nome}`}
                          className="size-4"
                          checked={linhas[chave].comShuttle}
                          onChange={(evento) => mudar(chave, 'comShuttle', evento.target.checked)}
                        />
                      </td>
                    </tr>
                  )
                }),
              )}
            </tbody>
          </table>
        </div>
        <Campo
          rotulo="Prazo para a marca destinar"
          type="date"
          value={prazo}
          onChange={(evento) => setPrazo(evento.target.value)}
          ajuda={`Vale para todas as marcas da edição ${parceria.evento.edicao}. Sem data, não há trava. Depois do prazo, só a RBS ajusta.`}
        />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {feito && (
          <p className="text-sm font-semibold text-secao-texto" role="status">
            {feito}
          </p>
        )}
        <div>
          <Botao type="submit" carregando={enviando}>
            Salvar cota e prazo
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}
