package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] [VALIDACAO] O {@code calcular} pelo loop inteiro e pela API, no Atlas só chat (M5, critério bloqueante): a resposta
 * {@code R$ {{calc:tarifa}} [C1]} mostra "R$ 10.608,00" no fluxo (o {@code FiltroDoTexto}), no JSON da rota 44 e ao reler a conversa
 * pela rota 45, e <b>nunca</b> "indisponível". Duas contas de mesmo nome em turnos diferentes mostram cada uma o seu valor ao reler.
 * A conta usada só pelo marcador, sem citação, também relê certo. A citação {@code [C1]} passa na checagem e a {@code [C9]} não.
 * Denominador zero nunca vira zero: o marcador da conta que não existiu é recusado. Com o modelo roteirizado. Não é
 * {@code @Transactional}: o turno confirma de verdade e o que ficou é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=false"})
class CalcularNoChatTest {

    private static final String CALCULAR = "calcular";
    private static final String MENSAGEM = "O preço é R$ 312,00 por metro e a área tem 34 metros.";

    @Autowired
    MockMvcTester mvc;

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

    /** A conta tarifa = preco * area com os valores que a mensagem da pessoa tem. */
    static String tarifa(String nome, String preco, String area) {
        return "{\"nome\":\"" + nome + "\",\"script\":\"preco * area\",\"formato\":\"moeda\",\"variaveis\":{"
                + "\"preco\":{\"fonte\":\"usuario\",\"valor\":\"" + preco + "\"},\"area\":{\"fonte\":\"usuario\",\"valor\":\"" + area + "\"}}}";
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return resposta;
    }

    List<String> textos(MvcTestResult resposta) {
        return lerLista(resposta, "$.itens[?(@.tipo=='texto')].texto");
    }

    /** O que a tela mostra no fim: a soma dos {@code texto} que vieram depois do último {@code texto_recusado}. */
    static String queATelaMostra(List<Evento> eventos) {
        int ultimaRecusa = nomes(eventos).lastIndexOf("texto_recusado");
        StringBuilder tela = new StringBuilder();
        for (Evento evento : eventos.subList(ultimaRecusa + 1, eventos.size())) {
            if (evento.nome().equals("texto")) {
                tela.append(evento.<String>ler("$.delta"));
            }
        }
        return tela.toString();
    }

    // ---------------------------------------------------------------------
    // A conta certa aparece, nos três caminhos
    // ---------------------------------------------------------------------

