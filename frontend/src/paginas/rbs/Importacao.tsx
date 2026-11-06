// Uma importação (plano da base da RBS, E3, E9 a E16): enquanto o Atlas lê, a orbe "pensa" e as etapas avançam; depois vem a
// prévia, linha a linha, com a origem de cada valor ao lado (a célula, o trecho, a imagem). Nada entra na base antes de a
// pessoa confirmar; o que a conferência recusou fica à vista, com o motivo.
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ErroApi, pedirArquivo } from '../../api/cliente'
import {
  CAMPOS_DO_DESTINO,
  NOME_DO_DESTINO,
  aceitarTodas,
  confirmarImportacao,
  descartarImportacao,
  mudarLinha,
  obterImportacao,
  obterOrigem,
  retirarImportacao,
  type ImportacaoCompleta,
  type LinhaDaImportacao,
  type OrigemParaVer,
} from '../../api/importacao'
import { obterConsolidacao } from '../../api/resultados'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { LeituraDoAtlas } from '../../componentes/LeituraDoAtlas'
import { Campo } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Esqueleto } from '../../componentes/Carregando'
import { Filtros, Seletor } from '../../componentes/Formulario'
import { Modal } from '../../componentes/Modal'
import { usarToast } from '../../componentes/Toast'
import { nomeDoMes } from '../../api/comprovacao'
import { dataHora, numero } from '../../util/formatar'
import { SituacaoDaImportacao } from './Importar'

type Visao = 'revisar' | 'aceitas' | 'recusadas' | 'descartadas' | 'naoEntendidos'


function valorNaTela(valor: string | number | null | undefined, ehNumero?: boolean) {
  if (valor === null || valor === undefined || valor === '') return '—'
  if (ehNumero && typeof valor === 'number') return numero(valor)
  // A data e hora da exibição vem em ISO ("2026-11-03T10:00:00-03:00"); o mês, em AAAA-MM.
  if (typeof valor === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(valor)) return dataHora(valor)
  if (typeof valor === 'string' && /^\d{4}-\d{2}$/.test(valor)) return nomeDoMes(valor)
  return String(valor)
}

/** O número com a palavra no singular ou no plural: "1 linha nova", "2 linhas novas". */
function contagem(valor: number, singular: string, plural: string) {
  return `${numero(valor)} ${valor === 1 ? singular : plural}`
}

/** A confirmação só diz que entrou o que a carga gravou (rodada A1: "entrou na base" com 0 linhas novas). */
function avisoDaConfirmacao(resposta: ImportacaoCompleta) {
  const novas = resposta.resultado?.inseridas ?? 0
  return novas > 0
    ? `Confirmado: ${contagem(novas, 'linha nova entrou', 'linhas novas entraram')} na base.`
    : 'Confirmado, mas nada novo entrou na base: veja o motivo de cada linha.'
}

