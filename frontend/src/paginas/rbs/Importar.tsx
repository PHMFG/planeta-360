// Importar (plano da base da RBS, E1 a E3): a porta de entrada dos dados. A pessoa da RBS diz de que edição e de quem é o
// arquivo, arrasta (planilha, Word, PDF, print, conversa) e o Atlas lê; a revisão acontece na página da importação.
// Arquivos no formato padrão da carga (CSV) continuam em Cargas, que vira o "histórico de cargas" desta tela.
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { NOME_DA_SITUACAO, NOME_DO_DESTINO, importar, listarImportacoes, type AlcanceDaImportacao, type ResumoDaImportacao } from '../../api/importacao'
import { listarEventos, listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { AreaEnvio } from '../../componentes/AreaEnvio'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { Tabela } from '../../componentes/Tabela'
import { dataHora } from '../../util/formatar'

const ACEITOS = '.xlsx,.xls,.csv,.docx,.pptx,.pdf,.txt,.png,.jpg,.jpeg'
const COTAS = [
  { codigo: 'MASTER', nome: 'Master' },
  { codigo: 'SUBCOTA_G', nome: 'Subcota G' },
  { codigo: 'SUBCOTA', nome: 'Subcota' },
]

/** A situação da importação como uma pílula, na cor que diz se falta alguma coisa. */
export function SituacaoDaImportacao({ importacao }: { importacao: Pick<ResumoDaImportacao, 'situacao'> }) {
  const cor: Record<ResumoDaImportacao['situacao'], string> = {
    LENDO: 'bg-estado-carregando/14 text-[#08788a]',
    EM_PREVIA: 'bg-estado-ausente/30 text-[#7a5a00]',
    CONFIRMADA: 'bg-aplicacoes/12 text-aplicacoes',
    DESCARTADA: 'bg-cinza-100 text-texto-2',
    RETIRADA: 'bg-cinza-100 text-texto-2',
    ERRO: 'bg-estado-falha/14 text-[#c8203f]',
  }
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold transition-colors duration-(--duracao-media) ${cor[importacao.situacao]}`}>
      {importacao.situacao === 'LENDO' && <i className="ri-loader-4-line animate-spin" aria-hidden="true" />}
      {NOME_DA_SITUACAO[importacao.situacao]}
    </span>
  )
}

function deQuem(item: ResumoDaImportacao) {
  if (item.parceria) return item.parceria.patrocinador
  if (item.cota) return `Cota ${item.cota.nome}`
  return `Todas as marcas · ${item.evento.edicao}`
}

export function Importar() {
  const navegar = useNavigate()
  const eventos = useDados(() => listarEventos(), [])
  const [eventoId, setEventoId] = useState('')
  const edicao = eventoId || String(eventos.dado?.[0]?.id ?? '')
  const parcerias = useDados(() => (edicao ? listarParcerias({ eventoId: Number(edicao) }) : Promise.resolve([])), [edicao])
  const [alcance, setAlcance] = useState<AlcanceDaImportacao>('EDICAO')
  const [cota, setCota] = useState('MASTER')
  const [parceriaId, setParceriaId] = useState('')
  const [colando, setColando] = useState(false)
  const [texto, setTexto] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const importacoes = useDados(() => (edicao ? listarImportacoes({ eventoId: Number(edicao), tamanho: 50 }) : Promise.resolve(null)), [edicao])

  const pronto = Boolean(edicao) && (alcance !== 'PARCERIA' || Boolean(parceriaId))

  async function enviar(arquivo?: File) {
    if (!pronto) return
    setEnviando(true)
    setFalha(null)
    try {
      const nova = await importar({
        arquivo,
        texto: arquivo ? undefined : texto.trim(),
        eventoId: Number(edicao),
        alcance,
        cota: alcance === 'COTA' ? cota : undefined,
        parceriaId: alcance === 'PARCERIA' ? Number(parceriaId) : undefined,
      })
      navegar(`/rbs/importar/${nova.id}`, { viewTransition: true })
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível enviar o arquivo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Importar"
        subtitulo="Arraste o arquivo de um veículo, da pesquisa ou de uma marca. O Atlas lê, e você confere antes de entrar."
        acoes={
          <Link to="/rbs/cargas" viewTransition className="text-[13px] font-semibold text-white underline-offset-4 hover:underline">
            Histórico de cargas (CSV padrão) →
          </Link>
        }
      />

      <Cartao titulo="Novo arquivo">
        <div className="grid gap-5 lg:grid-cols-[300px_1fr]">
          <div className="flex flex-col gap-3">
            <Seletor rotulo="Edição" value={edicao} onChange={(evento) => setEventoId(evento.target.value)}>
              {(eventos.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome} {item.edicao}
                </option>
              ))}
            </Seletor>
            <fieldset className="flex flex-col gap-2">
              <legend className="texto-sobretitulo mb-1 text-texto-2">De quem é</legend>
              {(
                [
                  ['EDICAO', 'De todas as marcas da edição'],
                  ['COTA', 'De uma cota'],
                  ['PARCERIA', 'De uma marca'],
                ] as const
              ).map(([valor, nome]) => (
                <label key={valor} className="flex items-center gap-2 text-sm font-medium">
                  <input type="radio" name="alcance" className="size-4 accent-[var(--secao-cor)]" checked={alcance === valor} onChange={() => setAlcance(valor)} />
                  {nome}
                </label>
              ))}
            </fieldset>
            {alcance === 'COTA' && (
              <Seletor rotulo="Cota" value={cota} onChange={(evento) => setCota(evento.target.value)}>
                {COTAS.map((item) => (
                  <option key={item.codigo} value={item.codigo}>
                    {item.nome}
                  </option>
                ))}
              </Seletor>
            )}
            {alcance === 'PARCERIA' && (
              <Seletor rotulo="Marca" value={parceriaId} onChange={(evento) => setParceriaId(evento.target.value)}>
                <option value="">Escolha a marca</option>
                {(parcerias.dado ?? []).map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.patrocinador.nome}
                  </option>
                ))}
              </Seletor>
            )}
            <p className="text-xs font-medium text-texto-2">Uma linha de uma marca fora disso é recusada na revisão, com o motivo.</p>
          </div>

          <div className="flex flex-col gap-3">
            {colando ? (
              <div className="flex flex-col gap-3">
                <AreaTexto rotulo="Conversa ou texto colado" rows={8} maxLength={100_000} value={texto} onChange={(evento) => setTexto(evento.target.value)} />
                <div className="flex flex-wrap gap-3">
                  <Botao onClick={() => enviar()} carregando={enviando} disabled={!pronto || !texto.trim()} icone="ri-sparkling-line">
                    Ler o texto
                  </Botao>
                  <Botao variante="secundario" onClick={() => setColando(false)}>
                    Arrastar um arquivo
                  </Botao>
                </div>
              </div>
            ) : (
              <>
                <AreaEnvio
                  titulo="Arraste o arquivo aqui"
                  texto="Planilha, Word, PowerPoint, PDF, print ou texto, até 20 MB. O Atlas diz de onde tirou cada número."
                  aceitar={ACEITOS}
                  desabilitado={!pronto || enviando}
                  aoEscolher={(arquivo) => enviar(arquivo)}
                />
                <button type="button" className="self-start text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => setColando(true)}>
                  Ou cole uma conversa (WhatsApp, e-mail)
                </button>
              </>
            )}
            {!pronto && <p className="text-xs font-medium text-texto-2">Escolha a marca para liberar o envio.</p>}
            {falha && <Aviso tipo="falha" titulo={falha} />}
          </div>
        </div>
      </Cartao>

      <Cartao titulo="Importações">
        <Carregando dados={importacoes} linhas={4}>
          {(pagina) =>
            !pagina || pagina.itens.length === 0 ? (
              <Aviso tipo="vazio" titulo="Nenhuma importação nesta edição ainda" />
            ) : (
              <Tabela<ResumoDaImportacao>
                rotulo="Importações"
                linhas={pagina.itens}
                chave={(item) => item.id}
                aoClicar={(item) => navegar(`/rbs/importar/${item.id}`, { viewTransition: true })}
                colunas={[
                  { titulo: 'Arquivo', celula: (item) => <span className="font-semibold">{item.arquivo.nome}</span> },
                  { titulo: 'Virou', celula: (item) => (item.destino ? NOME_DO_DESTINO[item.destino] : '—') },
                  { titulo: 'De quem', celula: (item) => deQuem(item) },
                  { titulo: 'Situação', celula: (item) => <SituacaoDaImportacao importacao={item} /> },
                  {
                    titulo: 'Linhas',
                    celula: (item) => (item.linhas.total ? `${item.linhas.aceitas} ${item.linhas.aceitas === 1 ? 'aceita' : 'aceitas'} de ${item.linhas.total}` : '—'),
                    classe: 'text-right',
                  },
                  { titulo: 'Enviada', celula: (item) => `${item.criadaPor.nome} · ${dataHora(item.criadaEm)}` },
                ]}
              />
            )
          }
        </Carregando>
      </Cartao>
    </div>
  )
}
