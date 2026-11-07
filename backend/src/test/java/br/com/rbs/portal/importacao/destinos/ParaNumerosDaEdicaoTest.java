package br.com.rbs.portal.importacao.destinos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** As regras da prévia do destino NUMEROS_EDICAO, as mesmas que o PosVendaService confere na confirmação. */
class ParaNumerosDaEdicaoTest {

    private final ParaNumerosDaEdicao destino = new ParaNumerosDaEdicao(null, mock(PlatformTransactionManager.class));

    private static LinhaProposta linha(Object valor, String qualificador, String unidade) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("indicador", "Público total");
        dados.put("valor", valor);
        dados.put("qualificador", qualificador);
        dados.put("unidade", unidade);
        dados.put("fonte", "Relatório da RBS");
        return new LinhaProposta(DestinoDaImportacao.NUMEROS_EDICAO, dados, Map.of());
    }

    @Test
    void oValorComMaisDeDuasCasasVemRecusadoNaPrevia() {
        assertThat(destino.problemas(linha(1.2345, "EXATO", "pessoas"), null))
                .containsExactly("O valor tem no máximo 2 casas decimais.");
    }

    @Test
    void valorNegativoOuZeroEQualificadorDesconhecidoVemRecusados() {
        assertThat(destino.problemas(linha(-5, "EXATO", "pessoas"), null))
                .containsExactly("O valor do número precisa ser maior que zero.");
        assertThat(destino.problemas(linha(0, "EXATO", "pessoas"), null))
                .containsExactly("O valor do número precisa ser maior que zero.");
        assertThat(destino.problemas(linha(10, "talvez", "pessoas"), null))
                .containsExactly("Use EXATO ou MAIS_DE.");
    }

    @Test
    void oValorAcimaDoMaximoVemRecusado() {
        assertThat(destino.problemas(linha(100000000000000L, "MAIS_DE", "pessoas"), null))
                .containsExactly("O valor passa do máximo: use menos de 100.000.000.000.000.");
    }
}
