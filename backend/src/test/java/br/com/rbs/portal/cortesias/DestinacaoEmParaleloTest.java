package br.com.rbs.portal.cortesias;

import static br.com.rbs.portal.cortesias.ApoioDeCortesias.RBS;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.VERDE;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.destinacao;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uriDestinacoes;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.verde;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * [R5.4.c] [CORRIDA] Dois envios juntos não passam do disponível. A cota é travada com FOR UPDATE antes de somar o destinado: sem a
 * trava, os dois pedidos liam "3 disponíveis" ao mesmo tempo e os dois gravavam. Aqui os pedidos largam juntos, de pessoas
 * diferentes (a marca e a RBS), numa cota nova do Premium da Verde, e no fim o banco não tem mais do que a cota. Não é
 * {@code @Transactional}: tudo é confirmado de verdade, e o teste apaga o que confirmou (o histórico, que é imutável, com o gatilho
 * desligado só durante a limpeza).
 */
@TesteDeIntegracao
class DestinacaoEmParaleloTest {

    private static final int COTA = 3;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private long parceria;
    private long cota;
    private long ultimoHistorico;

    @BeforeEach
    void prepararACota() {
        parceria = verde(jdbc);
        ultimoHistorico = jdbc.sql("SELECT coalesce(max(id), 0) FROM historico").query(Long.class).single();
        cota = jdbc.sql("""
                INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade) VALUES (:p, 'PREMIUM', 'SEXTA', :q) RETURNING id
                """).param("p", parceria).param("q", COTA).query(Long.class).single();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        jdbc.sql("DELETE FROM cortesia_destinacao WHERE cortesia_cota_id = :c").param("c", cota).update();
        jdbc.sql("DELETE FROM cortesia_cota WHERE id = :c").param("c", cota).update();
        jdbc.sql("ALTER TABLE historico DISABLE TRIGGER rn_historico_imutavel").update();
        try {
            jdbc.sql("DELETE FROM historico WHERE id > :ultimo").param("ultimo", ultimoHistorico).update();
        } finally {
            jdbc.sql("ALTER TABLE historico ENABLE TRIGGER rn_historico_imutavel").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM cortesia_cota WHERE id = :c").param("c", cota).query(Long.class).single())
                .as("a limpeza deixou sobra").isZero();
    }

    /** Dispara um POST por quantidade, todos ao mesmo tempo, e devolve o status de cada um. */
    private List<Integer> dispararJuntos(List<Integer> quantidades) throws Exception {
        List<SessaoDeApi> pessoas = new ArrayList<>();
        for (int i = 0; i < quantidades.size(); i++) {
            pessoas.add(SessaoDeApi.entrar(mvc, i % 2 == 0 ? VERDE : RBS));   // as sessões são abertas antes: só o POST corre junto
        }
        ExecutorService pool = Executors.newFixedThreadPool(quantidades.size());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < quantidades.size(); i++) {
                SessaoDeApi pessoa = pessoas.get(i);
                String corpo = destinacao("PREMIUM", "SEXTA", quantidades.get(i), "Convidado " + i);
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return pessoa.post(uriDestinacoes(parceria), corpo).getResponse().getStatus();
                }));
            }
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                status.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return status;
        } finally {
            pool.shutdownNow();
        }
    }

    private long destinadas() {
        return jdbc.sql("SELECT CAST(coalesce(sum(quantidade), 0) AS bigint) FROM cortesia_destinacao WHERE cortesia_cota_id = :c")
                .param("c", cota).query(Long.class).single();
    }

    @Test
    void doisEnviosJuntosQueCabemSoUmNaCotaPassamSoUm() throws Exception {
        List<Integer> status = dispararJuntos(List.of(COTA, COTA));

        assertThat(status).as("um grava e o outro é recusado, nunca os dois nem um 500").containsExactlyInAnyOrder(201, 422);
        assertThat(destinadas()).isEqualTo(COTA);
    }

    @Test
    void muitosEnviosDeUmaPorVezNuncaPassamDaCota() throws Exception {
        List<Integer> status = dispararJuntos(List.of(1, 1, 1, 1, 1, 1, 1, 1));

        assertThat(status.stream().filter(s -> s == 201).count()).isEqualTo(COTA);
        assertThat(status.stream().filter(s -> s == 422).count()).isEqualTo(status.size() - COTA);
        assertThat(destinadas()).isEqualTo(COTA);
        assertThat(jdbc.sql("SELECT count(*) FROM cortesia_destinacao WHERE cortesia_cota_id = :c").param("c", cota).query(Long.class).single())
                .isEqualTo(COTA);
    }
}
