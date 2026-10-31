package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AGENCIA_DO_AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * [ATLAS] As rotas 49 a 51 do diagnóstico, como o front as usa (login, sessão e CSRF): o diagnóstico da conversa para o dono dela, e
 * os turnos de todas as conversas e o detalhe de um turno para a RBS. Confere o formato (turnos, etapas, tokens, custo, o
 * {@code dados} como objeto), os filtros e a paginação da lista e, nos bloqueantes do plano, o isolamento: a marca B recebe 404 no
 * diagnóstico da conversa da marca A (e a outra pessoa da mesma parceria também), a RBS e quem não entrou recebem 403 e 401, e a marca
 * recebe 403 nas rotas da RBS. Mesmo contexto do DiagnosticoDoTurnoTest (diagnóstico e marca ligados, provedor roteirizado).
 * Não é {@code @Transactional}: o traço é gravado fora de transação e o resumo, por outra thread; o que o teste confirmou é apagado
 * no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class DiagnosticoRotasTest {

    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";
    private static final String CONVERSA_NAO_ENCONTRADA = "Não encontramos esta conversa.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

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

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    long abrirConversa(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return numero(resposta, "$.conversaId");
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return resposta;
    }

    /** Uma conversa do Amarelo com um turno que consultou os dados e respondeu, medindo 120 tokens de entrada e 30 de saída. */
    long conversaComUmTurno(SessaoDeApi marca) {
        long conversa = abrirConversa(marca);
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}").textoComUso("Os números estão no portal.", 120, 30, new BigDecimal("0.0005"));
        falar(marca, conversa, "Como estou?");
        return conversa;
    }

    // ---------------------------------------------------------------------
    // Rota 49: o diagnóstico da conversa, para o dono dela
    // ---------------------------------------------------------------------

    @Test
    void odonoLeOsTurnosDaConversaComEtapasTokensECusto() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = conversaComUmTurno(marca);

        MvcTestResult resposta = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(numero(resposta, "$.conversaId")).isEqualTo(conversa);
        assertThat(lerLista(resposta, "$.turnos")).hasSize(1);
        assertThat(lerLista(resposta, "$.turnos[0].etapas[*].etapa")).containsExactly("G1_LIMITES", "LLM_IDA", "FERRAMENTA", "LLM_IDA",
                "CHECAGEM", "SAIDA");
        assertThat(lerLista(resposta, "$.turnos[0].etapas[*].ordem")).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(lerLista(resposta, "$.turnos[0].etapas[*].resultado")).containsOnly("OK");
        assertThat(lerJson(resposta, "$.turnos[0].resultado")).isEqualTo("OK");
        assertThat(UUID.fromString((String) lerJson(resposta, "$.turnos[0].turno"))).isNotNull();
        assertThat((String) lerJson(resposta, "$.turnos[0].iniciadoEm")).endsWith("-03:00");
        assertThat((String) lerJson(resposta, "$.turnos[0].etapas[1].iniciadaEm")).endsWith("-03:00");
        assertThat(((Number) lerJson(resposta, "$.turnos[0].duracaoMs")).intValue()).isGreaterThanOrEqualTo(0);
        long ultimaMensagem = jdbc.sql("SELECT max(id) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'")
                .param("c", conversa).query(Long.class).single();
        assertThat(numero(resposta, "$.turnos[0].mensagemId")).isEqualTo(ultimaMensagem);
        // o dados de cada etapa é um objeto JSON, não um texto
        assertThat(lerJson(resposta, "$.turnos[0].etapas[0].dados")).isInstanceOf(Map.class);
        assertThat(lerJson(resposta, "$.turnos[0].etapas[0].dados.tetoPorParceria")).isEqualTo(200);
        assertThat(lerJson(resposta, "$.turnos[0].etapas[1].dados.ida")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.turnos[0].etapas[2].dados.ferramenta")).isEqualTo("parceria_consultar");
        // a segunda ida mediu o uso: tokens e custo de lista, que o diagnóstico soma
        assertThat(lerJson(resposta, "$.turnos[0].etapas[3].dados.tokensEntrada")).isEqualTo(120);
        assertThat(lerJson(resposta, "$.turnos[0].etapas[3].dados.custoOrigem")).isEqualTo("RESPOSTA");
        assertThat(lerJson(resposta, "$.turnos[0].etapas[3].dados.modelo")).isEqualTo("modelo-do-roteiro");
        assertThat(lerJson(resposta, "$.turnos[0].tokensEntrada")).isEqualTo(120);
        assertThat(lerJson(resposta, "$.turnos[0].tokensSaida")).isEqualTo(30);
        assertThat(lerJson(resposta, "$.tokensEntrada")).isEqualTo(120);
        assertThat(lerJson(resposta, "$.tokensSaida")).isEqualTo(30);
        assertThat(lerJson(resposta, "$.tokens")).isEqualTo(150);
        assertThat(new BigDecimal(String.valueOf(lerJson(resposta, "$.custoUsd")))).isEqualByComparingTo("0.0005");
        assertThat(new BigDecimal(String.valueOf(lerJson(resposta, "$.turnos[0].custoUsd")))).isEqualByComparingTo("0.0005");
    }

    @Test
    void doisTurnosSaemNaOrdemEOsTotaisSomam() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca);
        modelo.textoComUso("Primeira resposta.", 100, 10, new BigDecimal("0.0001")).textoComUso("Segunda resposta.", 200, 20,
                new BigDecimal("0.0002"));
        falar(marca, conversa, "Um");
        falar(marca, conversa, "Dois");

        MvcTestResult resposta = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");

        assertThat(lerLista(resposta, "$.turnos")).hasSize(2);
        assertThat(lerJson(resposta, "$.turnos[0].tokensEntrada")).isEqualTo(100);
        assertThat(lerJson(resposta, "$.turnos[1].tokensEntrada")).isEqualTo(200);
        assertThat(lerJson(resposta, "$.tokensEntrada")).isEqualTo(300);
        assertThat(lerJson(resposta, "$.tokens")).isEqualTo(330);
        assertThat(new BigDecimal(String.valueOf(lerJson(resposta, "$.custoUsd")))).isEqualByComparingTo("0.0003");
        assertThat((String) lerJson(resposta, "$.turnos[0].iniciadoEm")).isLessThanOrEqualTo((String) lerJson(resposta, "$.turnos[1].iniciadoEm"));
        assertThat(lerJson(resposta, "$.turnos[0].turno")).isNotEqualTo(lerJson(resposta, "$.turnos[1].turno"));
    }

    @Test
    void conversaSemTurnoTemOsTotaisZeradosEAListaVazia() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca);

        MvcTestResult resposta = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.turnos")).isEmpty();
        assertThat(lerJson(resposta, "$.tokens")).isEqualTo(0);
        assertThat(new BigDecimal(String.valueOf(lerJson(resposta, "$.custoUsd")))).isEqualByComparingTo("0");
    }

    @Test
    void aConversaEOsItensDoAssistenteDizemQueODiagnosticoEstaDisponivelEQualOTurno() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca);
        modelo.texto("Posso ajudar com os dados da parceria.");

        MvcTestResult turno = falar(marca, conversa, "Oi");
        MvcTestResult completa = marca.get("/api/atlas/conversas/" + conversa);
        String idDoTurno = (String) lerJson(marca.get("/api/atlas/conversas/" + conversa + "/diagnostico"), "$.turnos[0].turno");

        for (MvcTestResult resposta : List.of(turno, completa)) {
            assertThat(lerJson(resposta, "$.diagnosticoDisponivel")).isEqualTo(true);
            assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].turno")).containsExactly(idDoTurno);
            assertThat(lerLista(resposta, "$.itens[?(@.tipo=='usuario')].turno")).as("a fala da pessoa não leva turno").isEmpty();
        }
        assertThat(corpo(marca.get("/api/parcerias/" + amarelo() + "/atlas/conversas"))).as("a lista de conversas não traz o campo")
                .doesNotContain("diagnosticoDisponivel");
        assertThat(corpo(completa)).doesNotContain("\"turno\":null");
    }

    // ---------------------------------------------------------------------
    // Bloqueante: o diagnóstico de uma conversa é de quem a abriu
    // ---------------------------------------------------------------------

    @Test
    void marcaBRecebe404NoDiagnosticoDaConversaDaMarcaAEAOutraPessoaDaMesmaParceriaTambem() {
        SessaoDeApi marcaA = entrar(AMARELO);
        long conversa = conversaComUmTurno(marcaA);
        String uri = "/api/atlas/conversas/" + conversa + "/diagnostico";

        // outra marca (o Roxo): 404 com a frase de conversa que não existe
        MvcTestResult doRoxo = entrar(ROXO).get(uri);
        assertThat(doRoxo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doRoxo, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
        // outra pessoa da mesma parceria: a conversa é de quem a abriu
        assertThat(entrar(AGENCIA_DO_AMARELO).get(uri)).hasStatus(HttpStatus.NOT_FOUND);
        // não vaza nada do traço no erro
        assertThat(corpo(doRoxo)).doesNotContain("G1_LIMITES").doesNotContain("tokens");
        // o mesmo erro de uma conversa que não existe e de um id que não é número
        MvcTestResult inexistente = entrar(ROXO).get("/api/atlas/conversas/999999/diagnostico");
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(inexistente, "$.detail")).isEqualTo(lerJson(doRoxo, "$.detail"));
        MvcTestResult naoENumero = marcaA.get("/api/atlas/conversas/abc/diagnostico");
        assertThat(naoENumero).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(naoENumero, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
        // o dono continua lendo
        assertThat(marcaA.get(uri)).hasStatus(HttpStatus.OK);
    }

    @Test
    void comAAbaAplicacoesFechadaODonoRecebe403ComAsFrasesDeAbaComoNaRota45() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = conversaComUmTurno(marca);
        String fechar = "UPDATE parceria_aba SET liberada = :liberada WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')";
        jdbc.sql(fechar).param("liberada", false).param("p", amarelo()).update();
        try {
            MvcTestResult diagnostico = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
            MvcTestResult completa = marca.get("/api/atlas/conversas/" + conversa);

            assertThat(diagnostico).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(diagnostico, "$.detail")).isEqualTo(lerJson(completa, "$.detail"))
                    .isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
            assertThat(corpo(diagnostico)).doesNotContain("G1_LIMITES");
        } finally {
            jdbc.sql(fechar).param("liberada", true).param("p", amarelo()).update();
        }
        assertThat(marca.get("/api/atlas/conversas/" + conversa + "/diagnostico")).hasStatus(HttpStatus.OK);
    }

    @Test
    void rbsRecebe403ESemSessaoRecebe401NoDiagnosticoDaConversa() {
        long conversa = conversaComUmTurno(entrar(AMARELO));
        String uri = "/api/atlas/conversas/" + conversa + "/diagnostico";

        MvcTestResult daRbs = entrar(RBS).get(uri);
        assertThat(daRbs).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(daRbs, "$.detail")).isEqualTo("Só o Admin Patrocinador da parceria conversa com o Atlas.");
        assertThat(SessaoDeApi.anonima(mvc).get(uri)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Bloqueante: as rotas 50 a 53 são da RBS
    // ---------------------------------------------------------------------

    @Test
    void marcaRecebe403NasRotasDaRbsDoDiagnostico() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = conversaComUmTurno(marca);
        String turno = (String) lerJson(marca.get("/api/atlas/conversas/" + conversa + "/diagnostico"), "$.turnos[0].turno");

        List<MvcTestResult> respostas = List.of(marca.get("/api/atlas/diagnostico/turnos"),
                marca.get("/api/atlas/diagnostico/turnos/" + turno), marca.get("/api/atlas/provedores"),
                marca.post("/api/atlas/provedores/llm/teste", null), marca.post("/api/atlas/provedores/jev/teste", null),
                marca.post("/api/atlas/provedores/gemini/teste", null));
        for (MvcTestResult resposta : respostas) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
        assertThat(SessaoDeApi.anonima(mvc).get("/api/atlas/diagnostico/turnos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/atlas/provedores")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Rota 50: a lista de turnos
    // ---------------------------------------------------------------------

    @Test
    void rbsListaOsTurnosDeTodasAsConversasDoMaisNovoParaOMaisAntigo() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca);
        modelo.textoComUso("Um.", 10, 1, null).textoComUso("Dois.", 20, 2, null).falha();
        falar(marca, conversa, "Um");
        falar(marca, conversa, "Dois");
        falar(marca, conversa, "Tres");   // a falha do modelo: o turno termina com ERRO
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = rbs.get("/api/atlas/diagnostico/turnos?parceriaId=" + amarelo());

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.pagina")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.tamanho")).isEqualTo(50);
        assertThat(lerJson(resposta, "$.total")).isEqualTo(3);
        assertThat(lerLista(resposta, "$.itens[*].resultado")).containsExactly("ERRO", "OK", "OK");   // o mais novo primeiro
        assertThat(lerLista(resposta, "$.itens[*].tokensEntrada")).containsExactly(0, 20, 10);
        assertThat(lerLista(resposta, "$.itens[*].conversaId")).extracting(n -> ((Number) n).longValue()).containsOnly(conversa);
        assertThat(lerJson(resposta, "$.itens[0].parceria")).isEqualTo("Cliente Amarelo");
        assertThat(numero(resposta, "$.itens[0].parceriaId")).isEqualTo(amarelo());
        assertThat(lerJson(resposta, "$.itens[0].usuario.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat(lerJson(resposta, "$.itens[0].usuario.nome")).isNotNull();
        assertThat(lerJson(resposta, "$.itens[0].etapas")).isEqualTo(3);   // G1_LIMITES, LLM_IDA e SAIDA
        assertThat(lerJson(resposta, "$.itens[1].etapas")).isEqualTo(4);
        assertThat((String) lerJson(resposta, "$.itens[0].iniciadoEm")).endsWith("-03:00");
        assertThat(corpo(resposta)).doesNotContain("\"dados\"");   // a lista não traz as etapas
    }

    @Test
    void osFiltrosDaListaFuncionamEAPaginaCorta() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca);
        modelo.texto("Um.").texto("Dois.").falha();
        falar(marca, conversa, "Um");
        falar(marca, conversa, "Dois");
        falar(marca, conversa, "Tres");
        SessaoDeApi rbs = entrar(RBS);
        String parceria = "parceriaId=" + amarelo();
        LocalDate hoje = java.time.LocalDate.now(br.com.rbs.portal.comum.Datas.BRASILIA);

        // por resultado (maiúsculo ou minúsculo)
        assertThat(lerLista(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&resultado=ERRO"), "$.itens[*].resultado")).containsExactly("ERRO");
        assertThat(lerLista(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&resultado=ok"), "$.itens[*].resultado")).containsExactly("OK", "OK");
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&resultado=RECUSADO"), "$.total")).isEqualTo(0);
        // por parceria: o Roxo não tem turno
        long roxo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?parceriaId=" + roxo), "$.total")).isEqualTo(0);
        // por data: hoje tem; amanhã e ontem não
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&de=" + hoje + "&ate=" + hoje), "$.total")).isEqualTo(3);
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&de=" + hoje.plusDays(1)), "$.total")).isEqualTo(0);
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&ate=" + hoje.minusDays(1)), "$.total")).isEqualTo(0);
        // a página corta, e o total é o de todas
        MvcTestResult primeira = rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&pagina=0&tamanho=2");
        MvcTestResult segunda = rbs.get("/api/atlas/diagnostico/turnos?" + parceria + "&pagina=1&tamanho=2");
        assertThat(lerLista(primeira, "$.itens")).hasSize(2);
        assertThat(lerLista(segunda, "$.itens")).hasSize(1);
        assertThat(lerJson(primeira, "$.total")).isEqualTo(3);
        assertThat(lerJson(segunda, "$.pagina")).isEqualTo(1);
        assertThat(lerLista(primeira, "$.itens[*].turno")).doesNotContainAnyElementsOf(lerLista(segunda, "$.itens[*].turno"));
    }

    @Test
    void filtroInvalidoDa422ComOCampoEOQueNaoEDoTipoDa400() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resultado = rbs.get("/api/atlas/diagnostico/turnos?resultado=TALVEZ");
        assertThat(resultado).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resultado, "$.campos.resultado")).startsWith("Resultado inválido. Use OK, RECUSADO");
        MvcTestResult data = rbs.get("/api/atlas/diagnostico/turnos?de=ontem");
        assertThat(data).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(data, "$.campos.de")).isEqualTo("Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-10-23.");
        assertThat(lerJson(rbs.get("/api/atlas/diagnostico/turnos?de=2026-11-02&ate=2026-11-01"), "$.campos.ate"))
                .isEqualTo("A data final não pode ser anterior à inicial.");
        for (String uri : List.of("/api/atlas/diagnostico/turnos?tamanho=0", "/api/atlas/diagnostico/turnos?tamanho=201",
                "/api/atlas/diagnostico/turnos?pagina=-1")) {
            assertThat(rbs.get(uri)).as(uri).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        MvcTestResult parceriaTexto = rbs.get("/api/atlas/diagnostico/turnos?parceriaId=abc");
        assertThat(parceriaTexto).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(parceriaTexto, "$.detail")).isEqualTo("O parâmetro parceriaId precisa ser um número inteiro.");
    }

    // ---------------------------------------------------------------------
    // Rota 51: um turno
    // ---------------------------------------------------------------------

    @Test
    void rbsLeUmTurnoComAParceriaOUsuarioEAsEtapas() {
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = conversaComUmTurno(marca);
        MvcTestResult daConversa = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        String turno = (String) lerJson(daConversa, "$.turnos[0].turno");
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = rbs.get("/api/atlas/diagnostico/turnos/" + turno);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.turno")).isEqualTo(turno);
        assertThat(numero(resposta, "$.conversaId")).isEqualTo(conversa);
        assertThat(numero(resposta, "$.parceriaId")).isEqualTo(amarelo());
        assertThat(lerJson(resposta, "$.parceria")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(resposta, "$.usuario.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat(lerJson(resposta, "$.usuario.nome")).isEqualTo(jdbc.sql("SELECT nome FROM usuario WHERE email = :e")
                .param("e", AMARELO).query(String.class).single());
        // o mesmo item que o diagnóstico da conversa traz, mais a parceria e quem conversou
        assertThat(lerLista(resposta, "$.etapas[*].etapa")).isEqualTo(lerLista(daConversa, "$.turnos[0].etapas[*].etapa"));
        assertThat(lerJson(resposta, "$.duracaoMs")).isEqualTo(lerJson(daConversa, "$.turnos[0].duracaoMs"));
        assertThat(lerJson(resposta, "$.resultado")).isEqualTo(lerJson(daConversa, "$.turnos[0].resultado"));
        assertThat(lerJson(resposta, "$.tokensEntrada")).isEqualTo(120);
        // e o item da lista (rota 50) concorda com o detalhe
        MvcTestResult lista = rbs.get("/api/atlas/diagnostico/turnos?parceriaId=" + amarelo());
        assertThat(lerJson(lista, "$.itens[0].turno")).isEqualTo(turno);
        assertThat(lerJson(lista, "$.itens[0].duracaoMs")).isEqualTo(lerJson(resposta, "$.duracaoMs"));
        assertThat(lerJson(lista, "$.itens[0].etapas")).isEqualTo(6);
    }

    @Test
    void turnoQueNaoExisteOuIdQueNaoEUmUuidDa404() {
        SessaoDeApi rbs = entrar(RBS);

        for (String id : List.of(UUID.randomUUID().toString(), "123", "nao-e-um-uuid")) {
            MvcTestResult resposta = rbs.get("/api/atlas/diagnostico/turnos/" + id);
            assertThat(resposta).as(id).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos este turno.");
        }
    }
}
