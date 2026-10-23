// Rotas do portal: acesso público, as oito seções da parceria e as telas de gestão da RBS. [ITEM-7]
import { Navigate, Route, Routes } from 'react-router'
import { Aviso } from './componentes/Aviso'
import { Layout } from './componentes/Layout'
import { GESTAO_RBS, SECOES } from './componentes/secoes'
import { Ativar } from './paginas/acesso/Ativar'
import { Login } from './paginas/acesso/Login'
import { ContextoParceria } from './paginas/parceria/ContextoParceria'
import { PaginaSecao } from './paginas/parceria/PaginaSecao'
import { PaginaGestao } from './paginas/rbs/PaginaGestao'
import { RotaProtegida, TelaCarregando, telaInicial, useSessao } from './sessao/Sessao'

const SUBTITULOS_RBS: Record<string, string> = {
  '/rbs': 'Resumo das parcerias, materiais na fila e últimas atualizações.',
  '/rbs/patrocinadores': 'Cadastre patrocinadores, cotas e e-mails autorizados.',
  '/rbs/fila': 'Materiais enviados pelas marcas, à espera da análise da RBS.',
  '/rbs/cargas': 'Envie as planilhas de veiculação e de pesquisa e veja o resultado de cada carga.',
  '/rbs/relatorios': 'Gere, valide e disponibilize o relatório de cada parceria.',
  '/rbs/historico': 'Quem fez o quê, e quando, em todas as parcerias.',
}

function Inicio() {
  const { usuario, carregando } = useSessao()
  if (carregando) return <TelaCarregando />
  return <Navigate to={usuario ? telaInicial(usuario) : '/entrar'} replace />
}

function TelaAviso({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <div className="grid min-h-screen place-items-center p-8">
      <Aviso tipo="restrito" titulo={titulo} className="max-w-lg">
        {texto}
      </Aviso>
    </div>
  )
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<Inicio />} />
      <Route path="/entrar" element={<Login />} />
      <Route path="/ativar" element={<Ativar />} />

      <Route
        path="/parceria/:parceriaId"
        element={
          <RotaProtegida>
            <ContextoParceria />
          </RotaProtegida>
        }
      >
        {SECOES.map((secao) =>
          secao.caminho ? (
            <Route key={secao.chave} path={secao.caminho} element={<PaginaSecao secao={secao} />} />
          ) : (
            <Route key={secao.chave} index element={<PaginaSecao secao={secao} />} />
          ),
        )}
      </Route>

      <Route
        path="/rbs"
        element={
          <RotaProtegida somenteRbs>
            <Layout parceria={null} />
          </RotaProtegida>
        }
      >
        {GESTAO_RBS.map((atalho) => {
          const pagina = <PaginaGestao titulo={atalho.nome} subtitulo={SUBTITULOS_RBS[atalho.caminho] ?? ''} />
          const trecho = atalho.caminho.replace('/rbs', '').replace(/^\//, '')
          return trecho ? (
            <Route key={atalho.caminho} path={trecho} element={pagina} />
          ) : (
            <Route key={atalho.caminho} index element={pagina} />
          )
        })}
      </Route>

      <Route
        path="/sem-parceria"
        element={
          <TelaAviso
            titulo="Nenhuma parceria vinculada"
            texto="Seu e-mail ainda não está ligado a uma parceria ativa. Fale com o time B2B da RBS."
          />
        }
      />
      <Route
        path="*"
        element={<TelaAviso titulo="Página não encontrada" texto="Confira o endereço ou volte para a tela inicial." />}
      />
    </Routes>
  )
}
