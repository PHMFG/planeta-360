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
import { Campo, focarPrimeiroErro } from '../../componentes/Campo'
import { CompraDeIngressos, PlanoComercialDoPerfil } from '../../componentes/DocumentosDoPerfil'
import { DireitosPorContrato } from '../../componentes/DireitosPorContrato'
import { CodigoAtivacao } from '../../componentes/CodigoAtivacao'
import { Cartao, Grade2 } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Seletor } from '../../componentes/Formulario'
import { SECOES } from '../../componentes/secoes'
import { Tabela } from '../../componentes/Tabela'
import { useSessao } from '../../sessao/Sessao'
import { dataHora, moeda } from '../../util/formatar'
import { useParceria, useRecarregarParceria } from './ContextoParceria'
import { nomeDaAba, TrocaDeCota } from '../../componentes/TrocaDeCota'
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
  // O investimento e a base andam juntos (a API recusa um sem o outro) e a RBS edita os dois aqui (R6.d1).
  const [investimento, setInvestimento] = useState(detalhe.valorInvestimento === null ? '' : String(detalhe.valorInvestimento).replace('.', ','))
  const [base, setBase] = useState<'BRUTO_TABELA' | 'LIQUIDO'>(detalhe.baseValor ?? 'LIQUIDO')
  const [falha, setFalha] = useState<string | null>(null)
  const [erros, setErros] = useState<Record<string, string>>({})
  const [salvando, setSalvando] = useState(false)

  async function salvar(evento: FormEvent) {
    evento.preventDefault()
    // "1.000.000,00" e "1000000" valem 1000000; o resto é número inválido, conferido antes de chamar a API.
    const texto = investimento.trim().replace(/\./g, '').replace(',', '.')
    const valor = texto ? Number(texto) : null
    if (texto && (!/^\d+(\.\d{1,2})?$/.test(texto) || Number.isNaN(valor))) {
      setErros({ valorInvestimento: 'Use só números, com até 2 casas decimais (ex.: 500.000,00).' })
      focarPrimeiroErro()
      return
    }
    setSalvando(true)
    setFalha(null)
    setErros({})
    try {
      aoSalvar(
        await alterarParceria(detalhe.id, {
          patrocinador: { nome: nome.trim(), segmento: segmento.trim() || null },
          nomeProduto: produto.trim() || null,
          situacao,
          ...(valor !== null ? { valorInvestimento: valor, baseValor: base } : {}),
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
        <Campo
          rotulo="Investimento (R$)"
          inputMode="decimal"
          value={investimento}
          onChange={(evento) => setInvestimento(evento.target.value)}
          erro={erros.valorInvestimento}
          ajuda="O valor do contrato da cota, com a base ao lado."
        />
        <Seletor rotulo="Base do valor" value={base} onChange={(evento) => setBase(evento.target.value as 'BRUTO_TABELA' | 'LIQUIDO')} erro={erros.baseValor}>
          <option value="LIQUIDO">Líquido</option>
          <option value="BRUTO_TABELA">Bruto de tabela</option>
        </Seletor>
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

type EstadoNaTela = 'LIBERADA' | 'FECHADA' | 'OCULTA'

function AbasLiberadas({ detalhe, rbs, aoSalvar }: { detalhe: ParceriaDetalhe; rbs: boolean; aoSalvar: (novo: ParceriaDetalhe) => void }) {
  const [mudando, setMudando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  async function mudar(codigo: ParceriaDetalhe['abas'][number]['codigo'], estado: EstadoNaTela) {
    setMudando(codigo)
    setFalha(null)
    setFeito(null)
    try {
      const mudanca =
        estado === 'LIBERADA' ? { codigo, liberada: true, visivel: true } : estado === 'FECHADA' ? { codigo, liberada: false, visivel: true } : { codigo, visivel: false }
      aoSalvar(await alterarParceria(detalhe.id, { abas: [mudanca] }))
      const nome = SECOES.find((item) => item.aba === codigo)?.nome ?? codigo
      setFeito(
        estado === 'LIBERADA'
          ? `A aba ${nome} foi liberada para a marca.`
          : estado === 'FECHADA'
            ? `A aba ${nome} foi fechada: a marca a vê no menu, com o aviso. As entregas e os arquivos ficam guardados.`
            : `A aba ${nome} foi ocultada: ela some do menu da marca. As entregas e os arquivos ficam guardados.`,
      )
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível mudar a aba.')
    } finally {
      setMudando(null)
    }
  }

  return (
    <Cartao titulo="Abas da parceria">
      <ul className="grid gap-2 md:grid-cols-2">
        {/* A marca não vê a aba oculta nem aqui. */}
        {detalhe.abas.filter((aba) => rbs || aba.visivel !== false).map((aba) => {
          const secao = SECOES.find((item) => item.aba === aba.codigo)
          const foraDaCota = aba.incluidaNaCota === false
          const atual: EstadoNaTela = aba.visivel === false ? 'OCULTA' : aba.liberada ? 'LIBERADA' : 'FECHADA'
          return (
            <li key={aba.codigo} data-secao={secao?.chave} className="flex items-center gap-3 rounded-xl border border-borda px-4 py-3">
              <i className={`${secao?.icone ?? 'ri-folder-line'} text-xl text-secao`} aria-hidden="true" />
              <span className="flex-1 text-sm font-semibold">{secao?.nome ?? aba.nome}</span>
              {rbs ? (
                <span className="flex flex-col items-end gap-1">
                  {/* Três estados (R6.a): liberada, fechada (a marca vê o aviso) e oculta (some do menu da marca). */}
                  <select
                    aria-label={`Situação da aba ${secao?.nome ?? aba.nome}`}
                    className="rounded-lg border border-borda bg-white px-2 py-1.5 text-xs font-semibold"
                    value={atual}
                    disabled={mudando !== null}
                    onChange={(evento) => mudar(aba.codigo, evento.target.value as EstadoNaTela)}
                  >
                    {/* [PERMISSAO] Aba fora da cota não pode ser liberada (a API recusa, saída A do plano): a opção fica desabilitada. */}
                    <option value="LIBERADA" disabled={foraDaCota && atual !== 'LIBERADA'}>
                      Liberada
                    </option>
                    <option value="FECHADA">Fechada</option>
                    <option value="OCULTA">Oculta</option>
                  </select>
                  {foraDaCota && (
                    <span className="text-[11px] font-medium text-texto-2">A cota desta parceria não inclui esta aba. Para dar acesso, use Trocar cota, em Empresa e cota.</span>
                  )}
                </span>
              ) : (
                <span className={`text-xs font-bold ${aba.liberada ? 'text-secao-texto' : 'text-texto-2'}`}>
                  {aba.liberada ? 'Liberada' : foraDaCota ? 'Não incluída na cota' : atual === 'OCULTA' ? 'Oculta pela RBS' : 'Fechada pela RBS'}
                </span>
              )}
            </li>
          )
        })}
      </ul>
      {feito && (
        <p className="mt-4 text-xs font-semibold text-texto-2" role="status">
          {feito}
        </p>
      )}
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
      // O código mostrado para esse e-mail foi invalidado junto: tirar da tela para ninguém repassar.
      if (codigo?.email === item.email) setCodigo(null)
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
        <Aviso titulo={codigo.codigoAtivacao ? `Código de ativação de ${codigo.email}` : `${codigo.email} voltou a ter acesso`} className="mb-4">
          {codigo.codigoAtivacao ? (
            <span className="flex flex-col gap-1">
              <CodigoAtivacao codigo={codigo.codigoAtivacao} />
              <span>Repasse agora: o código não aparece de novo e vale até {dataHora(codigo.codigoExpiraEm)}.</span>
            </span>
          ) : (
            'A pessoa já tinha conta, então o vínculo voltou sem precisar de código.'
          )}
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
                titulo: 'Ações',
                celula: (item) =>
                  item.situacao === 'REVOGADO' ? (
                    <span className="text-xs text-texto-2">Revogado em {dataHora(item.revogadoEm)}</span>
                  ) : (
                    <div className="flex justify-end gap-3 text-xs font-bold">
                      {item.situacao === 'PENDENTE' && (
                        <button
                          type="button"
                          className="hover:underline"
                          aria-label={`Novo código para ${item.email}`}
                          onClick={() => autorizarEmail(parceriaId, item.email, item.vinculo).then(setCodigo).catch(() => setFalha('Não foi possível gerar outro código.'))}
                        >
                          Novo código
                        </button>
                      )}
                      {/* Cada linha repete os botões: o nome acessível diz de qual e-mail é (U16). */}
                      <button type="button" className="text-[#c8203f] hover:underline" aria-label={`Revogar ${item.email}`} onClick={() => revogar(item)}>
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
  const [trocandoCota, setTrocandoCota] = useState(false)
  const [cotaTrocada, setCotaTrocada] = useState<string | null>(null)
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
                    <Dado
                      rotulo="Cota"
                      valor={
                        rbs && detalhe.situacao !== 'ENCERRADA' ? (
                          <span className="flex flex-wrap items-center gap-x-3">
                            {detalhe.cota.nome}
                            {/* R6.d3: a troca abre a prévia do impacto antes de confirmar. */}
                            <button type="button" className="text-[13px] font-semibold text-secao-texto hover:underline" onClick={() => setTrocandoCota(true)}>
                              Trocar cota
                            </button>
                          </span>
                        ) : (
                          detalhe.cota.nome
                        )
                      }
                    />
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
              {cotaTrocada && (
                <Aviso titulo={cotaTrocada}>
                  Cortesias, credenciais, plano comercial e investimento não mudaram sozinhos: ajuste-os nas abas, se a cota nova pedir.
                </Aviso>
              )}
              {trocandoCota && (
                <TrocaDeCota
                  detalhe={detalhe}
                  aoFechar={() => setTrocandoCota(false)}
                  aoTrocar={(nova, impacto) => {
                    setTrocandoCota(false)
                    setCotaTrocada(
                      `Cota trocada para ${impacto.cotaNova.nome}.${impacto.abasQueFecham.length ? ` Fecharam: ${impacto.abasQueFecham.map(nomeDaAba).join(', ')}.` : ''}`,
                    )
                    salvo(nova)
                  }}
                />
              )}

              {/* Remonta quando a cota muda: o aviso da última mudança de aba não pode ficar ao lado do da troca. */}
              <AbasLiberadas key={detalhe.cota.codigo} detalhe={detalhe} rbs={rbs} aoSalvar={salvo} />

              {rbs && (
                <>
                  <DireitosPorContrato parceria={parceria} />
                  {/* Figma R02: os dois avisos da configuração administrativa. */}
                  <Aviso titulo="Isolamento por patrocinador">
                    Arquivos, resultados e relatórios pertencem à empresa selecionada. Confira a parceria aberta antes de publicar ou de mudar
                    acessos.
                  </Aviso>
                  <Aviso titulo="Visibilidade das 7 abas · controle da RBS">
                    Perfil, Aplicações, Mídia, Cortesias, Credenciamento, Pesquisa e Contrato: liberar, fechar ou ocultar conforme o contrato. A
                    Pesquisa é só da cota Master.
                  </Aviso>
                </>
              )}

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

              {/* Figma P01: o plano comercial e a compra de ingressos têm cartões próprios (R5.1); as outras entregas ficam abaixo. */}
              <PlanoComercialDoPerfil parceriaId={detalhe.id} rbs={rbs} />
              <CompraDeIngressos parceriaId={detalhe.id} rbs={rbs} />
              <EntregasDaAba aba="PERFIL" titulo="Outros documentos da parceria" semPapel />

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
