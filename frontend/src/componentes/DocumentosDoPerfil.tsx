// Os documentos do Perfil (Figma P01; requisito R5.1 da RBS): o plano comercial que a RBS sobe, com versões, e a compra de
// ingressos com valor de patrocinador, em que cada solicitação da marca tem a sua situação.
import { useRef, useState } from 'react'
import { ErroApi } from '../api/cliente'
import {
  enviarAoEspaco,
  enviarPlanoComercial,
  obterEspacos,
  obterPlanoComercial,
  publicarFormularioDeIngressos,
  type VersaoAvulsa,
} from '../api/espacos'
import { baixarArquivo, mudarStatusEntrega, type Entrega } from '../api/materiais'
import { useDados } from '../api/useDados'
import { useRecarregarSeAbaFechou } from '../paginas/parceria/ContextoParceria'
import { dataHora, tamanho } from '../util/formatar'
import { LinhaArquivo } from './Arquivo'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { Tabela } from './Tabela'

/** Escolher arquivo com um botão (sem a área de arrastar): devolve o arquivo escolhido e limpa o campo. */
function BotaoDeArquivo({ rotulo, espaco, aceitar, aoEscolher, carregando, variante, desabilitado }: { rotulo: string; espaco: string; aceitar?: string; aoEscolher: (arquivo: File) => void; carregando?: boolean; variante?: 'primario' | 'secundario'; desabilitado?: boolean }) {
  const campo = useRef<HTMLInputElement>(null)
  return (
    <>
      <Botao variante={variante} carregando={carregando} disabled={desabilitado} onClick={() => campo.current?.click()}>
        {rotulo}
      </Botao>
      <input
        ref={campo}
        type="file"
        accept={aceitar}
        className="sr-only"
        tabIndex={-1}
        // O campo de arquivo é exposto como botão: com nome, o leitor de tela ouvia o mesmo botão duas vezes.
        aria-hidden="true"
        data-espaco={espaco}
        onChange={(evento) => {
          const arquivo = evento.target.files?.[0]
          evento.target.value = ''
          if (arquivo) aoEscolher(arquivo)
        }}
      />
    </>
  )
}

export function PlanoComercialDoPerfil({ parceriaId, rbs }: { parceriaId: number; rbs: boolean }) {
  const plano = useDados(() => obterPlanoComercial(parceriaId), [parceriaId])
  useRecarregarSeAbaFechou(plano)
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  async function enviar(arquivo: File) {
    setEnviando(true)
    setFalha(null)
    setFeito(null)
    try {
      const versao = await enviarPlanoComercial(parceriaId, arquivo)
      setFeito(`Plano enviado como versão ${versao.versao}. A marca vê ao abrir o Perfil.`)
      plano.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível enviar o plano.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Plano comercial">
      <Carregando dados={plano}>
        {({ versoes }) => {
          const [vigente, ...anteriores] = versoes
          return (
            <div className="flex flex-col gap-3">
              {vigente ? (
                <LinhaArquivo
                  titulo={vigente.nomeOriginal}
                  meta={`Publicado pela RBS · versão ${vigente.versao} · ${dataHora(vigente.enviadoEm)}`}
                  rodape={<span className="font-semibold text-secao-texto">Somente consulta</span>}
                  acoes={
                    <button type="button" className="text-xs font-bold hover:underline" onClick={() => baixarArquivo(vigente)}>
                      Baixar plano
                    </button>
                  }
                />
              ) : (
                <Aviso tipo="ausente" titulo={rbs ? 'O plano desta parceria ainda não foi enviado' : 'A RBS ainda não enviou o plano da sua cota'} />
              )}
              {anteriores.length > 0 && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-semibold">Versões anteriores ({anteriores.length})</summary>
                  <ul className="mt-2 flex flex-col gap-1">
                    {anteriores.map((versao: VersaoAvulsa) => (
                      <li key={versao.id} className="flex items-center justify-between gap-3">
                        <span>
                          v{versao.versao} · {versao.nomeOriginal} · {dataHora(versao.enviadoEm)}
                        </span>
                        <button type="button" className="text-xs font-bold hover:underline" onClick={() => baixarArquivo(versao)} aria-label={`Baixar a versão ${versao.versao} do plano`}>
                          Baixar
                        </button>
                      </li>
                    ))}
                  </ul>
                </details>
              )}
              {rbs && (
                <div>
                  <BotaoDeArquivo rotulo={vigente ? 'Enviar nova versão do plano' : 'Enviar plano comercial'} espaco="PLANO_COMERCIAL" aceitar=".pdf,application/pdf" aoEscolher={enviar} carregando={enviando} />
                  <p className="mt-2 text-xs font-medium text-texto-2">PDF, até 20 MB.</p>
                </div>
              )}
              {falha && <Aviso tipo="falha" titulo={falha} />}
              {feito && (
                <p className="text-sm font-semibold text-secao-texto" role="status">
                  {feito}
                </p>
              )}
            </div>
          )
        }}
      </Carregando>
    </Cartao>
  )
}

