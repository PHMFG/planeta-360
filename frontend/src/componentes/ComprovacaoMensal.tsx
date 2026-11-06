// "Comprovação mensal" (requisitos R3.2 e R3.3 da RBS; o Figma 06 não desenha): o que foi exibido em cada mês, por tipo de mídia e
// unidade, a partir da veiculação carregada pela RBS. Só para as cotas Master e para a RBS; as outras cotas nem veem o cartão.
// [DUPLICIDADE] Unidades diferentes (inserções, CPM, aplicações) nunca se somam: cada total do mês é de uma unidade só.
import { useMemo, useState } from 'react'
import { ErroApi } from '../api/cliente'
import { baixarComprovacaoMensal, nomeDoMes, obterComprovacaoMensal, type LinhaDaComprovacao } from '../api/comprovacao'
import { useDados } from '../api/useDados'
import { useRecarregarSeAbaFechou } from '../paginas/parceria/ContextoParceria'
import { numero } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { Seletor } from './Formulario'
import { Tabela } from './Tabela'

export function ComprovacaoMensal({ parceriaId, patrocinador }: { parceriaId: number; patrocinador: string }) {
  const comprovacao = useDados(() => obterComprovacaoMensal(parceriaId), [parceriaId])
  useRecarregarSeAbaFechou(comprovacao)
  const [mes, setMes] = useState('')
  const [baixando, setBaixando] = useState<'xlsx' | 'csv' | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const meses = useMemo(() => [...new Set((comprovacao.dado?.linhas ?? []).map((linha) => linha.mes))], [comprovacao.dado])

  async function baixar(formato: 'xlsx' | 'csv') {
    setBaixando(formato)
    setFalha(null)
    try {
      await baixarComprovacaoMensal(parceriaId, patrocinador, formato, mes || undefined)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível baixar a comprovação.')
    } finally {
      setBaixando(null)
    }
  }

  return (
    <Cartao titulo="Comprovação mensal">
      <p className="mb-4 text-sm font-medium text-texto-2">O exibido de cada mês. A planilha baixada traz o mês escolhido.</p>
      <Carregando dados={comprovacao}>
        {({ linhas }) =>
          linhas.length === 0 ? (
            <Aviso tipo="ausente" titulo="Ainda sem exibição para comprovar">
              A comprovação aparece depois que a RBS carrega a planilha de veiculação dos veículos.
            </Aviso>
          ) : (
            <div className="flex flex-col gap-4">
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div className="w-full sm:w-64">
                  <Seletor rotulo="Mês" value={mes} onChange={(campo) => setMes(campo.target.value)}>
                    <option value="">Todos os meses ({meses.length})</option>
                    {meses.map((item) => (
                      <option key={item} value={item}>
                        {nomeDoMes(item)}
                      </option>
                    ))}
                  </Seletor>
                </div>
                <div className="flex flex-wrap gap-3">
                  <Botao icone="ri-file-excel-2-line" carregando={baixando === 'xlsx'} onClick={() => baixar('xlsx')}>
                    Baixar planilha (.xlsx)
                  </Botao>
                  <Botao variante="secundario" carregando={baixando === 'csv'} onClick={() => baixar('csv')}>
                    Baixar CSV
                  </Botao>
                </div>
              </div>
              {falha && <Aviso tipo="falha" titulo={falha} />}
              <Tabela<LinhaDaComprovacao>
                rotulo="Comprovação mensal"
                linhas={mes ? linhas.filter((linha) => linha.mes === mes) : linhas}
                chave={(linha) => `${linha.mes}-${linha.tipoMidia}-${linha.unidade}`}
                colunas={[
                  { titulo: 'Mês', celula: (linha) => <span className="font-semibold">{nomeDoMes(linha.mes)}</span> },
                  { titulo: 'Tipo de mídia', celula: (linha) => linha.tipoMidiaNome },
                  { titulo: 'Unidade', celula: (linha) => linha.unidadeNome },
                  { titulo: 'Exibido no mês', celula: (linha) => numero(linha.exibido), classe: 'text-right' },
                  { titulo: 'Previsto no plano (total)', celula: (linha) => numero(linha.previsto), classe: 'text-right' },
                ]}
              />
            </div>
          )
        }
      </Carregando>
    </Cartao>
  )
}
