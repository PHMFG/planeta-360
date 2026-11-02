package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] A mensagem do cartão de perguntas passa pela mesma regra do parágrafo do relatório (M6): sem algarismo, sem número nem data
 * por extenso, sem endereço de internet, sem HTML e sem chave crua ou nome de ferramenta, e sem marcador {@code {{ref}}} (o cartão
 * mostra o texto como está, e o marcador apareceria cru). Antes só se conferia o tamanho, e um texto com número, link e chave
 * chegava inteiro à tela. Recusada, a ferramenta devolve o erro com o motivo e o modelo reescreve; a mensagem fixa do botão e as
 * frases comuns ("Faltam dois números") passam. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class MensagemDoCartaoTest {

    private static final List<String> CHAVES = List.of("ativacao.visitantes_total", "ativacao.cadastros_consentidos");
    private static final String MENSAGEM_DO_REVISOR =
            "O total foi 1.234 visitantes? Veja www.coleta.example e dado:ativacao.visitantes_total.";

    @Autowired
    RelatorioAtlasService relatorios;

    @Autowired
    AtlasContextoFabrica contextos;

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

    /** O contexto da conversa e o relatório que o modelo abriu nela (MONTANDO). */
    private record Aberto(AtlasContexto contexto, long relatorioId) {
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    Aberto relatorioEmMontagem() {
        var pessoa = usuario(jdbc, AMARELO);
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        AtlasContexto contexto = contextos.para(pessoa, amarelo(), conversa, requisicao());
        return new Aberto(contexto, relatorios.iniciar(contexto, "ATIVACAO_EVENTO", null).relatorioId());
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    static String argumentosDoCartao(String mensagem) {
        return AtlasJson.escrever(Map.of("mensagem", mensagem, "campos", CHAVES));
    }

    // ---------------------------------------------------------------------
    // A regra
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "recusa: {0}")
    @ValueSource(strings = {
            MENSAGEM_DO_REVISOR,
            "Informe 1.234 visitantes.",
            "Informe mil visitantes.",
            "Foram dois mil visitantes?",
            "Informe os brindes: trezentos e doze entregues.",
            "Informe a data de primeiro de dezembro.",
            "Informe a data 04/12/2026.",
            "Informe os números ⑫.",
            "Veja www.coleta.example.",
            "Acesse https://coleta.example/numeros.",
            "Veja coleta.com.br.",
            "<b>Informe</b> os números.",
            "Informe dado:ativacao.visitantes_total.",
            "Use a ferramenta relatorio_editar.",
            "Informe {{dado:ativacao.visitantes_total}} agora.",
            "{ Informe os números."})
    void mensagemComNumeroEnderecoHtmlChaveOuMarcadorNaoAbreOCartao(String mensagem) {
        Aberto aberto = relatorioEmMontagem();

        assertThatThrownBy(() -> relatorios.solicitarCampos(aberto.contexto(), aberto.relatorioId(), mensagem, CHAVES, "call_x"))
                .isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos()).containsOnlyKeys("mensagem"));
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ?", aberto.relatorioId())).isZero();
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE id = :id").param("id", aberto.relatorioId()).query(String.class).single())
                .as("o relatório continua em montagem").isEqualTo("MONTANDO");
    }

    @ParameterizedTest(name = "passa: {0}")
    @ValueSource(strings = {
            PlanoBSemLlm.MENSAGEM_DO_CARTAO,
            "Faltam dois números.",
            "Preciso dos números de público e dos brindes para fechar a seção.",
            "Pode me passar os valores da contagem da agência? Sem eles a seção de público fica incompleta.",
            "Quantos visitantes passaram pelo espaço? E quantos deixaram cadastro com consentimento? Preciso dos dois para fechar o público do evento."})
    void mensagemSemNumeroEmPalavrasSimplesAbreOCartao(String mensagem) {
        Aberto aberto = relatorioEmMontagem();

        var cartao = relatorios.solicitarCampos(aberto.contexto(), aberto.relatorioId(), mensagem, CHAVES, "call_x");

        assertThat(cartao.mensagem()).isEqualTo(mensagem);
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ?", aberto.relatorioId())).isEqualTo(1);
    }

    @Test
    void oBotaoNaoCaiNaRegraComAMensagemFixa() {
        Aberto aberto = relatorioEmMontagem();

        var cartao = relatorios.solicitarCamposDoBotao(aberto.contexto(), aberto.relatorioId(), PlanoBSemLlm.MENSAGEM_DO_CARTAO, CHAVES,
                "botao_x");

        assertThat(cartao.mensagem()).isEqualTo("Para fechar a seção de público, preciso destes números.");
    }

    // ---------------------------------------------------------------------
    // No loop: o erro volta ao modelo, que reescreve
    // ---------------------------------------------------------------------

    @Test
    void aFerramentaDevolveOMotivoAoModeloEOCartaoSoAbreComAMensagemReescrita() {
        long conversa = atlas.abrirConversa(usuario(jdbc, AMARELO), amarelo(), null, requisicao()).conversaId();
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .chamada(LoopDoAgenteTest.SOLICITAR, argumentosDoCartao(MENSAGEM_DO_REVISOR))
                .chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);

        ConversaDto turno = atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa,
                new MensagemRequisicao("Monte o relatório da ativação.", null, null), requisicao());

        // o texto com número, link e chave nunca chegou à tela: o único cartão é o da segunda tentativa
        List<ItemCartao> cartoes = turno.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).toList();
        assertThat(cartoes).singleElement().satisfies(cartao -> assertThat(cartao.mensagem()).isEqualTo("Preciso destes números."));
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ?",
                conversa)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ? "
                + "AND (s.mensagem LIKE '%1.234%' OR s.mensagem LIKE '%www%' OR s.mensagem LIKE '%dado:%')", conversa)).isZero();
        // o modelo leu o motivo da recusa na ida seguinte
        MensagemDeChat recusa = modelo.pedidos().get(2).mensagens().stream()
                .filter(m -> "tool".equals(m.papel()) && LoopDoAgenteTest.SOLICITAR.equals(m.ferramenta())).findFirst().orElseThrow();
        Map<String, Object> corpo = AtlasJson.ler(recusa.conteudo(), new TypeReference<Map<String, Object>>() {
        });
        assertThat(corpo).containsKey("erro");
        assertThat(corpo.get("campos")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) corpo.get("campos")).get("mensagem").toString()).contains("endereço");
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }
}
