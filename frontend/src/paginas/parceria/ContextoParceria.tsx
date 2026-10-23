// Abre uma parceria pelo id da rota e entrega os dados dela às telas das oito seções. [ITEM-3] [ISOLAMENTO]
// O patrocinador só tem as próprias parcerias na sessão; a RBS consulta qualquer uma pela API.
import { useEffect, useState } from 'react'
import { useOutletContext, useParams } from 'react-router'
import { ErroApi, pedir } from '../../api/cliente'
import type { ParceriaResumo } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Layout } from '../../componentes/Layout'
import { TelaCarregando, useSessao } from '../../sessao/Sessao'

type Contexto = { parceria: ParceriaResumo }

export function useParceria() {
  return useOutletContext<Contexto>().parceria
}

export function ContextoParceria() {
  const { usuario } = useSessao()
  const { parceriaId } = useParams()
  const id = Number(parceriaId)
  const daSessao = usuario?.parcerias.find((item) => item.id === id) ?? null
  const rbs = usuario?.perfil === 'ADMIN_RBS'

  const [daApi, setDaApi] = useState<ParceriaResumo | null>(null)
  const [erro, setErro] = useState<string | null>(null)

  useEffect(() => {
    if (!rbs || !Number.isFinite(id)) return
    setDaApi(null)
    setErro(null)
    pedir<ParceriaResumo>(`/parcerias/${id}`)
      .then(setDaApi)
      .catch((falha: unknown) => setErro(falha instanceof ErroApi ? falha.message : 'Não foi possível abrir a parceria.'))
  }, [rbs, id])

  const parceria = rbs ? daApi : daSessao

  if (rbs && !parceria && !erro) return <TelaCarregando />
  if (!parceria) {
    // [ISOLAMENTO] Parceria de outra marca e parceria inexistente recebem a mesma resposta.
    return (
      <div className="grid min-h-screen place-items-center p-8">
        <Aviso tipo="restrito" titulo="Parceria não encontrada" className="max-w-lg">
          {erro ?? 'Confira o endereço ou volte para a tela inicial. Cada patrocinador vê apenas a própria parceria.'}
        </Aviso>
      </div>
    )
  }
  return <Layout parceria={parceria} contexto={{ parceria } satisfies Contexto} />
}
