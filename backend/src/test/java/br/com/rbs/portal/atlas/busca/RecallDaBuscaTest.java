package br.com.rbs.portal.atlas.busca;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.GabaritoDaBusca.Medida;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] A busca sem o modelo de linguagem, medida nas perguntas do gabarito do kit (M4). A suíte usa os embeddings de teste
 * (determinísticos, por hash de palavras): o recall@5 com a lista vetorial mais a textual tem de passar de 0,60 (o do Gemini, de 0,90,
 * está em IndexacaoDoKitComOGeminiAvaliacaoTest e na BuscaComOGeminiAvaliacaoTest). O recall só com a lista textual é o do modo
 * degradado (sem chave do Gemini ou com o disjuntor aberto): é medido e registrado, e o piso evita que ele caia sem ninguém ver.
 * Os números de cada rodada saem no log de teste (linha "Recall da busca").
 */
@TesteDeIntegracao
class RecallDaBuscaTest {

    @Autowired
    BuscaHibrida busca;

    @Autowired
    ProvedorDeEmbeddings embeddings;

    @Autowired
    JdbcClient jdbc;

    private final Map<String, Escopo> escopos = new HashMap<>();

    private Escopo escopo(String cliente) {
        return escopos.computeIfAbsent(cliente, c -> jdbc.sql("""
                        SELECT pa.evento_id, pa.cota_tipo_id, pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :cliente AND e.edicao = 2027
                        """)
                .param("cliente", c).query((rs, n) -> new Escopo(rs.getLong(1), rs.getInt(2), rs.getLong(3))).single());
    }

    private List<Candidato> hibrida(String cliente, String pergunta) {
        return busca.buscar(new Pedido(escopo(cliente), pergunta, null, 10, embeddings.paraConsulta(pergunta), embeddings.modelo())).escolhidos();
    }

    private List<Candidato> soTexto(String cliente, String pergunta) {
        return busca.buscar(new Pedido(escopo(cliente), pergunta, null, 10, null, null)).escolhidos();
    }

    @Test
    void recallDaBuscaHibridaComOsEmbeddingsDeTeste() {
        Medida medida = GabaritoDaBusca.medir(this::hibrida);

        System.out.println("Recall da busca híbrida (embeddings de teste): " + medida);
        medida.erradas().forEach(e -> System.out.println("  errou: " + e));
        assertThat(medida.perguntas()).isGreaterThanOrEqualTo(200);
        assertThat(medida.recallAos5()).as("recall@5 da busca híbrida com os embeddings de teste").isGreaterThanOrEqualTo(0.60);
    }

    @Test
    void recallSoComAListaTextualNoModoDegradado() {
        Medida medida = GabaritoDaBusca.medir(this::soTexto);

        System.out.println("Recall da busca só com a lista textual (modo degradado): " + medida);
        medida.erradas().forEach(e -> System.out.println("  errou (textual): " + e));
        assertThat(medida.perguntas()).isGreaterThanOrEqualTo(200);
        assertThat(medida.recallAos5()).as("recall@5 só com a lista textual").isGreaterThanOrEqualTo(0.50);
    }
}
