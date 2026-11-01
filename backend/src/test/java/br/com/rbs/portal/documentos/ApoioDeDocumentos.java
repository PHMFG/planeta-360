package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.documentos.KitDeDocumentos.Documento;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes das rotas 54 a 58 (documentos do Atlas): entrar pela rota de login, enviar um documento como o front faz (multipart
 * com sessão e token CSRF) e achar um documento do kit, que fica confirmado no banco de teste (KitDeDocumentosDeTeste). Não faz parte
 * da API.
 */
abstract class ApoioDeDocumentos {

    static final String RBS = "b2b@rbs.example";
    static final String AMARELO = "marketing@amarelo.example";
    static final String ROXO = "marketing@roxo.example";
    static final String VERDE = "marketing@verde.example";
    static final String AGENTE = "TesteDeDocumentos/1.0";
    static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";
    static final String NAO_ENCONTRADO = "Não encontramos este documento.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    /** Uma lista do JSON da resposta, pelo caminho JsonPath (com tipo concreto, para os asserts do AssertJ). */
    static List<Object> lista(MvcTestResult resposta, String caminho) {
        return ApoioDeTeste.lerLista(resposta, caminho);
    }

    /** Um objeto do JSON da resposta como mapa. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mapa(MvcTestResult resposta, String caminho) {
        return (Map<String, Object>) lerJson(resposta, caminho);
    }

    static long id(MvcTestResult resposta) {
        return ((Number) lerJson(resposta, "$.id")).longValue();
    }

    /** O id do documento do kit (D01 a D13), pela chave, a versão e a edição. */
    long doKit(String idDoKit) {
        Documento d = KitDeDocumentos.por(idDoKit);
        return jdbc.sql("""
                        SELECT dc.id FROM documento dc JOIN evento e ON e.id = dc.evento_id
                        WHERE dc.chave = :chave AND dc.versao = :versao AND e.edicao = :edicao
                          AND dc.cota_tipo_id IS NOT DISTINCT FROM (SELECT ct.id FROM cota_tipo ct WHERE ct.codigo = :cota)
                          AND dc.parceria_id IS NOT DISTINCT FROM (SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                                                                    JOIN evento ev ON ev.id = pa.evento_id
                                                                    WHERE p.nome_canonico = :patrocinador AND ev.edicao = :edicao)
                        """)
                .param("chave", d.chave()).param("versao", d.versao()).param("edicao", d.edicao()).param("patrocinador", d.patrocinador()).param("cota", d.cota())
                .query(Long.class).single();
    }

    long arquivoDoKit(String idDoKit) {
        return jdbc.sql("SELECT arquivo_id FROM documento WHERE id = :id").param("id", doKit(idDoKit)).query(Long.class).single();
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = :edicao").param("edicao", edicao)
                .query(Long.class).single();
    }

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    String statusDe(long documentoId) {
        return jdbc.sql("SELECT status FROM documento WHERE id = :id").param("id", documentoId).query(String.class).single();
    }

    static byte[] texto(String conteudo) {
        return conteudo.getBytes(StandardCharsets.UTF_8);
    }

    /** Os campos de um documento GLOBAL de 2027. */
    Map<String, String> global(String titulo, String chave) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("titulo", titulo);
        campos.put("chave", chave);
        campos.put("tipo", "OUTRO");
        campos.put("escopo", "GLOBAL");
        campos.put("eventoId", String.valueOf(evento(2027)));
        return campos;
    }

    /** POST multipart da rota 54 como o front faz: cookie de sessão, cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN. */
    MvcTestResult enviar(SessaoDeApi pessoa, String nomeDoArquivo, byte[] bytes, Map<String, String> campos) {
        return pedido(pessoa, true, nomeDoArquivo, bytes, campos).exchange();
    }

    /** O mesmo envio, sem o cabeçalho X-XSRF-TOKEN (a API tem de recusar com 403). */
    MvcTestResult enviarSemCsrf(SessaoDeApi pessoa, String nomeDoArquivo, byte[] bytes, Map<String, String> campos) {
        return pedido(pessoa, false, nomeDoArquivo, bytes, campos).exchange();
    }

    private MockMvcTester.MockMultipartMvcRequestBuilder pedido(SessaoDeApi pessoa, boolean comCsrf, String nomeDoArquivo, byte[] bytes,
                                                                Map<String, String> campos) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/documentos").multipart().header("User-Agent", AGENTE).cookie(csrf.cookie())
                .session(pessoa.sessao());
        if (comCsrf) {
            pedido = pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (nomeDoArquivo != null) {
            pedido.file(new MockMultipartFile("arquivo", nomeDoArquivo, "application/octet-stream", bytes));
        }
        for (Map.Entry<String, String> campo : campos.entrySet()) {
            pedido.param(campo.getKey(), campo.getValue());
        }
        return pedido;
    }

    /** O envio que tem de dar certo (201): devolve o id. */
    long enviarOk(SessaoDeApi rbs, String nomeDoArquivo, byte[] bytes, Map<String, String> campos) {
        MvcTestResult resposta = enviar(rbs, nomeDoArquivo, bytes, campos);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return id(resposta);
    }

    MvcTestResult acao(SessaoDeApi pessoa, long documentoId, String acao) {
        return pessoa.patch("/api/documentos/" + documentoId + "/status", "{\"acao\":\"" + acao + "\"}");
    }

    MvcTestResult acao(SessaoDeApi pessoa, long documentoId, String acao, String observacao) {
        return pessoa.patch("/api/documentos/" + documentoId + "/status", "{\"acao\":\"" + acao + "\",\"observacao\":\"" + observacao + "\"}");
    }
}
