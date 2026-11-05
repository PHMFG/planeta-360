// O anexo "Pós-venda da edição" do relatório (requisitos R8.1 e R5.3.d da RBS; o deck de pós-venda de 2026 como modelo):
// texto, links (aftermovie, clipping), audiência por veículo e os links dos posts da parceria. Fica depois das 6 seções do
// relatório, que não mudam. A marca o vê com o relatório disponibilizado; a RBS publica versões e retira, sem apagar.
import { useState, type FormEvent } from 'react'
import { ErroApi } from '../api/cliente'
import {
  NOME_DO_LINK,
  obterPosVenda,
  publicarPosVenda,
  retirarPosVenda,
  type Audiencia,
  type PosVendaDaParceria,
  type TipoDeLink,
  type VersaoDoAnexo,
} from '../api/posvenda'
import { useDados } from '../api/useDados'
import { data, dataHora, numero } from '../util/formatar'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Campo } from './Campo'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { AreaTexto, Seletor } from './Formulario'
import { LinkExterno } from './LinkExterno'
import { Modal } from './Modal'
import { Tabela } from './Tabela'

type LinhaDeLink = { tipo: TipoDeLink; titulo: string; url: string }
type LinhaDeAudiencia = { veiculo: string; metrica: string; numero: string; unidade: string }

