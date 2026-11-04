// Aba Contrato (Figma P07; requisito R5.7 da RBS): o contrato final assinado, que a RBS sobe para histórico, no
// visualizador, com "Baixar PDF" e "Abrir em tela cheia"; e os dados de quem assina, que a marca preenche. O signatário é
// dado pessoal de terceiro (D8): só esta aba o mostra. As entregas da aba continuam embaixo.
import { lazy, Suspense, useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import { enviarContratoAssinado, informarSignatario, obterContrato, type Contrato as DadosDoContrato } from '../../api/contrato'
import { baixarArquivo } from '../../api/materiais'
import { useDados } from '../../api/useDados'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { LinhaArquivo } from '../../componentes/Arquivo'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo, focarPrimeiroErro } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { AreaTexto } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { SECOES } from '../../componentes/secoes'
import { useSessao } from '../../sessao/Sessao'
import { data, dataHora, tamanho } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

const VisualizadorDeContrato = lazy(() => import('./VisualizadorDeContrato'))
const SECAO = SECOES.find((secao) => secao.chave === 'contrato')!
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

export function Contrato() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'CONTRATO')
  const podeVer = rbs || estado === 'LIBERADA'

  return (
    <div data-secao="contrato" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Contrato"
        subtitulo="Contrato final assinado, disponibilizado pela RBS para consulta, e os dados de quem assina."
      />
      <AvisoAbaFechada parceria={parceria} estado={estado} nome={SECAO.nome} rbs={rbs} />
      {podeVer && <ConteudoDoContrato rbs={rbs} />}
      {podeVer && <EntregasDaAba aba="CONTRATO" />}
    </div>
  )
}

