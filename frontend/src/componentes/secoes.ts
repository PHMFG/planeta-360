// As oito seções do menu, na ordem e nas cores do Figma, e os atalhos de gestão da RBS. [ITEM-7]
import type { CodigoAba } from '../api/tipos'

export type ChaveSecao =
  | 'parceria'
  | 'perfil'
  | 'aplicacoes'
  | 'midia'
  | 'cortesias'
  | 'credenciamento'
  | 'pesquisa'
  | 'contrato'

export type Secao = {
  chave: ChaveSecao
  nome: string
  /** Trecho da rota depois de /parceria/:id; vazio é a própria Minha parceria. */
  caminho: string
  icone: string
  /** Aba da modelagem que libera a seção; Minha parceria não depende de aba. */
  aba: CodigoAba | null
  subtitulo: string
  /** As quatro abas fora da prioridade da Entrega 2 mostram só as entregas da aba. */
  desejavel: boolean
}

export const SECOES: Secao[] = [
  {
    chave: 'parceria',
    nome: 'Minha parceria',
    caminho: '',
    icone: 'ri-shake-hands-line',
    aba: null,
    subtitulo: 'Próximas ações e entregas da sua empresa.',
    desejavel: false,
  },
  {
    chave: 'perfil',
    nome: 'Perfil',
    caminho: 'perfil',
    icone: 'ri-building-line',
    aba: 'PERFIL',
    subtitulo: 'Dados da empresa, cota, acessos autorizados e plano comercial.',
    desejavel: false,
  },
  {
    chave: 'aplicacoes',
    nome: 'Aplicações da marca',
    caminho: 'aplicacoes',
    icone: 'ri-palette-line',
    aba: 'APLICACOES',
    subtitulo: 'Logo, manual de identidade visual e evidências pós-evento. Funcionalidade desejável.',
    desejavel: true,
  },
  {
    chave: 'midia',
    nome: 'Mídia e conteúdo',
    caminho: 'midia',
    icone: 'ri-film-line',
    aba: 'MIDIA',
    subtitulo: 'Envie os materiais da campanha e consulte as publicações disponibilizadas pela RBS.',
    desejavel: false,
  },
  {
    chave: 'cortesias',
    nome: 'Cortesias',
    caminho: 'cortesias',
    icone: 'ri-ticket-2-line',
    aba: 'CORTESIAS',
    subtitulo: 'Regras e destinação das cortesias da sua cota. Funcionalidade desejável.',
    desejavel: true,
  },
  {
    chave: 'credenciamento',
    nome: 'Credenciamento',
    caminho: 'credenciamento',
    icone: 'ri-profile-line',
    aba: 'CREDENCIAMENTO',
    subtitulo: 'Credenciais da equipe da marca no festival. Funcionalidade desejável.',
    desejavel: true,
  },
  {
    chave: 'pesquisa',
    nome: 'Pesquisa',
    caminho: 'pesquisa',
    icone: 'ri-clipboard-line',
    aba: 'PESQUISA',
    subtitulo: 'As três perguntas da sua marca na pesquisa do festival e os resultados.',
    desejavel: false,
  },
  {
    chave: 'contrato',
    nome: 'Contrato',
    caminho: 'contrato',
    icone: 'ri-file-shield-2-line',
    aba: 'CONTRATO',
    subtitulo: 'Contrato assinado e signatários da parceria. Funcionalidade desejável.',
    desejavel: true,
  },
]

export type AtalhoRbs = { nome: string; caminho: string; icone: string }

// Telas da RBS que não estão no Figma; seguem o protótipo de 19/10 no visual do Figma.
export const GESTAO_RBS: AtalhoRbs[] = [
  { nome: 'Painel', caminho: '/rbs', icone: 'ri-dashboard-3-line' },
  { nome: 'Patrocinadores', caminho: '/rbs/patrocinadores', icone: 'ri-building-2-line' },
  { nome: 'Fila de materiais', caminho: '/rbs/fila', icone: 'ri-inbox-archive-line' },
  { nome: 'Cargas', caminho: '/rbs/cargas', icone: 'ri-upload-cloud-2-line' },
  { nome: 'Relatórios', caminho: '/rbs/relatorios', icone: 'ri-file-chart-line' },
  { nome: 'Histórico', caminho: '/rbs/historico', icone: 'ri-history-line' },
]

export function secaoPorCaminho(caminho: string): Secao {
  return SECOES.find((secao) => secao.caminho === caminho) ?? SECOES[0]
}
