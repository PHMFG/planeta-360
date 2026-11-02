package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * [ATLAS] O kit de documentos indexado com o Gemini DE VERDADE (M3, "Kit indexado com o Gemini: 768 dimensões, normas, tempo").
 * Fica fora do {@code ./mvnw test}: precisa da chave do Gemini e chama a rede. Roda com
 * {@code ./mvnw test -Dgroups=avaliacao -Dtestes.excluidos=}. O perfil {@code avaliacao} (application-avaliacao.yml) liga
 * {@code ATLAS_EMBEDDINGS=gemini} e importa {@code config/segredos.local.properties}; quando o contexto sobe, o
 * {@code KitDeDocumentosDeTeste} envia, aprova e indexa os 13 documentos pelo Gemini. Sem a chave, o teste é pulado (e não falha).
 * O kit é fictício: nada de dado real sai da máquina.
 */
@Tag("avaliacao")
@TesteDeIntegracao
@ActiveProfiles({"test", "avaliacao"})
class IndexacaoDoKitComOGeminiAvaliacaoTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorDeEmbeddings embeddings;

    @Test
    void oKitFoiIndexadoComOGeminiCom768DimensoesENormaUmEOTempoFicouRegistrado() {
        assumeTrue(embeddings.ligado(), "falta a chave do Gemini (GEMINI_API_KEY ou config/segredos.local.properties)");

        assertThat(embeddings.modelo()).isEqualTo("gemini-embedding-2");
        List<Map<String, Object>> documentos = jdbc.sql("""
                        SELECT id, titulo, versao, status, modelo_embedding, dimensoes, trechos, embeddings_ms
                        FROM documento WHERE status IN ('INDEXADO', 'SUBSTITUIDO', 'REVOGADO') ORDER BY id
                        """).query().listOfRows();
        assertThat(documentos).as("os 11 documentos que passaram pela indexação: D01 a D10 e o D13, revogado").hasSize(11);
        long totalDeMs = 0;
        for (Map<String, Object> documento : documentos) {
            assertThat(documento.get("modelo_embedding")).as(documento.get("titulo") + " v" + documento.get("versao")).isEqualTo("gemini-embedding-2");
            assertThat(((Number) documento.get("dimensoes")).intValue()).isEqualTo(768);
            assertThat(((Number) documento.get("trechos")).intValue()).isGreaterThan(0);
            totalDeMs += ((Number) documento.get("embeddings_ms")).longValue();
        }

        Map<String, Object> vetores = jdbc.sql("""
                        SELECT count(*) AS trechos, count(*) FILTER (WHERE vector_dims(embedding) = 768) AS com_768,
                               min(vector_norm(embedding)) AS norma_minima, max(vector_norm(embedding)) AS norma_maxima
                        FROM documento_trecho
                        """).query().singleRow();
        assertThat(((Number) vetores.get("trechos")).longValue()).isGreaterThan(50);
        assertThat(vetores.get("com_768")).isEqualTo(vetores.get("trechos"));
        assertThat(((Number) vetores.get("norma_minima")).doubleValue()).as("norma mínima").isBetween(0.99, 1.01);
        assertThat(((Number) vetores.get("norma_maxima")).doubleValue()).as("norma máxima").isBetween(0.99, 1.01);
        System.out.println("[avaliacao] kit indexado pelo Gemini: " + vetores.get("trechos") + " trechos, embeddings em " + totalDeMs
                + " ms no total, normas de " + vetores.get("norma_minima") + " a " + vetores.get("norma_maxima"));
    }

    @Test
    void aPerguntaGeraUmVetorDe768DimensoesENormaUm() {
        assumeTrue(embeddings.ligado(), "falta a chave do Gemini (GEMINI_API_KEY ou config/segredos.local.properties)");

        float[] vetor = embeddings.paraConsulta("Até quando preciso informar os geradores?");

        assertThat(vetor).hasSize(768);
        assertThat(ProvedorDeEmbeddings.norma(vetor)).isBetween(0.99, 1.01);
    }
}
