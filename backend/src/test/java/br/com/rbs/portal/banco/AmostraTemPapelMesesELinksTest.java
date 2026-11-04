package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** [F1b] O que a amostra traz para as telas novas: papel das entregas, exibições em vários meses, links de posts e datas da edição. */
@TesteDeIntegracao
class AmostraTemPapelMesesELinksTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void asEntregasDeIngressosELogoTemPapelENaoHaEntregaDeMiv() {
        assertThat(jdbc.sql("SELECT count(*) FROM entrega WHERE papel = 'DOCUMENTO_INGRESSOS'").query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM entrega e JOIN aba a ON a.id = e.aba_id WHERE e.papel = 'DOCUMENTO_INGRESSOS' AND a.codigo <> 'PERFIL'")
                .query(Long.class).single()).isZero();
        List<String> logos = jdbc.sql("""
                SELECT p.nome_canonico FROM entrega e JOIN parceria pa ON pa.id = e.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN aba a ON a.id = e.aba_id WHERE e.papel = 'LOGO' AND a.codigo = 'APLICACOES' ORDER BY 1
                """).query(String.class).list();
        assertThat(logos).containsExactly("Cliente Amarelo", "Cliente Roxo");
        assertThat(jdbc.sql("SELECT count(*) FROM entrega WHERE papel = 'MIV'").query(Long.class).single()).isZero();
    }

    @Test
    void asExibicoesDoAmareloEstaoEmPeloMenosTresMeses() {
        long meses = jdbc.sql("""
                SELECT count(DISTINCT date_trunc('month', v.exibido_em AT TIME ZONE 'America/Sao_Paulo'))
                FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
                WHERE p.nome_canonico = 'Cliente Amarelo'
                """).query(Long.class).single();
        assertThat(meses).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void algumasExibicoesDasRedesTraemOLinkDoPostComHttps() {
        List<String> links = jdbc.sql("SELECT link FROM veiculacao WHERE link IS NOT NULL").query(String.class).list();
        assertThat(links).isNotEmpty().allMatch(l -> l.startsWith("https://www.instagram.com/p/AMOSTRA") || l.startsWith("https://www.tiktok.com/@planetaatlantida/video/"));
        assertThat(links).anyMatch(l -> l.contains("instagram")).anyMatch(l -> l.contains("tiktok"));
    }

    @Test
    void aEdicaoDe2027TemOpenStaffPeriodoEPrazoDasCortesias() {
        Map<String, Object> e = jdbc.sql("""
                SELECT credenciamento_url, credenciamento_inicio, credenciamento_fim, cortesias_prazo
                FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027
                """).query().singleRow();
        assertThat(e.get("credenciamento_url")).isEqualTo("https://example.com/");
        assertThat(((java.sql.Date) e.get("credenciamento_inicio")).toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(((java.sql.Date) e.get("credenciamento_fim")).toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(((java.sql.Date) e.get("cortesias_prazo")).toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    }
}
