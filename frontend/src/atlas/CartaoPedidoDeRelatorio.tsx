// O pedido de um relatório que o Atlas não monta (pós-evento, mídia): o texto fixo do servidor, o atalho para o relatório oficial
// quando a RBS já o disponibilizou e os modelos em branco. Pedir um modelo só preenche o campo: quem envia é a pessoa. [ATLAS]
import type { ParceriaResumo } from '../api/tipos'
import { Chip, ChipLink } from './Elementos'

type Props = {
  parceria: ParceriaResumo
  item: { texto: string; relatorioOficial: boolean; modelos: { tipo: string; titulo: string }[] }
  aoPedirModelo: (texto: string) => void
}

export function CartaoPedidoDeRelatorio({ parceria, item, aoPedirModelo }: Props) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-white/16 bg-white/8 p-4">
      <p className="text-[15px] leading-[1.5] text-white">{item.texto}</p>
      {(item.relatorioOficial || item.modelos.length > 0) && (
        <div className="flex flex-wrap gap-2.5">
          {item.relatorioOficial && (
            <ChipLink variante="primario" to={`/parceria/${parceria.id}/relatorio`} icone="ri-file-chart-line">
              Abrir o relatório oficial
            </ChipLink>
          )}
          {item.modelos.map((modelo) => (
            <Chip key={modelo.tipo} icone="ri-draft-line" onClick={() => aoPedirModelo(`Quero o modelo em branco: ${modelo.titulo}`)}>
              Modelo: {modelo.titulo}
            </Chip>
          ))}
        </div>
      )}
    </div>
  )
}
