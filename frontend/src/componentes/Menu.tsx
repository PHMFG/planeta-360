// Menu lateral flutuante do Figma (P360/Navegação): marca, conta, as oito seções e o rodapé. [ITEM-7]
// A RBS vê também os atalhos de gestão e escolhe a parceria que está consultando.
import { useEffect, useEffectEvent, useState } from 'react'
import { Link, NavLink, useMatch, useNavigate } from 'react-router'
import type { ParceriaResumo, UsuarioLogado } from '../api/tipos'
import { useSessao } from '../sessao/Sessao'
import { estadoAbaNaParceria } from './AbaFechada'
import { GESTAO_RBS, SECOES, type Secao } from './secoes'

const NOME_COTA: Record<ParceriaResumo['cota']['codigo'], string> = {
  MASTER: 'Master',
  SUBCOTA_G: 'Subcota G',
  SUBCOTA: 'Subcota',
}

export function nomeCota(parceria: ParceriaResumo) {
  return NOME_COTA[parceria.cota.codigo] ?? parceria.cota.nome
}

function iniciais(nome: string) {
  const palavras = nome.replace(/^Cliente\s+/i, '').split(/\s+/).filter(Boolean)
  if (palavras.length === 1) return palavras[0].slice(0, 2).toUpperCase()
  return palavras
    .slice(0, 2)
    .map((palavra) => palavra[0]?.toUpperCase())
    .join('')
}

const ITEM =
  'flex h-11 items-center gap-3 rounded-[10px] px-3 text-sm transition-colors'

function classeItem(ativo: boolean) {
  return ativo
    ? `${ITEM} bg-secao font-bold text-white`
    : `${ITEM} font-semibold text-tinta hover:bg-cinza-100`
}

function ItemSecao({ secao, parceria, rbs }: { secao: Secao; parceria: ParceriaResumo; rbs: boolean }) {
  const estado = estadoAbaNaParceria(parceria, secao.aba)
  // A RBS olha a parceria de fora: "sua cota" é texto para a marca.
  const motivo =
    estado === 'FORA_DA_COTA'
      ? rbs
        ? 'Não incluída na cota desta parceria'
        : 'Não incluída na sua cota'
      : rbs
        ? 'Fechada para esta parceria'
        : 'Fechada pela RBS por enquanto'
  const destino = `/parceria/${parceria.id}${secao.caminho ? `/${secao.caminho}` : ''}`

  return (
    <li data-secao={secao.chave}>
      <NavLink to={destino} end className={({ isActive }) => classeItem(isActive)}>
        {({ isActive }) => (
          <>
            <i className={`${secao.icone} text-xl ${isActive ? 'text-white' : 'text-secao'}`} aria-hidden="true" />
            <span className="flex-1">{secao.nome}</span>
            {/* [PERMISSAO] A aba aparece para todos; o cadeado diz se a cota não a inclui ou se a RBS a fechou. */}
            {estado !== 'LIBERADA' && (
              <i className={`ri-lock-line text-base ${isActive ? 'text-white' : 'text-cinza-400'}`} aria-label={motivo} title={motivo} />
            )}
          </>
        )}
      </NavLink>
    </li>
  )
}

function ChipConta({ usuario, parceria }: { usuario: UsuarioLogado; parceria: ParceriaResumo | null }) {
  const navegar = useNavigate()
  const rbs = usuario.perfil === 'ADMIN_RBS'
  const varias = !rbs && usuario.parcerias.length > 1
  const nome = parceria?.patrocinador ?? (rbs ? 'Todas as parcerias' : usuario.nome)

  return (
    <div className="mt-5 flex items-center gap-2.5 rounded-xl bg-cinza-100 p-2.5">
      <span className="grid size-8 shrink-0 place-items-center rounded-full bg-tinta font-display text-base text-white">
        {parceria ? iniciais(parceria.patrocinador) : rbs ? 'RBS' : iniciais(usuario.nome)}
      </span>
      <div className="min-w-0 flex-1 text-[13px] leading-tight font-semibold">
        {rbs && <span className="block text-[11px] font-medium text-texto-2">Contexto</span>}
        {varias ? (
          <select
            aria-label="Parceria"
            className="w-full truncate bg-transparent font-semibold outline-none"
            value={parceria?.id ?? ''}
            onChange={(evento) => navegar(`/parceria/${evento.target.value}`)}
          >
            {usuario.parcerias.map((item) => (
              <option key={item.id} value={item.id}>
                {item.patrocinador.replace(/^Cliente\s+/i, '')} · {nomeCota(item)}
              </option>
            ))}
          </select>
        ) : (
          <span className="block truncate">
            {nome}
            {parceria && ` · ${nomeCota(parceria)}`}
          </span>
        )}
      </div>
    </div>
  )
}

