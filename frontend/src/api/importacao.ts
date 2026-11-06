// O Atlas de entrada (plano da base da RBS, E1 a E17): a RBS arrasta um arquivo, o Atlas lê e propõe linhas, a pessoa revisa
// e confirma. Rotas 109 a 117 (docs/atlas/extracao/contrato-onda0.md, que vai para o api.md junto com o back).
import { consulta, pedir, type Pagina } from './cliente'

export type DestinoDaImportacao = 'VEICULACAO' | 'COMPROVANTE' | 'PESQUISA' | 'NUMEROS_EDICAO' | 'DADOS_ATIVACAO' | 'PLANO' | 'SO_CONSULTA'
export type SituacaoDaImportacao = 'LENDO' | 'EM_PREVIA' | 'CONFIRMADA' | 'DESCARTADA' | 'RETIRADA' | 'ERRO'
export type SituacaoDaLinha = 'PROPOSTA' | 'EDITADA' | 'ACEITA' | 'DESCARTADA' | 'RECUSADA'
export type AlcanceDaImportacao = 'EDICAO' | 'COTA' | 'PARCERIA'
export type EtapaDaLeitura = 'LENDO_ARQUIVO' | 'ENTENDENDO' | 'CONFERINDO'

export const NOME_DO_DESTINO: Record<DestinoDaImportacao, string> = {
  VEICULACAO: 'Veiculação de mídia',
  COMPROVANTE: 'Comprovante de veiculação',
  PESQUISA: 'Pesquisa',
  NUMEROS_EDICAO: 'Números da edição',
  DADOS_ATIVACAO: 'Dados da ativação',
  PLANO: 'Plano comercial',
  SO_CONSULTA: 'Só para consulta',
}

export const NOME_DA_SITUACAO: Record<SituacaoDaImportacao, string> = {
  LENDO: 'Lendo',
  EM_PREVIA: 'Para revisar',
  CONFIRMADA: 'Confirmada',
  DESCARTADA: 'Descartada',
  RETIRADA: 'Retirada',
  ERRO: 'Não deu para ler',
}

/** Os campos de cada destino, na ordem em que a prévia mostra, com o rótulo da coluna. */
export const CAMPOS_DO_DESTINO: Partial<Record<DestinoDaImportacao, { chave: string; rotulo: string; numero?: boolean }[]>> = {
  VEICULACAO: [
    { chave: 'patrocinador', rotulo: 'Marca' },
    { chave: 'veiculo', rotulo: 'Veículo' },
    { chave: 'formato', rotulo: 'Formato' },
    { chave: 'espacoPrograma', rotulo: 'Espaço ou programa' },
    { chave: 'mes', rotulo: 'Mês' },
    { chave: 'exibidoEm', rotulo: 'Exibido em' },
    { chave: 'quantidade', rotulo: 'Quantidade', numero: true },
    { chave: 'impacto', rotulo: 'Impacto', numero: true },
  ],
  NUMEROS_EDICAO: [
    { chave: 'indicador', rotulo: 'Indicador' },
    { chave: 'valor', rotulo: 'Valor', numero: true },
    { chave: 'qualificador', rotulo: 'Qualificador' },
    { chave: 'unidade', rotulo: 'Unidade' },
    { chave: 'fonte', rotulo: 'Fonte' },
  ],
  DADOS_ATIVACAO: [
    { chave: 'campoChave', rotulo: 'Campo' },
    { chave: 'valor', rotulo: 'Valor', numero: true },
  ],
  PESQUISA: [
    { chave: 'tipo', rotulo: 'Tipo' },
    { chave: 'patrocinador', rotulo: 'Marca' },
    { chave: 'dimensao', rotulo: 'Dimensão ou pergunta' },
    { chave: 'categoria', rotulo: 'Categoria ou alternativa' },
    { chave: 'valor', rotulo: 'Valor', numero: true },
    { chave: 'baseRespondentes', rotulo: 'Base', numero: true },
  ],
  COMPROVANTE: [
    { chave: 'patrocinador', rotulo: 'Marca' },
    { chave: 'veiculo', rotulo: 'Veículo' },
    { chave: 'formato', rotulo: 'Formato' },
    { chave: 'data', rotulo: 'Data' },
  ],
}

export type ResumoDaImportacao = {
  id: number
  situacao: SituacaoDaImportacao
  origem: 'RBS' | 'MARCA'
  arquivo: { id: number; nome: string; tipo: string; tamanho: number }
  evento: { id: number; nome: string; edicao: number }
  alcance: AlcanceDaImportacao
  cota: { codigo: string; nome: string } | null
  parceria: { id: number; patrocinador: string } | null
  destino: DestinoDaImportacao | null
  destinoSugerido: DestinoDaImportacao | null
  confianca: number | null
  linhas: { total: number; propostas: number; editadas: number; aceitas: number; descartadas: number; recusadas: number }
  criadaPor: { id: number; nome: string }
  criadaEm: string
  confirmadaEm: string | null
  retiradaEm: string | null
  erro: string | null
}

