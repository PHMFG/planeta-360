package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

/**
 * Roda por JDBC o arquivo db/verificacao.sql (as 647 conferências do banco: contagens, órfãos, cargas, relatórios, a V7, a V8, a V9, a V10, a V11, a V12, a V13, a V14, a V15, a V16, a V17, a V18,
 * a V19, a V20, a V21, a V22, a V23 e uma tentativa de violar cada regra) e exige que todas passem. Atende o item 1 da Entrega 2 dentro do `./mvnw test`.
 * O arquivo é SQL puro, sem comandos do psql, então roda inteiro numa única conexão.
 */
@TesteDeIntegracao
class VerificacaoDoBancoTest {

    private static final int CONFERENCIAS_ESPERADAS = 647;
    // Começo do bloco que encerra o script com erro quando alguma conferência falhou.
    private static final String MARCA_DO_ERRO_FINAL = "DO $$\nBEGIN\n    IF EXISTS (SELECT 1 FROM _ver WHERE NOT ok)";

    @Autowired
    DataSource dados;

    /** O que o script devolve: as conferências que falharam e os totais da última consulta. */
    record Resultado(List<String> falhas, long conferencias, long ok) {
    }

    @Test
    void todasAsConferenciasDoBancoPassam() throws Exception {
        String sql = new ClassPathResource("db/verificacao.sql").getContentAsString(StandardCharsets.UTF_8);
        Resultado resultado;
        try {
            resultado = executar(sql);
        } catch (SQLException erro) {
            // O script termina com erro se alguma conferência falhar, e o erro desfaz a lista. Rodamos de novo sem o
            // bloco final que lança o erro, só para mostrar quais conferências falharam.
            String semOErroFinal = sql.contains(MARCA_DO_ERRO_FINAL)
                    ? sql.substring(0, sql.lastIndexOf(MARCA_DO_ERRO_FINAL)) : sql;
            throw new AssertionError("verificacao.sql terminou com erro: " + erro.getMessage()
                    + "\nConferências que falharam: " + executar(semOErroFinal).falhas(), erro);
        }

        assertThat(resultado.falhas()).as("conferências que falharam").isEmpty();
        assertThat(resultado.conferencias()).as("total de conferências").isEqualTo(CONFERENCIAS_ESPERADAS);
        assertThat(resultado.ok()).as("conferências ok").isEqualTo(CONFERENCIAS_ESPERADAS);
    }

    private Resultado executar(String sql) throws SQLException {
        List<String> falhas = new ArrayList<>();
        long conferencias = -1;
        long ok = -1;
        // Uma conexão só: o script cria uma tabela temporária e funções em pg_temp, que valem apenas nesta sessão.
        try (Connection conexao = dados.getConnection(); Statement comando = conexao.createStatement()) {
            boolean temResultado = comando.execute(sql);
            while (true) {
                if (temResultado) {
                    try (ResultSet resultado = comando.getResultSet()) {
                        String primeiraColuna = resultado.getMetaData().getColumnLabel(1);
                        while (resultado.next()) {
                            if ("grupo".equals(primeiraColuna) && "FALHOU".equals(resultado.getString("resultado"))) {
                                falhas.add(resultado.getString("grupo") + " | " + resultado.getString("conferencia")
                                        + " | esperado " + resultado.getString("esperado") + ", obtido " + resultado.getString("obtido"));
                            }
                            if ("conferencias".equals(primeiraColuna)) {
                                conferencias = resultado.getLong("conferencias");
                                ok = resultado.getLong("ok");
                            }
                        }
                    }
                } else if (comando.getUpdateCount() == -1) {
                    break;
                }
                temResultado = comando.getMoreResults();
            }
        }
        return new Resultado(falhas, conferencias, ok);
    }
}
