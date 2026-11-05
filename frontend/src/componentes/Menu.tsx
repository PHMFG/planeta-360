// Menu lateral flutuante do Figma (P360/Navegação): marca, conta, as oito seções e o rodapé. [ITEM-7]
// A RBS vê também os atalhos de gestão e escolhe a parceria que está consultando.
import { useEffect, useRef, useState, type RefObject } from 'react'
import { Link, NavLink, useMatch, useNavigate } from 'react-router'
import type { ParceriaResumo, UsuarioLogado } from '../api/tipos'
import { useSessao } from '../sessao/Sessao'
import { estadoAbaNaParceria } from './AbaFechada'
import { Modal } from './Modal'
import { GESTAO_RBS, SECOES, type Secao } from './secoes'
import { usarFocoPreso } from './usarFocoPreso'


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

// Em telas baixas (a variante `baixa`, de tema.css) o menu fica mais compacto. As classes ficam escritas por inteiro: o
// Tailwind só gera as que aparecem no código.
const ITEM = 'flex h-11 baixa:h-9 items-center gap-3 rounded-[10px] px-3 text-sm transition-colors'

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
      : estado === 'OCULTA'
        ? 'Oculta para a marca'
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
            {/* [PERMISSAO] A aba aparece para todos; o estado fica escrito ao lado do cadeado, e não só no hover (U05 e U19).
                O motivo inteiro vai no title e no texto para leitor de tela. */}
            {estado !== 'LIBERADA' && (
              <span className={`flex items-center gap-1 text-[11px] font-semibold ${isActive ? 'text-white' : 'text-texto-2'}`} title={motivo}>
                <i className="ri-lock-line text-sm" aria-hidden="true" />
                {estado === 'FORA_DA_COTA' ? 'Fora da cota' : estado === 'OCULTA' ? 'Oculta' : 'Fechada'}
                <span className="sr-only">: {motivo}</span>
              </span>
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
    <div className="mt-5 baixa:mt-3 flex items-center gap-2.5 rounded-xl bg-cinza-100 p-2.5">
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

const CLASSE_BOTAO_ATLAS = 'relative flex h-[60px] baixa:h-[50px] w-full items-center justify-center gap-2 overflow-hidden rounded-2xl border border-white/22 bg-atlas text-white shadow-atlas-brilho transition-[filter]'

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
        // Desabilitado precisa parecer desabilitado (U17): a RBS não conversa com o Atlas da marca.
        <button type="button" disabled aria-describedby="atlas-legenda" className={`${CLASSE_BOTAO_ATLAS} cursor-not-allowed opacity-55 grayscale-[40%]`}>
          {conteudo}
        </button>
      )}
      <p id="atlas-legenda" className={`mt-1.5 text-center text-[11px] font-medium ${ativo ? 'text-atlas-roxo' : 'text-texto-2'}`}>
        {ativo ? 'Conversa aberta · assistente IA' : destino ? 'Assistente IA · números da parceria' : 'Assistente das marcas'}
      </p>
    </div>
  )
}

