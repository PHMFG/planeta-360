package br.com.rbs.portal.importacao.extracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.AlcanceDaImportacao;
import br.com.rbs.portal.importacao.ConteudoLido;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.leitores.LeitorDePlanilha.AbaLida;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O mapeamento tabular reconhece a planilha de pesquisa sem modelo: o cabeçalho com pergunta/alternativa/base ou
 * dimensão/categoria sugere PESQUISA e o código monta as linhas dos três tipos. [IMPORTACAO] Teste puro, sem banco.
 */
class MapeadorTabularPesquisaTest {

    private static ConteudoLido conteudo(List<List<String>> linhas) {
        return new ConteudoLido("CSV", List.of(), Map.of("abas", List.of(new AbaLida("Pesquisa", linhas))));
    }

    @Test
    void oCabecalhoDaPesquisaSugereODestinoEDistingueOsTresTipos() {
        ConteudoLido planilha = conteudo(List.of(
                List.of("tipo", "patrocinador", "pergunta", "alternativa", "dimensao", "categoria", "valor",
                        "base_respondentes", "unidade"),
                List.of("PERFIL", "Cliente Amarelo", "", "", "GENERO", "Feminino", "58", "412", ""),
                List.of("RESULTADO", "Cliente Amarelo", "1", "Sim", "", "", "64", "412", "PERCENTUAL"),
                List.of("TEXTO", "Cliente Amarelo", "4", "Gostei da ativação", "", "", "1", "412", "")));
        MapeadorTabular mapeador = new MapeadorTabular(null, null);

        assertThat(mapeador.sugerirDestino(planilha)).isEqualTo(DestinoDaImportacao.PESQUISA);
        var extracao = mapeador.extrairSemModeloComDetalhe(planilha, DestinoDaImportacao.PESQUISA,
                new AlcanceDeUmaImportacao(1, AlcanceDaImportacao.EDICAO, null, null));
        assertThat(extracao.linhas()).hasSize(3);
        assertThat(extracao.linhas()).extracting(linha -> linha.dados().get("tipo"))
                .containsExactly("PERFIL", "RESULTADO", "TEXTO");
        assertThat(String.valueOf(extracao.linhas().get(0).dados().get("valor"))).isEqualTo("58");
        assertThat(extracao.linhas().get(1).dados().get("unidade")).isEqualTo("PERCENTUAL");
        assertThat(extracao.linhas().get(2).dados().get("alternativa")).isEqualTo("Gostei da ativação");
    }

    @Test
    void oCabecalhoDeVeiculacaoNaoSugerePesquisa() {
        ConteudoLido planilha = conteudo(List.of(
                List.of("patrocinador", "veiculo", "formato", "mes", "quantidade"),
                List.of("Cliente Amarelo", "RBS TV", "Comercial 30\"", "2026-11", "3")));
        assertThat(new MapeadorTabular(null, null).sugerirDestino(planilha)).isNull();
    }
}
