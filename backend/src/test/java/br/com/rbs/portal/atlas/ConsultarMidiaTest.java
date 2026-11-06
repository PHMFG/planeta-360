package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [ISOLAMENTO] A ferramenta {@code consultar_midia} na conversa da marca (plano da base da RBS, AC1): a parceria vem do
 * contexto, a aba Mídia precisa estar aberta, o recorte por mês é só das cotas Master, cada linha e cada total viram uma fonte
 * {@code [R#]} e a veiculação importada leva o arquivo e o localizador de origem. Chamada direta pelo registro (as duas fases do
 * loop); cada teste é desfeito no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
@Transactional
class ConsultarMidiaTest extends ApoioDasConsultas {

    private static final String MIDIA = "consultar_midia";

    @Test
    void semMesCadaLinhaDoPlanoVemUmaVezComOsTotaisPorUnidade() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        long amarelo = parceria("Cliente Amarelo");
        FontesDoTurno turno = turno();

        Map<String, Object> resultado = resultado(contexto, turno, MIDIA, "{}");

        List<Map<String, Object>> registros = registros(resultado);
        long linhasDoPlano = jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'")
                .param("p", amarelo).query(Long.class).single();
        assertThat(registros).hasSize((int) linhasDoPlano);
        assertThat(registros).allSatisfy(registro -> {
            assertThat(registro).containsEntry("mes", null);   // a cota Master tem o campo; sem recorte, ele é nulo
            assertThat((String) registro.get("marcador")).startsWith("R");
            assertThat(registro).containsKeys("veiculo", "formato", "previsto", "exibido", "unidade", "unidadeNome");
            var fonte = turno.fonte((String) registro.get("marcador")).orElseThrow();
            assertThat(fonte.tipo()).isEqualTo(FontesDoTurno.Tipo.REGISTRO);
            assertThat(fonte.registroEntidade()).isEqualTo("aproveitamento");
            assertThat(fonte.texto()).contains("Previsto:", "Exibido:");
        });
        assertThat(turno.numerosLivres()).contains(String.valueOf(linhasDoPlano));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totais = (List<Map<String, Object>>) resultado.get("totais");
        assertThat(totais).isNotEmpty();
        assertThat(totais).extracting(t -> t.get("unidade")).doesNotHaveDuplicates().contains("INSERCAO");
        assertThat(totais).allSatisfy(total -> {
            assertThat((String) total.get("marcador")).startsWith("R");
            assertThat(turno.fonte((String) total.get("marcador")).orElseThrow().registroEntidade()).isEqualTo("veiculacao");
        });
        // os totais são a soma do recorte, por unidade, e nunca misturam inserção com CPM
        Map<String, BigDecimal> exibidoPorUnidade = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT unidade, sum(exibido) AS exibido FROM (
                    SELECT ap.unidade, coalesce(sum(v.exibido), 0) AS exibido
                    FROM aproveitamento_previsto ap LEFT JOIN vw_midia_detalhe v ON v.aproveitamento_id = ap.id
                    WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA' GROUP BY ap.id, ap.unidade) x GROUP BY unidade
                """).param("p", amarelo).query().listOfRows()
                .forEach(linha -> exibidoPorUnidade.put((String) linha.get("unidade"), (BigDecimal) linha.get("exibido")));
        for (Map<String, Object> total : totais) {
            assertThat(valor(total.get("exibido"))).isEqualByComparingTo(exibidoPorUnidade.get(total.get("unidade")));
        }
    }

    @Test
    void comMesSoAsLinhasDaqueleMesESoNaCotaMaster() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        long amarelo = parceria("Cliente Amarelo");
        String mes = jdbc.sql("SELECT to_char(max(mes), 'YYYY-MM') FROM vw_midia_detalhe WHERE parceria_id = :p AND mes IS NOT NULL")
                .param("p", amarelo).query(String.class).single();

        Map<String, Object> resultado = resultado(contexto, turno(), MIDIA, "{\"mes\":\"" + mes + "\"}");

        assertThat(registros(resultado)).isNotEmpty().allSatisfy(registro -> assertThat(registro).containsEntry("mes", mes));
        // a linha do plano sem exibição no mês continua, com o contratado e zero exibido
        assertThat(registros(resultado)).anySatisfy(registro -> assertThat(valor(registro.get("exibido")))
                .isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void oRecortePorMesNaoValeParaAsOutrasCotasEAsLinhasNuncaTrazemMes() {
        AtlasContexto roxo = contexto(ROXO, "Cliente Roxo");

        assertThatThrownBy(() -> chamar(roxo, turno(), MIDIA, "{\"mes\":\"2026-07\"}")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos())
                        .containsEntry("mes", "O recorte por mês é das cotas Master."));

        Map<String, Object> resultado = resultado(roxo, turno(), MIDIA, "{}");
        assertThat(registros(resultado)).isNotEmpty().allSatisfy(registro -> assertThat(registro).doesNotContainKey("mes"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'
                """).param("p", parceria("Cliente Roxo")).query(Long.class).single()).isEqualTo((long) registros(resultado).size());
    }

    @Test
    void comAbaMidiaFechadaNaoHaNumeroNenhum() {
        long amarelo = parceria("Cliente Amarelo");
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        fecharAba(amarelo, "MIDIA");

        Map<String, Object> resultado = resultado(contexto, turno(), MIDIA, "{}");

        assertThat(registros(resultado)).isEmpty();
        assertThat((List<?>) resultado.get("totais")).as(AtlasJson.escrever(resultado)).isEmpty();
        assertThat((String) resultado.get("observacao")).as(AtlasJson.escrever(resultado)).contains("fechada");
        assertThat(AtlasJson.escrever(resultado)).doesNotContain("Chamadas assinadas", "Previsto");
    }

    @Test
    void aParceriaNuncaVemDeArgumento() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");

        for (String[] caso : new String[][]{{"{\"parceria\":" + roxo + "}", "parceria"},
                {"{\"parceriaId\":" + roxo + "}", "parceriaId"}, {"{\"parceria_id\":" + roxo + "}", "parceria_id"},
                {"{\"cota\":\"MASTER\"}", "cota"}}) {
            assertThatThrownBy(() -> chamar(amarelo, turno(), MIDIA, caso[0])).as(caso[0]).isInstanceOf(DadosInvalidosException.class)
                    .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos()).containsKey(caso[1]));
        }
    }

    @Test
    void doisRecortesDoMesmoTurnoGanhamMarcadoresProprios() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        FontesDoTurno turno = turno();

        List<Map<String, Object>> primeiro = registros(resultado(contexto, turno, MIDIA, "{\"veiculo\":\"RBS TV\"}"));
        List<Map<String, Object>> segundo = registros(resultado(contexto, turno, MIDIA, "{\"veiculo\":\"Rádio 92\"}"));

        assertThat(primeiro).isNotEmpty();
        assertThat(segundo).isNotEmpty();
        assertThat(primeiro).extracting(r -> r.get("marcador")).doesNotContainAnyElementsOf(
                segundo.stream().map(r -> r.get("marcador")).toList());
        assertThat(turno.fonte((String) segundo.get(0).get("marcador")).orElseThrow().texto()).contains("Rádio 92");
    }

    @Test
    void aVeiculacaoImportadaLevaOArquivoEOLocalizadorNaFonte() {
        long amarelo = parceria("Cliente Amarelo");
        long usuario = ApoioDoAtlas.usuario(jdbc, ApoioDoAtlas.RBS).id();
        long aproveitamento = jdbc.sql("""
                INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, tipo_midia, quantidade_prevista, unidade)
                VALUES (:p, 'MIDIA', 'Veículo Importado', 'Formato Importado', 'ASSINADA', 10, 'INSERCAO') RETURNING id
                """).param("p", amarelo).query(Long.class).single();
        long arquivo = jdbc.sql("""
                INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
                VALUES ('IMPORTACAO', 'tv-novembro.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                        3296, 'teste/tv-novembro.xlsx', :u, now()) RETURNING id
                """).param("u", usuario).query(Long.class).single();
        long importacao = jdbc.sql("""
                INSERT INTO importacao (evento_id, alcance, parceria_id, origem, arquivo_id, criada_por, situacao)
                VALUES ((SELECT evento_id FROM parceria WHERE id = :p), 'PARCERIA', :p, 'RBS', :a, :u, 'LENDO') RETURNING id
                """).param("p", amarelo).param("a", arquivo).param("u", usuario).query(Long.class).single();
        long linhaDaImportacao = jdbc.sql("""
                INSERT INTO importacao_linha (importacao_id, ordem, destino, dados, origem, situacao, parceria_id, aproveitamento_id)
                VALUES (:i, 7, 'VEICULACAO', '{}'::jsonb,
                        CAST('{"quantidade":{"localizador":"Entregas Nov-26!F7","trecho":"4"}}' AS jsonb),
                        'ACEITA', :p, :ap) RETURNING id
                """).param("i", importacao).param("p", amarelo).param("ap", aproveitamento).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, impacto, importacao_linha_id)
                VALUES (:ap, now() - interval '10 days', 4, 1000, :l)
                """).param("ap", aproveitamento).param("l", linhaDaImportacao).update();

        FontesDoTurno turno = turno();
        Map<String, Object> resultado = resultado(contexto(AMARELO, "Cliente Amarelo"), turno, MIDIA,
                "{\"veiculo\":\"Veículo Importado\"}");

        Map<String, Object> registro = registros(resultado).get(0);
        assertThat(registro).containsEntry("veiculo", "Veículo Importado");
        assertThat(valor(registro.get("exibido"))).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(registro.get("importacao")).isEqualTo(Map.of("arquivo", "tv-novembro.xlsx", "localizador", "Entregas Nov-26!F7"));
        var fonte = turno.fonte((String) registro.get("marcador")).orElseThrow();
        assertThat(fonte.dados()).containsEntry("arquivo", "tv-novembro.xlsx").containsEntry("localizador", "Entregas Nov-26!F7");
        assertThat(fonte.texto()).contains("tv-novembro.xlsx", "Entregas Nov-26!F7");
    }

    private static BigDecimal valor(Object numero) {
        return new BigDecimal(String.valueOf(numero));
    }
}
