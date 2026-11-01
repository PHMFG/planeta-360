package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.etapas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.resultados;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDoTurno;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] O limite de mensagens por hora no traço do diagnóstico (M8): a mensagem recusada deixa {@code G1_LIMITES} e {@code SAIDA}
 * como {@code RECUSADO}, com o motivo {@code LIMITE_POR_HORA} e quanto falta, sem chamar o modelo. Um contexto próprio, com o limite
 * em duas mensagens por hora e o diagnóstico ligado; sem {@code @Transactional}, porque o traço é gravado no fim do turno.
 */
@TesteComLlmRoteirizado
@Import(RelogioDeTesteConfig.class)
@TestPropertySource(properties = {"atlas.limites.mensagens-por-hora=2", "atlas.diagnostico.ligado=true"})
class LimitesPorUsuarioNoTracoTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        relogio.avancar(Duration.ofDays(3));
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    @Test
    void aMensagemRecusadaPeloLimiteDeixaOTracoDaRecusaEPoupaOModelo() {
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long gasta = atlas.abrirConversa(marca, amarelo, null, requisicao()).conversaId();
        long conversa = atlas.abrirConversa(marca, amarelo, null, requisicao()).conversaId();   // o limite é do usuário, não da conversa
        for (int i = 0; i < 2; i++) {
            modelo.texto("Posso ajudar.");
            atlas.enviarMensagem(marca, gasta, new MensagemRequisicao("Oi", null, null), requisicao());
        }

        assertThatThrownBy(() -> atlas.enviarMensagem(marca, conversa, new MensagemRequisicao("Oi", null, null), requisicao()))
                .isInstanceOf(LimiteDeUsoException.class)
                .hasMessage("Você atingiu o limite de mensagens por hora. Tente de novo em 30 minutos.");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "SAIDA");
        assertThat(resultados(traco)).containsExactly("RECUSADO", "RECUSADO");
        assertThat(primeira(traco, "G1_LIMITES").dados()).containsEntry("motivo", "LIMITE_POR_HORA").containsEntry("esperaSegundos", 1800);
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "RECUSADO_ANTES_DO_TURNO").containsEntry("motivo", "LIMITE_POR_HORA");
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();
    }
}
