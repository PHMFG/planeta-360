// Linha do tempo do histórico: quem fez o quê e quando, da ação mais nova para a mais antiga. [ITEM-4] [HISTORICO]
import { NOMES_ACAO, type ItemHistorico } from '../api/materiais'
import { SECOES } from './secoes'
import { nomeStatus } from './Status'
import { dataHora } from '../util/formatar'

const ICONES: Record<string, string> = {
  ENVIO_ARQUIVO: 'ri-upload-cloud-2-line',
  NOVA_VERSAO: 'ri-upload-cloud-2-line',
  ALTERACAO_STATUS: 'ri-checkbox-circle-line',
  DOWNLOAD: 'ri-download-line',
  LOGIN: 'ri-login-box-line',
  LOGOUT: 'ri-logout-box-r-line',
  LOGIN_FALHOU: 'ri-error-warning-line',
  AUTORIZACAO: 'ri-shield-check-line',
  REVOGACAO: 'ri-forbid-line',
  ATIVACAO: 'ri-key-2-line',
  CARGA: 'ri-database-2-line',
  GERACAO_RELATORIO: 'ri-file-chart-line',
  VALIDACAO: 'ri-file-chart-line',
  DISPONIBILIZACAO: 'ri-file-chart-line',
}

// Status das perguntas da pesquisa; "RASCUNHO" de uma pergunta é a volta para a marca ajustar, não um relatório em rascunho.
const STATUS_PERGUNTA: Record<string, string> = { RASCUNHO: 'Devolvida para ajuste', ENVIADA: 'Enviada', APROVADA: 'Aprovada' }
// Situação da parceria, que a API grava em código ("ATIVA → CANCELADA").
const SITUACOES: Record<string, string> = { EM_NEGOCIACAO: 'Em negociação', ATIVA: 'Ativa', ENCERRADA: 'Encerrada', CANCELADA: 'Cancelada' }
// Vínculo do e-mail autorizado, que a API grava em minúscula ("(agencia)").
const VINCULOS: [RegExp, string][] = [
  [/\(agencia\)$/, '(Agência)'],
  [/\(marca\)$/, '(Marca)'],
]

/** "(aba MIDIA)" no texto gravado pela API vira "(aba Mídia e conteúdo)". */
function semCodigoDeAba(texto: string) {
  return texto.replace(/\(aba ([A-Z_]+)\)/g, (trecho, codigo: string) => {
    const secao = SECOES.find((item) => item.aba === codigo)
    return secao ? `(aba ${secao.nome})` : trecho
  })
}

function mudanca(item: ItemHistorico) {
  if (!item.valorAnterior && !item.valorNovo) return null
  const ler = (valor: string | null) => {
    if (!valor) return '—'
    if (item.entidade === 'pesquisa_pergunta' && STATUS_PERGUNTA[valor]) return STATUS_PERGUNTA[valor]
    if (SITUACOES[valor]) return SITUACOES[valor]
    return semCodigoDeAba(VINCULOS.reduce((texto, [padrao, nome]) => texto.replace(padrao, nome), nomeStatus(valor)))
  }
  // Sem valor anterior (envio, download, criação), a linha diz só o que entrou, sem "— →".
  if (!item.valorAnterior) return ler(item.valorNovo)
  return `${ler(item.valorAnterior)} → ${ler(item.valorNovo)}`
}

export function LinhaDoTempo({ itens, comParceria = false }: { itens: ItemHistorico[]; comParceria?: boolean }) {
  return (
    <ol className="flex flex-col">
      {itens.map((item) => (
        <li key={item.id} className="flex gap-4 border-b border-borda py-3 last:border-b-0">
          <span className="grid size-9 shrink-0 place-items-center rounded-full bg-secao-tinta text-secao-texto">
            <i className={`${ICONES[item.acao] ?? 'ri-time-line'} text-lg`} aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1 text-sm">
            <p className="[overflow-wrap:anywhere]">
              <span className="font-bold">{NOMES_ACAO[item.acao] ?? item.acao}</span>
              {comParceria && item.parceria && <span className="font-semibold text-texto-2"> · {item.parceria.patrocinador}</span>}
              {mudanca(item) && <span className="font-semibold"> · {mudanca(item)}</span>}
            </p>
            {item.detalhe && <p className="mt-0.5 [overflow-wrap:anywhere]">{semCodigoDeAba(item.detalhe)}</p>}
            <p className="mt-0.5 text-xs font-medium text-texto-2">
              {item.usuario ? `${item.usuario.nome}${item.usuario.perfil === 'ADMIN_RBS' ? ' (RBS)' : ''}` : 'Sistema ou e-mail desconhecido'} ·{' '}
              {dataHora(item.ocorridoEm)}
              {item.ip && ` · IP ${item.ip}`}
            </p>
          </div>
        </li>
      ))}
    </ol>
  )
}

/** Controles de página das listas longas (50 itens por vez). */
export function Paginacao({ pagina, tamanho, total, aoMudar }: { pagina: number; tamanho: number; total: number; aoMudar: (pagina: number) => void }) {
  const paginas = Math.max(1, Math.ceil(total / tamanho))
  if (paginas <= 1) return null
  return (
    <div className="mt-4 flex items-center justify-between text-xs font-semibold">
      <button type="button" disabled={pagina === 0} onClick={() => aoMudar(pagina - 1)} className="hover:underline disabled:opacity-40">
        ← Anteriores
      </button>
      <span className="text-texto-2">
        Página {pagina + 1} de {paginas} · {total} registros
      </span>
      <button type="button" disabled={pagina + 1 >= paginas} onClick={() => aoMudar(pagina + 1)} className="hover:underline disabled:opacity-40">
        Seguintes →
      </button>
    </div>
  )
}
