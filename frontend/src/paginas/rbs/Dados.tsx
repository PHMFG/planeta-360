// Dados (plano da base da RBS, M6): a base de mídia de todas as marcas da edição num lugar só, com a marca e a cota como
// filtros. É a planilha que a RBS montava à mão, agora vinda das importações; o download leva os filtros da tela.
import { useState } from 'react'
import { Link } from 'react-router'
import { listarEventos, listarParcerias } from '../../api/parcerias'
import { useDados } from '../../api/useDados'
import { BaseDeMidia } from '../../componentes/BaseDeMidia'
import { CabecalhoSecao } from '../../componentes/CabecalhoSecao'
import { Seletor } from '../../componentes/Formulario'

export function Dados() {
  const eventos = useDados(() => listarEventos(), [])
  const [eventoId, setEventoId] = useState('')
  const edicao = eventoId || String(eventos.dado?.[0]?.id ?? '')
  const parcerias = useDados(() => (edicao ? listarParcerias({ eventoId: Number(edicao) }) : Promise.resolve([])), [edicao])

  return (
    <div data-secao="neutro" className="flex flex-col gap-4">
      <CabecalhoSecao
        trilha="Planeta Atlântida / RBS · Gestão"
        titulo="Dados"
        subtitulo="O contratado e o entregue de todas as marcas, com impacto e comprovantes. Filtre e baixe a planilha."
        acoes={
          <Link to="/rbs/importar" viewTransition className="text-[13px] font-semibold text-white underline-offset-4 hover:underline">
            Importar um arquivo →
          </Link>
        }
      />
      {(eventos.dado?.length ?? 0) > 1 && (
        <div className="max-w-xs">
          <Seletor rotulo="Edição" value={edicao} onChange={(evento) => setEventoId(evento.target.value)}>
            {(eventos.dado ?? []).map((item) => (
              <option key={item.id} value={item.id}>
                {item.nome} {item.edicao}
              </option>
            ))}
          </Seletor>
        </div>
      )}
      {edicao && (
        <BaseDeMidia
          key={edicao}
          modo="edicao"
          eventoId={Number(edicao)}
          edicao={(eventos.dado ?? []).find((item) => String(item.id) === edicao)?.edicao}
          parcerias={(parcerias.dado ?? []).map((item) => ({ id: item.id, nome: item.patrocinador.nome })).sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'))}
        />
      )}
    </div>
  )
}
