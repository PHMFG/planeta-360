package br.com.rbs.portal.pesquisa;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 23 a 25 (pesquisa da ativação) como o front as usa: login pela rota, sessão de verdade e token CSRF em cada
 * alteração. Atende o item 5 da Entrega 2. [VALIDACAO] [PERMISSAO] [ISOLAMENTO] [HISTORICO] Cada teste é desfeito no fim
 * (@Transactional). A amostra já traz as 3 perguntas do Amarelo APROVADAS; os testes que precisam delas em outro estado as
 * apagam antes (os resultados saem junto, em cascata). Um 409 ou 422 da API não aborta a transação do teste: só uma recusa do
 * banco aborta, e estas rotas conferem as regras antes de gravar.
 */
@TesteDeIntegracao
@Transactional
class PesquisaControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA_DO_AMARELO = "atendimento@agencia-x.example";
    private static final String ROXO = "marketing@roxo.example";

    private static final String AGENTE = "TesteDePesquisa/1.0";

    private static final String P1 = "Você visitou o espaço da marca na Arena ou no Camarote?";
    private static final String P2 = "De 0 a 10, quanto você recomendaria a marca a um amigo?";
    private static final String P3 = "Depois da ativação, sua percepção sobre a marca mudou?";
    private static final String FRASE_DA_P1 = "A pergunta 1 já foi aprovada pela RBS e não pode mais ser alterada.";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email, AGENTE);
    }

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    long parceria(String patrocinador) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :nome AND e.edicao = 2027
                        """).param("nome", patrocinador).query(Long.class).single();
    }

    long usuarioId(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :email").param("email", email).query(Long.class).single();
    }

    long perguntaId(long parceriaId, int ordem) {
        return jdbc.sql("SELECT id FROM pesquisa_pergunta WHERE parceria_id = :p AND ordem = :o")
                .param("p", parceriaId).param("o", ordem).query(Long.class).single();
    }

    String statusNoBanco(long perguntaId) {
        return jdbc.sql("SELECT status FROM pesquisa_pergunta WHERE id = :id").param("id", perguntaId)
                .query(String.class).single();
    }

    String textoNoBanco(long perguntaId) {
        return jdbc.sql("SELECT texto FROM pesquisa_pergunta WHERE id = :id").param("id", perguntaId)
                .query(String.class).single();
    }

    /** Apaga as perguntas da parceria (e, em cascata, os resultados), para o teste começar do zero. */
    void apagarPerguntas(long parceriaId) {
        jdbc.sql("DELETE FROM pesquisa_pergunta WHERE parceria_id = :p").param("p", parceriaId).update();
    }

    long contarHistorico(String acao, long parceriaId) {
        return jdbc.sql("SELECT count(*) FROM historico WHERE acao = :a AND entidade = 'pesquisa_pergunta' AND parceria_id = :p")
                .param("a", acao).param("p", parceriaId).query(Long.class).single();
    }

    /** O corpo do PUT com as perguntas nas ordens 1, 2, 3... */
    static String perguntas(String... textos) {
        StringBuilder json = new StringBuilder("{\"perguntas\":[");
        for (int i = 0; i < textos.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"ordem\":").append(i + 1).append(",\"texto\":\"").append(textos[i]).append("\"}");
        }
        return json.append("]}").toString();
    }

    MvcTestResult enviarPerguntas(SessaoDeApi sessao, long parceriaId, String json) {
        return sessao.put("/api/parcerias/" + parceriaId + "/pesquisa/perguntas", json);
    }

    MvcTestResult decidir(SessaoDeApi sessao, long perguntaId, String decisao, String observacao) {
        String corpo = "{\"decisao\":\"" + decisao + "\"" + (observacao != null ? ",\"observacao\":\"" + observacao + "\"" : "") + "}";
        return sessao.patch("/api/pesquisa/perguntas/" + perguntaId + "/status", corpo);
    }

    MvcTestResult verPesquisa(SessaoDeApi sessao, long parceriaId) {
        return sessao.get("/api/parcerias/" + parceriaId + "/pesquisa");
    }

    static long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    // ---------------------------------------------------------------------
    // Rota 23: ver a pesquisa
    // ---------------------------------------------------------------------

    @Test
    void amareloVeAPesquisaComAsTresPerguntasAprovadasEOsResultadosComABase() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(AMARELO);

        MvcTestResult resposta = verPesquisa(sessao, amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(numero(resposta, "$.parceriaId")).isEqualTo(amarelo);
        assertThat(lerJson(resposta, "$.abaLiberada")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.limitePerguntas")).isEqualTo(3);
        assertThat(lerJson(resposta, "$.pesquisa.escopo")).isEqualTo("ATIVACAO");
        assertThat(lerJson(resposta, "$.pesquisa.nome")).isEqualTo("Pesquisa nos espaços de ativação 2027");
        assertThat(lerJson(resposta, "$.pesquisa.baseRespondentes")).isEqualTo(412);
        assertThat(lerJson(resposta, "$.pesquisa.nivelConfianca")).isNull();
        assertThat(lerLista(resposta, "$.perguntas[*].ordem")).containsExactly(1, 2, 3);
        assertThat(lerLista(resposta, "$.perguntas[*].status")).containsOnly("APROVADA");
        assertThat(lerLista(resposta, "$.perguntas[*].texto")).containsExactly(P1, P2, P3);
        assertThat(lerJson(resposta, "$.perguntas[0].observacaoRbs")).isNull();
        assertThat(lerJson(resposta, "$.perguntas[0].enviadaPor.nome")).isEqualTo("Marketing Amarelo");
        assertThat(lerJson(resposta, "$.perguntas[0].enviadaPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat((String) lerJson(resposta, "$.perguntas[0].enviadaEm")).as("data com o fuso de Brasília").endsWith("-03:00");

        // Os resultados batem com o banco, linha a linha, e toda linha traz a base de respondentes.
        List<Map<String, Object>> doBanco = jdbc.sql("""
                        SELECT r.pergunta_id, q.ordem, r.alternativa, r.valor, r.unidade, r.base_respondentes
                        FROM pesquisa_resultado r JOIN pesquisa_pergunta q ON q.id = r.pergunta_id
                        WHERE q.parceria_id = :p AND q.status = 'APROVADA' ORDER BY q.ordem, r.id
                        """).param("p", amarelo).query().listOfRows();
        assertThat(doBanco).isNotEmpty();
        List<Map<String, Object>> resultados = lerLista(resposta, "$.resultados");
        assertThat(resultados).hasSameSizeAs(doBanco);
        for (int i = 0; i < doBanco.size(); i++) {
            Map<String, Object> esperado = doBanco.get(i);
            Map<String, Object> recebido = resultados.get(i);
            assertThat(((Number) recebido.get("perguntaId")).longValue()).isEqualTo(((Number) esperado.get("pergunta_id")).longValue());
            assertThat(recebido.get("ordem")).isEqualTo(((Number) esperado.get("ordem")).intValue());
            assertThat(recebido.get("alternativa")).isEqualTo(esperado.get("alternativa"));
            assertThat(new BigDecimal(String.valueOf(recebido.get("valor")))).isEqualByComparingTo((BigDecimal) esperado.get("valor"));
            assertThat(recebido.get("unidade")).isEqualTo(esperado.get("unidade"));
            assertThat(recebido.get("baseRespondentes")).as("a base de respondentes vai em todo resultado")
                    .isEqualTo(((Number) esperado.get("base_respondentes")).intValue());
        }
    }

    @Test
    void resultadoDePerguntaQueNaoEstaAprovadaNaoAparece() {
        long amarelo = parceria("Cliente Amarelo");
        long segunda = perguntaId(amarelo, 2);
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE id = :id").param("id", segunda).update();
        long daSegunda = jdbc.sql("SELECT count(*) FROM pesquisa_resultado WHERE pergunta_id = :id").param("id", segunda)
                .query(Long.class).single();
        long total = jdbc.sql("SELECT count(*) FROM pesquisa_resultado WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single();
        assertThat(daSegunda).as("a amostra tem resultado da pergunta 2").isPositive();

        MvcTestResult resposta = verPesquisa(entrar(AMARELO), amarelo);

        assertThat(lerLista(resposta, "$.perguntas[*].status")).containsExactly("APROVADA", "ENVIADA", "APROVADA");
        assertThat(lerLista(resposta, "$.resultados")).hasSize((int) (total - daSegunda));
        assertThat(lerLista(resposta, "$.resultados[*].ordem")).doesNotContain(2);
    }

    @Test
    void rbsVeAPesquisaDeQualquerParceriaEAgenciaDoAmareloTambem() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult doAmarelo = verPesquisa(rbs, amarelo);
        MvcTestResult doRoxo = verPesquisa(rbs, roxo);

        assertThat(doAmarelo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(doAmarelo, "$.abaLiberada")).isEqualTo(true);
        assertThat(lerLista(doAmarelo, "$.perguntas")).hasSize(3);
        assertThat(doRoxo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(doRoxo, "$.abaLiberada")).as("a RBS vê a aba fechada, mas é avisada").isEqualTo(false);
        assertThat(lerLista(doRoxo, "$.perguntas")).isEmpty();
        assertThat(lerLista(doRoxo, "$.resultados")).isEmpty();
        assertThat(verPesquisa(rbs, 999_999L)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(verPesquisa(entrar(AGENCIA_DO_AMARELO), amarelo)).hasStatus(HttpStatus.OK);
    }

    @Test
    void semPesquisaDaAtivacaoAbertaOCampoPesquisaVemNuloEAPerguntaFicaSemLigacao() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);
        jdbc.sql("DELETE FROM pesquisa WHERE escopo = 'ATIVACAO'").update();
        SessaoDeApi sessao = entrar(AMARELO);

        MvcTestResult vazia = verPesquisa(sessao, amarelo);
        assertThat(vazia).hasStatus(HttpStatus.OK);
        assertThat(lerJson(vazia, "$.pesquisa")).isNull();
        assertThat(lerLista(vazia, "$.perguntas")).isEmpty();

        // A marca já pode mandar as perguntas; a carga de resultados liga cada uma à pesquisa quando a RBS a abrir.
        MvcTestResult enviada = enviarPerguntas(sessao, amarelo, perguntas(P1, P2));
        assertThat(enviada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(enviada, "$.pesquisa")).isNull();
        assertThat(lerLista(enviada, "$.perguntas[*].status")).containsExactly("ENVIADA", "ENVIADA");
        assertThat(jdbc.sql("SELECT count(*) FROM pesquisa_pergunta WHERE parceria_id = :p AND pesquisa_id IS NULL")
                .param("p", amarelo).query(Long.class).single()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Rota 24: o patrocinador envia as perguntas
    // ---------------------------------------------------------------------

    @Test
    void amareloEnviaTresPerguntasEElasFicamEnviadasEComHistorico() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);
        long pesquisa = jdbc.sql("SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'").query(Long.class).single();
        SessaoDeApi sessao = entrar(AMARELO);

        MvcTestResult resposta = enviarPerguntas(sessao, amarelo, perguntas(P1, P2, P3));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.perguntas[*].ordem")).containsExactly(1, 2, 3);
        assertThat(lerLista(resposta, "$.perguntas[*].status")).containsOnly("ENVIADA");
        assertThat(lerLista(resposta, "$.perguntas[*].texto")).containsExactly(P1, P2, P3);
        assertThat(lerJson(resposta, "$.perguntas[0].enviadaPor.nome")).isEqualTo("Marketing Amarelo");
        assertThat((String) lerJson(resposta, "$.perguntas[0].enviadaEm")).endsWith("-03:00");
        assertThat(lerLista(resposta, "$.resultados")).as("sem aprovação não há resultado").isEmpty();
        assertThat(lerJson(resposta, "$.abaLiberada")).isEqualTo(true);

        // No banco: ENVIADA, ligada à pesquisa da edição, enviada pelo Amarelo.
        var linhas = jdbc.sql("SELECT status, pesquisa_id, enviada_por FROM pesquisa_pergunta WHERE parceria_id = :p ORDER BY ordem")
                .param("p", amarelo).query().listOfRows();
        assertThat(linhas).hasSize(3).allSatisfy(l -> {
            assertThat(l.get("status")).isEqualTo("ENVIADA");
            assertThat(((Number) l.get("pesquisa_id")).longValue()).isEqualTo(pesquisa);
            assertThat(((Number) l.get("enviada_por")).longValue()).isEqualTo(usuarioId(AMARELO));
        });

        // [HISTORICO] Uma linha EDICAO por pergunta, com o texto novo e o autor.
        var historico = jdbc.sql("""
                        SELECT usuario_id, entidade_id, valor_novo, host(ip) AS ip, user_agent FROM historico
                        WHERE acao = 'EDICAO' AND entidade = 'pesquisa_pergunta' AND parceria_id = :p ORDER BY entidade_id
                        """).param("p", amarelo).query().listOfRows();
        assertThat(historico).hasSize(3);
        assertThat(historico).extracting(l -> l.get("valor_novo")).containsExactlyInAnyOrder(P1, P2, P3);
        assertThat(historico).allSatisfy(l -> {
            assertThat(((Number) l.get("usuario_id")).longValue()).isEqualTo(usuarioId(AMARELO));
            assertThat(l.get("ip")).isNotNull();
            assertThat(l.get("user_agent")).isEqualTo(AGENTE);
        });
    }

    @Test
    void fluxoCompletoEnviaAprovaPedeAjusteEBloqueiaAAprovada() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);
        SessaoDeApi marca = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);
        assertThat(enviarPerguntas(marca, amarelo, perguntas(P1, P2, P3))).hasStatus(HttpStatus.OK);
        long p1 = perguntaId(amarelo, 1);
        long p2 = perguntaId(amarelo, 2);
        long p3 = perguntaId(amarelo, 3);

        // A RBS aprova a pergunta 1 e pede ajuste na 2.
        MvcTestResult aprovada = decidir(rbs, p1, "APROVAR", null);
        MvcTestResult devolvida = decidir(rbs, p2, "PEDIR_AJUSTE", "Troque \\\"bom\\\" por uma escala de 0 a 10.");
        assertThat(aprovada).hasStatus(HttpStatus.OK);
        assertThat(numero(aprovada, "$.id")).isEqualTo(p1);
        assertThat(lerJson(aprovada, "$.status")).isEqualTo("APROVADA");
        assertThat(lerJson(aprovada, "$.observacaoRbs")).isNull();
        assertThat(devolvida).hasStatus(HttpStatus.OK);
        assertThat(lerJson(devolvida, "$.status")).isEqualTo("RASCUNHO");
        assertThat(lerJson(devolvida, "$.observacaoRbs")).isEqualTo("Troque \"bom\" por uma escala de 0 a 10.");
        assertThat(statusNoBanco(p1)).isEqualTo("APROVADA");
        assertThat(statusNoBanco(p2)).isEqualTo("RASCUNHO");
        assertThat(statusNoBanco(p3)).isEqualTo("ENVIADA");

        // A marca vê a decisão: a observação aparece só na pergunta devolvida.
        MvcTestResult vista = verPesquisa(marca, amarelo);
        assertThat(lerLista(vista, "$.perguntas[*].status")).containsExactly("APROVADA", "RASCUNHO", "ENVIADA");
        assertThat(lerJson(vista, "$.perguntas[1].observacaoRbs")).isEqualTo("Troque \"bom\" por uma escala de 0 a 10.");
        assertThat(lerJson(vista, "$.perguntas[0].observacaoRbs")).isNull();
        assertThat(lerJson(vista, "$.perguntas[2].observacaoRbs")).isNull();

        // [HISTORICO] A decisão grava ALTERACAO_STATUS com o status anterior e o novo e a observação em "detalhe".
        var devolucao = jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe, host(ip) AS ip FROM historico
                        WHERE acao = 'ALTERACAO_STATUS' AND entidade = 'pesquisa_pergunta' AND entidade_id = :id
                        """).param("id", p2).query().singleRow();
        assertThat(((Number) devolucao.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) devolucao.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(devolucao.get("valor_anterior")).isEqualTo("ENVIADA");
        assertThat(devolucao.get("valor_novo")).isEqualTo("RASCUNHO");
        assertThat(devolucao.get("detalhe")).isEqualTo("Troque \"bom\" por uma escala de 0 a 10.");
        assertThat(devolucao.get("ip")).isNotNull();
        assertThat(contarHistorico("ALTERACAO_STATUS", amarelo)).isEqualTo(2);

        // A marca tenta mexer na aprovada: 409 com a frase do contrato, e NADA muda (nem a pergunta 2, que estava certa).
        long edicoesAntes = contarHistorico("EDICAO", amarelo);
        MvcTestResult alterada = enviarPerguntas(marca, amarelo, perguntas("Texto novo da pergunta um, que a RBS não aprovou", P2 + " Mais uma frase.", P3));
        assertThat(alterada).hasStatus(HttpStatus.CONFLICT);
        assertThat(alterada.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(alterada, "$.detail")).isEqualTo(FRASE_DA_P1);
        assertThat(lerJson(alterada, "$.title")).isEqualTo("Conflito");
        assertThat(lerJson(alterada, "$.type")).isEqualTo("about:blank");
        assertThat(lerJson(alterada, "$.instance")).isEqualTo("/api/parcerias/" + amarelo + "/pesquisa/perguntas");
        assertThat(textoNoBanco(p1)).isEqualTo(P1);
        assertThat(textoNoBanco(p2)).isEqualTo(P2);
        assertThat(statusNoBanco(p2)).isEqualTo("RASCUNHO");

        // Retirar a aprovada da lista também é 409.
        MvcTestResult retirada = enviarPerguntas(marca, amarelo, "{\"perguntas\":[{\"ordem\":2,\"texto\":\"" + P2 + "\"}]}");
        assertThat(retirada).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(retirada, "$.detail")).isEqualTo(FRASE_DA_P1);
        assertThat(statusNoBanco(p1)).isEqualTo("APROVADA");
        assertThat(contarHistorico("EDICAO", amarelo)).as("recusa não grava histórico").isEqualTo(edicoesAntes);

        // A marca corrige a devolvida e reenvia a lista inteira: a aprovada e a enviada ficam como estão.
        String corrigida = "De 0 a 10, quanto você recomendaria a marca a um amigo ou colega?";
        MvcTestResult reenviada = enviarPerguntas(marca, amarelo, perguntas(P1, corrigida, P3));
        assertThat(reenviada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(reenviada, "$.perguntas[*].status")).containsExactly("APROVADA", "ENVIADA", "ENVIADA");
        assertThat(lerLista(reenviada, "$.perguntas[*].texto")).containsExactly(P1, corrigida, P3);
        assertThat(lerJson(reenviada, "$.perguntas[1].observacaoRbs")).as("reenviada, o recado antigo sai").isNull();
        assertThat(textoNoBanco(p2)).isEqualTo(corrigida);
        assertThat(contarHistorico("EDICAO", amarelo)).as("só a pergunta 2 mudou").isEqualTo(edicoesAntes + 1);
    }

    @Test
    void listaIgualAoQueJaEstaNoBancoNaoMudaNadaENaoGravaHistorico() {
        long amarelo = parceria("Cliente Amarelo");
        long antes = contarHistorico("EDICAO", amarelo);

        MvcTestResult resposta = enviarPerguntas(entrar(AMARELO), amarelo, perguntas(P1, P2, P3));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.perguntas[*].status")).containsOnly("APROVADA");
        assertThat(contarHistorico("EDICAO", amarelo)).isEqualTo(antes);
    }

    @Test
    void perguntaEnviadaPodeSerAlteradaOuRetiradaAteAReceberADecisao() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);
        SessaoDeApi marca = entrar(AMARELO);
        enviarPerguntas(marca, amarelo, perguntas(P1, P2, P3));
        long p2 = perguntaId(amarelo, 2);

        // Muda a 2, tira a 3 e deixa a 1: continua ENVIADA e a 3 some.
        String nova = "Qual nota de 0 a 10 você daria para a ativação da marca?";
        MvcTestResult resposta = enviarPerguntas(marca, amarelo, "{\"perguntas\":[{\"ordem\":1,\"texto\":\"" + P1
                + "\"},{\"ordem\":2,\"texto\":\"" + nova + "\"}]}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.perguntas[*].ordem")).containsExactly(1, 2);
        assertThat(lerLista(resposta, "$.perguntas[*].status")).containsExactly("ENVIADA", "ENVIADA");
        assertThat(textoNoBanco(p2)).isEqualTo(nova);
        assertThat(jdbc.sql("SELECT count(*) FROM pesquisa_pergunta WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single()).isEqualTo(2);
        var retirada = jdbc.sql("""
                        SELECT valor_anterior, valor_novo, detalhe FROM historico
                        WHERE acao = 'EDICAO' AND entidade = 'pesquisa_pergunta' AND parceria_id = :p AND valor_anterior = :t
                        """).param("p", amarelo).param("t", P3).query().singleRow();
        assertThat(retirada.get("valor_novo")).isNull();
        assertThat((String) retirada.get("detalhe")).contains("retirada");
    }

    @Test
    void validacaoDevolve422ComOsCamposENadaMuda() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi marca = entrar(AMARELO);
        String curto = "{\"perguntas\":[{\"ordem\":1,\"texto\":\"Curta\"}]}";
        String longo = "{\"perguntas\":[{\"ordem\":1,\"texto\":\"" + "a".repeat(301) + "\"}]}";
        String branco = "{\"perguntas\":[{\"ordem\":1,\"texto\":\"          \"}]}";
        String ordemFora = "{\"perguntas\":[{\"ordem\":4,\"texto\":\"" + P1 + "\"}]}";
        String ordemRepetida = "{\"perguntas\":[{\"ordem\":1,\"texto\":\"" + P1 + "\"},{\"ordem\":1,\"texto\":\"" + P2 + "\"}]}";

        // Quatro perguntas: 422 em "perguntas".
        MvcTestResult quatro = enviarPerguntas(marca, amarelo, perguntas(P1, P2, P3, "Qual é o seu nome completo, por favor?"));
        assertThat(quatro).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quatro.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(quatro, "$.title")).isEqualTo("Dados inválidos");
        assertThat(lerJson(quatro, "$.campos.perguntas")).isEqualTo("Envie de 1 a 3 perguntas.");

        assertThat(enviarPerguntas(marca, amarelo, "{\"perguntas\":[]}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(enviarPerguntas(marca, amarelo, "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        // Texto curto, longo ou em branco: 422 apontando o campo do item.
        for (String corpoInvalido : List.of(curto, longo, branco)) {
            MvcTestResult r = enviarPerguntas(marca, amarelo, corpoInvalido);
            assertThat(r).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(r, "$.campos['perguntas[0].texto']")).isEqualTo("A pergunta precisa ter de 10 a 300 caracteres.");
        }

        MvcTestResult fora = enviarPerguntas(marca, amarelo, ordemFora);
        assertThat(fora).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(fora, "$.campos['perguntas[0].ordem']")).isEqualTo("A ordem da pergunta vai de 1 a 3.");

        MvcTestResult repetida = enviarPerguntas(marca, amarelo, ordemRepetida);
        assertThat(repetida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(repetida, "$.campos['perguntas[1].ordem']")).isEqualTo("A ordem 1 já foi usada por outra pergunta da lista.");

        assertThat(enviarPerguntas(marca, amarelo, "isto não é json")).hasStatus(HttpStatus.BAD_REQUEST);

        // Nenhuma tentativa mexeu nas perguntas aprovadas da amostra.
        assertThat(jdbc.sql("SELECT string_agg(status || ordem, ',' ORDER BY ordem) FROM pesquisa_pergunta WHERE parceria_id = :p")
                .param("p", amarelo).query(String.class).single()).isEqualTo("APROVADA1,APROVADA2,APROVADA3");
    }

    @Test
    void textoComEspacosNasPontasValeSemOsEspacos() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);

        MvcTestResult resposta = enviarPerguntas(entrar(AMARELO), amarelo,
                "{\"perguntas\":[{\"ordem\":3,\"texto\":\"   " + P1 + "   \"}]}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.perguntas[0].texto")).isEqualTo(P1);
        assertThat(lerJson(resposta, "$.perguntas[0].ordem")).as("a ordem 3 sozinha vale: o contrato não exige sequência").isEqualTo(3);
    }

    // ---------------------------------------------------------------------
    // Rota 25: a RBS decide
    // ---------------------------------------------------------------------

    @Test
    void decisaoQueNaoCabeDevolve409422Ou404() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi rbs = entrar(RBS);
        long aprovada = perguntaId(amarelo, 1);

        // Aprovar vale só para a pergunta ENVIADA: a APROVADA da amostra devolve 409 (pedir ajuste nela é a reabertura, outro teste).
        MvcTestResult jaAprovada = decidir(rbs, aprovada, "APROVAR", null);
        assertThat(jaAprovada).hasStatus(HttpStatus.CONFLICT);
        assertThat(jaAprovada.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat((String) lerJson(jaAprovada, "$.detail")).contains("pergunta 1").contains("aprovada");
        assertThat(statusNoBanco(aprovada)).isEqualTo("APROVADA");

        // A pergunta devolvida (RASCUNHO) não recebe decisão nenhuma até a marca enviá-la de novo.
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE id = :id").param("id", aprovada).update();
        assertThat(decidir(rbs, aprovada, "PEDIR_AJUSTE", "Mude o texto.")).hasStatus(HttpStatus.OK);
        assertThat(decidir(rbs, aprovada, "APROVAR", null)).hasStatus(HttpStatus.CONFLICT);
        MvcTestResult ajusteEmRascunho = decidir(rbs, aprovada, "PEDIR_AJUSTE", "Mude o texto de novo.");
        assertThat(ajusteEmRascunho).hasStatus(HttpStatus.CONFLICT);
        assertThat((String) lerJson(ajusteEmRascunho, "$.detail")).contains("pergunta 1").contains("rascunho");

        // Pergunta que não existe.
        assertThat(decidir(rbs, 999_999L, "APROVAR", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void pedirAjusteExigeObservacaoEDecisaoPrecisaSerValida() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE parceria_id = :p").param("p", amarelo).update();
        long pergunta = perguntaId(amarelo, 1);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult semObservacao = decidir(rbs, pergunta, "PEDIR_AJUSTE", null);
        MvcTestResult observacaoEmBranco = decidir(rbs, pergunta, "PEDIR_AJUSTE", "     ");
        MvcTestResult decisaoInvalida = decidir(rbs, pergunta, "TALVEZ", "Não sei.");
        MvcTestResult semDecisao = rbs.patch("/api/pesquisa/perguntas/" + pergunta + "/status", "{}");
        MvcTestResult observacaoLonga = decidir(rbs, pergunta, "PEDIR_AJUSTE", "a".repeat(1001));

        for (MvcTestResult r : List.of(semObservacao, observacaoEmBranco)) {
            assertThat(r).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(lerJson(r, "$.title")).isEqualTo("Dados inválidos");
            assertThat(lerJson(r, "$.instance")).isEqualTo("/api/pesquisa/perguntas/" + pergunta + "/status");
            assertThat(lerJson(r, "$.campos.observacao")).isEqualTo("Diga à marca o que precisa ser ajustado na pergunta.");
        }
        assertThat(decisaoInvalida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(decisaoInvalida, "$.campos.decisao")).isEqualTo("Decisão inválida. Use APROVAR ou PEDIR_AJUSTE.");
        assertThat(semDecisao).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semDecisao, "$.campos.decisao")).isNotNull();
        assertThat(observacaoLonga).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(observacaoLonga, "$.campos.observacao")).isNotNull();
        assertThat(statusNoBanco(pergunta)).as("nenhuma recusa mudou a pergunta").isEqualTo("ENVIADA");
        assertThat(contarHistorico("ALTERACAO_STATUS", amarelo)).isZero();
    }

    @Test
    void aprovarComObservacaoOpcionalGuardaOTextoNoHistoricoEMostraEnquantoAprovada() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE id = :id").param("id", perguntaId(amarelo, 2)).update();
        long pergunta = perguntaId(amarelo, 2);

        MvcTestResult resposta = decidir(entrar(RBS), pergunta, "APROVAR", "Ótima pergunta.");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("APROVADA");
        assertThat(lerJson(resposta, "$.observacaoRbs")).isEqualTo("Ótima pergunta.");
        assertThat(jdbc.sql("SELECT detalhe FROM historico WHERE acao = 'ALTERACAO_STATUS' AND entidade = 'pesquisa_pergunta' "
                + "AND entidade_id = :id").param("id", pergunta).query(String.class).single()).isEqualTo("Ótima pergunta.");
    }

    // ---------------------------------------------------------------------
    // Rota 25: a RBS reabre uma pergunta aprovada (PEDIR_AJUSTE) para a marca poder mudá-la
    // ---------------------------------------------------------------------

    long resultadosDaPergunta(long perguntaId) {
        return jdbc.sql("SELECT count(*) FROM pesquisa_resultado WHERE pergunta_id = :id").param("id", perguntaId)
                .query(Long.class).single();
    }

    @Test
    void rbsReabreUmaPerguntaAprovadaEAMarcaVoltaAPoderEditar() {
        long amarelo = parceria("Cliente Amarelo");
        long p1 = perguntaId(amarelo, 1);
        long p2 = perguntaId(amarelo, 2);
        long p3 = perguntaId(amarelo, 3);
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        assertThat(resultadosDaPergunta(p2)).as("a amostra tem resultados nesta pergunta").isPositive();
        long resultadosGuardados = resultadosDaPergunta(p2);

        MvcTestResult reaberta = decidir(rbs, p2, "PEDIR_AJUSTE", "Use uma escala de 0 a 10 com legenda.");

        assertThat(reaberta).hasStatus(HttpStatus.OK);
        assertThat(numero(reaberta, "$.id")).isEqualTo(p2);
        assertThat(lerJson(reaberta, "$.status")).as("volta ao estado em que a marca edita").isEqualTo("RASCUNHO");
        assertThat(lerJson(reaberta, "$.observacaoRbs")).isEqualTo("Use uma escala de 0 a 10 com legenda.");
        assertThat(lerJson(reaberta, "$.texto")).as("o texto continua o mesmo até a marca mudar").isEqualTo(P2);
        assertThat(statusNoBanco(p1)).isEqualTo("APROVADA");
        assertThat(statusNoBanco(p2)).isEqualTo("RASCUNHO");
        assertThat(statusNoBanco(p3)).isEqualTo("APROVADA");

        // A marca vê a pergunta devolvida com o recado; os resultados dela somem enquanto não for aprovada de novo, mas ficam guardados.
        MvcTestResult vista = verPesquisa(marca, amarelo);
        assertThat(lerLista(vista, "$.perguntas[*].status")).containsExactly("APROVADA", "RASCUNHO", "APROVADA");
        assertThat(lerJson(vista, "$.perguntas[1].observacaoRbs")).isEqualTo("Use uma escala de 0 a 10 com legenda.");
        assertThat(lerLista(vista, "$.resultados[*].perguntaId").stream().map(id -> ((Number) id).longValue()))
                .contains(p1, p3).doesNotContain(p2);
        assertThat(resultadosDaPergunta(p2)).isEqualTo(resultadosGuardados);

        // [HISTORICO] Fica registrado como mudança de status da pergunta, de APROVADA para RASCUNHO, com o recado da RBS.
        var linha = jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe FROM historico
                        WHERE acao = 'ALTERACAO_STATUS' AND entidade = 'pesquisa_pergunta' AND entidade_id = :id
                        """).param("id", p2).query().singleRow();
        assertThat(((Number) linha.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) linha.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(linha.get("valor_anterior")).isEqualTo("APROVADA");
        assertThat(linha.get("valor_novo")).isEqualTo("RASCUNHO");
        assertThat(linha.get("detalhe")).isEqualTo("Use uma escala de 0 a 10 com legenda.");

        // A marca muda só o texto da reaberta; as aprovadas seguem como estão. A pergunta volta a esperar a RBS, sem o recado antigo.
        String novoTexto = "De 0 a 10, quanto você recomendaria a marca (0 = nunca, 10 = com certeza)?";
        MvcTestResult reenviada = enviarPerguntas(marca, amarelo, perguntas(P1, novoTexto, P3));
        assertThat(reenviada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(reenviada, "$.perguntas[*].status")).containsExactly("APROVADA", "ENVIADA", "APROVADA");
        assertThat(lerJson(reenviada, "$.perguntas[1].texto")).isEqualTo(novoTexto);
        assertThat(lerJson(reenviada, "$.perguntas[1].observacaoRbs")).isNull();
        assertThat(textoNoBanco(p1)).isEqualTo(P1);
        assertThat(textoNoBanco(p3)).isEqualTo(P3);
        assertThat(contarHistorico("EDICAO", amarelo)).as("só a pergunta 2 mudou").isEqualTo(1);

        // A RBS aprova a nova versão; a partir daí a marca não altera mais (409), como antes.
        assertThat(decidir(rbs, p2, "APROVAR", null)).hasStatus(HttpStatus.OK);
        assertThat(statusNoBanco(p2)).isEqualTo("APROVADA");
        MvcTestResult tentativa = enviarPerguntas(marca, amarelo, perguntas(P1, P2, P3));
        assertThat(tentativa).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(tentativa, "$.detail")).isEqualTo("A pergunta 2 já foi aprovada pela RBS e não pode mais ser alterada.");
    }

    @Test
    void perguntaReabertaPodeVoltarSemMudarOTextoEOsResultadosVoltamAoSerAprovadaDeNovo() {
        long amarelo = parceria("Cliente Amarelo");
        long p1 = perguntaId(amarelo, 1);
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        assertThat(decidir(rbs, p1, "PEDIR_AJUSTE", "Releia com calma.")).hasStatus(HttpStatus.OK);

        MvcTestResult reenviada = enviarPerguntas(marca, amarelo, perguntas(P1, P2, P3));

        assertThat(reenviada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(reenviada, "$.perguntas[*].status")).containsExactly("ENVIADA", "APROVADA", "APROVADA");
        assertThat(jdbc.sql("SELECT detalhe FROM historico WHERE acao = 'EDICAO' AND entidade_id = :id").param("id", p1)
                .query(String.class).single()).contains("devolvida e enviada de novo");
        assertThat(decidir(rbs, p1, "APROVAR", null)).hasStatus(HttpStatus.OK);
        assertThat(lerLista(verPesquisa(marca, amarelo), "$.resultados[*].perguntaId").stream().map(id -> ((Number) id).longValue()))
                .contains(p1);
    }

    @Test
    void reabrirExigeObservacaoENadaMudaSemEla() {
        long amarelo = parceria("Cliente Amarelo");
        long p1 = perguntaId(amarelo, 1);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult semObservacao = decidir(rbs, p1, "PEDIR_AJUSTE", null);
        MvcTestResult emBranco = decidir(rbs, p1, "PEDIR_AJUSTE", "   ");

        for (MvcTestResult r : List.of(semObservacao, emBranco)) {
            assertThat(r).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(r, "$.campos.observacao")).isEqualTo("Diga à marca o que precisa ser ajustado na pergunta.");
        }
        assertThat(statusNoBanco(p1)).isEqualTo("APROVADA");
        assertThat(contarHistorico("ALTERACAO_STATUS", amarelo)).isZero();
    }

    @Test
    void marcaNaoReabreNemAlteraNemRetiraPerguntaAprovada() {
        long amarelo = parceria("Cliente Amarelo");
        long p1 = perguntaId(amarelo, 1);
        SessaoDeApi marca = entrar(AMARELO);

        // A marca não reabre (403, rota da RBS) e não altera nem retira a aprovada (409): só a RBS reabre.
        assertThat(decidir(marca, p1, "PEDIR_AJUSTE", "Quero mudar.")).hasStatus(HttpStatus.FORBIDDEN);
        MvcTestResult alterar = enviarPerguntas(marca, amarelo, perguntas("Texto novo para a pergunta um, sem a RBS", P2, P3));
        MvcTestResult retirar = enviarPerguntas(marca, amarelo, perguntas(P1, P2));
        assertThat(alterar).hasStatus(HttpStatus.CONFLICT);
        assertThat(retirar).hasStatus(HttpStatus.CONFLICT);
        assertThat(statusNoBanco(p1)).isEqualTo("APROVADA");
        assertThat(textoNoBanco(p1)).isEqualTo(P1);
    }

    @Test
    void perguntaReabertaQueJaTeveResultadosNaoSaiDaListaPorqueApagariaOsResultados() {
        long amarelo = parceria("Cliente Amarelo");
        long p3 = perguntaId(amarelo, 3);
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long resultados = resultadosDaPergunta(p3);
        assertThat(resultados).isPositive();
        assertThat(decidir(rbs, p3, "PEDIR_AJUSTE", "Pergunta confusa.")).hasStatus(HttpStatus.OK);

        MvcTestResult retirar = enviarPerguntas(marca, amarelo, perguntas(P1, P2));

        assertThat(retirar).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(retirar, "$.detail")).isEqualTo("A pergunta 3 já tem resultados carregados e não pode ser retirada da lista. "
                + "Altere o texto dela ou fale com a RBS.");
        assertThat(statusNoBanco(p3)).isEqualTo("RASCUNHO");
        assertThat(resultadosDaPergunta(p3)).isEqualTo(resultados);
    }

    // ---------------------------------------------------------------------
    // Permissão: o que é só da RBS e o que é só da marca
    // ---------------------------------------------------------------------

    @Test
    void rbsTentandoOPutRecebe403ENadaMuda() {
        long amarelo = parceria("Cliente Amarelo");
        apagarPerguntas(amarelo);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = enviarPerguntas(rbs, amarelo, perguntas(P1, P2, P3));

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Acesso negado");
        assertThat(jdbc.sql("SELECT count(*) FROM pesquisa_pergunta WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single()).isZero();
        // Nem a RBS nem uma parceria que não existe mudam isso: a RBS recebe 403 antes de qualquer outra coisa.
        assertThat(enviarPerguntas(rbs, 999_999L, perguntas(P1))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void patrocinadorTentandoOPatchRecebe403ENadaMuda() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE parceria_id = :p").param("p", amarelo).update();
        long pergunta = perguntaId(amarelo, 1);

        // Nem a marca dona da pergunta, nem a agência dela, nem outra marca: a decisão é só da RBS.
        for (String email : List.of(AMARELO, AGENCIA_DO_AMARELO, ROXO)) {
            SessaoDeApi sessao = entrar(email);
            MvcTestResult resposta = decidir(sessao, pergunta, "APROVAR", null);
            assertThat(resposta).as(email).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(decidir(sessao, 999_999L, "APROVAR", null)).as(email + ", id que não existe").hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(statusNoBanco(pergunta)).isEqualTo("ENVIADA");
        assertThat(contarHistorico("ALTERACAO_STATUS", amarelo)).isZero();
    }

    // ---------------------------------------------------------------------
    // Isolamento e aba fechada: o Roxo (Subcota G) não tem a aba Pesquisa e não enxerga o Amarelo
    // ---------------------------------------------------------------------

    @Test
    void roxoComAPesquisaFechadaRecebe403NaPropriaParceria() {
        long roxo = parceria("Cliente Roxo");
        SessaoDeApi sessao = entrar(ROXO);

        MvcTestResult ver = verPesquisa(sessao, roxo);
        MvcTestResult enviar = enviarPerguntas(sessao, roxo, perguntas(P1));

        for (MvcTestResult r : List.of(ver, enviar)) {
            assertThat(r).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(lerJson(r, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM pesquisa_pergunta WHERE parceria_id = :p").param("p", roxo)
                .query(Long.class).single()).isZero();
    }

    @Test
    void abaLiberadaMasInvisivelOuSoLiberadaDepoisContaComoAbertaSoSeAsDuasCoisas() {
        long roxo = parceria("Cliente Roxo");
        SessaoDeApi sessao = entrar(ROXO);

        // A RBS libera a aba para o Roxo: passa a valer.
        jdbc.sql("UPDATE parceria_aba SET liberada = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')")
                .param("p", roxo).update();
        assertThat(verPesquisa(sessao, roxo)).hasStatus(HttpStatus.OK);

        // Liberada, mas escondida do menu (visivel = false): para a API, continua fechada.
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')")
                .param("p", roxo).update();
        assertThat(verPesquisa(sessao, roxo)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(enviarPerguntas(sessao, roxo, perguntas(P1))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void roxoPedindoAPesquisaEAsPerguntasDoAmareloRecebe404IgualAParceriaQueNaoExiste() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(ROXO);
        long antes = contarHistorico("EDICAO", amarelo);

        MvcTestResult ver = verPesquisa(sessao, amarelo);
        MvcTestResult enviar = enviarPerguntas(sessao, amarelo, perguntas("Pergunta plantada por outra marca no Amarelo"));
        MvcTestResult inexistente = verPesquisa(sessao, 999_999L);

        for (MvcTestResult r : List.of(ver, enviar)) {
            assertThat(r).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(lerJson(r, "$.detail")).as("mesma frase de uma parceria que não existe")
                    .isEqualTo(lerJson(inexistente, "$.detail"));
            assertThat(corpo(r)).doesNotContain("Amarelo").doesNotContain(P1);
        }
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contarHistorico("EDICAO", amarelo)).isEqualTo(antes);
        assertThat(textoNoBanco(perguntaId(amarelo, 1))).isEqualTo(P1);
    }

    @Test
    void acessoRevogadoDerrubaAPesquisaNaHora() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = entrar(AGENCIA_DO_AMARELO);
        assertThat(verPesquisa(sessao, amarelo)).hasStatus(HttpStatus.OK);

        jdbc.sql("DELETE FROM parceria_usuario WHERE parceria_id = :p AND usuario_id = :u")
                .param("p", amarelo).param("u", usuarioId(AGENCIA_DO_AMARELO)).update();

        // A agência só tinha este vínculo: perdeu o último, então a sessão termina (401) em qualquer rota.
        MvcTestResult ver = verPesquisa(sessao, amarelo);
        assertThat(ver).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(lerJson(ver, "$.detail")).isEqualTo("Sua sessão terminou. Entre de novo.");
        assertThat(sessao.sessao().isInvalid()).isTrue();
    }

    @Test
    void semSessaoDevolve401ESemTokenCsrfDevolve403() {
        long amarelo = parceria("Cliente Amarelo");
        long pergunta = perguntaId(amarelo, 1);
        SessaoDeApi marca = entrar(AMARELO);

        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo + "/pesquisa")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).put("/api/parcerias/" + amarelo + "/pesquisa/perguntas", perguntas(P1)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).patch("/api/pesquisa/perguntas/" + pergunta + "/status", "{\"decisao\":\"APROVAR\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        // Logado, mas sem o cabeçalho X-XSRF-TOKEN, o PUT é recusado.
        MvcTestResult semToken = marca.semCsrf(HttpMethod.PUT,
                "/api/parcerias/" + amarelo + "/pesquisa/perguntas", perguntas(P1, P2, P3));
        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
    }
}
