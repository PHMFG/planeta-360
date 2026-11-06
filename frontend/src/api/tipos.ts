// Formatos de dados trocados com a API, no mesmo desenho do docs/api.md. [ITEM-3]

export type Perfil = 'ADMIN_RBS' | 'ADMIN_PATROCINADOR'

export type CodigoAba =
  | 'PERFIL'
  | 'APLICACOES'
  | 'MIDIA'
  | 'CORTESIAS'
  | 'CREDENCIAMENTO'
  | 'PESQUISA'
  | 'CONTRATO'

export type AbaLiberada = {
  codigo: CodigoAba
  nome: string
  ordem: number
  liberada: boolean
  /** Falso quando a cota não inclui a aba (aí ela não pode ser liberada); verdadeiro quando a RBS só fechou por enquanto. */
  incluidaNaCota?: boolean
  /** Falso quando a RBS ocultou a aba: ela some do menu da marca (R6.a). Com ela oculta, `liberada` vem falso. */
  visivel?: boolean
}

export type ParceriaResumo = {
  id: number
  patrocinador: string
  cota: { codigo: 'MASTER' | 'SUBCOTA_G' | 'SUBCOTA'; nome: string }
  evento: { id: number; nome: string; edicao: number }
  abas: AbaLiberada[]
  /** Como quem está logado entra nesta parceria: pela marca ou pela agência dela (U10). */
  vinculo?: 'MARCA' | 'AGENCIA'
  /** O atendimento da RBS desta parceria (U7, V19): aparece no fim da Ajuda. Nulo até a RBS preencher no Perfil. */
  atendimentoNome?: string | null
  atendimentoEmail?: string | null
}

export type UsuarioLogado = {
  id: number
  nome: string
  email: string
  perfil: Perfil
  /** Vazio para Admin RBS, que enxerga todas as parcerias. */
  parcerias: ParceriaResumo[]
}
