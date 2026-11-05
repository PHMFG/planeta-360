// Mídia e conteúdo, "Posts e links" (R4.3 e R5.3.b): os links dos posts que a RBS carregou com a veiculação e os que foram
// enviados como material. Cada um abre em aba nova, só se for http(s), com o domínio à vista; a marca só vê e clica.
import { listarLinksDeMidia, type LinkDeMidia } from '../api/materiais'
import { useDados } from '../api/useDados'
import { useRecarregarSeAbaFechou } from '../paginas/parceria/ContextoParceria'
import { data } from '../util/formatar'
import { Cartao } from './Cartao'
import { Carregando } from './Carregando'
import { LinkExterno } from './LinkExterno'

function descricao(link: LinkDeMidia) {
  if (link.origem === 'CARGA') return [link.veiculo, link.formato, link.espacoPrograma].filter(Boolean).join(' · ') || 'Veiculação'
  return [link.titulo, link.entregaTitulo].filter(Boolean).join(' · ') || 'Material enviado'
}

export function PostsELinks({ parceriaId }: { parceriaId: number }) {
  const links = useDados(() => listarLinksDeMidia(parceriaId), [parceriaId])
  useRecarregarSeAbaFechou(links)

  return (
    <Cartao titulo="Posts e links">
      <Carregando dados={links} vazio={(dado) => dado.itens.length === 0} textoVazio="Nenhum link de post ainda. Eles aparecem quando a RBS carrega a veiculação ou quando alguém envia um link.">
        {({ itens }) => (
          <ul className="flex flex-col divide-y divide-borda">
            {itens.map((link, indice) => (
              <li key={`${link.origem}-${link.url}-${indice}`} className="flex flex-wrap items-center justify-between gap-2 py-3 text-sm">
                <div className="min-w-0">
                  <LinkExterno href={link.url} className="font-semibold text-secao-texto hover:underline">
                    {link.host}
                  </LinkExterno>
                  <p className="text-xs font-medium text-texto-2 [overflow-wrap:anywhere]">{descricao(link)}</p>
                </div>
                <span className="text-xs font-medium text-texto-2">
                  {link.origem === 'CARGA' ? 'Publicado pela RBS' : `Enviado por ${link.enviadoPor?.nome ?? '—'}`} · {data(link.data)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Carregando>
    </Cartao>
  )
}
