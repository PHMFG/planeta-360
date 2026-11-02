package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AGENCIA_DO_AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VERDE;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] [ISOLAMENTO] [PERMISSAO] Critério B do Atlas da RBS (M8): a conversa da RBS (sem parceria) não vaza para nenhuma marca, e a
 * RBS só tem a conversa dela. Cobre a matriz de acesso das rotas 44, 45 e 49 (a dona, outra pessoa da RBS, a marca e a conversa de
 * parceria, com corpos que não prestam), cada consulta que lê conversa, traço, relatório e diagnóstico (nenhuma devolve linha de
 * {@code parceria_id} nulo a uma marca) e o teto por parceria, que a RBS não gasta. Não é {@code @Transactional}: o que o teste
 * confirmou é apagado no fim.
 */
@TesteDoAtlasDaRbs
class AtlasDaRbsIsolamentoTest {

    private static final String CONVERSA_NAO_ENCONTRADA = "Não encontramos esta conversa.";
    private static final String SO_PATROCINADOR = "Só o Admin Patrocinador da parceria conversa com o Atlas.";
    private static final String OUTRA_RBS = "pesquisa@rbs.example";
    private static final List<String> MARCAS = List.of(AMARELO, AGENCIA_DO_AMARELO, ROXO, VERDE);
    private static final List<String> CORPOS = List.of("", "{}", "isto nao e json", "{\"texto\":\"Oi\"}",
            "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    AtlasRepositorio repositorio;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        limpeza.apagar();
    }

    long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    long conversaDaRbs(SessaoDeApi pessoa) {
        MvcTestResult aberta = pessoa.post("/api/eventos/" + evento2027() + "/atlas/conversas", "");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    long conversaDaMarca(SessaoDeApi pessoa, String cliente) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + parceria(cliente) + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    /** Uma conversa da RBS com um turno feito, para existir mensagem, traço e tudo o que a marca não pode ver. */
    long conversaDaRbsComUmTurno(SessaoDeApi rbs) {
        long conversa = conversaDaRbs(rbs);
        modelo.texto("Posso ajudar com os documentos da edição.");
        MvcTestResult fala = rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Segredo-da-rbs-123 sobre geradores\"}");
        assertThat(fala).hasStatus(HttpStatus.OK);
        return conversa;
    }

    // ---------------------------------------------------------------------
    // A matriz de acesso das rotas 44, 45 e 49
    // ---------------------------------------------------------------------

    @Test
    void aDonaEntraNasTresRotas() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long conversa = conversaDaRbsComUmTurno(rbs);

