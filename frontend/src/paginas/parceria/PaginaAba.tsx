// As quatro abas fora da prioridade da Entrega 2 (Aplicações de marca, Cortesias, Credenciamento, Contrato). [ITEM-7]
// [PENDENTE] As telas próprias de cada aba (Figma) ficam para depois; nesta versão a aba mostra as suas entregas.
import { useSessao } from '../../sessao/Sessao'
import { Aviso } from '../../componentes/Aviso'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { nomeCota } from '../../componentes/Menu'
import type { Secao } from '../../componentes/secoes'
import { useParceria } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

export function PaginaAba({ secao }: { secao: Secao }) {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const aba = parceria.abas.find((item) => item.codigo === secao.aba)
  const bloqueada = aba ? !aba.liberada : false

  return (
    <div data-secao={secao.chave} className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={secao.nome}
        subtitulo={secao.subtitulo}
      />
      {bloqueada && !rbs ? (
        // [PERMISSAO] Estado "benefício não contratado" do S00; a API também recusa (403).
        <Aviso tipo="restrito" titulo="Esta aba não faz parte da sua cota">
          A cota {nomeCota(parceria)} não inclui {secao.nome.toLowerCase()}. Para contratar, fale com o time B2B da RBS.
        </Aviso>
      ) : (
        <>
          {bloqueada && (
            <Aviso tipo="restrito" titulo="Aba fechada para esta parceria">
              A marca não vê esta aba. Para liberar, use Perfil · Abas da parceria.
            </Aviso>
          )}
          {secao.desejavel && (
            <Aviso titulo="Funcionalidade desejável">
              Nesta versão, a aba reúne as entregas combinadas com a RBS, com envio de arquivo, status e histórico.
            </Aviso>
          )}
          <EntregasDaAba aba={secao.aba!} />
        </>
      )}
    </div>
  )
}
