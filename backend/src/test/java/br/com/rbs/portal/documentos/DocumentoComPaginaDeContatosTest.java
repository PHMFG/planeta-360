package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [R2.1] Um PDF de 3 páginas com uma lista de telefones fictícios só na página 2 (o desenho do manual de operações de 2026,
 * em que só a p. 4 trava a aprovação). Sem marcar nada, a aprovação trava e a resposta cita a página (e o tipo do achado), nunca o
 * número. Com a página 2 fora do Atlas, ele aprova e indexa sem nenhum trecho dela. Cada teste é desfeito no fim; a ingestão roda
 * pelo {@code processar}, como no DocumentoStatusTest.
 */
@TesteDeIntegracao
@Transactional
class DocumentoComPaginaDeContatosTest extends ApoioDeDocumentos {

    static final String PAGINA_1 = "Pagina um. O abastecimento das ativacoes acontece entre sete e dez horas da manha.";
    static final String PAGINA_2 = "Contatos chave Zebrinha: (51) 3000-1111 (51) 3000-2222 (51) 3000-3333 (51) 3000-4444 (51) 3000-5555";
    static final String PAGINA_3 = "Pagina tres. A montagem das ativacoes termina na quinta-feira anterior ao festival.";
    /** Os pedaços de telefone que nunca podem aparecer numa resposta, num trecho nem no histórico. */
    static final List<String> NUMEROS = List.of("3000-1111", "30001111", "3000-3333", "3000-5555");

    @Autowired
    IngestaoService ingestao;

    static byte[] manualDeTresPaginas() throws Exception {
        return ExtratorDeTextoTest.pdf(PAGINA_1, PAGINA_2, PAGINA_3);
    }

    private static String corpo(MvcTestResult resposta) throws Exception {
        return resposta.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void semNumeros(String texto) {
        for (String numero : NUMEROS) {
            assertThat(texto).as("o número " + numero.charAt(0) + "… não pode aparecer").doesNotContain(numero);
        }
        assertThat(texto).doesNotContain("Zebrinha");
    }

    /**
     * A aprovação que o banco recusa (409). A recusa aborta a transação do PostgreSQL, e este teste é {@code @Transactional}: o ponto de
     * salvamento deixa o teste seguir lendo o banco depois dela.
     */
    private MvcTestResult aprovarTravado(SessaoDeApi rbs, long id) {
        jdbc.sql("SAVEPOINT antes_da_recusa").update();
        MvcTestResult resposta = acao(rbs, id, "APROVAR");
        jdbc.sql("ROLLBACK TO SAVEPOINT antes_da_recusa").update();
        return resposta;
    }

    @Test
    void semMarcarNadaAAprovacaoTravaECitaAPaginaSemOValor() throws Exception {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult envio = enviar(rbs, "manual.pdf", manualDeTresPaginas(), global("Manual com contatos", "teste-contatos"));

        assertThat(envio).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(envio, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(lerJson(envio, "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(lerJson(envio, "$.paginas")).isEqualTo(3);
        assertThat(lista(envio, "$.paginasSuspeitas")).containsExactly(2);
        assertThat(lista(envio, "$.suspeitas")).hasSize(1);
        assertThat(mapa(envio, "$.suspeitas[0]")).containsEntry("pagina", 2).containsEntry("tipo", "CONTATOS");
        assertThat(lista(envio, "$.paginasForaDoAtlas")).isEmpty();
        assertThat(lista(envio, "$.acoes")).containsExactly("RECUSAR");
        semNumeros(corpo(envio));

        long id = id(envio);
        MvcTestResult aprovar = aprovarTravado(rbs, id);
        assertThat(aprovar).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(aprovar, "$.detail")).isEqualTo(DocumentoRecusasTest.FRASE_DO_DADO_PESSOAL);
        semNumeros(corpo(aprovar));
        // a rota 56 e a lista (55) dizem a mesma coisa, também sem o valor
        MvcTestResult ver = rbs.get("/api/documentos/" + id);
        assertThat(lista(ver, "$.paginasSuspeitas")).containsExactly(2);
        semNumeros(corpo(ver));
        MvcTestResult listar = rbs.get("/api/documentos?chave=teste-contatos");
        assertThat(lista(listar, "$.itens[0].paginasSuspeitas")).containsExactly(2);
        semNumeros(corpo(listar));
    }

    @Test
    void comAPaginaMarcadaNoEnvioAprovaEIndexaSemNenhumTrechoDela() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        Map<String, String> campos = global("Manual sem a página de contatos", "teste-contatos-fora");
        campos.put("paginasForaDoAtlas", "2");

        MvcTestResult envio = enviar(rbs, "manual.pdf", manualDeTresPaginas(), campos);

        assertThat(envio).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(envio, "$.contemDadosPessoais")).isEqualTo(false);
        assertThat(lista(envio, "$.paginasSuspeitas")).isEmpty();
        assertThat(lista(envio, "$.paginasForaDoAtlas")).containsExactly(2);
        assertThat(lista(envio, "$.acoes")).containsExactly("APROVAR", "RECUSAR");
        long id = id(envio);

        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.OK);
        ingestao.processar(id);

        indexadoSemAPagina2(rbs, id);
        var historico = jdbc.sql("SELECT detalhe FROM historico WHERE entidade = 'documento' AND entidade_id = :id AND acao = 'CRIACAO'")
                .param("id", id).query(String.class).single();
        assertThat(historico).contains("páginas fora do Atlas: 2");
        semNumeros(historico);
    }

