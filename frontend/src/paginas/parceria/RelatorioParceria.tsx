// Relatório da parceria: documento gerado da base, com as seções do Modelo do relatório. [ITEM-6] [RELATORIO]
// O patrocinador só vê a versão disponibilizada; a RBS gera, valida e disponibiliza. Sem dado, o número é "indisponível".
import { useState } from 'react'
import { ErroApi } from '../../api/cliente'
import { enderecoRelatorioCsv, gerarRelatorio, mudarStatusRelatorio, obterRelatorio, type Indicador, type Relatorio } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Carregando } from '../../componentes/Carregando'
import { nomeCota } from '../../componentes/Menu'
import { Status } from '../../componentes/Status'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, numero } from '../../util/formatar'
import { useParceria } from './ContextoParceria'

// Partes do recorte que chegam como código do banco e o nome que a marca lê.
const CODIGOS: Record<string, string> = {
  INSERCAO: 'Inserções',
  CPM: 'CPM',
  APLICACAO: 'Aplicações de marca',
  ARENA: 'Arena',
  CAMAROTE: 'Camarote',
  PREMIUM: 'Premium',
  LOUNGE: 'Lounge',
  MARCA: 'Marca',
  AGENCIA: 'Agência',
  SALA_CONTEUDO: 'Sala de conteúdo',
  SALA_IMPRENSA: 'Sala de imprensa',
  EQUIPE_CONTEUDO: 'Equipe de conteúdo',
}

// Unidades do catálogo no plural; com valor 1 a frase fica no singular.
const SINGULAR: Record<string, string> = {
  entregas: 'entrega',
  arquivos: 'arquivo',
  ingressos: 'ingresso',
  credenciais: 'credencial',
  aplicações: 'aplicação',
  respondentes: 'respondente',
}

function recorteDo(indicador: Indicador) {
  if (!indicador.recorte) return '—'
  return indicador.recorte
    .split(' · ')
    .map((parte) => CODIGOS[parte] ?? parte)
    .join(' · ')
}

function valorDo(indicador: Indicador) {
  if (!indicador.disponivel || indicador.valor === null) return <span className="font-medium text-texto-2 italic">Indisponível</span>
  const unidade = (indicador.unidade ?? '').trim()
  const texto = numero(indicador.valor)
  // Resultado de pesquisa usa "% ou nota": a base de cálculo diz qual dos dois é.
  if (unidade.startsWith('%')) return /\(nota\)/.test(indicador.baseCalculo ?? '') ? `nota ${texto}` : `${texto}%`
  if (!unidade || unidade === 'por unidade') return texto
  return `${texto} ${indicador.valor === 1 ? (SINGULAR[unidade] ?? unidade) : unidade}`
}

