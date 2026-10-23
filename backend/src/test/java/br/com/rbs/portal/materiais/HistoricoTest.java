package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Datas;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o histórico: o da parceria (rota 20), o geral da RBS (21) e o geral em CSV (22). Atende os itens 4 e 5 da Entrega 2.
 * [HISTORICO] [ISOLAMENTO] [PERMISSAO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class HistoricoTest extends ApoioDeMateriais {

    private static final String AGENTE_DA_AMOSTRA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15";
    private static final String AGENTE_DO_LOGIN_DA_AMOSTRA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15";

    String historicoDe(long parceriaId) {
        return "/api/parcerias/" + parceriaId + "/historico";
    }

    /** Quantas linhas da parceria o patrocinador pode ver: tudo, menos login, falha de login e logout. */
    long linhasVisiveisDaParceria(long parceriaId) {
        return contar("SELECT count(*) FROM historico WHERE parceria_id = ? AND acao NOT IN ('LOGIN', 'LOGIN_FALHOU', 'LOGOUT')",
                parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rota 20: histórico da parceria
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorVeOHistoricoDaPropriaParceriaSemIpSemNavegadorESemLoginELogout() {
        long loginsNaParceria = contar("SELECT count(*) FROM historico WHERE parceria_id = ? AND acao = 'LOGIN'", amarelo());
        assertThat(loginsNaParceria).as("a amostra tem um LOGIN gravado com a parceria; ele não pode aparecer").isPositive();

        MvcTestResult resposta = entrar(AMARELO).get(historicoDe(amarelo()) + "?tamanho=200");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.total")).isEqualTo((int) linhasVisiveisDaParceria(amarelo()));
        List<String> acoes = lerLista(resposta, "$.itens[*].acao");
        assertThat(acoes).contains("ENVIO_ARQUIVO", "NOVA_VERSAO", "ALTERACAO_STATUS", "DOWNLOAD", "AUTORIZACAO")
                .doesNotContain("LOGIN", "LOGIN_FALHOU", "LOGOUT");
        List<Object> ips = lerLista(resposta, "$.itens[*].ip");
        List<Object> agentes = lerLista(resposta, "$.itens[*].userAgent");
        assertThat(ips).as("o campo existe em toda linha, mas vai nulo").hasSameSizeAs(acoes).containsOnlyNulls();
        assertThat(agentes).hasSameSizeAs(acoes).containsOnlyNulls();
        assertThat(corpo(resposta)).doesNotContain("200.0.2.10").doesNotContain("Mozilla");
    }

    @Test
    void rbsVeIpENavegadorNaMesmaListaESemLoginELogoutTambem() {
        MvcTestResult resposta = entrar(RBS).get(historicoDe(amarelo()) + "?tamanho=200&acao=ENVIO_ARQUIVO");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].ip")).containsExactly("200.0.2.10");
        assertThat(lerLista(resposta, "$.itens[*].userAgent")).containsExactly(AGENTE_DA_AMOSTRA);
        List<String> todas = lerLista(entrar(RBS).get(historicoDe(amarelo()) + "?tamanho=200"), "$.itens[*].acao");
        assertThat(todas).doesNotContain("LOGIN", "LOGIN_FALHOU", "LOGOUT");
    }

    @Test
    void cadaLinhaTemOFormatoDoContratoEAOrdemEDaMaisNovaParaAMaisAntiga() {
        MvcTestResult resposta = entrar(AMARELO).get(historicoDe(amarelo()) + "?acao=ALTERACAO_STATUS&entidade=entrega");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.pagina")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.tamanho")).isEqualTo(50);
        assertThat(lerJson(resposta, "$.total")).isEqualTo(2);
        assertThat(lerLista(resposta, "$.itens[*].ocorridoEm")).containsExactly("2026-10-18T10:15:00-03:00", "2026-10-16T09:42:00-03:00");
        Map<String, Object> ajuste = lerLista(resposta, "$.itens[?(@.valorNovo == 'AJUSTE_SOLICITADO')]").stream()
                .map(o -> (Map<String, Object>) o).findFirst().orElseThrow();
        assertThat(ajuste).containsEntry("acao", "ALTERACAO_STATUS").containsEntry("entidade", "entrega")
                .containsEntry("valorAnterior", "EM_ANALISE").containsEntry("detalhe", null)
                .containsKeys("id", "entidadeId", "usuario", "ocorridoEm", "ip", "userAgent");
        assertThat((Map<String, Object>) ajuste.get("usuario")).containsEntry("nome", "Equipe B2B RBS").containsEntry("perfil", "ADMIN_RBS");
    }

    @Test
    void filtrosPorAcaoMultiplaEntidadeEPeriodoNoHorarioDeBrasilia() {
        SessaoDeApi amarelo = entrar(AMARELO);
        // Uma ação às 23:30 de Brasília do dia 17 (02:30 UTC do dia 18) ainda é do dia 17.
        gravarHistorico(amarelo(), "2026-10-17 23:30-03", "EDICAO", "Noite do dia 17");
        gravarHistorico(amarelo(), "2026-10-18 00:10-03", "EDICAO", "Madrugada do dia 18");

        MvcTestResult duas = amarelo.get(historicoDe(amarelo()) + "?acao=NOVA_VERSAO,ENVIO_ARQUIVO");
        MvcTestResult arquivo = amarelo.get(historicoDe(amarelo()) + "?entidade=arquivo");
        MvcTestResult dia17 = amarelo.get(historicoDe(amarelo()) + "?de=2026-10-17&ate=2026-10-17");
        MvcTestResult aPartirDe18 = amarelo.get(historicoDe(amarelo()) + "?de=2026-10-18&acao=EDICAO");
        MvcTestResult ateOntem = amarelo.get(historicoDe(amarelo()) + "?ate=2026-10-15");

        assertThat(lerLista(duas, "$.itens[*].acao")).containsExactly("NOVA_VERSAO", "ENVIO_ARQUIVO");
        assertThat(lerLista(arquivo, "$.itens[*].acao")).containsOnly("DOWNLOAD");
        assertThat(lerLista(dia17, "$.itens[*].detalhe")).contains("Noite do dia 17").doesNotContain("Madrugada do dia 18");
        assertThat(lerLista(dia17, "$.itens[*].acao")).contains("NOVA_VERSAO");
        assertThat(lerLista(aPartirDe18, "$.itens[*].detalhe")).contains("Madrugada do dia 18").doesNotContain("Noite do dia 17");
        assertThat(lerLista(ateOntem, "$.itens[*].acao")).as("até o fim do dia 15").contains("ENVIO_ARQUIVO").doesNotContain("NOVA_VERSAO");
    }

    @Test
    void paginacaoSomaOTotalESemRepetirLinhas() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long total = linhasVisiveisDaParceria(amarelo());

        MvcTestResult primeira = amarelo.get(historicoDe(amarelo()) + "?tamanho=3&pagina=0");
        MvcTestResult segunda = amarelo.get(historicoDe(amarelo()) + "?tamanho=3&pagina=1");

        assertThat(lerJson(primeira, "$.total")).isEqualTo((int) total);
        assertThat(lerJson(segunda, "$.pagina")).isEqualTo(1);
        assertThat(lerJson(segunda, "$.tamanho")).isEqualTo(3);
        List<Integer> idsDaPrimeira = lerLista(primeira, "$.itens[*].id");
        List<Integer> idsDaSegunda = lerLista(segunda, "$.itens[*].id");
        assertThat(idsDaPrimeira).hasSize(3);
        assertThat(idsDaSegunda).hasSize(3).doesNotContainAnyElementsOf(idsDaPrimeira);
    }

    @Test
    void filtroInvalidoDevolve422ComOCampoCerto() {
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult acao = amarelo.get(historicoDe(amarelo()) + "?acao=NOVA_VERSAO,INVENTADA");
        MvcTestResult data = amarelo.get(historicoDe(amarelo()) + "?de=amanha");
        MvcTestResult invertido = amarelo.get(historicoDe(amarelo()) + "?de=2026-10-18&ate=2026-10-01");
        MvcTestResult tamanho = amarelo.get(historicoDe(amarelo()) + "?tamanho=201");
        MvcTestResult pagina = amarelo.get(historicoDe(amarelo()) + "?pagina=-1");

        assertThat(acao).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(acao, "$.campos.acao")).isNotNull();
        assertThat(data).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(data, "$.campos.de")).isNotNull();
        assertThat(invertido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invertido, "$.campos.ate")).isNotNull();
        assertThat(tamanho).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(tamanho, "$.campos.tamanho")).isNotNull();
        assertThat(pagina).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(pagina, "$.campos.pagina")).isNotNull();
    }

    @Test
    void roxoLendoOHistoricoDoAmareloRecebe404IgualAParceriaInexistente() {
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult doAmarelo = roxo.get(historicoDe(amarelo()));
        MvcTestResult inexistente = roxo.get(historicoDe(999999));

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("logo_final");
        assertThat(SessaoDeApi.anonima(mvc).get(historicoDe(amarelo()))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oQueOPatrocinadorFazApareceNoHistoricoDaParceriaDele() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT novo");
        enviar(entrar(AMARELO), entrega, "vt.pdf", pdf("vt"), null, "Segue o VT.");

        MvcTestResult resposta = entrar(AMARELO).get(historicoDe(amarelo()) + "?tamanho=200");

        assertThat(lerLista(resposta, "$.itens[?(@.entidadeId == " + entrega + ")].acao"))
                .containsExactlyInAnyOrder("CRIACAO", "ENVIO_ARQUIVO", "ALTERACAO_STATUS");
        assertThat(lerLista(resposta, "$.itens[?(@.acao == 'ENVIO_ARQUIVO' && @.entidadeId == " + entrega + ")].detalhe"))
                .containsExactly("Segue o VT.");
    }

    /** Grava uma linha de histórico direto no banco, com data e hora escolhidas (a tabela aceita INSERT). */
    void gravarHistorico(long parceriaId, String quando, String acao, String detalhe) {
        jdbc.sql("""
                        INSERT INTO historico (parceria_id, entidade, entidade_id, acao, detalhe, usuario_id, ocorrido_em)
                        VALUES (:p, 'parceria', :p, :acao, :detalhe, :u, CAST(:quando AS timestamptz))
                        """)
                .param("p", parceriaId).param("acao", acao).param("detalhe", detalhe).param("u", usuarioId(RBS))
                .param("quando", quando).update();
    }

    // ---------------------------------------------------------------------
    // Rota 21: histórico geral (só a RBS)
    // ---------------------------------------------------------------------

    @Test
    void historicoGeralEhSoDaRbs() {
        SessaoDeApi amarelo = entrar(AMARELO);

        assertThat(amarelo.get("/api/historico")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.get("/api/historico?parceriaId=" + amarelo())).as("nem o da própria parceria").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.get("/api/historico.csv")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/historico")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/historico.csv")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void historicoGeralTrazOsAcessosEAParceriaDeCadaLinha() {
        MvcTestResult resposta = entrar(RBS).get("/api/historico?acao=LOGIN_FALHOU");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.total")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.itens[0].parceria")).as("falha de login não tem parceria").isNull();
        assertThat(lerJson(resposta, "$.itens[0].usuario")).as("e-mail que não existe: sem usuário").isNull();
        assertThat(lerJson(resposta, "$.itens[0].entidade")).isEqualTo("usuario");
        assertThat(lerJson(resposta, "$.itens[0].entidadeId")).isNull();
        assertThat(lerJson(resposta, "$.itens[0].detalhe")).isEqualTo("e-mail tentado: amarelo.marketing@amarelo.example");
        assertThat(lerJson(resposta, "$.itens[0].ip")).isEqualTo("200.0.2.10");
        assertThat(lerJson(resposta, "$.itens[0].userAgent")).isEqualTo(AGENTE_DO_LOGIN_DA_AMOSTRA);

        MvcTestResult doAmarelo = entrar(RBS).get("/api/historico?acao=ENVIO_ARQUIVO");
        assertThat(lerJson(doAmarelo, "$.itens[0].parceria.id")).isEqualTo((int) amarelo());
        assertThat(lerJson(doAmarelo, "$.itens[0].parceria.patrocinador")).isEqualTo("Cliente Amarelo");
    }

    @Test
    void loginELogoutFeitosPelaApiApareceNoHistoricoGeralComIpENavegador() {
        SessaoDeApi amarelo = entrar(AMARELO);
        amarelo.post("/api/auth/logout", null);

        MvcTestResult resposta = entrar(RBS).get("/api/historico?usuarioId=" + usuarioId(AMARELO) + "&acao=LOGIN,LOGOUT&tamanho=200");

        assertThat(lerLista(resposta, "$.itens[*].acao")).contains("LOGIN", "LOGOUT");
        assertThat(lerLista(resposta, "$.itens[?(@.acao == 'LOGOUT')].parceria")).containsOnlyNulls();
        assertThat(lerLista(resposta, "$.itens[?(@.acao == 'LOGOUT')].ip")).containsOnly("127.0.0.1");
    }

    @Test
    void historicoGeralFiltraPorParceriaUsuarioEntidadeETexto() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult daParceria = rbs.get("/api/historico?parceriaId=" + roxo() + "&tamanho=200");
        MvcTestResult doUsuario = rbs.get("/api/historico?usuarioId=" + usuarioId(AMARELO) + "&acao=ENVIO_ARQUIVO,NOVA_VERSAO");
        MvcTestResult daEntidade = rbs.get("/api/historico?entidade=arquivo&tamanho=200");
        MvcTestResult porTexto = rbs.get("/api/historico?q=LOGO_FINAL.PDF (versão 2)");
        MvcTestResult noDetalhe = rbs.get("/api/historico?q=amarelo.marketing");
        MvcTestResult curinga = rbs.get("/api/historico?q=%");

        assertThat(lerLista(daParceria, "$.itens[*].parceria.patrocinador")).isNotEmpty().containsOnly("Cliente Roxo");
        assertThat(lerLista(doUsuario, "$.itens[*].acao")).containsExactly("NOVA_VERSAO", "ENVIO_ARQUIVO");
        assertThat(lerLista(doUsuario, "$.itens[*].usuario.nome")).containsOnly("Marketing Amarelo");
        assertThat(lerLista(daEntidade, "$.itens[*].acao")).containsOnly("DOWNLOAD");
        assertThat(lerLista(porTexto, "$.itens[*].acao")).as("a nova versão e o download dela").containsExactlyInAnyOrder("NOVA_VERSAO", "DOWNLOAD");
        assertThat(lerLista(porTexto, "$.itens[*].valorNovo")).containsOnly("logo_final.pdf (versão 2)");
        assertThat(lerLista(noDetalhe, "$.itens[*].acao")).containsExactly("LOGIN_FALHOU");
        assertThat(lerJson(curinga, "$.total")).as("% é texto, não curinga").isEqualTo(0);
    }

    @Test
    void historicoGeralPaginaEOrdenaDaMaisNovaParaAMaisAntiga() {
        SessaoDeApi rbs = entrar(RBS);
        long total = contar("SELECT count(*) FROM historico");

        MvcTestResult primeira = rbs.get("/api/historico?tamanho=5");
        MvcTestResult todas = rbs.get("/api/historico?tamanho=200");

        assertThat(lerJson(primeira, "$.total")).isEqualTo((int) total);
        assertThat(lerLista(primeira, "$.itens")).hasSize(5);
        List<String> datas = lerLista(todas, "$.itens[*].ocorridoEm");
        assertThat(datas).isSortedAccordingTo((a, b) -> java.time.OffsetDateTime.parse(b).compareTo(java.time.OffsetDateTime.parse(a)));
    }

    @Test
    void historicoGeralRecusaFiltroInvalidoCom422() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult acao = rbs.get("/api/historico?acao=INVENTADA");
        MvcTestResult data = rbs.get("/api/historico?ate=31/10/2026");

        assertThat(acao).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(acao, "$.campos.acao")).isNotNull();
        assertThat(data).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(data, "$.campos.ate")).isNotNull();
    }

    // ---------------------------------------------------------------------
    // Rota 22: CSV
    // ---------------------------------------------------------------------

    static String texto(MvcTestResult resposta) {
        return new String(resposta.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void csvAbreComBomPontoEVirgulaCabecalhoCertoENomeComAData() {
        MvcTestResult resposta = entrar(RBS).get("/api/historico.csv");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(resposta.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"historico-" + Datas.hoje() + ".csv\"");
        assertThat(resposta.getResponse().getContentAsByteArray()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String[] linhas = texto(resposta).substring(1).split("\r\n");
        assertThat(linhas[0]).isEqualTo("id;ocorrido_em;acao;entidade;entidade_id;parceria;usuario;perfil;valor_anterior;valor_novo;detalhe;ip;user_agent");
        assertThat(linhas.length - 1).as("uma linha por registro do histórico").isEqualTo((int) contar("SELECT count(*) FROM historico"));
    }

    @Test
    void csvTemAsMesmasLinhasDaRota21ComOMesmoFiltroEQuoteDoQueTemPontoEVirgula() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult csv = rbs.get("/api/historico.csv?acao=ENVIO_ARQUIVO&parceriaId=" + amarelo());
        MvcTestResult json = rbs.get("/api/historico?acao=ENVIO_ARQUIVO&parceriaId=" + amarelo());

        String[] linhas = texto(csv).substring(1).split("\r\n");
        assertThat(linhas).hasSize(2);
        long entrega = entregaDaAmostra(amarelo(), "Logo final para aplicações no evento");
        // O navegador do exemplo tem ";" no texto: a célula vem entre aspas, como manda a RFC 4180.
        assertThat(linhas[1]).isEqualTo(lerJson(json, "$.itens[0].id") + ";2026-10-15T14:20:00-03:00;ENVIO_ARQUIVO;entrega;" + entrega
                + ";Cliente Amarelo;Marketing Amarelo;ADMIN_PATROCINADOR;;logo_final.pdf (versão 1);;200.0.2.10;\"" + AGENTE_DA_AMOSTRA.replace("\"", "\"\"") + "\"");
    }

    @Test
    void csvNeutralizaCelulaQueComecaComSinalDeFormula() {
        gravarHistorico(amarelo(), "2026-10-20 10:00-03", "EDICAO", "=HYPERLINK(\"http://exemplo.test\")");
        gravarHistorico(amarelo(), "2026-10-20 10:01-03", "EDICAO", "@SOMA(A1)");
        gravarHistorico(amarelo(), "2026-10-20 10:02-03", "EDICAO", "-2+3");
        gravarHistorico(amarelo(), "2026-10-20 10:03-03", "EDICAO", "+55 51 99999-0000");

        String csv = texto(entrar(RBS).get("/api/historico.csv?acao=EDICAO&de=2026-10-20&ate=2026-10-20"));

        assertThat(csv).contains(";\"'=HYPERLINK(\"\"http://exemplo.test\"\")\";").contains(";'@SOMA(A1);").contains(";'-2+3;")
                .contains(";'+55 51 99999-0000;");
        assertThat(csv).doesNotContain(";=HYPERLINK").doesNotContain(";@SOMA").doesNotContain(";-2+3;").doesNotContain(";+55");
    }

    @Test
    void csvComMaisDe50MilLinhasDevolve422PedindoParaFiltrarPorPeriodo() {
        jdbc.sql("""
                        INSERT INTO historico (parceria_id, entidade, entidade_id, acao, usuario_id)
                        SELECT NULL, 'teste', g, 'EDICAO', :u FROM generate_series(1, 50001) AS g
                        """).param("u", usuarioId(RBS)).update();
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult grande = rbs.get("/api/historico.csv");
        MvcTestResult filtrado = rbs.get("/api/historico.csv?de=2026-10-15&ate=2026-10-15");

        assertThat(grande).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(grande, "$.detail")).contains("50000").contains("de e ate");
        assertThat(lerJson(grande, "$.campos.de")).isNotNull();
        assertThat(filtrado).as("com período curto cabe").hasStatus(HttpStatus.OK);
        assertThat(texto(filtrado).substring(1).split("\r\n").length).isGreaterThan(1).isLessThan(50);
    }

    @Test
    void csvComFiltroInvalidoDevolve422() {
        assertThat(entrar(RBS).get("/api/historico.csv?acao=INVENTADA")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
