package br.com.rbs.portal.atlas.busca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.GabaritoDaBusca.Medida;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * [ATLAS] A busca híbrida medida com o Gemini DE VERDADE nas perguntas do gabarito do kit (M4): recall@5 de pelo menos 0,90 e MRR de
 * pelo menos 0,70 (com os embeddings de teste, da suíte, o piso é 0,60: RecallDaBuscaTest). Fica fora do {@code ./mvnw test}: precisa
 * da chave do Gemini e chama a rede (uma consulta por pergunta, mais de 200). Roda com
 * {@code ./mvnw test -Dgroups=avaliacao -Dtest=BuscaComOGeminiAvaliacaoTest -Dtestes.excluidos=}. O perfil {@code avaliacao}
 * liga {@code ATLAS_EMBEDDINGS=gemini} e importa {@code config/segredos.local.properties}; ao subir o contexto, o
 * {@code KitDeDocumentosDeTeste} envia, aprova e indexa os 13 documentos pelo Gemini. Sem a chave, o teste é pulado (e não falha).
 * O kit é fictício: nada de dado real sai da máquina. Os números saem no log (linha "[avaliacao]").
 */
@Tag("avaliacao")
@TesteDeIntegracao
@ActiveProfiles({"test", "avaliacao"})
class BuscaComOGeminiAvaliacaoTest {

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

    /** O embedding da pergunta, com até 3 tentativas e uma pausa (a cota por minuto do Gemini pode segurar uma rodada de 200). */
    private float[] vetorDe(String pergunta) {
        RuntimeException ultima = null;
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            try {
                return embeddings.paraConsulta(pergunta);
            } catch (RuntimeException falhou) {
                ultima = falhou;
                try {
                    TimeUnit.SECONDS.sleep(2L * (tentativa + 1));
                } catch (InterruptedException interrompido) {
                    Thread.currentThread().interrupt();
                    throw falhou;
                }
            }
        }
        throw ultima;
    }

    private List<Candidato> hibrida(String cliente, String pergunta) {
        return busca.buscar(new Pedido(escopo(cliente), pergunta, null, 10, vetorDe(pergunta), embeddings.modelo())).escolhidos();
    }

    private List<Candidato> soTexto(String cliente, String pergunta) {
        return busca.buscar(new Pedido(escopo(cliente), pergunta, null, 10, null, null)).escolhidos();
    }

    @Test
    void recallEMrrDaBuscaHibridaComOGemini() {
        assumeTrue(embeddings.ligado(), "falta a chave do Gemini (GEMINI_API_KEY ou config/segredos.local.properties)");
        assertThat(embeddings.modelo()).isEqualTo("gemini-embedding-2");

        Medida medida = GabaritoDaBusca.medir(this::hibrida);

        System.out.println("[avaliacao] busca híbrida com o Gemini: " + medida);
        medida.erradas().forEach(e -> System.out.println("[avaliacao]   errou: " + e));
        assertThat(medida.perguntas()).isGreaterThanOrEqualTo(200);
        assertThat(medida.recallAos5()).as("recall@5 com o Gemini").isGreaterThanOrEqualTo(0.90);
        assertThat(medida.mrr()).as("MRR com o Gemini").isGreaterThanOrEqualTo(0.70);
    }

    @Test
    void recallSoComAListaTextualDoModoDegradadoSobOMesmoIndice() {
        assumeTrue(embeddings.ligado(), "falta a chave do Gemini (GEMINI_API_KEY ou config/segredos.local.properties)");

        Medida medida = GabaritoDaBusca.medir(this::soTexto);

        System.out.println("[avaliacao] busca só com a lista textual (modo degradado): " + medida);
        assertThat(medida.recallAos5()).as("recall@5 só com a lista textual").isGreaterThanOrEqualTo(0.50);
    }
}
