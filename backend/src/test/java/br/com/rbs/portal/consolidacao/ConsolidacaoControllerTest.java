package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa a rota 29 (consolidação: previsto x exibido e faltantes) e a rota 30 (painel da RBS) como o front as usa: login pela
 * rota e sessão de verdade. Atende os itens 5 e 7 da Entrega 2. [RELATORIO] [ISOLAMENTO] [PERMISSAO] Os números esperados
 * são conferidos por SQL direto nas tabelas (não pelas visões que a API usa). Cada teste é desfeito no fim (@Transactional).
 * Na amostra: parceria do Amarelo 2027 = só INSERCAO; Roxo = INSERCAO e CPM; "Cliente Amarelo" 2026 é uma parceria encerrada,
 * sem usuário, plano nem entregas.
 */
@TesteDeIntegracao
@Transactional
class ConsolidacaoControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA_DO_AMARELO = "atendimento@agencia-x.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String VERDE = "marketing@verde.example";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    long parceria(String patrocinador, int edicao) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :nome AND e.edicao = :edicao
                        """).param("nome", patrocinador).param("edicao", edicao).query(Long.class).single();
    }

    long parceria(String patrocinador) {
        return parceria(patrocinador, 2027);
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    MvcTestResult consolidacao(SessaoDeApi sessao, long parceriaId) {
        return sessao.get("/api/parcerias/" + parceriaId + "/consolidacao");
    }

    MvcTestResult painel(SessaoDeApi sessao, String consulta) {
        return sessao.get("/api/painel" + consulta);
    }

    static BigDecimal numero(Object valorDoJson) {
        return new BigDecimal(String.valueOf(valorDoJson));
    }

    BigDecimal somar(String sql, long parceriaId, String unidade) {
        return jdbc.sql(sql).param("p", parceriaId).param("u", unidade).query(BigDecimal.class).single();
    }

    /** O previsto de uma unidade, direto da tabela do plano comercial. */
    BigDecimal previsto(long parceriaId, String unidade) {
        return somar("SELECT sum(quantidade_prevista) FROM aproveitamento_previsto "
                + "WHERE parceria_id = :p AND categoria = 'MIDIA' AND unidade = :u", parceriaId, unidade);
    }

    /** O exibido de uma unidade, direto da tabela de veiculações. */
    BigDecimal exibido(long parceriaId, String unidade) {
        return somar("SELECT coalesce(sum(v.quantidade), 0) FROM veiculacao v JOIN aproveitamento_previsto ap "
                + "ON ap.id = v.aproveitamento_id WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA' AND ap.unidade = :u",
                parceriaId, unidade);
    }

    /** Insere uma entrega na aba PERFIL; {@code dias} é o prazo em relação a hoje (Brasília); nulo = sem prazo. */
    void entrega(long parceriaId, String status, Integer dias) {
        jdbc.sql("""
                        INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo)
                        SELECT :p, a.id, 'Entrega de teste', :status,
                               CASE WHEN CAST(:dias AS int) IS NULL THEN NULL
                                    ELSE (now() AT TIME ZONE 'America/Sao_Paulo')::date + CAST(:dias AS int) END
                        FROM aba a WHERE a.codigo = 'PERFIL'
                        """).param("p", parceriaId).param("status", status).param("dias", dias).update();
    }

    /** Uma parceria nova da edição 2027, sem plano, sem entrega, sem cortesia e sem usuário. */
    long criarParceriaVazia(String situacao) {
        long patrocinador = jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente Sem Nada') RETURNING id")
                .query(Long.class).single();
        return jdbc.sql("""
                        INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, situacao)
                        VALUES (:e, :p, 3, :s) RETURNING id
                        """).param("e", evento(2027)).param("p", patrocinador).param("s", situacao).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // Rota 29: previsto x exibido
    // ---------------------------------------------------------------------

    @Test
    void amareloVeOPrevistoEOExibidoDeUmaUnidadeIgualAoBanco() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(AMARELO);

        MvcTestResult resposta = consolidacao(sessao, amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(((Number) lerJson(resposta, "$.parceriaId")).longValue()).isEqualTo(amarelo);

        // Conferência por SQL direto nas tabelas: 1113 previstos e 1105 exibidos em inserções.
        BigDecimal previstoNoBanco = previsto(amarelo, "INSERCAO");
        BigDecimal exibidoNoBanco = exibido(amarelo, "INSERCAO");
        assertThat(previstoNoBanco).isEqualByComparingTo("1113");
        assertThat(exibidoNoBanco).isEqualByComparingTo("1105");
        assertThat(lerLista(resposta, "$.midia.porUnidade")).hasSize(1);
        assertThat(lerJson(resposta, "$.midia.porUnidade[0].unidade")).isEqualTo("INSERCAO");
        assertThat(numero(lerJson(resposta, "$.midia.porUnidade[0].previsto"))).isEqualByComparingTo(previstoNoBanco);
        assertThat(numero(lerJson(resposta, "$.midia.porUnidade[0].exibido"))).isEqualByComparingTo(exibidoNoBanco);
        assertThat(numero(lerJson(resposta, "$.midia.porUnidade[0].percentual"))).isEqualByComparingTo("99.2");   // 1105 de 1113 = 99,28%: cortado, e não arredondado

        // Aplicações de marca, entregas e cortesias, também conferidas nas tabelas.
        BigDecimal previstas = jdbc.sql("SELECT sum(quantidade_prevista) FROM aproveitamento_previsto "
                + "WHERE parceria_id = :p AND categoria = 'APLICACAO_MARCA'").param("p", amarelo).query(BigDecimal.class).single();
        long fotos = jdbc.sql("SELECT count(*) FROM comprovacao_marca WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single();
        BigDecimal comprovadas = jdbc.sql("""
                        SELECT sum(least(ap.quantidade_prevista, coalesce(f.n, 0)))
                        FROM aproveitamento_previsto ap
                        LEFT JOIN (SELECT aproveitamento_id, count(*) AS n FROM comprovacao_marca GROUP BY 1) f
                               ON f.aproveitamento_id = ap.id
                        WHERE ap.parceria_id = :p AND ap.categoria = 'APLICACAO_MARCA'
                        """).param("p", amarelo).query(BigDecimal.class).single();
        assertThat(numero(lerJson(resposta, "$.aplicacoes.previstas"))).isEqualByComparingTo(previstas).isEqualByComparingTo("19");
        assertThat(numero(lerJson(resposta, "$.aplicacoes.comprovadas"))).isEqualByComparingTo(comprovadas).isEqualByComparingTo("17");
        assertThat(((Number) lerJson(resposta, "$.aplicacoes.fotos")).longValue()).isEqualTo(fotos);
        assertThat(numero(lerJson(resposta, "$.aplicacoes.percentual"))).isEqualByComparingTo("89.5");

        long aprovadas = jdbc.sql("SELECT count(*) FROM entrega WHERE parceria_id = :p AND status = 'APROVADO'")
                .param("p", amarelo).query(Long.class).single();
        long total = jdbc.sql("SELECT count(*) FROM entrega WHERE parceria_id = :p").param("p", amarelo).query(Long.class).single();
        assertThat(((Number) lerJson(resposta, "$.entregas.total")).longValue()).isEqualTo(total).isEqualTo(11);
        assertThat(((Number) lerJson(resposta, "$.entregas.aprovadas")).longValue()).isEqualTo(aprovadas).isEqualTo(10);
        assertThat(lerJson(resposta, "$.entregas.pendentes")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.entregas.naoAplicaveis")).isEqualTo(0);
        assertThat(numero(lerJson(resposta, "$.entregas.percentualConcluido"))).isEqualByComparingTo("90.9");

        long disponiveis = jdbc.sql("SELECT sum(quantidade) FROM cortesia_cota WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single();
        long destinadas = jdbc.sql("""
                        SELECT sum(d.quantidade) FROM cortesia_destinacao d JOIN cortesia_cota c ON c.id = d.cortesia_cota_id
                        WHERE c.parceria_id = :p
                        """).param("p", amarelo).query(Long.class).single();
        assertThat(((Number) lerJson(resposta, "$.cortesias.disponiveis")).longValue()).isEqualTo(disponiveis).isEqualTo(150);
        assertThat(((Number) lerJson(resposta, "$.cortesias.destinadas")).longValue()).isEqualTo(destinadas).isEqualTo(143);
        assertThat(numero(lerJson(resposta, "$.cortesias.percentual"))).isEqualByComparingTo("95.3");
    }

    @Test
    void unidadesDiferentesNuncaSaoSomadasEOsTotaisFechamPorVeiculoEPorLinha() {
        long roxo = parceria("Cliente Roxo");
        SessaoDeApi sessao = entrar(ROXO);

        MvcTestResult resposta = consolidacao(sessao, roxo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        // O Roxo compra inserções e CPM: dois totais separados, inserções primeiro.
        assertThat(lerLista(resposta, "$.midia.porUnidade[*].unidade")).containsExactly("INSERCAO", "CPM");
        for (String unidade : List.of("INSERCAO", "CPM")) {
            Map<String, Object> total = unidade(resposta, unidade);
            assertThat(numero(total.get("previsto"))).as("previsto " + unidade).isEqualByComparingTo(previsto(roxo, unidade));
            assertThat(numero(total.get("exibido"))).as("exibido " + unidade).isEqualByComparingTo(exibido(roxo, unidade));
        }
        assertThat(numero(unidade(resposta, "INSERCAO").get("previsto"))).isEqualByComparingTo("343");
        assertThat(numero(unidade(resposta, "CPM").get("previsto"))).isEqualByComparingTo("460000");
        assertThat(numero(unidade(resposta, "CPM").get("percentual"))).isEqualByComparingTo("97.2");

        // Por veículo: cada par (veículo, unidade) bate com o banco, e a soma de cada unidade fecha com o total dela.
        Map<String, BigDecimal> previstoPorVeiculo = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT veiculo || '|' || unidade AS chave, sum(quantidade_prevista) AS previsto
                        FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA' GROUP BY 1
                        """).param("p", roxo).query().listOfRows()
                .forEach(l -> previstoPorVeiculo.put((String) l.get("chave"), (BigDecimal) l.get("previsto")));
        List<Map<String, Object>> porVeiculo = lerLista(resposta, "$.midia.porVeiculo");
        assertThat(porVeiculo).hasSize(previstoPorVeiculo.size());
        for (Map<String, Object> v : porVeiculo) {
            assertThat(numero(v.get("previsto"))).isEqualByComparingTo(previstoPorVeiculo.get(v.get("veiculo") + "|" + v.get("unidade")));
        }
        for (String unidade : List.of("INSERCAO", "CPM")) {
            assertThat(porVeiculo.stream().filter(v -> unidade.equals(v.get("unidade"))).map(v -> numero(v.get("exibido")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).as("soma por veículo, " + unidade)
                    .isEqualByComparingTo(exibido(roxo, unidade));
        }

        // Por linha do plano: uma por linha de mídia, e a soma de cada unidade fecha com o total dela.
        long linhasNoBanco = jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'")
                .param("p", roxo).query(Long.class).single();
        List<Map<String, Object>> linhas = lerLista(resposta, "$.midia.linhas");
        assertThat(linhas).hasSize((int) linhasNoBanco);
        for (String unidade : List.of("INSERCAO", "CPM")) {
            assertThat(linhas.stream().filter(l -> unidade.equals(l.get("unidade"))).map(l -> numero(l.get("exibido")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).as("soma por linha, " + unidade)
                    .isEqualByComparingTo(exibido(roxo, unidade));
        }
        // A linha do plano que nunca foi exibida (o reels pós-festival) sai com exibido 0 e 0% (nunca nulo).
        Map<String, Object> semExibicao = linhas.stream().filter(l -> String.valueOf(l.get("formato")).contains("pós festival"))
                .findFirst().orElseThrow();
        assertThat(numero(semExibicao.get("exibido"))).isEqualByComparingTo("0");
        assertThat(numero(semExibicao.get("percentual"))).isEqualByComparingTo("0");
        assertThat(semExibicao).containsKeys("aproveitamentoId", "veiculo", "formato", "espacoPrograma", "previsto");
    }

    private static Map<String, Object> unidade(MvcTestResult resposta, String unidade) {
        List<Map<String, Object>> totais = lerLista(resposta, "$.midia.porUnidade");
        return totais.stream().filter(t -> unidade.equals(t.get("unidade"))).findFirst().orElseThrow();
    }

    @Test
    void patrocinadorSoVeOsDoisTiposDeFaltanteQueEleResolveEARbsVeTodos() {
        long amarelo = parceria("Cliente Amarelo");
        // A marca mandou só 2 das 3 perguntas: agora existe também o faltante PESQUISA_SEM_PERGUNTAS.
        jdbc.sql("DELETE FROM pesquisa_pergunta WHERE parceria_id = :p AND ordem = 3").param("p", amarelo).update();
        List<String> todosNoBanco = jdbc.sql("SELECT DISTINCT tipo FROM vw_faltantes WHERE parceria_id = :p")
                .param("p", amarelo).query(String.class).list();
        assertThat(todosNoBanco).as("a amostra tem faltantes que a marca não pode ver")
                .contains("MIDIA_ABAIXO_DO_PREVISTO", "APLICACAO_SEM_COMPROVACAO", "FESTIVAL_SEM_NUMEROS");

        MvcTestResult daMarca = consolidacao(entrar(AMARELO), amarelo);
        MvcTestResult daAgencia = consolidacao(entrar(AGENCIA_DO_AMARELO), amarelo);
        MvcTestResult daRbs = consolidacao(entrar(RBS), amarelo);

        for (MvcTestResult r : List.of(daMarca, daAgencia)) {
            assertThat(r).hasStatus(HttpStatus.OK);
            assertThat(lerLista(r, "$.faltantes[*].tipo")).containsExactly("PESQUISA_SEM_PERGUNTAS", "ENTREGA_PENDENTE");
            assertThat(lerLista(r, "$.faltantes[*].detalhe")).containsExactly(
                    "Cota Master sem as 3 perguntas cadastradas", "Peça de 30\" para mídia própria (em analise)");
            // O filtro vale para o corpo inteiro: nenhum faltante interno vaza nem no texto da resposta.
            assertThat(corpo(r)).doesNotContain("MIDIA_ABAIXO_DO_PREVISTO").doesNotContain("APLICACAO_SEM_COMPROVACAO")
                    .doesNotContain("FESTIVAL_SEM_NUMEROS").doesNotContain("Planeta Lounge");
        }

        // A RBS vê todos os faltantes da parceria, os mesmos da visão do banco.
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        List<Map<String, Object>> doBanco = jdbc.sql("SELECT tipo, detalhe FROM vw_faltantes WHERE parceria_id = :p")
                .param("p", amarelo).query().listOfRows();
        List<Map<String, Object>> daRbsLista = lerLista(daRbs, "$.faltantes");
        assertThat(daRbsLista).hasSameSizeAs(doBanco).hasSizeGreaterThan(2);
        assertThat(daRbsLista.stream().map(f -> f.get("tipo") + "|" + f.get("detalhe")))
                .containsExactlyInAnyOrderElementsOf(doBanco.stream().map(f -> f.get("tipo") + "|" + f.get("detalhe")).toList());
        // Do mais para o menos importante: exibição que falta antes de entrega pendente, que vem antes de número do festival.
        List<String> tipos = lerLista(daRbs, "$.faltantes[*].tipo");
        assertThat(tipos.indexOf("MIDIA_ABAIXO_DO_PREVISTO")).isLessThan(tipos.indexOf("ENTREGA_PENDENTE"));
        assertThat(tipos.indexOf("ENTREGA_PENDENTE")).isLessThan(tipos.indexOf("FESTIVAL_SEM_NUMEROS"));

        // Os números da consolidação são os mesmos para a marca e para a RBS (o filtro é só nos faltantes).
        for (String parte : List.of("midia", "aplicacoes", "entregas", "cortesias")) {
            assertThat(lerJson(daMarca, "$." + parte)).as(parte).isEqualTo(lerJson(daRbs, "$." + parte));
        }
    }

    @Test
    void marcaSemNenhumFaltanteQueEstejaNaListaDaMarcaRecebeListaVazia() {
        long roxo = parceria("Cliente Roxo");
        // O Roxo (Subcota G) não tem pesquisa; só lhe resta a entrega pendente. Quando ela é aprovada, a lista fica vazia.
        SessaoDeApi sessao = entrar(ROXO);
        assertThat(lerLista(consolidacao(sessao, roxo), "$.faltantes[*].tipo")).containsExactly("ENTREGA_PENDENTE");

        jdbc.sql("UPDATE entrega SET status = 'APROVADO' WHERE parceria_id = :p").param("p", roxo).update();

        MvcTestResult resposta = consolidacao(sessao, roxo);
        assertThat(lerLista(resposta, "$.faltantes")).isEmpty();
        assertThat(numero(lerJson(resposta, "$.entregas.percentualConcluido"))).isEqualByComparingTo("100");
    }

    @Test
    void parceriaSemPlanoNemEntregaNemCortesiaVemComListasVaziasETotaisNulos() {
        long vazia = criarParceriaVazia("EM_NEGOCIACAO");

        MvcTestResult resposta = consolidacao(entrar(RBS), vazia);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.midia.porUnidade")).isEmpty();
        assertThat(lerLista(resposta, "$.midia.porVeiculo")).isEmpty();
        assertThat(lerLista(resposta, "$.midia.linhas")).isEmpty();
        for (String campo : List.of("previstas", "comprovadas", "fotos", "percentual")) {
            assertThat(lerJson(resposta, "$.aplicacoes." + campo)).as("aplicacoes." + campo).isNull();
        }
        for (String campo : List.of("total", "aprovadas", "pendentes", "naoAplicaveis", "percentualConcluido")) {
            assertThat(lerJson(resposta, "$.entregas." + campo)).as("entregas." + campo).isNull();
        }
        for (String campo : List.of("disponiveis", "destinadas", "percentual")) {
            assertThat(lerJson(resposta, "$.cortesias." + campo)).as("cortesias." + campo).isNull();
        }
        assertThat(lerLista(resposta, "$.faltantes[*].tipo")).contains("PARCERIA_SEM_USUARIO", "FESTIVAL_SEM_NUMEROS");
    }

    // ---------------------------------------------------------------------
    // Rota 29: isolamento e acesso
    // ---------------------------------------------------------------------

    @Test
    void roxoPedindoAConsolidacaoDoAmareloRecebe404IgualAParceriaQueNaoExiste() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(ROXO);

        MvcTestResult doAmarelo = consolidacao(sessao, amarelo);
        MvcTestResult inexistente = consolidacao(sessao, 999_999L);

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(doAmarelo.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.detail")).as("mesma frase de uma parceria que não existe")
                .isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("Rede Atlântida").doesNotContain("1113").doesNotContain("faltantes");
        assertThat(consolidacao(entrar(RBS), 999_999L)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void agenciaPerdeAConsolidacaoNaHoraQuandoOVinculoSai() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(AGENCIA_DO_AMARELO);
        assertThat(consolidacao(sessao, amarelo)).hasStatus(HttpStatus.OK);

        jdbc.sql("DELETE FROM parceria_usuario WHERE parceria_id = :p AND usuario_id = (SELECT id FROM usuario WHERE email = :e)")
                .param("p", amarelo).param("e", AGENCIA_DO_AMARELO).update();

        // Era o único vínculo da agência: a sessão termina (401 em qualquer rota).
        assertThat(consolidacao(sessao, amarelo)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void semSessaoDevolve401NasDuasRotas() {
        long amarelo = parceria("Cliente Amarelo");

        SessaoDeApi visitante = SessaoDeApi.anonima(mvc);

        assertThat(consolidacao(visitante, amarelo)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(painel(visitante, "")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Rota 30: painel da RBS
    // ---------------------------------------------------------------------

    @Test
    void rbsVeOPainelDaEdicaoMaisNovaComOsNumerosIguaisAoBanco() {
        long evento = evento(2027);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = painel(rbs, "");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(((Number) lerJson(resposta, "$.evento.id")).longValue()).isEqualTo(evento);
        assertThat(lerJson(resposta, "$.evento.nome")).isEqualTo("Planeta Atlântida");
        assertThat(lerJson(resposta, "$.evento.edicao")).isEqualTo(2027);

        // Parcerias: contadas direto na tabela.
        long total = jdbc.sql("SELECT count(*) FROM parceria WHERE evento_id = :e").param("e", evento).query(Long.class).single();
        long ativas = jdbc.sql("SELECT count(*) FROM parceria WHERE evento_id = :e AND situacao = 'ATIVA'").param("e", evento)
                .query(Long.class).single();
        assertThat(((Number) lerJson(resposta, "$.parcerias.total")).longValue()).isEqualTo(total).isEqualTo(3);
        assertThat(((Number) lerJson(resposta, "$.parcerias.ativas")).longValue()).isEqualTo(ativas).isEqualTo(3);
        assertThat(lerJson(resposta, "$.parcerias.semUsuario")).isEqualTo(0);

        // Entregas por status: os 7 status, na ordem do contrato, iguais ao GROUP BY da tabela.
        Map<String, Object> porStatus = castMapa(lerJson(resposta, "$.entregas.porStatus"));
        assertThat(porStatus.keySet()).containsExactly("NAO_INICIADO", "AGUARDANDO_ENVIO", "ENVIADO", "EM_ANALISE",
                "AJUSTE_SOLICITADO", "APROVADO", "NAO_APLICAVEL");
        Map<String, Long> doBanco = new LinkedHashMap<>();
        jdbc.sql("SELECT e.status, count(*) AS n FROM entrega e JOIN parceria pa ON pa.id = e.parceria_id "
                        + "WHERE pa.evento_id = :e GROUP BY 1").param("e", evento).query().listOfRows()
                .forEach(l -> doBanco.put((String) l.get("status"), ((Number) l.get("n")).longValue()));
        porStatus.forEach((status, n) -> assertThat(((Number) n).longValue()).as(status).isEqualTo(doBanco.getOrDefault(status, 0L)));
        assertThat(porStatus.get("APROVADO")).isEqualTo(23);
        assertThat(porStatus.get("ENVIADO")).isEqualTo(1);
        assertThat(porStatus.get("EM_ANALISE")).isEqualTo(1);
        assertThat(porStatus.get("AJUSTE_SOLICITADO")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.entregas.esperandoAnalise")).isEqualTo(2);
        assertThat(lerJson(resposta, "$.entregas.atrasadas")).isEqualTo(0);

        // Relatórios: as 3 parcerias de 2027 têm só o rascunho da amostra.
        assertThat(lerJson(resposta, "$.relatorios.semRelatorio")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.relatorios.rascunho")).isEqualTo(3);
        assertThat(lerJson(resposta, "$.relatorios.validado")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.relatorios.disponibilizado")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.relatorios.marcaVe")).as("nenhuma marca vê relatório ainda").isEqualTo(0);

        // Última carga: a de pesquisa, que é a mais nova da edição; o tipo e os totais vêm da própria linha.
        var carga = jdbc.sql("SELECT id, tipo, linhas_inseridas, linhas_rejeitadas FROM carga WHERE evento_id = :e "
                + "ORDER BY executada_em DESC, id DESC LIMIT 1").param("e", evento).query().singleRow();
        assertThat(((Number) lerJson(resposta, "$.ultimaCarga.id")).longValue()).isEqualTo(((Number) carga.get("id")).longValue());
        assertThat(lerJson(resposta, "$.ultimaCarga.tipo")).isEqualTo(carga.get("tipo")).isEqualTo("PESQUISA");
        assertThat(lerJson(resposta, "$.ultimaCarga.linhasInseridas")).isEqualTo(carga.get("linhas_inseridas")).isEqualTo(9);
        assertThat(lerJson(resposta, "$.ultimaCarga.linhasRejeitadas")).isEqualTo(carga.get("linhas_rejeitadas")).isEqualTo(1);
        assertThat((String) lerJson(resposta, "$.ultimaCarga.executadaEm")).endsWith("-03:00");

        // Pendências: os faltantes de todas as parcerias da edição (aqui são menos de 20, então vêm todos).
        long faltantes = jdbc.sql("SELECT count(*) FROM vw_faltantes f JOIN parceria pa ON pa.id = f.parceria_id WHERE pa.evento_id = :e")
                .param("e", evento).query(Long.class).single();
        assertThat(faltantes).isLessThan(20);
        assertThat(lerLista(resposta, "$.pendencias")).hasSize((int) faltantes);
        assertThat(lerLista(resposta, "$.pendencias[*].patrocinador")).containsOnly("Cliente Amarelo", "Cliente Roxo", "Cliente Verde");
        assertThat(lerLista(resposta, "$.pendencias[?(@.tipo == 'ENTREGA_PENDENTE')].detalhe")).containsExactlyInAnyOrder(
                "Peça de 30\" para mídia própria (em analise)", "VT para os telões dos palcos (ajuste solicitado)",
                "Destinação das cortesias (enviado)");
        Map<String, Object> primeira = castMapa(lerLista(resposta, "$.pendencias").get(0));
        assertThat(primeira).containsOnlyKeys("parceriaId", "patrocinador", "tipo", "detalhe");
    }

    /** Põe o relatório na ordem RASCUNHO, VALIDADO, DISPONIBILIZADO direto no banco (o gatilho confere a ordem). */
    void disponibilizar(long relatorioId, long usuarioId) {
        jdbc.sql("UPDATE relatorio SET status = 'VALIDADO', validado_por = :u, validado_em = now() WHERE id = :id")
                .param("u", usuarioId).param("id", relatorioId).update();
        jdbc.sql("UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = :u, disponibilizado_em = now() WHERE id = :id")
                .param("u", usuarioId).param("id", relatorioId).update();
    }

    @Test
    void marcaVeContaAParceriaComVersaoDisponibilizadaMesmoQuandoAMaisNovaEstaEmRascunho() {
        long amarelo = parceria("Cliente Amarelo");
        long rbsId = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        long v1 = jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", amarelo).query(Long.class).single();
        disponibilizar(v1, rbsId);
        jdbc.sql("SELECT fn_gerar_relatorio(:p, :u)").param("p", amarelo).param("u", rbsId).query(Long.class).single();   // v2, rascunho

        MvcTestResult resposta = painel(entrar(RBS), "");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        // Pela versão mais nova, o Amarelo é um rascunho (v2): o card "disponibilizados" não o conta.
        assertThat(lerJson(resposta, "$.relatorios.rascunho")).isEqualTo(3);
        assertThat(lerJson(resposta, "$.relatorios.disponibilizado")).isEqualTo(0);
        // Mas a marca continua vendo a v1: é isso que marcaVe conta.
        assertThat(lerJson(resposta, "$.relatorios.marcaVe")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.relatorios.semRelatorio")).isEqualTo(0);
        assertThat(entrar(AMARELO).get("/api/parcerias/" + amarelo + "/relatorio")).hasStatus(HttpStatus.OK);
    }

    @Test
    void marcaVeSomaTodasAsParceriasDaEdicaoEIgnoraOutraEdicao() {
        long rbsId = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo")) {
            disponibilizar(jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", parceria(cliente))
                    .query(Long.class).single(), rbsId);
        }

        MvcTestResult daEdicaoNova = painel(entrar(RBS), "");
        MvcTestResult de2026 = painel(entrar(RBS), "?eventoId=" + evento(2026));

        assertThat(lerJson(daEdicaoNova, "$.relatorios.marcaVe")).isEqualTo(2);
        assertThat(lerJson(daEdicaoNova, "$.relatorios.disponibilizado")).isEqualTo(2);
        assertThat(lerJson(de2026, "$.relatorios.marcaVe")).isEqualTo(0);
    }

    @Test
    void percentualDeMidiaNuncaArredondaPara100SemEstarCompleto() {
        long verde = parceria("Cliente Verde");
        long linha = jdbc.sql("""
                        INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, tipo_midia, quantidade_prevista, unidade)
                        VALUES (:p, 'MIDIA', 'Mídia', 'atl.la', 'Display CPM de teste', 'PROPRIA', 460000, 'CPM') RETURNING id
                        """).param("p", verde).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, now() - interval '1 day', 459900)")
                .param("a", linha).update();   // 459.900 de 460.000 = 99,978%

        MvcTestResult incompleto = consolidacao(entrar(VERDE), verde);

        assertThat(incompleto).hasStatus(HttpStatus.OK);
        Map<String, Object> cpm = unidade(incompleto, "CPM");
        assertThat(numero(cpm.get("exibido"))).isEqualByComparingTo("459900");
        assertThat(numero(cpm.get("percentual"))).as("cortado na 1ª casa: 99,9 e nunca 100").isEqualByComparingTo("99.9");
        assertThat(numero(lerLista(incompleto, "$.midia.porVeiculo[?(@.unidade == 'CPM')].percentual").get(0))).isEqualByComparingTo("99.9");
        assertThat(numero(lerLista(incompleto, "$.midia.linhas[?(@.aproveitamentoId == " + linha + ")].percentual").get(0)))
                .isEqualByComparingTo("99.9");

        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, now() - interval '2 day', 100)")
                .param("a", linha).update();
        MvcTestResult completo = consolidacao(entrar(VERDE), verde);
        assertThat(numero(unidade(completo, "CPM").get("percentual"))).as("completo: agora sim, 100").isEqualByComparingTo("100");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMapa(Object mapa) {
        return (Map<String, Object>) mapa;
    }

    @Test
    void painelDeOutraEdicaoPeloParametroEventoId() {
        long edicao2026 = evento(2026);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = painel(rbs, "?eventoId=" + edicao2026);

        // 2026 tem só a parceria encerrada do Amarelo: sem usuário, sem entrega, sem relatório e sem carga.
        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.evento.edicao")).isEqualTo(2026);
        assertThat(lerJson(resposta, "$.parcerias.total")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.parcerias.ativas")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.parcerias.semUsuario")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.entregas.porStatus.APROVADO")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.entregas.esperandoAnalise")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.relatorios.semRelatorio")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.relatorios.rascunho")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.ultimaCarga")).isNull();
        assertThat(lerLista(resposta, "$.pendencias[*].tipo")).containsExactly("PESQUISA_SEM_PERGUNTAS", "PARCERIA_SEM_USUARIO");
    }

    @Test
    void painelComEdicaoQueNaoExisteDevolve404EComIdQueNaoEhNumeroDevolve400() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult inexistente = painel(rbs, "?eventoId=999999");

        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(painel(rbs, "?eventoId=abc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void entregaAtrasadaEAquelaComPrazoVencidoQueAindaEstaComAMarca() {
        long amarelo = parceria("Cliente Amarelo");
        entrega(amarelo, "AGUARDANDO_ENVIO", -1);   // atrasada: a marca não enviou
        entrega(amarelo, "NAO_INICIADO", -10);      // atrasada
        entrega(amarelo, "AJUSTE_SOLICITADO", -3);  // atrasada: a RBS pediu ajuste e a marca ainda não corrigiu
        entrega(amarelo, "EM_ANALISE", -5);         // não conta: já foi enviada, a bola está com a RBS
        entrega(amarelo, "ENVIADO", -5);            // não conta
        entrega(amarelo, "APROVADO", -5);           // não conta
        entrega(amarelo, "NAO_APLICAVEL", -5);      // não conta
        entrega(amarelo, "AGUARDANDO_ENVIO", 0);    // vence hoje: ainda não está atrasada
        entrega(amarelo, "AGUARDANDO_ENVIO", 7);    // prazo no futuro
        entrega(amarelo, "AGUARDANDO_ENVIO", null); // sem prazo

        MvcTestResult resposta = painel(entrar(RBS), "");

        assertThat(lerJson(resposta, "$.entregas.atrasadas")).isEqualTo(3);
        assertThat(lerJson(resposta, "$.entregas.porStatus.AGUARDANDO_ENVIO")).isEqualTo(4);
        assertThat(lerJson(resposta, "$.entregas.porStatus.NAO_INICIADO")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.entregas.porStatus.AJUSTE_SOLICITADO")).isEqualTo(2);
        assertThat(lerJson(resposta, "$.entregas.esperandoAnalise")).isEqualTo(4);
    }

    @Test
    void pendenciasSaoLimitadasA20ComOsFaltantesMaisImportantesPrimeiro() {
        long amarelo = parceria("Cliente Amarelo");
        for (int i = 0; i < 25; i++) {
            entrega(amarelo, "AGUARDANDO_ENVIO", null);
        }
        long faltantes = jdbc.sql("""
                        SELECT count(*) FROM vw_faltantes f JOIN parceria pa ON pa.id = f.parceria_id
                        JOIN evento e ON e.id = pa.evento_id WHERE e.edicao = 2027
                        """).query(Long.class).single();
        assertThat(faltantes).isGreaterThan(20);

        MvcTestResult resposta = painel(entrar(RBS), "");

        List<String> tipos = lerLista(resposta, "$.pendencias[*].tipo");
        assertThat(tipos).hasSize(20);
        // A ordem é a do contrato: nunca um tipo "menos importante" antes de um mais importante.
        List<String> ordem = List.of("MIDIA_SEM_EXIBICAO", "MIDIA_ABAIXO_DO_PREVISTO", "APLICACAO_SEM_COMPROVACAO",
                "PESQUISA_SEM_PERGUNTAS", "PERGUNTA_SEM_RESULTADO", "ENTREGA_PENDENTE", "PARCERIA_SEM_USUARIO", "FESTIVAL_SEM_NUMEROS");
        assertThat(tipos.stream().map(ordem::indexOf).toList()).isSorted();
        assertThat(lerJson(resposta, "$.pendencias[0].tipo")).isEqualTo("MIDIA_SEM_EXIBICAO");
        assertThat(lerJson(resposta, "$.pendencias[0].patrocinador")).isEqualTo("Cliente Roxo");
        assertThat(((Number) lerJson(resposta, "$.pendencias[0].parceriaId")).longValue()).isEqualTo(parceria("Cliente Roxo"));
        // O corte cai nos menos importantes: os números do festival (e boa parte das entregas novas) ficam de fora.
        assertThat(tipos).doesNotContain("FESTIVAL_SEM_NUMEROS");
    }

    @Test
    void painelContaParceriaSemUsuarioSemRelatorioEOTipoDaCarga() {
        long vazia = criarParceriaVazia("EM_NEGOCIACAO");
        long evento = evento(2027);
        long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        long carga = jdbc.sql("""
                        INSERT INTO carga (tipo, evento_id, executada_por, executada_em, linhas_lidas, linhas_inseridas, linhas_rejeitadas)
                        VALUES ('MIDIA', :e, :u, (SELECT max(executada_em) FROM carga) + interval '1 day', 12, 10, 2) RETURNING id
                        """).param("e", evento).param("u", rbs).query(Long.class).single();

        MvcTestResult resposta = painel(entrar(RBS), "");

        assertThat(lerJson(resposta, "$.parcerias.total")).isEqualTo(4);
        assertThat(lerJson(resposta, "$.parcerias.ativas")).as("em negociação não é ativa").isEqualTo(3);
        assertThat(lerJson(resposta, "$.parcerias.semUsuario")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.relatorios.semRelatorio")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.relatorios.rascunho")).isEqualTo(3);
        // A carga de veiculação é gravada como MIDIA e sai como VEICULACAO.
        assertThat(((Number) lerJson(resposta, "$.ultimaCarga.id")).longValue()).isEqualTo(carga);
        assertThat(lerJson(resposta, "$.ultimaCarga.tipo")).isEqualTo("VEICULACAO");
        assertThat(lerJson(resposta, "$.ultimaCarga.linhasInseridas")).isEqualTo(10);
        assertThat(lerJson(resposta, "$.ultimaCarga.linhasRejeitadas")).isEqualTo(2);
        List<Integer> semUsuario = lerLista(resposta, "$.pendencias[?(@.tipo == 'PARCERIA_SEM_USUARIO')].parceriaId");
        assertThat(semUsuario).containsExactly((int) vazia);
    }

    @Test
    void patrocinadorRecebe403NoPainel() {
        for (String email : List.of(AMARELO, AGENCIA_DO_AMARELO, ROXO)) {
            SessaoDeApi sessao = entrar(email);
            MvcTestResult resposta = painel(sessao, "");
            assertThat(resposta).as(email).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(lerJson(resposta, "$.title")).isEqualTo("Acesso negado");
            assertThat(corpo(resposta)).doesNotContain("pendencias").doesNotContain("Cliente");
            assertThat(painel(sessao, "?eventoId=" + evento(2026))).as(email + " com eventoId").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(painel(sessao, "?eventoId=999999")).as(email + " com eventoId que não existe").hasStatus(HttpStatus.FORBIDDEN);
        }
    }
}
