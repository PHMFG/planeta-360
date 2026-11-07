package br.com.rbs.portal.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.importacao.ImportacaoRepositorio.Evento;
import br.com.rbs.portal.importacao.extracao.ConferenciaDaExtracao;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A conferência em código de uma linha de texto corrido (E8): a regra de palavras do caso 1 da rodada A1, o mês e a data que não
 * se contradizem e as mensagens com o nome do campo em português.
 */
class ConferenciaDaExtracaoTest {

    private static final long EVENTO = 7L;

    private final ConferenciaDaExtracao conferencia = new ConferenciaDaExtracao(repositorio());

    private static ImportacaoRepositorio repositorio() {
        ImportacaoRepositorio repositorio = mock(ImportacaoRepositorio.class);
        when(repositorio.evento(EVENTO)).thenReturn(Optional.of(new Evento(EVENTO, "Planeta Atlântida", 2027)));
        return repositorio;
    }

    private static AlcanceDeUmaImportacao alcance() {
        return new AlcanceDeUmaImportacao(EVENTO, AlcanceDaImportacao.EDICAO, null, null);
    }

    private static LinhaProposta deTexto(Map<String, Object> dados, Map<String, String> trechos) {
        Map<String, OrigemDoValor> origem = new LinkedHashMap<>();
        trechos.forEach((campo, trecho) -> origem.put(campo, new OrigemDoValor("parágrafo 3", trecho)));
        return new LinhaProposta(DestinoDaImportacao.VEICULACAO, dados, origem, true);
    }

    private static Map<String, Object> dados(Object... pares) {
        Map<String, Object> dados = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            dados.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return dados;
    }

    @Test
    void oFormatoNoPluralCasaComOTrechoNoSingular() {
        LinhaProposta linha = deTexto(dados("formato", "chamada assinada de 5\""),
                Map.of("formato", "3 chamadas assinadas de 5\""));

        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void aGrafiaErradaDoWordCasaPeloPrefixoDeAoMenosCincoLetras() {
        LinhaProposta linha = deTexto(dados("formato", "comercial de 30\""),
                Map.of("formato", "2 comercialis de 30\""));

        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void oNumeroEscritoComSegundosSegOuAbreviaturaValeOMesmoNumero() {
        for (String formato : List.of("chamada de 5 segundos", "chamada de 5 seg", "chamada de 5s")) {
            LinhaProposta linha = deTexto(dados("formato", formato), Map.of("formato", "chamadas assinadas 5\""));
            assertThat(conferencia.problemas(linha, alcance(), null)).as(formato).isEmpty();
        }
    }

    @Test
    void oNumeroDiferenteNoTextoNaoCasa() {
        LinhaProposta linha = deTexto(dados("formato", "comercial de 15\""),
                Map.of("formato", "2 comerciais de 30\""));

        assertThat(conferencia.problemas(linha, alcance(), null))
                .anySatisfy(problema -> assertThat(problema).isEqualTo("O formato não aparece na fonte parágrafo 3."));
    }

    @Test
    void aDataDeOutroMesQueOMesInformadoEhRecusadaNaPrevia() {
        LinhaProposta linha = deTexto(dados("mes", "2026-11", "exibido_em", "2026-12-10"),
                Map.of("mes", "novembro de 2026", "exibido_em", "10 de dezembro de 2026"));

        assertThat(conferencia.problemas(linha, alcance(), null))
                .contains("A data da exibição não é do mês informado.");
    }

    @Test
    void asMensagensUsamONomeDoCampoEmPortugues() {
        LinhaProposta linha = deTexto(dados("formato", "Chamada", "veiculo", "Rádio Bandeirantes", "quantidade", 3),
                Map.of("formato", "Rádio 92", "veiculo", "Rádio 92"));

        assertThat(conferencia.problemas(linha, alcance(), null)).contains(
                "O formato não aparece na fonte parágrafo 3.",
                "O veículo não aparece na fonte parágrafo 3.",
                "Falta a origem da quantidade na fonte.");
    }

    @Test
    void oMesCitadoSemAnoValeComOAnoDaEdicao() {
        LinhaProposta linha = deTexto(dados("mes", "2026-10"), Map.of("mes", "em outubro"));

        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void oMesCitadoComOutroAnoNaoVale() {
        LinhaProposta linha = deTexto(dados("mes", "2026-10"), Map.of("mes", "em outubro de 2025"));

        assertThat(conferencia.problemas(linha, alcance(), null))
                .contains("O mês não aparece na fonte parágrafo 3.");
    }
}
