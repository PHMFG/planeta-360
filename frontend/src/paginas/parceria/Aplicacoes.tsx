// Aplicações de marca (aba APLICACOES): o plano comercial linha a linha, as fotos que comprovam cada aplicação, os números da
// ativação que a marca informa e as entregas da aba. [ITEM-5] [UPLOAD]
import { useRef, useState, type FormEvent } from 'react'
import {
  EXTENSOES_FOTO,
  baixarComprovacao,
  enviarComprovacao,
  obterAplicacoes,
  problemaNaFoto,
  type Comprovacao,
  type LinhaAplicacao,
  type PlanoDeAplicacoes,
} from '../../api/aplicacoes'
import { ErroApi } from '../../api/cliente'
import { enviarArquivoDoCampo, informarDados, obterDados, type CampoComValor } from '../../api/dados'
import { baixarArquivo } from '../../api/materiais'
import { useDados } from '../../api/useDados'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { LinhaArquivo } from '../../componentes/Arquivo'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo } from '../../componentes/Campo'
import { Cartao, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { FormularioDadosInformados } from '../../componentes/FormularioDadosInformados'
import { MateriaisDaMarca } from '../../componentes/MateriaisDaMarca'
import { Modal } from '../../componentes/Modal'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, numero, porcentagem, tamanho } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

const NOME = 'Aplicações da marca'

export function Aplicacoes() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'APLICACOES')
  // [PERMISSAO] Com a aba fechada, a marca não busca nada daqui (a API recusaria) e lê só o aviso.
  if (!rbs && estado !== 'LIBERADA') {
    return (
      <div data-secao="aplicacoes" className="flex flex-col gap-4">
        <CabecalhoSecao trilha="Planeta Atlântida / Patrocinador" titulo={NOME} />
        <AvisoAbaFechada parceria={parceria} estado={estado} nome={NOME} rbs={false} />
      </div>
    )
  }
  return <ConteudoAplicacoes rbs={rbs} />
}

function ConteudoAplicacoes({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const estado = estadoAbaNaParceria(parceria, 'APLICACOES')
  const plano = useDados(() => obterAplicacoes(parceria.id), [parceria.id])
  useRecarregarSeAbaFechou(plano)
  const [anexando, setAnexando] = useState<LinhaAplicacao | null>(null)
  // Cada "Anexar foto" abre um formulário novo: a descrição e o erro do anexo anterior não passam para o próximo.
  const [anexos, setAnexos] = useState(0)
  const [anexada, setAnexada] = useState<{ linha: LinhaAplicacao; comprovacao: Comprovacao } | null>(null)

  return (
    <div data-secao="aplicacoes" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={NOME}
        subtitulo={
          rbs
            ? `O plano de aplicações de ${parceria.patrocinador}, as fotos de comprovação e os números da ativação.`
            : 'O que a sua cota prevê no evento, as fotos que comprovam cada aplicação e os números da sua ativação.'
        }
      />

      {/* Figma P02: os botões próprios da logo e do MIV (R5.2.a). */}
      <MateriaisDaMarca parceriaId={parceria.id} rbs={rbs} />

      {rbs && <AvisoAbaFechada parceria={parceria} estado={estado} nome={NOME} rbs />}

      {anexada && (
        <Aviso titulo="Foto anexada">
          {anexada.comprovacao.nomeOriginal} agora comprova “{anexada.linha.formato}”.
        </Aviso>
      )}

      <Carregando dados={plano}>{(dado) => <Totais totais={dado.totais} />}</Carregando>

      <Cartao titulo="Plano de aplicações">
        <Carregando
          dados={plano}
          vazio={(dado) => dado.linhas.length === 0}
          textoVazio={
            rbs
              ? 'Esta parceria ainda não tem aplicações de marca no plano. Suba a planilha do plano comercial em Cargas.'
              : 'A RBS ainda não carregou o plano de aplicações da sua cota.'
          }
        >
          {(dado) => (
            <ul className="flex flex-col gap-3">
              {dado.linhas.map((linha) => (
                <LinhaDoPlano
                  key={linha.id}
                  linha={linha}
                  rbs={rbs}
                  aoAnexar={() => {
                    setAnexada(null)
                    setAnexos((total) => total + 1)
                    setAnexando(linha)
                  }}
                />
              ))}
            </ul>
          )}
        </Carregando>
      </Cartao>

      <DadosDaAtivacao rbs={rbs} />

      <EntregasDaAba aba="APLICACOES" titulo="Entregas da aba" semPapel />

      <AnexarFoto
        key={anexos}
        linha={anexando}
        aoFechar={() => setAnexando(null)}
        aoAnexar={(linha, comprovacao) => {
          setAnexando(null)
          setAnexada({ linha, comprovacao })
          plano.recarregar()
        }}
      />
    </div>
  )
}

function Totais({ totais }: { totais: PlanoDeAplicacoes['totais'] }) {
  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
      <Indicador rotulo="Previstas" valor={numero(totais.previstas)} legenda="Aplicações no plano" />
      <Indicador rotulo="Comprovadas" valor={numero(totais.comprovadas)} legenda="Fotos de comprovação, contando até o previsto de cada linha" />
      <Indicador rotulo="Comprovação" valor={porcentagem(totais.percentual)} legenda="Comprovadas sobre previstas" />
      <Indicador rotulo="Fotos" valor={numero(totais.fotos)} legenda="Enviadas pela RBS" />
    </div>
  )
}

