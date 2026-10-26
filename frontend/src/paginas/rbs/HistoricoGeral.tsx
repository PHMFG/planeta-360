// Histórico geral da RBS (protótipo R12): ações de todas as parcerias e os acessos, com filtros e exportação. [ITEM-4] [HISTORICO]
import { useState } from 'react'
import { NOMES_ACAO, enderecoHistoricoCsv, historicoGeral, type FiltrosHistorico } from '../../api/materiais'
import { listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Busca, Seletor } from '../../componentes/Formulario'
import { LinhaDoTempo, Paginacao } from '../../componentes/LinhaDoTempo'

export function HistoricoGeral() {
  const parcerias = useDados(() => listarParcerias(), [])
  const [parceriaId, setParceriaId] = useState('')
  const [acao, setAcao] = useState('')
  const [de, setDe] = useState('')
  const [ate, setAte] = useState('')
  const [q, setQ] = useState('')
  const [pagina, setPagina] = useState(0)
  const filtros: FiltrosHistorico = {
    parceriaId: parceriaId ? Number(parceriaId) : undefined,
    acao: acao || undefined,
    de: de || undefined,
    ate: ate || undefined,
    q: q.trim() || undefined,
  }
  const historico = useDados(() => historicoGeral({ ...filtros, pagina }), [parceriaId, acao, de, ate, q, pagina])
  const filtrar = (definir: (valor: string) => void) => (valor: string) => {
    definir(valor)
    setPagina(0)
  }

  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Histórico"
        subtitulo="Quem fez o quê, e quando, em todas as parcerias, incluindo entradas e tentativas de acesso."
        acoes={
          <a href={enderecoHistoricoCsv(filtros)} className="texto-botao inline-flex h-12 items-center gap-2 rounded-[10px] bg-white px-6 text-tinta hover:bg-cinza-100">
            <i className="ri-download-line text-lg" aria-hidden="true" /> Exportar CSV
          </a>
        }
      />
      <Cartao>
        <div className="mb-4 grid gap-4 md:grid-cols-4">
          <Seletor rotulo="Parceria" value={parceriaId} onChange={(evento) => filtrar(setParceriaId)(evento.target.value)}>
            <option value="">Todas</option>
            {(parcerias.dado ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.patrocinador.nome} · {item.evento.edicao}
              </option>
            ))}
          </Seletor>
          <Seletor rotulo="Ação" value={acao} onChange={(evento) => filtrar(setAcao)(evento.target.value)}>
            <option value="">Todas</option>
            {Object.entries(NOMES_ACAO).map(([codigo, nome]) => (
              <option key={codigo} value={codigo}>
                {nome}
              </option>
            ))}
          </Seletor>
          <Campo rotulo="De" type="date" value={de} onChange={(evento) => filtrar(setDe)(evento.target.value)} />
          <Campo rotulo="Até" type="date" value={ate} onChange={(evento) => filtrar(setAte)(evento.target.value)} />
        </div>
        <div className="mb-4">
          <Busca valor={q} aoMudar={filtrar(setQ)} placeholder="Buscar no detalhe e nos valores…" />
        </div>
        <Carregando dados={historico} vazio={(dado) => dado.itens.length === 0} textoVazio="Nada encontrado com esses filtros.">
          {(dado) => (
            <>
              <LinhaDoTempo itens={dado.itens} comParceria />
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      </Cartao>
    </div>
  )
}
