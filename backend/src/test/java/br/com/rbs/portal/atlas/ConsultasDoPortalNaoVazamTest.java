package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.prazos.ApoioDePrazos;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [ISOLAMENTO] [PERMISSAO] O que as três ferramentas de consulta ao portal ({@code listar_entregas}, {@code consultar_cota},
 * {@code consultar_prazos}) nunca deixam chegar ao modelo, pelo loop inteiro (M5, bloqueante): o valor do investimento da parceria
 * (nem o dela, nem o de outra) em nenhum resultado, no que o modelo recebe e na {@code atlas_tool_call}; e entrega, cota ou prazo
 * de outra parceria, mesmo com o modelo pedindo a parceria por argumento. Não é {@code @Transactional}: o turno confirma de verdade,
 * e o que o teste mudou (os valores, os prazos) e o que o Atlas gravou voltam ao que eram no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class ConsultasDoPortalNaoVazamTest {

    private static final String LISTAR = "listar_entregas";
    private static final String COTA = "consultar_cota";
    private static final String PRAZOS = "consultar_prazos";
    private static final String CONSULTAR = "parceria_consultar";
    /** O valor do Amarelo e o do Roxo que o teste põe, com dígitos que nenhum outro número da amostra tem. */
    private static final String VALOR_DO_AMARELO = "7654321.99";
    private static final String VALOR_DO_ROXO = "8765432.11";
    private static final List<String> FORMAS_DOS_VALORES = List.of("7654321", "7.654.321", "7 654 321", "8765432", "8.765.432", "8 765 432");
    private static final List<String> NOMES_DO_VALOR = List.of("valor_investimento", "valorInvestimento", "base_valor", "baseValor");

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;
    private long ultimoPrazo;
    private BigDecimal valorDoAmarelo;
    private String baseDoAmarelo;
    private BigDecimal valorDoRoxo;
    private String baseDoRoxo;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
        ultimoPrazo = ApoioDePrazos.ultimoId(jdbc);
        valorDoAmarelo = valor("Cliente Amarelo");
        baseDoAmarelo = base("Cliente Amarelo");
        valorDoRoxo = valor("Cliente Roxo");
        baseDoRoxo = base("Cliente Roxo");
    }

    @AfterEach
    void desfazerOQueOTesteConfirmou() {
        jdbc.sql("UPDATE parceria SET valor_investimento = :v, base_valor = :b WHERE id = :p").param("v", valorDoAmarelo).param("b", baseDoAmarelo)
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo")).update();
        jdbc.sql("UPDATE parceria SET valor_investimento = :v, base_valor = :b WHERE id = :p").param("v", valorDoRoxo).param("b", baseDoRoxo)
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo")).update();
        limpeza.apagar();
        ApoioDePrazos.apagarDepoisDe(jdbc, ultimoPrazo);
    }

    private BigDecimal valor(String cliente) {
        return jdbc.sql("SELECT valor_investimento FROM parceria WHERE id = :p").param("p", SessaoDeApi.parceriaDe2027(jdbc, cliente))
                .query(BigDecimal.class).single();
    }

    private String base(String cliente) {
        return jdbc.sql("SELECT base_valor FROM parceria WHERE id = :p").param("p", SessaoDeApi.parceriaDe2027(jdbc, cliente))
                .query(String.class).single();
    }

    private long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    private void falar(long conversa, String texto) {
        atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    @Test
    void oValorDoInvestimentoNuncaVaiAoModeloNemParaATrilhaDeNenhumaFerramenta() {
        jdbc.sql("UPDATE parceria SET valor_investimento = :v, base_valor = 'LIQUIDO' WHERE id = :p").param("v", new BigDecimal(VALOR_DO_AMARELO))
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo")).update();
        jdbc.sql("UPDATE parceria SET valor_investimento = :v, base_valor = 'LIQUIDO' WHERE id = :p").param("v", new BigDecimal(VALOR_DO_ROXO))
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo")).update();
        ApoioDePrazos.global(jdbc, "Prazo de teste", Datas.hoje().plusDays(5), null, null);
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(CONSULTAR, "{}"), pedido(LISTAR, "{}"), pedido(COTA, "{}"),
                pedido(PRAZOS, "{\"proximos_dias\":180,\"incluir_vencidos\":true}")).texto("Certo.");

        falar(conversa, "Quanto eu investi no patrocínio?");

        // o que o modelo recebeu de cada ferramenta
        List<String> resultados = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
        assertThat(resultados).hasSize(4);
        assertThat(resultados).allSatisfy(r -> assertThat(r).doesNotContain("erro\":"));
        // a trilha da auditoria: argumentos e resultado
        String trilha = jdbc.sql("""
                SELECT string_agg(coalesce(t.argumentos::text, '') || ' ' || coalesce(t.resultado::text, '') || ' ' || coalesce(t.erro, ''), ' ')
                FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query(String.class).single();
        // tudo o que seguiu ao provedor, menos o prompt de sistema (que é do prompt, não das ferramentas)
        String ditoAoModelo = modelo.pedidos().stream().map(PedidoAoLlm::mensagens).flatMap(List::stream).filter(m -> !"system".equals(m.papel()))
                .map(m -> String.valueOf(m.conteudo()) + " " + m.chamadas()).collect(Collectors.joining(" "));
        String tudo = String.join(" ", resultados) + " " + trilha + " " + ditoAoModelo;
        assertThat(tudo).as("o valor do investimento, em qualquer forma, e os nomes das colunas").doesNotContain(FORMAS_DOS_VALORES.toArray(new String[0]))
                .doesNotContain(NOMES_DO_VALOR.toArray(new String[0])).doesNotContain("LIQUIDO", "BRUTO_TABELA");
        // e as ferramentas funcionaram: a cota e as entregas vieram
        assertThat(resultados.get(2)).contains("Cota Patrocínio Master");
        assertThat(resultados.get(1)).contains("Peça de 30");
        assertThat(resultados.get(3)).contains("Prazo de teste");
    }

    @Test
    void trocarDeParceriaPorArgumentoEImpossivelEOQueEDeOutraMarcaNaoChegaAoModelo() {
        long roxo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
        ApoioDePrazos.daParceria(jdbc, "Cliente Roxo", "PRAZO-SECRETO-DO-ROXO", Datas.hoje().plusDays(2), null, null);
        ApoioDePrazos.daCota(jdbc, "SUBCOTA_G", "PRAZO-SECRETO-DA-SUBCOTA-G", Datas.hoje().plusDays(2), null, null);
        long conversa = abrir();
        modelo.chamadas(null, null,
                pedido(LISTAR, "{\"parceria_id\":" + roxo + "}"), pedido(COTA, "{\"parceriaId\":" + roxo + "}"),
                pedido(PRAZOS, "{\"parceria\":\"Cliente Roxo\"}"))
                .chamadas(null, null, pedido(LISTAR, "{}"), pedido(COTA, "{}"), pedido(PRAZOS, "{\"proximos_dias\":180}"))
                .texto("Certo.");

        falar(conversa, "Mostre os dados do Cliente Roxo.");

        List<String> resultados = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
        assertThat(resultados).hasSize(6);
        assertThat(resultados.subList(0, 3)).allSatisfy(r -> assertThat(r).contains("erro", "Argumento desconhecido"));
        assertThat(resultados.get(0)).contains("parceria_id");
        assertThat(resultados.get(1)).contains("parceriaId");
        assertThat(resultados.get(2)).contains("parceria");
        String doAmarelo = String.join(" ", resultados.subList(3, 6));
        assertThat(doAmarelo).doesNotContain("VT para os telões dos palcos", "PRAZO-SECRETO", "Cliente Roxo", "Cota Apresenta", "Subcota G")
                .contains("Peça de 30", "Cota Patrocínio Master");
        String trilha = jdbc.sql("""
                SELECT string_agg(coalesce(t.resultado::text, ''), ' ') FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id
                WHERE m.conversa_id = :c
                """).param("c", conversa).query(String.class).single();
        assertThat(trilha).doesNotContain("VT para os telões dos palcos", "PRAZO-SECRETO", "Cota Apresenta");
    }
}
