// Telas de gestão da RBS (painel, patrocinadores, fila, cargas, relatórios, histórico), no modo neutro. [ITEM-7]
// Nesta etapa o conteúdo ainda é provisório; cada tela entra junto com a rota da API correspondente.
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'

export function PaginaGestao({ titulo, subtitulo }: { titulo: string; subtitulo: string }) {
  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo={titulo} subtitulo={subtitulo} />
      <Aviso tipo="ausente" titulo="Tela em construção">
        As informações desta tela chegam com as próximas rotas da API.
      </Aviso>
    </div>
  )
}
