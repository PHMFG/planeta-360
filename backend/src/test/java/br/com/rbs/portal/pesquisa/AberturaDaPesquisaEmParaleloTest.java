package br.com.rbs.portal.pesquisa;

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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * R5.6.d · Corrida: vários Admin RBS abrem a pesquisa da ativação da mesma edição ao mesmo tempo. A linha da edição fica travada
 * durante a abertura, então um pedido vence (201) e os outros recebem 409 com a frase clara: nenhum vira 500 e fica uma pesquisa só.
 * Não é {@code @Transactional}: tudo é confirmado de verdade, e o teste apaga o que criou.
 */
@TesteDeIntegracao
class AberturaDaPesquisaEmParaleloTest {

    private static final int PEDIDOS = 4;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    private long evento;

    private long ultimoHistorico;

    @BeforeEach
    void criarEdicao() {
        ultimoHistorico = jdbc.sql("SELECT coalesce(max(id), 0) FROM historico").query(Long.class).single();
        evento = jdbc.sql("INSERT INTO evento (nome, edicao) VALUES ('Planeta Atlântida', 2032) RETURNING id")
                .query(Long.class).single();
    }

    @AfterEach
    void apagar() {
        jdbc.sql("DELETE FROM pesquisa WHERE evento_id = :e").param("e", evento).update();
        jdbc.sql("DELETE FROM evento WHERE id = :e").param("e", evento).update();
        jdbc.sql("ALTER TABLE historico DISABLE TRIGGER rn_historico_imutavel").update();
        try {
            jdbc.sql("DELETE FROM historico WHERE id > :ultimo").param("ultimo", ultimoHistorico).update();
        } finally {
            jdbc.sql("ALTER TABLE historico ENABLE TRIGGER rn_historico_imutavel").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM evento WHERE id = :e").param("e", evento).query(Long.class).single())
                .as("a limpeza deixou sobra").isZero();
    }

    @Test
    void aberturasJuntasDaAtivacaoDeixamUmaSoEOsOutrosRecebem409() throws Exception {
        List<SessaoDeApi> sessoes = new ArrayList<>();
        for (int i = 0; i < PEDIDOS; i++) {
            sessoes.add(SessaoDeApi.entrar(mvc, "b2b@rbs.example"));
        }
        ExecutorService threads = Executors.newFixedThreadPool(PEDIDOS);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<MvcTestResult>> respostas = new ArrayList<>();
        try {
            for (int i = 0; i < PEDIDOS; i++) {
                SessaoDeApi sessao = sessoes.get(i);
                String json = "{\"nome\":\"Pesquisa da ativação " + i + "\"}";
                respostas.add(threads.submit(() -> {
                    largada.await();
                    return sessao.post("/api/eventos/" + evento + "/pesquisas", json);
                }));
            }
            largada.countDown();
            int criadas = 0;
            int conflitos = 0;
            for (Future<MvcTestResult> resposta : respostas) {
                int status = resposta.get(60, TimeUnit.SECONDS).getResponse().getStatus();
                if (status == 201) {
                    criadas++;
                } else if (status == 409) {
                    conflitos++;
                }
            }
            assertThat(criadas).as("um pedido vence").isEqualTo(1);
            assertThat(conflitos).as("os outros recebem 409, nenhum 500").isEqualTo(PEDIDOS - 1);
        } finally {
            threads.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM pesquisa WHERE evento_id = :e AND escopo = 'ATIVACAO'").param("e", evento)
                .query(Long.class).single()).isEqualTo(1);
    }
}
