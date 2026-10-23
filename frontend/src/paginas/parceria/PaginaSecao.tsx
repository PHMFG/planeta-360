// Tela de uma das oito seções da parceria: cabeçalho na cor da seção e o conteúdo da aba. [ITEM-7]
// Nesta etapa o conteúdo ainda é provisório; as telas de cada seção entram uma a uma.
import { useSessao } from '../../sessao/Sessao'
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { nomeCota } from '../../componentes/Menu'
import type { Secao } from '../../componentes/secoes'
import { useParceria } from './ContextoParceria'

export function PaginaSecao({ secao }: { secao: Secao }) {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const trilha = usuario?.perfil === 'ADMIN_RBS' ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'
  const aba = secao.aba ? parceria.abas.find((item) => item.codigo === secao.aba) : null
  const bloqueada = aba ? !aba.liberada : false

  return (
    <div data-secao={secao.chave} className="flex flex-col gap-4">
      <CabecalhoSecao trilha={trilha} titulo={secao.nome} subtitulo={secao.subtitulo} />
      {bloqueada ? (
        // [PERMISSAO] Estado "benefício não contratado" do S00.
        <Aviso tipo="restrito" titulo="Esta aba não faz parte da sua cota">
          A cota {nomeCota(parceria)} não inclui {secao.nome.toLowerCase()}. Para contratar, fale com o time B2B da RBS.
        </Aviso>
      ) : (
        <Aviso tipo="ausente" titulo="Tela em construção">
          {parceria.patrocinador} · {nomeCota(parceria)} · {parceria.evento.nome} {parceria.evento.edicao}
        </Aviso>
      )}
    </div>
  )
}