function ConteudoDoContrato({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const dados = useDados(() => obterContrato(parceria.id), [parceria.id])
  useRecarregarSeAbaFechou(dados)
  const [cheio, setCheio] = useState(false)

  return (
    <Carregando dados={dados}>
      {(contrato) => (
        <>
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
            <Cartao className="bg-secao-tinta">
              {contrato.arquivo ? (
                <Suspense fallback={<p className="text-sm text-texto-2">Abrindo o visualizador…</p>}>
                  <VisualizadorDeContrato arquivoId={contrato.arquivo.id} nome={contrato.arquivo.nomeOriginal} />
                </Suspense>
              ) : (
                <Aviso tipo="ausente" titulo="A RBS ainda não enviou o contrato assinado">
                  {rbs ? 'Envie o PDF assinado no cartão "Enviar contrato assinado".' : 'Quando a RBS enviar, o PDF abre aqui.'}
                </Aviso>
              )}
            </Cartao>
            <div className="flex flex-col gap-4">
              <Cartao>
                <p className="text-sm font-semibold">Contrato final assinado</p>
                <p className="text-xs font-medium text-texto-2">
                  {contrato.arquivo
                    ? `Publicado pela RBS · versão ${contrato.arquivo.versao} · ${dataHora(contrato.arquivo.enviadoEm)}`
                    : 'Publicado pela RBS · ainda não enviado'}
                </p>
                <p className="mt-1 text-xs font-semibold text-secao-texto">Somente consulta</p>
              </Cartao>
              <Signatarios contrato={contrato} aoSalvar={dados.recarregar} />
              {contrato.arquivo && (
                <div className="flex flex-col gap-3">
                  <Botao onClick={() => baixarArquivo({ id: contrato.arquivo!.id, nomeOriginal: contrato.arquivo!.nomeOriginal })}>Baixar PDF</Botao>
                  <Botao variante="secundario" onClick={() => setCheio(true)}>
                    Abrir em tela cheia
                  </Botao>
                </div>
              )}
            </div>
          </div>

          <Aviso titulo="Documento final">
            O PDF já assinado é disponibilizado pela RBS. Esta tela não realiza assinatura eletrônica nem altera o contrato.
          </Aviso>

          {rbs && <EnvioDaRbs aoEnviar={dados.recarregar} />}

          {contrato.versoes.length > 1 && (
            <Cartao titulo="Versões anteriores">
              <div className="flex flex-col gap-2">
                {contrato.versoes.slice(1).map((versao) => (
                  <LinhaArquivo
                    key={versao.id}
                    titulo={versao.nomeOriginal}
                    meta={`v${versao.versao} · ${versao.enviadoPor?.nome ?? '—'} · ${dataHora(versao.enviadoEm)} · ${tamanho(versao.tamanhoBytes)}`}
                    acoes={
                      <button type="button" className="text-xs font-bold hover:underline" onClick={() => baixarArquivo(versao)} aria-label={`Baixar a versão ${versao.versao}`}>
                        Baixar
                      </button>
                    }
                  />
                ))}
              </div>
            </Cartao>
          )}

          {contrato.arquivo && (
            <Modal titulo="Contrato" legenda={contrato.arquivo.nomeOriginal} aberto={cheio} aoFechar={() => setCheio(false)} largura="max-w-[1100px]">
              <Suspense fallback={<p className="text-sm text-texto-2">Abrindo o visualizador…</p>}>
                <VisualizadorDeContrato arquivoId={contrato.arquivo.id} nome={contrato.arquivo.nomeOriginal} cheio />
              </Suspense>
            </Modal>
          )}
        </>
      )}
    </Carregando>
  )
}

/** Figma P07, "Signatários": a marca informa quem assina pela empresa; a RBS vê e pode corrigir no lugar dela (R5.7.a). */
function Signatarios({ contrato, aoSalvar }: { contrato: DadosDoContrato; aoSalvar: () => void }) {
  const parceria = useParceria()
  const atual = contrato.signatario
  const [editando, setEditando] = useState(atual === null)
  const [nome, setNome] = useState(atual?.nome ?? '')
  const [cargo, setCargo] = useState(atual?.cargo ?? '')
  const [email, setEmail] = useState(atual?.email ?? '')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState(false)
  const [enviando, setEnviando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    const novos: Record<string, string> = {}
    if (nome.trim().length < 3) novos.nome = 'Informe o nome de quem assina (pelo menos 3 letras).'
    if (!EMAIL.test(email.trim())) novos.email = 'Confira o formato do e-mail.'
    setErros(novos)
    setFeito(false)
    if (Object.keys(novos).length) return focarPrimeiroErro()
    setEnviando(true)
    setFalha(null)
    try {
      await informarSignatario(parceria.id, { nome: nome.trim(), email: email.trim(), cargo: cargo.trim() || null })
      setEditando(false)
      setFeito(true)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(erro.campos)
        setFalha(erro.message)
      } else setFalha('Não foi possível salvar o signatário.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Signatários">
      {editando ? (
        <form onSubmit={salvar} className="flex flex-col gap-3" noValidate>
          <Campo rotulo="Nome de quem assina" maxLength={120} value={nome} onChange={(evento) => setNome(evento.target.value)} erro={erros.nome} autoComplete="off" />
          <Campo rotulo="Cargo" maxLength={80} value={cargo} onChange={(evento) => setCargo(evento.target.value)} erro={erros.cargo} autoComplete="off" />
          <Campo rotulo="E-mail" type="email" maxLength={254} value={email} onChange={(evento) => setEmail(evento.target.value)} erro={erros.email} autoComplete="off" />
          {falha && <Aviso tipo="falha" titulo={falha} />}
          <div className="flex gap-3">
            <Botao type="submit" carregando={enviando}>
              Salvar signatário
            </Botao>
            {atual && (
              <Botao variante="secundario" onClick={() => setEditando(false)}>
                Cancelar
              </Botao>
            )}
          </div>
        </form>
      ) : (
        <div className="flex flex-col gap-1 text-sm">
          <p>
            <span className="font-semibold">Patrocinador:</span> {atual?.nome}
            {atual?.cargo && ` · ${atual.cargo}`}
          </p>
          <p className="text-texto-2">{atual?.email}</p>
          <p>
            <span className="font-semibold">Assinado em:</span> {contrato.assinadoEm ? data(contrato.assinadoEm) : 'a informar pela RBS'}
          </p>
          {atual?.informadoEm && (
            <p className="text-xs text-texto-2">
              Informado por {atual.informadoPor?.nome ?? '—'} em {dataHora(atual.informadoEm)}
            </p>
          )}
          {feito && (
            <p className="text-xs font-semibold text-secao-texto" role="status">
              Signatário salvo.
            </p>
          )}
          <div className="mt-2">
            <Botao variante="secundario" onClick={() => setEditando(true)}>
              Alterar signatário
            </Botao>
          </div>
        </div>
      )}
    </Cartao>
  )
}

/** A RBS sobe o contrato final assinado (só PDF), com a data da assinatura; cada envio é uma versão nova (R5.7.b). */
function EnvioDaRbs({ aoEnviar }: { aoEnviar: () => void }) {
  const parceria = useParceria()
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [campoDoArquivo, setCampoDoArquivo] = useState(0)
  const [assinadoEm, setAssinadoEm] = useState('')
  const [observacao, setObservacao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    setFeito(null)
    if (!arquivo) return setFalha('Escolha o PDF do contrato assinado.')
    if (!arquivo.name.toLowerCase().endsWith('.pdf')) return setFalha('O contrato assinado precisa ser um PDF.')
    setEnviando(true)
    setFalha(null)
    try {
      const contrato = await enviarContratoAssinado(parceria.id, arquivo, assinadoEm, observacao)
      setFeito(`Contrato enviado como versão ${contrato.arquivo?.versao ?? ''}. A marca já pode abrir.`)
      setArquivo(null)
      setCampoDoArquivo((atual) => atual + 1)
      setObservacao('')
      aoEnviar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível enviar o contrato.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Enviar contrato assinado">
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-tinta bg-cinza-100 px-4 py-4 text-sm">
          <i className="ri-file-pdf-2-line text-2xl" aria-hidden="true" />
          <span className="flex-1 font-semibold">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher o PDF assinado (até 20 MB)'}</span>
          <input key={campoDoArquivo} type="file" accept=".pdf,application/pdf" className="sr-only" onChange={(campo) => setArquivo(campo.target.files?.[0] ?? null)} />
        </label>
        <div className="grid gap-4 md:grid-cols-2">
          <Campo rotulo="Data da assinatura" type="date" value={assinadoEm} onChange={(evento) => setAssinadoEm(evento.target.value)} ajuda="Opcional. Sem ela, fica a data anterior." />
          <AreaTexto rotulo="Observação (opcional)" maxLength={500} value={observacao} onChange={(evento) => setObservacao(evento.target.value)} />
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {feito && (
          <p className="text-sm font-semibold text-secao-texto" role="status">
            {feito}
          </p>
        )}
        <div>
          <Botao type="submit" carregando={enviando}>
            Enviar contrato
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}