/** O menu tem itens abaixo da área visível, sem ter rolado até o fim. Relê ao rolar e quando o tamanho muda. */
function usarMaisAbaixo(nav: RefObject<HTMLElement | null>) {
  const [mais, setMais] = useState(false)
  useEffect(() => {
    const elemento = nav.current
    if (!elemento) return
    const medir = () => setMais(elemento.scrollHeight - elemento.scrollTop - elemento.clientHeight > 4)
    medir()
    elemento.addEventListener('scroll', medir, { passive: true })
    const observador = new ResizeObserver(medir)
    observador.observe(elemento)
    for (const filho of elemento.children) observador.observe(filho)
    return () => {
      elemento.removeEventListener('scroll', medir)
      observador.disconnect()
    }
  })
  return mais
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
  // Na gaveta aberta o foco entra nela, o Tab gira só dentro dela, e o Esc fecha e devolve o foco ao botão Menu (U08).
  const gavetaRef = usarFocoPreso(aberto, aoFechar)
  // A gaveta entra com transição de visibilidade: no quadro em que abre ela ainda está invisível, e o foco do usarFocoPreso
  // não pega. Dois quadros depois ela já é visível e recebe o foco.
  useEffect(() => {
    if (!aberto) return
    let segundo = 0
    const primeiro = requestAnimationFrame(() => {
      segundo = requestAnimationFrame(() => gavetaRef.current?.focus())
    })
    return () => {
      cancelAnimationFrame(primeiro)
      cancelAnimationFrame(segundo)
    }
  }, [aberto, gavetaRef])
  const navRef = useRef<HTMLElement>(null)
  const maisAbaixo = usarMaisAbaixo(navRef)

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
        ref={gavetaRef}
        tabIndex={-1}
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
          <p className="font-display text-[56px] baixa:text-[44px] leading-[0.9] tracking-[-0.01em] whitespace-nowrap text-parceria uppercase">
            Planeta 360
          </p>
          <p className="mt-1.5 text-[10px] font-bold tracking-[0.14em] text-texto-2 uppercase">
            {rbs ? 'RBS · Gestão' : parceria?.vinculo === 'AGENCIA' ? 'Agência do patrocinador' : 'Patrocinador'}
          </p>
        </div>

        <ChipConta usuario={usuario} parceria={parceria} />

        <nav ref={navRef} className="-mx-1 mt-5 baixa:mt-3 flex-1 overflow-y-auto px-1" aria-label="Menu principal">
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
              {/* A aba que a RBS ocultou some do menu da marca; a RBS continua vendo, marcada como Oculta (R6.a). */}
              {SECOES.filter((secao) => rbs || estadoAbaNaParceria(parceria, secao.aba) !== 'OCULTA').map((secao) => (
                <ItemSecao key={secao.chave} secao={secao} parceria={parceria} rbs={rbs} />
              ))}
            </ul>
          )}
          {/* Quando o menu não cabe (a RBS com uma parceria aberta), a pista mostra que há itens abaixo (U01). Não recebe
              clique: o item que está por baixo dela continua clicável. */}
          {maisAbaixo && (
            <p
              data-mais-abaixo
              aria-hidden="true"
              className="pointer-events-none sticky bottom-0 -mt-8 bg-gradient-to-t from-white via-white/90 to-transparent pt-6 pb-1 text-center text-[11px] font-semibold text-texto-2"
            >
              Role para ver mais <i className="ri-arrow-down-line" />
            </p>
          )}
        </nav>

        <div className="mt-4 baixa:mt-3 flex flex-col gap-4 baixa:gap-3">
          <BotaoAtlas destino={atlas} />
          <div className="relative flex items-center justify-between border-t border-borda pt-4 text-[13px] font-semibold">
            <button
              type="button"
              className="flex items-center gap-1.5 hover:text-parceria"
              aria-haspopup="dialog"
              onClick={() => setAjudaAberta(true)}
            >
              <i className="ri-question-line text-lg" aria-hidden="true" />
              Ajuda
            </button>
            <button type="button" className="flex items-center gap-1.5 hover:text-parceria" onClick={encerrar}>
              <i className="ri-logout-box-r-line text-lg" aria-hidden="true" />
              Sair
            </button>

          </div>
        </div>
      </aside>
      {/* A ajuda abre num painel por cima da tela: dentro do menu, que rola, ela ficava cortada (U11). */}
      <Modal titulo="Ajuda" legenda={rbs ? 'O que cada tela da gestão faz' : 'O que cada aba da parceria faz'} aberto={ajudaAberta} aoFechar={() => setAjudaAberta(false)}>
        <ul className="flex flex-col gap-3 text-sm">
          {(rbs ? GESTAO_RBS.map((atalho) => ({ nome: atalho.nome, texto: TEXTO_DA_GESTAO[atalho.caminho] ?? '' })) : SECOES.map((secao) => ({ nome: secao.nome, texto: secao.subtitulo }))).map(
            (item) => (
              <li key={item.nome}>
                <span className="font-semibold">{item.nome}</span>
                {item.texto && <span className="text-texto-2"> · {item.texto}</span>}
              </li>
            ),
          )}
        </ul>
        <p className="mt-4 rounded-xl bg-cinza-100 p-3 text-sm font-medium">
          {rbs
            ? 'O Atlas de cada marca abre só no acesso dela. Dúvidas sobre o portal: fale com a equipe do projeto.'
            : 'Dúvidas sobre a parceria, acessos, prazos ou cortesias: fale com o atendimento ao patrocinador da RBS (time B2B).'}
        </p>
      </Modal>
    </>
  )
}

/** Uma linha por tela da gestão, para a ajuda da RBS. */
const TEXTO_DA_GESTAO: Record<string, string> = {
  '/rbs': 'resumo das parcerias, da fila e da última carga.',
  '/rbs/patrocinadores': 'cadastro das parcerias e dos e-mails de acesso.',
  '/rbs/fila': 'materiais enviados pelas marcas, para aprovar ou pedir ajuste.',
  '/rbs/cargas': 'planilhas de veiculação, de pesquisa e do plano comercial.',
  '/rbs/pesquisa': 'a pesquisa de cada edição e as perguntas das marcas Master.',
  '/rbs/relatorios': 'gerar, validar e disponibilizar o relatório de cada parceria.',
  '/rbs/historico': 'tudo o que aconteceu no portal, com autor e data.',
  '/rbs/atlas': 'documentos, prazos e o diagnóstico do assistente.',
}
