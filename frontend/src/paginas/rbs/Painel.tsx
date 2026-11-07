// Painel da RBS (Figma R00): parcerias da edição, materiais esperando análise, relatórios e pendências. [ITEM-7]
import { useState } from 'react'
import { Link } from 'react-router'
import { listarEventos } from '../../api/parcerias'
import { obterPainel, type Painel as DadosDoPainel } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { LinkBotao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Grade2, Grade3, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { STATUS_ENTREGA, Status } from '../../componentes/Status'
import { dataHora, numero } from '../../util/formatar'

const TIPOS_PENDENCIA: Record<string, string> = {
  MIDIA_SEM_EXIBICAO: 'Mídia sem exibição',
  MIDIA_ABAIXO_DO_PREVISTO: 'Mídia abaixo do previsto',
  APLICACAO_SEM_COMPROVACAO: 'Aplicação sem comprovação',
  PESQUISA_SEM_PERGUNTAS: 'Pesquisa sem perguntas',
  PERGUNTA_SEM_RESULTADO: 'Pergunta sem resultado',
  ENTREGA_PENDENTE: 'Entrega pendente',
  PARCERIA_SEM_USUARIO: 'Parceria sem usuário',
  FESTIVAL_SEM_NUMEROS: 'Festival sem números',
}

export function nomePendencia(tipo: string) {
  return TIPOS_PENDENCIA[tipo] ?? tipo
}

/** Para onde cada status do Painel leva em Materiais (U8). */
const VISAO_DO_STATUS: Record<string, string> = {
  NAO_INICIADO: 'falta-aba',
  AGUARDANDO_ENVIO: 'falta-aba',
  AJUSTE_SOLICITADO: 'ajuste',
  ENVIADO: 'analise',
  EM_ANALISE: 'analise',
  APROVADO: 'aprovadas',
  NAO_APLICAVEL: 'todas',
}

export function Painel() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const painel = useDados(() => obterPainel(eventoId ? Number(eventoId) : undefined), [eventoId])

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Painel"
        subtitulo="Resumo das parcerias, materiais na fila e últimas atualizações."
        acoes={
          <LinkBotao to="/rbs/fila" viewTransition className="bg-white! text-tinta!" icone="ri-inbox-archive-line">
            Abrir materiais
          </LinkBotao>
        }
      />

      <div className="max-w-sm">
        <Seletor rotulo="Edição" value={eventoId} onChange={(evento) => setEventoId(evento.target.value)}>
          <option value="">Edição mais nova</option>
          {(eventos.dado ?? []).map((item) => (
            <option key={item.id} value={item.id}>
              {item.nome} {item.edicao}
            </option>
          ))}
        </Seletor>
      </div>

      <Carregando dados={painel}>
        {(dado) => (
          <>
            <Grade3>
              <Indicador
                rotulo="Parcerias ativas"
                valor={dado.parcerias.ativas}
                legenda={`${dado.parcerias.total} cadastradas na edição ${dado.evento.edicao}${dado.parcerias.semUsuario ? ` · ${dado.parcerias.semUsuario} sem usuário` : ''}`}
              />
              {/* U8: o número abre a lista dele em Materiais. */}
              <Link to="/rbs/fila?status=analise" viewTransition className="rounded-2xl transition-shadow hover:shadow-menu" aria-label={`Esperando análise: ${dado.entregas.esperandoAnalise}. Abrir em Materiais`}>
                <Indicador rotulo="Esperando análise" valor={dado.entregas.esperandoAnalise} legenda={`${dado.entregas.atrasadas} ${dado.entregas.atrasadas === 1 ? 'entrega' : 'entregas'} com prazo vencido`} />
              </Link>
              {/* O número grande é o que as marcas veem; a legenda é a situação da versão mais nova e fecha com o total. */}
              <Indicador
                rotulo="Relatórios com a marca"
                valor={dado.relatorios.marcaVe ?? dado.relatorios.disponibilizado}
                legenda={`Versão mais nova: ${dado.relatorios.disponibilizado} disponibilizada · ${dado.relatorios.validado} validada · ${dado.relatorios.rascunho} em rascunho · ${dado.relatorios.semRelatorio} sem relatório`}
              />
            </Grade3>

            <Grade2>
              <Cartao titulo="Entregas por status">
                <ul className="flex flex-col gap-2">
                  {STATUS_ENTREGA.map((codigo) => (
                    <li key={codigo} className="border-b border-borda last:border-b-0">
                      {/* U8: cada status abre a lista dele em Materiais. */}
                      <Link
                        to={`/rbs/fila?${VISAO_DO_STATUS[codigo] === 'falta-aba' ? 'aba=falta' : `status=${VISAO_DO_STATUS[codigo]}`}`}
                        viewTransition
                        className="flex items-center justify-between rounded-lg py-2 transition-colors hover:bg-cinza-100"
                      >
                        <Status codigo={codigo} />
                        <span className="flex items-center gap-1 text-sm font-bold">
                          {numero(dado.entregas.porStatus[codigo] ?? 0)}
                          <i className="ri-arrow-right-s-line text-texto-2" aria-hidden="true" />
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </Cartao>
              <Cartao titulo="Última carga">
                {dado.ultimaCarga ? (
                  <div className="flex flex-col gap-2 text-sm">
                    <p>
                      <span className="font-semibold">{dado.ultimaCarga.tipo === 'PESQUISA' ? 'Resultados de pesquisa' : 'Veiculação de mídia'}</span> ·{' '}
                      {dataHora(dado.ultimaCarga.executadaEm)}
                    </p>
                    <p>
                      {numero(dado.ultimaCarga.linhasInseridas)} linhas inseridas · {numero(dado.ultimaCarga.linhasRejeitadas)} rejeitadas
                    </p>
                    <Link to="/rbs/cargas" className="text-xs font-bold text-secao-texto hover:underline">
                      Ver cargas e rejeições →
                    </Link>
                  </div>
                ) : (
                  <Aviso tipo="vazio" titulo="Nenhuma carga ainda">
                    Envie a planilha de veiculação em Cargas.
                  </Aviso>
                )}
              </Cartao>
            </Grade2>

            {/* F9 (plano da base da RBS): as pendências agrupadas por marca, e o que vale para todas (os números da edição)
                aparece uma vez só, em vez de uma linha por marca. */}
            <Cartao titulo="Pendências para o relatório">
              {dado.pendencias.length === 0 ? (
                <Aviso tipo="vazio" titulo="Nenhuma pendência nesta edição" />
              ) : (
                <PendenciasPorMarca pendencias={dado.pendencias} />
              )}
            </Cartao>
          </>
        )}
      </Carregando>
    </div>
  )
}

type Pendencia = DadosDoPainel['pendencias'][number]

/** Uma linha por marca com as pendências dela (as três primeiras à vista); as de toda a edição numa linha só, no topo. */
function PendenciasPorMarca({ pendencias }: { pendencias: Pendencia[] }) {
  const daEdicao = pendencias.filter((item) => item.tipo === 'FESTIVAL_SEM_NUMEROS')
  const porMarca = new Map<number, Pendencia[]>()
  for (const item of pendencias) {
    if (item.tipo === 'FESTIVAL_SEM_NUMEROS') continue
    porMarca.set(item.parceriaId, [...(porMarca.get(item.parceriaId) ?? []), item])
  }
  return (
    <div className="flex flex-col gap-3">
      {daEdicao.length > 0 && (
        <p className="rounded-xl bg-cinza-100 px-4 py-3 text-sm">
          <span className="font-semibold">{nomePendencia('FESTIVAL_SEM_NUMEROS')}</span>
          <span className="text-texto-2"> · {daEdicao[0].detalhe} (vale para todas as marcas). </span>
          <Link to="/rbs/relatorios" viewTransition className="font-semibold text-secao-texto hover:underline">
            Informar →
          </Link>
        </p>
      )}
      <ul className="flex flex-col divide-y divide-borda">
        {[...porMarca.entries()].map(([parceriaId, itens], indice) => (
          <li key={parceriaId} className="entrar-em-cascata py-3" style={{ '--ordem': indice } as React.CSSProperties}>
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <Link to={`/parceria/${parceriaId}`} viewTransition className="font-semibold hover:underline">
                {itens[0].patrocinador}
              </Link>
              <span className="text-xs font-semibold text-texto-2">
                {itens.length} {itens.length === 1 ? 'pendência' : 'pendências'}
              </span>
            </div>
            <ul className="mt-1.5 flex flex-col gap-1 text-sm">
              {itens.slice(0, 3).map((item, i) => (
                <li key={i} className="flex gap-2">
                  <span className="shrink-0 text-xs font-bold text-secao-texto">{nomePendencia(item.tipo)}</span>
                  <span className="text-texto-2">{item.detalhe}</span>
                </li>
              ))}
              {itens.length > 3 && <li className="text-xs font-semibold text-texto-2">e mais {itens.length - 3}</li>}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  )
}
