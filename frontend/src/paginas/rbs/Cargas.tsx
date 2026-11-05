// Cargas da RBS: envio das planilhas de veiculação, de pesquisa e do plano comercial, resultado e linhas rejeitadas com o motivo.
// [ITEM-5] [CARGA]
// [DUPLICIDADE] Reenviar a mesma planilha não muda a contagem: as linhas repetidas entram como duplicadas.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { baixarLoteDaComprovacao } from '../../api/comprovacao'
import { listarEventos } from '../../api/parcerias'
import { enviarCarga, listarCargas, listarRejeicoes, type Carga, type Rejeicao } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { Tabela } from '../../componentes/Tabela'
import { dataHora, numero, tamanho } from '../../util/formatar'

const COLUNAS: Record<Carga['tipo'], string> = {
  VEICULACAO: 'patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link',
  PESQUISA: 'patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes',
  PLANO:
    'patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina',
}

const NOME_TIPO: Record<Carga['tipo'], string> = {
  VEICULACAO: 'Veiculação de mídia',
  PESQUISA: 'Resultados de pesquisa',
  PLANO: 'Plano comercial',
}

function Resultado({ carga }: { carga: Carga }) {
  return (
    <div className="grid gap-4 md:grid-cols-4">
      <Indicador rotulo="Lidas" valor={numero(carga.linhasLidas)} legenda="Linhas da planilha" />
      <Indicador rotulo="Inseridas" valor={numero(carga.linhasInseridas)} legenda="Entraram na base" />
      <Indicador rotulo="Duplicadas" valor={numero(carga.linhasDuplicadas)} legenda="Já estavam na base" />
      <Indicador rotulo="Rejeitadas" valor={numero(carga.linhasRejeitadas)} legenda="Veja o motivo abaixo" />
    </div>
  )
}

function Rejeicoes({ carga }: { carga: Carga }) {
  const [pagina, setPagina] = useState(0)
  const rejeicoes = useDados(() => listarRejeicoes(carga.id, pagina), [carga.id, pagina])
  return (
    <Cartao titulo={`Rejeições da carga ${carga.id}`}>
      <Carregando dados={rejeicoes} vazio={(dado) => dado.itens.length === 0} textoVazio="Nenhuma linha rejeitada nesta carga.">
        {(dado) => (
          <>
            <Tabela<Rejeicao>
              linhas={dado.itens}
              chave={(item) => `${item.linha}-${item.campo}`}
              colunas={[
                { titulo: 'Linha', celula: (item) => item.linha, classe: 'w-16' },
                { titulo: 'Campo', celula: (item) => item.campo ?? '—' },
                { titulo: 'Motivo', celula: (item) => <span className="font-semibold">{item.motivo}</span> },
                { titulo: 'Conteúdo da linha', celula: (item) => <code className="text-xs break-all text-texto-2">{item.conteudo ?? '—'}</code> },
              ]}
            />
            <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
          </>
        )}
      </Carregando>
    </Cartao>
  )
}