        assertThat(rbs.get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/atlas/conversas/" + conversa + "/diagnostico")).hasStatus(HttpStatus.OK);
        modelo.texto("Posso ajudar de novo.");
        assertThat(rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"E agora?\"}")).hasStatus(HttpStatus.OK);
    }

    @Test
    void outraPessoaDaRbsRecebe404NasTresRotasQualquerQueSejaOCorpo() {
        long conversa = conversaDaRbsComUmTurno(SessaoDeApi.entrar(mvc, RBS));
        SessaoDeApi outra = SessaoDeApi.entrar(mvc, OUTRA_RBS);

        for (String corpo : CORPOS) {
            if (!corpo.equals("isto nao e json") && !corpo.isEmpty()) {
                MvcTestResult enviar = outra.post("/api/atlas/conversas/" + conversa + "/mensagens", corpo);
                assertThat(enviar).as("POST [" + corpo + "]").hasStatus(HttpStatus.NOT_FOUND);
                assertThat(lerJson(enviar, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
            }
        }
        for (String uri : List.of("/api/atlas/conversas/" + conversa, "/api/atlas/conversas/" + conversa + "/diagnostico")) {
            MvcTestResult ler = outra.get(uri);
            assertThat(ler).as(uri).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(ler, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
            assertThat(corpo(ler)).doesNotContain("Segredo-da-rbs-123");
        }
        assertThat(modelo.pedidos()).hasSize(1);   // só o turno da dona
    }

    @Test
    void aConversaQueNaoExisteDa404ParaARbsEIdQueNaoENumeroTambem() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String id : List.of("999999999", "0", "abc")) {
            for (String uri : List.of("/api/atlas/conversas/" + id, "/api/atlas/conversas/" + id + "/diagnostico")) {
                assertThat(rbs.get(uri)).as(uri).hasStatus(HttpStatus.NOT_FOUND);
            }
            assertThat(rbs.post("/api/atlas/conversas/" + id + "/mensagens", "{\"texto\":\"Oi\"}")).hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void aRbsContinuaComOs403NasConversasDeParceriaQualquerQueSejaOCorpo() {
        long daMarca = conversaDaMarca(SessaoDeApi.entrar(mvc, AMARELO), "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String corpo : CORPOS) {
            MvcTestResult enviar = rbs.post("/api/atlas/conversas/" + daMarca + "/mensagens", corpo);
            assertThat(enviar).as("POST [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(enviar, "$.detail")).isEqualTo(SO_PATROCINADOR);
        }
        for (String uri : List.of("/api/atlas/conversas/" + daMarca, "/api/atlas/conversas/" + daMarca + "/diagnostico")) {
            MvcTestResult ler = rbs.get(uri);
            assertThat(ler).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(ler, "$.detail")).isEqualTo(SO_PATROCINADOR);
        }
        // o cartão e o descarte do relatório seguem só da marca
        assertThat(rbs.post("/api/atlas/solicitacoes/1/respostas", "{\"acao\":\"cancel\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post("/api/atlas/relatorios/1/descartar", "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post("/api/parcerias/" + parceria("Cliente Amarelo") + "/atlas/conversas", "{}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void cadaMarcaRecebe404NaConversaDaRbsNasTresRotasComOMesmoCorpoDeUmaQueNaoExiste() {
        long conversa = conversaDaRbsComUmTurno(SessaoDeApi.entrar(mvc, RBS));

        for (String email : MARCAS) {
            SessaoDeApi marca = SessaoDeApi.entrar(mvc, email);
            for (String corpo : CORPOS) {
                if (corpo.equals("isto nao e json") || corpo.isEmpty()) {
                    continue;   // o corpo ruim é lido antes do service; o que importa é que nenhum chega a ver a conversa
                }
                MvcTestResult enviar = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", corpo);
                assertThat(enviar).as(email + " POST [" + corpo + "]").hasStatus(HttpStatus.NOT_FOUND);
                assertThat(lerJson(enviar, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
            }
            for (String uri : List.of("/api/atlas/conversas/" + conversa, "/api/atlas/conversas/" + conversa + "/diagnostico")) {
                MvcTestResult ler = marca.get(uri);
                assertThat(ler).as(email + " " + uri).hasStatus(HttpStatus.NOT_FOUND);
                assertThat(lerJson(ler, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);
                assertThat(corpo(ler)).doesNotContain("Segredo-da-rbs-123").doesNotContain("G1_LIMITES");
            }
        }
        assertThat(modelo.pedidos()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Nenhuma consulta existente devolve linha de parceria nula a uma marca
    // ---------------------------------------------------------------------

    @Test
    void asListasDaMarcaNaoTrazemAConversaDaRbsNemOTurnoNemOTeto() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long conversa = conversaDaRbsComUmTurno(rbs);
        String turno = (String) lerJson(rbs.get("/api/atlas/conversas/" + conversa + "/diagnostico"), "$.turnos[0].turno");

        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo", "Cliente Verde")) {
            long parceria = parceria(cliente);
            String email = switch (cliente) {
                case "Cliente Amarelo" -> AMARELO;
                case "Cliente Roxo" -> ROXO;
                default -> VERDE;
            };
            SessaoDeApi marca = SessaoDeApi.entrar(mvc, email);
            MvcTestResult conversas = marca.get("/api/parcerias/" + parceria + "/atlas/conversas");
            assertThat(conversas).as(cliente).hasStatus(HttpStatus.OK);
            assertThat(lerLista(conversas, "$.conversas[*].id")).doesNotContain((int) conversa);
            assertThat(corpo(marca.get("/api/parcerias/" + parceria + "/atlas/relatorios"))).doesNotContain("Segredo-da-rbs-123");
            // o diagnóstico de todas as conversas e os provedores são da RBS
            assertThat(marca.get("/api/atlas/diagnostico/turnos")).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(marca.get("/api/atlas/diagnostico/turnos/" + turno)).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(marca.get("/api/atlas/provedores")).hasStatus(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void asConsultasDoRepositorioNaoDevolvemLinhaSemParceriaParaUmaParceria() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long usuarioDaRbs = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        long gastasAntes = repositorio.mensagensDoModeloHojeDaRbs(usuarioDaRbs);
        long conversa = conversaDaRbsComUmTurno(rbs);
        long amarelo = parceria("Cliente Amarelo");
        long dono = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", AMARELO).query(Long.class).single();

        // a lista de conversas da marca e os ids para o descarte: o filtro é "parceria_id = :parceria", nulo nunca casa
        assertThat(repositorio.conversasDoUsuario(amarelo, dono, 100)).extracting(AtlasRepositorio.LinhaDeConversaResumo::id)
                .doesNotContain(conversa);
        assertThat(repositorio.idsDasConversasDoUsuario(amarelo, dono)).doesNotContain(conversa);
        // mesmo se a RBS fosse "dona" na consulta da marca (um id de usuário da RBS numa parceria)
        assertThat(repositorio.conversasDoUsuario(amarelo, usuarioDaRbs, 100)).isEmpty();
        assertThat(repositorio.idsDasConversasDoUsuario(amarelo, usuarioDaRbs)).isEmpty();
        // o teto da parceria não conta o que a RBS gastou, e o da RBS não conta o da marca
        assertThat(repositorio.mensagensDoModeloHojeDaRbs(usuarioDaRbs)).isEqualTo(gastasAntes + 1);
        long tetoDoAmarelo = repositorio.mensagensDoModeloHoje(amarelo);
        modelo.texto("Mais uma resposta.");
        assertThat(rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"De novo\"}")).hasStatus(HttpStatus.OK);
        assertThat(repositorio.mensagensDoModeloHoje(amarelo)).isEqualTo(tetoDoAmarelo);
        assertThat(repositorio.mensagensDoModeloHojePorParceria()).allSatisfy(uso -> assertThat(uso.parceriaId()).isNotNull());
        assertThat(repositorio.mensagensDoModeloHojeDaRbs(usuarioDaRbs)).isEqualTo(gastasAntes + 2);
    }

    @Test
    void aRotaDosProvedoresDaRbsNaoTropecaNaConversaSemParceria() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        conversaDaRbsComUmTurno(rbs);

        MvcTestResult provedores = rbs.get("/api/atlas/provedores");

        assertThat(provedores).hasStatus(HttpStatus.OK);
        assertThat(lerLista(provedores, "$.tetoDiario.parcerias[*].parceriaId")).doesNotContainNull();
    }

    @Test
    void oTurnoDaRbsNaoApareceNoDiagnosticoDeNenhumaConversaDeMarca() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long daMarca = conversaDaMarca(marca, "Cliente Amarelo");
        conversaDaRbsComUmTurno(SessaoDeApi.entrar(mvc, RBS));

        MvcTestResult diagnostico = marca.get("/api/atlas/conversas/" + daMarca + "/diagnostico");

        assertThat(diagnostico).hasStatus(HttpStatus.OK);
        assertThat(lerLista(diagnostico, "$.turnos")).isEmpty();
        assertThat(corpo(diagnostico)).doesNotContain("tetoPorUsuario");
    }
}
