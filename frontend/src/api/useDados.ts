// Busca um dado da API e guarda o estado (carregando, erro, dado) para a tela mostrar o aviso certo. [ITEM-7]
import { useCallback, useEffect, useState } from 'react'
import { ErroApi } from './cliente'

export type Dados<T> = {
  dado: T | null
  /** Verdadeiro depois da primeira resposta certa, mesmo que o dado seja null ("ainda não existe"). */
  pronto: boolean
  carregando: boolean
  erro: string | null
  /** Status HTTP da falha (ex.: 403 quando a RBS fechou a aba depois que a tela abriu). */
  erroStatus: number | null
  recarregar: () => void
}

export function useDados<T>(buscar: () => Promise<T>, dependencias: unknown[]): Dados<T> {
  const [dado, setDado] = useState<T | null>(null)
  const [pronto, setPronto] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState<string | null>(null)
  const [erroStatus, setErroStatus] = useState<number | null>(null)
  const [versao, setVersao] = useState(0)

  // A busca muda só quando as dependências informadas mudam (ex.: o id da parceria).
  const executar = useCallback(buscar, dependencias)

  useEffect(() => {
    let ativo = true
    setCarregando(true)
    executar()
      .then((resultado) => {
        if (!ativo) return
        setDado(resultado)
        setPronto(true)
        setErro(null)
        setErroStatus(null)
      })
      .catch((falha: unknown) => {
        if (!ativo) return
        setErro(falha instanceof ErroApi ? falha.message : 'Não foi possível carregar.')
        setErroStatus(falha instanceof ErroApi ? falha.status : null)
      })
      .finally(() => ativo && setCarregando(false))
    return () => {
      ativo = false
    }
  }, [executar, versao])

  const recarregar = useCallback(() => setVersao((atual) => atual + 1), [])

  // Quem volta para a aba do navegador vê o que a outra ponta mudou (a RBS aprovou, a marca reenviou), sem recarregar a página.
  useEffect(() => {
    const aoVoltar = () => document.visibilityState === 'visible' && recarregar()
    document.addEventListener('visibilitychange', aoVoltar)
    return () => document.removeEventListener('visibilitychange', aoVoltar)
  }, [recarregar])

  return { dado, pronto, carregando, erro, erroStatus, recarregar }
}
