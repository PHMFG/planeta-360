// Aba Credenciamento (Figma P05; requisito R5.5 da RBS): a RBS informa a quantidade e o setor de cada credencial da parceria,
// e o endereço do Open Staff e o período de cadastro da edição; a marca vê a liberação e abre o Open Staff, onde o cadastro
// das pessoas é feito (fora do portal). As entregas da aba (o guia de credenciamento, por exemplo) continuam embaixo.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import {
  BENEFICIARIOS,
  NOME_BENEFICIARIO,
  NOME_SETOR_CREDENCIAL,
  SETORES_CREDENCIAL,
  definirCredenciamentoDaEdicao,
  gravarCredenciais,
  obterCredenciais,
  type Beneficiario,
  type Credenciais,
  type Credencial,
  type SetorCredencial,
} from '../../api/credenciamento'
import { useDados } from '../../api/useDados'
import { AvisoAbaFechada, estadoAbaNaParceria } from '../../componentes/AbaFechada'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo, focarPrimeiroErro } from '../../componentes/Campo'
import { Cartao } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { enderecoSeguro, LinkExterno } from '../../componentes/LinkExterno'
import { SECOES } from '../../componentes/secoes'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { data, numero } from '../../util/formatar'
import { useParceria, useRecarregarSeAbaFechou } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

const SECAO = SECOES.find((secao) => secao.chave === 'credenciamento')!

export function Credenciamento() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const estado = estadoAbaNaParceria(parceria, 'CREDENCIAMENTO')
  const podeVer = rbs || estado === 'LIBERADA'

  return (
    <div data-secao="credenciamento" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo="Credenciamento"
        subtitulo="Quantidades e setores definidos pela RBS; o cadastro das pessoas é feito no Open Staff."
      />
      <AvisoAbaFechada parceria={parceria} estado={estado} nome={SECAO.nome} rbs={rbs} />
      {podeVer && <ConteudoDoCredenciamento rbs={rbs} />}
      {podeVer && <EntregasDaAba aba="CREDENCIAMENTO" />}
    </div>
  )
}

function periodo(credenciais: Credenciais) {
  const { credenciamentoInicio: inicio, credenciamentoFim: fim } = credenciais
  if (inicio && fim) return `de ${data(inicio)} a ${data(fim)}`
  if (inicio) return `a partir de ${data(inicio)}`
  if (fim) return `até ${data(fim)}`
  return 'a informar pela RBS'
}

function ConteudoDoCredenciamento({ rbs }: { rbs: boolean }) {
  const parceria = useParceria()
  const dados = useDados(() => obterCredenciais(parceria.id), [parceria.id])
  useRecarregarSeAbaFechou(dados)

  return (
    <Carregando dados={dados}>
      {(credenciais) => (
        <>
          <Cartao titulo="Sua liberação">
            {credenciais.credenciais.length === 0 ? (
              <Aviso tipo="ausente" titulo="A RBS ainda não informou as credenciais desta parceria">
                {rbs ? 'Preencha as credenciais no cartão abaixo.' : 'Quando a RBS informar, o setor e a quantidade aparecem aqui.'}
              </Aviso>
            ) : (
              <Tabela<Credencial>
                rotulo="Sua liberação"
                linhas={credenciais.credenciais}
                chave={(credencial) => credencial.id}
                colunas={[
                  { titulo: 'Setor autorizado', celula: (credencial) => <span className="font-semibold">{NOME_SETOR_CREDENCIAL[credencial.setor]}</span> },
                  { titulo: 'Para', celula: (credencial) => NOME_BENEFICIARIO[credencial.beneficiario] },
                  { titulo: 'Quantidade por dia', celula: (credencial) => numero(credencial.quantidadePorDia), classe: 'text-right' },
                ]}
              />
            )}
            <p className="mt-4 text-sm">
              <span className="font-semibold">Período de cadastro:</span> {periodo(credenciais)}
            </p>
            <div className="mt-4">
              {enderecoSeguro(credenciais.openStaffUrl) ? (
                <LinkExterno
                  href={credenciais.openStaffUrl!}
                  mostrarDominio={false}
                  className="inline-flex h-12 items-center rounded-xl bg-secao px-6 text-sm font-bold tracking-[0.08em] text-white uppercase hover:brightness-110"
                >
                  Abrir Open Staff
                </LinkExterno>
              ) : (
                <p className="text-sm font-medium text-texto-2">A RBS ainda não informou o endereço do Open Staff desta edição.</p>
              )}
            </div>
          </Cartao>

          <Aviso titulo="Você será direcionado a um serviço externo">
            O cadastro de pessoas acontece no Open Staff. Este portal mostra as orientações e não confirma cadastros nem sincroniza com o
            sistema.
          </Aviso>

          <Cartao>
            <p className="text-sm font-semibold">Guia de credenciamento</p>
            <p className="text-xs font-medium text-texto-2">Publicado pela RBS na lista de entregas desta aba, no fim da página · somente consulta</p>
          </Cartao>

          {rbs && <GradeDeCredenciais credenciais={credenciais} aoGravar={dados.recarregar} />}
          {rbs && <EdicaoDaEdicao credenciais={credenciais} aoGravar={dados.recarregar} />}
        </>
      )}
    </Carregando>
  )
}

