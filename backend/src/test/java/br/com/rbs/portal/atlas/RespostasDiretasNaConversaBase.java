package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VERDE;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.ItemModelo;
import br.com.rbs.portal.atlas.AtlasDtos.ItemPedidoRelatorio;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ModeloOferecido;
import br.com.rbs.portal.atlas.ferramentas.GerarModelo;
import br.com.rbs.portal.atlas.ferramentas.PedidoDeRelatorio;
import br.com.rbs.portal.relatorio.RelatorioService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os itens {@code pedido_relatorio} e {@code modelo} da conversa (M5), derivados do histórico das respostas diretas, nos dois
 * modos do relatório (as classes concretas escolhem o modo): o turno traz o item, a releitura pela rota 45 traz o mesmo, o
 * {@code relatorioOficial} e os modelos do item são os de agora (falso e depois verdadeiro quando a RBS disponibiliza; a Pesquisa
 * muda com a aba), a Pesquisa é recusada para a Roxo e a Verde e aceita para o Amarelo, e resposta com erro não gera item. Cada
 * teste é desfeito no fim (@Transactional aqui, na classe que declara os testes: o do Spring procura o {@code @Transactional} de um método
 * na classe que o declara, então o da subclasse não vale para o teste herdado, que gravava com commit e deixava conversas no banco).
 * Não faz parte da API.
 */
@Transactional
abstract class RespostasDiretasNaConversaBase {

    /** O modo do relatório da classe concreta: o texto fixo do pedido muda com ele. */
    abstract boolean relatorioLigado();

    static final ModeloOferecido BRIEFING = new ModeloOferecido("BRIEFING_CONTEUDO", "Briefing de conteúdo");
    static final ModeloOferecido PESQUISA = new ModeloOferecido("PERGUNTAS_PESQUISA", "Perguntas da pesquisa");
    static final ModeloOferecido PLANO = new ModeloOferecido("PLANO_OPERACIONAL_ATIVACAO", "Plano operacional da ativação");

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    RelatorioService relatorios;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    long abrir(String email, String cliente) {
        return atlas.abrirConversa(usuario(jdbc, email), parceria(cliente), null, requisicao()).conversaId();
    }

    ConversaDto falar(String email, long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, email), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    ConversaDto reler(String email, long conversa) {
        return atlas.verConversa(usuario(jdbc, email), conversa);
    }

    static List<String> tipos(ConversaDto conversa) {
        return conversa.itens().stream().map(i -> switch (i) {
            case AtlasDtos.ItemUsuario u -> "usuario";
            case AtlasDtos.ItemRaciocinio r -> "raciocinio";
            case ItemTexto t -> "texto";
            case AtlasDtos.ItemFerramenta f -> "ferramenta";
            case AtlasDtos.ItemAviso a -> "aviso";
            case AtlasDtos.ItemCartao c -> "cartao";
            case AtlasDtos.ItemRelatorioPronto p -> "relatorio_pronto";
            case AtlasDtos.ItemCalculo c -> "calculo";
            case ItemPedidoRelatorio p -> "pedido_relatorio";
            case ItemModelo m -> "modelo";
        }).toList();
    }

    static <T extends ItemDaConversa> T unico(ConversaDto conversa, Class<T> tipo) {
        List<T> itens = conversa.itens().stream().filter(tipo::isInstance).map(tipo::cast).toList();
        assertThat(itens).as(tipo.getSimpleName()).hasSize(1);
        return itens.get(0);
    }

    // ---------------------------------------------------------------------
    // gerar_modelo
    // ---------------------------------------------------------------------

    @Test
    void gerarModeloViraOItemModeloEAReleituraTrazOMesmo() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(GerarModelo.NOME, "{\"tipo\":\"BRIEFING_CONTEUDO\"}");

        ConversaDto turno = falar(AMARELO, conversa, "Me dá um modelo de briefing.");

