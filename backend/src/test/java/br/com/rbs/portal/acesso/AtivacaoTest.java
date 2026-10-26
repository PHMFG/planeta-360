package br.com.rbs.portal.acesso;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.ApoioDeTeste.lerTexto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o primeiro acesso (POST /api/auth/ativar) de ponta a ponta: a RBS cria a parceria e recebe o código, o patrocinador
 * ativa e entra. Atende os itens 3 e 5 da Entrega 2. [PERMISSAO] [HISTORICO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class AtivacaoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String NOVO = "marketing@novo.example";
    private static final String FRASE = "Código inválido ou vencido. Peça um novo à RBS.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long eventoDe(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    /** A RBS cria a parceria de uma marca nova (na edição pedida) com um e-mail; devolve a resposta 201. */
    MvcTestResult criarParceria(String marca, int edicao, String email) {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult resposta = rbs.post("/api/parcerias", """
                {"patrocinador":{"nome":"%s","segmento":"Varejo"},"eventoId":%d,"cotaCodigo":"SUBCOTA",
                 "nomeProduto":"Cota Ativação Arena","valorInvestimento":100000.00,"baseValor":"LIQUIDO",
                 "emails":[{"email":"%s","vinculo":"MARCA"}]}
                """.formatted(marca, eventoDe(edicao), email));
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return resposta;
    }

    String codigoDe(MvcTestResult criada) {
        return lerTexto(criada, "$.emailsAutorizados[0].codigoAtivacao");
    }

    MvcTestResult ativar(String email, String codigo, String senha, String nome) {
        String nomeJson = nome == null ? "" : ",\"nome\":\"" + nome + "\"";
        return SessaoDeApi.anonima(mvc).post("/api/auth/ativar",
                "{\"email\":\"" + email + "\",\"codigo\":\"" + codigo + "\",\"senha\":\"" + senha + "\"" + nomeJson + "}");
    }

    MvcTestResult ativar(String email, String codigo) {
        return ativar(email, codigo, SessaoDeApi.SENHA, null);
    }

    int tentativasDoCodigo(String email) {
        return jdbc.sql("""
                        SELECT ca.tentativas FROM codigo_ativacao ca JOIN email_autorizado ea ON ea.id = ca.email_autorizado_id
                        WHERE lower(ea.email) = lower(:email) ORDER BY ca.id DESC LIMIT 1
                        """).param("email", email).query(Integer.class).single();
    }

    long usuarios(String email) {
        return jdbc.sql("SELECT count(*) FROM usuario WHERE lower(email) = lower(:email)").param("email", email)
                .query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // O fluxo completo
    // ---------------------------------------------------------------------

    @Test
    void fluxoCompletoRbsCriaPatrocinadorAtivaEEntraVendoSoAParceriaNova() {
        MvcTestResult criada = criarParceria("Cliente Novo", 2027, NOVO);
        long parceriaId = ((Number) lerJson(criada, "$.id")).longValue();
        String codigo = codigoDe(criada);
        assertThat(codigo).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");   // sem 0, O, 1 e I
        assertThat(lerJson(criada, "$.emailsAutorizados[0].situacao")).isEqualTo("PENDENTE");

        // Antes da ativação a pessoa ainda não existe.
        assertThat(usuarios(NOVO)).isZero();

        MvcTestResult ativacao = ativar(NOVO, codigo);
        assertThat(ativacao).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(corpo(ativacao)).as("204 não tem corpo").isEmpty();
        assertThat(ativacao.getResponse().getCookie("JSESSIONID")).as("ativar não abre sessão").isNull();

        SessaoDeApi novo = SessaoDeApi.entrar(mvc, NOVO);
        MvcTestResult lista = novo.get("/api/parcerias");
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$[*].id")).as("ele vê só a parceria nova").containsExactly((int) parceriaId);
        assertThat(lerJson(lista, "$[0].patrocinador.nome")).isEqualTo("Cliente Novo");
        assertThat(lerJson(lista, "$[0].usuarios")).as("o patrocinador não vê o número de usuários").isNull();

        MvcTestResult eu = novo.get("/api/auth/eu");
        assertThat(lerJson(eu, "$.nome")).as("sem nome no corpo, vale a parte do e-mail antes do @").isEqualTo("marketing");
        assertThat(lerLista(eu, "$.parcerias")).hasSize(1);

        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        assertThat(novo.get("/api/parcerias/{id}", amarelo)).as("a parceria de outro não existe para ele").hasStatus(HttpStatus.NOT_FOUND);

        // [HISTORICO] ATIVACAO na parceria, em nome da pessoa que acabou de entrar.
        var linha = jdbc.sql("""
                        SELECT h.parceria_id, h.entidade, h.usuario_id, h.entidade_id, host(h.ip) AS ip
                        FROM historico h WHERE h.acao = 'ATIVACAO'
                        """).query().singleRow();
        assertThat(((Number) linha.get("parceria_id")).longValue()).isEqualTo(parceriaId);
        assertThat(linha.get("entidade")).isEqualTo("usuario");
        assertThat(linha.get("usuario_id")).isEqualTo(linha.get("entidade_id"));
        assertThat(linha.get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void nomeInformadoEhUsadoNaConta() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        assertThat(ativar(NOVO, codigo, SessaoDeApi.SENHA, "Ana do Marketing")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(lerJson(SessaoDeApi.entrar(mvc, NOVO).get("/api/auth/eu"), "$.nome")).isEqualTo("Ana do Marketing");
    }

    @Test
    void ocodigoSoFicaNoBancoComoHashSha256() throws Exception {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));
        String esperado = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(codigo.replace("-", "").getBytes(StandardCharsets.UTF_8)));

        String guardado = jdbc.sql("SELECT codigo_hash FROM codigo_ativacao").query(String.class).single();
        assertThat(guardado).isEqualTo(esperado);
        long linhasComOTexto = jdbc.sql("SELECT count(*) FROM codigo_ativacao ca WHERE CAST(ca AS TEXT) LIKE :trecho")
                .param("trecho", "%" + codigo.replace("-", "") + "%").query(Long.class).single();
        assertThat(linhasComOTexto).as("o código em si nunca é gravado").isZero();
    }

    // ---------------------------------------------------------------------
    // O que não ativa
    // ---------------------------------------------------------------------

    @Test
    void emailNaoAutorizadoNaoAtivaEDaAMesmaFraseDoCodigoErrado() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        MvcTestResult desconhecido = ativar("ninguem@exemplo.test", codigo);
        MvcTestResult errado = ativar(NOVO, "AAAA-BBBB");

        assertThat(desconhecido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(errado).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(desconhecido, "$.detail")).isEqualTo(FRASE);
        assertThat(lerJson(errado, "$.detail")).as("a mesma frase: não revela quais e-mails existem").isEqualTo(FRASE);
        assertThat(lerJson(desconhecido, "$.campos.codigo")).isEqualTo(FRASE);
        assertThat(desconhecido.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(usuarios("ninguem@exemplo.test")).isZero();
        assertThat(usuarios(NOVO)).isZero();
    }

    @Test
    void codigoDeOutroEmailNaoServe() {
        String codigoDoNovo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));
        criarParceria("Cliente Outro", 2026, "outro@novo.example");

        assertThat(ativar("outro@novo.example", codigoDoNovo)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(usuarios("outro@novo.example")).isZero();
    }

    @Test
    void codigoUsadoNaoAtivaDeNovoNemTrocaASenha() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));
        assertThat(ativar(NOVO, codigo)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult denovo = ativar(NOVO, codigo, "outra-senha-456", null);

        assertThat(denovo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(denovo, "$.detail")).isEqualTo(FRASE);
        assertThat(SessaoDeApi.entrar(mvc, NOVO).get("/api/auth/eu")).as("a senha continua a primeira").hasStatus(HttpStatus.OK);
    }

    @Test
    void depoisDeCincoErrosOCodigoBloqueiaMesmoSeForOCerto() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        for (int i = 1; i <= 5; i++) {
            assertThat(ativar(NOVO, "ERRO-000" + i)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(tentativasDoCodigo(NOVO)).isEqualTo(5);

        MvcTestResult certo = ativar(NOVO, codigo);
        assertThat(certo).as("o código certo, mas já bloqueado").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(certo, "$.detail")).isEqualTo(FRASE);
        assertThat(usuarios(NOVO)).isZero();
        assertThat(tentativasDoCodigo(NOVO)).as("o contador para no limite").isEqualTo(5);

        // A RBS gera outro código (a rota 12 reemite para o e-mail pendente) e ele nasce com zero erros.
        long parceriaId = jdbc.sql("SELECT parceria_id FROM email_autorizado WHERE email = :e").param("e", NOVO).query(Long.class).single();
        MvcTestResult novoCodigo = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias/" + parceriaId + "/emails",
                "{\"email\":\"" + NOVO + "\",\"vinculo\":\"MARCA\"}");
        assertThat(novoCodigo).hasStatus(HttpStatus.OK);
        assertThat(ativar(NOVO, lerTexto(novoCodigo, "$.codigoAtivacao"))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void quatroErrosEUmAcertoAindaAtiva() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));
        for (int i = 1; i <= 4; i++) {
            assertThat(ativar(NOVO, "ERRO-000" + i)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }

        assertThat(ativar(NOVO, codigo)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void codigoVencidoNaoAtiva() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));
        // Faz o código ter sido gerado há 8 dias (vale 7). O CHECK exige expira_em depois de criado_em.
        jdbc.sql("UPDATE codigo_ativacao SET criado_em = now() - interval '8 days', expira_em = now() - interval '1 day'").update();

        MvcTestResult resposta = ativar(NOVO, codigo);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE);
        assertThat(usuarios(NOVO)).isZero();
    }

    @Test
    void codigoDeEmailRevogadoNaoAtiva() {
        MvcTestResult criada = criarParceria("Cliente Novo", 2027, NOVO);
        String codigo = codigoDe(criada);
        long parceriaId = ((Number) lerJson(criada, "$.id")).longValue();
        long emailId = ((Number) lerJson(criada, "$.emailsAutorizados[0].id")).longValue();
        assertThat(SessaoDeApi.entrar(mvc, RBS).delete("/api/parcerias/" + parceriaId + "/emails/" + emailId))
                .hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult resposta = ativar(NOVO, codigo);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE);
        assertThat(usuarios(NOVO)).isZero();
    }

    // ---------------------------------------------------------------------
    // Formato do código e validação do corpo
    // ---------------------------------------------------------------------

    @Test
    void maiusculasEHifenNaoImportam() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        assertThat(ativar("  Marketing@Novo.EXAMPLE ", codigo.replace("-", "").toLowerCase())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void senhaCurtaDevolve422ComCamposENaoGastaOCodigo() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        MvcTestResult curta = ativar(NOVO, codigo, "curta", null);

        assertThat(curta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(curta, "$.title")).isEqualTo("Dados inválidos");
        assertThat(lerJson(curta, "$.campos.senha")).isEqualTo("A senha deve ter de 8 a 72 caracteres.");
        assertThat(tentativasDoCodigo(NOVO)).as("erro de formato não conta como código errado").isZero();
        assertThat(ativar(NOVO, codigo)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void corpoVazioEEmailInvalidoDevolvemCampos() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        MvcTestResult vazio = anonima.post("/api/auth/ativar", "{}");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.email")).isEqualTo("Informe o e-mail.");
        assertThat(lerJson(vazio, "$.campos.codigo")).isEqualTo("Informe o código de ativação.");
        assertThat(lerJson(vazio, "$.campos.senha")).isEqualTo("Informe a senha.");

        MvcTestResult invalido = anonima.post("/api/auth/ativar", "{\"email\":\"a@b\",\"codigo\":\"X\",\"senha\":\"senha-longa-123\"}");
        assertThat(invalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invalido, "$.campos.email")).isEqualTo("E-mail inválido.");
    }

    @Test
    void ativarExigeOTokenCsrf() {
        String codigo = codigoDe(criarParceria("Cliente Novo", 2027, NOVO));

        MvcTestResult resposta = SessaoDeApi.anonima(mvc).postSemCsrf("/api/auth/ativar",
                "{\"email\":\"" + NOVO + "\",\"codigo\":\"" + codigo + "\",\"senha\":\"senha123\"}");

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(usuarios(NOVO)).isZero();
    }

    // ---------------------------------------------------------------------
    // E-mail autorizado em mais de uma parceria
    // ---------------------------------------------------------------------

    @Test
    void emailAutorizadoEmDuasParceriasGanhaAcessoAsDuasAoAtivar() {
        MvcTestResult primeira = criarParceria("Cliente Novo", 2027, "agencia@novo.example");
        MvcTestResult segunda = criarParceria("Cliente Outro", 2026, "agencia@novo.example");
        String codigoDaPrimeira = codigoDe(primeira);
        String codigoDaSegunda = codigoDe(segunda);
        assertThat(codigoDaSegunda).isNotEqualTo(codigoDaPrimeira);

        assertThat(ativar("agencia@novo.example", codigoDaPrimeira)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult lista = SessaoDeApi.entrar(mvc, "agencia@novo.example").get("/api/parcerias");
        assertThat(lerLista(lista, "$[*].patrocinador.nome")).containsExactlyInAnyOrder("Cliente Novo", "Cliente Outro");
        long ativacoes = jdbc.sql("SELECT count(*) FROM historico WHERE acao = 'ATIVACAO'").query(Long.class).single();
        assertThat(ativacoes).as("uma linha por parceria").isEqualTo(2);
        assertThat(ativar("agencia@novo.example", codigoDaSegunda)).as("o outro código já foi consumido")
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void contaDeUsuarioDaRbsNaoSeAtivaPeloCodigo() {
        // Mesmo que alguém tente "ativar" o e-mail da RBS, não há autorização nem código para ele.
        MvcTestResult resposta = ativar(RBS, "AAAA-BBBB", "outra-senha-456", null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(SessaoDeApi.entrar(mvc, RBS).get("/api/auth/eu")).as("a senha da RBS não mudou").hasStatus(HttpStatus.OK);
    }
}
