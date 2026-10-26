// Histórico da parceria: cada envio, mudança de status e acesso, com autor e data. [ITEM-4] [HISTORICO]
import { useState } from 'react'
import { historicoDaParceria } from '../../api/materiais'
import { useDados } from '../../api/useDados'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { LinhaDoTempo, Paginacao } from '../../componentes/LinhaDoTempo'
import { useSessao } from '../../sessao/Sessao'
import { useParceria } from './ContextoParceria'

export function HistoricoParceria() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const [pagina, setPagina] = useState(0)
  const historico = useDados(() => historicoDaParceria(parceria.id, pagina), [parceria.id, pagina])

  return (
    <div data-secao="parceria" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={usuario?.perfil === 'ADMIN_RBS' ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Histórico"
        subtitulo={`Tudo o que aconteceu na parceria ${parceria.patrocinador} · ${parceria.evento.edicao}, com autor e data.`}
      />
      <Cartao>
        <Carregando dados={historico} vazio={(dado) => dado.itens.length === 0} textoVazio="Nada registrado ainda.">
          {(dado) => (
            <>
              <LinhaDoTempo itens={dado.itens} />
              <Paginacao pagina={dado.pagina} tamanho={dado.tamanho} total={dado.total} aoMudar={setPagina} />
            </>
          )}
        </Carregando>
      </Cartao>
    </div>
  )
}