/** A grade da RBS (D10 do plano): uma linha por setor e beneficiário, com a quantidade por dia; 0 retira a linha. */
function GradeDeCredenciais({ credenciais, aoGravar }: { credenciais: Credenciais; aoGravar: () => void }) {
  const parceria = useParceria()
  // Só o que a RBS editou; o resto vem sempre dos dados recarregados (guardar uma cópia na abertura apagava, no salvar
  // seguinte, a linha que tinha acabado de ser criada).
  const [editadas, setEditadas] = useState<Record<number, string>>({})
  const valorDe = (credencial: Credencial) => editadas[credencial.id] ?? String(credencial.quantidadePorDia)
  const [setor, setSetor] = useState<SetorCredencial>('ARENA')
  const [beneficiario, setBeneficiario] = useState<Beneficiario>('MARCA')
  const [nova, setNova] = useState('')
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setFalha(null)
    setFeito(null)
    const itens: { setor: SetorCredencial; beneficiario: Beneficiario; quantidadePorDia: number }[] = []
    for (const credencial of credenciais.credenciais) {
      const texto = valorDe(credencial).trim()
      const valor = texto === '' ? 0 : Number(texto)
      if (!/^\d+$/.test(texto || '0')) return setFalha(`A quantidade de ${NOME_SETOR_CREDENCIAL[credencial.setor]} precisa ser um número inteiro.`)
      if (valor !== credencial.quantidadePorDia) itens.push({ setor: credencial.setor, beneficiario: credencial.beneficiario, quantidadePorDia: valor })
    }
    if (nova.trim()) {
      if (!/^\d+$/.test(nova.trim())) {
        setFalha('A quantidade nova precisa ser um número inteiro.')
        return focarPrimeiroErro()
      }
      itens.push({ setor, beneficiario, quantidadePorDia: Number(nova.trim()) })
    }
    if (!itens.length) return setFeito('Nada mudou.')
    setEnviando(true)
    try {
      await gravarCredenciais(parceria.id, itens)
      setNova('')
      setEditadas({})
      setFeito('Credenciais salvas. A marca vê ao abrir ou voltar à aba.')
      aoGravar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível salvar as credenciais.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo="Credenciais da parceria">
      <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
        {credenciais.credenciais.length > 0 && (
          <ul className="flex flex-col gap-2">
            {credenciais.credenciais.map((credencial) => {
              const nome = `${NOME_SETOR_CREDENCIAL[credencial.setor]} · ${NOME_BENEFICIARIO[credencial.beneficiario]}`
              return (
                <li key={credencial.id} className="flex items-center gap-3 rounded-xl border border-borda px-4 py-2 text-sm">
                  <span className="flex-1 font-semibold">{nome}</span>
                  <input
                    aria-label={`Quantidade por dia de ${nome}`}
                    inputMode="numeric"
                    className="w-20 rounded-lg border border-borda px-2 py-1.5"
                    value={valorDe(credencial)}
                    onChange={(evento) => setEditadas((atuais) => ({ ...atuais, [credencial.id]: evento.target.value }))}
                  />
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-xs font-medium text-texto-2">Quantidade 0 retira a linha. Para incluir um setor, use a linha abaixo.</p>
        <div className="grid gap-3 md:grid-cols-3">
          <Seletor rotulo="Setor" value={setor} onChange={(evento) => setSetor(evento.target.value as SetorCredencial)}>
            {SETORES_CREDENCIAL.map((item) => (
              <option key={item} value={item}>
                {NOME_SETOR_CREDENCIAL[item]}
              </option>
            ))}
          </Seletor>
          <Seletor rotulo="Para" value={beneficiario} onChange={(evento) => setBeneficiario(evento.target.value as Beneficiario)}>
            {BENEFICIARIOS.map((item) => (
              <option key={item} value={item}>
                {NOME_BENEFICIARIO[item]}
              </option>
            ))}
          </Seletor>
          <Campo rotulo="Quantidade por dia" inputMode="numeric" value={nova} onChange={(evento) => setNova(evento.target.value)} />
        </div>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        {feito && (
          <p className="text-sm font-semibold text-secao-texto" role="status">
            {feito}
          </p>
        )}
        <div>
          <Botao type="submit" carregando={enviando}>
            Salvar credenciais
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}

/** O Open Staff e o período de cadastro valem para todas as marcas da edição. */
function EdicaoDaEdicao({ credenciais, aoGravar }: { credenciais: Credenciais; aoGravar: () => void }) {
  const parceria = useParceria()
  const [url, setUrl] = useState(credenciais.openStaffUrl ?? '')
  const [inicio, setInicio] = useState(credenciais.credenciamentoInicio ?? '')
  const [fim, setFim] = useState(credenciais.credenciamentoFim ?? '')
  const [erros, setErros] = useState<Record<string, string>>({})
  const [feito, setFeito] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setFeito(null)
    const novos: Record<string, string> = {}
    if (url.trim() && (!enderecoSeguro(url.trim()) || !url.trim().startsWith('https://'))) novos.openStaffUrl = 'Use um endereço que comece com https://.'
    if (inicio && fim && fim < inicio) novos.fim = 'O fim não pode ser antes do início.'
    setErros(novos)
    if (Object.keys(novos).length) return focarPrimeiroErro()
    setEnviando(true)
    try {
      await definirCredenciamentoDaEdicao(credenciais.eventoId, { openStaffUrl: url.trim() || null, inicio: inicio || null, fim: fim || null })
      setFeito(`Open Staff e período salvos para a edição ${parceria.evento.edicao}.`)
      aoGravar()
    } catch (erro) {
      if (erro instanceof ErroApi) setErros(erro.campos)
      setFeito(null)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Cartao titulo={`Open Staff da edição ${parceria.evento.edicao}`}>
      <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
        <Campo
          rotulo="Endereço do Open Staff"
          type="url"
          value={url}
          onChange={(evento) => setUrl(evento.target.value)}
          erro={erros.openStaffUrl}
          ajuda="Vale para todas as marcas da edição. Só https."
        />
        <div className="grid gap-4 md:grid-cols-2">
          <Campo rotulo="Início do cadastro" type="date" value={inicio} onChange={(evento) => setInicio(evento.target.value)} erro={erros.inicio} />
          <Campo rotulo="Fim do cadastro" type="date" value={fim} onChange={(evento) => setFim(evento.target.value)} erro={erros.fim} />
        </div>
        {feito && (
          <p className="text-sm font-semibold text-secao-texto" role="status">
            {feito}
          </p>
        )}
        <div>
          <Botao type="submit" carregando={enviando}>
            Salvar Open Staff e período
          </Botao>
        </div>
      </form>
    </Cartao>
  )
}