    @Test
    void comAPaginaMarcadaDepoisDoEnvioAprovaEIndexaSemNenhumTrechoDela() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarOk(rbs, "manual.pdf", manualDeTresPaginas(), global("Manual ajustado depois", "teste-contatos-ajuste"));
        assertThat(aprovarTravado(rbs, id)).hasStatus(HttpStatus.CONFLICT);

        MvcTestResult ajuste = rbs.patch("/api/documentos/" + id + "/paginas-fora-do-atlas", "{\"paginasForaDoAtlas\":[2]}");

        assertThat(ajuste).hasStatus(HttpStatus.OK);
        assertThat(lerJson(ajuste, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(lerJson(ajuste, "$.contemDadosPessoais")).isEqualTo(false);
        assertThat(lista(ajuste, "$.paginasSuspeitas")).isEmpty();
        assertThat(lista(ajuste, "$.suspeitas")).isEmpty();
        assertThat(lista(ajuste, "$.paginasForaDoAtlas")).containsExactly(2);
        assertThat(lista(ajuste, "$.acoes")).containsExactly("APROVAR", "RECUSAR");
        semNumeros(corpo(ajuste));
        // o histórico diz quem marcou e o quê, só com o número da página
        var linha = jdbc.sql("""
                        SELECT usuario_id, valor_anterior, valor_novo, detalhe FROM historico
                        WHERE entidade = 'documento' AND entidade_id = :id AND acao = 'EDICAO'
                        """).param("id", id).query().singleRow();
        assertThat(linha.get("valor_anterior")).isEqualTo("páginas fora do Atlas: nenhuma");
        assertThat(linha.get("valor_novo")).isEqualTo("páginas fora do Atlas: 2");
        assertThat(((Number) linha.get("usuario_id")).longValue())
                .isEqualTo(contar("SELECT id FROM usuario WHERE email = ?", RBS));
        semNumeros(String.valueOf(linha.get("detalhe")));

        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.OK);
        ingestao.processar(id);

        indexadoSemAPagina2(rbs, id);
    }

    private void indexadoSemAPagina2(SessaoDeApi rbs, long id) {
        MvcTestResult depois = rbs.get("/api/documentos/" + id);
        assertThat(lerJson(depois, "$.status")).isEqualTo("INDEXADO");
        assertThat(lerJson(depois, "$.paginas")).isEqualTo(3);
        assertThat(lista(depois, "$.paginasForaDoAtlas")).containsExactly(2);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id)).isGreaterThanOrEqualTo(2);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND (pagina_inicio = 2 OR pagina_fim = 2)", id))
                .as("nenhum trecho da página 2").isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND pagina_inicio = 1", id)).isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND pagina_inicio = 3", id)).isGreaterThan(0);
        List<String> textos = jdbc.sql("SELECT texto FROM documento_trecho WHERE documento_id = :id").param("id", id).query(String.class).list();
        for (String texto : textos) {
            semNumeros(texto);
        }
    }
}
