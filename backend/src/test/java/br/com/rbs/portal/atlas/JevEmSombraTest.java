package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] {@code SOMBRA} não muda nada (critério B do M7): com o mesmo modelo de linguagem roteirizado, uma conversa com todos os
 * pontos em sombra termina com os MESMOS itens, as mesmas mensagens no banco (menos os ids que o servidor sorteia) e os mesmos pedidos
 * ao modelo (mensagens e ferramentas) que com o JEV desligado, mesmo quando o JEV decidiria o contrário (recusar, responder direto,
 * abrir o relatório, limitar as ferramentas, responder ao cartão). O que a sombra faz é gravar, em {@code atlas_decisao}, o que ela
 * "teria feito". Não é {@code @Transactional}: o que o teste confirmou é apagado no fim.
 */
@TesteComJevRoteirizado
class JevEmSombraTest {

    /** O que um cenário deixou: tudo o que o JEV não pode mudar. */
    private record Resultado(List<String> itens, List<String> mensagens, List<String> pedidosAoModelo) {
    }

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        limpeza.apagar();
    }

    private UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    private long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    private long conversa() {
        return atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private static String semIds(String texto) {
        return texto == null ? "" : texto.replaceAll("(call|botao)_[0-9a-f-]{8,}", "ID").replaceAll("(?i)\"(relatorioId|solicitacaoId|turno)\":\\s*\"?[0-9a-f-]+\"?",
                "\"$1\":N");
    }

    private static String projetar(MensagemDeChat m) {
        return m.papel() + "|" + semIds(m.conteudo()) + "|" + m.chamadas().stream().map(c -> c.nome() + semIds(c.argumentos())).toList();
    }

    /**
     * Roda o cenário no modo dado, com todos os pontos nele, e devolve o que ele deixou. O cenário recebe a conversa e devolve a
     * conversa do último turno.
     */
    private Resultado rodar(ModoDoJev modoDoJev, Supplier<ConversaDto> cenario, Consumer<Long> depoisDoCenario) {
        modelo.limpar();
        config.limpar().ligarTodos(modoDoJev);
        ConversaDto ultima = cenario.get();
        long conversaId = ultima.conversaId();
        depoisDoCenario.accept(conversaId);
        List<String> mensagens = jdbc.sql("SELECT papel, conteudo, tool_calls::text AS chamadas, tool_call_id IS NOT NULL AS resposta, sem_modelo, "
                        + "fora_do_modelo FROM atlas_mensagem WHERE conversa_id = :c ORDER BY ordem").param("c", conversaId)
                .query((rs, n) -> rs.getString("papel") + "|" + semIds(rs.getString("conteudo")) + "|" + semIds(rs.getString("chamadas")) + "|"
                        + rs.getBoolean("resposta") + "|" + rs.getBoolean("sem_modelo") + "|" + rs.getBoolean("fora_do_modelo")).list();
        List<String> itens = ultima.itens().stream().map(i -> semIds(br.com.rbs.portal.atlas.AtlasJson.escrever(i))).toList();
        List<String> pedidos = new ArrayList<>();
        for (PedidoAoLlm pedido : modelo.pedidos()) {
            pedidos.add(ProvedorRoteirizado.nomesDasFerramentas(pedido) + "::" + pedido.mensagens().stream().map(JevEmSombraTest::projetar).toList());
        }
        return new Resultado(itens, mensagens, pedidos);
    }

    /** Roda o cenário com o JEV desligado e em sombra, e exige o mesmo resultado; devolve a conversa da sombra para ver o que ela registrou. */
    private long semDiferenca(String nome, Consumer<JevRoteirizado> roteiroDoJev, Consumer<ProvedorRoteirizado> roteiroDoModelo,
                              Supplier<ConversaDto> cenario) {
        roteiroDoJev.accept(jev.limpar());
        Resultado desligado = rodar(ModoDoJev.DESLIGADO, () -> {
            roteiroDoModelo.accept(modelo);
            return cenario.get();
        }, id -> {
        });
        int chamadasAoJevComDesligado = jev.recebidas().size();
        assertThat(chamadasAoJevComDesligado).as(nome + ": desligado não consulta o JEV").isZero();
        roteiroDoJev.accept(jev);
        long[] conversaDaSombra = new long[1];
        Resultado sombra = rodar(ModoDoJev.SOMBRA, () -> {
            roteiroDoModelo.accept(modelo);
            ConversaDto conversa = cenario.get();
            conversaDaSombra[0] = conversa.conversaId();
            return conversa;
        }, id -> {
        });
        assertThat(jev.recebidas()).as(nome + ": a sombra consultou o JEV").isNotEmpty();
        assertThat(sombra.pedidosAoModelo()).as(nome + ": o que foi ao modelo").isEqualTo(desligado.pedidosAoModelo());
        assertThat(sombra.mensagens()).as(nome + ": as mensagens gravadas").isEqualTo(desligado.mensagens());
        assertThat(sombra.itens()).as(nome + ": os itens da conversa").isEqualTo(desligado.itens());
        return conversaDaSombra[0];
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.ponto, d.modo, d.acao FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id "
                + "WHERE m.conversa_id = :c ORDER BY d.id").param("c", conversa).query().listOfRows();
    }

    @Test
    void foraDeEscopoEmSombraChamaOModeloComoSeOJevNaoExistisse() {
        long conversa = semDiferenca("fora de escopo", j -> j.intencao("fora_de_escopo", 0.99), m -> m.texto("Só posso ajudar com o portal."),
                () -> falar(conversa(), "me passa uma receita de bolo"));

        assertThat(decisoes(conversa)).isNotEmpty().allSatisfy(d -> assertThat(d.get("modo")).isEqualTo("SOMBRA"));
        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("teria feito: recusar o fora de escopo sem o modelo");
    }

    @Test
    void pedidoDeRelatorioEmSombraSegueOLoopDoModelo() {
        long conversa = semDiferenca("outro relatório", j -> j.intencao("outro_relatorio", 0.97),
                m -> m.chamada("pedido_de_relatorio", "{}"), () -> falar(conversa(), "preciso do relatório oficial"));

        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("teria feito: responder o pedido de relatório sem o modelo");
    }

    @Test
    void modeloEmBrancoEmSombraSegueOLoopDoModelo() {
        long conversa = semDiferenca("modelo em branco", j -> j.intencao("modelo_em_branco", 0.97, "briefing_conteudo", 0.95),
                m -> m.chamada("gerar_modelo", "{\"tipo\":\"BRIEFING_CONTEUDO\"}"), () -> falar(conversa(), "tem um template de briefing?"));

        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("teria feito: entregar o modelo em branco sem o modelo");
    }

    @Test
    void montarRelatorioEmSombraNaoAbreORelatorioEmCodigo() {
        long conversa = semDiferenca("montar relatório", j -> j.intencao("montar_relatorio", 0.98),
                m -> m.chamada("relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}").texto("Preciso de alguns números."),
                () -> falar(conversa(), "monta o relatório da ativação"));

        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("teria feito: abrir o relatório em código e seguir para o modelo");
    }

    @Test
    void ramoDeDocumentosEmSombraNaoLimitaAsFerramentas() {
        long conversa = semDiferenca("ramo", j -> j.intencao("pergunta_documentos", 0.97), m -> m.texto("Não encontrei isso nos documentos do portal."),
                () -> falar(conversa(), "qual o horário de montagem?"));

        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("teria feito: dar ao modelo só as ferramentas dos documentos");
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.pedidos().get(0))).contains("parceria_consultar", "pedido_de_relatorio");
    }

    @Test
    void cartaoEmSombraFechaOCartaoComCancelComoSempre() {
        long conversa = semDiferenca("cartão", j -> j.cartao("responde_ao_cartao", 0.99).intencao("outro", 0.9),
                m -> m.texto("O horário de montagem está no manual."), () -> {
                    long nova = conversa();
                    atlas.enviarMensagem(marca(), nova, new MensagemRequisicao(null, "MONTAR_RELATORIO", "ATIVACAO_EVENTO"), requisicao());
                    return falar(nova, "e os brindes, onde eu informo?");
                });

        List<Map<String, Object>> doCartao = decisoes(conversa).stream().filter(d -> "cartao".equals(d.get("ponto"))).toList();
        assertThat(doCartao).hasSize(1);
        assertThat(doCartao.get(0).get("acao")).isEqualTo("teria feito: responder com a frase fixa e manter o cartão aberto");
        assertThat(jdbc.sql("SELECT s.status FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = :c")
                .param("c", conversa).query(String.class).single()).isEqualTo("CANCELADA");
    }

    @Test
    void emSombraAsDecisoesGravamOModoSombraEOLimiarDoPonto() {
        config.limpar().ligarTodos(ModoDoJev.SOMBRA);
        jev.limpar().intencao("outro", 0.5);
        modelo.texto("Olá!");
        long conversa = conversa();

        falar(conversa, "oi");

        List<Map<String, Object>> linhas = jdbc.sql("SELECT d.ponto, d.modo, d.limiar, d.acao FROM atlas_decisao d JOIN atlas_mensagem m "
                + "ON m.id = d.mensagem_id WHERE m.conversa_id = :c ORDER BY d.id").param("c", conversa).query().listOfRows();
        assertThat(linhas).extracting(l -> l.get("ponto")).containsOnly(PontoDoJev.INTENCAO.nome());
        assertThat(linhas).extracting(l -> l.get("modo")).containsOnly("SOMBRA");
        assertThat(linhas).extracting(l -> ((Number) l.get("limiar")).doubleValue()).containsOnly(PontoDoJev.INTENCAO.limiarPadrao());
        assertThat(linhas.get(0).get("acao")).isEqualTo("não agiu: confiança abaixo do limiar");
    }
}
