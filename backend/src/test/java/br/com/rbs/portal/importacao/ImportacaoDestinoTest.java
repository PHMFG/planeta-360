package br.com.rbs.portal.importacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ConfiguracaoDoJevDeTeste;
import br.com.rbs.portal.atlas.JevRoteirizado;
import br.com.rbs.portal.atlas.JevRoteirizadoConfig;
import br.com.rbs.portal.atlas.ProvedorRoteirizado;
import br.com.rbs.portal.atlas.RoteiroDeLlmConfig;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A sugestão do destino (E5 e AC3): sem destino forçado, o modelo escolhe; o JEV ARQUIVO_DESTINO roda em sombra, grava o que teria
 * sugerido em atlas_decisao e não muda o destino da importação.
 */
@TesteDeIntegracao
@Import({RoteiroDeLlmConfig.class, JevRoteirizadoConfig.class})
class ImportacaoDestinoTest {

    private static final String RBS = "b2b@rbs.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado roteiro;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste configuracaoDoJev;

    @AfterEach
    void limpar() {
        jdbc.sql("DELETE FROM atlas_decisao WHERE ponto = 'arquivo_destino'").update();
        jdbc.sql("DELETE FROM importacao").update();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN "
                + "(SELECT id FROM documento WHERE chave LIKE 'importacao-%')").update();
        jdbc.sql("DELETE FROM documento WHERE chave LIKE 'importacao-%'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria IN ('IMPORTACAO', 'DOCUMENTO') AND nome_original IN "
                + "('resumo.txt', 'numeros.txt')").update();
        roteiro.limpar();
        jev.limpar();
        configuracaoDoJev.limpar();
    }

    private long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    private long enviar(SessaoDeApi rbs, String nome, String texto, String destino) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", nome, "text/plain", texto.getBytes(StandardCharsets.UTF_8)))
                .param("eventoId", String.valueOf(evento2027())).param("alcance", "EDICAO")
                .session(rbs.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token());
        if (destino != null) {
            pedido.param("destino", destino);
        }
        MvcTestResult criada = pedido.exchange();
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        return ((Number) lerJson(criada, "$.id")).longValue();
    }

    @Test
    void oJevEmSombraGravaODestinoQueTeriaSugeridoSemMudarOForcado() {
        configuracaoDoJev.ligar(PontoDoJev.ARQUIVO_DESTINO, ModoDoJev.SOMBRA, 0.80);
        jev.limpar().arquivoDestino("pesquisa", 0.99);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = enviar(rbs, "resumo.txt", "Segunda, 05/10 - Rádio 92 - 3 chamadas assinadas para Cliente Amarelo.", "SO_CONSULTA");

        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerJson(detalhe, "$.destino")).as("o JEV em sombra não decide").isEqualTo("SO_CONSULTA");
        assertThat(jev.recebidasDe(PontoDoJev.ARQUIVO_DESTINO)).hasSize(1);
        assertThat(jev.recebidasDe(PontoDoJev.ARQUIVO_DESTINO).getFirst().pedido().estado())
                .contains("resumo.txt", "TXT", "3 chamadas assinadas");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM atlas_decisao
                        WHERE ponto = 'arquivo_destino' AND modo = 'SOMBRA' AND mensagem_id IS NULL
                          AND acao LIKE 'teria feito%' AND alvo = :alvo
                        """).param("alvo", "importacao:" + id).query(Long.class).single())
                .as("a decisão em sombra ficou registrada").isEqualTo(1);
    }

    @Test
    void semDestinoForcadoOModeloSugereODestino() {
        roteiro.limpar().passo(pedido -> {
            assertThat(pedido.mensagens().get(1).conteudo()).contains("numeros.txt", "TXT", "Números do festival");
            return new RespostaDoLlm(null, java.util.List.of(new ChamadaDeFerramenta("m1", "sugerir_destino",
                    "{\"destino\":\"SO_CONSULTA\",\"confianca\":0.87}")), null);
        });
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = enviar(rbs, "numeros.txt", "Números do festival: 80 mil planetários.", null);

        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerJson(detalhe, "$.destinoSugerido")).isEqualTo("SO_CONSULTA");
        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(((Number) lerJson(detalhe, "$.confianca")).doubleValue()).isEqualTo(0.87);
        assertThat(roteiro.pedidos()).as("a sugestão é uma chamada curta, e o destino só consulta não extrai linha").hasSize(1);
    }
}
