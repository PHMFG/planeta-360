package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMultipartMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes das rotas 69 a 73 (plano comercial e espaços de documento da marca): envio multipart com sessão e CSRF, arquivos
 * mínimos de cada formato e a limpeza dos testes que confirmam de verdade (os de corrida). Não faz parte da API.
 */
abstract class ApoioDeEspacos extends ApoioDeMateriais {

    static final String VERDE = "marketing@verde.example";

    /** Um PNG mínimo: a assinatura de 8 bytes, que é o que o servidor confere. */
    static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    /** Um ZIP mínimo: começa com PK 03 04. */
    static byte[] zip() {
        return new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0};
    }

    long parceriaDo(String nomeDoPatrocinador) {
        return SessaoDeApi.parceriaDe2027(jdbc, nomeDoPatrocinador);
    }

    /** POST multipart com a parte "arquivo" e mais partes de texto (nome, valor, nome, valor...). */
    MvcTestResult enviarPara(SessaoDeApi pessoa, String uri, String nome, byte[] bytes, String... partesDeTexto) {
        return pedido(pessoa, uri, true, partesDeTexto).file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes))
                .exchange();
    }

    MvcTestResult enviarSemArquivo(SessaoDeApi pessoa, String uri, String... partesDeTexto) {
        return pedido(pessoa, uri, true, partesDeTexto).exchange();
    }

    MvcTestResult enviarSemCsrf(SessaoDeApi pessoa, String uri, String nome, byte[] bytes) {
        return pedido(pessoa, uri, false).file(new MockMultipartFile("arquivo", nome, "application/pdf", bytes)).exchange();
    }

    /** Sem login: o cookie e o token CSRF existem, a sessão não. */
    MvcTestResult enviarSemSessao(String uri, String nome, byte[] bytes) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        return mvc.post().uri(uri).multipart().cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
                .file(new MockMultipartFile("arquivo", nome, "application/pdf", bytes)).exchange();
    }

    private MockMultipartMvcRequestBuilder pedido(SessaoDeApi pessoa, String uri, boolean comCsrf, String... partesDeTexto) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MockMultipartMvcRequestBuilder pedido = mvc.post().uri(uri).multipart().header("User-Agent", AGENTE)
                .cookie(csrf.cookie()).session(pessoa.sessao());
        if (comCsrf) {
            pedido = pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        for (int i = 0; i + 1 < partesDeTexto.length; i += 2) {
            pedido = pedido.param(partesDeTexto[i], partesDeTexto[i + 1]);
        }
        return pedido;
    }

    /** O envio ao espaço (rota 72) e o confere 201. */
    MvcTestResult enviarAoEspaco(SessaoDeApi pessoa, long parceria, String papel, String nome, byte[] bytes) {
        MvcTestResult resposta = enviarPara(pessoa, "/api/parcerias/" + parceria + "/espacos/" + papel + "/arquivos", nome, bytes);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return resposta;
    }

    long entregaDoEspaco(MvcTestResult resposta) {
        return ((Number) lerJson(resposta, "$.entregaId")).longValue();
    }

    /** Fecha uma aba da parceria no banco (o teste é desfeito no fim). */
    void fecharAba(long parceriaId, String abaCodigo, boolean liberada, boolean visivel) {
        int linhas = jdbc.sql("""
                        UPDATE parceria_aba SET liberada = :liberada, visivel = :visivel
                        WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :aba)
                        """)
                .param("liberada", liberada).param("visivel", visivel).param("p", parceriaId).param("aba", abaCodigo).update();
        assertThat(linhas).isEqualTo(1);
    }

    void encerrar(long parceriaId) {
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :id").param("id", parceriaId).update();
    }

    /**
     * Para os testes que confirmam de verdade (sem @Transactional): guarda o último id de arquivo, entrega e histórico e, no fim, apaga o
     * que passou disso. O histórico é imutável por gatilho, que fica desligado só durante a limpeza.
     */
    static final class Limpeza {

        private final JdbcClient jdbc;
        private final Map<String, Long> ultimoIdAntes = new LinkedHashMap<>();

        Limpeza(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        void guardar() {
            for (String tabela : new String[] {"historico", "arquivo", "entrega"}) {
                ultimoIdAntes.put(tabela, jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela).query(Long.class).single());
            }
        }

        void apagar() {
            jdbc.sql("DELETE FROM arquivo WHERE id > :u").param("u", ultimoIdAntes.get("arquivo")).update();
            jdbc.sql("DELETE FROM entrega WHERE id > :u").param("u", ultimoIdAntes.get("entrega")).update();
            jdbc.sql("ALTER TABLE historico DISABLE TRIGGER rn_historico_imutavel").update();
            try {
                jdbc.sql("DELETE FROM historico WHERE id > :u").param("u", ultimoIdAntes.get("historico")).update();
            } finally {
                jdbc.sql("ALTER TABLE historico ENABLE TRIGGER rn_historico_imutavel").update();
            }
        }
    }
}
