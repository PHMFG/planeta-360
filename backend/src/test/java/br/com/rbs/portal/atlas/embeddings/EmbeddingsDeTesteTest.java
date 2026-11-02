package br.com.rbs.portal.atlas.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.diagnostico.ConfiguracaoDeProvedores;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings.TextoDoTrecho;
import java.time.Duration;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Os embeddings falsos da suíte (determinísticos, 768 dimensões, norma 1, sem acento), o desligado e a escolha por
 * {@code ATLAS_EMBEDDINGS}.
 */
class EmbeddingsDeTesteTest {

    private final EmbeddingsDeTeste embeddings = new EmbeddingsDeTeste();

    private static double cosseno(float[] a, float[] b) {
        double soma = 0;
        for (int i = 0; i < a.length; i++) {
            soma += (double) a[i] * b[i];
        }
        return soma;
    }

    @Test
    void tem768DimensoesENormaUmESempreOMesmoVetorParaOMesmoTexto() {
        float[] a = embeddings.paraConsulta("Até quando preciso informar os geradores?");
        float[] b = embeddings.paraConsulta("Até quando preciso informar os geradores?");

        assertThat(a).hasSize(768).isEqualTo(b);
        assertThat(ProvedorDeEmbeddings.norma(a)).isCloseTo(1.0, Offset.offset(1e-5));
    }

    @Test
    void semAcentoSemMaiusculasEComOPluralDe_sTiradoOVetorEOMesmo() {
        assertThat(embeddings.paraConsulta("Inserções na newsletter")).isEqualTo(embeddings.paraConsulta("insercoes na NEWSLETTER"));
        assertThat(embeddings.paraConsulta("tarifas")).isEqualTo(embeddings.paraConsulta("tarifa"));
    }

    @Test
    void textosComPalavrasEmComumFicamMaisPertoDoQueTextosSemNada() {
        float[] pergunta = embeddings.paraConsulta("qual o prazo dos geradores");
        float[] sobreGeradores = embeddings.paraTrechos("Manual", List.of(new TextoDoTrecho("5 Abastecimento",
                "Os geradores devem ser informados à produção até o prazo combinado."))).get(0);
        float[] sobreCredenciais = embeddings.paraTrechos("Plano", List.of(new TextoDoTrecho("3 Credenciais",
                "A troca de nomes na lista de credenciados custa uma taxa por pessoa."))).get(0);

        assertThat(cosseno(pergunta, sobreGeradores)).isGreaterThan(cosseno(pergunta, sobreCredenciais) + 0.1);
        assertThat(cosseno(pergunta, sobreGeradores)).isGreaterThan(0.1);
    }

    @Test
    void osTrechosVoltamNaOrdemDada() {
        List<float[]> vetores = embeddings.paraTrechos("Titulo", List.of(new TextoDoTrecho(null, "alfa bravo"), new TextoDoTrecho("S", "charlie delta")));

        assertThat(vetores).hasSize(2);
        assertThat(vetores.get(0)).isNotEqualTo(vetores.get(1));
        assertThat(vetores.get(1)).isEqualTo(embeddings.paraTrechos("Titulo", List.of(new TextoDoTrecho("S", "charlie delta"))).get(0));
    }

    @Test
    void textoSemPalavraNenhumaDaUmVetorFixoDeNormaUm() {
        float[] vazio = embeddings.paraConsulta("");

        assertThat(ProvedorDeEmbeddings.norma(vazio)).isCloseTo(1.0, Offset.offset(1e-6));
        assertThat(embeddings.paraConsulta("?!")).isEqualTo(vazio);
    }

    @Test
    void osPrefixosDoGeminiEOFormatoDoVetorNoBanco() {
        assertThat(ProvedorDeEmbeddings.textoDoTrecho("Manual", new TextoDoTrecho("3 Energia › 3.2 Tarifa", "R$ 312,00 por kVA.")))
                .isEqualTo("title: Manual | text: 3 Energia › 3.2 Tarifa\nR$ 312,00 por kVA.");
        assertThat(ProvedorDeEmbeddings.textoDoTrecho("Manual", new TextoDoTrecho("  ", "Sem seção."))).isEqualTo("title: Manual | text: Sem seção.");
        assertThat(ProvedorDeEmbeddings.textoDaConsulta("e a tarifa?")).isEqualTo("task: search result | query: e a tarifa?");
        assertThat(ProvedorDeEmbeddings.comoTextoDoBanco(new float[] {0.5f, -0.25f, 1e-7f})).isEqualTo("[0.5,-0.25,1.0e-7]");
        assertThat(ProvedorDeEmbeddings.normalizarSeFugir(new float[] {3f, 4f})).containsExactly(0.6f, 0.8f);
        float[] jaNormal = {0.6f, 0.8f};
        assertThat(ProvedorDeEmbeddings.normalizarSeFugir(jaNormal)).isSameAs(jaNormal);
    }

