package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import br.com.rbs.portal.documentos.PrazosDaAmostra.PrazoLido;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * [ATLAS] Os prazos do Manual de Operações 2027 v2 na demonstração (M5): <b>nenhum prazo inventado</b>. A data, a página e a descrição
 * de cada um saem do gabarito do kit, e aqui se confere no texto que o PDFBox extrai do D02, na página que o prazo cita. Depois, o
 * cadastro de verdade: a RBS, o escopo da edição inteira, o documento e a página, a hora das 18h e o "de forma segura sobre um banco
 * que já os tem" (rodar de novo não duplica, e o prazo desativado não ressuscita).
 */
@TesteDeIntegracao
@Transactional
class PrazosDaAmostraTest {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    PrazosDaAmostra amostra;

    @Autowired
    JdbcClient jdbc;

    private static String normalizar(String texto) {
        return GabaritoDosDocumentosTest.normalizar(texto);
    }

    private static Extracao d02() {
        KitDeDocumentos.Documento documento = KitDeDocumentos.por("D02");
        return new ExtratorDeTexto().extrair(TipoDeArquivo.daExtensao(documento.arquivo()).orElseThrow(), documento.conteudo());
    }

    private UsuarioAutenticado rbs() {
        return jdbc.sql("SELECT id, nome, email FROM usuario WHERE perfil = 'ADMIN_RBS' AND ativo ORDER BY id LIMIT 1")
                .query((rs, n) -> new UsuarioAutenticado(rs.getLong("id"), rs.getString("nome"), rs.getString("email"), Perfil.ADMIN_RBS)).single();
    }

    // ---------------------------------------------------------------------
    // Nenhum prazo inventado: o gabarito e o texto do D02
    // ---------------------------------------------------------------------

    @Test
    void cadaPrazoTemADataNaPaginaQueCitaNoTextoQueOPdfboxExtraiDoD02() {
        Extracao extracao = d02();

        List<PrazoLido> lidos = PrazosDaAmostra.ler(PrazosDaAmostra.gabarito());

        assertThat(lidos).hasSize(8);
        for (PrazoLido p : lidos) {
            assertThat(p.pagina()).as(p.titulo()).isBetween(1, extracao.totalDePaginas());
            String pagina = normalizar(extracao.paginas().get(p.pagina() - 1).texto());
            assertThat(pagina).as(p.titulo() + ": a data na página " + p.pagina()).contains(DIA.format(p.data()));
            assertThat(pagina).as(p.titulo() + ": a descrição é o trecho do manual").contains(normalizar(p.descricao()));
            assertThat(p.titulo()).isNotBlank().hasSizeLessThanOrEqualTo(200);
            assertThat(p.descricao()).hasSizeLessThanOrEqualTo(1000);
        }
    }

