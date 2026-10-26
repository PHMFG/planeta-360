// Lista de patrocinadores da RBS (protótipo R03): parcerias da edição, andamento das entregas e acesso ao cadastro. [ITEM-3]
import { useState } from 'react'
import { Link } from 'react-router'
import { listarEventos, listarParcerias, type ParceriaNaLista } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { Barra } from '../../componentes/Barra'
import { Botao } from '../../componentes/Botao'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Cartao, Grade3, Indicador } from '../../componentes/Cartao'
import { Carregando } from '../../componentes/Carregando'
import { Busca, Filtros, Seletor } from '../../componentes/Formulario'
import { Status } from '../../componentes/Status'
import { Tabela } from '../../componentes/Tabela'
import { CadastroPatrocinador } from './CadastroPatrocinador'

type FiltroCota = 'TODAS' | ParceriaNaLista['cota']['codigo']

export function Patrocinadores() {
  const eventos = useDados(listarEventos, [])
  const [eventoId, setEventoId] = useState('')
  const [busca, setBusca] = useState('')
  const [cota, setCota] = useState<FiltroCota>('TODAS')
  const [cadastrando, setCadastrando] = useState(false)
  const lista = useDados(() => listarParcerias({ eventoId: eventoId ? Number(eventoId) : undefined }), [eventoId])

  const todas = lista.dado ?? []
  const contar = (codigo: FiltroCota) => todas.filter((item) => codigo === 'TODAS' || item.cota.codigo === codigo).length
  const visiveis = todas.filter(
    (item) =>
      (cota === 'TODAS' || item.cota.codigo === cota) &&
      item.patrocinador.nome.toLowerCase().includes(busca.trim().toLowerCase()),
  )
  const pendentes = todas.reduce((soma, item) => soma + item.entregas.pendentes, 0)
  const usuarios = todas.reduce((soma, item) => soma + (item.usuarios ?? 0), 0)

  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Patrocinadores"
        subtitulo="Cadastre patrocinadores, cotas e e-mails autorizados, e acompanhe cada parceria."
        acoes={
          <Botao className="bg-white! text-tinta!" icone="ri-add-line" onClick={() => setCadastrando(true)} disabled={!eventos.dado}>
            Cadastrar patrocinador
          </Botao>
        }
      />

      <Grade3>
        <Indicador
          rotulo="Parcerias"
          valor={todas.length}
          legenda={`${contar('MASTER')} Master · ${contar('SUBCOTA_G')} Subcota G · ${contar('SUBCOTA')} Subcota`}
        />
        <Indicador rotulo="Entregas pendentes" valor={pendentes} legenda="Somando todas as parcerias da lista" />
        <Indicador rotulo="Usuários com acesso" valor={usuarios} legenda="E-mails que já fizeram o primeiro acesso" />
      </Grade3>

      <Cartao>
        <div className="mb-4 grid gap-4 md:grid-cols-[1fr_260px]">
          <Busca valor={busca} aoMudar={setBusca} placeholder="Buscar patrocinador pelo nome…" />
          <Seletor rotulo="Edição" value={eventoId} onChange={(evento) => setEventoId(evento.target.value)}>
            <option value="">Todas as edições</option>
            {(eventos.dado ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
        </div>
        <div className="mb-4">
          <Filtros<FiltroCota>
            valor={cota}
            aoMudar={setCota}
            opcoes={[
              { valor: 'TODAS', nome: 'Todas', total: contar('TODAS') },
              { valor: 'MASTER', nome: 'Master', total: contar('MASTER') },
              { valor: 'SUBCOTA_G', nome: 'Subcota G', total: contar('SUBCOTA_G') },
              { valor: 'SUBCOTA', nome: 'Subcota', total: contar('SUBCOTA') },
            ]}
          />
        </div>
        <Carregando dados={lista}>
          {() => (
            <Tabela<ParceriaNaLista>
              linhas={visiveis}
              chave={(item) => item.id}
              vazio="Nenhuma parceria encontrada com esses filtros."
              colunas={[
                {
                  titulo: 'Marca',
                  celula: (item) => (
                    <Link to={`/parceria/${item.id}`} className="font-semibold hover:underline">
                      {item.patrocinador.nome}
                      {item.nomeProduto && <span className="block text-xs font-medium text-texto-2">{item.nomeProduto}</span>}
                    </Link>
                  ),
                },
                { titulo: 'Cota', celula: (item) => item.cota.nome },
                { titulo: 'Edição', celula: (item) => item.evento.edicao },
                { titulo: 'Entregas aprovadas', celula: (item) => <Barra valor={item.entregas.percentualConcluido} rotulo="Entregas aprovadas" /> },
                { titulo: 'Pendências', celula: (item) => item.entregas.pendentes, classe: 'text-center' },
                { titulo: 'Usuários', celula: (item) => item.usuarios ?? '—', classe: 'text-center' },
                {
                  titulo: 'Relatório',
                  celula: (item) =>
                    item.relatorio ? <Status codigo={item.relatorio.status} complemento={`v${item.relatorio.versao}`} /> : <span className="text-xs text-texto-2">Sem relatório</span>,
                },
                {
                  titulo: '',
                  celula: (item) => (
                    <Link to={`/parceria/${item.id}`} className="text-xs font-bold whitespace-nowrap hover:underline">
                      Abrir →
                    </Link>
                  ),
                },
              ]}
            />
          )}
        </Carregando>
      </Cartao>

      {eventos.dado && (
        <CadastroPatrocinador
          aberto={cadastrando}
          aoFechar={() => setCadastrando(false)}
          aoCriar={lista.recarregar}
          eventos={eventos.dado}
          parcerias={todas}
        />
      )}
    </div>
  )
}
