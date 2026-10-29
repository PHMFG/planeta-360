// As quatro abas fora da prioridade da Entrega 2 (Aplicações de marca, Cortesias, Credenciamento, Contrato). [ITEM-7]
// [PENDENTE] As telas próprias de cada aba (Figma) ficam para depois; nesta versão a aba mostra as suas entregas.
import { useSessao } from '../../sessao/Sessao'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import type { Secao } from '../../componentes/secoes'
import { useParceria } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

export function PaginaAba({ secao }: { secao: Secao }) {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, secao.aba)

  return (
    <div data-secao={secao.chave} className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={secao.nome}
        subtitulo={secao.subtitulo}
      />
      {/* [PERMISSAO] A marca não vê o conteúdo de aba fechada; a API também recusa (403). A RBS vê, com o aviso. */}
      <AvisoAbaFechada parceria={parceria} estado={estado} nome={secao.nome} rbs={rbs} />
      {(rbs || estado === 'LIBERADA') && <EntregasDaAba aba={secao.aba!} />}
    </div>
  )
}
