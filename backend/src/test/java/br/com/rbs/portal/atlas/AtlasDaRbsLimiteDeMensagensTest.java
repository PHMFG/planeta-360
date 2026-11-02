package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.loop.TetoDaRbs;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] O limite de mensagens por hora na conversa da RBS (M8, problema 15 do quadro): a rota 44 da pessoa da RBS passa pelo mesmo
 * balde de fichas da marca ({@code atlas.limites.mensagens-por-hora}, aqui o padrão, 20), com a mesma ordem (o teto diário antes; o
 * 409 depois, e a tentativa recusada não gasta ficha), o mesmo 429 e a mesma frase. Vale um contexto próprio, com o {@code Clock}
 * que o teste adianta. Não é {@code @Transactional}: o turno da RBS roda com transações curtas próprias; o que o teste confirmou é
 * apagado no fim.
 */
@TesteDoAtlasDaRbs
@Import(RelogioDeTesteConfig.class)
@TestPropertySource(properties = "atlas.limites.mensagens-por-hora=20")
class AtlasDaRbsLimiteDeMensagensTest {

    private static final String PESQUISA = "pesquisa@rbs.example";
    private static final String TEXTO = "{\"texto\":\"Oi, Atlas.\"}";
    private static final String RESPOSTA = "Posso ajudar com os documentos da edição.";
    private static final String FRASE_DE_3_MINUTOS = "Você atingiu o limite de mensagens por hora. Tente de novo em 3 minutos.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    RelogioDeTeste relogio;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        // O balde fica na memória e a limpeza do banco não o zera: dois dias parados enchem todos de novo.
        relogio.avancar(Duration.ofDays(3));
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        limpeza.apagar();
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long abrir(SessaoDeApi pessoa) {
        long evento = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        MvcTestResult aberta = pessoa.post("/api/eventos/" + evento + "/atlas/conversas", "");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    String mensagens(long conversa) {
        return "/api/atlas/conversas/" + conversa + "/mensagens";
    }

    /** {@code quantas} mensagens de texto que o modelo responde, cada uma com 200. */
    void mandar(SessaoDeApi pessoa, long conversa, int quantas) {
        for (int i = 0; i < quantas; i++) {
            modelo.texto(RESPOSTA);
            assertThat(pessoa.post(mensagens(conversa), TEXTO)).as("mensagem %d de %d", i + 1, quantas).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void aMensagemVinteEUmaDaRbsRecebe429ComQuantoFaltaESemChamarOModelo() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);
        mandar(rbs, conversa, 20);
        modelo.texto("Isto não deve ser dito.");

        MvcTestResult resposta = rbs.post(mensagens(conversa), TEXTO);

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Limite de uso atingido");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DE_3_MINUTOS);
        assertThat(modelo.pedidos()).hasSize(20);   // o modelo não foi chamado na 21ª
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'user'").param("c", conversa)
                .query(Long.class).single()).as("a mensagem recusada não foi gravada").isEqualTo(20);
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void oQuantoFaltaDiminuiComOTempoEAFichaVoltaAosPoucos() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);
        mandar(rbs, conversa, 20);

