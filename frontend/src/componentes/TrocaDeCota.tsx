// Troca de cota (requisito R6.d3 da RBS): a RBS escolhe a cota nova e vê, antes de confirmar, o que muda: as abas que fecham,
// o relatório já disponibilizado (que exige ciência), a pesquisa e a comprovação mensal, e o que NÃO muda sozinho
// (cortesias, credenciais, plano e investimento, que a RBS ajusta nas abas). A troca faz exatamente o que a prévia disse.
import { useState } from 'react'
import { ErroApi } from '../api/cliente'
import { previaDaTrocaDeCota, trocarCota, type CodigoDeCota, type ImpactoDaTroca, type MudancaDeValidade, type ParceriaDetalhe } from '../api/parcerias'
import { useDados } from '../api/useDados'
import { data } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Carregando } from './Carregando'
import { Seletor } from './Formulario'
import { Modal } from './Modal'
import { SECOES } from './secoes'

const COTAS: { codigo: CodigoDeCota; nome: string }[] = [
  { codigo: 'MASTER', nome: 'Master' },
  { codigo: 'SUBCOTA_G', nome: 'Subcota G' },
  { codigo: 'SUBCOTA', nome: 'Subcota' },
]

/** O nome da aba como o menu o mostra (a API diz "Pesquisa da ativação"; o menu, "Pesquisa"). */
export const nomeDaAba = (aba: { codigo: string; nome: string }) => SECOES.find((secao) => secao.aba === aba.codigo)?.nome ?? aba.nome

const VALIDADE: Record<MudancaDeValidade, string | null> = {
  DEIXA_DE_VALER: 'deixa de valer',
  PASSA_A_VALER: 'passa a valer',
  SEM_MUDANCA: null,
}

function Previa({ impacto }: { impacto: ImpactoDaTroca }) {
  const validade = [
    VALIDADE[impacto.pesquisa] && `A pesquisa da ativação ${VALIDADE[impacto.pesquisa]}.`,
    VALIDADE[impacto.comprovacaoMensal] && `A comprovação mensal ${VALIDADE[impacto.comprovacaoMensal]}.`,
  ].filter(Boolean) as string[]
  return (
    <div className="flex flex-col gap-3 text-sm" aria-live="polite">
      <p className="font-semibold">
        De {impacto.cotaAtual.nome} para {impacto.cotaNova.nome}
      </p>
      <div>
        <p className="font-semibold">Abas que fecham</p>
        <p>{impacto.abasQueFecham.length ? impacto.abasQueFecham.map(nomeDaAba).join(', ') : 'Nenhuma.'}</p>
      </div>
      {impacto.abasQuePassamAPoderAbrir.length > 0 && (
        <div>
          <p className="font-semibold">Abas que passam a poder abrir</p>
          <p>{impacto.abasQuePassamAPoderAbrir.map(nomeDaAba).join(', ')}. A troca não as abre: libere no Perfil, depois.</p>
        </div>
      )}
      {validade.length > 0 && <p>{validade.join(' ')}</p>}
      {impacto.naoMudamSozinhos.length > 0 && (
        <div>
          <p className="font-semibold">Não mudam sozinhos</p>
          <ul className="list-disc pl-5">
            {impacto.naoMudamSozinhos.map((item) => (
              <li key={item.aba}>{item.texto}</li>
            ))}
          </ul>
        </div>
      )}
      {impacto.avisos.map((aviso) => (
        <p key={aviso} className="text-texto-2">
          {aviso}
        </p>
      ))}
    </div>
  )
}

export function TrocaDeCota({ detalhe, aoFechar, aoTrocar }: { detalhe: ParceriaDetalhe; aoFechar: () => void; aoTrocar: (nova: ParceriaDetalhe, impacto: ImpactoDaTroca) => void }) {
  const opcoes = COTAS.filter((cota) => cota.codigo !== detalhe.cota.codigo)
  const [para, setPara] = useState<CodigoDeCota>(opcoes[0].codigo)
  const previa = useDados(() => previaDaTrocaDeCota(detalhe.id, para), [detalhe.id, para])
  const [ciente, setCiente] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const nomeNova = opcoes.find((cota) => cota.codigo === para)?.nome ?? ''
  const exigeCiencia = previa.dado?.exigeCiencia ?? false

  async function confirmar() {
    setFalha(null)
    setEnviando(true)
    try {
      const resposta = await trocarCota(detalhe.id, para, ciente)
      aoTrocar(resposta.parceria, resposta.impacto)
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível trocar a cota.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo="Trocar a cota" legenda={`${detalhe.patrocinador.nome} · hoje ${detalhe.cota.nome}`} aoFechar={aoFechar}>
      <div className="flex flex-col gap-4">
        <Seletor
          rotulo="Cota nova"
          value={para}
          onChange={(campo) => {
            setPara(campo.target.value as CodigoDeCota)
            setCiente(false)
          }}
        >
          {opcoes.map((cota) => (
            <option key={cota.codigo} value={cota.codigo}>
              {cota.nome}
            </option>
          ))}
        </Seletor>
        <Carregando dados={previa}>{(impacto) => <Previa impacto={impacto} />}</Carregando>
        {exigeCiencia && previa.dado?.relatorioDisponibilizado && (
          <label className="flex items-start gap-3 rounded-xl border border-borda p-3 text-sm">
            <input type="checkbox" className="mt-0.5 size-4" checked={ciente} onChange={(campo) => setCiente(campo.target.checked)} />
            <span>
              Estou ciente de que o relatório v{previa.dado.relatorioDisponibilizado.versao}, disponibilizado em{' '}
              {data(previa.dado.relatorioDisponibilizado.disponibilizadoEm)}, já está com a marca e traz a cota antiga no cabeçalho.
            </span>
          </label>
        )}
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao carregando={enviando} disabled={!previa.dado || (exigeCiencia && !ciente)} className="min-w-0 max-sm:flex-1" onClick={confirmar}>
            Trocar para {nomeNova}
          </Botao>
        </div>
      </div>
    </Modal>
  )
}
