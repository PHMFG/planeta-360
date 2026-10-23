package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.ApoioDeTeste.lerTexto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa os e-mails autorizados (rotas 11 a 13): listar, autorizar com código de ativação, reemitir, revogar e reautorizar.
 * Atende os itens 3 e 5 da Entrega 2. [PERMISSAO] "Patrocinador não cadastra nada" (403); a revogação tira o acesso na hora.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class EmailsAutorizadosTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String CODIGO = "[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    String caminho(long parceriaId) {
        return "/api/parcerias/" + parceriaId + "/emails";
    }

    String corpoEmail(String email, String vinculo) {
        return "{\"email\":\"" + email + "\",\"vinculo\":\"" + vinculo + "\"}";
    }

    long emailId(long parceriaId, String email) {
        return jdbc.sql("SELECT id FROM email_autorizado WHERE parceria_id = :p AND lower(email) = lower(:e)")
                .param("p", parceriaId).param("e", email).query(Long.class).single();
    }

    long historico(String acao, long parceriaId) {
        return jdbc.sql("SELECT count(*) FROM historico WHERE acao = :a AND parceria_id = :p")
                .param("a", acao).param("p", parceriaId).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // [PERMISSAO] O patrocinador não cadastra nada
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NasTresRotasDeEmailsMesmoNaPropriaParceria() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        long propria = roxo();
        long emailDele = emailId(propria, ROXO);

        MvcTestResult lista = roxo.get(caminho(propria));
        assertThat(lista).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(lista, "$.title")).isEqualTo("Acesso negado");
        assertThat(roxo.post(caminho(propria), corpoEmail("novo@roxo.example", "AGENCIA"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(roxo.delete(caminho(propria) + "/" + emailDele)).hasStatus(HttpStatus.FORBIDDEN);

        long semAutorizacao = jdbc.sql("SELECT count(*) FROM email_autorizado WHERE email = 'novo@roxo.example'")
                .query(Long.class).single();
        assertThat(semAutorizacao).isZero();
        assertThat(jdbc.sql("SELECT revogado_em IS NULL FROM email_autorizado WHERE id = :id").param("id", emailDele)
                .query(Boolean.class).single()).as("o e-mail dele continua autorizado").isTrue();
    }

    @Test
    void patrocinadorPedindoEmailsDeOutraParceriaTambemRecebe403SemRevelarSeOIdExiste() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);

        MvcTestResult doAmarelo = roxo.get(caminho(amarelo()));
        MvcTestResult inexistente = roxo.get(caminho(999_999));

        assertThat(doAmarelo).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(inexistente).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(corpo(doAmarelo).replace("/" + amarelo() + "/", "/X/")).isEqualTo(corpo(inexistente).replace("/999999/", "/X/"));
    }

    @Test
    void semSessaoDevolve401() {
        assertThat(SessaoDeApi.anonima(mvc).get(caminho(amarelo()))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void autorizarExigeOTokenCsrf() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.postSemCsrf(caminho(amarelo()), corpoEmail("novo@amarelo.example", "MARCA"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // GET /api/parcerias/{id}/emails
    // ---------------------------------------------------------------------

    @Test
    void rbsListaOsEmailsComQuemAutorizouEOsRevogados() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = amarelo();
        assertThat(rbs.delete(caminho(amarelo) + "/" + emailId(amarelo, "atendimento@agencia-x.example")))
                .hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult lista = rbs.get(caminho(amarelo));

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$[*].email")).containsExactlyInAnyOrder(AMARELO, "atendimento@agencia-x.example");
        String marca = "$[?(@.email=='" + AMARELO + "')]";
        String agencia = "$[?(@.email=='atendimento@agencia-x.example')]";
        assertThat(lerLista(lista, marca + ".situacao")).containsExactly("ATIVO");
        assertThat(lerLista(lista, marca + ".vinculo")).containsExactly("MARCA");
        assertThat(lerLista(lista, marca + ".usuarioId")).doesNotContainNull().hasSize(1);
        assertThat(lerLista(lista, marca + ".autorizadoPor.nome")).containsExactly("Equipe B2B RBS");
        assertThat(lerLista(lista, marca + ".autorizadoPor.perfil")).containsExactly("ADMIN_RBS");
        assertThat(lerLista(lista, marca + ".revogadoPor")).containsExactly((Object) null);
        assertThat(lerLista(lista, agencia + ".situacao")).containsExactly("REVOGADO");
        assertThat(lerLista(lista, agencia + ".vinculo")).containsExactly("AGENCIA");
        assertThat(lerLista(lista, agencia + ".revogadoPor.nome")).containsExactly("Equipe B2B RBS");
        assertThat((String) lerLista(lista, agencia + ".revogadoEm").get(0)).endsWith("-03:00");
    }

    @Test
    void listarParceriaInexistenteDevolve404() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).get(caminho(999_999))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // POST /api/parcerias/{id}/emails
    // ---------------------------------------------------------------------

    @Test
    void autorizarEmailNovoDevolve201ComCodigoEGravaHistorico() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long roxo = roxo();
        long antes = historico("AUTORIZACAO", roxo);

        MvcTestResult resposta = rbs.post(caminho(roxo), corpoEmail("  Novo@Agencia.Example ", "AGENCIA"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.email")).as("fica em minúsculas, sem espaços").isEqualTo("novo@agencia.example");
        assertThat(lerJson(resposta, "$.vinculo")).isEqualTo("AGENCIA");
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("PENDENTE");
        assertThat(lerJson(resposta, "$.usuarioId")).isNull();
        assertThat(lerJson(resposta, "$.autorizadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerTexto(resposta, "$.codigoAtivacao")).matches(CODIGO);
        assertThat(lerTexto(resposta, "$.codigoExpiraEm")).endsWith("-03:00");
        assertThat(historico("AUTORIZACAO", roxo)).isEqualTo(antes + 1);

        // A parceria mostra o e-mail como pendente, e sem o código (ele sai uma vez só).
        MvcTestResult parceria = SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias/{id}", roxo);
        assertThat(lerLista(parceria, "$.emailsAutorizados[?(@.email=='novo@agencia.example')].situacao")).containsExactly("PENDENTE");
        assertThat(corpo(parceria)).doesNotContain("codigoAtivacao");
    }

    @Test
    void autorizarOEmailPendenteDeNovoReemiteOCodigoEOAntigoDeixaDeValer() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long roxo = roxo();
        String primeiro = lerTexto(rbs.post(caminho(roxo), corpoEmail("novo@agencia.example", "AGENCIA")), "$.codigoAtivacao");

        MvcTestResult segunda = rbs.post(caminho(roxo), corpoEmail("novo@agencia.example", "AGENCIA"));

        assertThat(segunda).as("a linha já existia: 200, não 201").hasStatus(HttpStatus.OK);
        String segundoCodigo = lerTexto(segunda, "$.codigoAtivacao");
        assertThat(segundoCodigo).matches(CODIGO).isNotEqualTo(primeiro);
        long vigentes = jdbc.sql("SELECT count(*) FROM codigo_ativacao WHERE usado_em IS NULL AND invalidado_em IS NULL")
                .query(Long.class).single();
        assertThat(vigentes).as("só um código vigente por e-mail").isEqualTo(1);
        long emails = jdbc.sql("SELECT count(*) FROM email_autorizado WHERE email = 'novo@agencia.example'").query(Long.class).single();
        assertThat(emails).as("não duplica o e-mail").isEqualTo(1);

        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);
        String corpoAtivar = "{\"email\":\"novo@agencia.example\",\"senha\":\"senha123\",\"codigo\":\"%s\"}";
        assertThat(anonima.post("/api/auth/ativar", corpoAtivar.formatted(primeiro))).as("o código antigo não vale mais")
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(anonima.post("/api/auth/ativar", corpoAtivar.formatted(segundoCodigo))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void emailJaAtivoDevolve409() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post(caminho(amarelo()), corpoEmail(AMARELO.toUpperCase(), "MARCA"));

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Este e-mail já está autorizado nesta parceria.");
    }

    @Test
    void emailDeContaQueJaExisteViraAcessoNaHoraSemCodigo() {
        // A agência do Amarelo já tem conta e senha. Ao ser autorizada também no Roxo, entra na hora, sem código.
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long roxo = roxo();
        SessaoDeApi agencia = SessaoDeApi.entrar(mvc, "atendimento@agencia-x.example");
        assertThat(agencia.get("/api/parcerias/{id}", roxo)).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult resposta = rbs.post(caminho(roxo), corpoEmail("atendimento@agencia-x.example", "AGENCIA"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("ATIVO");
        assertThat(lerJson(resposta, "$.codigoAtivacao")).isNull();
        assertThat(lerJson(resposta, "$.usuarioId")).isNotNull();
        assertThat(agencia.get("/api/parcerias/{id}", roxo)).as("a sessão que já estava aberta passa a ver a parceria")
                .hasStatus(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT count(*) FROM codigo_ativacao").query(Long.class).single()).isZero();
    }

    @Test
    void emailDeUsuarioDaRbsEhRecusadoCom422() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post(caminho(roxo()), corpoEmail("pesquisa@rbs.example", "MARCA"));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resposta, "$.campos.email")).contains("RBS");
    }

    @Test
    void emailEVinculoInvalidosDevolve422ComCampos() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult invalido = rbs.post(caminho(roxo()), corpoEmail("isto-nao-e-email", "SOCIO"));
        assertThat(invalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invalido, "$.campos.email")).isEqualTo("E-mail inválido.");
        assertThat(lerJson(invalido, "$.campos.vinculo")).isEqualTo("O vínculo deve ser MARCA ou AGENCIA.");

        MvcTestResult semPonto = rbs.post(caminho(roxo()), corpoEmail("alguem@semponto", "MARCA"));
        assertThat(lerJson(semPonto, "$.campos.email")).as("o banco exige um ponto no domínio").isEqualTo("E-mail inválido.");

        assertThat(rbs.post(caminho(roxo()), "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void autorizarEmDeParceriaInexistenteDevolve404() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).post(caminho(999_999), corpoEmail("novo@x.example", "MARCA")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // DELETE /api/parcerias/{id}/emails/{emailId}: revogar e reautorizar
    // ---------------------------------------------------------------------

    @Test
    void revogarOEmailDoAmareloTiraOAcessoNaHoraDaSessaoAberta() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = amarelo();
        assertThat(amarelo.get("/api/parcerias/{id}", parceria)).hasStatus(HttpStatus.OK);
        assertThat(amarelo.get("/api/auth/eu")).hasStatus(HttpStatus.OK);

        MvcTestResult revogacao = SessaoDeApi.entrar(mvc, RBS).delete(caminho(parceria) + "/" + emailId(parceria, AMARELO));

        assertThat(revogacao).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(corpo(revogacao)).isEmpty();
        assertThat(amarelo.get("/api/parcerias/{id}", parceria)).as("a parceria some na chamada seguinte")
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerLista(amarelo.get("/api/parcerias"), "$")).as("e a lista dele fica vazia").isEmpty();
        assertThat(amarelo.get("/api/auth/eu")).as("o app descobre pelo /eu: a sessão terminou").hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).post("/api/auth/login", "{\"email\":\"" + AMARELO + "\",\"senha\":\"senha123\"}"))
                .as("e ele não entra de novo").hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(historico("REVOGACAO", parceria)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM email_autorizado WHERE id = :id").param("id", emailId(parceria, AMARELO))
                .query(Long.class).single()).as("a linha não é apagada: a trilha fica").isEqualTo(1);
    }

    @Test
    void reautorizarDevolveOAcessoSemCodigo() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parceria = amarelo();
        long antes = historico("AUTORIZACAO", parceria);
        assertThat(rbs.delete(caminho(parceria) + "/" + emailId(parceria, AMARELO))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(SessaoDeApi.anonima(mvc).post("/api/auth/login", "{\"email\":\"" + AMARELO + "\",\"senha\":\"senha123\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        MvcTestResult resposta = rbs.post(caminho(parceria), corpoEmail(AMARELO, "MARCA"));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("ATIVO");
        assertThat(lerJson(resposta, "$.revogadoPor")).as("a revogação foi desfeita").isNull();
        assertThat(lerJson(resposta, "$.codigoAtivacao")).as("quem já tinha conta não precisa de código").isNull();
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        assertThat(amarelo.get("/api/parcerias/{id}", parceria)).as("o acesso voltou").hasStatus(HttpStatus.OK);
        assertThat(historico("AUTORIZACAO", parceria)).as("as 2 da amostra e a nova").isEqualTo(antes + 1);
    }

    @Test
    void reautorizarEmailQueNuncaAtivouGeraCodigoNovoEOAntigoMorre() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long roxo = roxo();
        MvcTestResult primeira = rbs.post(caminho(roxo), corpoEmail("novo@agencia.example", "AGENCIA"));
        String antigo = lerTexto(primeira, "$.codigoAtivacao");
        long id = ((Number) lerJson(primeira, "$.id")).longValue();
        assertThat(rbs.delete(caminho(roxo) + "/" + id)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult denovo = rbs.post(caminho(roxo), corpoEmail("novo@agencia.example", "MARCA"));

        assertThat(denovo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(denovo, "$.id")).as("é a mesma linha").isEqualTo((int) id);
        assertThat(lerJson(denovo, "$.vinculo")).isEqualTo("MARCA");
        assertThat(lerJson(denovo, "$.situacao")).isEqualTo("PENDENTE");
        String novo = lerTexto(denovo, "$.codigoAtivacao");
        assertThat(novo).matches(CODIGO).isNotEqualTo(antigo);
        String corpoAtivar = "{\"email\":\"novo@agencia.example\",\"senha\":\"senha123\",\"codigo\":\"%s\"}";
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);
        assertThat(anonima.post("/api/auth/ativar", corpoAtivar.formatted(antigo))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(anonima.post("/api/auth/ativar", corpoAtivar.formatted(novo))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void revogarDeNovoOuEmailDeOutraParceriaOuInexistente() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = amarelo();
        long idDoAmarelo = emailId(amarelo, AMARELO);
        assertThat(rbs.delete(caminho(amarelo) + "/" + idDoAmarelo)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult denovo = rbs.delete(caminho(amarelo) + "/" + idDoAmarelo);
        assertThat(denovo).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(denovo, "$.detail")).isEqualTo("Este e-mail já estava revogado.");

        // [ISOLAMENTO] O id do e-mail do Roxo, pedido pela parceria do Amarelo, é "não existe"; o do Roxo não foi tocado.
        long idDoRoxo = emailId(roxo(), ROXO);
        assertThat(rbs.delete(caminho(amarelo) + "/" + idDoRoxo)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.delete(caminho(amarelo) + "/999999")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.delete(caminho(999_999) + "/" + idDoAmarelo)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.entrar(mvc, ROXO).get("/api/auth/eu")).hasStatus(HttpStatus.OK);
    }
}