    @Test
    void odesligadoRecusaComAFraseEDizQueNaoEstaLigado() {
        EmbeddingsDesligado desligado = new EmbeddingsDesligado();

        assertThat(desligado.ligado()).isFalse();
        assertThat(desligado.motivoDeDesligado()).isEqualTo(EmbeddingsDesligado.MOTIVO);
        assertThatThrownBy(() -> desligado.paraConsulta("p")).isInstanceOf(EmbeddingsIndisponivelException.class).hasMessage(EmbeddingsDesligado.MOTIVO);
        assertThatThrownBy(() -> desligado.paraTrechos("T", List.of())).isInstanceOf(EmbeddingsIndisponivelException.class);
    }

    @Test
    void aVariavelEscolheOProvedor() {
        ConfiguracaoDeProvedores config = new ConfiguracaoDeProvedores("", "", "", "none", "chave-do-gemini", "http://localhost:1", "gemini-embedding-2");
        EmbeddingsConfig escolha = new EmbeddingsConfig();

        Duration cinco = Duration.ofSeconds(5);
        Duration noventa = Duration.ofSeconds(90);
        assertThat(escolha.provedorDeEmbeddings(config, "gemini", Duration.ZERO, cinco, noventa)).isInstanceOf(EmbeddingsGemini.class);
        assertThat(escolha.provedorDeEmbeddings(config, " TESTE ", Duration.ZERO, cinco, noventa)).isInstanceOf(EmbeddingsDeTeste.class);
        assertThat(escolha.provedorDeEmbeddings(config, "none", Duration.ZERO, cinco, noventa)).isInstanceOf(EmbeddingsDesligado.class);
        assertThat(escolha.provedorDeEmbeddings(config, "", Duration.ZERO, cinco, noventa)).isInstanceOf(EmbeddingsDesligado.class);
        assertThat(escolha.provedorDeEmbeddings(config, "inventado", Duration.ZERO, cinco, noventa)).as("erro de digitação não derruba a API")
                .isInstanceOf(EmbeddingsDesligado.class);
        assertThat(escolha.provedorDeEmbeddings(config, "gemini", Duration.ZERO, cinco, noventa).ligado()).isTrue();
        ConfiguracaoDeProvedores semChave = new ConfiguracaoDeProvedores("", "", "", "none", "", "http://localhost:1", "gemini-embedding-2");
        assertThat(escolha.provedorDeEmbeddings(semChave, "gemini", Duration.ZERO, cinco, noventa).ligado()).isFalse();
    }

    @Test
    void aEsperaMaximaDaConfiguracaoChegaAoClienteDoGemini() {
        try (var servidor = br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso.iniciar()) {
            String limite = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"%s\"}]}}";
            servidor.responder(429, limite.formatted("30s"));
            ConfiguracaoDeProvedores config = new ConfiguracaoDeProvedores("", "", "", "none", "chave-do-gemini", servidor.urlBase(), "gemini-embedding-2");
            // com a espera máxima de 10 s, um retryDelay de 30 s já é a cota do dia: falha na hora, sem esperar
            ProvedorDeEmbeddings gemini = new EmbeddingsConfig().provedorDeEmbeddings(config, "gemini", Duration.ZERO, Duration.ofSeconds(5),
                    Duration.ofSeconds(10));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> gemini.paraTrechos("Titulo", List.of(new ProvedorDeEmbeddings.TextoDoTrecho(null, "texto"))))
                    .isInstanceOf(EmbeddingsIndisponivelException.class).hasMessageContaining("cota do dia");

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
            assertThat(servidor.recebidas()).hasSize(1);
        }
    }

    @Test
    void oLimiteDaConsultaDaConfiguracaoChegaAoClienteDoGemini() {
        try (var servidor = br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso.iniciar()) {
            servidor.responderDepoisDe(Duration.ofSeconds(3), 200, "{}");
            ConfiguracaoDeProvedores config = new ConfiguracaoDeProvedores("", "", "", "none", "chave-do-gemini", servidor.urlBase(), "gemini-embedding-2");
            ProvedorDeEmbeddings gemini = new EmbeddingsConfig().provedorDeEmbeddings(config, "gemini", Duration.ZERO, Duration.ofMillis(300),
                    Duration.ofSeconds(90));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> gemini.paraConsulta("p")).isInstanceOf(EmbeddingsIndisponivelException.class).hasMessageContaining("não respondeu");

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
        }
    }
}
