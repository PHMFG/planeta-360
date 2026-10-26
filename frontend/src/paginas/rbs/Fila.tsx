// Fila de materiais da RBS (Figma R01): entregas de todas as parcerias, as que esperam análise primeiro. [ITEM-4]
import { useState } from 'react'
import { Link } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { listarEntregas, listarFila, type Entrega, type ItemFila } from '../../api/materiais'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Busca, Filtros, Seletor } from '../../componentes/Formulario'
import { Paginacao } from '../../componentes/LinhaDoTempo'
import { SECOES } from '../../componentes/secoes'
import { Status } from '../../componentes/Status'
import { Tabela } from '../../componentes/Tabela'
import { DetalheEntrega } from '../parceria/DetalheEntrega'
import { data, dataHora } from '../../util/formatar'

type Visao = 'analise' | 'ajuste' | 'aprovadas' | 'todas'

const STATUS_DA_VISAO: Record<Visao, string | undefined> = {
  analise: 'ENVIADO,EM_ANALISE',
  ajuste: 'AJUSTE_SOLICITADO',
  aprovadas: 'APROVADO',
  todas: undefined,
}

export function Fila() {
  const [visao, setVisao] = useState<Visao>('analise')
  const [aba, setAba] = useState('')
  const [busca, setBusca] = useState('')
  const [atrasadas, setAtrasadas] = useState(false)
  const [pagina, setPagina] = useState(0)
  const fila = useDados(
    () => listarFila({ status: STATUS_DA_VISAO[visao], abaCodigo: aba || undefined, q: busca.trim() || undefined, atrasadas: atrasadas || undefined, pagina }),
    [visao, aba, busca, atrasadas, pagina],
  )
  const [aberta, setAberta] = useState<Entrega | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  // A fila traz o resumo; o detalhe (com as versões) vem das entregas da parceria.
  async function abrir(item: ItemFila) {
    setFalha(null)
    try {
      const entregas = await listarEntregas(item.parceria.id, item.abaCodigo)
      setAberta(entregas.find((entrega) => entrega.id === item.id) ?? null)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível abrir a entrega.')
    }
  }

  const mudarFiltro = <T,>(definir: (valor: T) => void) => (valor: T) => {
    definir(valor)
    setPagina(0)
  }

  return (
    <div data-secao="midia" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Fila de materiais"
        subtitulo="Materiais enviados pelas marcas: registre o recebimento, peça ajuste ou aprove."
      />
      <Cartao>
        <div className="mb-4 grid gap-4 md:grid-cols-[1fr_240px]">
          <Busca valor={busca} aoMudar={mudarFiltro(setBusca)} placeholder="Buscar pelo título da entrega…" />
          <Seletor rotulo="Aba" value={aba} onChange={(evento) => mudarFiltro(setAba)(evento.target.value)}>
            <option value="">Todas as abas</option>
            {SECOES.filter((secao) => secao.aba).map((secao) => (
              <option key={secao.chave} value={secao.aba!}>
                {secao.nome}
              </option>
            ))}
          </Seletor>
        </div>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-4">
          <Filtros<Visao>
            valor={visao}
            aoMudar={mudarFiltro(setVisao)}
            opcoes={[
              { valor: 'analise', nome: 'Esperando análise' },
              { valor: 'ajuste', nome: 'Ajuste solicitado' },
              { valor: 'aprovadas', nome: 'Aprovadas' },
              { valor: 'todas', nome: 'Todas' },
            ]}
          />
          <label className="flex items-center gap-2 text-xs font-semibold">
            <input type="checkbox" className="size-4 accent-[var(--secao-cor)]" checked={atrasadas} onChange={(evento) => mudarFiltro(setAtrasadas)(evento.target.checked)} />
            Só com prazo vencido
          </label>
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
        <Carregando dados={fila}>
          {(dado) => (
            <>
              <Tabela<ItemFila>
                linhas={dado.itens}
                chave={(item) => item.id}
                vazio="Nada na fila com esses filtros."
                aoClicar={abrir}
                colunas={[
                  {
                    titulo: 'Marca',
                    celula: (item) => (
                      <Link to={`/parceria/${item.parceria.id}`} className="font-semibold hover:underline" onClick={(evento) => evento.stopPropagation()}>
                        {item.parceria.patrocinador}
                      </Link>
                    ),
                  },
                  { titulo: 'Aba', celula: (item) => SECOES.find((secao) => secao.aba === item.abaCodigo)?.nome ?? item.abaCodigo },
                  { titulo: 'Entrega', celula: (item) => item.titulo },
                  { titulo: 'Status', celula: (item) => <Status codigo={item.status} /> },
                  { titulo: 'Último envio', celula: (item) => dataHora(item.ultimoEnvioEm) },
                  {
                    titulo: 'Prazo',
                    celula: (item) => <span className={item.atrasada ? 'font-bold text-[#c8203f]' : ''}>{data(item.prazo)}{item.atrasada && ' · vencido'}</span>,
                  },
                ]}
              />
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      </Cartao>
      <DetalheEntrega entrega={aberta} aoFechar={() => setAberta(null)} aoMudar={fila.recarregar} />
    </div>
  )
}
