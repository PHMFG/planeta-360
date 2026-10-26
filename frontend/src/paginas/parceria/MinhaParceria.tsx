// Minha parceria (Figma P00): o que falta fazer, o que está com a RBS e o atalho para o relatório. [ITEM-7]
import { Link, useNavigate } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEntregas, type Entrega } from '../../api/materiais'
import { obterRelatorio } from '../../api/resultados'
import { useDados } from '../../api/useDados'
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
  const navegar = useNavigate()
  const base = `/parceria/${parceria.id}`
  const entregas = useDados(() => listarEntregas(parceria.id), [parceria.id])
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
          return (
            <>
              <Grade3>
                <Indicador rotulo="A fazer" valor={String(pendentes.length).padStart(2, '0')} legenda="Pendências da marca" />
                <Indicador rotulo="Com a RBS" valor={String(comRbs.length).padStart(2, '0')} legenda="Enviados, aguardando análise" />
                <Indicador rotulo="Aprovados" valor={String(aprovadas.length).padStart(2, '0')} legenda={`De ${lista.length} entregas da parceria`} />
              </Grade3>

              <Cartao titulo="Sua próxima ação">
                {proxima ? (
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
                ) : (
                  <p className="text-sm">Tudo em dia: nenhuma entrega esperando a sua empresa.</p>
                )}
              </Cartao>

              {pendentes.length > 1 && (
                <Cartao titulo="Pendências por módulo">
                  <Grade2>
                    {pendentes.slice(1, 7).map((entrega) => (
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
