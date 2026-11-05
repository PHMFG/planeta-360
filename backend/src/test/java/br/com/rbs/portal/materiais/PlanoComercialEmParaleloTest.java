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
 * R5.1.b: vários envios do plano comercial ao mesmo tempo. A versão é a maior mais 1, calculada com a linha da parceria travada
 * (FOR UPDATE), então as versões saem seguidas e sem repetir: nenhum envio vira 409 ou 500. Não é {@code @Transactional}: tudo é
 * confirmado de verdade, e o teste apaga o que confirmou.
 */
@TesteDeIntegracao
class PlanoComercialEmParaleloTest extends ApoioDeEspacos {

    private static final int ENVIOS = 4;

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

    @Test
    void enviosJuntosViramVersoesSeguidasSemRepetir() throws Exception {
        long parceria = amarelo();
        int antes = (int) contar("SELECT coalesce(max(versao), 0) FROM arquivo WHERE parceria_id = ? AND categoria = 'PLANO_COMERCIAL' "
                + "AND entrega_id IS NULL", parceria);
        List<SessaoDeApi> sessoes = new ArrayList<>();
        for (int i = 0; i < ENVIOS; i++) {
            sessoes.add(entrar(RBS));
        }

        ExecutorService threads = Executors.newFixedThreadPool(ENVIOS);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<MvcTestResult>> respostas = new ArrayList<>();
        try {
            for (int i = 0; i < ENVIOS; i++) {
                SessaoDeApi sessao = sessoes.get(i);
                String nome = "plano_" + i + ".pdf";
                byte[] bytes = pdf("plano " + i);
                respostas.add(threads.submit(() -> {
                    largada.await();
                    return enviarPara(sessao, "/api/parcerias/" + parceria + "/plano-comercial", nome, bytes);
                }));
            }
            largada.countDown();
            TreeSet<Integer> versoes = new TreeSet<>();
            for (Future<MvcTestResult> resposta : respostas) {
                MvcTestResult resultado = resposta.get(60, TimeUnit.SECONDS);
                assertThat(resultado).hasStatus(HttpStatus.CREATED);
                versoes.add(((Number) lerJson(resultado, "$.versao")).intValue());
            }
            assertThat(versoes).as("as versões saem seguidas e sem repetir").hasSize(ENVIOS)
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(antes + 1, antes + ENVIOS).boxed().toList());
        } finally {
            threads.shutdownNow();
        }
        assertThat(contar("SELECT count(*) FROM (SELECT versao FROM arquivo WHERE parceria_id = ? AND categoria = 'PLANO_COMERCIAL' "
                + "AND entrega_id IS NULL GROUP BY versao HAVING count(*) > 1) repetidas", parceria)).isZero();
    }
}
