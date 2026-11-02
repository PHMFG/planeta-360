// Descartar um relatório em montagem (rota 65): pede confirmação antes, porque o cartão aberto é cancelado junto. [ATLAS]
import { useState } from 'react'
import { descartarRelatorioAtlas } from '../api/atlas'
import { ErroApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Modal } from '../componentes/Modal'
import { Chip } from './Elementos'

type Props = {
  relatorioId: number
  /** Na janela do Atlas o botão é um chip sobre o grafite; na página do relatório, um botão do portal. */
  noAtlas?: boolean
  desabilitado?: boolean
  aoDescartar: () => void | Promise<void>
}

export function BotaoDescartar({ relatorioId, noAtlas = false, desabilitado = false, aoDescartar }: Props) {
  const [aberto, setAberto] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)

  async function descartar() {
    setEnviando(true)
    setFalha(null)
    try {
      await descartarRelatorioAtlas(relatorioId)
      setAberto(false)
      await aoDescartar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível descartar o relatório.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      {noAtlas ? (
        <Chip icone="ri-delete-bin-line" disabled={desabilitado} onClick={() => setAberto(true)}>
          Descartar
        </Chip>
      ) : (
        <Botao variante="secundario" icone="ri-delete-bin-line" disabled={desabilitado} onClick={() => setAberto(true)}>
          Descartar relatório
        </Botao>
      )}
      {/* O Atlas escreve em branco sobre o grafite: o modal volta às cores do portal. */}
      <div data-secao="parceria" className="text-tinta">
        <Modal titulo="Descartar este relatório?" aberto={aberto} aoFechar={() => setAberto(false)}>
          <p className="text-sm font-medium text-texto-2">
            O relatório em montagem deixa de seguir, e o cartão de perguntas aberto é cancelado. Os números já informados continuam na aba Aplicações, e você pode pedir um relatório novo quando quiser.
          </p>
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div className="flex flex-wrap gap-3">
            <Botao icone="ri-delete-bin-line" carregando={enviando} onClick={descartar}>
              Descartar relatório
            </Botao>
            <Botao variante="secundario" disabled={enviando} onClick={() => setAberto(false)}>
              Agora não
            </Botao>
          </div>
        </Modal>
      </div>
    </>
  )
}