function LinhaDoPlano({ linha, rbs, aoAnexar }: { linha: LinhaAplicacao; rbs: boolean; aoAnexar: () => void }) {
  const [falha, setFalha] = useState<string | null>(null)
  const local = [linha.bloco, linha.veiculo, linha.espacoPrograma, linha.janela].filter(Boolean).join(' · ')

  async function baixar(comprovacao: Comprovacao) {
    setFalha(null)
    try {
      await baixarComprovacao(comprovacao)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar a foto.')
    }
  }

  return (
    <li className="flex flex-col gap-3 rounded-[14px] border border-borda p-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold break-words">{linha.formato}</p>
          {local && <p className="text-xs font-medium text-texto-2">{local}</p>}
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-3">
          <span
            className={`rounded-full px-2.5 py-1 text-xs font-bold ${
              linha.comprovadas >= linha.previsto ? 'bg-secao-tinta text-secao-texto' : 'bg-cinza-100 text-texto-2'
            }`}
          >
            {numero(linha.comprovadas)} de {numero(linha.previsto)} comprovada{linha.previsto === 1 ? '' : 's'}
          </span>
          {rbs && (
            <button type="button" onClick={aoAnexar} className="flex items-center gap-1 text-[13px] font-semibold text-secao-texto hover:underline">
              <i className="ri-image-add-line" aria-hidden="true" /> Anexar foto
            </button>
          )}
        </div>
      </div>
      {linha.comprovacoes.length === 0 ? (
        <p className="text-xs font-medium text-texto-2">Nenhuma foto ainda.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {linha.comprovacoes.map((comprovacao) => (
            <li key={comprovacao.id} className="flex items-center gap-3 rounded-[10px] bg-fundo px-3 py-2">
              <i className="ri-image-line text-lg text-secao-texto" aria-hidden="true" />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-xs font-semibold">{comprovacao.descricao || comprovacao.nomeOriginal}</span>
                <span className="truncate text-[11px] font-medium text-texto-2">
                  {comprovacao.registradoPor?.nome ?? '—'} · {dataHora(comprovacao.registradoEm)}
                </span>
              </span>
              <button
                type="button"
                onClick={() => baixar(comprovacao)}
                className="shrink-0 text-xs font-semibold text-secao-texto hover:underline"
                aria-label={`Baixar ${comprovacao.nomeOriginal}`}
              >
                Baixar
              </button>
            </li>
          ))}
        </ul>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}
    </li>
  )
}

function AnexarFoto({
  linha,
  aoFechar,
  aoAnexar,
}: {
  linha: LinhaAplicacao | null
  aoFechar: () => void
  aoAnexar: (linha: LinhaAplicacao, comprovacao: Comprovacao) => void
}) {
  const parceria = useParceria()
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [descricao, setDescricao] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const problema = arquivo ? problemaNaFoto(arquivo) : null

  async function enviar(evento: FormEvent) {
    evento.preventDefault()
    if (!linha) return
    if (!arquivo) return setFalha('Escolha a foto.')
    if (problema) return
    setEnviando(true)
    setFalha(null)
    try {
      aoAnexar(linha, await enviarComprovacao(parceria.id, linha.id, arquivo, descricao.trim() || undefined))
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (erro.campos.descricao ?? erro.campos.arquivo ?? erro.message) : 'Não foi possível anexar a foto.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal titulo="Anexar foto de comprovação" legenda={linha ? `${linha.formato} · ${parceria.patrocinador}` : undefined} aberto={linha !== null} aoFechar={aoFechar}>
      <form onSubmit={enviar} className="flex flex-col gap-4" data-secao="aplicacoes" noValidate>
        <label className="flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] border-dashed border-tinta bg-cinza-100 px-4 py-4 text-sm">
          <i className="ri-image-add-line text-2xl" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate font-semibold">{arquivo ? `${arquivo.name} · ${tamanho(arquivo.size)}` : 'Escolher a foto (PNG ou JPG, até 20 MB)'}</span>
          <input type="file" accept={EXTENSOES_FOTO} className="sr-only" onChange={(campo) => setArquivo(campo.target.files?.[0] ?? null)} />
        </label>
        <Campo
          rotulo="Descrição (opcional)"
          maxLength={200}
          placeholder="Ex.: testeira no palco principal, sexta-feira"
          value={descricao}
          onChange={(evento) => setDescricao(evento.target.value)}
        />
        {(problema ?? falha) && <Aviso tipo="falha" titulo={problema ?? falha ?? ''} />}
        <div className="flex flex-col-reverse gap-3 sm:grid sm:grid-cols-2 sm:gap-4">
          <Botao variante="secundario" onClick={aoFechar} className="min-w-0">
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} disabled={!arquivo || problema !== null} className="min-w-0">
            Anexar foto
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

function DadosDaAtivacao({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const dados = useDados(() => obterDados(parceria.id, 'APLICACOES'), [parceria.id])
  useRecarregarSeAbaFechou(dados)
  const [salvo, setSalvo] = useState(false)

  return (
    <Cartao titulo="Números da ativação">
      <p className="mb-4 text-sm font-medium text-texto-2">
        {rbs
          ? `Os números que só a marca sabe informar. O Atlas de ${parceria.patrocinador} os usa nas respostas, e eles ficam com o nome de quem informou e a data.`
          : 'Números que só a sua equipe ou a agência sabem. O Atlas os usa nas respostas, e eles ficam com o nome de quem informou e a data. Para corrigir, informe o valor novo: o anterior fica no histórico.'}
      </p>
      <Carregando dados={dados} vazio={(dado) => dado.campos.length === 0} textoVazio="Nenhum número para informar nesta aba.">
        {(dado) => (
          <FormularioDadosInformados
            campos={dado.campos.filter((campo) => campo.tipo !== 'ARQUIVO')}
            somenteMudados
            textoEnviar="Salvar números"
            aoTentar={() => setSalvo(false)}
            aoEnviar={async (valores) => {
              await informarDados(parceria.id, valores)
              setSalvo(true)
              dados.recarregar()
            }}
          />
        )}
      </Carregando>
      {salvo && <Aviso titulo="Números salvos" className="mt-4" />}
      {dados.dado?.campos
        .filter((campo) => campo.tipo === 'ARQUIVO')
        .map((campo) => <ArquivoDaAtivacao key={campo.chave} campo={campo} aoEnviar={dados.recarregar} />)}
    </Cartao>
  )
}

/** Um campo do tipo ARQUIVO (planilha da agência): fica só aqui, fora do cartão do Atlas e dos números do relatório. */
function ArquivoDaAtivacao({ campo, aoEnviar }: { campo: CampoComValor; aoEnviar: () => void }) {
  const parceria = useParceria()
  const entrada = useRef<HTMLInputElement>(null)
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const atual = campo.vigente?.arquivo ?? null

  async function enviar(arquivo: File) {
    setEnviando(true)
    setFalha(null)
    try {
      await enviarArquivoDoCampo(parceria.id, campo.chave, arquivo)
      aoEnviar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (erro.campos.arquivo ?? erro.message) : 'Não foi possível enviar a planilha.')
    } finally {
      setEnviando(false)
    }
  }

  async function abrir(arquivo: { id: number; nomeOriginal: string }) {
    setFalha(null)
    try {
      await baixarArquivo(arquivo)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar a planilha.')
    }
  }

  return (
    <div className="mt-5 flex flex-col gap-3 border-t border-borda pt-5">
      <div>
        <p className="text-sm font-semibold">{campo.rotulo}</p>
        {campo.ajuda && <p className="mt-0.5 text-xs font-medium text-texto-2">{campo.ajuda}</p>}
      </div>
      {atual && campo.vigente && (
        <LinhaArquivo
          titulo={atual.nomeOriginal}
          meta={`Enviada por ${campo.vigente.informadoPor?.nome ?? '—'} em ${dataHora(campo.vigente.informadoEm)}`}
          acoes={
            <Botao variante="secundario" icone="ri-download-line" className="min-w-0" onClick={() => abrir(atual)}>
              Baixar
            </Botao>
          }
        />
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div>
        <Botao variante="secundario" icone="ri-upload-2-line" carregando={enviando} onClick={() => entrada.current?.click()}>
          {atual ? 'Enviar outra planilha' : 'Enviar planilha'}
        </Botao>
        <input
          ref={entrada}
          type="file"
          accept=".xlsx,.xls,.csv"
          className="hidden"
          aria-label={campo.rotulo}
          onChange={(evento) => {
            const arquivo = evento.target.files?.[0]
            if (arquivo) void enviar(arquivo)
            evento.target.value = ''
          }}
        />
      </div>
    </div>
  )
}
