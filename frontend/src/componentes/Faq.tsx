// A Ajuda da marca como FAQ (U7, plano da base da RBS): perguntas e respostas curtas sobre o portal, com busca, e no fim
// "Sua dúvida não está aqui? Fale com o seu atendimento". A RBS pediu isso na reunião de 05/11 no lugar de um chat.
// As respostas descrevem o que o portal faz hoje; quando uma regra mudar, a resposta muda junto.
import { useState } from 'react'
import { REGRA_ENVIO } from '../api/materiais'
import { Busca } from './Formulario'

type Pergunta = { pergunta: string; resposta: string }

export const PERGUNTAS_DA_MARCA: Pergunta[] = [
  {
    pergunta: 'Onde vejo o que a RBS já veiculou da minha campanha?',
    resposta:
      'Em Mídia e conteúdo: o previsto e o exibido por veículo e por formato, com filtro e o botão para baixar o CSV do que está na tela. As marcas Master veem também a comprovação mês a mês.',
  },
  {
    pergunta: 'Como envio um material?',
    resposta:
      'Na aba da entrega (Mídia e conteúdo, Aplicações e as outras), quando a RBS abrir a entrega ou pedir um ajuste. Cada envio vira uma versão nova; as anteriores continuam guardadas. "Enviado" quer dizer recebido, não aprovado.',
  },
  {
    pergunta: 'Por que não consigo enviar agora?',
    resposta: 'A entrega precisa estar aberta para envio ou com ajuste pedido. A RBS abre cada entrega quando é a hora de mandar o material.',
  },
  { pergunta: 'Que arquivos posso enviar?', resposta: `${REGRA_ENVIO}. Arquivos .svg e .ai vão dentro de um .zip.` },
  {
    pergunta: 'Quando sai o relatório?',
    resposta:
      'A RBS gera o relatório a partir dos dados do portal, valida os números e libera. Ele aparece em Relatório, com o pós-venda da edição no fim. Antes disso, os números de mídia já ficam em Mídia e conteúdo.',
  },
  {
    pergunta: 'Como destino as cortesias?',
    resposta: 'Em Cortesias, no botão Destinar cortesias: setor, dia e quantidade, dentro do disponível e até o prazo que a RBS definiu.',
  },
  {
    pergunta: 'Como cadastro as pessoas do credenciamento?',
    resposta: 'No Open Staff, pelo botão em Credenciamento, dentro do período de cadastro. O portal mostra as quantidades e o período; o cadastro é feito lá.',
  },
  {
    pergunta: 'Posso mudar uma pergunta da pesquisa?',
    resposta: 'Enquanto a RBS não aprova, sim, na aba Pesquisa (cotas Master). Depois de aprovada, peça ao seu atendimento: a RBS pode reabrir a pergunta.',
  },
  {
    pergunta: 'Por que uma aba aparece com cadeado?',
    resposta: 'Ou a RBS fechou a aba por enquanto, ou ela não faz parte da sua cota. O motivo aparece ao lado do cadeado e dentro da aba.',
  },
  {
    pergunta: 'Como incluo outra pessoa da minha equipe ou da agência?',
    resposta: 'Os acessos são os e-mails que a RBS autoriza (veja em Perfil). Para incluir alguém, peça ao seu atendimento.',
  },
  {
    pergunta: 'Esqueci a senha. O que faço?',
    resposta: 'Nesta versão a senha não se recupera sozinha. Fale com o seu atendimento, que libera um código de primeiro acesso.',
  },
]

function semAcento(texto: string) {
  return texto
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
}

/** O contato do atendimento da parceria; sem ele, o time B2B da RBS. */
export function FaleComAtendimento({ nome, email }: { nome?: string | null; email?: string | null }) {
  return (
    <p className="rounded-xl bg-cinza-100 p-3 text-sm font-medium">
      <span className="font-semibold">Sua dúvida não está aqui?</span> Fale com o seu atendimento
      {nome || email ? (
        <>
          : {nome && <span className="font-semibold">{nome}</span>}
          {email && (
            <>
              {nome ? ', ' : ''}
              <a href={`mailto:${email}`} className="font-semibold text-secao-texto underline">
                {email}
              </a>
            </>
          )}
          .
        </>
      ) : (
        ' ao patrocinador da RBS (time B2B).'
      )}
    </p>
  )
}

export function Faq({ atendimento }: { atendimento?: { nome?: string | null; email?: string | null } }) {
  const [busca, setBusca] = useState('')
  const termo = semAcento(busca.trim())
  const achadas = PERGUNTAS_DA_MARCA.filter((item) => !termo || semAcento(`${item.pergunta} ${item.resposta}`).includes(termo))
  return (
    <div className="flex flex-col gap-3">
      <Busca valor={busca} aoMudar={setBusca} placeholder="Buscar na ajuda…" />
      <div className="flex max-h-[min(48vh,420px)] flex-col gap-1 overflow-y-auto pr-1">
        {achadas.length === 0 ? (
          <p className="py-3 text-sm text-texto-2">Nenhuma pergunta com essa busca.</p>
        ) : (
          achadas.map((item) => (
            <details key={item.pergunta} className="group rounded-xl border border-borda px-4 py-3 open:bg-cinza-100/60">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-3 text-sm font-semibold">
                {item.pergunta}
                <i className="ri-arrow-down-s-line text-lg transition-transform duration-(--duracao-media) group-open:rotate-180" aria-hidden="true" />
              </summary>
              <p className="mt-2 animate-entrar text-sm text-texto-2">{item.resposta}</p>
            </details>
          ))
        )}
      </div>
      <FaleComAtendimento nome={atendimento?.nome} email={atendimento?.email} />
    </div>
  )
}
