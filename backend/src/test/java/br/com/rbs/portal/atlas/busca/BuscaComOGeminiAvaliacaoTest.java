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
 * O kit é fictício: nada de dado real sai da máquina. Os números saem no log (linha "[avaliacao]"). As consultas andam a no máximo 80
 * por minuto, então as mais de 200 perguntas levam uns 3 minutos.
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

    /** No máximo 80 consultas por minuto (o Gemini gratuito segura 100): uma a cada 750 ms. */
    private static final long INTERVALO_ENTRE_CONSULTAS_MS = 60_000L / 80;
    /** Se a consulta falhar mesmo assim, espera 30 s (o limite por minuto passa sozinho) e tenta de novo, até 4 vezes. */
    private static final long PAUSA_DEPOIS_DE_FALHAR_MS = 30_000L;
    private static final int NOVAS_TENTATIVAS = 4;

    private long ultimaConsultaEm;

    private static void dormir(long milissegundos) {
        try {
            TimeUnit.MILLISECONDS.sleep(milissegundos);
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A espera do teste foi interrompida.", interrompido);
        }
    }

    /** Espera o que falta para a consulta seguinte ficar a {@value #INTERVALO_ENTRE_CONSULTAS_MS} ms da anterior. */
    private void andarNoRitmo() {
        if (ultimaConsultaEm != 0) {
            long falta = INTERVALO_ENTRE_CONSULTAS_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ultimaConsultaEm);
            if (falta > 0) {
                dormir(falta);
            }
        }
        ultimaConsultaEm = System.nanoTime();
    }

    /**
     * O embedding da pergunta. {@code paraConsulta} faz uma tentativa só e não espera nada, então o teste é quem respeita o limite por
     * minuto: anda a no máximo 80 consultas por minuto e, se o Gemini ainda assim recusar, espera 30 s e tenta de novo, até 4 vezes.
     */
    private float[] vetorDe(String pergunta) {
        for (int nova = 0; ; nova++) {
            andarNoRitmo();
            try {
                return embeddings.paraConsulta(pergunta);
            } catch (RuntimeException falhou) {
                if (nova >= NOVAS_TENTATIVAS) {
                    throw falhou;
                }
                dormir(PAUSA_DEPOIS_DE_FALHAR_MS);
            }
        }
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
