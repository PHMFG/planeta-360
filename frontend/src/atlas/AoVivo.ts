// A resposta do Atlas enquanto chega (rotas 44 e 46 em fluxo): o tempo pensando, os resumos do raciocínio, o texto e o que
// ele está fazendo. O raciocínio cru não sai do servidor; chega só o resumo de cada ida ao modelo, quando fica pronto. [ATLAS]
// É só a vista do momento: no fim do turno a tela relê a conversa gravada e esta versão some.
import type { EventoDoTurno } from '../api/atlas'

export type ParteAoVivo =
  /** {@code ate} nulo: o modelo ainda está pensando. */
  | { tipo: 'raciocinio'; resumos: string[]; desde: number; ate: number | null }
  | { tipo: 'texto'; texto: string }

export type RespostaAoVivo = {
  partes: ParteAoVivo[]
  /** O que o Atlas está fazendo agora ("Consultando os números da parceria…"), até chegar pensamento ou texto novo. */
  etapa: string | null
}

export const SEM_RESPOSTA: RespostaAoVivo = { partes: [], etapa: null }

export function aplicar(estado: RespostaAoVivo, evento: EventoDoTurno, agora = Date.now()): RespostaAoVivo {
  const partes = [...estado.partes]
  const ultima = partes.at(-1)
  switch (evento.tipo) {
    case 'pensando':
      // Pensamentos seguidos (separados só por ferramentas) ficam numa caixa só, como na conversa gravada.
      if (ultima?.tipo === 'raciocinio') partes[partes.length - 1] = { ...ultima, ate: null }
      else partes.push({ tipo: 'raciocinio', resumos: [], desde: agora, ate: null })
      return { partes, etapa: null }
    case 'resumo': {
      // O resumo chega quando fica pronto, às vezes depois do texto da mesma ida: vai para a última caixa de pensamento.
      const indice = partes.findLastIndex((parte) => parte.tipo === 'raciocinio')
      const caixa = partes[indice]
      if (!evento.texto || caixa?.tipo !== 'raciocinio') return estado
      partes[indice] = { ...caixa, resumos: [...caixa.resumos, evento.texto] }
      return { ...estado, partes }
    }
    case 'texto':
      if (!evento.delta) return estado
      if (ultima?.tipo === 'texto') partes[partes.length - 1] = { ...ultima, texto: ultima.texto + evento.delta }
      else {
        if (ultima?.tipo === 'raciocinio') partes[partes.length - 1] = { ...ultima, ate: agora }
        partes.push({ tipo: 'texto', texto: evento.delta })
      }
      return { partes, etapa: null }
    case 'etapa':
      return { partes, etapa: evento.texto }
    case 'texto_recusado': {
      const indice = partes.findLastIndex((parte) => parte.tipo === 'texto')
      if (indice >= 0) partes.splice(indice, 1)
      return { partes, etapa: 'Reescrevendo a resposta…' }
    }
  }
}