const CLASSE_BOTAO_ATLAS =
  'relative flex h-[60px] w-full items-center justify-center gap-2 overflow-hidden rounded-2xl border border-white/22 bg-atlas text-white shadow-atlas-brilho transition-[filter]'

function BotaoAtlas({ destino }: { destino?: string }) {
  // [ATLAS] Só a marca conversa com o Atlas, dentro de uma parceria: o botão abre a janela dele (Figma P360/Botão Atlas,
  // 167:18). Para a RBS fica desabilitado, e os relatórios do Atlas aparecem na tela Relatório da parceria.
  const ativo = Boolean(useMatch(`${destino ?? '/sem-atlas'}/*`))
  const conteudo = (
    <>
      <span className="absolute -top-6 -left-4 size-20 rounded-full bg-[#0a2a6e] opacity-70 blur-2xl" aria-hidden="true" />
      <span className="absolute -bottom-8 left-16 size-20 rounded-full bg-[#572781] opacity-70 blur-2xl" aria-hidden="true" />
      <span className="absolute -right-6 -bottom-6 size-20 rounded-full bg-[#e1047d] opacity-60 blur-2xl" aria-hidden="true" />
      <i className="ri-globe-fill relative text-2xl" aria-hidden="true" />
      <span className="relative text-xl font-semibold tracking-[-0.01em]">atlas</span>
    </>
  )
  return (
    <div>
      {destino ? (
        <Link to={destino} aria-describedby="atlas-legenda" aria-current={ativo ? 'page' : undefined} className={`${CLASSE_BOTAO_ATLAS} hover:brightness-125`}>
          {conteudo}
        </Link>
      ) : (
        <button type="button" disabled aria-describedby="atlas-legenda" className={`${CLASSE_BOTAO_ATLAS} cursor-not-allowed`}>
          {conteudo}
        </button>
      )}
      <p id="atlas-legenda" className={`mt-1.5 text-center text-[11px] font-medium ${ativo ? 'text-atlas-roxo' : 'text-texto-2'}`}>
        {ativo ? 'Conversa aberta · assistente IA' : destino ? 'Assistente IA · números da parceria' : 'Assistente da marca'}
      </p>
    </div>
  )
}

type PropsMenu = {
  parceria: ParceriaResumo | null
  /** Abaixo de 1024 px o menu é uma gaveta: fechada até a pessoa tocar em "Menu". */
  aberto: boolean
  aoFechar: () => void
  /** O endereço da janela do Atlas; sem ele (RBS ou fora de uma parceria), o botão fica desabilitado. */
  atlas?: string
}