function Documento({ relatorio }: { relatorio: Relatorio }) {
  const parceria = useParceria()
  return (
    <article className="rounded-[20px] border border-borda bg-white p-10 print:border-0 print:p-0">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-borda pb-6">
        <div>
          <p className="texto-sobretitulo text-texto-2">Planeta 360 · relatório pós-evento</p>
          <h2 className="mt-1 font-display text-[44px] leading-none uppercase">Relatório da parceria</h2>
        </div>
        <Status codigo={relatorio.status} complemento={`versão ${relatorio.versao}`} />
      </div>
      <dl className="mb-8 grid gap-x-8 gap-y-3 text-sm sm:grid-cols-2">
        {[
          ['Empresa', `${parceria.patrocinador} · cota ${nomeCota(parceria)}`],
          ['Edição', `${parceria.evento.nome} ${parceria.evento.edicao}`],
          ['Gerado em', dataHora(relatorio.geradoEm)],
          ['Validado pela RBS em', dataHora(relatorio.validadoEm)],
          ['Disponibilizado em', dataHora(relatorio.disponibilizadoEm)],
          ...(relatorio.geradoPor ? [['Gerado por', relatorio.geradoPor.nome]] : []),
        ].map(([rotulo, texto]) => (
          <div key={rotulo}>
            <dt className="texto-sobretitulo text-texto-2">{rotulo}</dt>
            <dd className="font-semibold">{texto}</dd>
          </div>
        ))}
      </dl>

      {relatorio.secoes.map((secao, indice) => (
        <section key={secao.secao} className="mb-8 break-inside-avoid">
          <h3 className="mb-3 text-base font-bold">
            {indice + 1}. {secao.secao}
          </h3>
          <table className="w-full border-separate border-spacing-0 text-sm">
            <thead>
              <tr>
                {['Indicador', 'Recorte', 'Valor', 'Como foi calculado'].map((titulo, posicao) => (
                  <th key={titulo} className={`bg-cinza-100 px-3 py-2 text-left font-semibold ${posicao === 0 ? 'rounded-l-[10px]' : ''} ${posicao === 3 ? 'rounded-r-[10px]' : ''}`}>
                    {titulo}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {secao.indicadores.map((indicador) => (
                <tr key={`${indicador.codigo}-${indicador.recorte}`}>
                  <td className="border-b border-borda px-3 py-2.5 font-semibold">{indicador.nome}</td>
                  <td className="border-b border-borda px-3 py-2.5">{recorteDo(indicador)}</td>
                  <td className="border-b border-borda px-3 py-2.5 whitespace-nowrap">{valorDo(indicador)}</td>
                  <td className="border-b border-borda px-3 py-2.5 text-xs text-texto-2">{indicador.baseCalculo ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
      <p className="text-xs text-texto-2">
        Os números vêm dos registros do portal. Quando não há dado na base, o indicador aparece como indisponível: nenhum valor é estimado.
      </p>
    </article>
  )
}

export function RelatorioParceria() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const [versao, setVersao] = useState<number | undefined>()
  const dados = useDados(
    () => obterRelatorio(parceria.id, versao).catch((erro: unknown) => (erro instanceof ErroApi && erro.status === 404 ? null : Promise.reject(erro))),
    [parceria.id, versao],
  )
  const [falha, setFalha] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  async function acao(fazer: () => Promise<Relatorio>) {
    setFalha(null)
    setOcupado(true)
    try {
      const novo = await fazer()
      setVersao(novo.versao)
      dados.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível concluir.')
    } finally {
      setOcupado(false)
    }
  }

  const relatorio = dados.dado
  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <div className="print:hidden">
        <CabecalhoSecao
          trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
          titulo="Relatório"
          subtitulo="Resultado da parceria gerado a partir dos dados do portal, depois da validação da RBS."
        />
      </div>

      {rbs && (
        <div className="flex flex-wrap items-center gap-3 print:hidden">
          <Botao icone="ri-refresh-line" carregando={ocupado} onClick={() => acao(() => gerarRelatorio(parceria.id))}>
            Gerar nova versão
          </Botao>
          {relatorio?.status === 'RASCUNHO' && (
            <Botao variante="secundario" disabled={ocupado} onClick={() => acao(() => mudarStatusRelatorio(relatorio.id, 'VALIDADO'))}>
              Validar números
            </Botao>
          )}
          {relatorio?.status === 'VALIDADO' && (
            <Botao variante="secundario" disabled={ocupado} onClick={() => acao(() => mudarStatusRelatorio(relatorio.id, 'DISPONIBILIZADO'))}>
              Disponibilizar para a marca
            </Botao>
          )}
          {relatorio && relatorio.versao > 1 && (
            <label className="ml-auto flex items-center gap-2 text-xs font-semibold">
              Versão
              <select className="rounded-lg border border-borda bg-white px-2 py-1" value={relatorio.versao} onChange={(evento) => setVersao(Number(evento.target.value))}>
                {Array.from({ length: Math.max(relatorio.versao, versao ?? 0) }, (_, indice) => indice + 1)
                  .reverse()
                  .map((numeroVersao) => (
                    <option key={numeroVersao} value={numeroVersao}>
                      v{numeroVersao}
                    </option>
                  ))}
              </select>
            </label>
          )}
        </div>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}

      <Carregando dados={dados}>
        {(dado) =>
          dado ? (
            <>
              {dado.status !== 'DISPONIBILIZADO' && (
                <Aviso tipo="ausente" titulo={dado.status === 'RASCUNHO' ? 'Rascunho: a marca ainda não vê este relatório' : 'Validado: falta disponibilizar para a marca'} />
              )}
              <div className="flex flex-wrap gap-3 print:hidden">
                <Botao variante="secundario" icone="ri-printer-line" onClick={() => window.print()}>
                  Imprimir ou salvar PDF
                </Botao>
                <a
                  href={enderecoRelatorioCsv(dado.id)}
                  className="texto-botao inline-flex h-12 items-center gap-2 rounded-[10px] border-[1.5px] border-tinta bg-white px-6 hover:bg-cinza-100"
                >
                  <i className="ri-download-line text-lg" aria-hidden="true" /> Exportar CSV
                </a>
              </div>
              <Documento relatorio={dado} />
            </>
          ) : (
            <Aviso tipo="ausente" titulo={rbs ? 'Esta parceria ainda não tem relatório' : 'O relatório desta parceria ainda não está disponível'}>
              {rbs
                ? 'Use “Gerar nova versão”: o relatório nasce em rascunho, a partir dos dados do portal.'
                : 'A RBS gera o relatório a partir dos dados do portal e o libera depois de validar os números.'}
            </Aviso>
          )
        }
      </Carregando>
    </div>
  )
}
