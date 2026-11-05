package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5.3.a e R5.3.c (rota 86): a marca e a RBS enviam texto ou link numa entrega, cada envio é uma versão nova (com autor, data e histórico) e
 * nada se edita depois. R4.3: o link só vale com http(s), host e sem usuario@. [PERMISSAO] [ISOLAMENTO] [HISTORICO] Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class ConteudoDaEntregaTest extends ApoioDeMateriais {

    private static String json(String tipo, String texto, String url) {
        return "{\"tipo\":%s,\"texto\":%s,\"url\":%s}".formatted(aspas(tipo), aspas(texto), aspas(url));
    }

    private static String aspas(String valor) {
        if (valor == null) {
            return "null";
        }
        StringBuilder saida = new StringBuilder("\"");
        for (char c : valor.toCharArray()) {
            switch (c) {
                case '"' -> saida.append("\\\"");
                case '\\' -> saida.append("\\\\");
                case '\n' -> saida.append("\\n");
                default -> {
                    if (c < 0x20) {
                        saida.append("\\u%04x".formatted((int) c));
                    } else {
                        saida.append(c);
                    }
                }
            }
        }
        return saida.append('"').toString();
    }

    private MvcTestResult enviarConteudo(SessaoDeApi pessoa, long entrega, String corpo) {
        return pessoa.post("/api/entregas/" + entrega + "/conteudos", corpo);
    }

    private long entregaDeMidia() {
        return criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Textos das citações");
    }

    // ---------------------------------------------------------------------
    // Texto e link viram versões
    // ---------------------------------------------------------------------

    @Test
    void marcaEnviaTextoEEleViraAVersao1ComAutorDataEHistorico() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult resposta = enviarConteudo(marca, entrega, json("TEXTO", "  Briefing completo\ncom acentuação e emoji 😀  ", null));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("TEXTO");
        assertThat(lerJson(resposta, "$.versao")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.texto")).isEqualTo("Briefing completo\ncom acentuação e emoji 😀");
        assertThat(lerJson(resposta, "$.url")).isNull();
        assertThat(lerJson(resposta, "$.enviadoPor.id")).isEqualTo((int) usuarioId(AMARELO));
        assertThat(lerJson(resposta, "$.enviadoPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat((String) lerJson(resposta, "$.enviadoEm")).endsWith("-03:00");
        assertThat(lerJson(resposta, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ? AND tipo = 'TEXTO' AND enviado_por = ?", entrega,
                usuarioId(AMARELO))).isEqualTo(1);
        // O histórico guarda o tipo e a versão, nunca o texto.
        List<Map<String, Object>> envio = historico("ENVIO_ARQUIVO", "entrega", entrega);
        assertThat(envio).hasSize(1);
        assertThat(envio.getFirst().get("valor_novo")).isEqualTo("Texto (versão 1)");
        assertThat(historico("ALTERACAO_STATUS", "entrega", entrega)).hasSize(1);
    }

    @Test
    void marcaEnviaLinkDoTiktokComArrobaDepoisDoHost() {
        long entrega = entregaDeMidia();

        MvcTestResult resposta = enviarConteudo(entrar(AMARELO), entrega,
                json("LINK", null, "https://www.tiktok.com/@planetaatlantida/video/7300000000000000001?lang=pt"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("LINK");
        assertThat(lerJson(resposta, "$.host")).isEqualTo("tiktok.com");
        assertThat(lerJson(resposta, "$.texto")).isNull();
        assertThat(historico("ENVIO_ARQUIVO", "entrega", entrega).getFirst().get("valor_novo")).isEqualTo("Link: tiktok.com (versão 1)");
    }

    @Test
    void cadaEnvioEUmaVersaoNovaEAListaDaRota14JuntaArquivoTextoELinkNaMesmaOrdem() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);

        assertThat(enviar(marca, entrega, "vt.pdf", pdf("v1"))).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(enviarConteudo(marca, entrega, json("TEXTO", "texto dois", null)), "$.versao")).isEqualTo(2);
        MvcTestResult arquivo3 = enviar(marca, entrega, "vt2.pdf", pdf("v3"));
        assertThat(lerJson(arquivo3, "$.versao")).as("o arquivo seguinte continua a sequência").isEqualTo(3);
        assertThat(lerJson(enviarConteudo(rbs, entrega, json("LINK", null, "https://www.instagram.com/p/ABC123/")), "$.versao")).isEqualTo(4);

        for (SessaoDeApi pessoa : List.of(marca, rbs)) {
            MvcTestResult lista = pessoa.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
            assertThat(lista).hasStatus(HttpStatus.OK);
            Object tipos = lerJson(lista, "$[?(@.id==" + entrega + ")].versoes[*].tipo");
            assertThat(tipos).isEqualTo(List.of("LINK", "ARQUIVO", "TEXTO", "ARQUIVO"));
            assertThat(lerJson(lista, "$[?(@.id==" + entrega + ")].versoes[*].versao")).isEqualTo(List.of(4, 3, 2, 1));
            assertThat(lerJson(lista, "$[?(@.id==" + entrega + ")].arquivos[*].versao")).as("os arquivos seguem à parte, como antes")
                    .isEqualTo(List.of(3, 1));
            assertThat(lerJson(lista, "$[?(@.id==" + entrega + ")].versoes[0].url")).isEqualTo(List.of("https://www.instagram.com/p/ABC123/"));
            assertThat(lerJson(lista, "$[?(@.id==" + entrega + ")].versoes[1].nomeOriginal")).isEqualTo(List.of("vt2.pdf"));
        }
        // A mudança de status (rota 19) devolve a entrega com as mesmas versões.
        MvcTestResult status = rbs.patch("/api/entregas/" + entrega + "/status", "{\"status\":\"EM_ANALISE\"}");
        assertThat(status).hasStatus(HttpStatus.OK);
        assertThat(lerLista(status, "$.versoes")).hasSize(4);
    }

    @Test
    void retornoDaRbsNaoMudaOStatusEMarcaEmAjusteVoltaAEnviado() {
        long entrega = entregaDeMidia();
        definirStatus(entrega, "APROVADO");

        assertThat(enviarConteudo(entrar(RBS), entrega, json("LINK", null, "https://www.instagram.com/p/XYZ/"))).hasStatus(HttpStatus.CREATED);
        assertThat(statusDaEntrega(entrega)).isEqualTo("APROVADO");

        definirStatus(entrega, "AJUSTE_SOLICITADO");
        MvcTestResult resposta = enviarConteudo(entrar(AMARELO), entrega, json("TEXTO", "ajustado", null));
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");
    }

    @Test
    void marcaNaoEnviaComAEntregaEmAnaliseAprovadaOuNaoAplicavel409() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        for (String status : List.of("EM_ANALISE", "APROVADO", "NAO_APLICAVEL")) {
            definirStatus(entrega, status);
            assertThat(enviarConteudo(marca, entrega, json("TEXTO", "tarde demais", null))).as(status).hasStatus(HttpStatus.CONFLICT);
        }
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ?", entrega)).isZero();
    }

    // ---------------------------------------------------------------------
    // 422
    // ---------------------------------------------------------------------

    @Test
    void textoVazioSoEspacosOuComNulDa422ENaoGrava() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        for (String texto : new String[] {"", "   ", " \n\t ", null, "a\u0000b", "\u0000", "controle\u0007aqui"}) {
            MvcTestResult resposta = enviarConteudo(marca, entrega, json("TEXTO", texto, null));
            assertThat(resposta).as("texto [" + texto + "]").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.texto")).isNotNull();
        }
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ?", entrega)).isZero();
        assertThat(statusDaEntrega(entrega)).as("a entrega não saiu do lugar").isNotEqualTo("ENVIADO");
    }

    @Test
    void textoDe5000CaracteresPassaEDe5001Da422ContandoCaractereNaoUnidadeUtf16() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        assertThat(enviarConteudo(marca, entrega, json("TEXTO", "x".repeat(5000), null))).hasStatus(HttpStatus.CREATED);
        assertThat(enviarConteudo(marca, entrega, json("TEXTO", "😀".repeat(5000), null))).as("5.000 emojis são 5.000 caracteres")
                .hasStatus(HttpStatus.CREATED);
        MvcTestResult longo = enviarConteudo(marca, entrega, json("TEXTO", "x".repeat(5001), null));
        assertThat(longo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longo, "$.campos.texto")).isEqualTo("O texto tem no máximo 5000 caracteres.");
    }

    @Test
    void linksPerigososOuMalFormadosDao422() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        List<String> invalidos = List.of("javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "ftp://exemplo.com/a",
                "file:///etc/passwd", "//exemplo.com/a", "exemplo.com/a", "https://", "https:///sem-host", "http://:80/a",
                "https://usuario@exemplo.com/a", "https://usuario:senha@exemplo.com/a", "https://exemplo.com com espaco",
                "https://exemplo.com/‮fdp.exe", "https://exe‏mplo.com/a", "https://exemplo.com/a\u0007b",
                "https://exemplo.com/" + "a".repeat(500));
        for (String url : invalidos) {
            MvcTestResult resposta = enviarConteudo(marca, entrega, json("LINK", null, url));
            assertThat(resposta).as("link [" + url + "]").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.url")).isNotNull();
        }
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ?", entrega)).isZero();
    }

    @Test
    void tipoInvalidoLinkSemEnderecoTextoComEnderecoELinkComTextoDao422() {
        long entrega = entregaDeMidia();
        SessaoDeApi marca = entrar(AMARELO);

        assertThat(lerJson(enviarConteudo(marca, entrega, json("ARQUIVO", "x", null)), "$.campos.tipo")).isNotNull();
        assertThat(lerJson(enviarConteudo(marca, entrega, json(null, "x", null)), "$.campos.tipo")).isNotNull();
        assertThat(enviarConteudo(marca, entrega, json("LINK", null, null))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(enviarConteudo(marca, entrega, json("TEXTO", "x", "https://exemplo.com/")), "$.campos.url")).isNotNull();
        assertThat(lerJson(enviarConteudo(marca, entrega, json("LINK", "x", "https://exemplo.com/")), "$.campos.texto")).isNotNull();
        assertThat(enviarConteudo(marca, entrega, "{\"tipo\":\"TEXTO\",\"texto\":\"ok\",\"observacao\":\"" + "o".repeat(501) + "\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(enviarConteudo(marca, entrega, "{\"tipo\":\"TEXTO\",\"texto\":\"ok\",\"titulo\":\"a\\u0000b\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ?", entrega)).isZero();
    }

    // ---------------------------------------------------------------------
    // 404, 403, 409 e 401
    // ---------------------------------------------------------------------

    @Test
    void marcaDeOutraParceriaEEntregaQueNaoExisteDaoOMesmo404() {
        long entrega = entregaDeMidia();

        MvcTestResult alheia = enviarConteudo(entrar(ROXO), entrega, json("TEXTO", "intruso", null));
        MvcTestResult inexistente = enviarConteudo(entrar(ROXO), 999_999_999L, json("TEXTO", "intruso", null));

        assertThat(alheia).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(alheia, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE entrega_id = ?", entrega)).isZero();
    }

    @Test
    void abaFechadaOuOcultaDa403ParaAMarcaEARbsAindaEnvia() {
        long entrega = entregaDeMidia();
        for (String coluna : List.of("liberada", "visivel")) {
            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                    .param("p", amarelo()).update();

            assertThat(enviarConteudo(entrar(AMARELO), entrega, json("TEXTO", "fechada", null))).as(coluna).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(enviarConteudo(entrar(RBS), entrega, json("TEXTO", "rbs entra", null))).as(coluna).hasStatus(HttpStatus.CREATED);

            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                    .param("p", amarelo()).update();
        }
    }

    @Test
    void parceriaEncerradaRecusaOEnvioCom409() {
        long entrega = entregaDeMidia();
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :p").param("p", amarelo()).update();

        for (SessaoDeApi pessoa : List.of(entrar(AMARELO), entrar(RBS))) {
            MvcTestResult resposta = enviarConteudo(pessoa, entrega, json("TEXTO", "encerrada", null));
            assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita novos envios.");
        }
    }

    @Test
    void semSessaoRecebe401() {
        long entrega = entregaDeMidia();

        assertThat(enviarConteudo(SessaoDeApi.anonima(mvc), entrega, json("TEXTO", "x", null))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // R5.3.c: nada se edita nem se apaga
    // ---------------------------------------------------------------------

    @Test
    void oBancoRecusaUpdateEDeleteDoConteudoEAApiNaoTemPatchNemDelete() {
        long entrega = entregaDeMidia();
        long conteudo = ((Number) lerJson(enviarConteudo(entrar(AMARELO), entrega, json("TEXTO", "original", null)), "$.id")).longValue();

        // O gatilho recusa o UPDATE e o DELETE. Cada tentativa roda num bloco com subtransação, para o erro esperado não abortar a transação do teste.
        for (String comando : List.of("UPDATE entrega_conteudo SET texto = 'adulterado' WHERE id = " + conteudo,
                "UPDATE entrega_conteudo SET url = NULL, texto = 'x', tipo = 'TEXTO' WHERE id = " + conteudo,
                "DELETE FROM entrega_conteudo WHERE id = " + conteudo)) {
            jdbc.sql("DO $$ BEGIN " + comando + "; RAISE EXCEPTION 'NAO_RECUSOU'; EXCEPTION WHEN check_violation THEN NULL; END $$").update();
        }

        SessaoDeApi rbs = entrar(RBS);
        for (String uri : List.of("/api/entregas/" + entrega + "/conteudos", "/api/entregas/" + entrega + "/conteudos/" + conteudo)) {
            assertThat(rbs.patch(uri, "{\"texto\":\"x\"}").getResponse().getStatus()).as("PATCH " + uri).isIn(404, 405);
            assertThat(rbs.put(uri, "{\"texto\":\"x\"}").getResponse().getStatus()).as("PUT " + uri).isIn(404, 405);
            assertThat(rbs.delete(uri).getResponse().getStatus()).as("DELETE " + uri).isIn(404, 405);
        }
        assertThat(contar("SELECT count(*) FROM entrega_conteudo WHERE id = ? AND texto = 'original'", conteudo)).isEqualTo(1);
    }

    @Test
    void aChaveCompostaImpedeLigarOConteudoAEntregaDeOutraParceria() {
        long entrega = entregaDeMidia();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO entrega_conteudo (entrega_id, parceria_id, tipo, texto, enviado_por)
                VALUES (:e, :p, 'TEXTO', 'x', :u)
                """).param("e", entrega).param("p", roxo()).param("u", usuarioId(RBS)).update())
                .hasMessageContaining("fk_conteudo_entrega");
    }

    @Test
    void aFilaDaRbsMostraOEnvioDeTextoDaMarcaComoUltimoEnvio() {
        long entrega = entregaDeMidia();
        enviarConteudo(entrar(AMARELO), entrega, json("TEXTO", "para a fila", null));

        MvcTestResult fila = entrar(RBS).get("/api/entregas?parceriaId=" + amarelo() + "&abaCodigo=MIDIA&status=ENVIADO&tamanho=200");

        assertThat(fila).hasStatus(HttpStatus.OK);
        assertThat(lerJson(fila, "$.itens[?(@.id==" + entrega + ")].ultimoEnvioEm")).isNotEqualTo(List.of());
        assertThat(((List<?>) lerJson(fila, "$.itens[?(@.id==" + entrega + ")].ultimoEnvioEm")).getFirst()).isNotNull();
    }
}