export function Menu({ parceria, aberto, aoFechar, atlas }: PropsMenu) {
  const { usuario, sair } = useSessao()
  const navegar = useNavigate()
  const [ajudaAberta, setAjudaAberta] = useState(false)
  const fecharComEsc = useEffectEvent((evento: KeyboardEvent) => evento.key === 'Escape' && aoFechar())

  useEffect(() => {
    if (!aberto) return
    const aoTeclar = (evento: KeyboardEvent) => fecharComEsc(evento)
    document.addEventListener('keydown', aoTeclar)
    return () => document.removeEventListener('keydown', aoTeclar)
  }, [aberto])

  if (!usuario) return null
  const rbs = usuario.perfil === 'ADMIN_RBS'

  async function encerrar() {
    await sair()
    navegar('/entrar', { replace: true })
  }

  // Na gaveta fechada, o menu sai da tela e do Tab (invisible); de 1024 px para cima ele fica sempre à vista.
  // Escolher um destino na gaveta (link ou a parceria no seletor) já fecha o menu.
  const gaveta = aberto ? 'max-lg:visible max-lg:translate-x-0' : 'max-lg:invisible max-lg:-translate-x-full'

  return (
    <>
      {aberto && <div className="fixed inset-0 z-40 bg-tinta/45 lg:hidden" onClick={aoFechar} aria-hidden="true" />}
      <aside
        id="menu-principal"
        onClick={(evento) => (evento.target as HTMLElement).closest('a') && aoFechar()}
        onChange={aoFechar}
        className={`fixed top-0 bottom-0 left-0 z-50 flex w-[280px] max-w-[85vw] flex-col overflow-y-auto border border-borda-menu bg-white px-5 pt-12 pb-5 shadow-menu lg:pt-6 transition-[translate,visibility] duration-200 lg:top-4 lg:bottom-4 lg:left-4 lg:z-20 lg:w-[264px] lg:rounded-[20px] print:hidden ${gaveta}`}
      >
        <button
          type="button"
          onClick={aoFechar}
          className="absolute top-3 right-3 flex items-center gap-1 text-[13px] font-semibold lg:hidden"
        >
          Fechar <i className="ri-close-line text-lg" aria-hidden="true" />
        </button>
        <div>
          <p className="font-display text-[56px] leading-[0.9] tracking-[-0.01em] whitespace-nowrap text-parceria uppercase">
            Planeta 360
          </p>
          <p className="mt-1.5 text-[10px] font-bold tracking-[0.14em] text-texto-2 uppercase">
            {rbs ? 'RBS · Gestão' : 'Patrocinador'}
          </p>
        </div>

        <ChipConta usuario={usuario} parceria={parceria} />

        <nav className="-mx-1 mt-5 flex-1 overflow-y-auto px-1" aria-label="Menu principal">
          {rbs && (
            <>
              <ul className="flex flex-col gap-1" data-secao="neutro">
                {GESTAO_RBS.map((atalho) => (
                  <li key={atalho.caminho}>
                    <NavLink to={atalho.caminho} end className={({ isActive }) => classeItem(isActive)}>
                      <i className={`${atalho.icone} text-xl`} aria-hidden="true" />
                      {atalho.nome}
                    </NavLink>
                  </li>
                ))}
              </ul>
              <p className="mt-4 mb-2 px-3 text-[10px] font-bold tracking-[0.14em] text-texto-2 uppercase">
                {parceria ? 'Parceria aberta' : 'Abra uma parceria em Patrocinadores'}
              </p>
            </>
          )}
          {parceria && (
            <ul className="flex flex-col gap-1">
              {SECOES.map((secao) => (
                <ItemSecao key={secao.chave} secao={secao} parceria={parceria} rbs={rbs} />
              ))}
            </ul>
          )}
        </nav>

        <div className="mt-4 flex flex-col gap-4">
          <BotaoAtlas destino={atlas} />
          <div className="relative flex items-center justify-between border-t border-borda pt-4 text-[13px] font-semibold">
            <button
              type="button"
              className="flex items-center gap-1.5 hover:text-parceria"
              aria-expanded={ajudaAberta}
              onClick={() => setAjudaAberta((aberta) => !aberta)}
            >
              <i className="ri-question-line text-lg" aria-hidden="true" />
              Ajuda
            </button>
            <button type="button" className="flex items-center gap-1.5 hover:text-parceria" onClick={encerrar}>
              <i className="ri-logout-box-r-line text-lg" aria-hidden="true" />
              Sair
            </button>
            {ajudaAberta && (
              <p className="absolute right-0 bottom-full left-0 mb-2 rounded-xl border border-borda bg-white p-3 text-xs font-medium shadow-menu">
                Dúvidas sobre a parceria, acessos ou prazos: fale com o time B2B da RBS.
              </p>
            )}
          </div>
        </div>
      </aside>
    </>
  )
}
