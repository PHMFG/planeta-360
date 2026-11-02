package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O que o debug das ferramentas ({@code atlas.debug.ferramentas=true}) tem de igual nos dois modos do relatório: o campo
 * {@code debugDeFerramentas}, o item {@code ferramenta} depois do {@code texto} da mesma mensagem e na ordem em que rodou, com os
 * argumentos e o resultado crus (iguais ao que o histórico guarda e ao que o modelo recebeu), o corte em 20.000 caracteres, o erro
 * de ferramenta, o raciocínio cru que nunca sai e o 404 de quem não é da parceria. As classes concretas ligam a propriedade e
 * escolhem o modo. Não faz parte da API.
 * <p>
 * É {@code @Transactional} aqui, na classe que declara os testes, e não só nas concretas: o Spring procura o {@code @Transactional} de um
 * método na classe que o declara, então o da subclasse não vale para o teste herdado, que rodava sem transação, gravava as conversas com
 * commit e deixava cinco delas no banco para o teste que viesse depois (o AtlasControllerTest viu 6 conversas onde esperava 1).
 */
@Transactional
abstract class DebugDeFerramentasBase {

    /** O que o provedor roteirizado põe no reasoning_content e que não pode aparecer em nenhum corpo da API. */
    static final String SENTINELA = "SENTINELA-DO-RACIOCINIO-CRU";
    static final String CONSULTAR = LoopDoAgenteTest.CONSULTAR;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    MvcTestResult abrir(SessaoDeApi pessoa) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return aberta;
    }

    long abrirConversa(SessaoDeApi pessoa) {
        return ((Number) lerJson(abrir(pessoa), "$.conversaId")).longValue();
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return resposta;
    }

    /** O que as ferramentas devolveram ao modelo, na ordem em que o histórico as gravou. */
    List<String> respostasGravadas(long conversa) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
    }

    @Test
    void aConversaDizQueODebugEstaLigado() {
        SessaoDeApi pessoa = entrar(AMARELO);

        MvcTestResult aberta = abrir(pessoa);
        long conversa = ((Number) lerJson(aberta, "$.conversaId")).longValue();
        modelo.texto("Oi, sou o Atlas.");

        assertThat(lerJson(aberta, "$.debugDeFerramentas")).isEqualTo(true);
        assertThat(lerJson(falar(pessoa, conversa, "Oi"), "$.debugDeFerramentas")).isEqualTo(true);
        assertThat(lerJson(pessoa.get("/api/atlas/conversas/" + conversa), "$.debugDeFerramentas")).isEqualTo(true);
        // sem ferramenta no turno, não há item ferramenta
        assertThat(lerLista(pessoa.get("/api/atlas/conversas/" + conversa), "$.itens[*].tipo")).containsExactly("usuario", "texto");
    }

    @Test
    void oItemFerramentaVemDepoisDoTextoDaMensagemEMostraOQueOModeloEnviouERecebeu() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamadas("Vou consultar os dados.", SENTINELA, pedido(CONSULTAR, "{}"), pedido(CONSULTAR, "{\"parceria_id\":1}"))
                .texto("Aplicações de marca previstas: {{cons:aplicacoes.previstas}}.");

        MvcTestResult resposta = falar(pessoa, conversa, "Quantas aplicações?");

        // o resumo do raciocínio, se já está gravado, vem antes do texto da mensagem; o resto é a ordem do contrato
        List<String> tipos = lerLista(resposta, "$.itens[*].tipo");
        assertThat(tipos.stream().filter(t -> !t.equals("raciocinio")).toList())
                .containsExactly("usuario", "texto", "ferramenta", "ferramenta", "texto");
        if (tipos.contains("raciocinio")) {
            assertThat(tipos.indexOf("raciocinio")).isLessThan(tipos.indexOf("texto"));
        }
        List<Map<String, Object>> itens = lerLista(resposta, "$.itens[?(@.tipo!='raciocinio')]");
        assertThat(itens.get(1).get("texto")).isEqualTo("Vou consultar os dados.");
        Map<String, Object> primeira = itens.get(2);
        assertThat(primeira.keySet()).containsExactly("tipo", "nome", "argumentos", "resultado", "erro", "duracaoMs");
        assertThat(primeira).containsEntry("nome", CONSULTAR).containsEntry("argumentos", "{}").containsEntry("erro", false);
        assertThat(primeira.get("duracaoMs")).isInstanceOf(Integer.class);
        assertThat((Integer) primeira.get("duracaoMs")).isGreaterThanOrEqualTo(0);
        // argumentos e resultado são strings com o JSON cru: o resultado é o mesmo texto que o modelo recebeu
        List<String> gravadas = respostasGravadas(conversa);
        assertThat(gravadas).hasSize(2);
        assertThat(primeira.get("resultado")).isEqualTo(gravadas.get(0));
        assertThat((String) primeira.get("resultado")).startsWith("{").contains("\"dados\"");
        var vistoPeloModelo = modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo()).toList();
        assertThat(vistoPeloModelo).isEqualTo(gravadas);

        // a que o servidor recusou: erro de ferramenta, também crua
        Map<String, Object> segunda = itens.get(3);
        assertThat(segunda).containsEntry("nome", CONSULTAR).containsEntry("argumentos", "{\"parceria_id\":1}").containsEntry("erro", true)
                .containsEntry("resultado", gravadas.get(1));
        assertThat((String) segunda.get("resultado")).contains("Argumento desconhecido");

        // a leitura da conversa inteira (rota 45) traz o mesmo
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        List<Map<String, Object>> daConversa = lerLista(inteira, "$.itens[?(@.tipo=='ferramenta')]");
        assertThat(daConversa).containsExactly(primeira, segunda);
        // o raciocínio cru nunca sai, nem pelo debug
        assertThat(corpo(resposta)).doesNotContain(SENTINELA);
        assertThat(corpo(inteira)).doesNotContain(SENTINELA);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND reasoning_content LIKE :s").param("c", conversa)
                .param("s", "%" + SENTINELA + "%").query(Long.class).single()).isEqualTo(1);   // gravado no servidor, só lá
    }

    @Test
    void aRespostaDiretaTambemApareceNoDebugComoFerramenta() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada("gerar_modelo", "{\"tipo\":\"BRIEFING_CONTEUDO\"}");

        MvcTestResult resposta = falar(pessoa, conversa, "Me dá um modelo de briefing.");

        assertThat(modelo.pedidos()).hasSize(1);   // o turno acabou na resposta direta
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "ferramenta", "modelo");   // a ferramenta, depois o item dela
        List<Map<String, Object>> ferramentas = lerLista(resposta, "$.itens[?(@.tipo=='ferramenta')]");
        assertThat(ferramentas).hasSize(1);
        assertThat(ferramentas.get(0)).containsEntry("nome", "gerar_modelo").containsEntry("argumentos", "{\"tipo\":\"BRIEFING_CONTEUDO\"}")
                .containsEntry("erro", false).containsEntry("resultado", respostasGravadas(conversa).get(0));
        assertThat((String) ferramentas.get(0).get("resultado")).contains("\"nomeArquivo\":\"briefing-de-conteudo.md\"");
    }

    @Test
    void argumentosEResultadoSaemCortadosEm20000Caracteres() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        String enorme = "A".repeat(25_000);
        modelo.chamada(CONSULTAR, "{\"x\":\"" + enorme + "\"}").texto("Não consegui buscar esse dado agora.");

        MvcTestResult resposta = falar(pessoa, conversa, "Oi");

        String argumentos = lerJson(resposta, "$.itens[1].argumentos").toString();
        assertThat(lerJson(resposta, "$.itens[1].tipo")).isEqualTo("ferramenta");
        assertThat(argumentos).hasSize(20_000).startsWith("{\"x\":\"AAAA");
        assertThat(lerJson(resposta, "$.itens[1].erro")).isEqualTo(true);
        // o histórico guarda os argumentos inteiros
        assertThat(jdbc.sql("SELECT length(tool_calls -> 0 -> 'function' ->> 'arguments') FROM atlas_mensagem "
                + "WHERE conversa_id = :c AND tool_calls IS NOT NULL").param("c", conversa).query(Integer.class).single()).isEqualTo(25_000 + 8);
    }

    @Test
    void quemNaoEDaParceriaRecebe404NaConversaComDebug() {
        SessaoDeApi dona = entrar(AMARELO);
        long conversa = abrirConversa(dona);
        modelo.chamada(CONSULTAR, "{}").texto("Os números de {{parc:patrocinador}} estão no portal.");
        falar(dona, conversa, "Oi");

        SessaoDeApi outra = entrar(ROXO);
        MvcTestResult lida = outra.get("/api/atlas/conversas/" + conversa);
        MvcTestResult escrita = outra.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(lida).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(escrita).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(corpo(lida)).doesNotContain("ferramenta", "resultado", CONSULTAR);
        assertThat(entrar("b2b@rbs.example").get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.FORBIDDEN);
    }
}
