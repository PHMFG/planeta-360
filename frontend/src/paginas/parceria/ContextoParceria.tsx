// Abre uma parceria pelo id da rota e entrega os dados dela às telas das oito seções. [ITEM-3] [ISOLAMENTO]
// O patrocinador só tem as próprias parcerias na sessão; a RBS consulta qualquer uma pela API.
import { useCallback, useEffect, useState } from 'react'
import type { Dados } from '../../api/useDados'
import { useOutletContext, useParams } from 'react-router'
import { ErroApi } from '../../api/cliente'
import { obterParceria, paraResumo } from '../../api/parcerias'
import type { ParceriaResumo } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { LinkBotao } from '../../componentes/Botao'
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

/**
 * Um 403 numa tela da parceria quer dizer que a RBS fechou a aba depois que a tela abriu: lê a parceria de novo, e o menu
 * e a tela passam a mostrar a aba fechada (em vez de "Não foi possível carregar"). [PERMISSAO]
 */
export function useRecarregarSeAbaFechou(dados: Dados<unknown>) {
  const recarregar = useRecarregarParceria()
  const status = dados.erroStatus
  useEffect(() => {
    if (status === 403) recarregar()
  }, [status, recarregar])
}

export function ContextoParceria() {
  const { usuario, atualizar } = useSessao()
  const { parceriaId = '' } = useParams()
  // Id que não é número ("abc") vira -1: nenhuma parceria tem esse id, e a tela cai em "Parceria não encontrada".
  const id = /^\d+$/.test(parceriaId) ? Number(parceriaId) : -1
  const daSessao = usuario?.parcerias.find((item) => item.id === id) ?? null
  const rbs = usuario?.perfil === 'ADMIN_RBS'

  const [daApi, setDaApi] = useState<ParceriaResumo | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [versao, setVersao] = useState(0)

  useEffect(() => {
    if (!rbs || id < 0) return
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
  // A RBS lê a parceria pela API; o patrocinador, pela própria sessão (as abas liberadas vêm com a conta).
  const recarregar = useCallback(() => {
    if (rbs) setVersao((atual) => atual + 1)
    else void atualizar()
  }, [rbs, atualizar])

  // Voltar para a aba do navegador relê a parceria: a RBS pode ter liberado ou fechado abas nesse meio-tempo.
  useEffect(() => {
    const aoVoltar = () => document.visibilityState === 'visible' && recarregar()
    document.addEventListener('visibilitychange', aoVoltar)
    return () => document.removeEventListener('visibilitychange', aoVoltar)
  }, [recarregar])

  const parceria = rbs ? daApi : daSessao

  if (rbs && !parceria && !erro && id >= 0) return <TelaCarregando />
  if (!parceria) {
    // [ISOLAMENTO] Parceria de outra marca e parceria inexistente recebem a mesma resposta.
    return (
      <div className="grid min-h-screen place-items-center p-8">
        <div className="flex max-w-lg flex-col items-start gap-4">
          <Aviso tipo="restrito" titulo="Parceria não encontrada">
            {erro ?? 'Confira o endereço ou volte para a tela inicial. Cada patrocinador vê apenas a própria parceria.'}
          </Aviso>
          <LinkBotao to="/" variante="secundario">
            Voltar para a tela inicial
          </LinkBotao>
        </div>
      </div>
    )
  }
  return <Layout parceria={parceria} contexto={{ parceria, recarregar } satisfies Contexto} />
}
