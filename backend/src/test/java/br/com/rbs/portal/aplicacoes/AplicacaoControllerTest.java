package br.com.rbs.portal.aplicacoes;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 37 (o plano de aplicações de marca com as fotos) e 38 (anexar uma foto, só da RBS) como o front as usa: login
 * pela rota, sessão de verdade, multipart e token CSRF. [RELATORIO] [ISOLAMENTO] [PERMISSAO] [UPLOAD] [HISTORICO] Os números
 * esperados são conferidos pela rota 29 e por SQL direto nas tabelas. Cada teste é desfeito no fim (@Transactional). Na
 * amostra, o Amarelo tem 9 linhas de aplicação (19 previstas); todas têm tantas fotos quanto o previsto, menos o Lounge (nenhuma)
 * e o Shuttle (3 de 4). O Roxo não tem aplicação no plano. Os arquivos das fotos da amostra não existem em disco no perfil de
 * teste: o download só é testado com a foto que o próprio teste anexa.
 */
@TesteDeIntegracao
@Transactional
class AplicacaoControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";
    private static final String ABA_FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";
    private static final String FORA_DA_COTA = "Esta aba não faz parte da cota da sua parceria.";
    private static final String NAO_ACHOU_A_LINHA = "Não encontramos esta aplicação.";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

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

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    long linhaDoAmarelo(String formato) {
        return contar("SELECT id FROM aproveitamento_previsto WHERE parceria_id = ? AND formato = ?", amarelo(), formato);
    }

    void fechar(long parceriaId) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", parceriaId).update();
    }

    static BigDecimal numero(MvcTestResult resposta, String caminho) {
        return new BigDecimal(String.valueOf(lerJson(resposta, caminho)));
    }

    static long longo(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    /** O JSON da linha com esse id, procurada na resposta da rota 37. */
    static Map<String, Object> linha(MvcTestResult plano, long id) {
        List<Map<String, Object>> linhas = lerLista(plano, "$.linhas");
        return linhas.stream().filter(l -> ((Number) l.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    /** POST /api/parcerias/{id}/aplicacoes/{ap}/comprovacoes como o front: multipart, cookie de sessão, cookie XSRF-TOKEN e cabeçalho. */
    MvcTestResult anexar(SessaoDeApi pessoa, Object parceria, Object aproveitamento, String nome, byte[] bytes, String descricao) {
        return pedido(pessoa, true, parceria, aproveitamento, nome, bytes, descricao);
    }

    MvcTestResult anexarSemCsrf(SessaoDeApi pessoa, Object parceria, Object aproveitamento, String nome, byte[] bytes) {
        return pedido(pessoa, false, parceria, aproveitamento, nome, bytes, null);
    }

    private MvcTestResult pedido(SessaoDeApi pessoa, boolean comCsrf, Object parceria, Object aproveitamento, String nome,
                                 byte[] bytes, String descricao) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/parcerias/" + parceria + "/aplicacoes/" + aproveitamento + "/comprovacoes").multipart()
                .cookie(csrf.cookie()).session(pessoa.sessao());
        if (comCsrf) {
            pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (bytes != null) {
            pedido.file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes));
        }
        if (descricao != null) {
            pedido.param("descricao", descricao);
        }
        return pedido.exchange();
    }

    // ---------------------------------------------------------------------
    // Rota 37
    // ---------------------------------------------------------------------

    @Test
    void rbsVeOPlanoDoAmareloLinhaALinhaComOsMesmosTotaisDaConsolidacao() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();

        MvcTestResult plano = rbs.get("/api/parcerias/" + amarelo + "/aplicacoes");
        MvcTestResult consolidacao = rbs.get("/api/parcerias/" + amarelo + "/consolidacao");

        assertThat(plano).hasStatus(HttpStatus.OK);
        assertThat(longo(plano, "$.parceriaId")).isEqualTo(amarelo);
        // Os totais são exatamente os da rota 29 (campo aplicacoes): a mesma visão e o mesmo corte na primeira casa.
        assertThat(numero(plano, "$.totais.previstas")).isEqualByComparingTo(numero(consolidacao, "$.aplicacoes.previstas"))
                .isEqualByComparingTo("19");
        assertThat(numero(plano, "$.totais.comprovadas")).isEqualByComparingTo(numero(consolidacao, "$.aplicacoes.comprovadas"))
                .isEqualByComparingTo("17");
        assertThat(longo(plano, "$.totais.fotos")).isEqualTo(longo(consolidacao, "$.aplicacoes.fotos")).isEqualTo(17);
        assertThat(numero(plano, "$.totais.percentual")).isEqualByComparingTo(numero(consolidacao, "$.aplicacoes.percentual"))
                .isEqualByComparingTo("89.4");
        // As linhas: só aplicação de marca, na ordem bloco, formato, id, e a soma delas fecha com os totais.
        List<Map<String, Object>> linhas = lerLista(plano, "$.linhas");
        assertThat(linhas).hasSize(9);
        assertThat(linhas).extracting(l -> l.get("bloco")).containsOnly("Aplicações de marca");
        // A ordem é a do contrato (bloco, formato, id), a mesma do SQL.
        assertThat(linhas).extracting(l -> ((Number) l.get("id")).longValue()).containsExactlyElementsOf(
                jdbc.sql("SELECT id FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'APLICACAO_MARCA' "
                        + "ORDER BY bloco, formato, id").param("p", amarelo).query(Long.class).list());
        assertThat(linhas.stream().map(l -> new BigDecimal(String.valueOf(l.get("previsto")))).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("19");
        assertThat(linhas.stream().map(l -> new BigDecimal(String.valueOf(l.get("comprovadas")))).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("17");
        assertThat(linhas.stream().mapToLong(l -> ((Number) l.get("fotos")).longValue()).sum()).isEqualTo(17);
        // O formato de cada linha e de cada foto é o do contrato.
        Map<String, Object> lounge = linha(plano, linhaDoAmarelo("Elemento dentro do Planeta Lounge"));
        assertThat(lounge.keySet()).containsExactly("id", "bloco", "veiculo", "formato", "espacoPrograma", "janela", "previsto",
                "comprovadas", "fotos", "comprovacoes");
        assertThat(lounge.get("veiculo")).isEqualTo("No evento");
        assertThat(lounge.get("espacoPrograma")).isNull();
        assertThat(lounge.get("fotos")).isEqualTo(0);
        assertThat((List<?>) lounge.get("comprovacoes")).isEmpty();
        Map<String, Object> shuttle = linha(plano, linhaDoAmarelo("Elemento no acesso ao Shuttle no centro de Atlântida"));
        assertThat(new BigDecimal(String.valueOf(shuttle.get("previsto")))).isEqualByComparingTo("4");
        assertThat(new BigDecimal(String.valueOf(shuttle.get("comprovadas")))).isEqualByComparingTo("3");
        List<Map<String, Object>> fotos = (List<Map<String, Object>>) shuttle.get("comprovacoes");
        assertThat(fotos).hasSize(3);
        assertThat(fotos.getFirst().keySet()).containsExactly("id", "arquivoId", "nomeOriginal", "descricao", "registradoPor", "registradoEm");
        assertThat(fotos.getFirst().get("descricao")).isEqualTo("Foto do evento");
        assertThat((String) fotos.getFirst().get("registradoEm")).endsWith("-03:00");
        assertThat(((Map<?, ?>) fotos.getFirst().get("registradoPor")).get("perfil")).isEqualTo("ADMIN_RBS");
        // Cada foto tem o arquivo da aba APLICACOES, que é o que a rota 18 baixa.
        assertThat(contar("SELECT count(*) FROM arquivo a JOIN comprovacao_marca c ON c.arquivo_id = a.id WHERE c.parceria_id = ? "
                + "AND a.aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')", amarelo)).isEqualTo(17);
    }

    @Test
    void aMarcaVeOMesmoPlanoDaPropriaParceria() {
        long amarelo = amarelo();

        MvcTestResult daMarca = entrar(AMARELO).get("/api/parcerias/" + amarelo + "/aplicacoes");
        MvcTestResult daRbs = entrar(RBS).get("/api/parcerias/" + amarelo + "/aplicacoes");

        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(corpo(daMarca)).isEqualTo(corpo(daRbs));
        assertThat(lerLista(daMarca, "$.linhas")).hasSize(9);
    }

    @Test
    void parceriaSemAplicacaoNoPlanoVemComTotaisNulosELinhasVazias() {
        long roxo = roxo();

        for (String email : List.of(ROXO, RBS)) {
            MvcTestResult plano = entrar(email).get("/api/parcerias/" + roxo + "/aplicacoes");

            assertThat(plano).as(email).hasStatus(HttpStatus.OK);
            assertThat(longo(plano, "$.parceriaId")).isEqualTo(roxo);
            for (String campo : List.of("previstas", "comprovadas", "fotos", "percentual")) {
                assertThat(lerJson(plano, "$.totais." + campo)).as(campo).isNull();
            }
            assertThat(lerLista(plano, "$.linhas")).isEmpty();
        }
    }

    @Test
    void parceriaDeOutraMarcaOuQueNaoExisteDa404ComAMesmaFrase() {
        SessaoDeApi roxo = entrar(ROXO);
        long amarelo = amarelo();

        MvcTestResult deOutra = roxo.get("/api/parcerias/" + amarelo + "/aplicacoes");
        MvcTestResult inexistente = roxo.get("/api/parcerias/999999/aplicacoes");
        MvcTestResult naoEhNumero = roxo.get("/api/parcerias/abc/aplicacoes");

        for (MvcTestResult resposta : List.of(deOutra, inexistente, naoEhNumero)) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta parceria.");
        }
        assertThat(entrar(RBS).get("/api/parcerias/999999/aplicacoes")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(corpo(deOutra)).doesNotContain("Elemento");
    }

    @Test
    void abaAplicacoesFechadaDa403ParaAMarcaComAsDuasFrasesEARbsContinuaVendo() {
        long amarelo = amarelo();
        SessaoDeApi marca = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);

        fechar(amarelo);
        MvcTestResult fechada = marca.get("/api/parcerias/" + amarelo + "/aplicacoes");
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.title")).isEqualTo("Acesso negado");
        assertThat(lerJson(fechada, "$.detail")).isEqualTo(ABA_FECHADA);
        assertThat(corpo(fechada)).doesNotContain("Elemento");
        assertThat(rbs.get("/api/parcerias/" + amarelo + "/aplicacoes")).hasStatus(HttpStatus.OK);

        // Aba que a cota não inclui: a frase muda, e nem abrindo a aba na parceria ela passa a valer.
        jdbc.sql("DELETE FROM cota_tipo_aba WHERE aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES') "
                + "AND cota_tipo_id = (SELECT cota_tipo_id FROM parceria WHERE id = :p)").param("p", amarelo).update();
        MvcTestResult foraDaCota = marca.get("/api/parcerias/" + amarelo + "/aplicacoes");
        assertThat(foraDaCota).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(foraDaCota, "$.detail")).isEqualTo(FORA_DA_COTA);
    }

    @Test
    void semSessaoDevolve401() {
        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo() + "/aplicacoes")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Rota 38: anexar a foto
    // ---------------------------------------------------------------------

    @Test
    void rbsAnexaAFotoELinhaSemFotoPassaAContarComoComprovada() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);

        MvcTestResult criada = anexar(rbs, amarelo, lounge, "lounge.png", PNG, "  Marca na parede do Lounge  ");

        assertThat(criada).hasStatus(HttpStatus.CREATED);
        assertThat(chaves(criada)).containsExactly("id", "arquivoId", "nomeOriginal", "descricao", "registradoPor", "registradoEm");
        assertThat(lerJson(criada, "$.nomeOriginal")).isEqualTo("lounge.png");
        assertThat(lerJson(criada, "$.descricao")).as("sem os espaços das pontas").isEqualTo("Marca na parede do Lounge");
        assertThat(longo(criada, "$.registradoPor.id")).isEqualTo(rbsId);
        assertThat(lerJson(criada, "$.registradoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat((String) lerJson(criada, "$.registradoEm")).endsWith("-03:00");

        // O arquivo: categoria COMPROVACAO, da parceria e da aba APLICACOES, sem entrega, com nome gerado e o conteúdo enviado.
        Map<String, Object> arquivo = jdbc.sql("""
                        SELECT parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por
                        FROM arquivo WHERE id = :id
                        """).param("id", longo(criada, "$.arquivoId")).query().singleRow();
        assertThat(((Number) arquivo.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(((Number) arquivo.get("aba_id")).longValue()).isEqualTo(contar("SELECT id FROM aba WHERE codigo = 'APLICACOES'"));
        assertThat(arquivo.get("entrega_id")).isNull();
        assertThat(arquivo.get("categoria")).isEqualTo("COMPROVACAO");
        assertThat(arquivo.get("mime_type")).isEqualTo("image/png");
        assertThat(((Number) arquivo.get("tamanho_bytes")).intValue()).isEqualTo(PNG.length);
        assertThat((String) arquivo.get("caminho")).startsWith("parcerias/" + amarelo + "/").doesNotContain("lounge");
        assertThat(((Number) arquivo.get("enviado_por")).longValue()).isEqualTo(rbsId);
        // A comprovação: ligada à linha, com a parceria preenchida pelo gatilho.
        Map<String, Object> foto = jdbc.sql("SELECT aproveitamento_id, parceria_id, arquivo_id, descricao, registrado_por FROM comprovacao_marca WHERE id = :id")
                .param("id", longo(criada, "$.id")).query().singleRow();
        assertThat(((Number) foto.get("aproveitamento_id")).longValue()).isEqualTo(lounge);
        assertThat(((Number) foto.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(((Number) foto.get("arquivo_id")).longValue()).isEqualTo(longo(criada, "$.arquivoId"));
        assertThat(foto.get("descricao")).isEqualTo("Marca na parede do Lounge");

        // A rota 37 e a rota 29 passam a contar a foto: a linha vira comprovada e os totais sobem 17 -> 18 de 19 (94,7).
        MvcTestResult plano = rbs.get("/api/parcerias/" + amarelo + "/aplicacoes");
        Map<String, Object> linha = linha(plano, lounge);
        assertThat(linha.get("fotos")).isEqualTo(1);
        assertThat(new BigDecimal(String.valueOf(linha.get("comprovadas")))).isEqualByComparingTo("1");
        assertThat(((List<?>) linha.get("comprovacoes"))).hasSize(1);
        assertThat(numero(plano, "$.totais.comprovadas")).isEqualByComparingTo("18");
        assertThat(longo(plano, "$.totais.fotos")).isEqualTo(18);
        assertThat(numero(plano, "$.totais.percentual")).isEqualByComparingTo("94.7");
        MvcTestResult consolidacao = rbs.get("/api/parcerias/" + amarelo + "/consolidacao");
        assertThat(numero(consolidacao, "$.aplicacoes.comprovadas")).isEqualByComparingTo("18");
        assertThat(numero(consolidacao, "$.aplicacoes.percentual")).isEqualByComparingTo(numero(plano, "$.totais.percentual"));
        // A marca vê a foto na própria tela.
        MvcTestResult daMarca = entrar(AMARELO).get("/api/parcerias/" + amarelo + "/aplicacoes");
        assertThat(((List<?>) linha(daMarca, lounge).get("comprovacoes"))).hasSize(1);
    }

    @Test
    void fotoAMaisNaLinhaNaoContaComoAplicacaoAMais() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long arena = linhaDoAmarelo("Elemento na Arena");   // previsto 1, já com 1 foto

        assertThat(anexar(rbs, amarelo, arena, "arena2.jpg", JPG, null)).hasStatus(HttpStatus.CREATED);

        MvcTestResult plano = rbs.get("/api/parcerias/" + amarelo + "/aplicacoes");
        Map<String, Object> linha = linha(plano, arena);
        assertThat(linha.get("fotos")).isEqualTo(2);
        assertThat(new BigDecimal(String.valueOf(linha.get("comprovadas")))).as("least(previsto, fotos)").isEqualByComparingTo("1");
        assertThat(numero(plano, "$.totais.comprovadas")).isEqualByComparingTo("17");
        assertThat(longo(plano, "$.totais.fotos")).isEqualTo(18);
        assertThat(lerJson(anexar(rbs, amarelo, arena, "arena3.jpeg", JPG, null), "$.descricao")).as("descrição é opcional").isNull();
    }

    @Test
    void cadaFotoRegistraCriacaoNoHistoricoDaParceria() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);

        MvcTestResult criada = anexar(rbs, amarelo, lounge, "lounge.png", PNG, "Parede");

        var historico = jdbc.sql("SELECT usuario_id, parceria_id, entidade, valor_novo, detalhe FROM historico "
                + "WHERE acao = 'CRIACAO' AND entidade = 'comprovacao_marca' AND entidade_id = :id")
                .param("id", longo(criada, "$.id")).query().listOfRows();
        assertThat(historico).hasSize(1);
        assertThat(((Number) historico.getFirst().get("usuario_id")).longValue()).isEqualTo(rbsId);
        assertThat(((Number) historico.getFirst().get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(historico.getFirst().get("valor_novo")).isEqualTo("lounge.png");
        assertThat(historico.getFirst().get("detalhe")).isEqualTo("Elemento dentro do Planeta Lounge: Parede");
        // A marca vê a criação no histórico da própria parceria.
        MvcTestResult daMarca = entrar(AMARELO).get("/api/parcerias/" + amarelo + "/historico?entidade=comprovacao_marca");
        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(lerLista(daMarca, "$.itens[*].entidadeId")).contains((int) longo(criada, "$.id"));
    }

    @Test
    void patrocinadorRecebe403AntesDeQualquerOutraConferencia() {
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        long fotosAntes = contar("SELECT count(*) FROM comprovacao_marca");

        for (String email : List.of(AMARELO, ROXO)) {
            SessaoDeApi marca = entrar(email);
            MvcTestResult certa = anexar(marca, amarelo, lounge, "x.png", PNG, "x");
            assertThat(certa).as(email + " com tudo certo").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(certa, "$.detail")).isEqualTo(SO_RBS);
            // Parceria alheia, linha que não existe, id que não é número, nenhuma parte: ainda 403 (e não 404 nem 422).
            for (MvcTestResult resposta : List.of(anexar(marca, 999999, lounge, "x.png", PNG, null),
                    anexar(marca, amarelo, 999999, "x.png", PNG, null), anexar(marca, amarelo, "abc", "x.png", PNG, null),
                    anexar(marca, amarelo, lounge, null, null, null), anexar(marca, amarelo, lounge, "x.exe", new byte[]{'M', 'Z'}, "d".repeat(500)))) {
                assertThat(resposta).as(email).hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            }
        }
        assertThat(contar("SELECT count(*) FROM comprovacao_marca")).isEqualTo(fotosAntes);
    }

    @Test
    void linhaDeMidiaDa422ComAFraseDaAplicacaoENadaFicaGravado() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long midia = contar("SELECT min(id) FROM aproveitamento_previsto WHERE parceria_id = ? AND categoria = 'MIDIA'", amarelo);
        long fotosAntes = contar("SELECT count(*) FROM comprovacao_marca");
        long arquivosAntes = contar("SELECT count(*) FROM arquivo");

        MvcTestResult resposta = anexar(rbs, amarelo, midia, "x.png", PNG, null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("A comprovação vale só para aplicação de marca.");
        assertThat(lerJson(resposta, "$.campos.aproveitamentoId")).isEqualTo("A comprovação vale só para aplicação de marca.");
        assertThat(contar("SELECT count(*) FROM comprovacao_marca")).isEqualTo(fotosAntes);
        assertThat(contar("SELECT count(*) FROM arquivo")).isEqualTo(arquivosAntes);
    }

    @Test
    void linhaDeOutraParceriaOuQueNaoExisteDa404AteParaARbs() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long linhaDoRoxo = contar("SELECT min(id) FROM aproveitamento_previsto WHERE parceria_id = ?", roxo());
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");

        MvcTestResult deOutraParceria = anexar(rbs, amarelo, linhaDoRoxo, "x.png", PNG, null);
        MvcTestResult inexistente = anexar(rbs, amarelo, 999999, "x.png", PNG, null);
        MvcTestResult naoEhNumero = anexar(rbs, amarelo, "abc", "x.png", PNG, null);
        MvcTestResult parceriaInexistente = anexar(rbs, 999999, lounge, "x.png", PNG, null);

        for (MvcTestResult resposta : List.of(deOutraParceria, inexistente, naoEhNumero)) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ACHOU_A_LINHA);
        }
        assertThat(parceriaInexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(parceriaInexistente, "$.detail")).isEqualTo("Não encontramos esta parceria.");
    }

    @Test
    void arquivoQueNaoEhFotoOuNaoBateComAExtensaoDa415() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        long fotosAntes = contar("SELECT count(*) FROM comprovacao_marca");
        long arquivosAntes = contar("SELECT count(*) FROM arquivo");
        byte[] executavel = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0};

        MvcTestResult exeComoPng = anexar(rbs, amarelo, lounge, "foto.png", executavel, null);
        MvcTestResult exeComoJpg = anexar(rbs, amarelo, lounge, "foto.jpg", executavel, null);
        MvcTestResult pngComoJpg = anexar(rbs, amarelo, lounge, "foto.jpg", PNG, null);
        MvcTestResult pdf = anexar(rbs, amarelo, lounge, "foto.pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8), null);
        MvcTestResult planilha = anexar(rbs, amarelo, lounge, "foto.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), null);
        MvcTestResult semExtensao = anexar(rbs, amarelo, lounge, "foto", PNG, null);

        for (MvcTestResult resposta : List.of(exeComoPng, exeComoJpg)) {
            assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat((String) lerJson(resposta, "$.detail")).contains("de verdade").contains("renomeado");
        }
        assertThat(lerJson(exeComoPng, "$.detail")).isEqualTo("O conteúdo do arquivo não é um PNG de verdade. Confira se o arquivo não foi renomeado.");
        // Um PNG de verdade com extensão de JPG não bate com ela.
        assertThat(pngComoJpg).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        for (MvcTestResult resposta : List.of(pdf, planilha, semExtensao)) {
            assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Envie uma foto em PNG ou JPG.");
        }
        assertThat(contar("SELECT count(*) FROM comprovacao_marca")).isEqualTo(fotosAntes);
        assertThat(contar("SELECT count(*) FROM arquivo")).isEqualTo(arquivosAntes);
    }

    @Test
    void fotoAusenteOuDescricaoGrandeDemaisDa422ComOsCampos() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");

        MvcTestResult semFoto = anexar(rbs, amarelo, lounge, null, null, "só a descrição");
        MvcTestResult vazia = anexar(rbs, amarelo, lounge, "vazia.png", new byte[0], null);
        MvcTestResult descricaoGrande = anexar(rbs, amarelo, lounge, "x.png", PNG, "d".repeat(201));
        MvcTestResult tudoErrado = anexar(rbs, amarelo, lounge, null, null, "d".repeat(201));
        MvcTestResult noLimite = anexar(rbs, amarelo, lounge, "x.png", PNG, "d".repeat(200));

        assertThat(semFoto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semFoto, "$.campos.arquivo")).isEqualTo("Envie a foto na parte \"arquivo\" do formulário.");
        assertThat(vazia).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(descricaoGrande).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(descricaoGrande, "$.campos.descricao")).isEqualTo("A descrição tem no máximo 200 caracteres.");
        assertThat(tudoErrado).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(new ArrayList<>(((Map<String, Object>) lerJson(tudoErrado, "$.campos")).keySet())).containsExactly("arquivo", "descricao");
        assertThat(noLimite).as("200 caracteres ainda passam").hasStatus(HttpStatus.CREATED);
    }

    @Test
    void semTokenCsrfDa403EsemSessaoDa401() {
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        long fotosAntes = contar("SELECT count(*) FROM comprovacao_marca");

        MvcTestResult semToken = anexarSemCsrf(entrar(RBS), amarelo, lounge, "x.png", PNG);
        MvcTestResult semSessao = anexar(SessaoDeApi.anonima(mvc), amarelo, lounge, "x.png", PNG, null);

        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
        assertThat(semSessao).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(contar("SELECT count(*) FROM comprovacao_marca")).isEqualTo(fotosAntes);
    }

    @Test
    void aMarcaBaixaAFotoPelaRota18EComAbaFechadaRecebe403() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long lounge = linhaDoAmarelo("Elemento dentro do Planeta Lounge");
        MvcTestResult criada = anexar(rbs, amarelo, lounge, "lounge.png", PNG, null);
        long arquivo = longo(criada, "$.arquivoId");
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult aberta = marca.get("/api/arquivos/" + arquivo);
        assertThat(aberta).hasStatus(HttpStatus.OK);
        assertThat(aberta.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(aberta.getResponse().getContentType()).isEqualTo("image/png");

        // [PERMISSAO] A foto foi gravada com a aba APLICACOES: com a aba fechada, o download dá 403, como a rota 37 e a lista de entregas.
        long baixadas = contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", arquivo);
        fechar(amarelo);
        MvcTestResult fechada = marca.get("/api/arquivos/" + arquivo);
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo(ABA_FECHADA);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", arquivo))
                .as("o 403 não grava download").isEqualTo(baixadas);
        assertThat(rbs.get("/api/arquivos/" + arquivo)).as("a RBS baixa com a aba fechada").hasStatus(HttpStatus.OK);
        // Outra marca nunca baixa a foto: 404.
        assertThat(entrar(ROXO).get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Ajudas de leitura
    // ---------------------------------------------------------------------

    private static List<String> chaves(MvcTestResult resposta) {
        return new ArrayList<>(((Map<String, Object>) lerJson(resposta, "$")).keySet());
    }
}
