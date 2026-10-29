// O cartão em que o Atlas pede os números que o portal não tem. É o mesmo formulário da aba Aplicações: os valores vão
// para os dados informados da parceria, com autor e data, e nunca ficam no texto da conversa. [ATLAS] [VALIDACAO]
// As três respostas são as da elicitação do MCP: enviar (accept), não tenho (decline) e responder depois (cancel).
import { useState } from 'react'
import { responderCartao, type Conversa, type ItemDaConversa, type RespostaDoCartao } from '../api/atlas'
import { ErroApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { FormularioDadosInformados } from '../componentes/FormularioDadosInformados'

type ItemCartao = Extract<ItemDaConversa, { tipo: 'cartao' }>

const DESFECHO: Record<Exclude<ItemCartao['status'], 'PENDENTE'>, string> = {
  RESPONDIDA: 'Números enviados',
  RECUSADA: 'Sem os números: o relatório mostra esses campos como indisponíveis',
  CANCELADA: 'Cartão fechado sem resposta',
}

export function CartaoSolicitacao({ item, aoResponder }: { item: ItemCartao; aoResponder: (turno: Conversa) => void }) {
  const [ocupado, setOcupado] = useState<'decline' | 'cancel' | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  if (item.status !== 'PENDENTE') {
    return (
      <div className="rounded-[14px] border border-borda px-4 py-3 text-xs">
        <p className="font-semibold">{item.mensagem}</p>
        <p className="mt-0.5 text-texto-2">{DESFECHO[item.status]}</p>
      </div>
    )
  }

  async function responderSemNumeros(acao: Exclude<RespostaDoCartao['acao'], 'accept'>) {
    setOcupado(acao)
    setFalha(null)
    try {
      aoResponder(await responderCartao(item.solicitacaoId, { acao }))
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível responder.')
      setOcupado(null)
    }
  }

  return (
    <div data-secao="aplicacoes" className="flex flex-col gap-3 rounded-2xl border-[1.5px] border-secao bg-white p-4">
      <p className="texto-sobretitulo text-secao-texto">Pergunta do Atlas</p>
      <p className="text-sm font-semibold">{item.mensagem}</p>
      <FormularioDadosInformados
        compacto
        campos={item.campos}
        textoEnviar="Enviar números"
        desabilitado={ocupado !== null}
        aoEnviar={async (valores) => aoResponder(await responderCartao(item.solicitacaoId, { acao: 'accept', valores }))}
        extra={
          <Botao variante="secundario" className="w-full min-w-0" carregando={ocupado === 'decline'} disabled={ocupado !== null} onClick={() => responderSemNumeros('decline')}>
            Não tenho estes números
          </Botao>
        }
      />
      <button
        type="button"
        disabled={ocupado !== null}
        onClick={() => responderSemNumeros('cancel')}
        className="self-center text-xs font-semibold text-texto-2 hover:text-tinta hover:underline disabled:opacity-60"
      >
        Responder depois
      </button>
      {falha && <Aviso tipo="falha" titulo={falha} />}
    </div>
  )
}
