package br.com.rbs.portal.cortesias;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.AMARELO;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.RBS;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.ROXO;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.VERDE;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.amarelo;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.celula;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.cotas;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.destinacao;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.destinadasNoBanco;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.eventoDe;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.linha;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.totalDeDestinacoes;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uri;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uriCotas;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uriDestinacoes;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.verde;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.time.LocalDate;
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
 * [R5.4.c] A marca destina as cortesias (rota 83), edita (84) e retira (85) qualquer destinação da parceria, e a RBS faz o mesmo no
 * lugar dela. Acima do disponível dá 422 e nada grava; depois do prazo da edição a marca recebe 422 com a data e a RBS ainda ajusta;
 * parceria encerrada recusa a escrita; aba fechada ou oculta é 403; parceria ou destinação de outra marca é 404. O nome do
 * destinatário não vai ao histórico. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class DestinacaoDeCortesiasTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void marcaDestinaDentroDoDisponivelEATabelaMostraAsDuasPontas() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        // Arena de sábado: 4 de cota, 2 destinadas na amostra, 2 disponíveis
        MvcTestResult criada = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 2, "Maria Souza"));

        assertThat(criada).hasStatus(HttpStatus.CREATED);
        Map<String, Object> arena = linha(criada, "ARENA", "SABADO");
        assertThat(arena).containsEntry("cota", 4).containsEntry("destinadas", 4).containsEntry("disponiveis", 0);
        assertThat(lerJson(criada, "$.cotas[?(@.setor=='ARENA' && @.dia=='SABADO')].destinacoes[?(@.destinatario=='Maria Souza')].quantidade"))
                .isEqualTo(List.of(2));
        assertThat((Number) lerJson(criada, "$.totais.destinadas")).isEqualTo(8);
        assertThat((Number) lerJson(criada, "$.totais.disponiveis")).isEqualTo(4);
        // a RBS lê o mesmo
        MvcTestResult daRbs = SessaoDeApi.entrar(mvc, RBS).get(uri(parceria));
        assertThat(linha(daRbs, "ARENA", "SABADO")).containsEntry("destinadas", 4);
    }

    @Test
    void acimaDoDisponivelDa422EMantemOPreenchimento() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        // a Arena de sexta tem 1 disponível na amostra: a marca a esgota, e a próxima já nasce sem vaga
        assertThat(marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SEXTA", 1, "Primeira Pessoa"))).hasStatus(HttpStatus.CREATED);
        long destinacoesAntes = totalDeDestinacoes(jdbc, parceria);
        long historicoAntes = jdbc.sql("SELECT count(*) FROM historico WHERE parceria_id = :p").param("p", parceria).query(Long.class).single();

        // 2 disponíveis na Arena de sábado: pedir 3 passa do limite; pedir 1 numa cota esgotada (Arena de sexta) também
        MvcTestResult dois = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 3, "Maria Souza"));
        MvcTestResult esgotada = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SEXTA", 1, "Maria Souza"));
        MvcTestResult enorme = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 100_000, "Maria Souza"));

        assertThat(dois).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(dois, "$.campos.quantidade")).isEqualTo("Só restam 2 cortesias de Arena · sábado para destinar.");
        assertThat(esgotada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(esgotada, "$.campos.quantidade")).isEqualTo("Não restam cortesias de Arena · sexta-feira para destinar.");
        assertThat(enorme).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(corpo(dois)).as("a resposta não repete o nome").doesNotContain("Maria");
        // o back não grava nada: o front mantém o preenchimento e a tabela segue como estava
        assertThat(totalDeDestinacoes(jdbc, parceria)).isEqualTo(destinacoesAntes);
        assertThat(destinadasNoBanco(jdbc, parceria, "ARENA", "SABADO")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM historico WHERE parceria_id = :p").param("p", parceria).query(Long.class).single())
                .isEqualTo(historicoAntes);
        assertThat(linha(marca.get(uri(parceria)), "ARENA", "SABADO")).containsEntry("disponiveis", 2);
    }

    @Test
    void cotaQueAParceriaNaoTemDa422NoCampoSetor() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        MvcTestResult resposta = marca.post(uriDestinacoes(parceria), destinacao("LOUNGE", "SEXTA", 1, "Maria Souza"));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resposta, "$.campos.setor")).contains("não tem cota de cortesia de Lounge");
    }

    @Test
    void corpoInvalidoDa422ComOCampoCerto() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        long antes = totalDeDestinacoes(jdbc, parceria);

        Map<String, String> casos = new java.util.LinkedHashMap<>();
        casos.put(destinacao("ARENA", "SABADO", 0, "Maria"), "quantidade");
        casos.put(destinacao("ARENA", "SABADO", -3, "Maria"), "quantidade");
        casos.put(destinacao("ARENA", "SABADO", 32768, "Maria"), "quantidade");
        casos.put(destinacao("ARENA", "SABADO", 9_999_999_999L, "Maria"), "quantidade");
        casos.put(destinacao("ARENA", "SABADO", 1, null), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "   "), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "x".repeat(121)), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "Ma\\u0000ria"), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "Ma\\nria"), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "Ma\\u202Eria"), "destinatario");
        casos.put(destinacao("ARENA", "SABADO", 1, "Ma\\ud800ria"), "destinatario");
        casos.put(destinacao("PISTA", "SABADO", 1, "Maria"), "setor");
        casos.put(destinacao("ARENA", "DOMINGO", 1, "Maria"), "dia");
        casos.put("{}", "setor");
        for (var caso : casos.entrySet()) {
            MvcTestResult resposta = marca.post(uriDestinacoes(parceria), caso.getKey());
            assertThat(resposta).as(caso.getKey()).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(corpo(resposta)).as(caso.getKey()).contains("\"" + caso.getValue() + "\"");
        }
        assertThat(totalDeDestinacoes(jdbc, parceria)).isEqualTo(antes);
    }

    @Test
    void nomeComEspacosDeMaisFicaNormalizadoEUmNomeDe120CaracteresPassa() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        rbs.patch(uriCotas(parceria), cotas(celula("PREMIUM", "SEXTA", 10, false, null)));

        MvcTestResult espacos = rbs.post(uriDestinacoes(parceria), destinacao("PREMIUM", "SEXTA", 1, "  Maria   da  Silva "));
        MvcTestResult longo = rbs.post(uriDestinacoes(parceria), destinacao("PREMIUM", "SEXTA", 1, "x".repeat(120)));

        assertThat(espacos).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(espacos, "$.cotas[?(@.setor=='PREMIUM')].destinacoes[0].destinatario")).isEqualTo(List.of("Maria da Silva"));
        assertThat(longo).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void marcaERbsEditamERetiramQualquerDestinacaoDaParceria() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        rbs.patch(uriCotas(parceria), cotas(celula("PREMIUM", "SEXTA", 10, false, null)));
        // uma criada pela RBS e uma pela marca
        MvcTestResult daRbs = rbs.post(uriDestinacoes(parceria), destinacao("PREMIUM", "SEXTA", 2, "Ana da RBS"));
        MvcTestResult daMarca = marca.post(uriDestinacoes(parceria), destinacao("PREMIUM", "SEXTA", 3, "Beto da marca"));
        long idRbs = ((Number) ((List<?>) lerJson(daRbs, "$.cotas[?(@.setor=='PREMIUM')].destinacoes[0].id")).get(0)).longValue();
        long idMarca = ((Number) ((List<?>) lerJson(daMarca, "$.cotas[?(@.setor=='PREMIUM')].destinacoes[?(@.destinatario=='Beto da marca')].id"))
                .get(0)).longValue();

        // a marca edita a que a RBS criou: nome e quantidade
        MvcTestResult editada = marca.patch(uriDestinacoes(parceria) + "/" + idRbs, "{\"destinatario\":\"Ana Souza\",\"quantidade\":4}");
        assertThat(editada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(editada, "$.cotas[?(@.setor=='PREMIUM')].destinacoes[?(@.id==" + idRbs + ")].destinatario"))
                .isEqualTo(List.of("Ana Souza"));
        assertThat(linha(editada, "PREMIUM", "SEXTA")).containsEntry("destinadas", 7).containsEntry("disponiveis", 3);
        // a RBS edita a da marca: só a quantidade (o nome fica)
        MvcTestResult soQuantidade = rbs.patch(uriDestinacoes(parceria) + "/" + idMarca, "{\"quantidade\":5}");
        assertThat(soQuantidade).hasStatus(HttpStatus.OK);
        assertThat(lerJson(soQuantidade, "$.cotas[?(@.setor=='PREMIUM')].destinacoes[?(@.id==" + idMarca + ")].destinatario"))
                .isEqualTo(List.of("Beto da marca"));
        // passar do disponível ao aumentar: cota 10, 4 + 5 usados, sobra 1; a de 5 não vai a 7
        MvcTestResult passou = rbs.patch(uriDestinacoes(parceria) + "/" + idMarca, "{\"quantidade\":7}");
        assertThat(passou).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(passou, "$.campos.quantidade")).contains("Só restam 6");
        assertThat(rbs.patch(uriDestinacoes(parceria) + "/" + idMarca, "{\"quantidade\":6}")).hasStatus(HttpStatus.OK);
        // corpo sem nada para mudar
        assertThat(rbs.patch(uriDestinacoes(parceria) + "/" + idMarca, "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch(uriDestinacoes(parceria) + "/" + idMarca, "{\"destinatario\":\"\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        // a marca retira a que a RBS criou; a RBS retira a da marca. Apagar é apagar de verdade
        assertThat(marca.delete(uriDestinacoes(parceria) + "/" + idRbs)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rbs.delete(uriDestinacoes(parceria) + "/" + idMarca)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM cortesia_destinacao WHERE id IN (:a, :b)").param("a", idRbs).param("b", idMarca)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM cortesia_destinacao WHERE destinatario_nome IN ('Ana Souza', 'Ana da RBS', 'Beto da marca')
                """).query(Long.class).single()).as("o nome saiu do banco").isZero();
        assertThat(marca.delete(uriDestinacoes(parceria) + "/" + idRbs)).as("retirar de novo").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(linha(marca.get(uri(parceria)), "PREMIUM", "SEXTA")).containsEntry("destinadas", 0);
    }

    @Test
    void destinacaoDeOutraParceriaEOutraMarcaDao404() {
        long verde = verde(jdbc);
        long amarelo = amarelo(jdbc);
        long deOutra = jdbc.sql("""
                SELECT d.id FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                WHERE cc.parceria_id = :p ORDER BY d.id LIMIT 1
                """).param("p", amarelo).query(Long.class).single();
        long deVerde = jdbc.sql("""
                SELECT d.id FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                WHERE cc.parceria_id = :p ORDER BY d.id LIMIT 1
                """).param("p", verde).query(Long.class).single();
        long antes = totalDeDestinacoes(jdbc, amarelo);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        // o id é do Amarelo, mas a URL é da Verde: 404 para a marca e para a RBS (nunca 403)
        for (SessaoDeApi sessao : List.of(marca, rbs)) {
            assertThat(sessao.patch(uriDestinacoes(verde) + "/" + deOutra, "{\"quantidade\":1}")).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(sessao.delete(uriDestinacoes(verde) + "/" + deOutra)).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(sessao.patch(uriDestinacoes(verde) + "/999999", "{\"quantidade\":1}")).hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(totalDeDestinacoes(jdbc, amarelo)).isEqualTo(antes);
        // a marca do Amarelo na parceria da Verde: 404 em todo verbo, com o id certo ou errado
        SessaoDeApi outra = SessaoDeApi.entrar(mvc, AMARELO);
        assertThat(outra.get(uri(verde))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(outra.post(uriDestinacoes(verde), destinacao("ARENA", "SABADO", 1, "Maria"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(outra.patch(uriDestinacoes(verde) + "/" + deVerde, "{\"quantidade\":1}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(outra.delete(uriDestinacoes(verde) + "/" + deVerde)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.entrar(mvc, ROXO).get(uri(verde))).hasStatus(HttpStatus.NOT_FOUND);
        // parceria que não existe
        assertThat(rbs.get(uri(999_999))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.post(uriDestinacoes(999_999), destinacao("ARENA", "SABADO", 1, "Maria"))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void abaFechadaOuOcultaDa403ParaAMarcaEARbsContinuaPassando() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String coluna : List.of("liberada", "visivel")) {
            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'CORTESIAS')")
                    .param("p", parceria).update();
            for (MvcTestResult resposta : List.of(marca.get(uri(parceria)),
                    marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Maria")),
                    marca.patch(uriDestinacoes(parceria) + "/1", "{\"quantidade\":1}"),
                    marca.delete(uriDestinacoes(parceria) + "/1"))) {
                assertThat(resposta).as("aba com " + coluna + " falso").hasStatus(HttpStatus.FORBIDDEN);
                assertThat((String) lerJson(resposta, "$.detail")).isEqualTo(VerificadorDeAcesso.ABA_NAO_LIBERADA);
            }
            assertThat(rbs.get(uri(parceria))).hasStatus(HttpStatus.OK);
            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'CORTESIAS')")
                    .param("p", parceria).update();
        }
        assertThat(marca.get(uri(parceria))).hasStatus(HttpStatus.OK);
    }

    @Test
    void depoisDoPrazoDaEdicaoAMarcaRecebe422ComADataEARbsAindaAjusta() {
        long parceria = verde(jdbc);
        long evento = eventoDe(jdbc, parceria);
        LocalDate ontem = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusDays(1);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        long idDaVerde = jdbc.sql("""
                SELECT d.id FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                WHERE cc.parceria_id = :p AND cc.setor = 'ARENA' AND cc.dia = 'SABADO'
                """).param("p", parceria).query(Long.class).single();
        assertThat(rbs.patch("/api/eventos/" + evento + "/cortesias", "{\"cortesiasPrazo\":\"" + ontem + "\"}")).hasStatus(HttpStatus.OK);
        String data = String.format("%02d/%02d/%d", ontem.getDayOfMonth(), ontem.getMonthValue(), ontem.getYear());

        MvcTestResult recusada = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Maria"));
        assertThat(recusada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(recusada, "$.detail")).contains(data);
        assertThat(marca.patch(uriDestinacoes(parceria) + "/" + idDaVerde, "{\"quantidade\":1}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(marca.delete(uriDestinacoes(parceria) + "/" + idDaVerde)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(destinadasNoBanco(jdbc, parceria, "ARENA", "SABADO")).isEqualTo(2);
        // a marca ainda lê, e a tela sabe que o prazo passou
        MvcTestResult leitura = marca.get(uri(parceria));
        assertThat(leitura).hasStatus(HttpStatus.OK);
        assertThat(lerJson(leitura, "$.prazoEncerrado")).isEqualTo(true);
        // a RBS ajusta: destina, edita e retira
        assertThat(rbs.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Maria"))).hasStatus(HttpStatus.CREATED);
        assertThat(rbs.patch(uriDestinacoes(parceria) + "/" + idDaVerde, "{\"quantidade\":1}")).hasStatus(HttpStatus.OK);
        assertThat(rbs.delete(uriDestinacoes(parceria) + "/" + idDaVerde)).hasStatus(HttpStatus.NO_CONTENT);
        // o prazo de hoje ainda vale; sem prazo, não há trava
        assertThat(rbs.patch("/api/eventos/" + evento + "/cortesias", "{\"cortesiasPrazo\":\"" + LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")) + "\"}"))
                .hasStatus(HttpStatus.OK);
        assertThat(marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Joana"))).hasStatus(HttpStatus.CREATED);
        assertThat(rbs.patch("/api/eventos/" + evento + "/cortesias", "{\"cortesiasPrazo\":null}")).hasStatus(HttpStatus.OK);
        assertThat(marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Joana"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void parceriaEncerradaRecusaAEscritaDaMarcaEDaRbsMasAindaLe() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        long idDaVerde = jdbc.sql("""
                SELECT d.id FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                WHERE cc.parceria_id = :p AND cc.setor = 'ARENA' AND cc.dia = 'SABADO'
                """).param("p", parceria).query(Long.class).single();
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :p").param("p", parceria).update();

        for (SessaoDeApi sessao : List.of(marca, rbs)) {
            assertThat(sessao.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, "Maria"))).hasStatus(HttpStatus.CONFLICT);
            assertThat(sessao.patch(uriDestinacoes(parceria) + "/" + idDaVerde, "{\"quantidade\":1}")).hasStatus(HttpStatus.CONFLICT);
            assertThat(sessao.delete(uriDestinacoes(parceria) + "/" + idDaVerde)).hasStatus(HttpStatus.CONFLICT);
            assertThat(sessao.get(uri(parceria))).hasStatus(HttpStatus.OK);
        }
        MvcTestResult cotas = rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 9, false, null)));
        assertThat(cotas).hasStatus(HttpStatus.CONFLICT);
        assertThat((String) lerJson(cotas, "$.detail")).isEqualTo(CortesiaService.ENCERRADA);
        assertThat(destinadasNoBanco(jdbc, parceria, "ARENA", "SABADO")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT quantidade FROM cortesia_cota WHERE parceria_id = :p AND setor = 'ARENA' AND dia = 'SABADO'")
                .param("p", parceria).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void historicoGuardaAQuantidadeEOSetorMasNuncaONome() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        String nome = "Zuleica Nomeunico Teste";
        MvcTestResult criada = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", 1, nome));
        long id = ((Number) ((List<?>) lerJson(criada, "$.cotas[?(@.setor=='ARENA' && @.dia=='SABADO')].destinacoes[?(@.destinatario=='" + nome + "')].id"))
                .get(0)).longValue();
        assertThat(marca.patch(uriDestinacoes(parceria) + "/" + id, "{\"destinatario\":\"Outro Nome Unico\"}")).hasStatus(HttpStatus.OK);
        assertThat(marca.delete(uriDestinacoes(parceria) + "/" + id)).hasStatus(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT acao, valor_anterior, valor_novo, detalhe FROM historico
                WHERE parceria_id = :p AND entidade = 'cortesia_destinacao' AND entidade_id = :id ORDER BY id
                """).param("p", parceria).param("id", id).query().listOfRows();

        assertThat(linhas).extracting(l -> l.get("acao")).containsExactly("CRIACAO", "EDICAO", "EXCLUSAO");
        assertThat(linhas.get(0)).containsEntry("valor_novo", "1 cortesia").containsEntry("detalhe", "Arena · sábado");
        assertThat(linhas.get(1)).containsEntry("detalhe", "Arena · sábado (destinatário alterado)");
        assertThat(linhas.toString()).doesNotContain("Zuleica", "Nomeunico", "Outro Nome");
        // (nenhuma linha do histórico da parceria cita o nome, nem na busca livre que a RBS usa)
        assertThat(SessaoDeApi.entrar(mvc, RBS).get("/api/historico?q=Zuleica")).hasStatus(HttpStatus.OK);
        assertThat(corpo(SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias/" + parceria + "/historico"))).doesNotContain("Zuleica");
    }
}
