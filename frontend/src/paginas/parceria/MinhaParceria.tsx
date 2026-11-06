// Minha parceria (Figma P00): o andamento das entregas com a próxima ação, o acompanhamento dos números e o relatório final.
// [ITEM-7] Plano da base da RBS (U10, U12, U13): sem o cartão do Atlas no topo; os três contadores, a próxima ação e as
// pendências viram um bloco só; o relatório final mostra o estado real da versão para a RBS e o disponibilizado para a marca.
import { Link, useNavigate } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEntregas, type Entrega } from '../../api/materiais'
import { obterConsolidacao, obterPesquisa, obterRelatorio, type Relatorio } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { estadoDaAba } from '../../componentes/AbaFechada'
import { LinhaArquivo } from '../../componentes/Arquivo'
import { Aviso } from '../../componentes/Aviso'
import { Barra } from '../../componentes/Barra'
import { LinkBotao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { nomeCota } from '../../componentes/Menu'
import { SECOES } from '../../componentes/secoes'
import { Status, type StatusEntrega } from '../../componentes/Status'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora, porcentagem } from '../../util/formatar'
import { useParceria } from './ContextoParceria'

const A_FAZER: StatusEntrega[] = ['NAO_INICIADO', 'AGUARDANDO_ENVIO', 'AJUSTE_SOLICITADO']
const COM_A_RBS: StatusEntrega[] = ['ENVIADO', 'EM_ANALISE']

function secaoDa(entrega: Entrega) {
  return SECOES.find((secao) => secao.aba === entrega.abaCodigo) ?? SECOES[0]
}

/** Mais urgente primeiro: ajuste pedido, depois o prazo mais próximo; sem prazo vai para o fim. */
function porUrgencia(a: Entrega, b: Entrega) {
  if ((a.status === 'AJUSTE_SOLICITADO') !== (b.status === 'AJUSTE_SOLICITADO')) return a.status === 'AJUSTE_SOLICITADO' ? -1 : 1
  return (a.prazo ?? '9999').localeCompare(b.prazo ?? '9999')
}