        assertThat(tipos(turno)).containsExactly("usuario", "modelo");
        ItemModelo item = unico(turno, ItemModelo.class);
        assertThat(item.titulo()).isEqualTo("Briefing de conteúdo");
        assertThat(item.nomeArquivo()).isEqualTo("briefing-de-conteudo.md");
        assertThat(item.conteudo()).startsWith("# Briefing de conteúdo").doesNotContainPattern("[0-9]");
        assertThat(item.turno()).isNull();
        assertThat(reler(AMARELO, conversa).itens()).isEqualTo(turno.itens());
    }

    @Test
    void osTresModelosViramItemComOTituloEONomeDoArquivo() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(GerarModelo.NOME, "{\"tipo\":\"BRIEFING_CONTEUDO\"}").chamada(GerarModelo.NOME, "{\"tipo\":\"PERGUNTAS_PESQUISA\"}")
                .chamada(GerarModelo.NOME, "{\"tipo\":\"PLANO_OPERACIONAL_ATIVACAO\"}");

        falar(AMARELO, conversa, "Um modelo de briefing.");
        falar(AMARELO, conversa, "E um de perguntas da pesquisa.");
        falar(AMARELO, conversa, "E um plano operacional.");

        assertThat(modelo.pedidos()).hasSize(3);   // uma ida por turno, sem nenhuma a mais
        List<ItemModelo> itens = reler(AMARELO, conversa).itens().stream().filter(ItemModelo.class::isInstance).map(ItemModelo.class::cast)
                .toList();
        assertThat(itens).extracting(ItemModelo::titulo)
                .containsExactly("Briefing de conteúdo", "Perguntas da pesquisa", "Plano operacional da ativação");
        assertThat(itens).extracting(ItemModelo::nomeArquivo)
                .containsExactly("briefing-de-conteudo.md", "perguntas-da-pesquisa.md", "plano-operacional-da-ativacao.md");
    }

    @Test
    void oTextoDaMesmaRodadaVemAntesDoModelo() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamadaComTexto("Segue o modelo.", GerarModelo.NOME, "{\"tipo\":\"PLANO_OPERACIONAL_ATIVACAO\"}");

        ConversaDto turno = falar(AMARELO, conversa, "Me dá um plano operacional.");

        assertThat(tipos(turno)).containsExactly("usuario", "texto", "modelo");
        assertThat(reler(AMARELO, conversa).itens()).isEqualTo(turno.itens());
    }

    @Test
    void aPesquisaERecusadaParaARoxoEParaAVerdeESemItemModelo() {
        for (var outro : List.of(List.of(ROXO, "Cliente Roxo"), List.of(VERDE, "Cliente Verde"))) {
            modelo.limpar();
            long conversa = abrir(outro.get(0), outro.get(1));
            modelo.chamada(GerarModelo.NOME, "{\"tipo\":\"PERGUNTAS_PESQUISA\"}").texto("Esse modelo é só de quem tem a pesquisa. Posso entregar outro.");

            ConversaDto turno = falar(outro.get(0), conversa, "Quero o modelo de perguntas da pesquisa.");

            assertThat(tipos(turno)).as(outro.get(1)).containsExactly("usuario", "texto");
            assertThat(reler(outro.get(0), conversa).itens()).as(outro.get(1)).isEqualTo(turno.itens());
        }
    }

    @Test
    void aPesquisaEAceitaParaOAmarelo() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(GerarModelo.NOME, "{\"tipo\":\"PERGUNTAS_PESQUISA\"}");

        ConversaDto turno = falar(AMARELO, conversa, "Quero o modelo de perguntas da pesquisa.");

        assertThat(tipos(turno)).containsExactly("usuario", "modelo");
        assertThat(unico(turno, ItemModelo.class).titulo()).isEqualTo("Perguntas da pesquisa");
    }

    @Test
    void respostaComErroNaoGeraItem() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(GerarModelo.NOME, "{\"tipo\":\"FAQ\"}").texto("Não tenho esse modelo.")
                .chamada(GerarModelo.NOME, "{\"tipo\":\"BRIEFING_CONTEUDO\",\"parceria_id\":3}").texto("Não consegui entregar o modelo.")
                .chamada(PedidoDeRelatorio.NOME, "{\"parceria_id\":3}").texto("Não consegui responder sobre o relatório.");

        assertThat(tipos(falar(AMARELO, conversa, "Um modelo de FAQ."))).containsExactly("usuario", "texto");
        assertThat(tipos(falar(AMARELO, conversa, "Um modelo de briefing."))).containsExactly("usuario", "texto");
        assertThat(tipos(falar(AMARELO, conversa, "Quero o relatório."))).containsExactly("usuario", "texto");
        assertThat(tipos(reler(AMARELO, conversa))).doesNotContain("modelo", "pedido_relatorio");
    }

    // ---------------------------------------------------------------------
    // pedido_de_relatorio
    // ---------------------------------------------------------------------

    @Test
    void pedidoDeRelatorioViraOItemELeAReleituraDeAgora() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(PedidoDeRelatorio.NOME, "{}");

        ConversaDto turno = falar(AMARELO, conversa, "Quero o relatório da minha parceria.");

        assertThat(tipos(turno)).containsExactly("usuario", "pedido_relatorio");
        assertThat(modelo.pedidos()).hasSize(1);
        ItemPedidoRelatorio item = unico(turno, ItemPedidoRelatorio.class);
        assertThat(item.relatorioOficial()).isFalse();
        assertThat(item.texto()).isEqualTo(PedidoDeRelatorio.texto(false, relatorioLigado()));
        assertThat(item.texto().contains("O relatório da ativação no evento eu monto aqui: é só pedir.")).as("a oferta de montar o relatório da ativação só existe com o relatório ligado")
                .isEqualTo(relatorioLigado());
        assertThat(item.modelos()).containsExactly(BRIEFING, PESQUISA, PLANO);
        assertThat(item.turno()).isNull();
        assertThat(reler(AMARELO, conversa).itens()).isEqualTo(turno.itens());

        // a RBS disponibiliza depois: a releitura mostra o de agora, e o que o modelo recebeu no turno continua como foi gravado
        long relatorio = jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", parceria("Cliente Amarelo"))
                .query(Long.class).single();
        relatorios.mudarStatus(usuario(jdbc, RBS), relatorio, "VALIDADO", requisicao());
        assertThat(unico(reler(AMARELO, conversa), ItemPedidoRelatorio.class).relatorioOficial()).as("validado ainda não vale").isFalse();
        relatorios.mudarStatus(usuario(jdbc, RBS), relatorio, "DISPONIBILIZADO", requisicao());

        ItemPedidoRelatorio depois = unico(reler(AMARELO, conversa), ItemPedidoRelatorio.class);
        assertThat(depois.relatorioOficial()).isTrue();
        assertThat(depois.texto()).isEqualTo(PedidoDeRelatorio.texto(true, relatorioLigado())).contains("já está disponível");
        assertThat(depois.modelos()).containsExactly(BRIEFING, PESQUISA, PLANO);
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single()).contains("\"relatorioOficial\":false");
    }

    @Test
    void osModelosDoPedidoMudamComAAbaPesquisaEComACota() {
        for (var outro : List.of(List.of(ROXO, "Cliente Roxo"), List.of(VERDE, "Cliente Verde"))) {
            modelo.limpar();
            long conversa = abrir(outro.get(0), outro.get(1));
            modelo.chamada(PedidoDeRelatorio.NOME, "{}");

            ConversaDto turno = falar(outro.get(0), conversa, "Quero o relatório.");

            assertThat(unico(turno, ItemPedidoRelatorio.class).modelos()).as(outro.get(1)).containsExactly(BRIEFING, PLANO);
        }

        // o Amarelo é Master, mas se a RBS fecha a aba Pesquisa o item relido já não oferece as perguntas
        modelo.limpar();
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(PedidoDeRelatorio.NOME, "{}");
        assertThat(unico(falar(AMARELO, conversa, "Quero o relatório."), ItemPedidoRelatorio.class).modelos())
                .containsExactly(BRIEFING, PESQUISA, PLANO);
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')")
                .param("p", parceria("Cliente Amarelo")).update();
        assertThat(unico(reler(AMARELO, conversa), ItemPedidoRelatorio.class).modelos()).containsExactly(BRIEFING, PLANO);
    }

    @Test
    void pedidoEModeloNaMesmaRodadaViramDoisItensNaOrdemDasRespostas() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamadas(null, null, pedido(PedidoDeRelatorio.NOME, "{}"), pedido(GerarModelo.NOME, "{\"tipo\":\"BRIEFING_CONTEUDO\"}"));

        ConversaDto turno = falar(AMARELO, conversa, "Quero o relatório e um modelo de briefing.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(tipos(turno)).containsExactly("usuario", "pedido_relatorio", "modelo");
        assertThat(reler(AMARELO, conversa).itens()).isEqualTo(turno.itens());
    }
}
