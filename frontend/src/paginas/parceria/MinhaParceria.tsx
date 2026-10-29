// Minha parceria (Figma P00): o que falta fazer, o que está com a RBS e o atalho para o relatório. [ITEM-7]
import { Link, useNavigate } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEntregas, type Entrega } from '../../api/materiais'
import { obterPesquisa, obterRelatorio } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { estadoDaAba } from '../../componentes/AbaFechada'
import { LinhaArquivo } from '../../componentes/Arquivo'
import { Aviso } from '../../componentes/Aviso'
import { LinkBotao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Grade2, Grade3, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { nomeCota } from '../../componentes/Menu'
import { SECOES } from '../../componentes/secoes'
import { Status, type StatusEntrega } from '../../componentes/Status'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora } from '../../util/formatar'
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

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={usuario?.perfil === 'ADMIN_RBS' ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={usuario?.perfil === 'ADMIN_RBS' ? parceria.patrocinador : 'Minha parceria'}
        subtitulo={`${nomeCota(parceria)} · ${parceria.evento.nome} ${parceria.evento.edicao} · próximas ações e entregas da empresa.`}
      />

      <Carregando dados={entregas}>
        {(lista) => {
          const pendentes = lista.filter((entrega) => A_FAZER.includes(entrega.status)).sort(porUrgencia)
          const comRbs = lista.filter((entrega) => COM_A_RBS.includes(entrega.status))
          const aprovadas = lista.filter((entrega) => entrega.status === 'APROVADO')
          const proxima = pendentes[0]
          // Ajuste pedido em entrega vem primeiro; depois, pergunta da pesquisa devolvida; depois, o prazo mais próximo.
          const pesquisaPrimeiro = ajustesPesquisa.length > 0 && proxima?.status !== 'AJUSTE_SOLICITADO'
          const aFazer = pendentes.length + ajustesPesquisa.length
          return (
            <>
              {fechadas.length > 0 && !rbs && (
                <Aviso tipo="restrito" titulo={`A RBS fechou por enquanto: ${fechadas.map((aba) => SECOES.find((secao) => secao.aba === aba.codigo)?.nome ?? aba.nome).join(', ')}`}>
                  As entregas dessas abas não entram nas contas abaixo até a aba reabrir. Dúvidas: fale com o time B2B da RBS.
                </Aviso>
              )}
              <Grade3>
                <Indicador rotulo="A fazer" valor={String(aFazer).padStart(2, '0')} legenda="Pendências da marca" />
                <Indicador rotulo="Com a RBS" valor={String(comRbs.length).padStart(2, '0')} legenda="Enviados, aguardando análise" />
                <Indicador
                  rotulo="Aprovados"
                  valor={String(aprovadas.length).padStart(2, '0')}
                  legenda={lista.length ? `De ${lista.length} ${lista.length === 1 ? 'entrega' : 'entregas'} da parceria` : 'Nenhuma entrega aberta ainda'}
                />
              </Grade3>

              <Cartao titulo="Sua próxima ação">
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
                        <span className="font-semibold">{proxima.titulo}</span> · {secaoDa(proxima).nome} · prazo {data(proxima.prazo)}
                      </p>
                      <div className="mt-1">
                        <Status codigo={proxima.status} />
                      </div>
                      {proxima.ultimaObservacao && <p className="mt-1 text-xs text-texto-2">RBS: {proxima.ultimaObservacao.texto}</p>}
                    </div>
                    <LinkBotao to={`${base}/${secaoDa(proxima).caminho}`}>Abrir {secaoDa(proxima).nome}</LinkBotao>
                  </div>
                ) : lista.length === 0 && !fechadas.length ? (
                  <p className="text-sm">A RBS ainda está preparando as entregas da sua parceria. Elas aparecem aqui assim que forem abertas.</p>
                ) : fechadas.length ? (
                  <p className="text-sm">Nada pendente nas abas abertas agora.</p>
                ) : (
                  <p className="text-sm">Tudo em dia: nenhuma entrega esperando a sua empresa.</p>
                )}
              </Cartao>

              {/* Tudo o que a marca tem a fazer, além da próxima ação: as outras entregas e a pesquisa devolvida. */}
              {aFazer > 1 && (
                <Cartao titulo="Pendências por módulo">
                  <Grade2>
                    {!pesquisaPrimeiro && ajustesPesquisa.length > 0 && (
                      <div data-secao="pesquisa">
                        <LinhaArquivo
                          titulo={`Pesquisa · ${ajustesPesquisa.length === 1 ? `pergunta ${ajustesPesquisa[0].ordem}` : `${ajustesPesquisa.length} perguntas`}`}
                          icone={SECOES.find((secao) => secao.aba === 'PESQUISA')?.icone ?? 'ri-survey-line'}
                          meta={`RBS: ${ajustesPesquisa[0].observacaoRbs ?? 'pediu ajuste'}`}
                          rodape={<span className="text-xs font-bold text-[#c8203f]">Ajuste pedido pela RBS</span>}
                          onClick={() => navegar(`${base}/pesquisa`)}
                        />
                      </div>
                    )}
                    {pendentes.slice(pesquisaPrimeiro ? 0 : 1, 7).map((entrega) => (
                      <div key={entrega.id} data-secao={secaoDa(entrega).chave}>
                        <LinhaArquivo
                          titulo={`${secaoDa(entrega).nome} · ${entrega.titulo}`}
                          icone={secaoDa(entrega).icone}
                          meta={`Responsável: sua empresa · prazo ${data(entrega.prazo)}`}
                          rodape={<Status codigo={entrega.status} />}
                          onClick={() => navegar(`${base}/${secaoDa(entrega).caminho}`)}
                        />
                      </div>
                    ))}
                  </Grade2>
                </Cartao>
              )}
            </>
          )
        }}
      </Carregando>

      <Carregando dados={relatorio}>
        {(dado) => (
          <Aviso tipo={dado ? 'info' : 'ausente'} titulo={dado ? `Relatório pós-evento disponível · versão ${dado.versao}` : 'Relatório pós-evento ainda não disponível'}>
            {dado
              ? `Disponibilizado pela RBS em ${dataHora(dado.disponibilizadoEm)}.`
              : 'A RBS gera o relatório a partir dos dados do portal e o libera depois de validar os números.'}
          </Aviso>
        )}
      </Carregando>

      <div className="flex flex-wrap gap-4">
        <LinkBotao to={`${base}/relatorio`} icone="ri-file-chart-line">
          Ver relatório
        </LinkBotao>
        <LinkBotao to={`${base}/historico`} variante="secundario" icone="ri-history-line">
          Ver histórico
        </LinkBotao>
        <Link to={`${base}/contrato`} className="self-center text-[13px] font-semibold text-secao-texto hover:underline">
          Consultar contrato →
        </Link>
      </div>
    </div>
  )
}
