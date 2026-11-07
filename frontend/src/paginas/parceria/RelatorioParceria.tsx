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
import { PosVendaDaMarca, PosVendaDaRbs } from '../../componentes/PosVenda'

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

/** Percentual de verdade (entrega, comprovação, destinação): ganha barra. Resultado de pesquisa em nota não. */
function ehPercentual(indicador: Indicador) {
  return indicador.disponivel && indicador.valor !== null && (indicador.unidade ?? '').trim().startsWith('%') && !/\(nota\)/.test(indicador.baseCalculo ?? '')
}

type Grupo = { codigo: string; nome: string; baseCalculo: string | null; itens: Indicador[] }

/** Os indicadores da seção agrupados pelo código, na ordem da API: "Entrega por veículo" vira um bloco com um veículo por linha. */
function agruparIndicadores(indicadores: Indicador[]): Grupo[] {
  const grupos: Grupo[] = []
  for (const indicador of indicadores) {
    const grupo = grupos.find((item) => item.codigo === indicador.codigo)
    if (grupo) grupo.itens.push(indicador)
    else grupos.push({ codigo: indicador.codigo, nome: indicador.nome, baseCalculo: indicador.baseCalculo, itens: [indicador] })
  }
  return grupos
}

/** "Como foi calculado" num "i" na tela; no papel, o texto sai inteiro embaixo. */
function ComoFoiCalculado({ texto }: { texto: string | null }) {
  if (!texto) return null
  return (
    <>
      <details className="relative shrink-0 print:hidden">
        <summary className="grid size-6 cursor-pointer list-none place-items-center rounded-full text-texto-2 hover:bg-cinza-100 hover:text-tinta [&::-webkit-details-marker]:hidden">
          <i className="ri-information-line text-base" aria-hidden="true" />
          <span className="sr-only">Como foi calculado</span>
        </summary>
        <p className="absolute right-0 z-10 mt-1 w-64 animate-surgir rounded-xl bg-tinta px-3 py-2 text-xs font-medium text-white shadow-modal">{texto}</p>
      </details>
      <span className="hidden text-[10px] text-texto-2 print:block">{texto}</span>
    </>
  )
}

function BarraFina({ valor }: { valor: number }) {
  return (
    <span className="mt-3 block h-2 overflow-hidden rounded-full bg-cinza-100" aria-hidden="true">
      <span className="block h-full rounded-full bg-secao" style={{ width: `${Math.max(0, Math.min(100, valor))}%` }} />
    </span>
  )
}

function BlocoDoIndicador({ grupo, indice }: { grupo: Grupo; indice: number }) {
  const [unico] = grupo.itens
  const estilo = { '--ordem': indice } as React.CSSProperties
  // Um valor só: o número grande. Vários recortes (veículo, setor, pergunta): uma lista, com barra no que é percentual.
  if (grupo.itens.length === 1) {
    return (
      <li className="entrar-em-cascata flex flex-col rounded-2xl border border-borda p-4 break-inside-avoid" style={estilo}>
        <p className="flex items-start justify-between gap-2 text-sm font-semibold text-texto-2">
          {grupo.nome}
          {unico.disponivel && <ComoFoiCalculado texto={grupo.baseCalculo} />}
        </p>
        <p className={`mt-2 leading-none ${unico.disponivel && unico.valor !== null ? 'font-display text-[40px]' : 'text-sm'}`}>{valorDo(unico)}</p>
        {/* Indisponível sempre com o motivo à vista (decisão do relatório), sem precisar abrir o "i". */}
        {!unico.disponivel && unico.baseCalculo && <p className="mt-1 text-xs font-medium text-texto-2">{unico.baseCalculo}</p>}
        {unico.recorte && <p className="mt-1.5 text-xs font-medium text-texto-2">{recorteDo(unico)}</p>}
        {ehPercentual(unico) && <BarraFina valor={unico.valor ?? 0} />}
      </li>
    )
  }
  return (
    <li className="entrar-em-cascata rounded-2xl border border-borda p-4 break-inside-avoid sm:col-span-2" style={estilo}>
      <p className="flex items-start justify-between gap-2 text-sm font-semibold text-texto-2">
        {grupo.nome}
        <ComoFoiCalculado texto={grupo.baseCalculo} />
      </p>
      <ul className="mt-3 flex flex-col gap-2.5">
        {grupo.itens.map((indicador) => (
          <li key={indicador.recorte} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 text-sm sm:grid-cols-[minmax(140px,260px)_1fr_auto]">
            <span className="font-medium">{recorteDo(indicador)}</span>
            {ehPercentual(indicador) ? (
              <span className="col-span-2 row-start-2 h-2 overflow-hidden rounded-full bg-cinza-100 sm:col-span-1 sm:row-start-auto" aria-hidden="true">
                <span className="block h-full rounded-full bg-secao" style={{ width: `${Math.max(0, Math.min(100, indicador.valor ?? 0))}%` }} />
              </span>
            ) : (
              <span className="hidden sm:block" aria-hidden="true" />
            )}
            <span className="text-right font-semibold whitespace-nowrap">{valorDo(indicador)}</span>
          </li>
        ))}
      </ul>
    </li>
  )
}

