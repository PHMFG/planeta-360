// "Direitos por contrato" (Figma R02; R6.d2 do plano): o resumo, para a RBS, do que a cota dá à parceria (cortesias,
// credenciais e pesquisa), com a situação da aba de cada um e o link para a aba onde se edita (D10: a edição fica na aba).
import { Link } from 'react-router'
import { NOME_DIA, NOME_SETOR, obterCortesias } from '../api/cortesias'
import { NOME_BENEFICIARIO, NOME_SETOR_CREDENCIAL, obterCredenciais } from '../api/credenciamento'
import type { ParceriaResumo } from '../api/tipos'
import { useDados } from '../api/useDados'
import { numero } from '../util/formatar'
import { estadoAbaNaParceria, type EstadoAba } from './AbaFechada'
import { Aviso } from './Aviso'
import { Cartao } from './Cartao'
import { Tabela } from './Tabela'

const VISIBILIDADE: Record<EstadoAba, string> = {
  LIBERADA: 'Liberada',
  FECHADA: 'Fechada',
  OCULTA: 'Oculta',
  FORA_DA_COTA: 'Fora da cota',
}

type Linha = { chave: string; beneficio: string; quantidade: string; visibilidade: string; editar: string; destino: string }

export function DireitosPorContrato({ parceria }: { parceria: ParceriaResumo }) {
  const dados = useDados(
    () => Promise.all([obterCortesias(parceria.id), obterCredenciais(parceria.id)]),
    [parceria.id],
  )
  const base = `/parceria/${parceria.id}`
  const estadoCortesias = VISIBILIDADE[estadoAbaNaParceria(parceria, 'CORTESIAS')]
  const estadoCredenciamento = VISIBILIDADE[estadoAbaNaParceria(parceria, 'CREDENCIAMENTO')]
  const master = parceria.cota.codigo === 'MASTER'

  const linhas: Linha[] = dados.dado
    ? [
        ...dados.dado[0].cotas.map((celula) => ({
          chave: `cortesia-${celula.id}`,
          beneficio: `Cortesias · ${NOME_SETOR[celula.setor]} · ${NOME_DIA[celula.dia]}`,
          quantidade: numero(celula.cota),
          visibilidade: estadoCortesias,
          editar: 'Cortesias',
          destino: `${base}/cortesias`,
        })),
        ...dados.dado[1].credenciais.map((credencial) => ({
          chave: `credencial-${credencial.id}`,
          beneficio: `Credenciais · ${NOME_SETOR_CREDENCIAL[credencial.setor]} · ${NOME_BENEFICIARIO[credencial.beneficiario]}`,
          quantidade: `${numero(credencial.quantidadePorDia)} por dia`,
          visibilidade: estadoCredenciamento,
          editar: 'Credenciamento',
          destino: `${base}/credenciamento`,
        })),
        {
          chave: 'pesquisa',
          beneficio: 'Pesquisa da ativação',
          quantidade: master ? '3 perguntas' : '—',
          visibilidade: master ? VISIBILIDADE[estadoAbaNaParceria(parceria, 'PESQUISA')] : 'Somente Master',
          editar: 'Pesquisa',
          destino: `${base}/pesquisa`,
        },
      ]
    : []

  return (
    <Cartao titulo="Direitos por contrato">
      <p className="mb-4 text-sm font-medium text-texto-2">
        O que a cota dá a esta parceria. Cada benefício é configurado na própria aba, sem herdar quantidades de outra cota.
      </p>
      {dados.erro ? (
        <Aviso tipo="falha" titulo="Não foi possível carregar os direitos">
          {dados.erro}
        </Aviso>
      ) : !dados.pronto ? (
        <Aviso tipo="carregando" titulo="Carregando…" />
      ) : (
        <Tabela<Linha>
          rotulo="Direitos por contrato"
          linhas={linhas}
          chave={(linha) => linha.chave}
          colunas={[
            { titulo: 'Benefício / setor / dia', celula: (linha) => <span className="font-semibold">{linha.beneficio}</span> },
            { titulo: 'Quantidade', celula: (linha) => linha.quantidade },
            { titulo: 'Visibilidade', celula: (linha) => linha.visibilidade },
            {
              titulo: '',
              celula: (linha) => (
                <Link to={linha.destino} className="text-xs font-bold text-secao-texto hover:underline" aria-label={`Editar ${linha.beneficio} na aba ${linha.editar}`}>
                  Editar em {linha.editar}
                </Link>
              ),
            },
          ]}
        />
      )}
    </Cartao>
  )
}
