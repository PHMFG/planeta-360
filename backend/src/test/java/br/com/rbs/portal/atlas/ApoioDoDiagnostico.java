package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O que os testes do diagnóstico têm em comum: ler o traço gravado ({@code atlas_traco}) de uma conversa, agrupar por turno,
 * conferir que as etapas se encaixam no turno e juntar todo o JSON gravado num texto só (para procurar o que nunca pode estar lá).
 * Não faz parte da API.
 */
final class ApoioDoDiagnostico {

    private ApoioDoDiagnostico() {
    }

    /** Uma linha do traço, com {@code dados} já lido. */
    record Linha(long id, UUID turno, Long mensagemId, int ordem, String etapa, String resultado, OffsetDateTime iniciadaEm,
                 int duracaoMs, Map<String, Object> dados) {

        OffsetDateTime terminouEm() {
            return iniciadaEm.plus(Duration.ofMillis(duracaoMs));
        }

        /** Fora da soma do caminho crítico: o resumo, que corre em paralelo, e a busca, que está dentro da ferramenta. */
        boolean emParalelo() {
            return "RESUMO".equals(etapa) || "BUSCA".equals(etapa);
        }
    }

    /** O traço de toda a conversa, na ordem em que as etapas começaram (e, dentro de um turno, na ordem gravada). */
    static List<Linha> tracoDaConversa(JdbcClient jdbc, long conversa) {
        return jdbc.sql("""
                        SELECT id, turno, mensagem_id, ordem, etapa, resultado, iniciada_em, duracao_ms, CAST(dados AS text) AS dados
                        FROM atlas_traco WHERE conversa_id = :c ORDER BY iniciada_em, ordem
                        """)
                .param("c", conversa)
                .query((rs, n) -> new Linha(rs.getLong("id"), rs.getObject("turno", UUID.class), (Long) rs.getObject("mensagem_id"),
                        rs.getInt("ordem"), rs.getString("etapa"), rs.getString("resultado"),
                        rs.getObject("iniciada_em", OffsetDateTime.class), rs.getInt("duracao_ms"),
                        AtlasJson.ler(rs.getString("dados"), new TypeReference<LinkedHashMap<String, Object>>() {
                        })))
                .list();
    }

    /** O traço de uma conversa que teve um turno só: confere que todas as linhas são do mesmo turno e as devolve na ordem gravada. */
    static List<Linha> tracoDoTurno(JdbcClient jdbc, long conversa) {
        List<Linha> linhas = new ArrayList<>(tracoDaConversa(jdbc, conversa));
        assertThat(linhas.stream().map(Linha::turno).distinct()).as("a conversa teve um turno só").hasSize(1);
        linhas.sort(Comparator.comparingInt(Linha::ordem));
        return linhas;
    }

    static List<String> etapas(List<Linha> linhas) {
        return linhas.stream().map(Linha::etapa).toList();
    }

    static List<String> resultados(List<Linha> linhas) {
        return linhas.stream().map(Linha::resultado).toList();
    }

    static Linha primeira(List<Linha> linhas, String etapa) {
        return linhas.stream().filter(l -> l.etapa().equals(etapa)).findFirst().orElseThrow(() -> new AssertionError("sem a etapa " + etapa));
    }

    static List<Linha> todas(List<Linha> linhas, String etapa) {
        return linhas.stream().filter(l -> l.etapa().equals(etapa)).toList();
    }

    /**
     * As conferências do plano (seção 8.4) sobre um turno: as posições vão de 1 em diante sem buraco; toda etapa sequencial
     * (todas, menos o resumo do raciocínio) começa depois de a anterior terminar; e a soma das durações delas não passa do tempo
     * do turno (do começo da primeira ao fim da última). 1 ms de folga: as durações são cortadas em milissegundos.
     */
    static void exigirCoerencia(List<Linha> turno) {
        assertThat(turno).isNotEmpty();
        List<Linha> ordenadas = new ArrayList<>(turno);
        ordenadas.sort(Comparator.comparingInt(Linha::ordem));
        for (int i = 0; i < ordenadas.size(); i++) {
            assertThat(ordenadas.get(i).ordem()).as("posição da etapa " + ordenadas.get(i).etapa()).isEqualTo(i + 1);
        }
        List<Linha> sequenciais = ordenadas.stream().filter(l -> !l.emParalelo()).sorted(Comparator.comparing(Linha::iniciadaEm)).toList();
        assertThat(sequenciais).isNotEmpty();
        long soma = 0;
        for (int i = 0; i < sequenciais.size(); i++) {
            Linha atual = sequenciais.get(i);
            soma += atual.duracaoMs();
            if (i > 0) {
                Linha anterior = sequenciais.get(i - 1);
                assertThat(Duration.between(anterior.terminouEm(), atual.iniciadaEm()).toMillis())
                        .as("a etapa " + atual.etapa() + " começa depois de a " + anterior.etapa() + " terminar").isGreaterThanOrEqualTo(-1);
            }
        }
        long duracaoDoTurno = Duration.between(sequenciais.get(0).iniciadaEm(), sequenciais.get(sequenciais.size() - 1).terminouEm()).toMillis();
        assertThat(soma).as("soma das etapas sequenciais não passa do tempo do turno").isLessThanOrEqualTo(duracaoDoTurno + 1);
    }

    /** Todo o JSON de {@code dados} do traço da conversa, num texto só: é onde o raciocínio cru, a chave e o prompt nunca podem aparecer. */
    static String tudoOQueOTracoGuarda(JdbcClient jdbc, long conversa) {
        return String.join("\n", jdbc.sql("SELECT CAST(dados AS text) FROM atlas_traco WHERE conversa_id = :c ORDER BY id")
                .param("c", conversa).query(String.class).list());
    }
}