function Documento({ relatorio }: { relatorio: Relatorio }) {
  const parceria = useParceria()
  const datas = [
    ['gerado', relatorio.geradoEm],
    ['validado', relatorio.validadoEm],
    ['disponibilizado', relatorio.disponibilizadoEm],
  ].filter(([, quando]) => quando)
  return (
    <article className="rounded-[20px] border border-borda bg-white p-5 sm:p-10 print:border-0 print:p-0">
      <header className="mb-8 flex flex-wrap items-start justify-between gap-4 border-b border-borda pb-6">
        <div>
          <p className="texto-sobretitulo text-texto-2">Planeta 360 · relatório pós-evento</p>
          <h2 className="mt-1 font-display text-[44px] leading-none uppercase">{parceria.patrocinador}</h2>
          <p className="mt-2 text-sm font-semibold">
            Cota {nomeCota(parceria)} · {parceria.evento.nome} {parceria.evento.edicao}
          </p>
          <p className="mt-1 text-xs font-medium text-texto-2">
            {datas.map(([nome, quando]) => `${nome} em ${dataHora(quando)}`).join(' · ')}
            {relatorio.geradoPor && ` · por ${relatorio.geradoPor.nome}`}
          </p>
        </div>
        <Status codigo={relatorio.status} complemento={`versão ${relatorio.versao}`} />
      </header>

      {relatorio.secoes.map((secao, indice) => (
        <section key={secao.secao} className="mb-8 break-inside-avoid" aria-label={secao.secao}>
          <h3 className="mb-3 flex items-baseline gap-2 text-base font-bold">
            <span className="font-display text-2xl leading-none text-secao-texto">{indice + 1}</span>
            {secao.secao}
          </h3>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {agruparIndicadores(secao.indicadores).map((grupo, posicao) => (
              <BlocoDoIndicador key={grupo.codigo} grupo={grupo} indice={posicao} />
            ))}
          </ul>
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
              <div className="flex flex-wrap items-center gap-3 print:hidden">
                {/* Uma linha de estado no lugar dos dois avisos (F9). */}
                {dado.status !== 'DISPONIBILIZADO' && (
                  <p className="mr-auto text-sm font-semibold text-texto-2">
                    {dado.status === 'RASCUNHO' ? 'Rascunho: a marca ainda não vê esta versão.' : 'Validado: falta disponibilizar para a marca.'}
                    <span className="font-medium"> Enquanto isso, ela vê a última versão disponibilizada, se houver.</span>
                  </p>
                )}
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
              {/* R8.1: o anexo de pós-venda vem depois das 6 seções, que não mudam. */}
              {!rbs && <PosVendaDaMarca parceriaId={parceria.id} />}
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

      {rbs && <PosVendaDaRbs parceriaId={parceria.id} />}
      {/* U10 (plano da base da RBS): os relatórios montados pelo Atlas saíram daqui, para a página do relatório oficial ser só
          dele. O código fica em RelatorioAtlas.tsx, e cada relatório do Atlas continua abrindo pela própria rota. */}
    </div>
  )
}
