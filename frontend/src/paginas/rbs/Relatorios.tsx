// Relatórios da RBS (protótipo R11): situação do relatório de cada parceria, com gerar, validar e disponibilizar. [ITEM-6] [RELATORIO]
import { useState } from 'react'
import { Link } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEventos } from '../../api/parcerias'
import { enderecoRelatoriosCsv, gerarRelatorio, listarRelatorios, mudarStatusRelatorio, type LinhaRelatorios } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Filtros, Seletor } from '../../componentes/Formulario'
import { Status } from '../../componentes/Status'
import { Tabela } from '../../componentes/Tabela'
import { dataHora } from '../../util/formatar'

type Situacao = 'TODAS' | LinhaRelatorios['situacao']

export function Relatorios() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const [situacao, setSituacao] = useState<Situacao>('TODAS')
  const lista = useDados(() => listarRelatorios({ eventoId: eventoId ? Number(eventoId) : undefined }), [eventoId])
  const [ocupado, setOcupado] = useState<number | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  const todas = lista.dado ?? []
  const contar = (codigo: Situacao) => todas.filter((linha) => codigo === 'TODAS' || linha.situacao === codigo).length
  const visiveis = todas.filter((linha) => situacao === 'TODAS' || linha.situacao === situacao)

  async function agir(parceriaId: number, fazer: () => Promise<unknown>) {
    setFalha(null)
    setOcupado(parceriaId)
    try {
      await fazer()
      lista.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível concluir.')
    } finally {
      setOcupado(null)
    }
  }

  function acoes(linha: LinhaRelatorios) {
    const relatorio = linha.relatorio
    const ocupada = ocupado !== null
    const botao = 'text-xs font-bold whitespace-nowrap hover:underline disabled:opacity-40'
    return (
      <div className="flex justify-end gap-3">
        <button type="button" className={botao} disabled={ocupada} onClick={() => agir(linha.parceriaId, () => gerarRelatorio(linha.parceriaId))}>
          {relatorio ? 'Nova versão' : 'Gerar'}
        </button>
        {relatorio?.status === 'RASCUNHO' && (
          <button type="button" className={botao} disabled={ocupada} onClick={() => agir(linha.parceriaId, () => mudarStatusRelatorio(relatorio.id, 'VALIDADO'))}>
            Validar
          </button>
        )}
        {relatorio?.status === 'VALIDADO' && (
          <button type="button" className={`${botao} text-[#007a2a]`} disabled={ocupada} onClick={() => agir(linha.parceriaId, () => mudarStatusRelatorio(relatorio.id, 'DISPONIBILIZADO'))}>
            Disponibilizar
          </button>
        )}
        <Link to={`/parceria/${linha.parceriaId}/relatorio`} className={botao}>
          Abrir →
        </Link>
      </div>
    )
  }

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Relatórios pós-evento"
        subtitulo="Gere o relatório de cada parceria a partir da base, valide os números e disponibilize para a marca."
        acoes={
          <a
            href={enderecoRelatoriosCsv({ eventoId: eventoId ? Number(eventoId) : undefined })}
            className="texto-botao inline-flex h-12 items-center gap-2 rounded-[10px] bg-white px-6 text-tinta hover:bg-cinza-100"
          >
            <i className="ri-download-line text-lg" aria-hidden="true" /> Exportar lista
          </a>
        }
      />

      <div className="grid gap-4 md:grid-cols-4">
        {/* Contagem pela versão mais nova (a mesma do filtro); a legenda lembra quantas marcas já veem uma versão anterior. */}
        <Indicador rotulo="Sem relatório" valor={contar('SEM_RELATORIO')} />
        <Indicador rotulo="Rascunho" valor={contar('RASCUNHO')} legenda="Versão mais nova ainda não vista pela marca" />
        <Indicador rotulo="Validado" valor={contar('VALIDADO')} legenda="Números conferidos, falta disponibilizar" />
        <Indicador
          rotulo="Disponibilizado"
          valor={contar('DISPONIBILIZADO')}
          legenda={`Versão mais nova com a marca · ${todas.filter((linha) => linha.disponibilizada).length} marcas veem algum relatório`}
        />
      </div>

      <Cartao>
        <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
          <Filtros<Situacao>
            valor={situacao}
            aoMudar={setSituacao}
            opcoes={[
              { valor: 'TODAS', nome: 'Todas', total: contar('TODAS') },
              { valor: 'SEM_RELATORIO', nome: 'Sem relatório' },
              { valor: 'RASCUNHO', nome: 'Rascunho' },
              { valor: 'VALIDADO', nome: 'Validado' },
              { valor: 'DISPONIBILIZADO', nome: 'Disponibilizado' },
            ]}
          />
          <div className="w-64">
            <Seletor rotulo="Edição" value={eventoId} onChange={(evento) => setEventoId(evento.target.value)}>
              <option value="">Edição mais nova</option>
              {(eventos.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nome} {item.edicao}
                </option>
              ))}
            </Seletor>
          </div>
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
        <Carregando dados={lista}>
          {() => (
            <Tabela<LinhaRelatorios>
              linhas={visiveis}
              chave={(linha) => linha.parceriaId}
              vazio="Nenhuma parceria com esse filtro."
              colunas={[
                { titulo: 'Marca', celula: (linha) => <span className="font-semibold">{linha.patrocinador}</span> },
                { titulo: 'Cota', celula: (linha) => linha.cota.nome },
                {
                  titulo: 'Situação',
                  celula: (linha) =>
                    linha.relatorio ? (
                      <>
                        <Status codigo={linha.relatorio.status} complemento={`v${linha.relatorio.versao}`} />
                        {linha.disponibilizada && linha.disponibilizada.versao !== linha.relatorio.versao && (
                          <span className="mt-0.5 block text-xs font-medium text-texto-2">A marca vê a v{linha.disponibilizada.versao}</span>
                        )}
                      </>
                    ) : (
                      <span className="text-xs text-texto-2">Sem relatório</span>
                    ),
                },
                { titulo: 'Gerado em', celula: (linha) => dataHora(linha.relatorio?.geradoEm) },
                {
                  titulo: 'Indicadores',
                  celula: (linha) =>
                    linha.relatorio ? `${linha.relatorio.indicadoresDisponiveis} com dado · ${linha.relatorio.indicadoresIndisponiveis} indisponíveis` : '—',
                },
                { titulo: 'Faltantes', celula: (linha) => linha.faltantes, classe: 'text-center' },
                { titulo: '', celula: acoes },
              ]}
            />
          )}
        </Carregando>
        {/* As duas colunas de números se explicam (U03). */}
        <p className="mt-3 text-xs font-medium text-texto-2">
          Indicadores: dos 17 do relatório, quantos têm dado e quantos saem como indisponíveis, com o motivo. Faltantes: o que impede
          ou enfraquece o relatório, como mídia abaixo do previsto, aplicação sem foto ou entrega pendente.
        </p>
      </Cartao>
    </div>
  )
}
