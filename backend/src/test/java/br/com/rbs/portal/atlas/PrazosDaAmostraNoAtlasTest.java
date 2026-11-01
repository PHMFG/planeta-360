package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.PrazosDaAmostra;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os prazos da amostra do D02 (M5) pela ferramenta: cada um chega ao modelo com o documento, a versão e a página de onde saiu,
 * e some quando o D02 sai do ar. Cada teste é desfeito no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
@Transactional
class PrazosDaAmostraNoAtlasTest extends ApoioDasConsultas {

    private static final String PRAZOS = "consultar_prazos";
    private static final String MANUAL = "Manual de Operações 2027";

    @Autowired
    PrazosDaAmostra amostra;

    private void criarOsPrazosDaAmostra() {
        UsuarioAutenticado rbs = jdbc.sql("SELECT id, nome, email FROM usuario WHERE perfil = 'ADMIN_RBS' ORDER BY id LIMIT 1")
                .query((rs, n) -> new UsuarioAutenticado(rs.getLong("id"), rs.getString("nome"), rs.getString("email"), Perfil.ADMIN_RBS)).single();
        assertThat(amostra.criar(rbs)).isEqualTo(8);
    }

    @Test
    void osPrazosDoManualChegamComODocumentoAVersaoEAPagina() {
        criarOsPrazosDaAmostra();
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        FontesDoTurno turno = turno(LocalDate.of(2026, 12, 1));

        Map<String, Object> resultado = resultado(contexto, turno, PRAZOS, "{}");

        List<Map<String, Object>> registros = registros(resultado);
        assertThat(registros).extracting(r -> r.get("titulo")).containsExactly("Informar os geradores próprios à produção",
                "Entregar o laudo técnico de estruturas acima de 3 metros");
        Map<String, Object> geradores = registros.get(0);
        assertThat(geradores).containsEntry("data", "04/12/2026").containsEntry("hora", "18:00").containsEntry("dias_restantes", 3);
        assertThat((String) geradores.get("descricao")).contains("geradores próprios devem informá-los à produção até 04/12/2026");
        @SuppressWarnings("unchecked")
        Map<String, Object> origem = (Map<String, Object>) geradores.get("origem");
        assertThat(origem).containsEntry("documento", MANUAL).containsEntry("versao", 2).containsEntry("pagina", 5);
        assertThat(turno.fonte((String) geradores.get("marcador")).orElseThrow().dados())
                .containsEntry("documentoId", documento(MANUAL, 2)).containsEntry("versao", 2).containsEntry("pagina", 5);
        // a Roxo e a Verde veem os mesmos: o manual é da edição inteira
        assertThat(titulos(contexto(ROXO, "Cliente Roxo"), turno(LocalDate.of(2026, 12, 1)))).hasSize(2);
        assertThat(titulos(contexto(VERDE, "Cliente Verde"), turno(LocalDate.of(2026, 12, 1)))).hasSize(2);
    }

    @Test
    void comOManualRevogadoNenhumPrazoDeleChegaAoModelo() {
        criarOsPrazosDaAmostra();
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");
        assertThat(titulos(contexto, turno(LocalDate.of(2026, 12, 1)))).isNotEmpty();

        mudarStatusDoDocumento(documento(MANUAL, 2), "REVOGADO");

        assertThat(titulos(contexto, turno(LocalDate.of(2026, 12, 1)))).isEmpty();
        assertThat(titulos(contexto, turno(LocalDate.of(2026, 11, 1)), "{\"proximos_dias\":180,\"incluir_vencidos\":true}")).isEmpty();
    }

    @Test
    void naJanelaDeSeisMesesVemOsOitoNaOrdemDasDatas() {
        criarOsPrazosDaAmostra();

        List<Map<String, Object>> registros = registros(resultado(contexto(AMARELO, "Cliente Amarelo"), turno(LocalDate.of(2026, 11, 1)), PRAZOS,
                "{\"proximos_dias\":180}"));

        assertThat(registros).extracting(r -> r.get("data")).containsExactly("04/12/2026", "29/12/2026", "08/01/2027", "12/01/2027",
                "15/01/2027", "18/01/2027", "22/01/2027", "27/01/2027");
        assertThat(registros).extracting(r -> r.get("hora")).containsExactly("18:00", "18:00", "18:00", "18:00", "18:00", null, "18:00", "18:00");
    }

    private List<String> titulos(AtlasContexto contexto, FontesDoTurno turno) {
        return titulos(contexto, turno, "{}");
    }

    private List<String> titulos(AtlasContexto contexto, FontesDoTurno turno, String argumentos) {
        return registros(resultado(contexto, turno, PRAZOS, argumentos)).stream().map(r -> (String) r.get("titulo")).toList();
    }
}