export function MinhaParceria() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const navegar = useNavigate()
  const base = `/parceria/${parceria.id}`
  const entregas = useDados(() => listarEntregas(parceria.id), [parceria.id])
  // Pergunta devolvida pela RBS também é ação da marca, como uma entrega com ajuste pedido.
  const pesquisaLiberada = estadoDaAba(parceria.abas.find((aba) => aba.codigo === 'PESQUISA')) === 'LIBERADA'
  const pesquisa = useDados(
    () => (pesquisaLiberada && !rbs ? obterPesquisa(parceria.id) : Promise.resolve(null)),
    [parceria.id, pesquisaLiberada, rbs],
  )
  const ajustesPesquisa = (pesquisa.dado?.perguntas ?? []).filter((pergunta) => pergunta.status === 'RASCUNHO' && pergunta.observacaoRbs)
  // Abas que a RBS fechou por enquanto: as entregas delas somem da lista até a aba reabrir, e a tela precisa dizer isso.
  const fechadas = parceria.abas.filter((aba) => estadoDaAba(aba) === 'FECHADA')
  // O patrocinador recebe 404 enquanto a RBS não disponibiliza o relatório: aqui isso é "ainda não", não erro.
  const relatorio = useDados(
    () => obterRelatorio(parceria.id).catch((erro: unknown) => (erro instanceof ErroApi && erro.status === 404 ? null : Promise.reject(erro))),
    [parceria.id],
  )
  const midiaLiberada = estadoDaAba(parceria.abas.find((aba) => aba.codigo === 'MIDIA')) === 'LIBERADA'
  const consolidacao = useDados(
    () => (rbs || midiaLiberada ? obterConsolidacao(parceria.id) : Promise.resolve(null)),
    [parceria.id, rbs, midiaLiberada],
  )

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={rbs ? parceria.patrocinador : 'Minha parceria'}
        subtitulo={`${nomeCota(parceria)} · ${parceria.evento.nome} ${parceria.evento.edicao}`}
      />

      {fechadas.length > 0 && !rbs && (
        <Aviso tipo="restrito" titulo={`A RBS fechou por enquanto: ${fechadas.map((aba) => SECOES.find((secao) => secao.aba === aba.codigo)?.nome ?? aba.nome).join(', ')}`}>
          As entregas dessas abas ficam fora das contas abaixo até a aba reabrir.
        </Aviso>
      )}

      <Carregando dados={entregas} linhas={4}>
        {(lista) => {
          const pendentes = lista.filter((entrega) => A_FAZER.includes(entrega.status)).sort(porUrgencia)
          const comRbs = lista.filter((entrega) => COM_A_RBS.includes(entrega.status)).length
          const aprovadas = lista.filter((entrega) => entrega.status === 'APROVADO').length
          const proxima = pendentes[0]
          // Ajuste pedido em entrega vem primeiro; depois, pergunta da pesquisa devolvida; depois, o prazo mais próximo.
          const pesquisaPrimeiro = ajustesPesquisa.length > 0 && proxima?.status !== 'AJUSTE_SOLICITADO'
          const outras = pendentes.slice(pesquisaPrimeiro ? 0 : 1, 6)
          return (
            <Cartao titulo={rbs ? 'Próxima ação da marca' : 'Sua próxima ação'}>
              {lista.length > 0 && (
                <div className="mb-5 flex flex-wrap items-center gap-x-6 gap-y-2">
                  <div className="min-w-[220px] flex-1">
                    <Barra valor={(aprovadas / lista.length) * 100} rotulo="Entregas aprovadas" />
                  </div>
                  <p className="text-sm font-medium text-texto-2">
                    <span className="font-semibold text-tinta">
                      {aprovadas} de {lista.length}
                    </span>{' '}
                    aprovadas · {comRbs} com a RBS · {pendentes.length + ajustesPesquisa.length} a fazer
                  </p>
                </div>
              )}

              {pesquisaPrimeiro ? (
                <div className="flex flex-wrap items-center justify-between gap-4" data-secao="pesquisa">
                  <div>
                    <p className="text-sm">
                      <span className="font-semibold">
                        {ajustesPesquisa.length === 1 ? `Pergunta ${ajustesPesquisa[0].ordem}` : `${ajustesPesquisa.length} perguntas`} da pesquisa
                      </span>{' '}
                      · a RBS pediu ajuste
                    </p>
                    {ajustesPesquisa[0].observacaoRbs && <p className="mt-1 text-xs text-texto-2">RBS: {ajustesPesquisa[0].observacaoRbs}</p>}
                  </div>
                  <LinkBotao to={`${base}/pesquisa`}>Abrir Pesquisa</LinkBotao>
                </div>
              ) : proxima ? (
                <div className="flex flex-wrap items-center justify-between gap-4">
                  <div>
                    <p className="text-sm">
                      <span className="font-semibold">{proxima.titulo}</span> · {secaoDa(proxima).nome} · {proxima.prazo ? `prazo ${data(proxima.prazo)}` : 'sem prazo definido'}
                    </p>
                    <div className="mt-1">
                      <Status codigo={proxima.status} />
                    </div>
                    {proxima.ultimaObservacao && <p className="mt-1 text-xs text-texto-2">RBS: {proxima.ultimaObservacao.texto}</p>}
                  </div>
                  <LinkBotao to={`${base}/${secaoDa(proxima).caminho}`}>Abrir {secaoDa(proxima).nome}</LinkBotao>
                </div>
              ) : lista.length === 0 && !fechadas.length ? (
                <p className="text-sm">A RBS ainda está preparando as entregas da parceria. Elas aparecem aqui assim que forem abertas.</p>
              ) : (
                <p className="text-sm font-medium">
                  <i className="ri-checkbox-circle-fill mr-1.5 align-[-2px] text-aplicacoes" aria-hidden="true" />
                  Tudo em dia: nenhuma entrega esperando {rbs ? 'a marca' : 'a sua empresa'}.
                </p>
              )}

              {/* O que mais falta, além da próxima ação: uma linha por entrega, que abre a aba dela. */}
              {(outras.length > 0 || (!pesquisaPrimeiro && ajustesPesquisa.length > 0)) && (
                <ul className="mt-5 flex flex-col gap-2 border-t border-borda pt-4" aria-label="Também falta">
                  {!pesquisaPrimeiro && ajustesPesquisa.length > 0 && (
                    <li data-secao="pesquisa" className="entrar-em-cascata" style={{ '--ordem': 0 } as React.CSSProperties}>
                      <LinhaArquivo
                        titulo={`Pesquisa · ${ajustesPesquisa.length === 1 ? `pergunta ${ajustesPesquisa[0].ordem}` : `${ajustesPesquisa.length} perguntas`}`}
                        icone={SECOES.find((secao) => secao.aba === 'PESQUISA')?.icone ?? 'ri-survey-line'}
                        meta={`RBS: ${ajustesPesquisa[0].observacaoRbs ?? 'pediu ajuste'}`}
                        onClick={() => navegar(`${base}/pesquisa`)}
                      />
                    </li>
                  )}
                  {outras.map((entrega, indice) => (
                    <li key={entrega.id} data-secao={secaoDa(entrega).chave} className="entrar-em-cascata" style={{ '--ordem': indice + 1 } as React.CSSProperties}>
                      <LinhaArquivo
                        titulo={`${secaoDa(entrega).nome} · ${entrega.titulo}`}
                        icone={secaoDa(entrega).icone}
                        meta={entrega.prazo ? `prazo ${data(entrega.prazo)}` : 'sem prazo definido'}
                        rodape={<Status codigo={entrega.status} />}
                        onClick={() => navegar(`${base}/${secaoDa(entrega).caminho}`)}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </Cartao>
          )
        }}
      </Carregando>

      {/* Acompanhamento (U12): os números de agora, que levam às abas onde se filtra e exporta. */}
      {consolidacao.dado && (
        <Cartao titulo="Acompanhamento">
          <div className="grid gap-4 md:grid-cols-2">
            {consolidacao.dado.midia.porUnidade.length > 0 && (
              <Link to={`${base}/midia`} viewTransition data-secao="midia" className="group flex flex-col gap-2 rounded-2xl border border-borda p-5 transition-shadow hover:shadow-menu">
                <span className="texto-sobretitulo text-texto-2">Mídia entregue</span>
                {consolidacao.dado.midia.porUnidade.map((linha) => (
                  <div key={linha.unidade} className="flex flex-col gap-1">
                    <Barra valor={linha.percentual} rotulo={`Mídia entregue em ${linha.unidade}`} />
                  </div>
                ))}
                <span className="text-[13px] font-semibold text-secao-texto group-hover:underline">Ver por veículo e formato →</span>
              </Link>
            )}
            {consolidacao.dado.aplicacoes.previstas ? (
              <Link to={`${base}/aplicacoes`} viewTransition data-secao="aplicacoes" className="group flex flex-col gap-2 rounded-2xl border border-borda p-5 transition-shadow hover:shadow-menu">
                <span className="texto-sobretitulo text-texto-2">Aplicações comprovadas</span>
                <Barra valor={consolidacao.dado.aplicacoes.percentual} rotulo="Aplicações comprovadas" />
                <span className="text-xs font-medium text-texto-2">
                  {consolidacao.dado.aplicacoes.comprovadas ?? 0} de {consolidacao.dado.aplicacoes.previstas} · {porcentagem(consolidacao.dado.aplicacoes.percentual)}
                </span>
                <span className="text-[13px] font-semibold text-secao-texto group-hover:underline">Ver as aplicações →</span>
              </Link>
            ) : null}
          </div>
        </Cartao>
      )}

      <Carregando dados={relatorio} linhas={1}>
        {(dado) => <RelatorioFinal relatorio={dado} rbs={rbs} base={base} />}
      </Carregando>
    </div>
  )
}

/** O relatório final numa linha: para a marca, o disponibilizado; para a RBS, o estado real da versão mais nova (U13). */
function RelatorioFinal({ relatorio, rbs, base }: { relatorio: Relatorio | null; rbs: boolean; base: string }) {
  const texto = !relatorio
    ? rbs
      ? 'Nenhuma versão gerada ainda.'
      : 'Ainda não disponível. A RBS libera depois de validar os números.'
    : relatorio.status === 'DISPONIBILIZADO'
      ? `Versão ${relatorio.versao}, disponibilizada pela RBS em ${dataHora(relatorio.disponibilizadoEm)}.`
      : relatorio.status === 'VALIDADO'
        ? `Versão ${relatorio.versao} validada; a marca ainda não vê.`
        : `Versão ${relatorio.versao} em rascunho; a marca ainda não vê.`
  return (
    <section className="flex flex-wrap items-center justify-between gap-4 rounded-[20px] border border-borda bg-white px-6 py-4">
      <div className="flex items-center gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-secao-tinta text-secao-texto">
          <i className="ri-file-chart-line text-xl" aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-sm font-bold">Relatório final</h2>
          <p className="text-xs font-medium text-texto-2">{texto}</p>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <Link to={`${base}/historico`} viewTransition className="text-[13px] font-semibold text-texto-2 hover:underline">
          Histórico
        </Link>
        <LinkBotao to={`${base}/relatorio`} icone="ri-file-chart-line" variante={relatorio?.status === 'DISPONIBILIZADO' ? 'primario' : 'secundario'}>
          Ver relatório
        </LinkBotao>
      </div>
    </section>
  )
}
