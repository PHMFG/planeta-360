package br.com.rbs.portal.visita;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa a rota 120 (a visita de cada pessoa a cada aba e o selo "novo") como o front a usa: login pela rota, sessão de
 * verdade. [PERMISSAO] A marca precisa ser da parceria e a aba precisa estar aberta; a RBS passa em qualquer aba. As
 * novidades vêm dos carimbos que o banco já tem: a carga da veiculação e do resultado, o arquivo do comprovante, o
 * histórico da mudança de status da entrega, o perfil e a disponibilização do relatório. Cada teste é desfeito no fim
 * (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class VisitaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    long parceria(String patrocinador) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :nome AND e.edicao = 2027
                        """).param("nome", patrocinador).query(Long.class).single();
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    long usuario(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", email).query(Long.class).single();
    }

    long novaCarga(String tipo, long eventoId) {
        return jdbc.sql("""
                        INSERT INTO carga (tipo, evento_id, executada_por, executada_em)
                        VALUES (:tipo, :evento, :usuario, now() - interval '1 hour') RETURNING id
                        """)
                .param("tipo", tipo).param("evento", eventoId).param("usuario", usuario(RBS))
                .query(Long.class).single();
    }

    void visitar(long usuarioId, long parceriaId, String aba, String quando) {
        jdbc.sql("""
                        INSERT INTO visita_aba (usuario_id, parceria_id, aba, visto_em)
                        VALUES (:usuario, :parceria, :aba, now() + CAST(:quando AS interval))
                        ON CONFLICT (usuario_id, parceria_id, aba) DO UPDATE SET visto_em = now() + CAST(:quando AS interval)
                        """)
                .param("usuario", usuarioId).param("parceria", parceriaId).param("aba", aba).param("quando", quando)
                .update();
    }

    MvcTestResult novidades(SessaoDeApi sessao, long parceriaId) {
        return sessao.get("/api/parcerias/" + parceriaId + "/novidades");
    }

    MvcTestResult visitarPelaApi(SessaoDeApi sessao, long parceriaId, String aba) {
        return sessao.post("/api/parcerias/" + parceriaId + "/visitas/" + aba, null);
    }

    void semVisitas(long usuarioId, long parceriaId) {
        jdbc.sql("DELETE FROM visita_aba WHERE usuario_id = :u AND parceria_id = :p")
                .param("u", usuarioId).param("p", parceriaId).update();
    }

    @Test
    void oPostGravaAUltimaVisitaEOSegundoPostAtualiza() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);

        assertThat(visitarPelaApi(SessaoDeApi.entrar(mvc, AMARELO), amarelo, "MIDIA")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM visita_aba WHERE usuario_id = :u AND parceria_id = :p AND aba = 'MIDIA'")
                .param("u", marketing).param("p", amarelo).query(Long.class).single()).isEqualTo(1);

        jdbc.sql("UPDATE visita_aba SET visto_em = now() - interval '2 hours' WHERE usuario_id = :u AND parceria_id = :p")
                .param("u", marketing).param("p", amarelo).update();
        assertThat(visitarPelaApi(SessaoDeApi.entrar(mvc, AMARELO), amarelo, "MIDIA")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM visita_aba
                        WHERE usuario_id = :u AND parceria_id = :p AND aba = 'MIDIA' AND visto_em > now() - interval '1 minute'
                        """).param("u", marketing).param("p", amarelo).query(Long.class).single())
                .as("a segunda visita atualizou a linha, sem criar outra").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM visita_aba WHERE usuario_id = :u AND parceria_id = :p")
                .param("u", marketing).param("p", amarelo).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void midiaPesquisaERelatorioFicamNovosAteAPessoaVisitarAAba() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);
        long rbs = usuario(RBS);
        semVisitas(marketing, amarelo);
        long cargaMidia = novaCarga("MIDIA", evento(2027));
        long aproveitamento = jdbc.sql("""
                        SELECT ap.id FROM aproveitamento_previsto ap JOIN parceria pa ON pa.id = ap.parceria_id
                        WHERE pa.id = :p AND ap.categoria = 'MIDIA' ORDER BY ap.id LIMIT 1
                        """).param("p", amarelo).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, carga_id)
                        VALUES (:ap, timestamptz '2031-01-10 10:00:00-03', 1, :carga)
                        """).param("ap", aproveitamento).param("carga", cargaMidia).update();

        long pesquisa = jdbc.sql("""
                        SELECT p.id FROM pesquisa p JOIN parceria pa ON pa.evento_id = p.evento_id
                        WHERE pa.id = :p AND p.escopo = 'ATIVACAO'
                        """).param("p", amarelo).query(Long.class).single();
        long pergunta = jdbc.sql("SELECT id FROM pesquisa_pergunta WHERE parceria_id = :p AND ordem = 1")
                .param("p", amarelo).query(Long.class).single();
        long cargaPesquisa = novaCarga("PESQUISA", evento(2027));
        jdbc.sql("""
                        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor,
                                                       unidade, base_respondentes, carga_id)
                        VALUES (:pesquisa, :parceria, :pergunta, 'Pergunta 1', 'Novo', 10, 'PERCENTUAL', 412, :carga)
                        """).param("pesquisa", pesquisa).param("parceria", amarelo).param("pergunta", pergunta)
                .param("carga", cargaPesquisa).update();

        long relatorio = jdbc.sql("SELECT max(id) FROM relatorio WHERE parceria_id = :p")
                .param("p", amarelo).query(Long.class).single();
        jdbc.sql("UPDATE relatorio SET status = 'VALIDADO', validado_por = :u, validado_em = now() - interval '1 hour' "
                + "WHERE id = :r").param("u", rbs).param("r", relatorio).update();
        jdbc.sql("UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = :u, "
                + "disponibilizado_em = now() - interval '1 hour' WHERE id = :r").param("u", rbs).param("r", relatorio).update();

        visitar(marketing, amarelo, "MIDIA", "-2 hours");
        visitar(marketing, amarelo, "PESQUISA", "-2 hours");
        visitar(marketing, amarelo, "RELATORIO", "-2 hours");
        visitar(marketing, amarelo, "CONTRATO", "1 hour");   // visita no futuro: sem novidade

        MvcTestResult antes = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);
        assertThat(antes).hasStatus(HttpStatus.OK);
        assertThat(lerJson(antes, "$.abas.MIDIA.novo")).isEqualTo(true);
        assertThat(lerJson(antes, "$.abas.PESQUISA.novo")).isEqualTo(true);
        assertThat(lerJson(antes, "$.abas.RELATORIO.novo")).isEqualTo(true);
        assertThat(lerJson(antes, "$.abas.CONTRATO.novo")).isEqualTo(false);
        assertThat((String) lerJson(antes, "$.abas.MIDIA.desde")).endsWith("-03:00");

        // A pessoa visita cada aba: o selo apaga só aquela.
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        assertThat(visitarPelaApi(marca, amarelo, "MIDIA")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(visitarPelaApi(marca, amarelo, "PESQUISA")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(visitarPelaApi(marca, amarelo, "RELATORIO")).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult depois = novidades(marca, amarelo);
        assertThat(lerJson(depois, "$.abas.MIDIA.novo")).isEqualTo(false);
        assertThat(lerJson(depois, "$.abas.PESQUISA.novo")).isEqualTo(false);
        assertThat(lerJson(depois, "$.abas.RELATORIO.novo")).isEqualTo(false);
    }

    @Test
    void semVisitaONovoVemDosEventosEAsOitoAbasAparecem() {
        long amarelo = parceria("Cliente Amarelo");
        semVisitas(usuario(AMARELO), amarelo);

        MvcTestResult resposta = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> abas = (Map<String, Object>) lerJson(resposta, "$.abas");
        assertThat(abas).containsOnlyKeys("PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO", "PESQUISA",
                "CONTRATO", "RELATORIO");
        assertThat(lerJson(resposta, "$.abas.MIDIA.novo")).as("a amostra tem veiculação").isEqualTo(true);
        assertThat(lerJson(resposta, "$.abas.PESQUISA.novo")).as("a amostra tem resultado").isEqualTo(true);
        assertThat(lerJson(resposta, "$.abas.RELATORIO.novo")).as("a amostra só tem relatório em rascunho").isEqualTo(false);
        assertThat(lerJson(resposta, "$.abas.MIDIA.desde")).isNull();
    }

    @Test
    void mudancaDeStatusDeUmaEntregaContaNaAbaDela() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);
        long rbs = usuario(RBS);
        Map<String, Object> entrega = jdbc.sql("""
                        SELECT e.id, a.codigo FROM entrega e JOIN aba a ON a.id = e.aba_id
                        WHERE e.parceria_id = :p ORDER BY e.id LIMIT 1
                        """).param("p", amarelo).query().singleRow();
        long entregaId = ((Number) entrega.get("id")).longValue();
        String aba = (String) entrega.get("codigo");
        semVisitas(marketing, amarelo);
        visitar(marketing, amarelo, aba, "-2 hours");
        jdbc.sql("""
                        INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo,
                                               usuario_id, ocorrido_em)
                        VALUES (:parceria, 'entrega', :entrega, 'ALTERACAO_STATUS', 'ENVIADO', 'APROVADO', :usuario,
                                now() - interval '1 hour')
                        """).param("parceria", amarelo).param("entrega", entregaId).param("usuario", rbs).update();

        MvcTestResult antes = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);
        assertThat(lerJson(antes, "$.abas." + aba + ".novo")).isEqualTo(true);

        visitar(marketing, amarelo, aba, "1 hour");
        MvcTestResult depois = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);
        assertThat(lerJson(depois, "$.abas." + aba + ".novo")).isEqualTo(false);
    }

    @Test
    void comprovanteDaVeiculacaoContaComoNovidadeDaMidia() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);
        long rbs = usuario(RBS);
        long veiculacao = jdbc.sql("""
                        SELECT v.id FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                        JOIN parceria pa ON pa.id = ap.parceria_id WHERE pa.id = :p ORDER BY v.id LIMIT 1
                        """).param("p", amarelo).query(Long.class).single();
        long arquivo = jdbc.sql("""
                        INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             enviado_por, enviado_em)
                        VALUES (:parceria, (SELECT id FROM aba WHERE codigo = 'MIDIA'), 'COMPROVACAO', 'comprovante.png',
                                'image/png', 10, :caminho, :usuario, now() - interval '1 hour') RETURNING id
                        """).param("parceria", amarelo).param("caminho", "teste/" + UUID.randomUUID() + ".png")
                .param("usuario", rbs).query(Long.class).single();
        jdbc.sql("UPDATE veiculacao SET comprovante_arquivo_id = :arquivo WHERE id = :id")
                .param("arquivo", arquivo).param("id", veiculacao).update();
        semVisitas(marketing, amarelo);
        visitar(marketing, amarelo, "MIDIA", "-2 hours");

        MvcTestResult antes = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);
        assertThat(lerJson(antes, "$.abas.MIDIA.novo")).isEqualTo(true);

        visitar(marketing, amarelo, "MIDIA", "1 hour");
        assertThat(lerJson(novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo), "$.abas.MIDIA.novo")).isEqualTo(false);
    }

    @Test
    void perfilDaPesquisaContaComoNovidadeDaPesquisa() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);
        long pesquisa = jdbc.sql("""
                        SELECT p.id FROM pesquisa p JOIN parceria pa ON pa.evento_id = p.evento_id
                        WHERE pa.id = :p AND p.escopo = 'ATIVACAO'
                        """).param("p", amarelo).query(Long.class).single();
        long carga = novaCarga("PESQUISA", evento(2027));
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes,
                                                     carga_id)
                        VALUES (:pesquisa, :parceria, 'OUTRA', 'Categoria nova', 10, 412, :carga)
                        """).param("pesquisa", pesquisa).param("parceria", amarelo).param("carga", carga).update();
        semVisitas(marketing, amarelo);
        visitar(marketing, amarelo, "PESQUISA", "-2 hours");

        assertThat(lerJson(novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo), "$.abas.PESQUISA.novo")).isEqualTo(true);

        visitar(marketing, amarelo, "PESQUISA", "1 hour");
        assertThat(lerJson(novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo), "$.abas.PESQUISA.novo")).isEqualTo(false);
    }

    @Test
    void semSessao401ParceriaAlheia404AbaFechada403EAbaInvalida422() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");

        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo + "/novidades"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).post("/api/parcerias/" + amarelo + "/visitas/MIDIA", null))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(novidades(SessaoDeApi.entrar(mvc, AMARELO), roxo)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(visitarPelaApi(SessaoDeApi.entrar(mvc, AMARELO), roxo, "MIDIA")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(visitarPelaApi(SessaoDeApi.entrar(mvc, AMARELO), amarelo, "INVENTADA")).hasStatus(
                HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(visitarPelaApi(SessaoDeApi.entrar(mvc, AMARELO), amarelo, "INVENTADA"), "$.campos.aba"))
                .isEqualTo(VisitaService.ABA_INVALIDA);

        // O Roxo é Subcota G: a aba Pesquisa está fora da cota e o POST não grava nada.
        MvcTestResult doRoxo = visitarPelaApi(SessaoDeApi.entrar(mvc, ROXO), roxo, "PESQUISA");
        assertThat(doRoxo).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(doRoxo, "$.detail")).isEqualTo("Esta aba não faz parte da cota da sua parceria.");
        assertThat(jdbc.sql("SELECT count(*) FROM visita_aba WHERE usuario_id = :u AND parceria_id = :p")
                .param("u", usuario(ROXO)).param("p", roxo).query(Long.class).single()).isZero();

        // A RBS passa em qualquer aba, inclusive na fechada para a marca.
        assertThat(visitarPelaApi(SessaoDeApi.entrar(mvc, RBS), roxo, "PESQUISA")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(novidades(SessaoDeApi.entrar(mvc, RBS), roxo)).hasStatus(HttpStatus.OK);
    }

    @Test
    void oRelatorioEntraNaListaDeNovidadesMesmoNaoSendoAbaDoMenu() {
        long amarelo = parceria("Cliente Amarelo");
        long marketing = usuario(AMARELO);
        semVisitas(marketing, amarelo);

        MvcTestResult resposta = novidades(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(lerJson(resposta, "$.abas.RELATORIO")).isNotNull();
        assertThat(lerJson(resposta, "$.abas.RELATORIO.novo")).isEqualTo(false);
    }
}
