package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O prazo do turno: o turno vale por 5 minutos (a trava de {@code turno_desde}), e o loop não começa uma ida ao modelo
 * depois de {@code atlas.prazo-do-turno} (padrão 3 minutos), para o turno não vencer sob ele. Aqui o prazo é zero: o loop para
 * antes da primeira ida, grava a frase fixa e deixa o histórico válido e o turno liberado. Cada teste é desfeito no fim.
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = "atlas.prazo-do-turno=PT0S")
class PrazoDoTurnoTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    @Test
    void oPadraoDoPrazoETresMinutos() {
        assertThat(LoopDoAgente.PRAZO_DO_TURNO).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void turnoQueEstouraOPrazoTerminaComAFraseFixaSemChamarOModelo() {
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long conversa = atlas.abrirConversa(marca, SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao()).conversaId();
        modelo.texto("Isto não deve ser dito.");

        ConversaDto turno = atlas.enviarMensagem(marca, conversa, new MensagemRequisicao("Oi", null, null), requisicao());

        assertThat(turno.itens()).hasSize(2);
        assertThat(((ItemTexto) turno.itens().get(1)).texto()).isEqualTo(LoopDoAgente.FRASE_DO_PRAZO);
        assertThat(modelo.pedidos()).isEmpty();
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa).query(Boolean.class).single())
                .isTrue();
    }
}
