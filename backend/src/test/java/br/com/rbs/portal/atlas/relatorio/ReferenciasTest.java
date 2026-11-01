package br.com.rbs.portal.atlas.relatorio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] [VALIDACAO] A gramática das referências (plano, seção 10.3) e dos marcadores de texto: só as seis famílias, com os
 * nomes fechados; o que não bate com a regex é recusado. Teste de unidade.
 */
class ReferenciasTest {

    @Test
    void aceitaAsReferenciasDasSeisFamilias() {
        for (String ref : List.of("parc:patrocinador", "parc:cota", "evento:edicao", "cons:aplicacoes.previstas",
                "cons:aplicacoes.comprovadas", "cons:aplicacoes.fotos", "cons:aplicacoes.percentual", "lista:aplicacoes",
                "lista:comprovacoes", "dado:ativacao.visitantes_total", "dado:ativacao.brindes_distribuidos", "calc:taxa_cadastro",
                "calc:brindes_pct", "calc:energia_20kva", "calc:a1", "calc:t" + "0".repeat(59))) {
            assertThat(Referencias.valida(ref)).as(ref).isTrue();
        }
    }

    @Test
    void recusaOQueNaoEstaNaGramatica() {
        for (String ref : List.of("", " ", "parc:cnpj", "parc:", "evento:nome", "cons:aplicacoes", "cons:aplicacoes.tudo",
                "cons:midia.previstas", "lista:entregas", "dado:visitantes", "dado:Ativacao.visitantes", "dado:ativacao.visitantes total",
                "dado:ativacao.", "calc:", "calc:Taxa", "calc:taxa-cadastro", "calc:" + "a".repeat(61), "calc:20kva", "calc:_taxa", "calc:t" + "0".repeat(60), "outro:valor",
                "parc:patrocinador ", " parc:patrocinador", "parc:patrocinador\nx", "javascript:alert(1)", "http://exemplo.com",
                "1200", "<b>x</b>")) {
            assertThat(Referencias.valida(ref)).as("[" + ref + "]").isFalse();
        }
        assertThat(Referencias.valida(null)).isFalse();
    }

    @Test
    void acharOsMarcadoresDeUmTextoNaOrdemEmQueAparecem() {
        String texto = "A marca {{parc:patrocinador}} teve {{ cons:aplicacoes.comprovadas }} de {{cons:aplicacoes.previstas}}, "
                + "{{cons:aplicacoes.comprovadas}} de novo.";

        assertThat(Referencias.marcadores(texto)).containsExactly("parc:patrocinador", "cons:aplicacoes.comprovadas",
                "cons:aplicacoes.previstas", "cons:aplicacoes.comprovadas");
        assertThat(Referencias.marcadores(null)).isEmpty();
        assertThat(Referencias.marcadores("sem nenhum")).isEmpty();
    }

    @Test
    void textoSemMarcadoresMostraOQueOAutorEscreveuPorContaPropria() {
        assertThat(Referencias.semMarcadores("O espaço recebeu {{dado:ativacao.visitantes_total}} visitantes."))
                .isEqualTo("O espaço recebeu  visitantes.");
        assertThat(Referencias.semMarcadores("Foram 1200 visitantes {{dado:ativacao.visitantes_total}}.")).contains("1200");
    }

    @Test
    void trocarMarcadoresPeloValorSemInterpretarOQueVier() {
        String texto = Referencias.substituir("Ola {{parc:patrocinador}}, {{evento:edicao}}.",
                ref -> ref.startsWith("parc") ? "Marca $1 {{x}} \\n" : "2027");

        assertThat(texto).isEqualTo("Ola Marca $1 {{x}} \\n, 2027.");
    }
}