const SITUACAO_DA_SOLICITACAO: Record<string, string> = {
  ENVIADO: 'Em análise pela RBS',
  EM_ANALISE: 'Em análise pela RBS',
  APROVADO: 'Confirmada',
  AJUSTE_SOLICITADO: 'Ajuste solicitado',
}

export function CompraDeIngressos({ parceriaId, rbs }: { parceriaId: number; rbs: boolean }) {
  const espacos = useDados(() => obterEspacos(parceriaId, 'PERFIL'), [parceriaId])
  useRecarregarSeAbaFechou(espacos)
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  async function acao(fazer: () => Promise<unknown>, texto: string, erroPadrao: string) {
    setEnviando(true)
    setFalha(null)
    setFeito(null)
    try {
      await fazer()
      setFeito(texto)
      espacos.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : erroPadrao)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Compra de ingressos com valor de patrocinador">
      <p className="mb-4 text-sm font-medium text-texto-2">
        Ingressos extras, além das cortesias da cota. Baixe o formulário, preencha e envie; a RBS confirma cada solicitação.
      </p>
      <Carregando dados={espacos}>
        {({ formularioIngressos, solicitacoesIngressos }) => {
          const formulario = formularioIngressos.versoes[0]
          return (
            <div className="flex flex-col gap-4">
              {solicitacoesIngressos.length > 0 && (
                <Tabela<Entrega>
                  rotulo="Solicitações de ingressos"
                  linhas={solicitacoesIngressos}
                  chave={(solicitacao) => solicitacao.id}
                  colunas={[
                    { titulo: 'Solicitação', celula: (solicitacao) => <span className="font-semibold">{solicitacao.titulo}</span> },
                    { titulo: 'Enviado por', celula: (solicitacao) => solicitacao.arquivos[0]?.enviadoPor?.nome ?? '—' },
                    { titulo: 'Data', celula: (solicitacao) => dataHora(solicitacao.arquivos[0]?.enviadoEm ?? solicitacao.criadoEm) },
                    {
                      titulo: 'Arquivo',
                      celula: (solicitacao) =>
                        solicitacao.arquivos[0] ? (
                          <button type="button" className="font-semibold hover:underline" onClick={() => baixarArquivo(solicitacao.arquivos[0])}>
                            {solicitacao.arquivos[0].nomeOriginal} · {tamanho(solicitacao.arquivos[0].tamanhoBytes)}
                          </button>
                        ) : (
                          '—'
                        ),
                    },
                    {
                      titulo: 'Situação',
                      celula: (solicitacao) =>
                        rbs && (solicitacao.status === 'ENVIADO' || solicitacao.status === 'EM_ANALISE') ? (
                          <span className="flex items-center gap-2">
                            {SITUACAO_DA_SOLICITACAO[solicitacao.status] ?? solicitacao.status}
                            <button
                              type="button"
                              className="text-xs font-bold text-secao-texto hover:underline"
                              aria-label={`Confirmar a ${solicitacao.titulo}`}
                              onClick={() => acao(() => mudarStatusEntrega(solicitacao.id, 'APROVADO'), `${solicitacao.titulo} confirmada.`, 'Não foi possível confirmar.')}
                            >
                              Confirmar
                            </button>
                          </span>
                        ) : (
                          SITUACAO_DA_SOLICITACAO[solicitacao.status] ?? solicitacao.status
                        ),
                    },
                  ]}
                />
              )}
              <div className="flex flex-wrap gap-3">
                {formulario ? (
                  <Botao variante="secundario" onClick={() => baixarArquivo(formulario)}>
                    Baixar formulário
                  </Botao>
                ) : (
                  <p className="self-center text-sm font-medium text-texto-2">A RBS ainda não publicou o formulário.</p>
                )}
                {!rbs && (
                  <BotaoDeArquivo
                    espaco="DOCUMENTO_INGRESSOS"
                    rotulo="Enviar solicitação"
                    aoEscolher={(arquivo) => acao(() => enviarAoEspaco(parceriaId, 'DOCUMENTO_INGRESSOS', arquivo), 'Solicitação enviada. A RBS confere e confirma.', 'Não foi possível enviar a solicitação.')}
                    carregando={enviando}
                    // A solicitação é o formulário preenchido: sem o formulário publicado, não há o que enviar.
                    desabilitado={!formulario}
                  />
                )}
                {rbs && (
                  <BotaoDeArquivo
                    espaco="FORMULARIO_INGRESSOS"
                    variante="secundario"
                    rotulo={formulario ? 'Publicar nova versão do formulário' : 'Publicar formulário'}
                    aoEscolher={(arquivo) => acao(() => publicarFormularioDeIngressos(parceriaId, arquivo), 'Formulário publicado. A marca já pode baixar.', 'Não foi possível publicar o formulário.')}
                    carregando={enviando}
                  />
                )}
              </div>
              {falha && <Aviso tipo="falha" titulo={falha} />}
              {feito && (
                <p className="text-sm font-semibold text-secao-texto" role="status">
                  {feito}
                </p>
              )}
            </div>
          )
        }}
      </Carregando>
    </Cartao>
  )
}
