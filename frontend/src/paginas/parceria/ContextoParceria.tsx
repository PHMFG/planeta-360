// Abre uma parceria pelo id da rota e entrega os dados dela às telas das oito seções. [ITEM-3] [ISOLAMENTO]
// O patrocinador só tem as próprias parcerias na sessão; a RBS consulta qualquer uma pela API.
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext, useParams } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { obterParceria, paraResumo } from '../../api/parcerias'
import type { ParceriaResumo } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Layout } from '../../componentes/Layout'
import { TelaCarregando, useSessao } from '../../sessao/Sessao'

type Contexto = { parceria: ParceriaResumo; recarregar: () => void }

export function useParceria() {
  return useOutletContext<Contexto>().parceria
}

/** Para telas que mudam a parceria (ex.: liberar uma aba) atualizarem o menu na hora. */
export function useRecarregarParceria() {
  return useOutletContext<Contexto>().recarregar
}

export function ContextoParceria() {
  const { usuario } = useSessao()
  const { parceriaId } = useParams()
  const id = Number(parceriaId)
  const daSessao = usuario?.parcerias.find((item) => item.id === id) ?? null
  const rbs = usuario?.perfil === 'ADMIN_RBS'

  const [daApi, setDaApi] = useState<ParceriaResumo | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [versao, setVersao] = useState(0)

  useEffect(() => {
    if (!rbs || !Number.isFinite(id)) return
    setErro(null)
    obterParceria(id)
      .then((detalhe) => setDaApi(paraResumo(detalhe)))
      .catch((falha: unknown) => setErro(falha instanceof ErroApi ? falha.message : 'Não foi possível abrir a parceria.'))
  }, [rbs, id, versao])

  // Trocar de parceria limpa a anterior, para o menu não mostrar a parceria errada enquanto carrega.
  const [idAnterior, setIdAnterior] = useState(id)
  if (id !== idAnterior) {
    setIdAnterior(id)
    setDaApi(null)
  }
  const recarregar = useCallback(() => setVersao((atual) => atual + 1), [])

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
  return <Layout parceria={parceria} contexto={{ parceria, recarregar } satisfies Contexto} />
}
