// Painel da RBS (Figma R00): parcerias da edição, materiais esperando análise, relatórios e pendências. [ITEM-7]
import { useState } from 'react'
import { Link } from 'react-router'
import { listarEventos } from '../../api/parcerias'
import { obterPainel } from '../../api/resultados'
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

export function Painel() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const painel = useDados(() => obterPainel(eventoId ? Number(eventoId) : undefined), [eventoId])

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Gestão de patrocinadores"
        subtitulo="Resumo das parcerias, materiais na fila e últimas atualizações."
        acoes={
          <LinkBotao to="/rbs/fila" className="bg-white! text-tinta!" icone="ri-inbox-archive-line">
            Abrir a fila
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
                legenda={`${dado.parcerias.total} na edição ${dado.evento.edicao}${dado.parcerias.semUsuario ? ` · ${dado.parcerias.semUsuario} sem usuário` : ''}`}
              />
              <Indicador rotulo="Esperando análise" valor={dado.entregas.esperandoAnalise} legenda={`${dado.entregas.atrasadas} entregas com prazo vencido`} />
              <Indicador
                rotulo="Relatórios disponibilizados"
                valor={dado.relatorios.disponibilizado}
                legenda={`${dado.relatorios.rascunho} em rascunho · ${dado.relatorios.validado} validados · ${dado.relatorios.semRelatorio} sem relatório`}
              />
            </Grade3>

            <Grade2>
              <Cartao titulo="Entregas por status">
                <ul className="flex flex-col gap-2">
                  {STATUS_ENTREGA.map((codigo) => (
                    <li key={codigo} className="flex items-center justify-between border-b border-borda pb-2 last:border-b-0">
                      <Status codigo={codigo} />
                      <span className="text-sm font-bold">{numero(dado.entregas.porStatus[codigo] ?? 0)}</span>
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

            <Cartao titulo="Pendências para o relatório">
              {dado.pendencias.length === 0 ? (
                <Aviso tipo="vazio" titulo="Nenhuma pendência nesta edição" />
              ) : (
                <ul className="flex flex-col">
                  {dado.pendencias.map((item, indice) => (
                    <li key={`${item.parceriaId}-${item.tipo}-${indice}`} className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-borda py-3 text-sm last:border-b-0">
                      <Link to={`/parceria/${item.parceriaId}`} className="w-40 font-semibold hover:underline">
                        {item.patrocinador}
                      </Link>
                      <span className="text-xs font-bold text-secao-texto">{nomePendencia(item.tipo)}</span>
                      <span className="flex-1 text-texto-2">{item.detalhe}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Cartao>
          </>
        )}
      </Carregando>
    </div>
  )
}