        assertThat(lerJson(rbs.post(mensagens(conversa), TEXTO), "$.detail")).isEqualTo(FRASE_DE_3_MINUTOS);
        relogio.avancar(Duration.ofMinutes(2));
        assertThat(lerJson(rbs.post(mensagens(conversa), TEXTO), "$.detail"))
                .isEqualTo("Você atingiu o limite de mensagens por hora. Tente de novo em 1 minuto.");
        relogio.avancar(Duration.ofSeconds(60));
        mandar(rbs, conversa, 1);   // voltou uma ficha: uma mensagem passa
        assertThat(rbs.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteEDaPessoaEAsConversasDelaDividemOMesmoBalde() {
        SessaoDeApi rbs = entrar(RBS);
        long primeira = abrir(rbs);
        long segunda = abrir(rbs);
        mandar(rbs, primeira, 20);

        assertThat(rbs.post(mensagens(segunda), TEXTO)).as("outra conversa da mesma pessoa não zera nada")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        SessaoDeApi outra = entrar(PESQUISA);
        long daOutra = abrir(outra);
        mandar(outra, daOutra, 1);   // o balde da outra pessoa está cheio
    }

    @Test
    void oTetoDiarioDaRbsVemAntesDoLimitePorHoraEAFraseEADele() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);
        mandar(rbs, conversa, 20);
        assertThat(rbs.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :c, (SELECT max(ordem) FROM atlas_mensagem WHERE conversa_id = :c) + g, 'assistant', 'x'
                FROM generate_series(1, :quantas) g
                """).param("c", conversa).param("quantas", TetoDaRbs.PADRAO).update();

        assertThat(lerJson(rbs.post(mensagens(conversa), TEXTO), "$.detail")).isEqualTo(TetoDaRbs.MENSAGEM);
    }

    @Test
    void o429VemAntesDoConflitoEATentativaRecusadaNaoGastaFicha() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);
        mandar(rbs, conversa, 18);

        // conversa ocupada: 409, e nenhuma ficha é gasta, por mais vezes que se tente
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", conversa).update();
        for (int i = 0; i < 6; i++) {
            assertThat(rbs.post(mensagens(conversa), TEXTO)).as("tentativa %d", i + 1).hasStatus(HttpStatus.CONFLICT);
        }
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = NULL WHERE id = :c").param("c", conversa).update();
        mandar(rbs, conversa, 2);   // as duas fichas que sobravam ainda estão lá

        // esgotado, o 429 vem antes do 409
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", conversa).update();
        assertThat(rbs.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oQueNaoChegaAoModeloNaoGastaFicha() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);

        // 422: texto vazio e atalho (que a RBS não tem) são recusados antes e não gastam nada
        for (int i = 0; i < 25; i++) {
            assertThat(rbs.post(mensagens(conversa), "{\"texto\":\"   \"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rbs.post(mensagens(conversa), "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}"))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        // 404: a conversa de outra pessoa da RBS
        long daOutra = abrir(entrar(PESQUISA));
        for (int i = 0; i < 25; i++) {
            assertThat(rbs.post(mensagens(daOutra), TEXTO)).hasStatus(HttpStatus.NOT_FOUND);
        }

        mandar(rbs, conversa, 20);   // as vinte fichas continuam todas lá
        assertThat(rbs.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteTambemBarraAMensagemEmFluxoAntesDeAbrirOFluxo() {
        SessaoDeApi rbs = entrar(RBS);
        long conversa = abrir(rbs);
        mandar(rbs, conversa, 20);

        MvcTestResult emFluxo = rbs.postAceitando(mensagens(conversa), TEXTO, "text/event-stream");

        assertThat(emFluxo).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(emFluxo.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(emFluxo, "$.detail")).isEqualTo(FRASE_DE_3_MINUTOS);
    }

    @Test
    void oTurnoRecusadoPeloLimiteFicaNoTracoSemParceriaComOMotivoEOTempoQueFalta() {
        SessaoDeApi rbs = entrar(RBS);
        long gasta = abrir(rbs);
        long conversa = abrir(rbs);   // o limite é da pessoa, não da conversa: nesta só há o turno recusado
        mandar(rbs, gasta, 20);

        assertThat(rbs.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        List<Linha> turno = ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa);
        assertThat(ApoioDoDiagnostico.etapas(turno)).containsExactly("G1_LIMITES", "SAIDA");
        assertThat(ApoioDoDiagnostico.primeira(turno, "SAIDA").dados()).containsEntry("tipo", "RECUSADO_ANTES_DO_TURNO")
                .containsEntry("motivo", "LIMITE_POR_HORA");
        Linha limites = ApoioDoDiagnostico.primeira(turno, "G1_LIMITES");
        assertThat(limites.resultado()).isEqualTo("RECUSADO");
        assertThat(limites.dados()).containsEntry("motivo", "LIMITE_POR_HORA").containsEntry("esperaSegundos", 180)
                .containsEntry("tetoPorUsuario", 200);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c AND parceria_id IS NOT NULL").param("c", conversa)
                .query(Long.class).single()).isZero();
    }
}
