// Aba que a marca não pode abrir: ou a cota não inclui, ou a RBS fechou por enquanto. [PERMISSAO] [ITEM-7]
// As duas situações têm causa e saída diferentes, então a mensagem precisa dizer qual é.
import type { AbaLiberada, CodigoAba, ParceriaResumo } from '../api/tipos'
import { Aviso } from './Aviso'
import { nomeCota } from './Menu'

export type EstadoAba = 'LIBERADA' | 'FORA_DA_COTA' | 'FECHADA' | 'OCULTA'

export function estadoDaAba(aba: AbaLiberada | undefined): EstadoAba {
  if (aba?.visivel === false) return 'OCULTA'
  if (!aba || aba.liberada) return 'LIBERADA'
  return aba.incluidaNaCota === false ? 'FORA_DA_COTA' : 'FECHADA'
}

export function estadoAbaNaParceria(parceria: ParceriaResumo, codigo: CodigoAba | null): EstadoAba {
  return codigo ? estadoDaAba(parceria.abas.find((aba) => aba.codigo === codigo)) : 'LIBERADA'
}

/** O que a marca (ou a RBS, olhando a parceria) lê no lugar do conteúdo da aba. */
export function AvisoAbaFechada({ parceria, estado, nome, rbs }: { parceria: ParceriaResumo; estado: EstadoAba; nome: string; rbs: boolean }) {
  if (estado === 'LIBERADA') return null
  if (rbs) {
    return (
      <Aviso tipo="restrito" titulo="Aba fechada para esta parceria">
        {estado === 'FORA_DA_COTA'
          ? `A cota ${nomeCota(parceria)} não inclui ${nome.toLowerCase()}.`
          : 'A marca não vê esta aba. Para liberar, use Perfil · Abas da parceria.'}
      </Aviso>
    )
  }
  return estado === 'FORA_DA_COTA' ? (
    <Aviso tipo="restrito" titulo="Esta aba não faz parte da sua cota">
      A cota da sua parceria ({nomeCota(parceria)}) não inclui {nome.toLowerCase()}. Para incluir, fale com o time B2B da RBS.
    </Aviso>
  ) : estado === 'OCULTA' ? (
    <Aviso tipo="restrito" titulo="Esta página não está disponível">
      Esta área não faz parte do que a RBS mostra para a sua parceria. Dúvidas: fale com o time B2B da RBS.
    </Aviso>
  ) : (
    <Aviso tipo="restrito" titulo="Esta aba está fechada no momento">
      A RBS fechou {nome.toLowerCase()} para a sua parceria por enquanto. As entregas e os arquivos continuam guardados e voltam quando a
      aba reabrir. Dúvidas: fale com o time B2B da RBS.
    </Aviso>
  )
}
