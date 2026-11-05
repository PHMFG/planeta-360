// Rotas do portal: acesso público, as oito seções da parceria e as telas de gestão da RBS. [ITEM-7]
import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { Aviso } from './componentes/Aviso'
import { LinkBotao } from './componentes/Botao'
import { Layout } from './componentes/Layout'
import { SECOES, type ChaveSecao } from './componentes/secoes'
import { Ativar } from './paginas/acesso/Ativar'
import { Login } from './paginas/acesso/Login'
import { Aplicacoes } from './paginas/parceria/Aplicacoes'
import { ContextoParceria } from './paginas/parceria/ContextoParceria'
import { HistoricoParceria } from './paginas/parceria/HistoricoParceria'
import { Midia } from './paginas/parceria/Midia'
import { MinhaParceria } from './paginas/parceria/MinhaParceria'
import { PaginaAba } from './paginas/parceria/PaginaAba'
import { Perfil } from './paginas/parceria/Perfil'
import { Pesquisa } from './paginas/parceria/Pesquisa'
import { Contrato } from './paginas/parceria/Contrato'
import { Cortesias } from './paginas/parceria/Cortesias'
import { Credenciamento } from './paginas/parceria/Credenciamento'
import { PaginaAtlas } from './atlas/PaginaAtlas'
import { RelatorioAtlas } from './paginas/parceria/RelatorioAtlas'
import { RelatorioParceria } from './paginas/parceria/RelatorioParceria'
import { AtlasRbs } from './paginas/rbs/Atlas'
import { Cargas } from './paginas/rbs/Cargas'
import { Fila } from './paginas/rbs/Fila'
import { HistoricoGeral } from './paginas/rbs/HistoricoGeral'
import { Painel } from './paginas/rbs/Painel'
import { Patrocinadores } from './paginas/rbs/Patrocinadores'
import { PesquisaRbs } from './paginas/rbs/Pesquisa'
import { Relatorios } from './paginas/rbs/Relatorios'
import { RotaProtegida, TelaCarregando, telaInicial, useSessao } from './sessao/Sessao'

// Cada seção do menu e a tela que a mostra. Todas as abas têm tela própria; a PaginaAba genérica fica de reserva.
const TELAS: Partial<Record<ChaveSecao, ReactNode>> = {
  parceria: <MinhaParceria />,
  perfil: <Perfil />,
  aplicacoes: <Aplicacoes />,
  midia: <Midia />,
  pesquisa: <Pesquisa />,
  cortesias: <Cortesias />,
  credenciamento: <Credenciamento />,
  contrato: <Contrato />,
}

function Inicio() {
  const { usuario, carregando } = useSessao()
  if (carregando) return <TelaCarregando />
  return <Navigate to={usuario ? telaInicial(usuario) : '/entrar'} replace />
}

function TelaAviso({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <div className="grid min-h-screen place-items-center p-8">
      <div className="flex max-w-lg flex-col items-start gap-4">
        <Aviso tipo="restrito" titulo={titulo}>
          {texto}
        </Aviso>
        <LinkBotao to="/" variante="secundario">
          Voltar para a tela inicial
        </LinkBotao>
      </div>
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
        {SECOES.map((secao) => {
          const tela = TELAS[secao.chave] ?? <PaginaAba secao={secao} />
          return secao.caminho ? (
            <Route key={secao.chave} path={secao.caminho} element={tela} />
          ) : (
            <Route key={secao.chave} index element={tela} />
          )
        })}
        <Route path="relatorio" element={<RelatorioParceria />} />
        <Route path="relatorios-atlas/:relatorioId" element={<RelatorioAtlas />} />
        <Route path="atlas" element={<PaginaAtlas />} />
        <Route path="atlas/:conversaId" element={<PaginaAtlas />} />
        <Route path="historico" element={<HistoricoParceria />} />
      </Route>

      <Route
        path="/rbs"
        element={
          <RotaProtegida somenteRbs>
            <Layout parceria={null} />
          </RotaProtegida>
        }
      >
        <Route index element={<Painel />} />
        <Route path="patrocinadores" element={<Patrocinadores />} />
        <Route path="fila" element={<Fila />} />
        <Route path="cargas" element={<Cargas />} />
        <Route path="pesquisa" element={<PesquisaRbs />} />
        <Route path="relatorios" element={<Relatorios />} />
        <Route path="historico" element={<HistoricoGeral />} />
        <Route path="atlas" element={<AtlasRbs />} />
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