/** R3.3: a RBS baixa a comprovação mensal de todas as Master ativas da edição, uma aba por parceria. */
function LoteDaComprovacao() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const [baixando, setBaixando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const escolhido = (eventos.dado ?? []).find((item) => String(item.id) === eventoId) ?? eventos.dado?.[0]

  async function baixar() {
    if (!escolhido) return
    setBaixando(true)
    setFalha(null)
    try {
      await baixarLoteDaComprovacao(escolhido.id, escolhido.edicao)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar a comprovação.')
    } finally {
      setBaixando(false)
    }
  }

  return (
    <Cartao titulo="Comprovação mensal das Master">
      <p className="mb-4 text-sm font-medium text-texto-2">
        Uma planilha .xlsx com uma aba por parceria Master ativa da edição: o exibido de cada mês, por tipo de mídia e unidade. Cada
        marca Master baixa a sua na aba Mídia.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-full sm:w-64">
          <Seletor rotulo="Edição da comprovação" value={escolhido ? String(escolhido.id) : ''} onChange={(campo) => setEventoId(campo.target.value)}>
            {(eventos.dado ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
        </div>
        <Botao icone="ri-file-excel-2-line" carregando={baixando} disabled={!escolhido} onClick={baixar}>
          Baixar comprovação das Master
        </Botao>
      </div>
      {falha && <Aviso tipo="falha" titulo={falha} className="mt-4" />}
    </Cartao>
  )
}

export function Cargas() {
  const eventos = useDados(listarEventos, [])
  const [pagina, setPagina] = useState(0)
  const cargas = useDados(() => listarCargas(pagina), [pagina])
  const [tipo, setTipo] = useState<Carga['tipo']>('VEICULACAO')
  const [eventoId, setEventoId] = useState('')
  const [escopo, setEscopo] = useState<'ATIVACAO' | 'GERAL'>('ATIVACAO')
  const [arquivo, setArquivo] = useState<File | null>(null)
  // O campo é recriado depois de cada envio: sem isso, escolher de novo o mesmo arquivo não dispara o onChange.
  const [campoDoArquivo, setCampoDoArquivo] = useState(0)
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [ultima, setUltima] = useState<Carga | null>(null)
  const [selecionada, setSelecionada] = useState<Carga | null>(null)
  const evento = eventoId || String(eventos.dado?.[0]?.id ?? '')

  /** Ao escolher outro arquivo, o resultado da carga anterior sai da tela, e um arquivo que não é CSV é recusado na hora (U07). */
  function escolher(escolhido: File | null) {
    setUltima(null)
    if (escolhido && !escolhido.name.toLowerCase().endsWith('.csv')) {
      setArquivo(null)
      setCampoDoArquivo((atual) => atual + 1)
      return setFalha(`${escolhido.name} não é CSV. A carga aceita só planilha em CSV (UTF-8, vírgula como separador); no Excel, use Salvar como › CSV UTF-8.`)
    }
    setFalha(null)
    setArquivo(escolhido)
  }

  async function enviar(evento_: FormEvent) {
    evento_.preventDefault()
    setUltima(null)
    if (!arquivo) return setFalha('Escolha a planilha CSV.')
    if (!evento) return setFalha('Escolha a edição.')
    setFalha(null)
    setEnviando(true)
    try {
      const carga = await enviarCarga({ arquivo, tipo, eventoId: Number(evento), escopo })
      setUltima(carga)
      setSelecionada(carga)
      setArquivo(null)
      setCampoDoArquivo((atual) => atual + 1)
      cargas.recarregar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        // Erro do arquivo inteiro (vazio, fora de UTF-8): a frase do campo "arquivo" já diz tudo, sem o "Confira os campos".
        setFalha(erro.campos.arquivo ?? erro.message)
      } else setFalha('Não foi possível enviar a planilha.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Cargas"
        subtitulo="Envie as planilhas de veiculação, de pesquisa e do plano comercial, veja o resultado de cada carga e baixe a comprovação mensal das Master."
      />

      <Cartao titulo="Enviar planilha">
        <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
          <div className="grid gap-4 md:grid-cols-3">
            <Seletor rotulo="Tipo" value={tipo} onChange={(campo) => setTipo(campo.target.value as Carga['tipo'])}>
              <option value="VEICULACAO">Veiculação de mídia</option>
              <option value="PESQUISA">Resultados de pesquisa</option>
              <option value="PLANO">Plano comercial (previsto de mídia e aplicações)</option>
            </Seletor>
            <Seletor rotulo="Edição" value={evento} onChange={(campo) => setEventoId(campo.target.value)}>
              {(eventos.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome} {item.edicao}
                </option>
              ))}
            </Seletor>
            {tipo === 'PESQUISA' && (
              <Seletor rotulo="Escopo" value={escopo} onChange={(campo) => setEscopo(campo.target.value as 'ATIVACAO' | 'GERAL')}>
                <option value="ATIVACAO">Pesquisa da ativação (por marca)</option>
                <option value="GERAL">Pesquisa geral do festival</option>
              </Seletor>
            )}
          </div>
          <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-tinta bg-cinza-100 px-4 py-4 text-sm">
            <i className="ri-file-excel-2-line text-2xl" aria-hidden="true" />
            <span className="flex-1 font-semibold">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher a planilha CSV (UTF-8, vírgula como separador)'}</span>
            <input key={campoDoArquivo} type="file" accept=".csv,text/csv" className="sr-only" onChange={(campo) => escolher(campo.target.files?.[0] ?? null)} />
          </label>
          <p className="text-xs text-texto-2">
            Colunas esperadas: <code>{COLUNAS[tipo]}</code>. Há exemplos em docs/exemplos no repositório.
          </p>
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div>
            <Botao type="submit" carregando={enviando} disabled={!arquivo} icone="ri-upload-cloud-2-line">
              Enviar e processar
            </Botao>
          </div>
        </form>
      </Cartao>

      {ultima && (
        <>
          <Aviso titulo={`Carga ${ultima.id} processada · ${NOME_TIPO[ultima.tipo]}`}>
            Linhas lidas = inseridas + duplicadas + rejeitadas. Reenviar a mesma planilha não insere nada de novo.
          </Aviso>
          <Resultado carga={ultima} />
        </>
      )}

      <LoteDaComprovacao />

      <Cartao titulo="Cargas feitas">
        <Carregando dados={cargas} vazio={(dado) => dado.itens.length === 0} textoVazio="Nenhuma carga ainda.">
          {(dado) => (
            <>
              <Tabela<Carga>
                linhas={dado.itens}
                chave={(item) => item.id}
                aoClicar={setSelecionada}
                colunas={[
                  { titulo: 'Carga', celula: (item) => <span className="font-semibold">#{item.id}</span> },
                  { titulo: 'Tipo', celula: (item) => NOME_TIPO[item.tipo] },
                  { titulo: 'Enviada em', celula: (item) => dataHora(item.executadaEm) },
                  { titulo: 'Por', celula: (item) => item.executadaPor?.nome ?? '—' },
                  { titulo: 'Lidas', celula: (item) => numero(item.linhasLidas), classe: 'text-right' },
                  { titulo: 'Inseridas', celula: (item) => numero(item.linhasInseridas), classe: 'text-right' },
                  { titulo: 'Duplicadas', celula: (item) => numero(item.linhasDuplicadas), classe: 'text-right' },
                  { titulo: 'Rejeitadas', celula: (item) => numero(item.linhasRejeitadas), classe: 'text-right' },
                ]}
              />
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      </Cartao>

      {selecionada && <Rejeicoes key={selecionada.id} carga={selecionada} />}
    </div>
  )
}