function ConteudoDoAnexo({ anexo, linksDePosts }: { anexo: VersaoDoAnexo | null; linksDePosts: PosVendaDaParceria['linksDePosts'] }) {
  return (
    <div className="flex flex-col gap-5">
      {anexo?.texto && <p className="whitespace-pre-line text-sm [overflow-wrap:anywhere]">{anexo.texto}</p>}
      {anexo && anexo.links.length > 0 && (
        <div>
          <p className="mb-2 text-sm font-semibold">Para ver e compartilhar</p>
          <ul className="flex flex-col gap-1 text-sm">
            {anexo.links.map((link) => (
              <li key={`${link.tipo}-${link.url}`} className="[overflow-wrap:anywhere]">
                <span className="text-texto-2">{NOME_DO_LINK[link.tipo]}: </span>
                <LinkExterno href={link.url} className="font-semibold text-secao-texto hover:underline">
                  {link.titulo}
                </LinkExterno>
              </li>
            ))}
          </ul>
        </div>
      )}
      {anexo && anexo.audiencias.length > 0 && (
        <Tabela<Audiencia>
          rotulo="Audiência por veículo"
          linhas={anexo.audiencias}
          chave={(linha) => `${linha.veiculo}-${linha.metrica}`}
          colunas={[
            { titulo: 'Veículo', celula: (linha) => <span className="font-semibold">{linha.veiculo}</span> },
            { titulo: 'Métrica', celula: (linha) => linha.metrica },
            { titulo: 'Audiência', celula: (linha) => `${numero(linha.numero)} ${linha.unidade}`, classe: 'text-right' },
          ]}
        />
      )}
      {linksDePosts.length > 0 && (
        <div>
          <p className="mb-2 text-sm font-semibold">Posts da sua marca no festival</p>
          <ul className="flex flex-col gap-1 text-sm">
            {linksDePosts.map((link) => (
              <li key={link.url} className="[overflow-wrap:anywhere]">
                <LinkExterno href={link.url} className="font-semibold text-secao-texto hover:underline">
                  {link.titulo || [link.veiculo, link.formato].filter(Boolean).join(' · ') || 'Post'}
                </LinkExterno>
                {link.exibidoEm && <span className="text-texto-2"> · {data(link.exibidoEm)}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function ModalDePublicar({ parceriaId, atual, aoFechar, aoConcluir }: { parceriaId: number; atual: VersaoDoAnexo | null; aoFechar: () => void; aoConcluir: () => void }) {
  const [texto, setTexto] = useState(atual?.texto ?? '')
  const [links, setLinks] = useState<LinhaDeLink[]>(atual?.links.map((link) => ({ ...link })) ?? [])
  const [audiencias, setAudiencias] = useState<LinhaDeAudiencia[]>(
    atual?.audiencias.map((linha) => ({ ...linha, numero: String(linha.numero) })) ?? [],
  )
  const [erros, setErros] = useState<Record<string, string>>({})
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  const mudarLink = (indice: number, campo: keyof LinhaDeLink, valor: string) =>
    setLinks((atuais) => atuais.map((link, i) => (i === indice ? { ...link, [campo]: valor } : link)))
  const mudarAudiencia = (indice: number, campo: keyof LinhaDeAudiencia, valor: string) =>
    setAudiencias((atuais) => atuais.map((linha, i) => (i === indice ? { ...linha, [campo]: valor } : linha)))

  async function publicar(evento: FormEvent) {
    evento.preventDefault()
    setErros({})
    setFalha(null)
    const locais: Record<string, string> = {}
    audiencias.forEach((linha, indice) => {
      if (!/^\d+([.,]\d{1,2})?$/.test(linha.numero.trim().replace(/\./g, '').replace(/^$/, 'x'))) locais[`audiencias[${indice}].numero`] = 'Use um número, como 1600000.'
    })
    if (Object.keys(locais).length) return setErros(locais)
    setEnviando(true)
    try {
      await publicarPosVenda(parceriaId, {
        texto: texto.trim() || undefined,
        links: links.map((link) => ({ tipo: link.tipo, titulo: link.titulo.trim(), url: link.url.trim() })),
        audiencias: audiencias.map((linha) => ({
          veiculo: linha.veiculo.trim(),
          metrica: linha.metrica.trim(),
          numero: Number(linha.numero.trim().replace(/\./g, '').replace(',', '.')),
          unidade: linha.unidade.trim(),
        })),
      })
      aoConcluir()
    } catch (erro) {
      if (erro instanceof ErroApi && Object.keys(erro.campos).length) setErros(erro.campos)
      else setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível publicar o anexo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal aberto titulo="Publicar o pós-venda" legenda={atual ? `Nova versão (a ${atual.versao + 1}), no lugar da ${atual.versao}` : 'Primeira versão'} aoFechar={aoFechar} largura="max-w-[720px] min-w-0">
      <form onSubmit={publicar} className="flex flex-col gap-5" noValidate>
        <AreaTexto rotulo="Texto do pós-venda" value={texto} onChange={(campo) => setTexto(campo.target.value)} erro={erros.texto} ajuda="O resumo da edição para a marca. Até 5.000 caracteres." maxLength={5000} rows={5} />

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-2 text-sm font-semibold">Links (aftermovie, clipping)</legend>
          {links.map((link, indice) => (
            <div key={indice} className="grid grid-cols-[minmax(0,1fr)] gap-3 rounded-xl border border-borda p-3 sm:grid-cols-[10rem_minmax(0,1fr)]">
              <Seletor rotulo={`Tipo do link ${indice + 1}`} value={link.tipo} onChange={(campo) => mudarLink(indice, 'tipo', campo.target.value)} erro={erros[`links[${indice}].tipo`]}>
                {(Object.keys(NOME_DO_LINK) as TipoDeLink[]).map((tipo) => (
                  <option key={tipo} value={tipo}>
                    {NOME_DO_LINK[tipo]}
                  </option>
                ))}
              </Seletor>
              <Campo rotulo={`Título do link ${indice + 1}`} value={link.titulo} onChange={(campo) => mudarLink(indice, 'titulo', campo.target.value)} erro={erros[`links[${indice}].titulo`]} maxLength={160} />
              <Campo
                rotulo={`Endereço do link ${indice + 1}`}
                value={link.url}
                onChange={(campo) => mudarLink(indice, 'url', campo.target.value)}
                erro={erros[`links[${indice}].url`]}
                ajuda="Começa com https://"
                inputMode="url"
                maxLength={500}
                className="sm:col-span-2"
              />
              <div className="sm:col-span-2">
                <button type="button" className="text-xs font-bold hover:underline" onClick={() => setLinks((atuais) => atuais.filter((_, i) => i !== indice))}>
                  Tirar o link {indice + 1}
                </button>
              </div>
            </div>
          ))}
          {erros.links && <p className="text-xs font-semibold text-estado-falha">{erros.links}</p>}
          <div>
            <Botao variante="secundario" icone="ri-add-line" className="h-10 min-w-0 px-4" disabled={links.length >= 30} onClick={() => setLinks((atuais) => [...atuais, { tipo: 'AFTERMOVIE', titulo: '', url: '' }])}>
              Adicionar link
            </Botao>
          </div>
        </fieldset>

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-2 text-sm font-semibold">Audiência por veículo</legend>
          {audiencias.map((linha, indice) => (
            <div key={indice} className="grid grid-cols-[minmax(0,1fr)] gap-3 rounded-xl border border-borda p-3 sm:grid-cols-2">
              <Campo rotulo={`Veículo ${indice + 1}`} value={linha.veiculo} onChange={(campo) => mudarAudiencia(indice, 'veiculo', campo.target.value)} erro={erros[`audiencias[${indice}].veiculo`]} maxLength={80} />
              <Campo rotulo={`Métrica ${indice + 1}`} value={linha.metrica} onChange={(campo) => mudarAudiencia(indice, 'metrica', campo.target.value)} erro={erros[`audiencias[${indice}].metrica`]} ajuda="Ex.: pessoas alcançadas" maxLength={80} />
              <Campo rotulo={`Número ${indice + 1}`} value={linha.numero} onChange={(campo) => mudarAudiencia(indice, 'numero', campo.target.value)} erro={erros[`audiencias[${indice}].numero`]} inputMode="decimal" maxLength={20} />
              <Campo rotulo={`Unidade ${indice + 1}`} value={linha.unidade} onChange={(campo) => mudarAudiencia(indice, 'unidade', campo.target.value)} erro={erros[`audiencias[${indice}].unidade`]} ajuda="Ex.: pessoas" maxLength={20} />
              <div className="sm:col-span-2">
                <button type="button" className="text-xs font-bold hover:underline" onClick={() => setAudiencias((atuais) => atuais.filter((_, i) => i !== indice))}>
                  Tirar a audiência {indice + 1}
                </button>
              </div>
            </div>
          ))}
          {erros.audiencias && <p className="text-xs font-semibold text-estado-falha">{erros.audiencias}</p>}
          <div>
            <Botao
              variante="secundario"
              icone="ri-add-line"
              className="h-10 min-w-0 px-4"
              disabled={audiencias.length >= 20}
              onClick={() => setAudiencias((atuais) => [...atuais, { veiculo: '', metrica: '', numero: '', unidade: '' }])}
            >
              Adicionar audiência
            </Botao>
          </div>
        </fieldset>

        <Aviso titulo="Antes de publicar">
          A versão nova substitui a anterior para a marca, que a vê no relatório disponibilizado. Nada é apagado: as versões ficam no histórico.
        </Aviso>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao type="submit" carregando={enviando} className="min-w-0 max-sm:flex-1">
            Publicar
          </Botao>
        </div>
      </form>
    </Modal>
  )
}

function ModalDeRetirar({ anexo, aoFechar, aoConcluir }: { anexo: VersaoDoAnexo; aoFechar: () => void; aoConcluir: () => void }) {
  const [falha, setFalha] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  async function retirar() {
    setFalha(null)
    setEnviando(true)
    try {
      await retirarPosVenda(anexo.id)
      aoConcluir()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? erro.message : 'Não foi possível retirar o anexo.')
    } finally {
      setEnviando(false)
    }
  }
  return (
    <Modal aberto titulo="Retirar o pós-venda" legenda={`Versão ${anexo.versao}`} aoFechar={aoFechar}>
      <div className="flex flex-col gap-4">
        <p className="text-sm">A marca deixa de ver o anexo. A versão não é apagada e fica no histórico; para corrigir, publique uma versão nova.</p>
        {falha && <Aviso tipo="falha" titulo={falha} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Botao variante="secundario" className="min-w-0 max-sm:flex-1" onClick={aoFechar}>
            Cancelar
          </Botao>
          <Botao carregando={enviando} className="min-w-0 max-sm:flex-1" onClick={retirar}>
            Retirar
          </Botao>
        </div>
      </div>
    </Modal>
  )
}

/** A marca: só com o relatório disponibilizado e um anexo publicado (a API dá 404 nos outros casos, e aí nada aparece). */
export function PosVendaDaMarca({ parceriaId }: { parceriaId: number }) {
  const posvenda = useDados(
    () => obterPosVenda(parceriaId).catch((erro: unknown) => (erro instanceof ErroApi && erro.status === 404 ? null : Promise.reject(erro))),
    [parceriaId],
  )
  const dado = posvenda.dado
  if (!dado?.anexo) return null
  return (
    <Cartao titulo="Pós-venda da edição">
      <p className="mb-4 text-xs text-texto-2">
        Publicado pela RBS em {dataHora(dado.anexo.publicadoEm)}. Os números do festival estão na seção “O festival em números” do relatório.
      </p>
      <ConteudoDoAnexo anexo={dado.anexo} linksDePosts={dado.linksDePosts} />
    </Cartao>
  )
}

/** A RBS: o anexo em vigor, publicar uma versão nova e retirar. */
export function PosVendaDaRbs({ parceriaId }: { parceriaId: number }) {
  const posvenda = useDados(() => obterPosVenda(parceriaId), [parceriaId])
  const [publicando, setPublicando] = useState(false)
  const [retirando, setRetirando] = useState<VersaoDoAnexo | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  return (
    <Cartao titulo="Pós-venda da edição" className="print:hidden">
      <Carregando dados={posvenda}>
        {(dado) => (
          <div className="flex flex-col gap-4">
            {!dado.relatorioDisponibilizado && (
              <Aviso tipo="ausente" titulo="A marca ainda não vê o pós-venda">
                Ele aparece para a marca junto do relatório, depois que uma versão for disponibilizada.
              </Aviso>
            )}
            {dado.anexo ? (
              <>
                <p className="text-xs text-texto-2">
                  Versão {dado.anexo.versao} · publicada em {dataHora(dado.anexo.publicadoEm)}
                  {dado.anexo.publicadoPor ? ` por ${dado.anexo.publicadoPor.nome}` : ''}
                </p>
                <ConteudoDoAnexo anexo={dado.anexo} linksDePosts={dado.linksDePosts} />
              </>
            ) : (
              <Aviso tipo="vazio" titulo="Nenhum pós-venda publicado para esta parceria">
                Use o deck da edição como modelo: o texto, o aftermovie, o clipping e a audiência por veículo.
                {dado.linksDePosts.length > 0 ? ' Os links dos posts da marca entram sozinhos.' : ''}
              </Aviso>
            )}
            {feito && (
              <p className="text-sm font-semibold text-secao-texto" role="status">
                {feito}
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <Botao icone="ri-send-plane-line" onClick={() => setPublicando(true)}>
                {dado.anexo ? 'Publicar nova versão' : 'Publicar pós-venda'}
              </Botao>
              {dado.anexo && (
                <Botao variante="secundario" onClick={() => setRetirando(dado.anexo)}>
                  Retirar
                </Botao>
              )}
            </div>
            {publicando && (
              <ModalDePublicar
                parceriaId={parceriaId}
                atual={dado.anexo}
                aoFechar={() => setPublicando(false)}
                aoConcluir={() => {
                  setPublicando(false)
                  setFeito('Pós-venda publicado.')
                  posvenda.recarregar()
                }}
              />
            )}
            {retirando && (
              <ModalDeRetirar
                anexo={retirando}
                aoFechar={() => setRetirando(null)}
                aoConcluir={() => {
                  setRetirando(null)
                  setFeito('Pós-venda retirado. A marca deixou de vê-lo.')
                  posvenda.recarregar()
                }}
              />
            )}
          </div>
        )}
      </Carregando>
    </Cartao>
  )
}