export type OrigemDoCampo = { localizador: string; trecho: string }

export type LinhaDaImportacao = {
  id: number
  ordem: number
  destino: DestinoDaImportacao
  situacao: SituacaoDaLinha
  motivo: string | null
  dados: Record<string, string | number | null>
  origem: Record<string, OrigemDoCampo>
  parceria: { id: number; patrocinador: string } | null
  aproveitamentoSugerido: { id: number; veiculo: string; formato: string; espacoPrograma: string | null; semelhanca: number } | null
  aproveitamento: { id: number; veiculo: string; formato: string; espacoPrograma: string | null } | null
  editadaPor: { id: number; nome: string } | null
  editadaEm: string | null
}

/** A rota 111: o resumo (com `linhas`, a contagem) e `itens`, as linhas propostas. */
export type ImportacaoCompleta = ResumoDaImportacao & {
  documentoId: number | null
  etapa: EtapaDaLeitura | null
  itens: LinhaDaImportacao[]
  naoEntendidos: { localizador: string; trecho: string; motivo: string }[]
  resultado: { lidas: number; inseridas: number; duplicadas: number; rejeitadas: number; rejeicoes: { ordem: number; motivo: string }[] } | null
}

export function contarLinhas(itens: LinhaDaImportacao[]): ResumoDaImportacao['linhas'] {
  const conta = (situacao: SituacaoDaLinha) => itens.filter((item) => item.situacao === situacao).length
  return {
    total: itens.length,
    propostas: conta('PROPOSTA'),
    editadas: conta('EDITADA'),
    aceitas: conta('ACEITA'),
    descartadas: conta('DESCARTADA'),
    recusadas: conta('RECUSADA'),
  }
}

export type OrigemParaVer = {
  arquivoId: number
  tipo: string
  localizador: string
  trecho: string
  planilha: { aba: string; celula: string; linhaInicial: number; colunaInicial: string; grade: (string | number | null)[][] } | null
  texto: { antes: string; trecho: string; depois: string } | null
  pagina: number | null
}

export type NovaImportacao = {
  arquivo?: File
  texto?: string
  eventoId: number
  alcance: AlcanceDaImportacao
  cota?: string
  parceriaId?: number
  destino?: DestinoDaImportacao
}

export function importar(dados: NovaImportacao) {
  const formulario = new FormData()
  if (dados.arquivo) formulario.append('arquivo', dados.arquivo)
  if (dados.texto) formulario.append('texto', dados.texto)
  formulario.append('eventoId', String(dados.eventoId))
  formulario.append('alcance', dados.alcance)
  if (dados.cota) formulario.append('cota', dados.cota)
  if (dados.parceriaId) formulario.append('parceriaId', String(dados.parceriaId))
  if (dados.destino) formulario.append('destino', dados.destino)
  return pedir<ResumoDaImportacao>('/importacoes', { metodo: 'POST', formulario })
}

export const listarImportacoes = (filtros: { eventoId?: number; situacao?: string; pagina?: number; tamanho?: number } = {}) =>
  pedir<Pagina<ResumoDaImportacao>>(`/importacoes${consulta(filtros)}`)

export const obterImportacao = (id: number) => pedir<ImportacaoCompleta>(`/importacoes/${id}`)

export const mudarLinha = (id: number, linhaId: number, mudanca: { situacao?: 'ACEITA' | 'DESCARTADA'; dados?: Record<string, string | number | null>; aproveitamentoId?: number }) =>
  pedir<LinhaDaImportacao>(`/importacoes/${id}/linhas/${linhaId}`, { metodo: 'PATCH', corpo: mudanca })

export const aceitarTodas = (id: number) => pedir<{ alteradas: number }>(`/importacoes/${id}/linhas`, { metodo: 'PATCH', corpo: { situacao: 'ACEITA', todas: true } })

export const confirmarImportacao = (id: number) => pedir<ImportacaoCompleta>(`/importacoes/${id}/confirmar`, { metodo: 'POST' })

export const descartarImportacao = (id: number) => pedir<ResumoDaImportacao>(`/importacoes/${id}/descartar`, { metodo: 'POST' })

export const retirarImportacao = (id: number) => pedir<ResumoDaImportacao>(`/importacoes/${id}/retirar`, { metodo: 'POST' })

export const obterOrigem = (id: number, linhaId: number, campo: string) =>
  pedir<OrigemParaVer>(`/importacoes/${id}/origem/${linhaId}${consulta({ campo })}`)

/** A marca importa a planilha da agência (rota 117): destino fixo, dados da ativação. */
export function importarPlanilhaDaAgencia(parceriaId: number, arquivo: File) {
  const formulario = new FormData()
  formulario.append('arquivo', arquivo)
  return pedir<ResumoDaImportacao>(`/parcerias/${parceriaId}/importacoes`, { metodo: 'POST', formulario })
}
