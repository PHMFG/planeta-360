// A resposta do Atlas enquanto chega (rotas 44 e 46 em fluxo): a linha de pensamento, o texto e o que ele está fazendo. [ATLAS]
// É só a vista do momento: no fim do turno a tela relê a conversa gravada e esta versão some.
import type { EventoDoTurno } from '../api/atlas'

export type ParteAoVivo =
  /** {@code ate} nulo: o modelo ainda está pensando. */
  | { tipo: 'raciocinio'; texto: string; desde: number; ate: number | null }
  | { tipo: 'texto'; texto: string }

export type RespostaAoVivo = {
  partes: ParteAoVivo[]
  /** O que o Atlas está fazendo agora ("Consultando os números da parceria…"), até chegar pensamento ou texto novo. */
  etapa: string | null
  /** Uma nova ida ao modelo começou (depois de uma ferramenta ou de um texto recusado): o pensamento ganha outro parágrafo. */
  novaIda: boolean
}

export const SEM_RESPOSTA: RespostaAoVivo = { partes: [], etapa: null, novaIda: false }

export function aplicar(estado: RespostaAoVivo, evento: EventoDoTurno, agora = Date.now()): RespostaAoVivo {
  const partes = [...estado.partes]
  const ultima = partes.at(-1)
  switch (evento.tipo) {
    case 'raciocinio':
      if (!evento.delta) return estado
      // Pensamentos seguidos (separados só por ferramentas) ficam numa caixa só, como na conversa gravada.
      if (ultima?.tipo === 'raciocinio') partes[partes.length - 1] = { ...ultima, texto: ultima.texto + (estado.novaIda ? '\n\n' : '') + evento.delta, ate: null }
      else partes.push({ tipo: 'raciocinio', texto: evento.delta, desde: agora, ate: null })
      return { partes, etapa: null, novaIda: false }
    case 'texto':
      if (!evento.delta) return estado
      if (ultima?.tipo === 'texto') partes[partes.length - 1] = { ...ultima, texto: ultima.texto + evento.delta }
      else {
        if (ultima?.tipo === 'raciocinio') partes[partes.length - 1] = { ...ultima, ate: agora }
        partes.push({ tipo: 'texto', texto: evento.delta })
      }
      return { partes, etapa: null, novaIda: false }
    case 'etapa':
      return { partes, etapa: evento.texto, novaIda: true }
    case 'texto_recusado': {
      const indice = partes.findLastIndex((parte) => parte.tipo === 'texto')
      if (indice >= 0) partes.splice(indice, 1)
      return { partes, etapa: 'Reescrevendo a resposta…', novaIda: true }
    }
  }
}
