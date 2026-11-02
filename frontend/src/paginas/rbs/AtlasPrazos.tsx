// Aba Prazos do Atlas na gestão da RBS: os prazos da edição, de uma cota ou de uma parceria, que o Atlas consulta (rotas 62 a 64). [ATLAS]
// Desativar não apaga: o prazo sai do Atlas e continua na lista, para quem pedir os desativados. Prazo com documento de origem só vai
// para o Atlas enquanto esse documento estiver no ar.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { listarDocumentos, type CodigoDaCota, type EscopoDoDocumento } from '../../api/documentos'
import { listarEventos, listarParcerias, type Evento } from '../../api/parcerias'
import { criarPrazo, desativarPrazo, listarPrazos, type Prazo } from '../../api/prazos'
import { useDados, type Dados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { Campo } from '../../componentes/Campo'
import { Carregando } from '../../componentes/Carregando'
import { Cartao } from '../../componentes/Cartao'
import { AreaTexto, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { data } from '../../util/formatar'

const ESCOPOS: { codigo: EscopoDoDocumento; nome: string }[] = [
  { codigo: 'GLOBAL', nome: 'Todas as parcerias da edição' },
  { codigo: 'COTA', nome: 'Uma cota da edição' },
  { codigo: 'PARCERIA', nome: 'Uma parceria' },
]

const COTAS: { codigo: CodigoDaCota; nome: string }[] = [
  { codigo: 'MASTER', nome: 'Master' },
  { codigo: 'SUBCOTA_G', nome: 'Subcota G' },
  { codigo: 'SUBCOTA', nome: 'Subcota' },
]

function alcance(prazo: Prazo) {
  if (prazo.escopo === 'COTA') return `Cota ${prazo.cota?.nome ?? ''}`
  if (prazo.escopo === 'PARCERIA') return prazo.parceria?.patrocinador ?? 'Parceria'
  return 'Todas as parcerias'
}

function NovoPrazo({ eventoId, aoCriar }: { eventoId: number; aoCriar: () => void }) {
  const [titulo, setTitulo] = useState('')
  const [dia, setDia] = useState('')
  const [hora, setHora] = useState('')
  const [descricao, setDescricao] = useState('')
  const [escopo, setEscopo] = useState<EscopoDoDocumento>('GLOBAL')
  const [cota, setCota] = useState<CodigoDaCota>('MASTER')
  const [parceriaId, setParceriaId] = useState('')
  const [documentoId, setDocumentoId] = useState('')
  const [pagina, setPagina] = useState('')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [criado, setCriado] = useState(false)
  const parcerias = useDados(() => (escopo === 'PARCERIA' ? listarParcerias({ eventoId }) : Promise.resolve([])), [escopo, eventoId])
  const documentos = useDados(() => listarDocumentos({ eventoId, status: 'INDEXADO' }), [eventoId])

  async function criar(evento: FormEvent) {
    evento.preventDefault()
    setFalha(null)
    setErros({})
    setCriado(false)
    setEnviando(true)
    try {
      await criarPrazo(eventoId, {
        escopo,
        cota: escopo === 'COTA' ? cota : undefined,
        parceriaId: escopo === 'PARCERIA' && parceriaId ? Number(parceriaId) : undefined,
        titulo: titulo.trim(),
        data: dia,
        hora: hora || undefined,
        descricao: descricao.trim() || undefined,
        documentoId: documentoId ? Number(documentoId) : undefined,
        pagina: documentoId && pagina ? Number(pagina) : undefined,
      })
      setTitulo('')
      setDia('')
      setHora('')
      setDescricao('')
      setDocumentoId('')
      setPagina('')
      setCriado(true)
      aoCriar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        setFalha(Object.keys(erro.campos).length > 0 ? null : erro.message)
      } else setFalha('Não foi possível criar o prazo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Novo prazo" className="min-w-0">
      <form onSubmit={criar} className="flex flex-col gap-4" noValidate>
        <div className="grid grid-cols-[minmax(0,1fr)] gap-4 md:grid-cols-2">
          <Campo rotulo="Título" value={titulo} onChange={(campo) => setTitulo(campo.target.value)} erro={erros.titulo} ajuda="É o que o Atlas diz à marca." maxLength={200} />
          <Seletor rotulo="Vale para" value={escopo} erro={erros.escopo} onChange={(campo) => setEscopo(campo.target.value as EscopoDoDocumento)}>
            {ESCOPOS.map((item) => (
              <option key={item.codigo} value={item.codigo}>
                {item.nome}
              </option>
            ))}
          </Seletor>
          {escopo === 'COTA' && (
            <Seletor rotulo="Cota" value={cota} erro={erros.cota} onChange={(campo) => setCota(campo.target.value as CodigoDaCota)}>
              {COTAS.map((item) => (
                <option key={item.codigo} value={item.codigo}>
                  {item.nome}
                </option>
              ))}
            </Seletor>
          )}
          {escopo === 'PARCERIA' && (
            <Seletor rotulo="Parceria" value={parceriaId} erro={erros.parceriaId} onChange={(campo) => setParceriaId(campo.target.value)}>
              <option value="">Escolha a parceria</option>
              {(parcerias.dado ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.patrocinador.nome} · {item.cota.nome}
                </option>
              ))}
            </Seletor>
          )}
          <Campo rotulo="Data" type="date" value={dia} onChange={(campo) => setDia(campo.target.value)} erro={erros.data} />
          <Campo rotulo="Hora (opcional)" type="time" value={hora} onChange={(campo) => setHora(campo.target.value)} erro={erros.hora} />
          <Seletor rotulo="Documento de origem (opcional)" value={documentoId} erro={erros.documentoId} onChange={(campo) => setDocumentoId(campo.target.value)}>
            <option value="">Sem documento</option>
            {(documentos.dado?.itens ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.titulo} · versão {item.versao}
              </option>
            ))}
          </Seletor>
          {documentoId && <Campo rotulo="Página do documento" type="number" min={1} inputMode="numeric" value={pagina} onChange={(campo) => setPagina(campo.target.value)} erro={erros.pagina} />}
        </div>
        <AreaTexto rotulo="Descrição (opcional)" value={descricao} onChange={(campo) => setDescricao(campo.target.value)} erro={erros.descricao} maxLength={1000} />
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {criado && <Aviso titulo="Prazo criado. O Atlas já o enxerga." />}
        <div>
          <Botao type="submit" carregando={enviando} icone="ri-add-line" className="max-sm:w-full">
            Criar prazo
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}

