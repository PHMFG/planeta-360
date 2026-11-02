package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCalculo;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] [RELATORIO] O {@code calcular} pelo loop com o relatório LIGADO (o padrão do perfil de teste, M5): o item {@code calculo}
 * sai também com o relatório ligado (a derivação dos itens saía cedo sem o relatório) e, com o relatório em montagem na conversa, a
 * conta vai para {@code atlas_calculo}, o bloco a usa como {@code {{calc:nome}}} e o chat a mostra resolvida. Com o modelo
 * roteirizado. Não é {@code @Transactional}: o turno confirma de verdade e o que ficou é apagado no fim.
 */
@TesteComLlmRoteirizado
class CalcularComRelatorioNoLoopTest {

    private static final String MENSAGEM = "O preço é R$ 312,00 por metro e a área tem 34 metros.";
    private static final String TARIFA = "{\"nome\":\"tarifa\",\"script\":\"preco * area\",\"formato\":\"moeda\",\"variaveis\":{"
            + "\"preco\":{\"fonte\":\"usuario\",\"valor\":\"312\"},\"area\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static ItemTexto textoFinal(ConversaDto conversa) {
        List<ItemTexto> textos = conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos).as("o turno produziu um texto").isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    @Test
    void comORelatorioLigadoMasSemRelatorioEmMontagemOItemCalculoSaiENadaVaiParaAtlasCalculo() {
        long conversa = abrir();
        modelo.chamada("calcular", TARIFA).texto("A tarifa é R$ {{calc:tarifa}} [C1].");

        ConversaDto resposta = falar(conversa, MENSAGEM);

        assertThat(textoFinal(resposta).texto()).isEqualTo("A tarifa é R$ 10.608,00 [C1].");
        List<ItemCalculo> calculos = resposta.itens().stream().filter(ItemCalculo.class::isInstance).map(ItemCalculo.class::cast).toList();
        assertThat(calculos).hasSize(1);
        assertThat(calculos.get(0).marcador()).isEqualTo("C1");
        assertThat(calculos.get(0).ferramenta()).isEqualTo("calcular");
        assertThat(calculos.get(0).nome()).isEqualTo("tarifa");
        assertThat(calculos.get(0).resultado()).isEqualTo("10.608,00");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_calculo").query(Long.class).single()).isZero();
    }

    @Test
    void comORelatorioEmMontagemAContaVaiParaAtlasCalculoEOBlocoAUsa() {
        long conversa = abrir();
        String edicao = "{\"operacoes\":[{\"op\":\"definir_texto\",\"bloco\":\"b-resumo\",\"texto\":\"A energia custou R$ {{calc:tarifa}}.\"}]}";
        modelo.chamada("relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}").chamada("calcular", TARIFA).chamada("relatorio_editar", edicao)
                .texto("Já usei a conta no resumo: R$ {{calc:tarifa}} [C1].");

        ConversaDto resposta = falar(conversa, MENSAGEM);

        assertThat(textoFinal(resposta).texto()).isEqualTo("Já usei a conta no resumo: R$ 10.608,00 [C1].");
        assertThat(resposta.itens().stream().filter(ItemCalculo.class::isInstance).count()).isEqualTo(1);
        List<Map<String, Object>> contas = jdbc.sql("SELECT nome, resultado, jsonb_typeof(variaveis) AS tipo FROM atlas_calculo").query().listOfRows();
        assertThat(contas).hasSize(1);
        assertThat(contas.get(0)).containsEntry("nome", "tarifa").containsEntry("tipo", "object");
        // o bloco guardou o texto com o marcador, e a edição foi aplicada
        String resultadoDaEdicao = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem DESC LIMIT 1")
                .param("c", conversa).query(String.class).single();
        assertThat(resultadoDaEdicao).contains("aplicada");
        assertThat(jdbc.sql("SELECT especificacao::text FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .contains("{{calc:tarifa}}");
        // e o relido da conversa (rota 45) segue mostrando o valor
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).texto()).isEqualTo("Já usei a conta no resumo: R$ 10.608,00 [C1].");
    }
}
