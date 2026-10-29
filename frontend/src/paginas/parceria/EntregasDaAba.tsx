// Lista das entregas de uma aba, com o detalhe em modal; a RBS também cria entregas aqui. [ITEM-4]
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { criarEntrega, listarEntregas, type Entrega } from '../../api/materiais'
import type { CodigoAba } from '../../api/tipos'
import { useDados } from '../../api/useDados'
import { LinhaArquivo } from '../../componentes/Arquivo'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { Status } from '../../componentes/Status'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { DetalheEntrega } from './DetalheEntrega'

export function NovaEntrega({ aba, aberto, aoFechar, aoCriar }: { aba: CodigoAba; aberto: boolean; aoFechar: () => void; aoCriar: () => void }) {
  const parceria = useParceria()
  const [titulo, setTitulo] = useState('')
  const [descricao, setDescricao] = useState('')
  const [prazo, setPrazo] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function criar(evento: FormEvent) {
    evento.preventDefault()
    if (titulo.trim().length < 3) return setErros({ titulo: 'Descreva a entrega em poucas palavras.' })
    setErros({})
    setFalha(null)
    setEnviando(true)
    try {
      await criarEntrega(parceria.id, {
        abaCodigo: aba,
        titulo: titulo.trim(),
        ...(descricao.trim() ? { descricao: descricao.trim() } : {}),
        ...(prazo ? { prazo } : {}),
      })
      setTitulo('')
      setDescricao('')
      setPrazo('')
      aoCriar()
      aoFechar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível criar a entrega.')
      if (erro instanceof ErroApi) setErros(erro.campos)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal titulo="Nova entrega" legenda={`${parceria.patrocinador} · a marca envia o material por aqui`} aberto={aberto} aoFechar={aoFechar}>
      <form onSubmit={criar} className="flex flex-col gap-4" noValidate>
        <Campo rotulo="Título" maxLength={160} value={titulo} onChange={(evento) => setTitulo(evento.target.value)} erro={erros.titulo} />
        <AreaTexto rotulo="Orientação para a marca (opcional)" value={descricao} onChange={(evento) => setDescricao(evento.target.value)} />
        <Campo rotulo="Prazo (opcional)" type="date" value={prazo} onChange={(evento) => setPrazo(evento.target.value)} erro={erros.prazo} />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex justify-end gap-4">
          <Botao variante="secundario" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando}>
            Criar entrega
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

/** As entregas em linhas do Figma (Arquivo): título, última versão e status; o clique abre o detalhe. */
export function ListaEntregas({ lista, aoAbrir }: { lista: Entrega[]; aoAbrir: (entrega: Entrega) => void }) {
  return (
    <div className="flex flex-col gap-3">
      {lista.map((entrega) => {
        const ultimo = entrega.arquivos[0]
        return (
          <LinhaArquivo
            key={entrega.id}
            titulo={entrega.titulo}
            icone={ultimo ? 'ri-file-text-line' : 'ri-file-add-line'}
            meta={
              ultimo
                ? `v${ultimo.versao} · ${ultimo.nomeOriginal} · ${ultimo.enviadoPor?.nome ?? '—'} · ${dataHora(ultimo.enviadoEm)}`
                : `Prazo: ${data(entrega.prazo)} · nenhum arquivo ainda`
            }
            rodape={<Status codigo={entrega.status} complemento="ver detalhe e histórico" />}
            onClick={() => aoAbrir(entrega)}
          />
        )
      })}
    </div>
  )
}

type Props = {
  aba: CodigoAba
  titulo?: string
  /** Frase do aviso quando a aba ainda não tem entregas. */
  vazio?: string
}

export function EntregasDaAba({ aba, titulo = 'Entregas', vazio = 'A RBS ainda não abriu entregas nesta aba.' }: Props) {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const entregas = useDados(() => listarEntregas(parceria.id, aba), [parceria.id, aba])
  useRecarregarSeAbaFechou(entregas)
  const [aberta, setAberta] = useState<Entrega | null>(null)
  const [criando, setCriando] = useState(false)

  return (
    <Cartao
      titulo={titulo}
      extra={
        rbs && (
          <button type="button" className="flex items-center gap-1 text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => setCriando(true)}>
            <i className="ri-add-line" aria-hidden="true" /> Nova entrega
          </button>
        )
      }
    >
      <Carregando dados={entregas} vazio={(lista) => lista.length === 0} textoVazio={vazio}>
        {(lista) => <ListaEntregas lista={lista} aoAbrir={setAberta} />}
      </Carregando>
      <DetalheEntrega entrega={aberta} aoFechar={() => setAberta(null)} aoMudar={entregas.recarregar} />
      {rbs && <NovaEntrega aba={aba} aberto={criando} aoFechar={() => setCriando(false)} aoCriar={entregas.recarregar} />}
    </Cartao>
  )
}
