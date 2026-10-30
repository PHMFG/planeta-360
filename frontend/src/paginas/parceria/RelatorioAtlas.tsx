// Relatório do Atlas: documento de trabalho da marca, em A4, com a origem de cada número em "Fontes e cálculos". [ATLAS]
// Não é o relatório oficial: a RBS não valida este documento, e o selo diz isso em toda página.
import { useEffect, useRef } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { NOME_STATUS_ATLAS, listarRelatoriosAtlas, obterRelatorioAtlas, type RelatorioAtlasResumo } from '../../api/atlas'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao, LinkBotao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { RenderizadorDeRelatorio } from '../../componentes/relatorio/RenderizadorDeRelatorio'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { dataHora } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'

export function RelatorioAtlas() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const { relatorioId = '' } = useParams()
  const id = /^\d+$/.test(relatorioId) ? Number(relatorioId) : -1
  const relatorio = useDados(() => obterRelatorioAtlas(id), [id])
  // "Imprimir ou salvar PDF" na janela do Atlas abre esta página com ?imprimir=1: a impressão sai daqui, sem o chat ao lado.
  const [busca, setBusca] = useSearchParams()
  const impresso = useRef(false)
  const pronto = relatorio.pronto
  useEffect(() => {
    if (!pronto || impresso.current || busca.get('imprimir') !== '1') return
    impresso.current = true
    setBusca({}, { replace: true })
    window.setTimeout(() => window.print(), 300)
  }, [pronto, busca, setBusca])
  useRecarregarSeAbaFechou(relatorio)

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <div className="print:hidden">
        <CabecalhoSecao
          trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
          titulo="Relatório do Atlas"
          subtitulo="Documento de trabalho montado pelo Atlas com os números do portal e os que a marca informou."
        />
      </div>
      <Carregando dados={relatorio}>
        {(dado) => (
          <>
            {dado.parceriaId !== parceria.id && <Aviso tipo="restrito" titulo="Este relatório é de outra parceria" />}
            {dado.status !== 'PRONTO' && (
              <Aviso tipo="ausente" titulo={`Relatório ${NOME_STATUS_ATLAS[dado.status].toLowerCase()}`}>
                {dado.status === 'DESCARTADO'
                  ? 'Ficou parado por mais de 7 dias e foi descartado. Peça um relatório novo ao Atlas.'
                  : 'Ainda não terminou: os números abaixo são os de agora e podem mudar até o Atlas finalizar.'}
              </Aviso>
            )}
            <div className="flex flex-wrap gap-3 print:hidden">
              <Botao variante="secundario" icone="ri-printer-line" onClick={() => window.print()}>
                Imprimir ou salvar PDF
              </Botao>
              <LinkBotao to={`/parceria/${parceria.id}/relatorio`} variante="secundario" icone="ri-arrow-left-line">
                Voltar aos relatórios
              </LinkBotao>
            </div>
            <p className="text-xs font-medium text-texto-2 print:hidden">
              Pedido por {dado.criadoPor?.nome ?? '—'} em {dataHora(dado.criadoEm)}
              {dado.finalizadoEm && ` · finalizado em ${dataHora(dado.finalizadoEm)}`}
            </p>
            <RenderizadorDeRelatorio relatorio={dado} />
          </>
        )}
      </Carregando>
    </div>
  )
}

/** A lista dos relatórios do Atlas da parceria, na tela Relatório (marca e RBS veem os mesmos). */
export function RelatoriosDoAtlas() {
  const parceria = useParceria()
  const lista = useDados(
    // Aba do modelo fechada para a marca (403) ou relatório do Atlas desligado na API (404): o cartão some em vez de mostrar erro.
    () => listarRelatoriosAtlas(parceria.id).catch((erro: unknown) => (erro instanceof ErroApi && (erro.status === 403 || erro.status === 404) ? null : Promise.reject(erro))),
    [parceria.id],
  )
  if (lista.pronto && lista.dado === null) return null

  return (
    <Cartao titulo="Relatórios do Atlas" className="print:hidden">
      <p className="mb-4 text-sm font-medium text-texto-2">
        Documentos de trabalho da marca, montados pelo Atlas. Não substituem o relatório oficial acima, que a RBS valida.
      </p>
      <Carregando dados={lista} vazio={(dado) => !dado?.length} textoVazio="Nenhum relatório do Atlas ainda. Abra o Atlas no menu e peça o relatório da ativação.">
        {(dado) => (
          <Tabela<RelatorioAtlasResumo>
            linhas={dado ?? []}
            chave={(item) => item.id}
            colunas={[
              {
                titulo: 'Relatório',
                celula: (item) => (
                  <Link to={`/parceria/${parceria.id}/relatorios-atlas/${item.id}`} className="font-semibold text-secao-texto hover:underline">
                    {item.titulo}
                  </Link>
                ),
              },
              { titulo: 'Situação', celula: (item) => NOME_STATUS_ATLAS[item.status] },
              { titulo: 'Pedido por', celula: (item) => item.criadoPor?.nome ?? '—' },
              { titulo: 'Finalizado em', celula: (item) => dataHora(item.finalizadoEm) },
            ]}
          />
        )}
      </Carregando>
    </Cartao>
  )
}
