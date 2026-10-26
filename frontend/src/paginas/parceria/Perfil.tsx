// Perfil do cliente (Figma 173:375) e, para a RBS, "Empresa, cota e acessos" (Figma R02). [ITEM-3] [ITEM-7]
// O patrocinador só consulta; a RBS edita a empresa, libera abas e autoriza ou revoga e-mails.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../../api/cliente'
import {
  alterarParceria,
  autorizarEmail,
  listarEmails,
  obterParceria,
  revogarEmail,
  type EmailAutorizado,
  type ParceriaDetalhe,
  type Situacao,
  type Vinculo,
} from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { Aviso } from '../../componentes/Aviso'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Campo } from '../../componentes/Campo'
import { Cartao, Grade2 } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { SECOES } from '../../componentes/secoes'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, moeda } from '../../util/formatar'
import { useParceria, useRecarregarParceria } from './ContextoParceria'
import { EntregasDaAba } from './EntregasDaAba'

const SITUACOES: Record<Situacao, string> = {
  EM_NEGOCIACAO: 'Em negociação',
  ATIVA: 'Ativa',
  ENCERRADA: 'Encerrada',
  CANCELADA: 'Cancelada',
}

const SITUACOES_EMAIL = { ATIVO: 'Com acesso', PENDENTE: 'Aguardando primeiro acesso', REVOGADO: 'Revogado' } as const

function Dado({ rotulo, valor }: { rotulo: string; valor: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border-[1.5px] border-borda bg-white px-4 py-3">
      <span className="texto-sobretitulo text-texto-2">{rotulo}</span>
      <span className="text-sm font-semibold">{valor || '—'}</span>
    </div>
  )
}

function EdicaoEmpresa({ detalhe, aoSalvar }: { detalhe: ParceriaDetalhe; aoSalvar: (novo: ParceriaDetalhe) => void }) {
  const [nome, setNome] = useState(detalhe.patrocinador.nome)
  const [segmento, setSegmento] = useState(detalhe.patrocinador.segmento ?? '')
  const [produto, setProduto] = useState(detalhe.nomeProduto ?? '')
  const [situacao, setSituacao] = useState<Situacao>(detalhe.situacao)
  const [falha, setFalha] = useState<string | null>(null)
  const [erros, setErros] = useState<Record<string, string>>({})
  const [salvando, setSalvando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    setSalvando(true)
    setFalha(null)
    try {
      aoSalvar(
        await alterarParceria(detalhe.id, {
          patrocinador: { nome: nome.trim(), segmento: segmento.trim() || null },
          nomeProduto: produto.trim() || null,
          situacao,
        }),
      )
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível salvar.')
      if (erro instanceof ErroApi) setErros(erro.campos)
    } finally {
      setSalvando(false)
    }
  }

  return (
    <form onSubmit={salvar} className="flex flex-col gap-4" noValidate>
      <Grade2>
        <Campo rotulo="Empresa" value={nome} onChange={(evento) => setNome(evento.target.value)} erro={erros['patrocinador.nome']} />
        <Campo rotulo="Segmento" value={segmento} onChange={(evento) => setSegmento(evento.target.value)} />
        <Campo rotulo="Produto" value={produto} onChange={(evento) => setProduto(evento.target.value)} erro={erros.nomeProduto} />
        <Seletor rotulo="Situação" value={situacao} onChange={(evento) => setSituacao(evento.target.value as Situacao)}>
          {Object.entries(SITUACOES).map(([codigo, nomeSituacao]) => (
            <option key={codigo} value={codigo}>
              {nomeSituacao}
            </option>
          ))}
        </Seletor>
      </Grade2>
      {falha && <Aviso tipo="falha" titulo={falha} />}
      <div>
        <Botao type="submit" carregando={salvando}>
          Salvar empresa
        </Botao>
      </div>
    </form>
  )
}

