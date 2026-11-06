package br.com.rbs.portal.dados;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 39 (catálogo de campos), 40 (valores vigentes de uma aba) e 41 (gravar valores) como o front as usa: login
 * pela rota, sessão de verdade e token CSRF, mais o serviço que o Atlas vai reutilizar. [VALIDACAO] [HISTORICO] [ISOLAMENTO]
 * [PERMISSAO] Os números esperados são conferidos por SQL direto em dado_informado e historico. Cada teste é desfeito no fim
 * (@Transactional). A amostra não tem nenhum dado informado: todo campo começa sem valor vigente. Os campos de outros tipos
 * (arquivo, decimal, data) e de outra aba são inseridos no catálogo dentro do próprio teste, que desfaz o INSERT.
 */
@TesteDeIntegracao
@Transactional
class DadoInformadoControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String VISITANTES = "ativacao.visitantes_total";
    private static final String CADASTROS = "ativacao.cadastros_consentidos";
    private static final String DISPONIVEIS = "ativacao.brindes_disponiveis";
    private static final String DISTRIBUIDOS = "ativacao.brindes_distribuidos";
    private static final String PESSOAS = "ativacao.pessoas_na_ativacao";
    private static final String PLANILHA = "ativacao.planilha_agencia";
    private static final String ABA_FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";
    private static final String FORA_DA_COTA = "Esta aba não faz parte da cota da sua parceria.";
    private static final List<String> CHAVES_DO_CAMPO = List.of("chave", "aba", "rotulo", "tipo", "unidade", "minimo", "maximo",
            "ajuda", "limiteCampo");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DadoInformadoService servico;

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    long usuario(String email) {
        return contar("SELECT id FROM usuario WHERE email = ?", email);
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    /** Um item do pedido: o valor entra como JSON cru (1200, "texto", null, 12.5, true...). */
    static String v(String chave, String valorJson) {
        return "{\"chave\":\"" + chave + "\",\"valor\":" + valorJson + "}";
    }

    static String pedido(String... itens) {
        return "{\"valores\":[" + String.join(",", itens) + "]}";
    }

    MvcTestResult gravar(SessaoDeApi pessoa, long parceria, String... itens) {
        return pessoa.post("/api/parcerias/" + parceria + "/dados", pedido(itens));
    }

    MvcTestResult ver(SessaoDeApi pessoa, long parceria) {
        return pessoa.get("/api/parcerias/" + parceria + "/dados?aba=APLICACOES");
    }

    long linhas(long parceria, String chave) {
        return contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND campo_chave = ?", parceria, chave);
    }

    long linhasDaParceria(long parceria) {
        return contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", parceria);
    }

    /** O JSON do campo com essa chave, procurado na resposta das rotas 40 e 41. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> campo(MvcTestResult resposta, String chave) {
        List<Map<String, Object>> campos = lerLista(resposta, "$.campos");
        return campos.stream().filter(c -> chave.equals(c.get("chave"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> vigente(MvcTestResult resposta, String chave) {
        return (Map<String, Object>) campo(resposta, chave).get("vigente");
    }

    @SuppressWarnings("unchecked")
    static List<String> chaves(MvcTestResult resposta, String caminho) {
        return new ArrayList<>(((Map<String, Object>) lerJson(resposta, caminho)).keySet());
    }

    void fechar(long parceriaId) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", parceriaId).update();
    }

    /** Campos de teste no catálogo (desfeitos no fim do teste): um de arquivo, um decimal, um de data e um de outra aba. */
    void camposDeOutrosTipos() {
        jdbc.sql("""
                INSERT INTO campo_catalogo (chave, aba_id, ordem, rotulo, tipo, unidade, minimo, maximo) VALUES
                 ('teste.arquivo', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 50, 'Planilha da agência', 'ARQUIVO', NULL, NULL, NULL),
                 ('teste.decimal', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 51, 'Nota média', 'DECIMAL', 'pontos', 0, 100),
                 ('teste.data', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 52, 'Data da ativação', 'DATA', NULL, NULL, NULL),
                 ('teste.perfil', (SELECT id FROM aba WHERE codigo = 'PERFIL'), 1, 'Número do perfil', 'INTEIRO', NULL, 0, 10)
                """).update();
    }

    // ---------------------------------------------------------------------
    // Rota 39: o catálogo
    // ---------------------------------------------------------------------

    @Test
    void qualquerPessoaLogadaVeOCatalogoDaAbaAplicacoesNaOrdemDoFormulario() {
        for (String email : List.of(AMARELO, RBS)) {
            MvcTestResult lista = entrar(email).get("/api/campos?aba=APLICACOES");

            assertThat(lista).as(email).hasStatus(HttpStatus.OK);
            assertThat(lerLista(lista, "$[*].chave")).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS, PESSOAS, PLANILHA);
            assertThat(chaves(lista, "$[0]")).containsExactlyElementsOf(CHAVES_DO_CAMPO);
            assertThat(lerJson(lista, "$[0].aba")).isEqualTo("APLICACOES");
            assertThat(lerJson(lista, "$[0].rotulo")).isEqualTo("Visitantes no espaço");
            assertThat(lerJson(lista, "$[0].tipo")).isEqualTo("INTEIRO");
            assertThat(lerJson(lista, "$[0].unidade")).isEqualTo("pessoas");
            assertThat(lerJson(lista, "$[0].minimo")).as("inteiro, e não 0.00").isEqualTo(0);
            assertThat(lerJson(lista, "$[0].maximo")).isNull();
            assertThat((String) lerJson(lista, "$[0].ajuda")).isNotBlank();
            assertThat(lerJson(lista, "$[0].limiteCampo")).isNull();
            assertThat(lerJson(lista, "$[1].limiteCampo")).isEqualTo(VISITANTES);
            assertThat(lerJson(lista, "$[2].limiteCampo")).isNull();
            assertThat(lerJson(lista, "$[3].limiteCampo")).isEqualTo(DISPONIVEIS);
            assertThat(lerLista(lista, "$[*].rotulo")).containsExactly("Visitantes no espaço", "Cadastros com consentimento",
                    "Brindes disponíveis", "Brindes distribuídos", "Pessoas na ativação", "Planilha da agência");
        }
    }

    @Test
    void semAbaVemTodoOCatalogoEAbaSemCamposVemVazia() {
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult todos = marca.get("/api/campos");
        MvcTestResult daAba = marca.get("/api/campos?aba=APLICACOES");
        MvcTestResult deOutraAba = marca.get("/api/campos?aba=PERFIL");

        assertThat(todos).hasStatus(HttpStatus.OK);
        assertThat(corpo(todos)).isEqualTo(corpo(daAba));
        assertThat(deOutraAba).hasStatus(HttpStatus.OK);
        assertThat(lerLista(deOutraAba, "$")).isEmpty();
    }

    @Test
    void abaQueNaoExisteDa422ESemSessaoDa401() {
        SessaoDeApi marca = entrar(AMARELO);

        for (String aba : List.of("NAO_EXISTE", "aplicacoes", "APLICACOES,PERFIL")) {
            MvcTestResult invalida = marca.get("/api/campos?aba=" + aba);
            assertThat(invalida).as(aba).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat((String) lerJson(invalida, "$.campos.aba")).startsWith("Aba inválida.");
        }
        assertThat(SessaoDeApi.anonima(mvc).get("/api/campos")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Rota 40: os valores vigentes
    // ---------------------------------------------------------------------

    @Test
    void antesDeQualquerValorTodoCampoVemSemVigenteNaOrdemDoCatalogo() {
        long amarelo = amarelo();

        for (String email : List.of(AMARELO, RBS)) {
            MvcTestResult dados = ver(entrar(email), amarelo);

            assertThat(dados).as(email).hasStatus(HttpStatus.OK);
            assertThat(chaves(dados, "$")).containsExactly("parceriaId", "aba", "campos");
            assertThat(((Number) lerJson(dados, "$.parceriaId")).longValue()).isEqualTo(amarelo);
            assertThat(lerJson(dados, "$.aba")).isEqualTo("APLICACOES");
            assertThat(lerLista(dados, "$.campos[*].chave")).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS, PESSOAS, PLANILHA);
            assertThat(chaves(dados, "$.campos[0]")).containsExactly("chave", "aba", "rotulo", "tipo", "unidade", "minimo", "maximo",
                    "ajuda", "limiteCampo", "vigente");
            assertThat(lerLista(dados, "$.campos[*].vigente")).containsOnlyNulls();
        }
    }

    @Test
    void abaObrigatoriaEPrecisaExistir() {
        SessaoDeApi marca = entrar(AMARELO);
        String uri = "/api/parcerias/" + amarelo() + "/dados";

        MvcTestResult semAba = marca.get(uri);
        MvcTestResult vazia = marca.get(uri + "?aba=");
        MvcTestResult inexistente = marca.get(uri + "?aba=NAO_EXISTE");

        for (MvcTestResult resposta : List.of(semAba, vazia, inexistente)) {
            assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(chaves(resposta, "$.campos")).containsExactly("aba");
        }
        assertThat(lerJson(semAba, "$.campos.aba")).isEqualTo("Informe a aba.");
        assertThat((String) lerJson(inexistente, "$.campos.aba")).startsWith("Aba inválida.");
    }

    @Test
    void parceriaDeOutraMarcaOuQueNaoExisteDa404ComAMesmaFrase() {
        SessaoDeApi roxo = entrar(ROXO);
        long amarelo = amarelo();
        gravar(entrar(AMARELO), amarelo, v(VISITANTES, "1200"));

        MvcTestResult deOutra = roxo.get("/api/parcerias/" + amarelo + "/dados?aba=APLICACOES");
        MvcTestResult inexistente = roxo.get("/api/parcerias/999999/dados?aba=APLICACOES");
        MvcTestResult naoEhNumero = roxo.get("/api/parcerias/abc/dados?aba=APLICACOES");

        for (MvcTestResult resposta : List.of(deOutra, inexistente, naoEhNumero)) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta parceria.");
        }
        assertThat(corpo(deOutra)).doesNotContain("1200");
        assertThat(entrar(RBS).get("/api/parcerias/999999/dados?aba=APLICACOES")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo + "/dados?aba=APLICACOES")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oDadoDeUmaParceriaNaoApareceNaOutra() {
        gravar(entrar(AMARELO), amarelo(), v(VISITANTES, "1200"));

        MvcTestResult doRoxo = ver(entrar(ROXO), roxo());

        assertThat(doRoxo).hasStatus(HttpStatus.OK);
        assertThat(lerLista(doRoxo, "$.campos[*].vigente")).containsOnlyNulls();
    }

    @Test
    void abaFechadaDa403ParaAMarcaComAsDuasFrasesEARbsContinuaVendo() {
        long amarelo = amarelo();
        SessaoDeApi marca = entrar(AMARELO);

        fechar(amarelo);
        MvcTestResult fechada = ver(marca, amarelo);
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.title")).isEqualTo("Acesso negado");
        assertThat(lerJson(fechada, "$.detail")).isEqualTo(ABA_FECHADA);
        assertThat(ver(entrar(RBS), amarelo)).hasStatus(HttpStatus.OK);

        jdbc.sql("DELETE FROM cota_tipo_aba WHERE aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES') "
                + "AND cota_tipo_id = (SELECT cota_tipo_id FROM parceria WHERE id = :p)").param("p", amarelo).update();
        assertThat(lerJson(ver(marca, amarelo), "$.detail")).isEqualTo(FORA_DA_COTA);
    }

    // ---------------------------------------------------------------------
    // Rota 41: gravar
    // ---------------------------------------------------------------------

    @Test
    void aMarcaGravaDoisValoresERecebeOCorpoDaRota40ComAutorOrigemEData() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        long marcaId = usuario(AMARELO);

        MvcTestResult gravado = gravar(marca, amarelo, v(VISITANTES, "1200"), v(CADASTROS, "900"));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat(corpo(gravado)).as("o corpo da 41 é o da 40").isEqualTo(corpo(ver(marca, amarelo)));
        assertThat(lerLista(gravado, "$.campos[*].chave")).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS, PESSOAS, PLANILHA);
        Map<String, Object> visitantes = vigente(gravado, VISITANTES);
        assertThat(visitantes.keySet()).containsExactly("valor", "origem", "informadoPor", "informadoEm", "arquivo");
        assertThat(visitantes.get("arquivo")).as("só o campo de arquivo traz o arquivo").isNull();
        assertThat(visitantes.get("valor")).as("inteiro").isEqualTo(1200);
        assertThat(visitantes.get("origem")).isEqualTo("PORTAL");
        assertThat(((Map<?, ?>) visitantes.get("informadoPor")).get("id")).isEqualTo((int) marcaId);
        assertThat(((Map<?, ?>) visitantes.get("informadoPor")).get("perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat((String) visitantes.get("informadoEm")).endsWith("-03:00");
        assertThat(vigente(gravado, CADASTROS).get("valor")).isEqualTo(900);
        assertThat(vigente(gravado, DISPONIVEIS)).as("campo que ninguém informou").isNull();
        // O banco: duas linhas, origem PORTAL, a marca como autora.
        assertThat(linhasDaParceria(amarelo)).isEqualTo(2);
        Map<String, Object> linha = jdbc.sql("SELECT valor_numero, valor_data, arquivo_id, origem, informado_por FROM dado_informado "
                + "WHERE parceria_id = :p AND campo_chave = :c").param("p", amarelo).param("c", VISITANTES).query().singleRow();
        assertThat(((BigDecimal) linha.get("valor_numero"))).isEqualByComparingTo("1200");
        assertThat(linha.get("valor_data")).isNull();
        assertThat(linha.get("arquivo_id")).isNull();
        assertThat(linha.get("origem")).isEqualTo("PORTAL");
        assertThat(((Number) linha.get("informado_por")).longValue()).isEqualTo(marcaId);
    }

    @Test
    void cadaValorGravadoRegistraCriacaoNoHistoricoDaParceria() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        long marcaId = usuario(AMARELO);

        gravar(marca, amarelo, v(VISITANTES, "1200"), v(CADASTROS, "900"));
        gravar(marca, amarelo, v(VISITANTES, "1500"));

        var historico = jdbc.sql("""
                        SELECT h.entidade_id, h.usuario_id, h.valor_anterior, h.valor_novo, h.detalhe, d.campo_chave
                        FROM historico h JOIN dado_informado d ON d.id = h.entidade_id
                        WHERE h.acao = 'CRIACAO' AND h.entidade = 'dado_informado' AND h.parceria_id = :p ORDER BY h.id
                        """).param("p", amarelo).query().listOfRows();
        assertThat(historico).hasSize(3);
        assertThat(historico.get(0).get("campo_chave")).isEqualTo(VISITANTES);
        assertThat(historico.get(0).get("valor_anterior")).isNull();
        assertThat(historico.get(0).get("valor_novo")).isEqualTo("1.200");
        assertThat(historico.get(0).get("detalhe")).isEqualTo("Visitantes no espaço (portal)");
        assertThat(((Number) historico.get(0).get("usuario_id")).longValue()).isEqualTo(marcaId);
        assertThat(historico.get(1).get("campo_chave")).isEqualTo(CADASTROS);
        assertThat(historico.get(2).get("valor_anterior")).as("a correção guarda o valor que saiu").isEqualTo("1.200");
        assertThat(historico.get(2).get("valor_novo")).isEqualTo("1.500");
        // A marca vê o que informou no histórico da própria parceria.
        MvcTestResult daMarca = marca.get("/api/parcerias/" + amarelo + "/historico?entidade=dado_informado");
        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(lerLista(daMarca, "$.itens[*].acao")).containsOnly("CRIACAO");
        assertThat(lerLista(daMarca, "$.itens")).hasSize(3);
    }

    @Test
    void aRbsTambemGravaEFicaComoAutora() {
        long amarelo = amarelo();
        long rbsId = usuario(RBS);

        MvcTestResult gravado = gravar(entrar(RBS), amarelo, v(DISPONIVEIS, "300"));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        Map<String, Object> disponiveis = vigente(gravado, DISPONIVEIS);
        assertThat(disponiveis.get("valor")).isEqualTo(300);
        assertThat(((Map<?, ?>) disponiveis.get("informadoPor")).get("perfil")).isEqualTo("ADMIN_RBS");
        assertThat(contar("SELECT informado_por FROM dado_informado WHERE parceria_id = ?", amarelo)).isEqualTo(rbsId);
        // A marca enxerga o que a RBS informou.
        assertThat(vigente(ver(entrar(AMARELO), amarelo), DISPONIVEIS).get("valor")).isEqualTo(300);
    }

    @Test
    void corrigirGravaLinhaNovaEAAnteriorFicaComoTrilha() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        gravar(marca, amarelo, v(VISITANTES, "1200"));
        MvcTestResult corrigido = gravar(marca, amarelo, v(VISITANTES, "1500"));

        assertThat(vigente(corrigido, VISITANTES).get("valor")).isEqualTo(1500);
        assertThat(linhas(amarelo, VISITANTES)).as("duas linhas: a antiga não foi tocada").isEqualTo(2);
        List<BigDecimal> valores = jdbc.sql("SELECT valor_numero FROM dado_informado WHERE parceria_id = :p AND campo_chave = :c ORDER BY id")
                .param("p", amarelo).param("c", VISITANTES).query(BigDecimal.class).list();
        assertThat(valores.stream().map(BigDecimal::intValueExact)).containsExactly(1200, 1500);
        assertThat(vigente(ver(marca, amarelo), VISITANTES).get("valor")).isEqualTo(1500);
    }

    @Test
    void valorIgualAoVigenteNaoGravaLinhaNova() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        gravar(marca, amarelo, v(VISITANTES, "1200"));
        long historicoAntes = contar("SELECT count(*) FROM historico WHERE entidade = 'dado_informado'");

        MvcTestResult igual = gravar(marca, amarelo, v(VISITANTES, "1200"));
        MvcTestResult igualEmDecimal = gravar(marca, amarelo, v(VISITANTES, "1200.0"));
        MvcTestResult umIgualEUmNovo = gravar(marca, amarelo, v(VISITANTES, "1200"), v(DISPONIVEIS, "300"));

        for (MvcTestResult resposta : List.of(igual, igualEmDecimal, umIgualEUmNovo)) {
            assertThat(resposta).hasStatus(HttpStatus.OK);
            assertThat(vigente(resposta, VISITANTES).get("valor")).isEqualTo(1200);
        }
        assertThat(linhas(amarelo, VISITANTES)).isEqualTo(1);
        assertThat(linhas(amarelo, DISPONIVEIS)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'dado_informado'")).as("só o valor novo entrou no histórico")
                .isEqualTo(historicoAntes + 1);
    }

    @Test
    void cadastrosNaoPassamDosVisitantesVigentesEBrindesDistribuidosNaoPassamDosDisponiveis() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        gravar(marca, amarelo, v(VISITANTES, "1200"), v(DISPONIVEIS, "300"));

        MvcTestResult cadastros = gravar(marca, amarelo, v(CADASTROS, "1201"));
        MvcTestResult brindes = gravar(marca, amarelo, v(DISTRIBUIDOS, "301"));

        assertThat(cadastros).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(cadastros, "$.campos")).containsExactly(CADASTROS);
        assertThat(lerJson(cadastros, "$.campos['" + CADASTROS + "']")).isEqualTo("Não pode passar de Visitantes no espaço (1.200).");
        assertThat(brindes).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(brindes, "$.campos['" + DISTRIBUIDOS + "']")).isEqualTo("Não pode passar de Brindes disponíveis (300).");
        assertThat(linhas(amarelo, CADASTROS) + linhas(amarelo, DISTRIBUIDOS)).as("nada foi gravado").isZero();
        // No limite exato ainda passa.
        assertThat(gravar(marca, amarelo, v(CADASTROS, "1200"), v(DISTRIBUIDOS, "300"))).hasStatus(HttpStatus.OK);
    }

    @Test
    void oLimiteTambemVaiPeloValorNovoDoMesmoPedido() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        MvcTestResult passa = gravar(marca, amarelo, v(CADASTROS, "1000"), v(VISITANTES, "1500"));
        MvcTestResult naoPassa = gravar(marca, amarelo, v(CADASTROS, "2000"), v(VISITANTES, "1800"));

        assertThat(passa).as("a ordem no pedido não importa").hasStatus(HttpStatus.OK);
        assertThat(naoPassa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(naoPassa, "$.campos")).containsExactly(CADASTROS);
        assertThat(lerJson(naoPassa, "$.campos['" + CADASTROS + "']")).isEqualTo("Não pode passar de Visitantes no espaço (1.800).");
        assertThat(vigente(ver(marca, amarelo), VISITANTES).get("valor")).as("o pedido inteiro foi recusado").isEqualTo(1500);
    }

    @Test
    void semValorVigenteNoCampoLimiteNaoHaComOQueComparar() {
        long amarelo = amarelo();

        MvcTestResult gravado = gravar(entrar(AMARELO), amarelo, v(CADASTROS, "5000"));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat(vigente(gravado, CADASTROS).get("valor")).isEqualTo(5000);
    }

    @Test
    void baixarOCampoLimiteAbaixoDeUmDependenteVigenteEhRecusado() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        gravar(marca, amarelo, v(VISITANTES, "1200"), v(CADASTROS, "900"), v(DISPONIVEIS, "300"), v(DISTRIBUIDOS, "250"));

        MvcTestResult visitantes = gravar(marca, amarelo, v(VISITANTES, "899"));
        MvcTestResult disponiveis = gravar(marca, amarelo, v(DISPONIVEIS, "249"));

        assertThat(visitantes).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(visitantes, "$.campos")).containsExactly(VISITANTES);
        assertThat(lerJson(visitantes, "$.campos['" + VISITANTES + "']")).isEqualTo("Não pode ficar abaixo de Cadastros com consentimento (900).");
        assertThat(lerJson(disponiveis, "$.campos['" + DISPONIVEIS + "']")).isEqualTo("Não pode ficar abaixo de Brindes distribuídos (250).");
        // No limite exato passa, e baixar junto com o dependente (no mesmo pedido) também.
        assertThat(gravar(marca, amarelo, v(VISITANTES, "900"))).hasStatus(HttpStatus.OK);
        MvcTestResult juntos = gravar(marca, amarelo, v(VISITANTES, "500"), v(CADASTROS, "400"));
        assertThat(juntos).hasStatus(HttpStatus.OK);
        assertThat(vigente(juntos, VISITANTES).get("valor")).isEqualTo(500);
        assertThat(vigente(juntos, CADASTROS).get("valor")).isEqualTo(400);
        // O dependente que sobe no mesmo pedido cai no erro dele, e não no do campo-limite.
        MvcTestResult dependenteAcima = gravar(marca, amarelo, v(VISITANTES, "300"), v(CADASTROS, "450"));
        assertThat(dependenteAcima).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(dependenteAcima, "$.campos")).containsExactly(CADASTROS);
    }

    @Test
    void valorQueNaoServePeloTipoOuPelosLimitesDa422ComACavaDoCampo() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        Map<String, String> esperado = new java.util.LinkedHashMap<>();
        esperado.put("-1", "O valor mínimo é 0.");
        esperado.put("12.5", "Informe um número inteiro.");
        esperado.put("\"1200\"", "Informe um número inteiro.");
        esperado.put("true", "Informe um número inteiro.");
        esperado.put("[1]", "Informe um número inteiro.");
        esperado.put("null", "Informe o valor.");
        esperado.put("1000000000000", "O valor é grande demais.");
        for (var caso : esperado.entrySet()) {
            MvcTestResult resposta = gravar(marca, amarelo, v(VISITANTES, caso.getKey()));
            assertThat(resposta).as(caso.getKey()).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(chaves(resposta, "$.campos")).as(caso.getKey()).containsExactly(VISITANTES);
            assertThat(lerJson(resposta, "$.campos['" + VISITANTES + "']")).as(caso.getKey()).isEqualTo(caso.getValue());
        }
        MvcTestResult semValor = marca.post("/api/parcerias/" + amarelo + "/dados", "{\"valores\":[{\"chave\":\"" + VISITANTES + "\"}]}");
        assertThat(semValor).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semValor, "$.campos['" + VISITANTES + "']")).isEqualTo("Informe o valor.");
        // 1200.0 e 0 são inteiros de verdade.
        assertThat(gravar(marca, amarelo, v(VISITANTES, "1200.0"), v(DISPONIVEIS, "0"))).hasStatus(HttpStatus.OK);
        assertThat(linhasDaParceria(amarelo)).isEqualTo(2);
    }

    @Test
    void chaveDesconhecidaOuRepetidaDa422EUmErroNaoGravaOsOutrosValores() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        MvcTestResult desconhecida = gravar(marca, amarelo, v(VISITANTES, "1200"), v("ativacao.nao_existe", "5"));
        MvcTestResult repetida = gravar(marca, amarelo, v(VISITANTES, "1200"), v(VISITANTES, "1300"));
        MvcTestResult duasRuins = gravar(marca, amarelo, v(VISITANTES, "-5"), v(CADASTROS, "\"x\""), v("x.y", "1"));

        assertThat(desconhecida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(desconhecida, "$.campos")).containsExactly("ativacao.nao_existe");
        assertThat(lerJson(desconhecida, "$.campos['ativacao.nao_existe']")).isEqualTo("Campo desconhecido.");
        assertThat(repetida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(repetida, "$.campos['" + VISITANTES + "']")).isEqualTo("Este campo aparece mais de uma vez no pedido.");
        // Todos os erros numa resposta só, cada um na chave do campo.
        assertThat(chaves(duasRuins, "$.campos")).containsExactlyInAnyOrder(VISITANTES, CADASTROS, "x.y");
        assertThat(linhasDaParceria(amarelo)).as("tudo ou nada").isZero();
    }

    @Test
    void camposDeArquivoDecimalEDataEDeOutraAbaSeguemAsRegrasDoTipo() {
        camposDeOutrosTipos();
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        MvcTestResult arquivo = gravar(marca, amarelo, v("teste.arquivo", "5"));
        MvcTestResult decimal = gravar(marca, amarelo, v("teste.decimal", "12.5"), v("teste.data", "\"2026-12-01\""));
        MvcTestResult demais = gravar(marca, amarelo, v("teste.decimal", "12.345"));
        MvcTestResult acima = gravar(marca, amarelo, v("teste.decimal", "100.5"));
        MvcTestResult texto = gravar(marca, amarelo, v("teste.decimal", "\"12.5\""), v("teste.data", "\"01/12/2026\""));
        MvcTestResult duasAbas = gravar(marca, amarelo, v(VISITANTES, "1200"), v("teste.perfil", "3"));

        assertThat(arquivo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(arquivo, "$.campos['teste.arquivo']")).isEqualTo("Este campo recebe um arquivo, que ainda não é aceito por aqui.");
        assertThat(decimal).hasStatus(HttpStatus.OK);
        assertThat(((Number) vigente(decimal, "teste.decimal").get("valor")).doubleValue()).isEqualTo(12.5);
        assertThat(vigente(decimal, "teste.data").get("valor")).isEqualTo("2026-12-01");
        assertThat(((Number) campo(decimal, "teste.decimal").get("maximo")).doubleValue()).isEqualTo(100.0);
        assertThat(lerJson(demais, "$.campos['teste.decimal']")).isEqualTo("Use no máximo 2 casas decimais.");
        assertThat(lerJson(acima, "$.campos['teste.decimal']")).isEqualTo("O valor máximo é 100.");
        assertThat(lerJson(texto, "$.campos['teste.decimal']")).isEqualTo("Informe um número.");
        assertThat(lerJson(texto, "$.campos['teste.data']")).isEqualTo("Informe a data no formato AAAA-MM-DD.");
        assertThat(duasAbas).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(duasAbas, "$.campos")).containsExactly("valores");
        assertThat(lerJson(duasAbas, "$.campos.valores")).isEqualTo("Envie os campos de uma aba só.");
        assertThat(linhas(amarelo, VISITANTES)).isZero();
        // A aba PERFIL, com um campo só dela, é outra consulta da rota 40.
        MvcTestResult doPerfil = gravar(marca, amarelo, v("teste.perfil", "3"));
        assertThat(doPerfil).hasStatus(HttpStatus.OK);
        assertThat(lerJson(doPerfil, "$.aba")).isEqualTo("PERFIL");
        assertThat(lerLista(doPerfil, "$.campos[*].chave")).containsExactly("teste.perfil");
    }

    @Test
    void entreUmEVinteValoresEOCorpoPrecisaDeValores() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        String uri = "/api/parcerias/" + amarelo + "/dados";
        List<String> vinteEUm = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            vinteEUm.add(v(VISITANTES, "1"));
        }

        MvcTestResult vazio = marca.post(uri, "{\"valores\":[]}");
        MvcTestResult demais = gravar(marca, amarelo, vinteEUm.toArray(String[]::new));
        MvcTestResult semLista = marca.post(uri, "{}");
        MvcTestResult chaveEmBranco = marca.post(uri, "{\"valores\":[{\"chave\":\" \",\"valor\":1}]}");
        MvcTestResult semCorpo = marca.post(uri, "");
        MvcTestResult jsonQuebrado = marca.post(uri, "isto nao e json");

        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.valores")).isEqualTo("Envie de 1 a 20 valores.");
        assertThat(demais).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(demais, "$.campos.valores")).isEqualTo("Envie de 1 a 20 valores.");
        assertThat(lerJson(semLista, "$.campos.valores")).isEqualTo("Envie a lista de valores.");
        assertThat(chaveEmBranco).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(chaveEmBranco, "$.campos['valores[0].chave']")).isEqualTo("Informe a chave do campo.");
        assertThat(semCorpo).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(jsonQuebrado).hasStatus(HttpStatus.BAD_REQUEST);
        // Quatro valores distintos cabem (os quatro campos do catálogo), e 20 é o máximo do pedido.
        assertThat(gravar(marca, amarelo, v(VISITANTES, "10"), v(CADASTROS, "5"), v(DISPONIVEIS, "3"), v(DISTRIBUIDOS, "2")))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void abaFechadaDa403ParaAMarcaAntesDeQualquerValidacaoENadaEGravado() {
        long amarelo = amarelo();
        SessaoDeApi marca = entrar(AMARELO);
        fechar(amarelo);

        MvcTestResult certo = gravar(marca, amarelo, v(VISITANTES, "1200"));
        MvcTestResult comErro = gravar(marca, amarelo, v(VISITANTES, "-1"));

        for (MvcTestResult resposta : List.of(certo, comErro)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(ABA_FECHADA);
        }
        assertThat(linhasDaParceria(amarelo)).isZero();
        assertThat(gravar(entrar(RBS), amarelo, v(VISITANTES, "1200"))).as("a RBS passa por aba fechada").hasStatus(HttpStatus.OK);
        jdbc.sql("DELETE FROM cota_tipo_aba WHERE aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES') "
                + "AND cota_tipo_id = (SELECT cota_tipo_id FROM parceria WHERE id = :p)").param("p", amarelo).update();
        assertThat(lerJson(gravar(marca, amarelo, v(VISITANTES, "1")), "$.detail")).isEqualTo(FORA_DA_COTA);
    }

    @Test
    void parceriaDeOutraMarcaOuQueNaoExisteDa404NaGravacao() {
        SessaoDeApi roxo = entrar(ROXO);
        long amarelo = amarelo();

        MvcTestResult deOutra = gravar(roxo, amarelo, v(VISITANTES, "1200"));
        MvcTestResult inexistente = gravar(roxo, 999999, v(VISITANTES, "1200"));
        MvcTestResult naoEhNumero = roxo.post("/api/parcerias/abc/dados", pedido(v(VISITANTES, "1200")));
        MvcTestResult rbsInexistente = gravar(entrar(RBS), 999999, v(VISITANTES, "1200"));

        for (MvcTestResult resposta : List.of(deOutra, inexistente, naoEhNumero, rbsInexistente)) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta parceria.");
        }
        assertThat(linhasDaParceria(amarelo)).isZero();
    }

    @Test
    void semTokenCsrfDa403EsemSessaoDa401() {
        long amarelo = amarelo();
        String uri = "/api/parcerias/" + amarelo + "/dados";

        MvcTestResult semToken = entrar(AMARELO).postSemCsrf(uri, pedido(v(VISITANTES, "1200")));
        MvcTestResult semSessao = SessaoDeApi.anonima(mvc).post(uri, pedido(v(VISITANTES, "1200")));

        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
        assertThat(semSessao).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(linhasDaParceria(amarelo)).isZero();
    }

    // ---------------------------------------------------------------------
    // O serviço que o Atlas reutiliza
    // ---------------------------------------------------------------------

    private UsuarioAutenticado daMarca(String email) {
        String nome = jdbc.sql("SELECT nome FROM usuario WHERE email = :email").param("email", email).query(String.class).single();
        return new UsuarioAutenticado(usuario(email), nome, email, Perfil.ADMIN_PATROCINADOR);
    }

    @Test
    void oServicoGravaComOrigemAtlasEDevolveOsVigentesParaOAtlas() {
        long amarelo = amarelo();
        UsuarioAutenticado marca = daMarca(AMARELO);

        var corpoDaAba = servico.gravar(marca, amarelo, List.of(new ValorRequisicao(VISITANTES, 1200), new ValorRequisicao(CADASTROS, 900)),
                OrigemDoDado.ATLAS, new MockHttpServletRequest());
        servico.gravar(marca, amarelo, List.of(new ValorRequisicao(DISPONIVEIS, 300L)), OrigemDoDado.PORTAL, new MockHttpServletRequest());

        assertThat(corpoDaAba.aba()).isEqualTo("APLICACOES");
        assertThat(corpoDaAba.campos().getFirst().vigente().origem()).isEqualTo("ATLAS");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND origem = 'ATLAS'", amarelo)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'dado_informado' AND detalhe LIKE '%(Atlas)'")).isEqualTo(2);
        Map<String, VigenteDto> vigentes = servico.vigentes(amarelo);
        assertThat(vigentes.keySet()).as("só o que foi informado, na ordem do catálogo").containsExactly(VISITANTES, CADASTROS, DISPONIVEIS);
        assertThat(vigentes.get(VISITANTES).valor()).isEqualTo(1200L);
        assertThat(vigentes.get(VISITANTES).origem()).isEqualTo("ATLAS");
        assertThat(vigentes.get(VISITANTES).informadoPor().nome()).isEqualTo(marca.nome());
        assertThat(vigentes.get(VISITANTES).informadoPor().perfil()).isEqualTo("ADMIN_PATROCINADOR");
        assertThat(vigentes.get(VISITANTES).informadoEm()).isNotNull();
        assertThat(vigentes.get(DISPONIVEIS).origem()).isEqualTo("PORTAL");
        assertThat(servico.vigentes(roxo())).as("o vigente é de cada parceria").isEmpty();
    }

    @Test
    void oServicoDeGravacaoConfereAParceriaDeQuemChama() {
        UsuarioAutenticado doRoxo = daMarca(ROXO);

        assertThatThrownBy(() -> servico.gravar(doRoxo, amarelo(), List.of(new ValorRequisicao(VISITANTES, 1)), OrigemDoDado.ATLAS,
                new MockHttpServletRequest())).isInstanceOf(NaoEncontradoException.class);
        assertThat(linhasDaParceria(amarelo())).isZero();
    }
}