    @Test
    void aContaApareceNoJsonENaReleituraENuncaComoIndisponivel() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ {{calc:tarifa}} [C1].");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("A tarifa é R$ 10.608,00 [C1].");
        assertThat(corpo(resposta)).doesNotContain("indisponível").doesNotContain("{{");
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "calculo", "texto");
        // o "Como calculei"
        Map<String, Object> calculo = lerLista(resposta, "$.itens[?(@.tipo=='calculo')]").get(0) instanceof Map<?, ?> mapa
                ? castMap(mapa) : Map.of();
        assertThat(calculo).containsEntry("marcador", "C1").containsEntry("ferramenta", "calcular").containsEntry("nome", "tarifa")
                .containsEntry("script", "preco * area").containsEntry("resultado", "10.608,00").containsEntry("regra", null)
                .containsEntry("feriadosConsiderados", List.of());
        assertThat(calculo.get("variaveis")).isEqualTo(List.of(Map.of("nome", "preco", "origem", "usuario", "valor", "312"),
                Map.of("nome", "area", "origem", "usuario", "valor", "34")));
        // a fonte [C1] do texto
        assertThat(lerJson(resposta, "$.itens[2].fontes[0].marcador")).isEqualTo("C1");
        assertThat(lerJson(resposta, "$.itens[2].fontes[0].tipo")).isEqualTo("CALCULO");
        assertThat(lerJson(resposta, "$.itens[2].fontes[0].titulo")).isEqualTo("tarifa");

        // reler a conversa pela rota 45 dá o mesmo
        MvcTestResult relida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(relida).hasStatus(HttpStatus.OK);
        assertThat(textos(relida)).containsExactly("A tarifa é R$ 10.608,00 [C1].");
        assertThat(corpo(relida)).doesNotContain("indisponível").doesNotContain("{{");
        assertThat(lerLista(relida, "$.itens[*].tipo")).containsExactly("usuario", "calculo", "texto");
        assertThat(lerJson(relida, "$.itens[1].resultado")).isEqualTo("10.608,00");
        // a linha de atlas_fonte
        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT f.marcador, f.tipo, f.dados ->> 'nome' AS nome, f.dados ->> 'valorFormatado' AS valor, f.dados ->> 'script' AS script,
                       f.registro_entidade, f.documento_id, f.trecho_id
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().listOfRows();
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0)).containsEntry("marcador", "C1").containsEntry("tipo", "CALCULO").containsEntry("nome", "tarifa")
                .containsEntry("valor", "10.608,00").containsEntry("script", "preco * area").containsEntry("registro_entidade", null)
                .containsEntry("documento_id", null).containsEntry("trecho_id", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> mapa) {
        return (Map<String, Object>) mapa;
    }

    @Test
    void aContaApareceNoFluxoQuandoOMarcadorChegaEmPedacos() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).textoEmPedacos(null, "A tarifa é R$ {{calc:", "tarifa}} ", "[C", "1].");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, MENSAGEM));

        assertThat(nomes(eventos)).doesNotContain("texto_recusado").contains("etapa");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")).toList())
                .contains("Fazendo a conta…");
        assertThat(queATelaMostra(eventos)).isEqualTo("A tarifa é R$ 10.608,00 [C1].");
        for (Evento evento : eventos) {
            assertThat(evento.dados()).doesNotContain("indisponível").doesNotContain("{{calc");
        }
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "calculo", "texto");
        assertThat(fim.<List<String>>ler("$.itens[?(@.tipo=='texto')].texto")).containsExactly("A tarifa é R$ 10.608,00 [C1].");
        assertThat(fim.<List<String>>ler("$.itens[?(@.tipo=='texto')].fontes[*].marcador")).containsExactly("C1");
    }

    @Test
    void duasContasDeMesmoNomeEmTurnosDiferentesMostramCadaUmaOSeuValorAoReler() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ {{calc:tarifa}} [C1].");
        falar(pessoa, conversa, MENSAGEM);
        modelo.chamada(CALCULAR, tarifa("tarifa", "400", "10")).texto("Agora a tarifa é R$ {{calc:tarifa}} [C1].");

        MvcTestResult segundo = falar(pessoa, conversa, "E se o preço for R$ 400,00 e a área tiver 10 metros?");

        assertThat(textos(segundo)).containsExactly("Agora a tarifa é R$ 4.000,00 [C1].");
        MvcTestResult relida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(textos(relida)).containsExactly("A tarifa é R$ 10.608,00 [C1].", "Agora a tarifa é R$ 4.000,00 [C1].");
        assertThat(lerLista(relida, "$.itens[?(@.tipo=='calculo')].resultado")).containsExactly("10.608,00", "4.000,00");
        assertThat(corpo(relida)).doesNotContain("indisponível");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void contaUsadaSoPeloMarcadorSemCitacaoTambemReleCerto() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("O total é R$ {{calc:tarifa}}.");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("O total é R$ 10.608,00.");
        // a conta foi gravada mesmo sem a citação, porque a releitura precisa dela; o texto não cita nada, então não tem fontes
        assertThat(jdbc.sql("SELECT f.marcador FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).list()).containsExactly("C1");
        MvcTestResult relida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(textos(relida)).containsExactly("O total é R$ 10.608,00.");
        assertThat(corpo(relida)).doesNotContain("indisponível");
        assertThat(lerLista(relida, "$.itens[?(@.tipo=='texto')].fontes")).isEmpty();
    }

    @Test
    void contaDeUmTurnoAnteriorNaoVaiComoMarcadorDoTurnoSeguinte() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ {{calc:tarifa}} [C1].");
        falar(pessoa, conversa, MENSAGEM);

        // no turno seguinte a conta não existe mais: o marcador é recusado, e a nova redação não usa a conta
        modelo.limpar();
        modelo.texto("De novo: R$ {{calc:tarifa}} [C1].").texto("Preciso calcular de novo.");
        MvcTestResult segundo = falar(pessoa, conversa, "Repete?");

        assertThat(textos(segundo)).containsExactly("Preciso calcular de novo.");
        MensagemDeChat pedidoDeNovaRedacao = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        assertThat(pedidoDeNovaRedacao.conteudo()).contains("[C1]", "não existe nos resultados deste turno");
    }

    @Test
    void aContaQueContinuaUmNumeroDeUmTurnoAnteriorDaPessoaFunciona() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        String energia = "{\"nome\":\"energia\",\"script\":\"kva * tarifa\",\"formato\":\"moeda\",\"variaveis\":{"
                + "\"kva\":{\"fonte\":\"usuario\",\"valor\":\"%s\"},\"tarifa\":{\"fonte\":\"usuario\",\"valor\":\"312\"}}}";
        modelo.chamada(CALCULAR, energia.formatted("12")).texto("Com 12 kVA, a energia é R$ {{calc:energia}} [C1].");
        assertThat(textos(falar(pessoa, conversa, "Quanto pago por 12 kVA, a R$ 312,00 por kVA?")))
                .containsExactly("Com 12 kVA, a energia é R$ 3.744,00 [C1].");
        // o 20 e o 312 são de mensagens anteriores
        modelo.limpar();
        modelo.chamada(CALCULAR, energia.formatted("20")).texto("Com 20 kVA, a energia é R$ {{calc:energia}} [C1].");
        assertThat(textos(falar(pessoa, conversa, "E se eu subir pra 20 kVA?"))).containsExactly("Com 20 kVA, a energia é R$ 6.240,00 [C1].");
        modelo.limpar();
        String junto = "{\"nome\":\"total\",\"script\":\"kva * tarifa + geradores\",\"formato\":\"moeda\",\"variaveis\":{"
                + "\"kva\":{\"fonte\":\"usuario\",\"valor\":\"20\"},\"tarifa\":{\"fonte\":\"usuario\",\"valor\":\"312\"},"
                + "\"geradores\":{\"fonte\":\"usuario\",\"valor\":\"2\"}}}";
        modelo.chamada(CALCULAR, junto).texto("Com 20 kVA e 2 geradores, fica R$ {{calc:total}} [C1].");

        MvcTestResult resposta = falar(pessoa, conversa, "Coloca junto o cadastro de 2 geradores, quanto fica tudo?");

        assertThat(textos(resposta)).containsExactly("Com 20 kVA e 2 geradores, fica R$ 6.242,00 [C1].");
        assertThat(modelo.pedidos()).as("nenhuma nova redação foi pedida").hasSize(2);
        // o que o assistente escreveu não vale como origem
        modelo.limpar();
        modelo.texto("Seriam R$ 3.744,00 como na primeira conta.").texto("Não sei dizer agora.");
        MvcTestResult sem = falar(pessoa, conversa, "E a primeira conta mesmo?");
        assertThat(textos(sem)).containsExactly("Não sei dizer agora.");
    }

    // ---------------------------------------------------------------------
    // As citações [C#]
    // ---------------------------------------------------------------------

    @Test
    void citacaoC1PassaNaChecagemEC9NaoPassa() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ {{calc:tarifa}} [C9].")
                .texto("A tarifa é R$ {{calc:tarifa}} [C1].");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("A tarifa é R$ 10.608,00 [C1].");
        MensagemDeChat pedidoDeNovaRedacao = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        assertThat(pedidoDeNovaRedacao.conteudo()).contains("[C9]", "não existe nos resultados deste turno", "C1");
        assertThat(lerJson(resposta, "$.itens[?(@.tipo=='texto')].fontes[0].marcador")).isEqualTo(List.of("C1"));
    }

    @Test
    void oResultadoEscritoComANumeroSemOMarcadorTambemPassaMasOutroNumeroNao() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ 10.608,00 [C1].");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("A tarifa é R$ 10.608,00 [C1].");

        modelo.limpar();
        long outra = conversa(pessoa, jdbc);
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).texto("A tarifa é R$ 10.609,00 [C1].").texto("A tarifa é R$ {{calc:tarifa}} [C1].");
        MvcTestResult errada = falar(pessoa, outra, MENSAGEM);

        assertThat(textos(errada)).containsExactly("A tarifa é R$ 10.608,00 [C1].");
    }

    // ---------------------------------------------------------------------
    // Indisponível: nunca zero
    // ---------------------------------------------------------------------

    @Test
    void denominadorZeroNuncaViraZeroEOMarcadorDaContaQueNaoExisteERecusado() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        String razao = "{\"nome\":\"razao\",\"script\":\"a / b\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"10\"},"
                + "\"b\":{\"fonte\":\"usuario\",\"valor\":\"0\"}}}";
        modelo.chamada(CALCULAR, razao).texto("A razão é {{calc:razao}}.").texto("Não dá para calcular essa razão porque o total é zero.");

        MvcTestResult resposta = falar(pessoa, conversa, "Quanto é 10 dividido por 0?");

        assertThat(textos(resposta)).containsExactly("Não dá para calcular essa razão porque o total é zero.");
        assertThat(lerLista(resposta, "$.itens[*].tipo")).as("sem item calculo").containsExactly("usuario", "texto");
        assertThat(corpo(resposta)).doesNotContain("indisponível").doesNotContain("0,00");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
        // o modelo recebeu o indisponível, com o motivo, e nenhum marcador
        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("\"indisponivel\":true", "motivo").doesNotContain("marcador");
    }

    @Test
    void erroDeArgumentoDoCalcularVoltaAoModeloENadaFica() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        String ataque = "{\"nome\":\"tarifa\",\"script\":\"preco * 312\",\"variaveis\":{\"preco\":{\"fonte\":\"usuario\",\"valor\":\"312\"}}}";
        modelo.chamada(CALCULAR, ataque).texto("Não consegui calcular isso agora.");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("Não consegui calcular isso agora.");
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("erro").contains("312");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
    }

    @Test
    void umaContaPodeUsarOResultadoDeOutraDoMesmoTurno() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        String dobro = "{\"nome\":\"dobro\",\"script\":\"t * 2\",\"formato\":\"moeda\",\"variaveis\":{\"t\":{\"ref\":\"calc:tarifa\"}}}";
        modelo.chamada(CALCULAR, tarifa("tarifa", "312", "34")).chamada(CALCULAR, dobro)
                .texto("A tarifa é R$ {{calc:tarifa}} [C1] e o dobro é R$ {{calc:dobro}} [C2].");

        MvcTestResult resposta = falar(pessoa, conversa, MENSAGEM);

        assertThat(textos(resposta)).containsExactly("A tarifa é R$ 10.608,00 [C1] e o dobro é R$ 21.216,00 [C2].");
        MvcTestResult relida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(textos(relida)).containsExactly("A tarifa é R$ 10.608,00 [C1] e o dobro é R$ 21.216,00 [C2].");
        assertThat(lerLista(relida, "$.itens[?(@.tipo=='texto')].fontes[*].marcador")).containsExactly("C1", "C2");
    }
}