function LinhaDoPrazo({ prazo, aoDesativar }: { prazo: Prazo; aoDesativar: () => void }) {
  const foraDoAr = prazo.documento !== null && prazo.documento.status !== 'INDEXADO'
  return (
    <li className="flex flex-col gap-2 border-t border-borda py-3.5 first:border-t-0 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <div className="min-w-0">
        <p className="font-semibold [overflow-wrap:anywhere]">
          {prazo.titulo}
          {!prazo.ativo && <span className="ml-2 text-xs font-medium text-texto-2">Desativado</span>}
        </p>
        <p className="text-sm text-texto-2">
          {data(prazo.data)}
          {prazo.hora && ` às ${prazo.hora}`} · {alcance(prazo)}
        </p>
        {prazo.descricao && <p className="mt-1 text-sm [overflow-wrap:anywhere]">{prazo.descricao}</p>}
        {prazo.documento && (
          <p className="mt-1 text-xs font-medium text-texto-2 [overflow-wrap:anywhere]">
            {prazo.documento.titulo} · versão {prazo.documento.versao}
            {prazo.pagina !== null && ` · página ${prazo.pagina}`}
            {foraDoAr && prazo.ativo && ' · documento fora do ar: o Atlas não usa este prazo'}
          </p>
        )}
      </div>
      {prazo.ativo && (
        <Botao variante="secundario" className="min-w-0 shrink-0 max-sm:w-full" aria-label={`Desativar o prazo ${prazo.titulo}`} onClick={aoDesativar}>
          Desativar
        </Botao>
      )}
    </li>
  )
}

function ModalDeDesativar({ prazo, aoFechar, aoConcluir }: { prazo: Prazo; aoFechar: () => void; aoConcluir: () => void }) {
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function confirmar() {
    setFalha(null)
    setEnviando(true)
    try {
      await desativarPrazo(prazo.id)
      aoConcluir()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível desativar o prazo.')
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo="Desativar o prazo" legenda={prazo.titulo} aoFechar={aoFechar}>
      <p className="text-sm">O Atlas deixa de usar este prazo na hora. Ele continua na lista, com os desativados.</p>
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div className="flex flex-wrap justify-end gap-2">
        <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
          Cancelar
        </Botao>
        <Botao carregando={enviando} className="min-w-0 max-sm:flex-1" onClick={confirmar}>
          Desativar
        </Botao>
      </div>
    </Modal>
  )
}

function PrazosDaEdicao({ evento, eventos, aoTrocar }: { evento: Evento; eventos: Evento[]; aoTrocar: (eventoId: number) => void }) {
  const [comInativos, setComInativos] = useState(false)
  const [desativando, setDesativando] = useState<Prazo | null>(null)
  const prazos = useDados(() => listarPrazos(evento.id, comInativos), [evento.id, comInativos])

  return (
    <>
      <Cartao titulo="Prazos" className="min-w-0">
        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-end">
          <Seletor rotulo="Edição" className="min-w-0 flex-1" value={evento.id} onChange={(campo) => aoTrocar(Number(campo.target.value))}>
            {eventos.map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
          <label className="flex min-h-11 items-center gap-2 text-sm font-medium">
            <input type="checkbox" className="size-4" checked={comInativos} onChange={(campo) => setComInativos(campo.target.checked)} />
            Mostrar os desativados
          </label>
        </div>
        <Carregando dados={prazos} vazio={(lista) => lista.length === 0} textoVazio="Nenhum prazo nesta edição.">
          {(lista) => (
            <ul className="flex flex-col">
              {lista.map((prazo) => (
                <LinhaDoPrazo key={prazo.id} prazo={prazo} aoDesativar={() => setDesativando(prazo)} />
              ))}
            </ul>
          )}
        </Carregando>
      </Cartao>
      <NovoPrazo eventoId={evento.id} aoCriar={prazos.recarregar} />
      {desativando && (
        <ModalDeDesativar
          prazo={desativando}
          aoFechar={() => setDesativando(null)}
          aoConcluir={() => {
            setDesativando(null)
            prazos.recarregar()
          }}
        />
      )}
    </>
  )
}

export function AbaPrazos() {
  const eventos: Dados<Evento[]> = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState<number | null>(null)
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Carregando dados={eventos} vazio={(lista) => lista.length === 0} textoVazio="Nenhuma edição cadastrada.">
        {(lista) => <PrazosDaEdicao evento={lista.find((item) => item.id === eventoId) ?? lista[0]} eventos={lista} aoTrocar={setEventoId} />}
      </Carregando>
    </div>
  )
}
