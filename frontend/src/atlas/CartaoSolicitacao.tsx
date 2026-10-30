// O cartão em que o Atlas pede os números que o portal não tem. É o mesmo formulário da aba Aplicações: os valores vão
// para os dados informados da parceria, com autor e data, e nunca ficam no texto da conversa. [ATLAS] [VALIDACAO]
// As três respostas são as da elicitação do MCP: enviar (accept), não tenho (decline) e responder depois (cancel).
// O Figma não desenha este cartão; ele segue o cartão de anexo do AT04 (vidro, raio 16, chips).
import { useState } from 'react'
import { responderCartao, type Conversa, type ItemDaConversa, type RespostaDoCartao } from '../api/atlas'
import { ErroApi } from '../api/cliente'
import { FormularioDadosInformados } from '../componentes/FormularioDadosInformados'
import { Chip } from './Elementos'

type ItemCartao = Extract<ItemDaConversa, { tipo: 'cartao' }>

const DESFECHO: Record<Exclude<ItemCartao['status'], 'PENDENTE'>, { texto: string; icone: string }> = {
  RESPONDIDA: { texto: 'Números enviados para a aba Aplicações, com o seu nome e a data.', icone: 'ri-checkbox-circle-line' },
  RECUSADA: { texto: 'Sem estes números: no documento eles aparecem como indisponíveis.', icone: 'ri-indeterminate-circle-line' },
  CANCELADA: { texto: 'Cartão fechado sem resposta.', icone: 'ri-close-circle-line' },
}

export function CartaoSolicitacao({ item, aoResponder }: { item: ItemCartao; aoResponder: (turno: Conversa) => void }) {
  const [ocupado, setOcupado] = useState<'decline' | 'cancel' | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  if (item.status !== 'PENDENTE') {
    const desfecho = DESFECHO[item.status]
    return (
      <div className="flex gap-3 rounded-[14px] border border-white/12 bg-white/6 px-4 py-3 text-sm">
        <i className={`${desfecho.icone} text-lg text-white/70`} aria-hidden="true" />
        <div className="min-w-0">
          <p className="font-semibold text-white">{item.mensagem}</p>
          <p className="mt-0.5 text-white/64">{desfecho.texto}</p>
        </div>
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
    <div className="@container flex flex-col gap-3 rounded-2xl border border-white/16 bg-white/8 p-4">
      <div className="flex items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-[10px] bg-white/10">
          <i className="ri-edit-box-line text-xl text-white" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="text-[11px] font-bold tracking-[0.12em] text-atlas-lilas uppercase">Pergunta do Atlas</p>
          <p className="mt-0.5 text-base font-semibold text-white">{item.mensagem}</p>
          <p className="mt-0.5 text-xs text-white/62">Os números vão para a aba Aplicações da marca, com o seu nome e a data.</p>
        </div>
      </div>
      <FormularioDadosInformados
        escuro
        campos={item.campos}
        textoEnviar="Enviar números"
        desabilitado={ocupado !== null}
        aoEnviar={async (valores) => aoResponder(await responderCartao(item.solicitacaoId, { acao: 'accept', valores }))}
        extra={
          <>
            <Chip carregando={ocupado === 'decline'} disabled={ocupado !== null} onClick={() => responderSemNumeros('decline')}>
              Não tenho estes números
            </Chip>
            <button
              type="button"
              disabled={ocupado !== null}
              onClick={() => responderSemNumeros('cancel')}
              className="px-2 text-xs font-semibold text-white/64 hover:text-white hover:underline disabled:opacity-50"
            >
              Responder depois
            </button>
          </>
        }
      />
      {falha && (
        <p className="text-sm font-semibold text-[#ff8aa1]" role="alert">
          {falha}
        </p>
      )}
    </div>
  )
}
