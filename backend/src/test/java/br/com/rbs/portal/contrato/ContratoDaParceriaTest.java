package br.com.rbs.portal.contrato;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.ApoioDeDireitos;
import br.com.rbs.portal.comum.Datas;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5.7.a e R5.7.b (rotas 74 a 76): o signatário que a marca informa, o contrato assinado que a RBS envia, as versões e o dado pessoal.
 * [CONTRATO] O canário prova a regra D8: o nome do signatário fica só na tabela {@code contrato}, e nenhuma outra coluna de texto, nenhum
 * log e nenhuma tela de histórico o repetem; o controle positivo prova que a varredura enxerga o canário onde ele está. Cada teste é
 * desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ContratoDaParceriaTest extends ApoioDeDireitos {

    private static final String CONTRATO = "/api/parcerias/%d/contrato";
    private static final String SIGNATARIO = "/api/parcerias/%d/contrato/signatario";
    private static final String CORPO = "{\"nome\":\"Fulana de Tal\",\"email\":\"fulana@marca.com.br\",\"cargo\":\"Diretora de Marketing\"}";

    private final Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> captura;
    private Level nivelAnterior;

    @BeforeEach
    void ligarCaptura() {
        captura = new ListAppender<>();
        captura.start();
        raiz.addAppender(captura);
        nivelAnterior = raiz.getLevel();
        raiz.setLevel(Level.DEBUG);   // DEBUG, o mais verboso que o portal usa; o TRACE do Spring e do driver mostra o corpo e os parâmetros por conta própria
    }

    @AfterEach
    void desligarCaptura() {
        raiz.detachAppender(captura);
        raiz.setLevel(nivelAnterior);
    }

    private String uri(String modelo, long parceria) {
        return modelo.formatted(parceria);
    }

    private static String corpoDe(String nome, String email, String cargo) {
        return "{\"nome\":%s,\"email\":%s,\"cargo\":%s}".formatted(json(nome), json(email), json(cargo));
    }

    private static String json(String texto) {
        return texto == null ? "null" : "\"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void semContratoNaParceria(long parceria) {
        jdbc.sql("DELETE FROM contrato WHERE parceria_id = :p").param("p", parceria).update();
    }

    // ---------------------------------------------------------------------
    // R5.7.a · o signatário
    // ---------------------------------------------------------------------

    @Test
    void semContratoOGetDevolveSignatarioNuloEListaVazia() {
        semContratoNaParceria(roxo());

        MvcTestResult resposta = entrar(ROXO).get(uri(CONTRATO, roxo()));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.parceriaId")).isEqualTo((int) roxo());
        assertThat(lerJson(resposta, "$.signatario")).isNull();
        assertThat(lerJson(resposta, "$.arquivo")).isNull();
        assertThat(lerLista(resposta, "$.versoes")).isEmpty();
    }

    @Test
    void marcaGravaOSignatarioERbsVeComNomeCargoEmailEData() {
        semContratoNaParceria(roxo());
        SessaoDeApi marca = entrar(ROXO);

        MvcTestResult gravado = marca.put(uri(SIGNATARIO, roxo()), CORPO);

        assertThat(gravado).hasStatus(HttpStatus.OK);
        MvcTestResult daRbs = entrar(RBS).get(uri(CONTRATO, roxo()));
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(lerJson(daRbs, "$.signatario.nome")).isEqualTo("Fulana de Tal");
        assertThat(lerJson(daRbs, "$.signatario.cargo")).isEqualTo("Diretora de Marketing");
        assertThat(lerJson(daRbs, "$.signatario.email")).isEqualTo("fulana@marca.com.br");
        assertThat(lerJson(daRbs, "$.signatario.informadoEm")).as("a RBS vê quando foi informado").isNotNull();
        assertThat(lerJson(daRbs, "$.signatario.informadoPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        // E a marca, ao recarregar, vê o mesmo.
        assertThat(lerJson(marca.get(uri(CONTRATO, roxo())), "$.signatario.nome")).isEqualTo("Fulana de Tal");
    }

    @Test
    void gravarDeNovoTrocaOSignatarioENaoCriaOutraLinha() {
        SessaoDeApi marca = entrar(ROXO);
        assertThat(marca.put(uri(SIGNATARIO, roxo()), CORPO)).hasStatus(HttpStatus.OK);

        MvcTestResult troca = marca.put(uri(SIGNATARIO, roxo()), corpoDe("Beltrano da Silva", "beltrano@marca.com.br", null));

        assertThat(troca).hasStatus(HttpStatus.OK);
        assertThat(lerJson(troca, "$.signatario.nome")).isEqualTo("Beltrano da Silva");
        assertThat(lerJson(troca, "$.signatario.cargo")).isNull();
        assertThat(contar("SELECT count(*) FROM contrato WHERE parceria_id = ?", roxo())).isEqualTo(1);
    }

    @Test
    void rbsInformaOSignatarioNoLugarDaMarca() {
        MvcTestResult gravado = entrar(RBS).put(uri(SIGNATARIO, roxo()), CORPO);

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(gravado, "$.signatario.informadoPor.perfil")).isEqualTo("ADMIN_RBS");
    }

    @Test
    void emailInvalidoNomeCurtoECargoLongoDao422ComOCampoNaResposta() {
        SessaoDeApi marca = entrar(ROXO);
        long antes = contar("SELECT count(*) FROM historico WHERE entidade = 'contrato'");

        for (String email : List.of("sem-arroba", "a@b", "a b@marca.com.br", "a@@marca.com.br", "", "x@y.z".repeat(60) + "@marca.com.br")) {
            MvcTestResult resposta = marca.put(uri(SIGNATARIO, roxo()), corpoDe("Fulana de Tal", email, null));
            assertThat(resposta).as("e-mail [" + email + "]").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.email")).isNotNull();
        }
        MvcTestResult nomeCurto = marca.put(uri(SIGNATARIO, roxo()), corpoDe("Al", "al@marca.com.br", null));
        assertThat(nomeCurto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(nomeCurto, "$.campos.nome")).isNotNull();
        MvcTestResult nomeLongo = marca.put(uri(SIGNATARIO, roxo()), corpoDe("N".repeat(121), "al@marca.com.br", null));
        assertThat(lerJson(nomeLongo, "$.campos.nome")).isNotNull();
        MvcTestResult cargoLongo = marca.put(uri(SIGNATARIO, roxo()), corpoDe("Fulana de Tal", "f@marca.com.br", "C".repeat(81)));
        assertThat(cargoLongo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(cargoLongo, "$.campos.cargo")).isNotNull();
        MvcTestResult vazio = marca.put(uri(SIGNATARIO, roxo()), "{}");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.nome")).isNotNull();
        assertThat(lerJson(vazio, "$.campos.email")).isNotNull();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'contrato'")).as("recusa não grava histórico").isEqualTo(antes);
    }

    @Test
    void nulEControleNoTextoDao422EnuncaUm500() {
        SessaoDeApi marca = entrar(ROXO);

        for (String texto : List.of("Fulana\\u0000 de Tal", "Fulana\\u0007 de Tal", "Fulana\\u001b[31m de Tal")) {
            MvcTestResult nome = marca.put(uri(SIGNATARIO, roxo()), "{\"nome\":\"" + texto + "\",\"email\":\"f@marca.com.br\"}");
            assertThat(nome).as("nome " + texto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(nome, "$.campos.nome")).isNotNull();
            MvcTestResult cargo = marca.put(uri(SIGNATARIO, roxo()), "{\"nome\":\"Fulana de Tal\",\"email\":\"f@marca.com.br\",\"cargo\":\"" + texto + "\"}");
            assertThat(cargo).as("cargo " + texto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            MvcTestResult email = marca.put(uri(SIGNATARIO, roxo()), "{\"nome\":\"Fulana de Tal\",\"email\":\"f" + texto + "@marca.com.br\"}");
            assertThat(email).as("e-mail " + texto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    @Test
    void parceriaEncerradaRecusaOSignatarioEOContratoMasContinuaLegivel() {
        encerrar(roxo());
        SessaoDeApi marca = entrar(ROXO);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult signatario = marca.put(uri(SIGNATARIO, roxo()), CORPO);
        MvcTestResult arquivo = enviarContrato(rbs, roxo(), "contrato.pdf", pdf("c"), null, null);

        assertThat(signatario).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(signatario, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita alterações.");
        assertThat(rbs.put(uri(SIGNATARIO, roxo()), CORPO)).hasStatus(HttpStatus.CONFLICT);
        assertThat(arquivo).hasStatus(HttpStatus.CONFLICT);
        assertThat(marca.get(uri(CONTRATO, roxo()))).hasStatus(HttpStatus.OK);
    }

    @Test
    void abaFechadaOuOcultaDa403ParaAMarcaComACorpoQualquerEARbsContinuaPassando() {
        SessaoDeApi marca = entrar(ROXO);
        SessaoDeApi rbs = entrar(RBS);

        fechar(roxo(), "CONTRATO");
        for (String corpo : List.of(CORPO, "{}", "", "isto nao e json")) {
            MvcTestResult resposta = marca.put(uri(SIGNATARIO, roxo()), corpo);
            assertThat(resposta).as("PUT com [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(FECHADA);
        }
        assertThat(marca.get(uri(CONTRATO, roxo()))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.get(uri(CONTRATO, roxo()))).hasStatus(HttpStatus.OK);
        assertThat(rbs.put(uri(SIGNATARIO, roxo()), CORPO)).hasStatus(HttpStatus.OK);

        jdbc.sql("UPDATE parceria_aba SET liberada = TRUE WHERE parceria_id = :p").param("p", roxo()).update();
        esconder(roxo(), "CONTRATO");
        assertThat(marca.get(uri(CONTRATO, roxo()))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.put(uri(SIGNATARIO, roxo()), CORPO)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void outraMarcaRecebe404EmTodosOsVerbosAntesDoCorpo() {
        SessaoDeApi amarelo = entrar(AMARELO);

        assertThat(amarelo.get(uri(CONTRATO, roxo()))).hasStatus(HttpStatus.NOT_FOUND);
        for (String corpo : List.of(CORPO, "{}", "", "isto nao e json")) {
            assertThat(amarelo.put(uri(SIGNATARIO, roxo()), corpo)).as("PUT com [" + corpo + "]").hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(amarelo.get(uri(CONTRATO, 999_999))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contar("SELECT count(*) FROM contrato WHERE signatario_nome = 'Fulana de Tal'")).isZero();
    }

    @Test
    void historicoDizSoQueOSignatarioMudouSemNomeEmailNemCargo() {
        semContratoNaParceria(roxo());
        SessaoDeApi marca = entrar(ROXO);

        marca.put(uri(SIGNATARIO, roxo()), CORPO);
        marca.put(uri(SIGNATARIO, roxo()), corpoDe("Beltrano da Silva", "beltrano@marca.com.br", "Gerente"));

        List<Map<String, Object>> linhas = jdbc.sql("SELECT acao, valor_anterior, valor_novo, detalhe FROM historico WHERE entidade = 'contrato' ORDER BY id")
                .query().listOfRows();
        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0)).containsEntry("acao", "CRIACAO").containsEntry("detalhe", "Signatário informado");
        assertThat(linhas.get(1)).containsEntry("acao", "EDICAO").containsEntry("detalhe", "Signatário alterado");
        for (Map<String, Object> linha : linhas) {
            assertThat(linha.get("valor_anterior")).isNull();
            assertThat(linha.get("valor_novo")).isNull();
        }
    }

    // ---------------------------------------------------------------------
    // R5.7.b · o contrato assinado
    // ---------------------------------------------------------------------

    @Test
    void rbsEnviaV1EV2AMarcaVeAUltimaNoContratoEBaixaAsDuas() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(ROXO);
        byte[] primeira = pdf("contrato versao um");
        byte[] segunda = pdf("contrato versao dois");

        MvcTestResult v1 = enviarContrato(rbs, roxo(), "contrato_v1.pdf", primeira, "2026-10-20", "Primeira via");
        MvcTestResult v2 = enviarContrato(rbs, roxo(), "contrato_v2.pdf", segunda, null, null);

        assertThat(v1).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(v1, "$.arquivo.versao")).isEqualTo(1);
        assertThat(lerJson(v1, "$.assinadoEm")).isEqualTo("2026-10-20");
        assertThat(v2).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(v2, "$.arquivo.versao")).isEqualTo(2);
        assertThat(lerJson(v2, "$.arquivo.nomeOriginal")).isEqualTo("contrato_v2.pdf");
        assertThat(lerJson(v2, "$.assinadoEm")).as("a data some só se outra for informada").isEqualTo("2026-10-20");

        MvcTestResult daMarca = marca.get(uri(CONTRATO, roxo()));
        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(lerJson(daMarca, "$.arquivo.versao")).as("o visualizador abre a última").isEqualTo(2);
        List<Integer> versoes = lerLista(daMarca, "$.versoes[*].versao");
        assertThat(versoes).containsExactly(2, 1);
        assertThat(lerJson(daMarca, "$.versoes[1].observacao")).isEqualTo("Primeira via");
        int id1 = (Integer) lerJson(daMarca, "$.versoes[1].id");
        int id2 = (Integer) lerJson(daMarca, "$.versoes[0].id");
        assertThat(marca.get("/api/arquivos/" + id1).getResponse().getContentAsByteArray()).isEqualTo(primeira);
        assertThat(marca.get("/api/arquivos/" + id2).getResponse().getContentAsByteArray()).isEqualTo(segunda);
        assertThat(contar("SELECT arquivo_id FROM contrato WHERE parceria_id = ?", roxo())).isEqualTo(id2);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE parceria_id = ? AND categoria = 'CONTRATO' AND entrega_id IS NULL "
                + "AND aba_id = (SELECT id FROM aba WHERE codigo = 'CONTRATO')", roxo())).isEqualTo(2);
        List<Map<String, Object>> historico = jdbc.sql("SELECT acao, valor_anterior, valor_novo FROM historico WHERE entidade = 'contrato' ORDER BY id")
                .query().listOfRows();
        assertThat(historico.stream().map(l -> l.get("acao")).toList()).contains("ENVIO_ARQUIVO", "NOVA_VERSAO");
    }

    @Test
    void aVersaoContinuaDepoisDaVersaoDaEntregaDaAmostra() {
        // O Amarelo já tem o contrato assinado da amostra (v1, numa entrega): o primeiro envio pelo portal é a v2, sem repetir número.
        MvcTestResult v = enviarContrato(entrar(RBS), amarelo(), "novo.pdf", pdf("novo"), null, null);

        assertThat(v).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(v, "$.arquivo.versao")).isEqualTo(2);
        assertThat(lerLista(v, "$.versoes[*].versao")).containsExactly(2, 1);
    }

    @Test
    void marcaRecebe403NoEnvioAntesDeLerOMultipartEOutraMarcaTambem() {
        SessaoDeApi marca = entrar(ROXO);

        for (SessaoDeApi pessoa : new SessaoDeApi[] {marca, entrar(AMARELO)}) {
            assertThat(enviarContrato(pessoa, roxo(), "contrato.pdf", pdf("x"), null, null)).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(enviarContrato(pessoa, roxo(), null, null, null, null)).as("sem arquivo").hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(enviarContrato(SessaoDeApi.anonima(mvc), roxo(), "contrato.pdf", pdf("x"), null, null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enviarContratoSemCsrf(entrar(RBS), roxo(), "contrato.pdf", pdf("x"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE parceria_id = ? AND categoria = 'CONTRATO'", roxo())).isZero();
    }

    @Test
    void arquivoQueNaoEPdfDataNoFuturoEEnvioVazioSaoRecusados() {
        SessaoDeApi rbs = entrar(RBS);

        assertThat(enviarContrato(rbs, roxo(), "contrato.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0}, null, null))
                .as("outro formato").hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviarContrato(rbs, roxo(), "contrato.pdf", "isto nao e um pdf".getBytes(), null, null))
                .as("conteúdo que não é PDF").hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviarContrato(rbs, roxo(), "contrato.docx", pdf("x"), null, null)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        MvcTestResult futuro = enviarContrato(rbs, roxo(), "contrato.pdf", pdf("x"), Datas.hoje().plusDays(1).toString(), null);
        assertThat(futuro).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(futuro, "$.campos.assinadoEm")).isNotNull();
        for (String data : List.of("amanha", "2026-13-40", "26-10-2026", "1999-01-01")) {
            assertThat(enviarContrato(rbs, roxo(), "contrato.pdf", pdf("x"), data, null)).as("data " + data).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        MvcTestResult semArquivo = enviarContrato(rbs, roxo(), null, null, null, null);
        assertThat(semArquivo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semArquivo, "$.campos.arquivo")).isNotNull();
        assertThat(enviarContrato(rbs, roxo(), "contrato.pdf", pdf("x"), null, "x".repeat(501))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(enviarContrato(rbs, roxo(), "contrato.pdf", pdf("x"), null, "ruim\u0000")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE parceria_id = ? AND categoria = 'CONTRATO'", roxo())).isZero();
        assertThat(enviarContrato(rbs, 999_999, "contrato.pdf", pdf("x"), null, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void abaDoContratoFechadaImpedeAMarcaDeBaixarOContratoEARbsBaixa() {
        MvcTestResult enviado = enviarContrato(entrar(RBS), roxo(), "contrato.pdf", pdf("sigiloso"), null, null);
        int arquivo = (Integer) lerJson(enviado, "$.arquivo.id");
        fechar(roxo(), "CONTRATO");

        assertThat(entrar(ROXO).get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(RBS).get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.OK);
        assertThat(entrar(AMARELO).get("/api/arquivos/" + arquivo)).as("outra marca").hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void obancoRecusaApontarOContratoParaArquivoDeOutraCategoria() {
        // O gatilho rn_contrato_arquivo (V16) é a rede de proteção: o service só aponta para o que ele mesmo gravou como CONTRATO.
        long outro = jdbc.sql("SELECT id FROM arquivo WHERE parceria_id = :p AND categoria <> 'CONTRATO' LIMIT 1").param("p", amarelo())
                .query(Long.class).optional().orElse(0L);
        if (outro == 0) {
            return;   // a amostra deste banco não tem outro arquivo do Amarelo; o gatilho tem o próprio teste em CriacaoDoBancoTest
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE contrato SET arquivo_id = :a WHERE parceria_id = :p")
                .param("a", outro).param("p", amarelo()).update()).hasMessageContaining("precisa ser da categoria CONTRATO");
    }

    // ---------------------------------------------------------------------
    // D8 · o signatário é dado pessoal: o canário só pode estar na tabela contrato
    // ---------------------------------------------------------------------

    /** As colunas de texto (e json) de todas as tabelas do esquema, menos {@code contrato}, que achem o canário. */
    private List<String> ondeEsta(String canario, boolean incluindoContrato) {
        List<Map<String, Object>> colunas = jdbc.sql("""
                        SELECT c.table_name, c.column_name FROM information_schema.columns c
                        JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
                        WHERE c.table_schema = 'public' AND c.data_type IN ('text', 'character varying', 'character', 'json', 'jsonb')
                        ORDER BY 1, 2
                        """).query().listOfRows();
        List<String> achados = new ArrayList<>();
        for (Map<String, Object> coluna : colunas) {
            String tabela = (String) coluna.get("table_name");
            String nome = (String) coluna.get("column_name");
            if (!incluindoContrato && "contrato".equals(tabela)) {
                continue;
            }
            long quantas = jdbc.sql("SELECT count(*) FROM \"" + tabela + "\" WHERE \"" + nome + "\"::text ILIKE :padrao")
                    .param("padrao", "%" + canario + "%").query(Long.class).single();
            if (quantas > 0) {
                achados.add(tabela + "." + nome);
            }
        }
        return achados;
    }

    /** Quem logou o texto (logger e nível), para a mensagem do teste dizer onde o signatário vazou. */
    private List<String> quemLogou(String canario) {
        return captura.list.stream().filter(e -> e.getFormattedMessage().contains(canario))
                .map(e -> e.getLevel() + " " + e.getLoggerName()).distinct().toList();
    }

    private boolean logouCanario(String canario) {
        return captura.list.stream().anyMatch(e -> e.getFormattedMessage().contains(canario)
                || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                        && e.getThrowableProxy().getMessage().contains(canario)));
    }

    @Test
    void canarioDoSignatarioSoFicaNaTabelaContratoEmNenhumHistoricoLogOuTela() {
        String canario = "Canario" + UUID.randomUUID().toString().replace("-", "");
        String email = canario.toLowerCase() + "@marca.com.br";
        SessaoDeApi marca = entrar(ROXO);
        SessaoDeApi rbs = entrar(RBS);

        assertThat(marca.put(uri(SIGNATARIO, roxo()), corpoDe(canario, email, "Cargo " + canario))).hasStatus(HttpStatus.OK);
        assertThat(marca.put(uri(SIGNATARIO, roxo()), corpoDe(canario, "email-ruim", null))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.put(uri(SIGNATARIO, roxo()), corpoDe(canario, email, null))).hasStatus(HttpStatus.OK);
        assertThat(enviarContrato(rbs, roxo(), "contrato.pdf", pdf("c"), "2026-10-20", "assinado")).hasStatus(HttpStatus.CREATED);
        marca.get(uri(CONTRATO, roxo()));

        // Controle positivo: a varredura enxerga o canário onde ele está (na tabela contrato) e o log enxerga o que é logado.
        assertThat(ondeEsta(canario, true)).as("controle positivo da varredura").contains("contrato.signatario_nome", "contrato.signatario_email");
        LoggerFactory.getLogger("controle.positivo").info("controle {}", canario);
        assertThat(logouCanario(canario)).as("controle positivo do log").isTrue();
        captura.list.clear();

        // O mesmo caminho de novo, agora medindo: nada fora de contrato, nenhum log, nenhuma tela de histórico.
        assertThat(rbs.put(uri(SIGNATARIO, roxo()), corpoDe(canario, email, null))).hasStatus(HttpStatus.OK);
        assertThat(marca.put(uri(SIGNATARIO, roxo()), corpoDe(canario, "email-ruim", null))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ondeEsta(canario, false)).as("colunas fora de contrato que guardam o signatário").isEmpty();
        assertThat(ondeEsta(canario.toLowerCase(), false)).as("o e-mail também").isEmpty();
        assertThat(logouCanario(canario)).as("o signatário no log: " + quemLogou(canario)).isFalse();
        assertThat(logouCanario(canario.toLowerCase())).as("o e-mail do signatário no log").isFalse();
        for (String tela : List.of("/api/parcerias/" + roxo() + "/historico", "/api/historico", "/api/historico.csv?q=contrato",
                "/api/parcerias/" + roxo() + "/relatorio", "/api/parcerias/" + roxo() + "/entregas", "/api/parcerias/" + roxo(),
                "/api/painel")) {
            SessaoDeApi quem = tela.startsWith("/api/parcerias") ? marca : rbs;
            String texto = corpo(quem.get(tela));
            assertThat(texto).as(tela).doesNotContain(canario).doesNotContain(canario.toLowerCase());
        }
    }
}
