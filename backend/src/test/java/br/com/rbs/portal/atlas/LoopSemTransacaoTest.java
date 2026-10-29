package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] O que só aparece SEM uma transação envolvendo o teste: nenhuma transação aberta durante a ida ao modelo (cada gravação
 * e cada ferramenta confirmam sozinhas) e o turno visível a quem chega no meio da conversa (o 409). Por isso esta classe NÃO é
 * {@code @Transactional}: tudo o que ela faz é confirmado no banco de verdade, e o que ficou é apagado no fim, tabela por tabela
 * (o histórico, os dados informados e o relatório pronto são imutáveis por gatilho; o gatilho é desligado só para esta limpeza,
 * dentro de um try/finally que o liga de novo). Os testes de comportamento do loop estão em LoopDoAgenteTest.
 */
@TesteComLlmRoteirizado
class LoopSemTransacaoTest {

    /** Da que depende das outras para a que não depende de ninguém, e os gatilhos de imutabilidade de cada uma. */
    private static final Map<String, String> TABELAS_E_GATILHOS = new LinkedHashMap<>();

    static {
        TABELAS_E_GATILHOS.put("atlas_tool_call", null);
        TABELAS_E_GATILHOS.put("atlas_calculo", null);
        TABELAS_E_GATILHOS.put("atlas_solicitacao", null);
        TABELAS_E_GATILHOS.put("atlas_relatorio", "rn_atlas_relatorio_congelado");
        TABELAS_E_GATILHOS.put("atlas_mensagem", null);
        TABELAS_E_GATILHOS.put("atlas_conversa", null);
        TABELAS_E_GATILHOS.put("dado_informado", "rn_dado_informado_imutavel");
        TABELAS_E_GATILHOS.put("historico", "rn_historico_imutavel");
    }

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private final Map<String, Long> ultimoIdAntes = new LinkedHashMap<>();

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        TABELAS_E_GATILHOS.keySet().forEach(tabela -> ultimoIdAntes.put(tabela,
                jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela).query(Long.class).single()));
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        TABELAS_E_GATILHOS.forEach((tabela, gatilho) -> {
            if (gatilho != null) {
                jdbc.sql("ALTER TABLE " + tabela + " DISABLE TRIGGER " + gatilho).update();
            }
            try {
                jdbc.sql("DELETE FROM " + tabela + " WHERE id > :ultimo").param("ultimo", ultimoIdAntes.get(tabela)).update();
            } finally {
                if (gatilho != null) {
                    jdbc.sql("ALTER TABLE " + tabela + " ENABLE TRIGGER " + gatilho).update();
                }
            }
        });
        TABELAS_E_GATILHOS.keySet().forEach(tabela -> assertThat(jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela)
                .query(Long.class).single()).as("a limpeza deixou sobra em " + tabela).isEqualTo(ultimoIdAntes.get(tabela)));
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    @Test
    void nenhumaTransacaoFicaAbertaDuranteAIdaAoModeloEOTurnoApareceParaQuemChegaNoMeio() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        String edicao = AtlasJson.escrever(Map.of("operacoes", List.of(
                Map.of("op", "definir_texto", "bloco", "b-resumo", "texto", LoopDoAgenteTest.RESUMO),
                Map.of("op", "definir_texto", "bloco", "b-publico", "texto", LoopDoAgenteTest.PUBLICO))));

        modelo.chamada(LoopDoAgenteTest.FINALIZAR, "{}")   // erro de ferramenta no meio: o que ela fez desfaz, a resposta é gravada
                .chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                // no meio da ida ao modelo, outra mensagem na mesma conversa encontra o turno em andamento (confirmado de verdade)
                .passo(pedido -> {
                    assertThatThrownBy(() -> atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Oi?", null, null), requisicao()))
                            .isInstanceOf(ConflitoException.class)
                            .hasMessage("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");
                    return new RespostaDoLlm(null, List.of(ProvedorRoteirizado.pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)), null);
                })
                .chamada(LoopDoAgenteTest.EDITAR, edicao)
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}");

        ConversaDto aberta = atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Monte o relatório.", null, null), requisicao());
        ItemCartao cartao = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        // o cartão aberto já está confirmado no banco, visto por outra conexão, e o turno terminou
        assertThat(jdbc.sql("SELECT status FROM atlas_solicitacao WHERE id = :id").param("id", cartao.solicitacaoId())
                .query(String.class).single()).isEqualTo("PENDENTE");
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", conversa)
                .query(Boolean.class).single()).isTrue();

        ConversaDto pronta = atlas.responderCartao(pessoa, cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("accept",
                valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());

        assertThat(pronta.itens()).extracting(i -> i.getClass().getSimpleName()).containsExactly("ItemRelatorioPronto");
        assertThat(modelo.pedidos()).hasSize(5);
        assertThat(modelo.transacaoAtivaEmCadaPedido()).as("transação aberta em cada ida ao modelo").containsOnly(false);
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("PRONTO");
        // a primeira ferramenta falhou (não havia relatório): a falha foi gravada como resposta, sem deixar nada pela metade
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c "
                + "AND t.erro IS NOT NULL").param("c", conversa).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .isEqualTo(1);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }
}
