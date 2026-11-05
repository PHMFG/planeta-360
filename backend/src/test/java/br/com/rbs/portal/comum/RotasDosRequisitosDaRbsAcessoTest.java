package br.com.rbs.portal.comum;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * [PERMISSAO] [ISOLAMENTO] A matriz de acesso das rotas novas do plano final (seção 8.1). Lê {@code rotas-requisitos-rbs.tsv}
 * (método, caminho, quem), congelado a partir da coluna "Quem" da seção 5.3, e para cada linha confere:
 * <ul>
 *   <li>a rota existe e o PermissaoAntesDoCorpo a classifica como o arquivo diz (só da RBS, ou das duas pontas);</li>
 *   <li>sem sessão: 401; sem CSRF: 403;</li>
 *   <li>marca em rota só da RBS: 403 antes de ler o corpo (vazio, {@code {}} e JSON quebrado);</li>
 *   <li>marca de outra parceria: 404 em todo verbo; id de destinação de outra parceria: 404;</li>
 *   <li>aba da rota fechada ou oculta: 403 com a frase de aba;</li>
 *   <li>parceria ENCERRADA: escrita recusada;</li>
 *   <li>R5.3.c: nenhuma rota de conteúdo ou de plano comercial tem PATCH nem DELETE.</li>
 * </ul>
 * Cada fase que acrescenta rotas ao arquivo acrescenta também a linha de {@link #DETALHES}: o corpo válido, a aba da rota e se o
 * caminho é de uma parceria. Rota do arquivo sem detalhe reprova o teste. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class RotasDosRequisitosDaRbsAcessoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";

    /** O que o teste precisa saber de cada rota além do arquivo: um corpo válido, a aba que a guarda (nula = nenhuma) e o tipo do {id}. */
    record Detalhe(String corpo, String aba, boolean caminhoDeParceria) {
    }

    record Rota(String metodo, String caminho, String quem) {
        String chave() {
            return metodo + " " + caminho;
        }

        boolean escreve() {
            return !"GET".equals(metodo);
        }
    }

    /** Uma linha por rota do arquivo. Ao acrescentar rota ao arquivo, acrescente a sua aqui. */
    private static final Map<String, Detalhe> DETALHES = Map.ofEntries(
            // F2 · Plano comercial e espaços de documento (rotas 69 a 73). As rotas 70, 72 e 73 são multipart (corpo de teste: formulário sem partes).
            Map.entry("GET /api/parcerias/{id}/plano-comercial", new Detalhe(null, "PERFIL", true)),
            Map.entry("POST /api/parcerias/{id}/plano-comercial", new Detalhe("{}", "PERFIL", true)),
            Map.entry("GET /api/parcerias/{id}/espacos", new Detalhe(null, "PERFIL", true)),
            Map.entry("POST /api/parcerias/{id}/espacos/{papel}/arquivos", new Detalhe("{}", "PERFIL", true)),
            Map.entry("POST /api/parcerias/{id}/formulario-ingressos", new Detalhe("{}", "PERFIL", true)),
            // F4 · Cortesias (rotas 80 a 85). O corpo da 81 repete a cota do Amarelo (25 na Arena de sexta) e o da 83 cabe na Arena de sábado.
            Map.entry("GET /api/parcerias/{id}/cortesias", new Detalhe(null, "CORTESIAS", true)),
            Map.entry("PATCH /api/parcerias/{id}/cortesias/cotas", new Detalhe(
                    "{\"cotas\":[{\"setor\":\"ARENA\",\"dia\":\"SEXTA\",\"quantidade\":25,\"comShuttle\":false,\"acesso\":\"Portão 6\"}]}",
                    "CORTESIAS", true)),
            Map.entry("PATCH /api/eventos/{id}/cortesias", new Detalhe("{\"cortesiasPrazo\":null}", null, false)),
            Map.entry("POST /api/parcerias/{id}/cortesias/destinacoes", new Detalhe(
                    "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"Maria Souza\"}", "CORTESIAS", true)),
            Map.entry("PATCH /api/parcerias/{id}/cortesias/destinacoes/{destId}", new Detalhe("{\"quantidade\":1}", "CORTESIAS", true)),
            Map.entry("DELETE /api/parcerias/{id}/cortesias/destinacoes/{destId}", new Detalhe(null, "CORTESIAS", true)),
            // F3 · Contrato e Credenciamento (rotas 74 a 79). A 76 é multipart: o corpo de teste dela é um formulário sem partes.
            Map.entry("GET /api/parcerias/{id}/contrato", new Detalhe(null, "CONTRATO", true)),
            Map.entry("PUT /api/parcerias/{id}/contrato/signatario", new Detalhe(
                    "{\"nome\":\"Fulana de Tal\",\"email\":\"fulana@marca.com.br\",\"cargo\":\"Diretora\"}", "CONTRATO", true)),
            Map.entry("POST /api/parcerias/{id}/contrato/arquivo", new Detalhe("{}", "CONTRATO", true)),
            Map.entry("GET /api/parcerias/{id}/credenciais", new Detalhe(null, "CREDENCIAMENTO", true)),
            Map.entry("PATCH /api/parcerias/{id}/credenciais", new Detalhe(
                    "{\"credenciais\":[{\"setor\":\"ARENA\",\"beneficiario\":\"MARCA\",\"quantidadePorDia\":20}]}", "CREDENCIAMENTO", true)),
            Map.entry("PATCH /api/eventos/{id}/credenciamento", new Detalhe("{\"inicio\":\"2027-01-05\"}", null, false)),
            // F5 · Texto ou link na entrega e os links de mídia (rotas 86 e 87). O {id} da 86 é de uma entrega de Mídia da parceria do Amarelo.
            Map.entry("POST /api/entregas/{id}/conteudos", new Detalhe("{\"tipo\":\"TEXTO\",\"texto\":\"Texto de teste\"}", "MIDIA", true)),
            Map.entry("GET /api/parcerias/{id}/links-de-midia", new Detalhe(null, "MIDIA", true)),
            // F5 · Comprovação mensal (rotas 88 a 91). A 91 é do lote da edição: só a RBS, com o {id} do evento.
            Map.entry("GET /api/parcerias/{id}/comprovacao-mensal", new Detalhe(null, "MIDIA", true)),
            Map.entry("GET /api/parcerias/{id}/comprovacao-mensal.xlsx", new Detalhe(null, "MIDIA", true)),
            Map.entry("GET /api/parcerias/{id}/comprovacao-mensal.csv", new Detalhe(null, "MIDIA", true)),
            Map.entry("GET /api/eventos/{id}/comprovacao-mensal.xlsx", new Detalhe(null, null, false)),
            // F6 · Abertura da pesquisa da edição (rotas 92 a 95), todas só da RBS. Nenhuma tem aba nem é de uma parceria; na 94 o {id}
            // é o da pesquisa, e o teste põe o da edição (a marca recebe 403 antes de o id ser lido, e a RBS só é chamada sem CSRF).
            Map.entry("GET /api/eventos/{id}/pesquisas", new Detalhe(null, null, false)),
            Map.entry("POST /api/eventos/{id}/pesquisas", new Detalhe("{\"nome\":\"Pesquisa de teste\"}", null, false)),
            Map.entry("PATCH /api/pesquisas/{id}", new Detalhe("{\"metodologia\":\"Teste\"}", null, false)),
            Map.entry("GET /api/pesquisa/perguntas", new Detalhe(null, null, false)),
            // F6 · Pós-venda e números da edição (rotas 96 a 101). Nenhuma é guardada por aba; a 99 tem o id do pós-venda, não o da parceria nem o da edição.
            Map.entry("GET /api/parcerias/{id}/posvenda", new Detalhe(null, null, true)),
            Map.entry("POST /api/parcerias/{id}/posvenda", new Detalhe("{\"texto\":\"Um texto do pós-venda.\"}", null, true)),
            Map.entry("GET /api/eventos/{id}/posvenda", new Detalhe(null, null, false)),
            Map.entry("DELETE /api/posvenda/{id}", new Detalhe(null, null, false)),
            Map.entry("GET /api/eventos/{id}/resultados", new Detalhe(null, null, false)),
            Map.entry("PUT /api/eventos/{id}/resultados", new Detalhe(
                    "{\"indicador\":\"Público total\",\"valor\":80000,\"qualificador\":\"MAIS_DE\",\"unidade\":\"pessoas\",\"fonte\":\"RBS\"}", null, false)));

    /** As rotas que recebem multipart: no teste vão como formulário sem partes (com JSON o Spring responderia 415 antes da permissão). */
    private static final Set<String> MULTIPART = Set.of("POST /api/parcerias/{id}/contrato/arquivo",
            "POST /api/parcerias/{id}/plano-comercial", "POST /api/parcerias/{id}/espacos/{papel}/arquivos",
            "POST /api/parcerias/{id}/formulario-ingressos");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapeamento;

    // ---------------------------------------------------------------------
    // O arquivo e as listas do código
    // ---------------------------------------------------------------------

    static List<Rota> rotas() {
        List<Rota> rotas = new ArrayList<>();
        try (InputStream in = RotasDosRequisitosDaRbsAcessoTest.class.getResourceAsStream("/rotas-requisitos-rbs.tsv")) {
            assertThat(in).as("backend/src/test/resources/rotas-requisitos-rbs.tsv").isNotNull();
            BufferedReader leitor = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linha = leitor.readLine(); linha != null; linha = leitor.readLine()) {
                if (linha.isBlank() || linha.startsWith("#")) {
                    continue;
                }
                String[] colunas = linha.split("\t", -1);
                assertThat(colunas).as("método, caminho e quem, separados por tabulação: " + linha).hasSize(3);
                assertThat(colunas[2]).as("quem: " + linha).isIn("ambos", "rbs");
                rotas.add(new Rota(colunas[0], colunas[1], colunas[2]));
            }
        } catch (IOException erro) {
            throw new IllegalStateException(erro);
        }
        return rotas;
    }

    @Test
    void oArquivoTemRotasSemRepeticaoETodaRotaTemDetalheNoTeste() {
        List<Rota> rotas = rotas();

        assertThat(rotas).isNotEmpty();
        assertThat(rotas.stream().map(Rota::chave)).doesNotHaveDuplicates();
        assertThat(rotas.stream().map(Rota::chave)).as("rota do arquivo sem linha em DETALHES").allMatch(DETALHES::containsKey);
        assertThat(DETALHES.keySet()).as("DETALHES com rota que não está no arquivo").allMatch(chave -> rotas.stream().anyMatch(r -> r.chave().equals(chave)));
    }

    @Test
    void todaRotaDoArquivoExisteEEstaClassificadaComoOPlanoDiz() {
        Set<String> mapeadas = new TreeSet<>();
        mapeamento.getHandlerMethods().keySet().forEach(info -> {
            for (String padrao : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod metodo : info.getMethodsCondition().getMethods()) {
                    mapeadas.add(metodo + " " + padrao);
                }
            }
        });

        for (Rota rota : rotas()) {
            assertThat(mapeadas).as("rota " + rota.chave() + " não está mapeada num controller").contains(rota.chave());
            if ("rbs".equals(rota.quem())) {
                assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS).as(rota.chave() + " é só da RBS").contains(rota.chave());
            } else {
                assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS).as(rota.chave() + " é das duas pontas").doesNotContain(rota.chave());
                assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR).doesNotContain(rota.chave());
                assertThat(PermissaoAntesDoCorpo.ROTAS_DA_CONVERSA_DO_ATLAS).doesNotContain(rota.chave());
            }
        }
    }

    @Test
    void conteudoEPlanoComercialNaoTemPatchNemDelete() {
        for (Rota rota : rotas()) {
            if (rota.caminho().contains("conteudos") || rota.caminho().contains("plano-comercial")) {
                assertThat(rota.metodo()).as("R5.3.c: a marca só visualiza, sem editar nem apagar: " + rota.chave())
                        .isIn("GET", "POST");
            }
        }
        // A conferência no código, não só no arquivo: nenhum controller mapeia PATCH, PUT nem DELETE de conteúdo ou de plano comercial.
        mapeamento.getHandlerMethods().keySet().forEach(info -> {
            for (String padrao : info.getPathPatternsCondition().getPatternValues()) {
                if (padrao.contains("conteudos") || padrao.contains("plano-comercial")) {
                    assertThat(info.getMethodsCondition().getMethods())
                            .as("R5.3.c: " + padrao + " só pode ter GET e POST")
                            .isNotEmpty()
                            .allMatch(m -> m == RequestMethod.GET || m == RequestMethod.POST);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Quem chama
    // ---------------------------------------------------------------------

    private long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    private long eventoDoAmarelo() {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :id").param("id", amarelo()).query(Long.class).single();
    }

    private long destinacaoDe(String cliente) {
        return jdbc.sql("""
                SELECT d.id FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                WHERE cc.parceria_id = :p ORDER BY d.id LIMIT 1
                """).param("p", SessaoDeApi.parceriaDe2027(jdbc, cliente)).query(Long.class).single();
    }

    /** Uma entrega de Mídia do Amarelo que ainda aceita envio da marca (a rota 86 tem o id de uma entrega, não o de uma parceria). */
    private long entregaDeMidiaDoAmarelo() {
        return jdbc.sql("""
                INSERT INTO entrega (parceria_id, aba_id, titulo, status)
                VALUES (:p, (SELECT id FROM aba WHERE codigo = 'MIDIA'), 'Entrega da matriz de acesso', 'AGUARDANDO_ENVIO')
                RETURNING id
                """).param("p", amarelo()).query(Long.class).single();
    }

    /** O caminho da rota com o {id} da parceria do Amarelo (ou da edição, ou da entrega) e o {destId} dado. */
    private String uri(Rota rota, long destinacao) {
        long id = rota.caminho().startsWith("/api/entregas/") ? entregaDeMidiaDoAmarelo()
                : DETALHES.get(rota.chave()).caminhoDeParceria() ? amarelo() : eventoDoAmarelo();
        return rota.caminho().replace("{id}", String.valueOf(id)).replace("{destId}", String.valueOf(destinacao)).replace("{papel}", "DOCUMENTO_INGRESSOS");
    }

    private String uri(Rota rota) {
        return uri(rota, destinacaoDe("Cliente Amarelo"));
    }

    private MvcTestResult chamar(SessaoDeApi sessao, Rota rota, String uri, String corpo) {
        if (MULTIPART.contains(rota.chave())) {
            return multipart(sessao, uri, true);
        }
        return switch (rota.metodo()) {
            case "GET" -> sessao.get(uri);
            case "POST" -> sessao.post(uri, corpo);
            case "PUT" -> sessao.put(uri, corpo);
            case "PATCH" -> sessao.patch(uri, corpo);
            case "DELETE" -> sessao.delete(uri);
            default -> throw new IllegalArgumentException(rota.metodo());
        };
    }

    private MvcTestResult multipart(SessaoDeApi sessao, String uri, boolean comCsrf) {
        var csrf = br.com.rbs.portal.ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri(uri).multipart().cookie(csrf.cookie()).session(sessao.sessao());
        return (comCsrf ? pedido.header("X-XSRF-TOKEN", csrf.token()) : pedido).exchange();
    }

    private MvcTestResult chamarSemCsrf(SessaoDeApi sessao, Rota rota, String uri, String corpo) {
        if (MULTIPART.contains(rota.chave())) {
            return multipart(sessao, uri, false);
        }
        return sessao.semCsrf(org.springframework.http.HttpMethod.valueOf(rota.metodo()), uri, corpo);
    }

    @Test
    void semSessaoRecebe401() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        for (Rota rota : rotas()) {
            assertThat(chamar(anonima, rota, uri(rota), DETALHES.get(rota.chave()).corpo())).as(rota.chave()).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void semTokenCsrfARotaQueEscreveRecebe403() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (Rota rota : rotas().stream().filter(Rota::escreve).toList()) {
            assertThat(chamarSemCsrf(rbs, rota, uri(rota), DETALHES.get(rota.chave()).corpo())).as(rota.chave()).hasStatus(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void marcaEmRotaSoDaRbsRecebe403AntesDeLerOCorpo() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);

        for (Rota rota : rotas().stream().filter(r -> "rbs".equals(r.quem())).toList()) {
            List<String> corpos = rota.escreve() ? java.util.Arrays.asList("", "{}", "isto nao e json", DETALHES.get(rota.chave()).corpo()) : java.util.Arrays.asList((String) null);
            for (String corpo : corpos) {
                MvcTestResult resposta = chamar(marca, rota, uri(rota), corpo);
                assertThat(resposta).as(rota.chave() + " com corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
            }
        }
    }

    @Test
    void marcaDeOutraParceriaRecebe404EmTodoVerbo() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);

        for (Rota rota : rotas().stream().filter(r -> "ambos".equals(r.quem()) && DETALHES.get(r.chave()).caminhoDeParceria()).toList()) {
            MvcTestResult resposta = chamar(roxo, rota, uri(rota), DETALHES.get(rota.chave()).corpo());
            assertThat(resposta).as(rota.chave() + " do Roxo na parceria do Amarelo").hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void idDeOutraParceriaRecebe404ParaAMarcaEParaARbs() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long deOutra = destinacaoDe("Cliente Verde");

        for (Rota rota : rotas().stream().filter(r -> r.caminho().contains("{destId}")).toList()) {
            for (SessaoDeApi sessao : List.of(marca, rbs)) {
                assertThat(chamar(sessao, rota, uri(rota, deOutra), DETALHES.get(rota.chave()).corpo())).as(rota.chave() + " com id da Verde na URL do Amarelo")
                        .hasStatus(HttpStatus.NOT_FOUND);
            }
        }
    }

    @Test
    void abaDaRotaFechadaOuOcultaRecebe403ComAFraseDeAba() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (Rota rota : rotas().stream().filter(r -> "ambos".equals(r.quem()) && DETALHES.get(r.chave()).aba() != null).toList()) {
            String aba = DETALHES.get(rota.chave()).aba();
            for (String coluna : List.of("liberada", "visivel")) {
                jdbc.sql("UPDATE parceria_aba SET " + coluna + " = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                        .param("p", amarelo()).param("a", aba).update();
                String uri = uri(rota);
                MvcTestResult resposta = chamar(marca, rota, uri, DETALHES.get(rota.chave()).corpo());
                assertThat(resposta).as(rota.chave() + " com a aba " + aba + " (" + coluna + " falso)").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(VerificadorDeAcesso.ABA_NAO_LIBERADA);
                assertThat(chamar(rbs, rota, uri, DETALHES.get(rota.chave()).corpo()).getResponse().getStatus())
                        .as(rota.chave() + ": a RBS passa pela aba fechada").isNotIn(401, 403);
                jdbc.sql("UPDATE parceria_aba SET " + coluna + " = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                        .param("p", amarelo()).param("a", aba).update();
            }
        }
    }

    @Test
    void rotaComParceriaEAbaAntesDoCorpoDa404EDa403MesmoComCorpoQuebrado() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        for (Rota rota : rotas().stream().filter(r -> PermissaoAntesDoCorpo.ROTAS_DA_ABA_ANTES_DO_CORPO.containsKey(r.chave())).toList()) {
            String aba = PermissaoAntesDoCorpo.ROTAS_DA_ABA_ANTES_DO_CORPO.get(rota.chave());
            for (String corpo : List.of("", "{}", "isto nao e json")) {
                assertThat(chamar(roxo, rota, uri(rota), corpo)).as(rota.chave() + " do Roxo [" + corpo + "]").hasStatus(HttpStatus.NOT_FOUND);
            }
            jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                    .param("p", this.amarelo()).param("a", aba).update();
            for (String corpo : List.of("", "{}", "isto nao e json")) {
                MvcTestResult resposta = chamar(amarelo, rota, uri(rota), corpo);
                assertThat(resposta).as(rota.chave() + " com a aba fechada [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(VerificadorDeAcesso.ABA_NAO_LIBERADA);
            }
        }
    }

    @Test
    void parceriaEncerradaRecusaAEscritaDeTodaRotaDeParceria() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        List<Rota> rotas = rotas().stream().filter(r -> r.escreve() && DETALHES.get(r.chave()).caminhoDeParceria()).toList();
        Map<Rota, String> uris = new java.util.LinkedHashMap<>();
        rotas.forEach(r -> uris.put(r, uri(r)));
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :p").param("p", amarelo()).update();

        for (Rota rota : rotas) {
            for (SessaoDeApi sessao : "rbs".equals(rota.quem()) ? List.of(rbs) : List.of(rbs, marca)) {
                int status = chamar(sessao, rota, uris.get(rota), DETALHES.get(rota.chave()).corpo()).getResponse().getStatus();
                assertThat(status).as(rota.chave() + " com a parceria encerrada").isIn(409, 422);
            }
        }
    }
}
