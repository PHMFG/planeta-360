package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] [ISOLAMENTO] O traço de uma conversa sem parceria (a do Atlas da RBS, V15) aparece para a RBS nas rotas 50 e 51, com
 * {@code parceriaId} e {@code parceria} nulos: as consultas juntavam {@code parceria} e {@code patrocinador} por junção interna e
 * deixavam o turno de fora. A marca nunca o vê (403 nas rotas da RBS, 404 na rota 49), e o banco não deixa um traço sem parceria
 * entrar numa conversa de marca, que ficaria invisível ao diagnóstico dela. Mesmo contexto dos testes do diagnóstico. Não é
 * {@code @Transactional}: segue o {@link DiagnosticoRotasTest}; o que o teste confirmou é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class DiagnosticoSemParceriaTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    long conversaDoEventoDe(String email) {
        return jdbc.sql("""
                INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
                VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM usuario WHERE email = :email)) RETURNING id
                """).param("email", email).query(Long.class).single();
    }

    /** Um turno de três etapas na conversa dada, sem parceria. */
    UUID turnoSemParceria(long conversa) {
        UUID turno = UUID.randomUUID();
        String[][] etapas = {{"G1_LIMITES", "{\"tetoPorUsuario\":200}"}, {"LLM_IDA", "{\"ida\":1,\"tokensEntrada\":10,\"tokensSaida\":2}"},
                {"SAIDA", "{\"tipo\":\"TEXTO\"}"}};
        for (int i = 0; i < etapas.length; i++) {
            jdbc.sql("""
                    INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms, dados)
                    VALUES (NULL, :conversa, :turno, :ordem, :etapa, 'OK', now() + (:ordem * interval '1 millisecond'), 1, CAST(:dados AS jsonb))
                    """).param("conversa", conversa).param("turno", turno).param("ordem", i + 1).param("etapa", etapas[i][0])
                    .param("dados", etapas[i][1]).update();
        }
        return turno;
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    @Test
    void aRbsVeNaListaDeTurnosOTurnoDaConversaSemParceria() {
        long conversa = conversaDoEventoDe(RBS);
        UUID turno = turnoSemParceria(conversa);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult lista = rbs.get("/api/atlas/diagnostico/turnos?tamanho=200");

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')]")).hasSize(1);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].parceriaId")).containsExactly((Object) null);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].parceria")).containsExactly((Object) null);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].conversaId")).containsExactly((int) conversa);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].usuario.perfil")).containsExactly("ADMIN_RBS");
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].etapas")).containsExactly(3);
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + turno + "')].resultado")).containsExactly("OK");
    }

    @Test
    void oFiltroPorParceriaNaoTrazOTurnoSemParceria() {
        long conversa = conversaDoEventoDe(RBS);
        UUID turno = turnoSemParceria(conversa);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult daParceria = rbs.get("/api/atlas/diagnostico/turnos?tamanho=200&parceriaId=" + amarelo());

        assertThat(daParceria).hasStatus(HttpStatus.OK);
        assertThat(corpo(daParceria)).doesNotContain(turno.toString());
    }

    @Test
    void aRbsLeNaRota51OTurnoSemParceriaComAsEtapas() {
        long conversa = conversaDoEventoDe(RBS);
        UUID turno = turnoSemParceria(conversa);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult detalhe = rbs.get("/api/atlas/diagnostico/turnos/" + turno);

        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(lerJson(detalhe, "$.turno")).isEqualTo(turno.toString());
        assertThat(lerJson(detalhe, "$.parceriaId")).isNull();
        assertThat(lerJson(detalhe, "$.parceria")).isNull();
        assertThat(lerJson(detalhe, "$.usuario.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerLista(detalhe, "$.etapas[*].etapa")).containsExactly("G1_LIMITES", "LLM_IDA", "SAIDA");
        assertThat(lerJson(detalhe, "$.tokensEntrada")).isEqualTo(10);
    }

    @Test
    void aMarcaNuncaVeOTurnoSemParceria() {
        long conversa = conversaDoEventoDe(RBS);
        UUID turno = turnoSemParceria(conversa);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);

        assertThat(marca.get("/api/atlas/diagnostico/turnos")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.get("/api/atlas/diagnostico/turnos/" + turno)).hasStatus(HttpStatus.FORBIDDEN);
        // a rota 49 é da conversa da marca: a do evento é como a que não existe, mesmo com o diagnóstico da marca ligado
        MvcTestResult daConversa = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        assertThat(daConversa).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(corpo(daConversa)).doesNotContain(turno.toString()).doesNotContain("G1_LIMITES");
    }

    @Test
    void oBancoNaoDeixaUmTracoSemParceriaEntrarNaConversaDeUmaMarca() {
        long conversaDaMarca = jdbc.sql("""
                INSERT INTO atlas_conversa (parceria_id, usuario_id)
                VALUES (:parceria, (SELECT id FROM usuario WHERE email = :email)) RETURNING id
                """).param("parceria", amarelo()).param("email", AMARELO).query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
                VALUES (NULL, :conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1)
                """).param("conversa", conversaDaMarca).update()).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("tem de levar a parceria dela");
        assertThat(List.of(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c").param("c", conversaDaMarca)
                .query(Long.class).single())).containsExactly(0L);
    }
}
