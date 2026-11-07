package br.com.rbs.portal.importacao.destinos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.rbs.portal.importacao.AlcanceDaImportacao;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.LinhaProposta;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** As mensagens do destino VEICULACAO que a prévia mostra antes da carga. */
class ParaVeiculacaoTest {

    private static final AlcanceDeUmaImportacao ALCANCE = new AlcanceDeUmaImportacao(7L, AlcanceDaImportacao.EDICAO, null, null);

    private final ParaVeiculacao destino = new ParaVeiculacao(mock(ImportacaoRepositorio.class));

    private static Map<String, Object> dados(Object... pares) {
        Map<String, Object> dados = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            dados.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return dados;
    }

    @Test
    void aLinhaSemMesESemDataDaExibicaoEhRecusadaComAFraseQueDizOQueFalta() {
        LinhaProposta linha = new LinhaProposta(DestinoDaImportacao.VEICULACAO,
                dados("patrocinador", "Cliente Amarelo", "veiculo", "Rádio 92", "formato", "Chamada assinada", "quantidade", 40),
                Map.of());

        assertThat(destino.problemas(linha, ALCANCE)).contains("Falta a data ou o mês da entrega.");
    }
}
