// A marca importa a planilha da agência (plano da base da RBS, S1; rota 117): o mesmo fluxo da importação da RBS, restrito à
// parceria dela e aos números da ativação. O Atlas lê, a marca confere cada número com a célula de onde ele saiu e confirma.
import { useEffect, useRef, useState } from 'react'
import { ErroApi } from '../../api/cliente'
import type { CampoComValor } from '../../api/dados'
import {
  aceitarTodas,
  confirmarImportacao,
  descartarImportacao,
  importarPlanilhaDaAgencia,
  mudarLinha,
  obterImportacao,
  type ImportacaoCompleta,
  type LinhaDaImportacao,
} from '../../api/importacao'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { LeituraDoAtlas } from '../../componentes/LeituraDoAtlas'
import { Modal } from '../../componentes/Modal'
import { usarToast } from '../../componentes/Toast'
import { numero } from '../../util/formatar'

const ACEITOS = '.xlsx,.xls,.csv'

function valorDa(linha: LinhaDaImportacao) {
  const valor = linha.dados.valor
  return typeof valor === 'number' ? numero(valor) : (valor ?? '—')
}

export function ImportacaoDaAgencia({ parceriaId, campos, aoConfirmar }: { parceriaId: number; campos: CampoComValor[]; aoConfirmar: () => void }) {
  const entrada = useRef<HTMLInputElement>(null)
  const avisar = usarToast()
  const [enviando, setEnviando] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [importacao, setImportacao] = useState<ImportacaoCompleta | null>(null)
  const [agindo, setAgindo] = useState(false)
  const lendo = importacao?.situacao === 'LENDO'

  // Enquanto o Atlas lê, a janela consulta a cada 2 s (a única tela que não espera a volta à aba).
  useEffect(() => {
    if (!lendo || !importacao) return
    const timer = window.setInterval(() => {
      obterImportacao(importacao.id)
        // A resposta só vale para a importação ainda aberta: se a pessoa fechou a janela ou enviou outra planilha enquanto o
        // pedido voltava, ela não reabre a janela nem troca a importação.
        .then((lida) => setImportacao((atual) => (atual?.id === lida.id ? lida : atual)))
        .catch(() => undefined)
    }, 2000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lendo, importacao?.id])

  async function enviar(arquivo: File) {
    setEnviando(true)
    setFalha(null)
    try {
      const resumo = await importarPlanilhaDaAgencia(parceriaId, arquivo)
      setImportacao({ ...resumo, documentoId: null, etapa: 'LENDO_ARQUIVO', itens: [], naoEntendidos: [], resultado: null })
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (erro.campos.arquivo ?? erro.message) : 'Não foi possível enviar a planilha.')
    } finally {
      setEnviando(false)
    }
  }

  async function agir(acao: () => Promise<void>) {
    setAgindo(true)
    setFalha(null)
    try {
      await acao()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível concluir. Tente de novo.')
    } finally {
      setAgindo(false)
    }
  }

  const rotulo = (chave: unknown) => campos.find((campo) => campo.chave === chave)?.rotulo ?? String(chave ?? '—')
  const itens = importacao?.itens ?? []
  const validas = itens.filter((linha) => linha.situacao !== 'RECUSADA' && linha.situacao !== 'DESCARTADA')

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-3">
        <Botao variante="secundario" icone="ri-sparkling-line" carregando={enviando} onClick={() => entrada.current?.click()}>
          Ler os números de uma planilha
        </Botao>
        <span className="text-xs font-medium text-texto-2">O Atlas lê a planilha da agência e você confere antes de salvar.</span>
      </div>
      <input
        ref={entrada}
        type="file"
        accept={ACEITOS}
        className="hidden"
        aria-label="Planilha da agência para o Atlas ler"
        onChange={(evento) => {
          const arquivo = evento.target.files?.[0]
          if (arquivo) void enviar(arquivo)
          evento.target.value = ''
        }}
      />
      {falha && !importacao && <Aviso tipo="falha" titulo={falha} />}

      {importacao && (
        <Modal
          titulo="Números da planilha"
          legenda={importacao.arquivo.nome}
          aberto
          largura="max-w-[720px]"
          aoFechar={() => setImportacao(null)}
        >
          {lendo && <LeituraDoAtlas etapa={importacao.etapa} compacta />}
          {importacao.situacao === 'ERRO' && (
            <Aviso tipo="falha" titulo="Não deu para ler esta planilha">
              {importacao.erro ?? 'Tente salvar a planilha de novo (.xlsx) ou informe os números à mão.'}
            </Aviso>
          )}
          {importacao.situacao === 'EM_PREVIA' && (
            <div className="flex flex-col gap-3">
              {itens.length === 0 ? (
                <Aviso tipo="vazio" titulo="O Atlas não achou números da ativação nesta planilha" />
              ) : (
                <ul className="flex flex-col gap-2">
                  {itens.map((linha, indice) => {
                    const fora = linha.situacao === 'RECUSADA' || linha.situacao === 'DESCARTADA'
                    const origem = linha.origem.valor
                    return (
                      <li
                        key={linha.id}
                        className={`entrar-em-cascata flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3 text-sm ${fora ? 'border-borda bg-cinza-100 text-texto-2' : 'border-borda bg-white'}`}
                        style={{ '--ordem': indice } as React.CSSProperties}
                      >
                        <span className="min-w-0 flex-1">
                          <span className="block font-semibold">{rotulo(linha.dados.campo_chave)}</span>
                          {origem && (
                            <span className="block truncate text-xs text-texto-2">
                              {origem.localizador} · “{origem.trecho}”
                            </span>
                          )}
                          {linha.motivo && <span className="block text-xs font-semibold text-[#c8203f]">{linha.motivo}</span>}
                        </span>
                        <span className="text-base font-bold">{valorDa(linha)}</span>
                        {linha.situacao !== 'RECUSADA' && (
                          <button
                            type="button"
                            className="text-xs font-semibold text-texto-2 hover:underline"
                            onClick={() =>
                              agir(async () => {
                                await mudarLinha(importacao.id, linha.id, { situacao: fora ? 'ACEITA' : 'DESCARTADA' })
                                setImportacao(await obterImportacao(importacao.id))
                              })
                            }
                          >
                            {fora ? 'Incluir' : 'Deixar de fora'}
                          </button>
                        )}
                      </li>
                    )
                  })}
                </ul>
              )}
              {importacao.naoEntendidos.length > 0 && (
                <p className="text-xs font-medium text-texto-2">
                  {importacao.naoEntendidos.length} {importacao.naoEntendidos.length === 1 ? 'trecho ficou' : 'trechos ficaram'} sem entender; informe à mão o que faltar.
                </p>
              )}
              {falha && <Aviso tipo="falha" titulo={falha} />}
            <div className="flex flex-wrap justify-end gap-3">
              <Botao
                variante="secundario"
                disabled={agindo}
                onClick={() =>
                  agir(async () => {
                    await descartarImportacao(importacao.id)
                    setImportacao(null)
                    avisar('Leitura descartada; nada foi salvo.')
                  })
                }
              >
                Descartar
              </Botao>
              <Botao
                carregando={agindo}
                disabled={validas.length === 0}
                icone="ri-check-line"
                onClick={() =>
                  agir(async () => {
                    await aceitarTodas(importacao.id)
                    const pronta = await confirmarImportacao(importacao.id)
                    setImportacao(null)
                    avisar(`${pronta.resultado?.inseridas ?? validas.length} números salvos.`)
                    aoConfirmar()
                  })
                }
              >
                Salvar {validas.length === 1 ? 'o número' : `os ${validas.length} números`}
              </Botao>
            </div>
            </div>
          )}
        </Modal>
      )}
    </div>
  )
}
