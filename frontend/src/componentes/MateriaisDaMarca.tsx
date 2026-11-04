// Aplicações, "Enviar materiais da marca" (Figma P02; requisito R5.2 da RBS): os botões próprios da logo e do MIV. O primeiro
// envio abre o espaço, e cada envio seguinte é uma versão nova, a qualquer momento.
import { useRef, useState } from 'react'
import { ErroApi } from '../api/cliente'
import { enviarAoEspaco, obterEspacos, type PapelDoEspaco } from '../api/espacos'
import { baixarArquivo, type Entrega } from '../api/materiais'
import { useDados } from '../api/useDados'
import { useRecarregarSeAbaFechou } from '../paginas/parceria/ContextoParceria'
import { dataHora } from '../util/formatar'
import { LinhaArquivo } from './Arquivo'
import { Aviso } from './Aviso'
import { Botao } from './Botao'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { nomeStatus } from './Status'

const FORMATOS = '.png,.jpg,.jpeg,.pdf,.zip'

function Seletor({ papel, rotulo, aoEscolher, enviando }: { papel: PapelDoEspaco; rotulo: string; aoEscolher: (papel: PapelDoEspaco, arquivo: File) => void; enviando: boolean }) {
  const campo = useRef<HTMLInputElement>(null)
  return (
    <>
      <Botao carregando={enviando} onClick={() => campo.current?.click()}>
        {rotulo}
      </Botao>
      <input
        ref={campo}
        type="file"
        accept={FORMATOS}
        className="sr-only"
        tabIndex={-1}
        // O campo de arquivo é exposto como botão: com nome, o leitor de tela ouvia o mesmo botão duas vezes.
        aria-hidden="true"
        data-espaco={papel}
        onChange={(evento) => {
          const arquivo = evento.target.files?.[0]
          evento.target.value = ''
          if (arquivo) aoEscolher(papel, arquivo)
        }}
      />
    </>
  )
}

function LinhaDoEspaco({ entrega, nome }: { entrega: Entrega; nome: string }) {
  const atual = entrega.arquivos[0]
  if (!atual) return null
  return (
    <LinhaArquivo
      titulo={`${nome} · ${atual.nomeOriginal}`}
      meta={`v${atual.versao} · ${atual.enviadoPor?.nome ?? '—'} · ${dataHora(atual.enviadoEm)}`}
      rodape={<span className="font-semibold text-secao-texto">{entrega.status === 'ENVIADO' ? 'Recebido' : nomeStatus(entrega.status)}</span>}
      acoes={
        <button type="button" className="text-xs font-bold hover:underline" onClick={() => baixarArquivo(atual)} aria-label={`Baixar ${nome}`}>
          Baixar
        </button>
      }
    />
  )
}

export function MateriaisDaMarca({ parceriaId, rbs }: { parceriaId: number; rbs: boolean }) {
  const espacos = useDados(() => obterEspacos(parceriaId, 'APLICACOES'), [parceriaId])
  useRecarregarSeAbaFechou(espacos)
  const [enviando, setEnviando] = useState<PapelDoEspaco | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  async function enviar(papel: PapelDoEspaco, arquivo: File) {
    setEnviando(papel)
    setFalha(null)
    setFeito(null)
    try {
      const enviado = await enviarAoEspaco(parceriaId, papel, arquivo)
      setFeito(`${papel === 'LOGO' ? 'Logo' : 'MIV'} recebido como versão ${enviado.versao}.`)
      espacos.recarregar()
    } catch (erro) {
      setFalha(erro instanceof ErroApi ? (Object.values(erro.campos)[0] ?? erro.message) : 'Não foi possível enviar o arquivo.')
    } finally {
      setEnviando(null)
    }
  }

  return (
    <Cartao titulo="Enviar materiais da marca">
      <p className="mb-4 text-sm font-medium text-texto-2">
        A logo e o manual de identidade visual (MIV) da marca, em PNG, JPG, PDF ou ZIP, até 20 MB. Arquivos .svg e .ai vão dentro de um .zip.
      </p>
      {!rbs && (
        <div className="mb-4 flex flex-wrap gap-3">
          <Seletor papel="LOGO" rotulo="Selecionar logo" aoEscolher={enviar} enviando={enviando === 'LOGO'} />
          <Seletor papel="MIV" rotulo="Selecionar MIV" aoEscolher={enviar} enviando={enviando === 'MIV'} />
        </div>
      )}
      {falha && <Aviso tipo="falha" titulo={falha} className="mb-4" />}
      {feito && (
        <p className="mb-4 text-sm font-semibold text-secao-texto" role="status">
          {feito}
        </p>
      )}
      <Carregando dados={espacos}>
        {({ logo, miv }) =>
          logo || miv ? (
            <div className="flex flex-col gap-2">
              <p className="text-sm font-semibold">Arquivos enviados</p>
              {logo && <LinhaDoEspaco entrega={logo} nome="Logo" />}
              {miv && <LinhaDoEspaco entrega={miv} nome="MIV" />}
            </div>
          ) : (
            <Aviso tipo="vazio" titulo={rbs ? 'A marca ainda não enviou a logo nem o MIV' : 'Você ainda não enviou a logo nem o MIV'} />
          )
        }
      </Carregando>
    </Cartao>
  )
}