function AbasLiberadas({ detalhe, rbs, aoSalvar }: { detalhe: ParceriaDetalhe; rbs: boolean; aoSalvar: (novo: ParceriaDetalhe) => void }) {
  const [mudando, setMudando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  async function alternar(codigo: ParceriaDetalhe['abas'][number]['codigo'], liberada: boolean) {
    setMudando(codigo)
    setFalha(null)
    try {
      aoSalvar(await alterarParceria(detalhe.id, { abas: [{ codigo, liberada }] }))
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível mudar a aba.')
    } finally {
      setMudando(null)
    }
  }

  return (
    <Cartao titulo="Abas da parceria">
      <ul className="grid gap-2 md:grid-cols-2">
        {detalhe.abas.map((aba) => {
          const secao = SECOES.find((item) => item.aba === aba.codigo)
          return (
            <li key={aba.codigo} data-secao={secao?.chave} className="flex items-center gap-3 rounded-xl border border-borda px-4 py-3">
              <i className={`${secao?.icone ?? 'ri-folder-line'} text-xl text-secao`} aria-hidden="true" />
              <span className="flex-1 text-sm font-semibold">{secao?.nome ?? aba.nome}</span>
              {rbs ? (
                <label className="flex cursor-pointer items-center gap-2 text-xs font-semibold">
                  <input
                    type="checkbox"
                    aria-label={`Liberar a aba ${secao?.nome ?? aba.nome}`}
                    className="size-4 accent-[var(--secao-cor)]"
                    checked={aba.liberada}
                    disabled={mudando !== null}
                    onChange={(evento) => alternar(aba.codigo, evento.target.checked)}
                  />
                  {aba.liberada ? 'Liberada' : 'Fechada'}
                </label>
              ) : (
                <span className={`text-xs font-bold ${aba.liberada ? 'text-secao-texto' : 'text-texto-2'}`}>
                  {aba.liberada ? 'Liberada' : 'Não incluída na cota'}
                </span>
              )}
            </li>
          )
        })}
      </ul>
      {falha && <Aviso tipo="falha" titulo={falha} className="mt-4" />}
    </Cartao>
  )
}

function AcessosRbs({ parceriaId }: { parceriaId: number }) {
  const emails = useDados(() => listarEmails(parceriaId), [parceriaId])
  const [email, setEmail] = useState('')
  const [vinculo, setVinculo] = useState<Vinculo>('MARCA')
  const [erro, setErro] = useState<string | undefined>()
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [codigo, setCodigo] = useState<EmailAutorizado | null>(null)

  async function autorizar(evento: FormEvent) {
    evento.preventDefault()
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setErro('Informe um e-mail válido.')
      return
    }
    setErro(undefined)
    setFalha(null)
    setEnviando(true)
    try {
      const autorizado = await autorizarEmail(parceriaId, email.trim().toLowerCase(), vinculo)
      setCodigo(autorizado)
      setEmail('')
      emails.recarregar()
    } catch (falhaApi) {
      setFalha(falhaApi instanceof ErroApi ? falhaApi.message : 'Não foi possível autorizar.')
    } finally {
      setEnviando(false)
    }
  }

  async function revogar(item: EmailAutorizado) {
    // [PERMISSAO] Revogar tira o acesso na hora, até de quem está logado.
    if (!window.confirm(`Revogar o acesso de ${item.email}? A pessoa perde o acesso imediatamente.`)) return
    try {
      await revogarEmail(parceriaId, item.id)
      emails.recarregar()
    } catch (falhaApi) {
      setFalha(falhaApi instanceof ErroApi ? falhaApi.message : 'Não foi possível revogar.')
    }
  }

  return (
    <Cartao titulo="Acessos autorizados">
      <form onSubmit={autorizar} className="mb-4 grid gap-4 md:grid-cols-[1fr_200px_auto] md:items-start" noValidate>
        <Campo rotulo="Autorizar e-mail" type="email" placeholder="pessoa@marca.com.br" value={email} onChange={(evento) => setEmail(evento.target.value)} erro={erro} />
        <Seletor rotulo="Vínculo" value={vinculo} onChange={(evento) => setVinculo(evento.target.value as Vinculo)}>
          <option value="MARCA">Marca</option>
          <option value="AGENCIA">Agência</option>
        </Seletor>
        <Botao type="submit" carregando={enviando} className="md:mt-1.5">
          Autorizar
        </Botao>
      </form>
      {codigo && (
        <Aviso titulo={codigo.codigoAtivacao ? `Código de ativação de ${codigo.email}: ${codigo.codigoAtivacao}` : `${codigo.email} voltou a ter acesso`} className="mb-4">
          {codigo.codigoAtivacao
            ? `Repasse agora: o código não aparece de novo e vale até ${dataHora(codigo.codigoExpiraEm)}.`
            : 'A pessoa já tinha conta, então o vínculo voltou sem precisar de código.'}
        </Aviso>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
      <Carregando dados={emails}>
        {(lista) => (
          <Tabela<EmailAutorizado>
            linhas={lista}
            chave={(item) => item.id}
            vazio="Nenhum e-mail autorizado."
            colunas={[
              { titulo: 'E-mail', celula: (item) => <span className={item.situacao === 'REVOGADO' ? 'text-texto-2 line-through' : 'font-semibold'}>{item.email}</span> },
              { titulo: 'Vínculo', celula: (item) => (item.vinculo === 'MARCA' ? 'Marca' : 'Agência') },
              { titulo: 'Situação', celula: (item) => SITUACOES_EMAIL[item.situacao] },
              { titulo: 'Autorizado em', celula: (item) => dataHora(item.autorizadoEm) },
              {
                titulo: '',
                celula: (item) =>
                  item.situacao === 'REVOGADO' ? (
                    <span className="text-xs text-texto-2">{dataHora(item.revogadoEm)}</span>
                  ) : (
                    <div className="flex justify-end gap-3 text-xs font-bold">
                      {item.situacao === 'PENDENTE' && (
                        <button type="button" className="hover:underline" onClick={() => autorizarEmail(parceriaId, item.email, item.vinculo).then(setCodigo).catch(() => setFalha('Não foi possível gerar outro código.'))}>
                          Novo código
                        </button>
                      )}
                      <button type="button" className="text-[#c8203f] hover:underline" onClick={() => revogar(item)}>
                        Revogar
                      </button>
                    </div>
                  ),
              },
            ]}
          />
        )}
      </Carregando>
    </Cartao>
  )
}

export function Perfil() {
  const parceria = useParceria()
  const { usuario } = useSessao()
  const rbs = usuario?.perfil === 'ADMIN_RBS'
  const recarregarParceria = useRecarregarParceria()
  const dados = useDados(() => obterParceria(parceria.id), [parceria.id])
  const [editando, setEditando] = useState(false)
  const [atual, setAtual] = useState<ParceriaDetalhe | null>(null)
  const detalhe = atual ?? dados.dado
  // Depois de editar, o menu (nome e cadeados das abas) também precisa ver a mudança.
  const salvo = (novo: ParceriaDetalhe) => {
    setAtual(novo)
    recarregarParceria()
  }

  return (
    <div data-secao="perfil" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha={rbs ? 'Planeta Atlântida / RBS · Gestão' : 'Planeta Atlântida / Patrocinador'}
        titulo={rbs ? 'Empresa, cota e acessos' : 'Perfil'}
        subtitulo={rbs ? 'Cadastro da parceria, abas liberadas e quem pode entrar.' : 'Dados da empresa, cota, acessos autorizados e documentos da parceria.'}
      />
      <Carregando dados={dados}>
        {() =>
          detalhe !== null && (
            <>
              <Cartao
                titulo="Empresa e cota"
                extra={
                  rbs && (
                    <button type="button" className="text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => setEditando((valor) => !valor)}>
                      {editando ? 'Cancelar edição' : 'Editar'}
                    </button>
                  )
                }
              >
                {editando ? (
                  <EdicaoEmpresa
                    detalhe={detalhe}
                    aoSalvar={(novo) => {
                      salvo(novo)
                      setEditando(false)
                    }}
                  />
                ) : (
                  <Grade2>
                    <Dado rotulo="Empresa" valor={detalhe.patrocinador.nome} />
                    <Dado rotulo="Segmento" valor={detalhe.patrocinador.segmento} />
                    <Dado rotulo="Cota" valor={detalhe.cota.nome} />
                    <Dado rotulo="Edição" valor={`${detalhe.evento.nome} ${detalhe.evento.edicao}`} />
                    <Dado rotulo="Produto" valor={detalhe.nomeProduto} />
                    <Dado rotulo="Situação" valor={SITUACOES[detalhe.situacao]} />
                    <Dado
                      rotulo="Investimento"
                      valor={detalhe.valorInvestimento ? `${moeda(detalhe.valorInvestimento)} · ${detalhe.baseValor === 'LIQUIDO' ? 'líquido' : 'bruto de tabela'}` : null}
                    />
                    <Dado rotulo="Cadastrada em" valor={dataHora(detalhe.criadoEm)} />
                  </Grade2>
                )}
              </Cartao>

              <AbasLiberadas detalhe={detalhe} rbs={rbs} aoSalvar={salvo} />

              {rbs ? (
                <AcessosRbs parceriaId={detalhe.id} />
              ) : (
                <Cartao titulo="Acessos autorizados">
                  <Tabela
                    linhas={detalhe.emailsAutorizados}
                    chave={(item) => item.id}
                    vazio="Nenhum e-mail autorizado."
                    colunas={[
                      { titulo: 'E-mail', celula: (item) => <span className="font-semibold">{item.email}</span> },
                      { titulo: 'Vínculo', celula: (item) => (item.vinculo === 'MARCA' ? 'Marca' : 'Agência') },
                      { titulo: 'Situação', celula: (item) => SITUACOES_EMAIL[item.situacao] },
                    ]}
                  />
                </Cartao>
              )}

              <EntregasDaAba aba="PERFIL" titulo="Documentos da parceria" />

              {!rbs && (
                <Aviso titulo="Cadastro divergente?">
                  Solicite a correção à RBS. A empresa não altera a própria cota nem libera novos acessos.
                </Aviso>
              )}
            </>
          )
        }
      </Carregando>
    </div>
  )
}
