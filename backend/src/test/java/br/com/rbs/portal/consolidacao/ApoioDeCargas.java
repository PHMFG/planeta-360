package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes das rotas 26 a 28 (cargas): entrar pela rota de login, enviar a planilha como o front faz (multipart
 * com sessão e token CSRF) e ler os exemplos de docs/exemplos. Atende o item 5 da Entrega 2. Não faz parte da API.
 */
abstract class ApoioDeCargas {

    static final String RBS = "b2b@rbs.example";
    static final String AMARELO = "marketing@amarelo.example";
    static final String ROXO = "marketing@roxo.example";
    static final String AGENTE = "TesteDeCargas/1.0";
    static final String CABECALHO_VEICULACAO = "patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link";
    static final String CABECALHO_PESQUISA = "patrocinador,pergunta,indicador,alternativa,valor,unidade,base_respondentes";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email, AGENTE);
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    static int inteiro(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).intValue();
    }

    static long longo(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    /** Os nomes dos campos de um objeto do JSON da resposta, na ordem em que vieram. */
    @SuppressWarnings("unchecked")
    static List<String> chaves(MvcTestResult resposta, String caminho) {
        return new ArrayList<>(((Map<String, Object>) lerJson(resposta, caminho)).keySet());
    }

    /** O exemplo de docs/exemplos (o Maven roda os testes dentro de backend/). */
    static byte[] exemplo(String nome) {
        try {
            return Files.readAllBytes(Path.of("..", "docs", "exemplos", nome));
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    static byte[] texto(String csv) {
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    /** POST /api/cargas como o front: multipart, cookie de sessão, cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN. */
    MvcTestResult enviar(SessaoDeApi pessoa, String nome, byte[] bytes, String tipo, Object eventoId, String escopo) {
        return pedido(pessoa, true, nome, bytes, tipo, eventoId, escopo);
    }

    /** O mesmo envio, mas sem o cabeçalho X-XSRF-TOKEN (a API tem de recusar com 403). */
    MvcTestResult enviarSemCsrf(SessaoDeApi pessoa, String nome, byte[] bytes, String tipo, Object eventoId) {
        return pedido(pessoa, false, nome, bytes, tipo, eventoId, null);
    }

    private MvcTestResult pedido(SessaoDeApi pessoa, boolean comCsrf, String nome, byte[] bytes, String tipo, Object eventoId,
                                 String escopo) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/cargas").multipart().header("User-Agent", AGENTE).cookie(csrf.cookie())
                .session(pessoa.sessao());
        if (comCsrf) {
            pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (bytes != null) {
            pedido.file(new MockMultipartFile("arquivo", nome, "text/csv", bytes));
        }
        if (tipo != null) {
            pedido.param("tipo", tipo);
        }
        if (eventoId != null) {
            pedido.param("eventoId", String.valueOf(eventoId));
        }
        if (escopo != null) {
            pedido.param("escopo", escopo);
        }
        return pedido.exchange();
    }
}