export function Importacao() {
  const { importacaoId } = useParams()
  const id = Number(importacaoId)
  const avisar = usarToast()
  const dados = useDados(() => obterImportacao(id), [id])
  const [visao, setVisao] = useState<Visao>('revisar')
  const [origem, setOrigem] = useState<{ linha: LinhaDaImportacao; campo: string } | null>(null)
  const [editando, setEditando] = useState<LinhaDaImportacao | null>(null)
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const importacao = dados.dado

  // Enquanto lê, a tela consulta a cada 2 s (a única exceção à regra de "atualiza ao voltar para a aba").
  useEffect(() => {
    if (importacao?.situacao !== 'LENDO') return
    const timer = window.setInterval(() => dados.recarregar(), 2000)
    return () => window.clearInterval(timer)
  }, [importacao?.situacao, dados])

  async function agir<T>(nome: string, acao: () => Promise<T>, sucesso?: string | ((resposta: T) => string)) {
    setOcupado(nome)
    setFalha(null)
    try {
      const resposta = await acao()
      if (sucesso) avisar(typeof sucesso === 'function' ? sucesso(resposta) : sucesso)
      dados.recarregar()
    } catch (erro) {
      // A frase do 422 é genérica ("Confira os campos destacados"), e esta tela não tem campo: vale a mensagem do campo (rodada H1).
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não deu certo agora. Tente de novo.')
    } finally {
      setOcupado(null)
    }
  }

  if (dados.erro) {
    return (
      <div data-secao="neutro" className="flex flex-col gap-4">
        <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo="Importação" />
        <Aviso tipo="falha" titulo={dados.erro} />
      </div>
    )
  }
  if (!importacao) {
    return (
      <div data-secao="neutro" className="flex flex-col gap-4">
        <CabecalhoSecao trilha="Planeta Atlântida / RBS · Gestão" titulo="Importação" />
        <Cartao>
          <Esqueleto linhas={6} />
        </Cartao>
      </div>
    )
  }

  // As colunas que vêm vazias em todas as linhas (o mês, numa planilha de exibições) não aparecem: "—" em tudo confundia (H1).
  const campos = ((importacao.destino && CAMPOS_DO_DESTINO[importacao.destino]) || []).filter((campo) =>
    importacao.itens.some((linha) => linha.dados[campo.chave] !== null && linha.dados[campo.chave] !== undefined && linha.dados[campo.chave] !== ''),
  )
  const porVisao: Record<Exclude<Visao, 'naoEntendidos'>, LinhaDaImportacao[]> = {
    revisar: importacao.itens.filter((linha) => linha.situacao === 'PROPOSTA' || linha.situacao === 'EDITADA'),
    aceitas: importacao.itens.filter((linha) => linha.situacao === 'ACEITA'),
    recusadas: importacao.itens.filter((linha) => linha.situacao === 'RECUSADA'),
    descartadas: importacao.itens.filter((linha) => linha.situacao === 'DESCARTADA'),
  }
  const linhasDaVisao = visao === 'naoEntendidos' ? [] : porVisao[visao]
  const emPrevia = importacao.situacao === 'EM_PREVIA'

  return (
    // Com a gaveta da origem aberta, a página abre espaço à direita: a gaveta cobria o Confirmar (rodada H1).
    <div data-secao="neutro" className={`flex flex-col gap-4 transition-[padding] duration-(--duracao-media) ${origem ? 'lg:pr-[476px]' : ''}`}>
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo={importacao.arquivo.nome}
        subtitulo={`${importacao.destino ? NOME_DO_DESTINO[importacao.destino] : 'Destino a definir'} · ${
          importacao.parceria?.patrocinador ?? (importacao.cota ? `cota ${importacao.cota.nome}` : `todas as marcas de ${importacao.evento.edicao}`)
        } · enviada por ${importacao.criadaPor.nome} em ${dataHora(importacao.criadaEm)}`}
        acoes={
          <Link to="/rbs/importar" viewTransition className="text-[13px] font-semibold text-white hover:underline">
            ← Importações
          </Link>
        }
      />

      {importacao.situacao === 'LENDO' && <LeituraDoAtlas etapa={importacao.etapa} />}

      {importacao.situacao === 'ERRO' && (
        <Aviso tipo="falha" titulo="Não deu para ler este arquivo">
          {importacao.erro ?? 'O arquivo não pôde ser lido.'} Confira o arquivo e envie de novo em Importar.
        </Aviso>
      )}

      {/* E4: com suspeita de dado pessoal, o modelo não foi chamado; a prévia tem só o que a leitura em código entendeu. */}
      {importacao.situacao === 'EM_PREVIA' && (importacao.suspeitas?.length ?? 0) > 0 && (
        <Aviso tipo="restrito" titulo="O arquivo parece ter dado pessoal, e o Atlas não o mandou ao modelo">
          A prévia traz só o que a leitura em código entendeu. Suspeita em:{' '}
          {importacao.suspeitas!
            .map((item) => `${item.pagina ? `${importacao.arquivo.tipo === 'PDF' ? 'página' : 'parte'} ${item.pagina}` : 'arquivo'} (${item.tipo.toLowerCase()})`)
            .join(', ')}
          . Se o resto
          não veio, tire os dados pessoais do arquivo e importe de novo. Enquanto isso, o arquivo fica aguardando revisão em
          Documentos, e o Atlas não o consulta nem o cita.
        </Aviso>
      )}

      {/* Word, PDF e texto sem o modelo (ou um arquivo que não traz números) ficam só para consulta: a tela diz o que houve (H1). */}
      {importacao.situacao === 'EM_PREVIA' && importacao.destino === 'SO_CONSULTA' && importacao.itens.length === 0 && (importacao.suspeitas?.length ?? 0) === 0 && (
        <Aviso tipo="info" titulo="Guardado só para consulta: o Atlas não tirou linhas deste arquivo">
          O arquivo já está nos documentos que o Atlas consulta e cita. Para virar números na base, Word, PowerPoint, PDF e texto
          precisam do modelo de linguagem ligado; sem ele, só as planilhas e o CSV no formato que o código entende viram linhas.
          Descarte esta importação se o arquivo não precisar ficar como consulta.
        </Aviso>
      )}

      {importacao.situacao === 'CONFIRMADA' && importacao.resultado && (
        <Cartao titulo={importacao.resultado.inseridas > 0 ? 'Entrou na base' : 'Nada novo entrou na base'}>
          <p className="text-sm">
            <span className="font-semibold">{contagem(importacao.resultado.inseridas, 'linha nova', 'linhas novas')}</span>
            <span className="text-texto-2">
              {' '}
              · {contagem(importacao.resultado.duplicadas, 'já estava na base', 'já estavam na base')} ·{' '}
              {contagem(importacao.resultado.rejeitadas, 'recusada na carga', 'recusadas na carga')}
            </span>
          </p>
          {importacao.resultado.rejeicoes.length > 0 && (
            <ul className="mt-3 flex flex-col gap-1 text-xs text-texto-2">
              {importacao.resultado.rejeicoes.map((item) => (
                <li key={item.ordem}>
                  Linha {item.ordem}: {item.motivo}
                </li>
              ))}
            </ul>
          )}
          <div className="mt-4 flex flex-wrap items-center gap-4">
            {importacao.destino === 'VEICULACAO' && (
              <Link to="/rbs/dados" viewTransition className="text-[13px] font-semibold text-secao-texto hover:underline">
                Ver em Dados →
              </Link>
            )}
            <Botao
              variante="secundario"
              carregando={ocupado === 'retirar'}
              onClick={() => {
                if (window.confirm('Retirar esta importação? As linhas que ela gravou saem da base. Para corrigir, importe de novo.'))
                  void agir('retirar', () => retirarImportacao(id), 'Importação retirada.')
              }}
            >
              Retirar importação
            </Botao>
          </div>
        </Cartao>
      )}

      {(importacao.situacao === 'DESCARTADA' || importacao.situacao === 'RETIRADA') && (
        <Aviso tipo="vazio" titulo={importacao.situacao === 'DESCARTADA' ? 'Importação descartada: nada entrou na base.' : `Importação retirada em ${dataHora(importacao.retiradaEm)}.`} />
      )}

      {importacao.situacao !== 'LENDO' && importacao.situacao !== 'ERRO' && (
        <Cartao
          titulo={emPrevia ? 'Revise antes de entrar' : 'Linhas'}
          extra={<SituacaoDaImportacao importacao={importacao} />}
        >
          {emPrevia && (
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-texto-2">
                <span className="font-semibold text-tinta">{porVisao.revisar.length} para revisar</span> · {porVisao.aceitas.length} aceitas ·{' '}
                {porVisao.recusadas.length} recusadas pela conferência · {importacao.naoEntendidos.length} trechos não entendidos
              </p>
              <div className="flex flex-wrap gap-3">
                <Botao variante="secundario" carregando={ocupado === 'descartar'} onClick={() => agir('descartar', () => descartarImportacao(id), 'Importação descartada.')}>
                  Descartar
                </Botao>
                <Botao
                  variante="secundario"
                  carregando={ocupado === 'todas'}
                  disabled={porVisao.revisar.length === 0}
                  onClick={() => agir('todas', () => aceitarTodas(id), 'Linhas aceitas.')}
                >
                  Aceitar as {porVisao.revisar.length}
                </Botao>
                <Botao
                  icone="ri-check-double-line"
                  carregando={ocupado === 'confirmar'}
                  disabled={porVisao.aceitas.length === 0}
                  onClick={() => agir('confirmar', () => confirmarImportacao(id), avisoDaConfirmacao)}
                >
                  Confirmar {porVisao.aceitas.length || ''}
                </Botao>
              </div>
            </div>
          )}
          {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
          <div className="mb-4">
            <Filtros<Visao>
              valor={visao}
              aoMudar={setVisao}
              opcoes={[
                { valor: 'revisar', nome: 'Para revisar', total: porVisao.revisar.length },
                { valor: 'aceitas', nome: 'Aceitas', total: porVisao.aceitas.length },
                { valor: 'recusadas', nome: 'Recusadas', total: porVisao.recusadas.length },
                { valor: 'descartadas', nome: 'Descartadas', total: porVisao.descartadas.length },
                { valor: 'naoEntendidos', nome: 'Não entendidos', total: importacao.naoEntendidos.length },
              ]}
            />
          </div>

          {visao === 'naoEntendidos' ? (
            importacao.naoEntendidos.length === 0 ? (
              <Aviso tipo="vazio" titulo="O Atlas usou todos os trechos do arquivo." />
            ) : (
              <ul className="flex flex-col gap-2 text-sm">
                {importacao.naoEntendidos.map((item, indice) => (
                  <li key={indice} className="rounded-xl border border-borda px-4 py-3">
                    <span className="font-mono text-xs text-texto-2">{item.localizador}</span> · “{item.trecho}”
                    <span className="block text-xs text-texto-2">{item.motivo}</span>
                  </li>
                ))}
              </ul>
            )
          ) : linhasDaVisao.length === 0 ? (
            <Aviso tipo="vazio" titulo="Nenhuma linha aqui." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full border-separate border-spacing-0 text-sm">
                <thead>
                  <tr>
                    <th scope="col" className="bg-cinza-100 px-3 py-2.5 text-left font-semibold first:rounded-l-[10px]">
                      #
                    </th>
                    {campos.map((campo) => (
                      <th key={campo.chave} scope="col" className={`bg-cinza-100 px-3 py-2.5 font-semibold ${campo.numero ? 'text-right' : 'text-left'}`}>
                        {campo.rotulo}
                      </th>
                    ))}
                    {importacao.destino === 'VEICULACAO' && (
                      <th scope="col" className="bg-cinza-100 px-3 py-2.5 text-left font-semibold">
                        Linha do plano
                      </th>
                    )}
                    <th scope="col" className="bg-cinza-100 px-3 py-2.5 text-right font-semibold last:rounded-r-[10px]">
                      <span className="sr-only">Ações</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {linhasDaVisao.map((linha, indice) => (
                    <tr key={linha.id} className="entrar-em-cascata align-top" style={{ '--ordem': indice } as React.CSSProperties}>
                      <td className="border-b border-borda px-3 py-2.5 text-xs text-texto-2">{linha.ordem}</td>
                      {campos.map((campo) => {
                        const daFonte = linha.origem[campo.chave]
                        return (
                          <td key={campo.chave} className={`border-b border-borda px-3 py-2.5 ${campo.numero ? 'text-right' : ''}`}>
                            {daFonte ? (
                              <button
                                type="button"
                                onClick={() => setOrigem({ linha, campo: campo.chave })}
                                className="group inline-flex items-center gap-1 rounded-md px-1 hover:bg-cinza-100"
                                title={`De onde veio: ${daFonte.localizador}`}
                              >
                                {valorNaTela(linha.dados[campo.chave], campo.numero)}
                                <i className="ri-map-pin-2-line text-xs text-texto-2 opacity-60 group-hover:opacity-100" aria-hidden="true" />
                                <span className="sr-only">, ver de onde veio ({daFonte.localizador})</span>
                              </button>
                            ) : (
                              valorNaTela(linha.dados[campo.chave], campo.numero)
                            )}
                          </td>
                        )
                      })}
                      {importacao.destino === 'VEICULACAO' && (
                        <td className="border-b border-borda px-3 py-2.5 text-xs">
                          {linha.aproveitamento ? (
                            <span className="font-semibold">{linha.aproveitamento.formato}</span>
                          ) : linha.aproveitamentoSugerido ? (
                            <span>
                              {linha.aproveitamentoSugerido.formato}
                              <span className="text-texto-2">
                                {' '}
                                · {linha.aproveitamentoSugerido.semelhanca >= 1 ? 'igual ao plano' : `parecido com o plano (${Math.round(linha.aproveitamentoSugerido.semelhanca * 100)}%)`}
                              </span>
                            </span>
                          ) : (
                            <span className="text-[#c8203f]">sem linha do plano</span>
                          )}
                        </td>
                      )}
                      <td className="border-b border-borda px-3 py-2.5 text-right whitespace-nowrap">
                        {emPrevia && (
                          <div className="flex justify-end gap-3 text-xs font-bold">
                            <button type="button" className="hover:underline" onClick={() => setEditando(linha)}>
                              Editar
                            </button>
                            {linha.situacao !== 'ACEITA' && linha.situacao !== 'RECUSADA' && (
                              <button type="button" className="text-aplicacoes hover:underline" onClick={() => agir(`linha-${linha.id}`, () => mudarLinha(id, linha.id, { situacao: 'ACEITA' }))}>
                                Aceitar
                              </button>
                            )}
                            {linha.situacao !== 'DESCARTADA' && (
                              <button type="button" className="text-[#c8203f] hover:underline" onClick={() => agir(`linha-${linha.id}`, () => mudarLinha(id, linha.id, { situacao: 'DESCARTADA' }))}>
                                Descartar
                              </button>
                            )}
                          </div>
                        )}
                        {linha.motivo && <p className="mt-1 max-w-64 text-left text-xs whitespace-normal text-[#c8203f]">{linha.motivo}</p>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Cartao>
      )}

      {origem && <PainelDaOrigem importacaoId={id} {...origem} aoFechar={() => setOrigem(null)} />}
      {editando && (
        <EdicaoDaLinha
          importacao={importacao}
          linha={editando}
          aoFechar={() => setEditando(null)}
          aoSalvar={(salva) => {
            setEditando(null)
            dados.recarregar()
            // O valor editado precisa estar na fonte: se não está, a linha vai para "Recusadas", e a pessoa precisa saber já (H1).
            if (salva.situacao === 'RECUSADA') avisar(`Linha ${salva.ordem} recusada: ${salva.motivo ?? 'o valor não confere com a fonte.'}`, 'falha')
          }}
        />
      )}
    </div>
  )
}

/** A leitura em andamento: a orbe pensa e as três etapas acendem uma a uma. */
/** O painel da origem: a célula marcada na planilha, o trecho no texto ou a imagem, ao lado da prévia. */
function PainelDaOrigem({ importacaoId, linha, campo, aoFechar }: { importacaoId: number; linha: LinhaDaImportacao; campo: string; aoFechar: () => void }) {
  const origem = useDados<OrigemParaVer>(() => obterOrigem(importacaoId, linha.id, campo), [importacaoId, linha.id, campo])
  const [imagem, setImagem] = useState<string | null>(null)
  const tipo = origem.dado?.tipo ?? ''
  const ehImagem = ['PNG', 'JPG', 'JPEG'].includes(tipo)

  useEffect(() => {
    if (!ehImagem || !origem.dado) return
    let url: string | null = null
    const controle = new AbortController()
    pedirArquivo(`/arquivos/${origem.dado.arquivoId}`, controle.signal)
      .then((blob) => {
        url = URL.createObjectURL(blob)
        setImagem(url)
      })
      .catch(() => setImagem(null))
    return () => {
      controle.abort()
      if (url) URL.revokeObjectURL(url)
    }
  }, [ehImagem, origem.dado])

  useEffect(() => {
    const fechar = (evento: KeyboardEvent) => evento.key === 'Escape' && aoFechar()
    window.addEventListener('keydown', fechar)
    return () => window.removeEventListener('keydown', fechar)
  }, [aoFechar])

  return (
    <aside
      role="dialog"
      aria-label="De onde veio o valor"
      className="fixed top-4 right-4 bottom-4 z-40 flex w-[min(460px,calc(100vw-32px))] animate-deslizar flex-col gap-4 overflow-y-auto rounded-[20px] border border-borda bg-white p-6 shadow-modal"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="texto-sobretitulo text-texto-2">De onde veio</p>
          <h2 className="mt-1 text-sm font-bold">
            Linha {linha.ordem} · {campo}
          </h2>
        </div>
        <button type="button" onClick={aoFechar} className="flex items-center gap-1 text-[13px] font-semibold">
          Fechar <i className="ri-close-line text-lg" aria-hidden="true" />
        </button>
      </div>
      {origem.erro && <Aviso tipo="falha" titulo={origem.erro} />}
      {!origem.dado && !origem.erro && <Esqueleto linhas={6} />}
      {origem.dado && (
        <>
          <p className="font-mono text-xs text-texto-2">{origem.dado.localizador}</p>
          {origem.dado.planilha && <GradeDaPlanilha planilha={origem.dado.planilha} />}
          {origem.dado.texto && (
            <p className="rounded-xl bg-cinza-100 p-4 text-sm leading-relaxed whitespace-pre-wrap">
              <span className="text-texto-2">{origem.dado.texto.antes}</span>
              <mark className="rounded bg-estado-ausente/60 px-0.5 font-semibold">{origem.dado.texto.trecho}</mark>
              <span className="text-texto-2">{origem.dado.texto.depois}</span>
            </p>
          )}
          {ehImagem && (imagem ? <img src={imagem} alt={`Imagem enviada: ${linha.dados.veiculo ?? 'comprovante'}`} className="w-full rounded-xl border border-borda" /> : <Esqueleto linhas={8} />)}
          {origem.dado.pagina && <p className="text-xs text-texto-2">Página {origem.dado.pagina}</p>}
        </>
      )}
    </aside>
  )
}

const LETRAS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

function letraMais(letra: string, quanto: number) {
  const base = LETRAS.indexOf(letra.toUpperCase())
  return base < 0 ? '?' : (LETRAS[base + quanto] ?? '?')
}

/** A grade em volta da célula citada, com a célula marcada. */
function GradeDaPlanilha({ planilha }: { planilha: NonNullable<OrigemParaVer['planilha']> }) {
  const alvoColuna = planilha.celula.replace(/\d+/g, '')
  const alvoLinha = Number(planilha.celula.replace(/\D+/g, ''))
  return (
    <div className="overflow-x-auto rounded-xl border border-borda">
      <p className="border-b border-borda bg-cinza-100 px-3 py-1.5 text-xs font-semibold">{planilha.aba}</p>
      <table className="text-xs">
        <thead>
          <tr>
            <th className="bg-cinza-100 px-2 py-1" />
            {(planilha.grade[0] ?? []).map((_, coluna) => (
              <th key={coluna} className="bg-cinza-100 px-2 py-1 font-semibold text-texto-2">
                {letraMais(planilha.colunaInicial, coluna)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {planilha.grade.map((linha, indice) => (
            <tr key={indice}>
              <th className="bg-cinza-100 px-2 py-1 font-semibold text-texto-2">{planilha.linhaInicial + indice}</th>
              {linha.map((valor, coluna) => {
                const alvo = planilha.linhaInicial + indice === alvoLinha && letraMais(planilha.colunaInicial, coluna) === alvoColuna
                return (
                  <td key={coluna} className={`max-w-40 truncate border border-borda px-2 py-1 ${alvo ? 'bg-estado-ausente/60 font-bold outline-2 outline-[#7a5a00]' : ''}`}>
                    {valor ?? ''}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Editar uma linha da prévia: os campos do destino e, na veiculação, a linha do plano da marca. */
function EdicaoDaLinha({
  importacao,
  linha,
  aoFechar,
  aoSalvar,
}: {
  importacao: ImportacaoCompleta
  linha: LinhaDaImportacao
  aoFechar: () => void
  aoSalvar: (salva: LinhaDaImportacao) => void
}) {
  const campos = (importacao.destino && CAMPOS_DO_DESTINO[importacao.destino]) || []
  const [valores, setValores] = useState<Record<string, string>>(() =>
    Object.fromEntries(campos.map((campo) => [campo.chave, linha.dados[campo.chave] === null || linha.dados[campo.chave] === undefined ? '' : String(linha.dados[campo.chave])])),
  )
  const [aproveitamentoId, setAproveitamentoId] = useState(String(linha.aproveitamento?.id ?? linha.aproveitamentoSugerido?.id ?? ''))
  const plano = useDados(
    () => (importacao.destino === 'VEICULACAO' && linha.parceria ? obterConsolidacao(linha.parceria.id).then((dado) => dado.midia.linhas) : Promise.resolve([])),
    [importacao.destino, linha.parceria?.id],
  )
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [salvando, setSalvando] = useState(false)

  async function salvar() {
    setSalvando(true)
    setFalha(null)
    const mudados = Object.fromEntries(
      campos
        .filter((campo) => valores[campo.chave] !== (linha.dados[campo.chave] === null || linha.dados[campo.chave] === undefined ? '' : String(linha.dados[campo.chave])))
        .map((campo) => [campo.chave, valores[campo.chave] === '' ? null : campo.numero ? Number(valores[campo.chave].replace(/\./g, '').replace(',', '.')) : valores[campo.chave]]),
    )
    try {
      const salva = await mudarLinha(importacao.id, linha.id, {
        dados: Object.keys(mudados).length ? mudados : undefined,
        aproveitamentoId: aproveitamentoId && Number(aproveitamentoId) !== linha.aproveitamento?.id ? Number(aproveitamentoId) : undefined,
      })
      aoSalvar(salva)
    } catch (erro) {
      if (erro instanceof ErroApi) {
        setErros(Object.fromEntries(Object.entries(erro.campos).map(([chave, texto]) => [chave.replace(/^dados\./, ''), texto])))
        setFalha(erro.message)
      } else setFalha('Não foi possível salvar a linha.')
    } finally {
      setSalvando(false)
    }
  }

  return (
    <Modal titulo={`Editar a linha ${linha.ordem}`} legenda="A conferência roda de novo ao salvar." aberto aoFechar={aoFechar} largura="max-w-[640px]">
      <div className="grid gap-3 sm:grid-cols-2">
        {campos.map((campo) => (
          <Campo
            key={campo.chave}
            rotulo={campo.rotulo}
            inputMode={campo.numero ? 'decimal' : undefined}
            value={valores[campo.chave]}
            onChange={(evento) => setValores((atuais) => ({ ...atuais, [campo.chave]: evento.target.value }))}
            erro={erros[campo.chave]}
          />
        ))}
      </div>
      {importacao.destino === 'VEICULACAO' && (
        <Seletor rotulo="Linha do plano" value={aproveitamentoId} onChange={(evento) => setAproveitamentoId(evento.target.value)} erro={erros.aproveitamentoId}>
          <option value="">Escolha a linha do plano</option>
          {(plano.dado ?? []).map((item) => (
            <option key={item.aproveitamentoId} value={item.aproveitamentoId}>
              {item.veiculo} · {item.formato}
              {item.espacoPrograma ? ` · ${item.espacoPrograma}` : ''}
            </option>
          ))}
        </Seletor>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div className="flex flex-col-reverse gap-3 sm:grid sm:grid-cols-2 sm:gap-4">
        <Botao variante="secundario" onClick={aoFechar} className="min-w-0">
          Cancelar
        </Botao>
        <Botao carregando={salvando} onClick={salvar} className="min-w-0">
          Salvar linha
        </Botao>
      </div>
    </Modal>
  )
}
