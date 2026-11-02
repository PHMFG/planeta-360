// Desenha o relatório do Atlas a partir da especificação já resolvida pelo servidor, como a página do Figma (AT05, 202:1863):
// cabeçalho de documento de trabalho, dados da parceria, seções numeradas e as referências no fim. [ATLAS] [RELATORIO]
// O mesmo documento aparece na pré-visualização ao lado do chat e na página inteira, e sai igual na impressão em A4.
import type { Bloco, RelatorioAtlas, StatusRelatorioAtlas } from '../../api/atlas'
import { dataHora } from '../../util/formatar'
import { BlocoDoRelatorio, type Referencias } from './Blocos'
import './relatorio.css'

const SITUACAO: Record<StatusRelatorioAtlas, string> = {
  MONTANDO: 'Em montagem: os números são os de agora',
  AGUARDANDO_RESPOSTA: 'Em montagem: esperando os números da marca',
  PRONTO: 'Documento de trabalho da marca, para revisão',
  DESCARTADO: 'Descartado',
}

type Capa = Extract<Bloco, { tipo: 'capa' }>

export function RenderizadorDeRelatorio({ relatorio, compacto = false }: { relatorio: RelatorioAtlas; compacto?: boolean }) {
  const blocos = relatorio.secoes.flatMap((secao) => secao.blocos)
  const capa = blocos.find((bloco): bloco is Capa => bloco.tipo === 'capa')
  const fontes = blocos.find((bloco) => bloco.tipo === 'fontes')
  const referencias: Referencias = new Map(fontes?.tipo === 'fontes' ? fontes.itens.map((item, indice) => [item.ref, indice + 1]) : [])
  const secoes = relatorio.secoes.filter((secao) => secao.titulo && secao.blocos.some((bloco) => bloco.tipo !== 'capa' && bloco.tipo !== 'fontes'))

  return (
    <article
      className={`@container mx-auto flex w-full max-w-[680px] flex-col gap-3 rounded-md bg-white font-documento text-[#111] shadow-documento print:max-w-none print:rounded-none print:p-0 print:shadow-none ${
        compacto ? 'px-6 pt-7 pb-6 sm:px-10 sm:pt-9 sm:pb-8' : 'px-6 pt-8 pb-7 sm:px-12 sm:pt-11 sm:pb-9'
      }`}
    >
      <div className="rel-bloco flex flex-wrap items-baseline justify-between gap-2 text-[9px] text-[#6b6b6b]">
        <span className="font-medium tracking-[0.12em] uppercase">Documento de trabalho · não validado pela RBS</span>
        <span>Gerado pelo Atlas · Planeta 360</span>
      </div>
      <h2 className="rel-bloco text-[22px] font-semibold tracking-[-0.01em]">{capa?.titulo ?? relatorio.titulo}</h2>
      <dl className="rel-bloco grid grid-cols-[90px_1fr] gap-x-3 gap-y-1 text-[11px]">
        {[
          ['Empresa', capa?.subtitulo ?? relatorio.patrocinador],
          ['Edição', capa?.edicao ?? '—'],
          ['Pedido por', relatorio.criadoPor?.nome ?? '—'],
          [relatorio.finalizadoEm ? 'Finalizado em' : 'Iniciado em', dataHora(relatorio.finalizadoEm ?? relatorio.criadoEm)],
          ['Situação', SITUACAO[relatorio.status]],
        ].map(([rotulo, valor]) => (
          <div key={rotulo} className="contents">
            <dt className="font-medium text-[#444]">{rotulo}</dt>
            <dd className="text-[#222]">{valor}</dd>
          </div>
        ))}
      </dl>
      <hr className="my-1 border-[#d9d9d9]" />

      {secoes.map((secao, indice) => (
        <section key={secao.id} className="rel-secao flex flex-col gap-2.5 pt-1">
          <h3 className="rel-bloco text-[13px] font-semibold">
            {indice + 1}. {secao.titulo}
          </h3>
          {secao.blocos.map((bloco) => (
            <BlocoDoRelatorio key={bloco.id} bloco={bloco} referencias={referencias} />
          ))}
        </section>
      ))}

      {fontes && (
        <section className="rel-secao flex flex-col gap-2 pt-2">
          <h3 className="rel-bloco text-[11px] font-semibold">Referências</h3>
          <BlocoDoRelatorio bloco={fontes} referencias={referencias} />
        </section>
      )}
      <p className="rel-bloco pt-2 text-[9px] text-[#8a8a8a]">{relatorio.selo} A revisão e a validação seguem o fluxo da RBS.</p>
    </article>
  )
}