    @Test
    void asPaginasEAsDatasSaoAsDoGabaritoDoD02() {
        Map<String, List<Object>> esperado = Map.of(
                "geradores-prazo", List.of(5, LocalDate.of(2026, 12, 4)),
                "laudo-tecnico", List.of(9, LocalDate.of(2026, 12, 29)),
                "seguro-prazo", List.of(8, LocalDate.of(2027, 1, 8)),
                "alvara-sanitario", List.of(9, LocalDate.of(2027, 1, 12)),
                "credenciamento-prazo", List.of(8, LocalDate.of(2027, 1, 15)),
                "credenciamento-troca", List.of(8, LocalDate.of(2027, 1, 22)));

        List<PrazoLido> lidos = PrazosDaAmostra.ler(PrazosDaAmostra.gabarito());

        esperado.forEach((fato, pagina) -> assertThat(lidos).filteredOn(p -> p.fato().equals(fato)).singleElement()
                .satisfies(p -> assertThat(List.<Object>of(p.pagina(), p.data())).as(fato).isEqualTo(pagina)));
        assertThat(lidos).filteredOn(p -> p.fato().equals("montagem-janela")).extracting(PrazoLido::data, PrazoLido::pagina, PrazoLido::hora)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LocalDate.of(2027, 1, 18), 3, null),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2027, 1, 27), 3, LocalTime.of(18, 0)));
    }

    @Test
    void aHoraDas18hVemDaRegraDoManualEOInicioDaMontagemNaoTemHora() {
        // "Todos os prazos terminam às 18h da data indicada, no horário de Brasília." (página 3 do D02)
        JsonNode regra = null;
        for (JsonNode fato : PrazosDaAmostra.gabarito()) {
            if ("prazos-18h".equals(fato.path("fato").asString()) && "D02".equals(fato.path("id").asString())) {
                regra = fato;
            }
        }
        assertThat(regra).isNotNull();
        assertThat(normalizar(d02().paginas().get(regra.path("pagina").asInt() - 1).texto())).contains(normalizar(regra.path("trecho").asString()))
                .contains("18h");
        List<PrazoLido> lidos = PrazosDaAmostra.ler(PrazosDaAmostra.gabarito());
        assertThat(lidos).filteredOn(p -> p.hora() != null).extracting(PrazoLido::hora).containsOnly(LocalTime.of(18, 0));
        assertThat(lidos).filteredOn(p -> p.hora() == null).extracting(PrazoLido::titulo).containsExactly("Iniciar a montagem das ativações");
    }

    @Test
    void osTitulosSaoOsDosMarcosDaTabelaDoManualOuAPartirDaFraseDoFato() {
        // a tabela 1 do manual (página 3) nomeia os marcos; o título de cada prazo tem as palavras do marco
        String tabela = normalizar(d02().paginas().get(2).texto());

        for (PrazoLido p : PrazosDaAmostra.ler(PrazosDaAmostra.gabarito())) {
            if (p.fato().equals("geradores-prazo") || p.fato().equals("seguro-prazo")) {
                continue;   // o título vem da frase do fato (página 5 e 8), e não da tabela: ela fala de "geradores e cargas" e "apólice de responsabilidade"
            }
            if (p.titulo().equals("Terminar a montagem das ativações")) {
                continue;   // a tabela só traz o início; o fim está no texto da seção 3.1
            }
            assertThat(tabela).as(p.titulo()).contains(p.titulo());
        }
    }

    // ---------------------------------------------------------------------
    // O cadastro
    // ---------------------------------------------------------------------

    @Test
    void criaOsOitoPrazosComoARbsNoEscopoDaEdicaoComODocumentoEAPagina() {
        long d02 = jdbc.sql("SELECT id FROM documento WHERE titulo = 'Manual de Operações 2027' AND versao = 2").query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM prazo WHERE documento_id = :d").param("d", d02).query(Long.class).single()).isZero();

        int criados = amostra.criar(rbs());

        assertThat(criados).isEqualTo(8);
        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT titulo, data, hora, escopo, documento_id, pagina, criado_por_perfil, ativo, descricao,
                       (SELECT edicao FROM evento WHERE id = prazo.evento_id) AS edicao
                FROM prazo WHERE documento_id = :d ORDER BY data, titulo
                """).param("d", d02).query().listOfRows();
        assertThat(linhas).hasSize(8).allSatisfy(l -> {
            assertThat(l).containsEntry("escopo", "GLOBAL").containsEntry("criado_por_perfil", "ADMIN_RBS").containsEntry("ativo", true)
                    .containsEntry("documento_id", d02);
            assertThat(((Number) l.get("edicao")).intValue()).isEqualTo(2027);
            assertThat((Integer) l.get("pagina")).isBetween(1, 11);
            assertThat((String) l.get("descricao")).isNotBlank();
        });
        assertThat(linhas.get(0)).containsEntry("titulo", "Informar os geradores próprios à produção").containsEntry("pagina", 5);
        assertThat(String.valueOf(linhas.get(0).get("data"))).isEqualTo("2026-12-04");
        assertThat(String.valueOf(linhas.get(0).get("hora"))).isEqualTo("18:00:00");
        assertThat(jdbc.sql("SELECT count(*) FROM historico WHERE entidade = 'prazo' AND acao = 'CRIACAO'").query(Long.class).single())
                .as("o cadastro grava o histórico, como pela rota 63").isGreaterThanOrEqualTo(8L);
    }

    @Test
    void rodarDeNovoNaoDuplicaENemRessuscitaOPrazoQueARbsDesativou() {
        amostra.criar(rbs());
        jdbc.sql("UPDATE prazo SET ativo = FALSE WHERE titulo = 'Informar os geradores próprios à produção'").update();

        int denovo = amostra.criar(rbs());

        assertThat(denovo).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM prazo WHERE titulo = 'Informar os geradores próprios à produção'").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT ativo FROM prazo WHERE titulo = 'Informar os geradores próprios à produção'").query(Boolean.class).single())
                .as("o prazo que a RBS desativou fica desativado").isFalse();
    }

    @Test
    void completaSoOQueFaltaNumBancoQueJaTemAlguns() {
        amostra.criar(rbs());
        jdbc.sql("DELETE FROM prazo WHERE titulo IN ('Entregar o laudo técnico de estruturas acima de 3 metros', 'Trocar nomes na lista de credenciados, com taxa')")
                .update();

        int completou = amostra.criar(rbs());

        assertThat(completou).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM prazo WHERE documento_id = (SELECT id FROM documento WHERE titulo = 'Manual de Operações 2027' AND versao = 2)")
                .query(Long.class).single()).isEqualTo(8L);
    }
}
