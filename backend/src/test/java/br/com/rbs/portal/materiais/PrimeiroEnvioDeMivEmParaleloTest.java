package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * R5.2.a: o primeiro envio de MIV ou logo com várias requisições ao mesmo tempo. O espaço é criado com INSERT ... ON CONFLICT DO
 * NOTHING e relido com FOR UPDATE: dois primeiros envios juntos viram v1 e v2 numa entrega só, e nenhum vira 409 ou 500. Não é
 * {@code @Transactional}: tudo é confirmado de verdade, e o teste apaga o que confirmou.
 */
@TesteDeIntegracao
class PrimeiroEnvioDeMivEmParaleloTest extends ApoioDeEspacos {

    private Limpeza limpeza;

    @BeforeEach
    void guardar() {
        limpeza = new Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        limpeza.apagar();
    }

    private void enviarJuntos(String papel, int envios) throws Exception {
        long verde = verde();
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = ?", verde, papel))
                .as("a parceria ainda não tem o espaço " + papel).isZero();
        List<SessaoDeApi> sessoes = new ArrayList<>();
        for (int i = 0; i < envios; i++) {
            sessoes.add(entrar(VERDE));
        }

        ExecutorService threads = Executors.newFixedThreadPool(envios);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<MvcTestResult>> respostas = new ArrayList<>();
        try {
            for (int i = 0; i < envios; i++) {
                SessaoDeApi sessao = sessoes.get(i);
                String nome = papel.toLowerCase() + "_" + i + ".pdf";
                byte[] bytes = pdf("primeiro envio " + i);
                respostas.add(threads.submit(() -> {
                    largada.await();
                    return enviarPara(sessao, "/api/parcerias/" + verde + "/espacos/" + papel + "/arquivos", nome, bytes);
                }));
            }
            largada.countDown();
            TreeSet<Integer> versoes = new TreeSet<>();
            TreeSet<Long> entregas = new TreeSet<>();
            for (Future<MvcTestResult> resposta : respostas) {
                MvcTestResult resultado = resposta.get(60, TimeUnit.SECONDS);
                assertThat(resultado).hasStatus(HttpStatus.CREATED);
                versoes.add(((Number) lerJson(resultado, "$.versao")).intValue());
                entregas.add(entregaDoEspaco(resultado));
            }
            assertThat(entregas).as("uma entrega só").hasSize(1);
            assertThat(versoes).as("versões seguidas, sem repetir").hasSize(envios)
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, envios).boxed().toList());
        } finally {
            threads.shutdownNow();
        }
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = ?", verde, papel)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM arquivo a JOIN entrega e ON e.id = a.entrega_id WHERE e.parceria_id = ? AND e.papel = ?",
                verde, papel)).isEqualTo(envios);
    }

    @Test
    void doisPrimeirosEnviosDeMivJuntosViramV1EV2() throws Exception {
        enviarJuntos("MIV", 2);
    }

    @Test
    void quatroPrimeirosEnviosDeLogoJuntosViramDeV1AV4() throws Exception {
        enviarJuntos("LOGO", 4);
    }
}
