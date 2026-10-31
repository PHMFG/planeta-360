package br.com.rbs.portal.atlas.diagnostico;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.UsoDoLlm;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * [ATLAS] A limpeza e as contas do {@link RegistroDoTraco}, sem banco: o que entra em {@code dados} (texto cortado em 20.000
 * caracteres, sem caractere nulo, sem as chaves de provedor que a API conhece nem o que parece chave, por dentro de mapas e
 * listas) e o custo de uma ida (o {@code list_cost} do provedor primeiro; senão o preço por milhão configurado; senão nenhum).
 * O {@link Traco#NENHUM} não grava nem guarda nada.
 */
class RegistroDoTracoTest {

    private static final String CHAVE_DO_LLM = "chave-de-mentira-so-do-teste";
    private static final String CHAVE_DO_JEV = "outra-chave-de-mentira-do-jev";

    RegistroDoTraco registro(boolean ligado, String precoDeEntrada, String precoDeSaida) {
        MockEnvironment ambiente = new MockEnvironment().withProperty("spring.ai.openai.api-key", CHAVE_DO_LLM)
                .withProperty("atlas.jev.chave", CHAVE_DO_JEV).withProperty("atlas.embeddings.chave", "curta");
        return new RegistroDoTraco(null, null, new ModoDoAtlas(false, false, ligado, false), ambiente, new BigDecimal(precoDeEntrada),
                new BigDecimal(precoDeSaida));
    }

    @Test
    void desligadoEntregaOTracoQueNaoFazNada() {
        Traco traco = registro(false, "0", "0").abrir(1, 2);

        assertThat(traco).isSameAs(Traco.NENHUM);
        assertThat(traco.ativo()).isFalse();
        assertThat(traco.turno()).isNull();
        traco.abrir(EtapaDoTraco.LLM_IDA).dado("a", "b").mensagem(7L).fim(ResultadoDaEtapa.OK);
        traco.erro(new IllegalStateException("x"));
        traco.encerrar();   // não há registro nem banco: se tentasse gravar, quebraria
        assertThat(traco.dadosDoUso(new UsoDoLlm("m", 1, 2, "stop", 3L, null), "m")).isEmpty();
    }

    @Test
    void ligadoCadaTurnoTemUmIdNovo() {
        RegistroDoTraco registro = registro(true, "0", "0");

        Traco um = registro.abrir(1, 2);
        Traco outro = registro.abrir(1, 2);

        assertThat(um.ativo()).isTrue();
        assertThat(um.turno()).isNotNull().isNotEqualTo(outro.turno());
    }

    @Test
    void textoPassaDeVinteMilCaracteresECortado() {
        RegistroDoTraco registro = registro(true, "0", "0");

        assertThat(registro.limparTexto("a".repeat(20_000))).hasSize(20_000);
        assertThat(registro.limparTexto("a".repeat(20_001))).hasSize(20_000);
        assertThat(registro.limparTexto("curto")).isEqualTo("curto");
        // não corta um par substituto ao meio
        String comEmoji = "a".repeat(19_999) + "😀";
        assertThat(registro.limparTexto(comEmoji)).hasSize(19_999);
    }

    @Test
    void caractereNuloEAsChavesConhecidasSaemDoTexto() {
        RegistroDoTraco registro = registro(true, "0", "0");

        assertThat(registro.limparTexto("antes\u0000depois")).isEqualTo("antesdepois");
        assertThat(registro.limparTexto("Incorrect API key provided: " + CHAVE_DO_LLM + " e " + CHAVE_DO_JEV))
                .isEqualTo("Incorrect API key provided: [omitido] e [omitido]");
        assertThat(registro.limparTexto("Authorization: Bearer abcdef1234567890 sk-abcdef123456"))
                .isEqualTo("Authorization: [omitido] [omitido]");
        // uma chave curta demais (menos de 8 caracteres) não é tratada como segredo: apagar "curta" estragaria qualquer texto
        assertThat(registro.limparTexto("uma curta frase")).isEqualTo("uma curta frase");
    }

    @Test
    void limpaPorDentroDeMapasEListasEDeixaNumerosEBooleanosComoEstao() {
        RegistroDoTraco registro = registro(true, "0", "0");
        Map<String, Object> filho = new LinkedHashMap<>();
        filho.put("erro", "401: " + CHAVE_DO_LLM);
        filho.put("nulo", null);
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("ferramentas", List.of("parceria_consultar", "x\u0000y"));
        dados.put("filho", filho);
        dados.put("tokens", 12);
        dados.put("custoUsd", new BigDecimal("0.0004"));
        dados.put("emFluxo", true);

        Object limpo = registro.limpar(dados);

        assertThat(limpo).isEqualTo(Map.of("ferramentas", List.of("parceria_consultar", "xy"), "filho", Map.of("erro", "401: [omitido]"),
                "tokens", 12, "custoUsd", new BigDecimal("0.0004"), "emFluxo", true));
    }

    @Test
    void custoVemDoListCostDaRespostaEEntaoDoPrecoConfiguradoEEntaoNenhum() {
        UsoDoLlm comListCost = new UsoDoLlm("deepseek-v4.1-flash", 1000, 500, "stop", 800L, new BigDecimal("0.00042"));
        UsoDoLlm semListCost = new UsoDoLlm("deepseek-v4.1-flash", 1000, 500, "stop", 800L, null);
        UsoDoLlm semTokens = new UsoDoLlm("deepseek-v4.1-flash", null, null, "stop", 800L, null);

        Map<String, Object> daResposta = registro(true, "0.14", "0.28").dadosDoUso(comListCost, null);
        assertThat(daResposta).containsEntry("modelo", "deepseek-v4.1-flash").containsEntry("tokensEntrada", 1000)
                .containsEntry("tokensSaida", 500).containsEntry("motivoDoFim", "stop").containsEntry("latenciaMs", 800L)
                .containsEntry("custoUsd", new BigDecimal("0.00042")).containsEntry("custoOrigem", "RESPOSTA");

        // sem list_cost: 1000 x 0,14 + 500 x 0,28 = 280 por milhão de tokens = 0,00028
        Map<String, Object> configurado = registro(true, "0.14", "0.28").dadosDoUso(semListCost, null);
        assertThat((BigDecimal) configurado.get("custoUsd")).isEqualByComparingTo("0.00028");
        assertThat(configurado).containsEntry("custoOrigem", "PRECO_CONFIGURADO");

        // sem list_cost e sem preço (o padrão do application.yml, porque o preço não consta nas referências): sem custo
        assertThat(registro(true, "0", "0").dadosDoUso(semListCost, null)).doesNotContainKey("custoUsd").doesNotContainKey("custoOrigem");
        // sem os tokens, nem o preço configurado dá para aplicar
        assertThat(registro(true, "0.14", "0.28").dadosDoUso(semTokens, null)).doesNotContainKey("custoUsd");
    }

    @Test
    void semUsoSaiSoOModeloDoProvedorSeHouver() {
        RegistroDoTraco registro = registro(true, "0", "0");

        assertThat(registro.dadosDoUso(null, "deepseek-v4.1-flash")).isEqualTo(Map.of("modelo", "deepseek-v4.1-flash"));
        assertThat(registro.dadosDoUso(null, null)).isEmpty();
        assertThat(registro.dadosDoUso(new UsoDoLlm(null, null, null, null, null, null), "outro")).isEqualTo(Map.of("modelo", "outro"));
    }
}
