package br.com.rbs.portal.importacao.destinos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.importacao.AlcanceDaImportacao;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.Parceria;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.CasadorDoPlano;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O motivo do comprovante sem veiculação: o veículo como veio e a data em dd/MM/aaaa, com o que fazer. */
class ParaComprovanteTest {

    private static final long EVENTO = 7L;
    private static final long PARCERIA = 5L;

    private static ParaComprovante destino() {
        ImportacaoRepositorio repositorio = mock(ImportacaoRepositorio.class);
        Parceria parceria = new Parceria(PARCERIA, EVENTO, "Cliente Amarelo", null, null, null, "ATIVA");
        when(repositorio.parceriaDaMarca(EVENTO, "Cliente Amarelo")).thenReturn(Optional.of(parceria));
        when(repositorio.veiculacoesDaParceriaNaData(eq(PARCERIA), any(LocalDate.class))).thenReturn(List.of());
        CasadorDoPlano casador = mock(CasadorDoPlano.class);
        when(casador.casar(anyLong(), any(), any(), any())).thenReturn(new CasadorDoPlano.Resultado(null, null, null));
        return new ParaComprovante(repositorio, casador, mock(Armazenamento.class));
    }

    private static Map<String, Object> dados(Object... pares) {
        Map<String, Object> dados = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            dados.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return dados;
    }

    @Test
    void oMotivoDizOVeiculoADataEOQueFazer() {
        LinhaProposta linha = new LinhaProposta(DestinoDaImportacao.COMPROVANTE,
                dados("patrocinador", "Cliente Amarelo", "veiculo", "ZERO HORA", "formato", "Página dupla assinada",
                        "data", "2026-10-18"), Map.of());

        AlcanceDeUmaImportacao alcance = new AlcanceDeUmaImportacao(EVENTO, AlcanceDaImportacao.EDICAO, null, null);

        assertThat(destino().problemas(linha, alcance))
                .contains("Nenhuma veiculação de ZERO HORA desta marca em 18/10/2026: registre a veiculação antes de anexar o comprovante.");
    }
}
