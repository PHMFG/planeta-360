package br.com.rbs.portal.dados;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Armazenamento;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [DADOS] A rota 66, {@code POST /api/parcerias/{id}/dados-informados/{chave}/arquivo} (M6): o campo de arquivo
 * {@code ativacao.planilha_agencia} da aba Aplicações (V14), a planilha de contagem que a agência manda. Como o front a usa: login
 * de verdade, multipart, sessão e token CSRF. [UPLOAD] só {@code .xlsx}, {@code .xls} e {@code .csv}, conferidos pelo conteúdo e
 * pelo limite de tamanho do projeto; [HISTORICO] cada envio é um valor novo (autor, data, origem), com o anterior guardado;
 * [ISOLAMENTO] [PERMISSAO] outra marca e aba fechada como nas demais rotas de dados informados; o download sai pela rota 18 com as
 * mesmas regras de acesso. A rota 41 continua recusando o campo de arquivo. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class PlanilhaDaAgenciaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String CHAVE = "ativacao.planilha_agencia";
    private static final String ABA_NAO_LIBERADA = "Esta aba ainda não foi liberada para a sua parceria.";
    private static final String TIPOS_ACEITOS = "Este tipo de arquivo não é aceito. Envie um destes: xlsx, xls, csv.";
    private static final byte[] XLSX = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 'x', 'l', '/'};
    private static final byte[] XLS = br.com.rbs.portal.comum.Ole2DeTeste.xls();   // um .xls de verdade: o diretório OLE2 é lido (R4.1)
    private static final byte[] CSV = "hora;visitantes\n10h;120\n11h;180\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Armazenamento armazenamento;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    long idDoUsuario(String email) {
        return contar("SELECT id FROM usuario WHERE email = ?", email);
    }

    static String uri(Object parceria, String chave) {
        return "/api/parcerias/" + parceria + "/dados-informados/" + chave + "/arquivo";
    }

    /** POST multipart como o front faz: cookie de sessão, cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN. */
    MvcTestResult enviar(SessaoDeApi pessoa, Object parceria, String chave, String nome, byte[] bytes) {
        return pedido(pessoa, true, parceria, chave, nome, bytes);
    }

    MvcTestResult pedido(SessaoDeApi pessoa, boolean comCsrf, Object parceria, String chave, String nome, byte[] bytes) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri(uri(parceria, chave)).multipart().cookie(csrf.cookie()).session(pessoa.sessao());
        if (comCsrf) {
            pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (bytes != null) {
            pedido.file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes));
        }
        return pedido.exchange();
    }

    long id(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    void fecharAAba() {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();
    }

    /** O campo da planilha na resposta da rota 40, para a aba Aplicações da parceria. */
    Map<String, Object> campoDaPlanilha(SessaoDeApi pessoa, long parceria) {
        MvcTestResult dados = pessoa.get("/api/parcerias/" + parceria + "/dados?aba=APLICACOES");
        assertThat(dados).hasStatus(HttpStatus.OK);
        List<Map<String, Object>> campos = lerLista(dados, "$.campos[?(@.chave=='" + CHAVE + "')]");
        assertThat(campos).hasSize(1);
        return campos.get(0);
    }

    // ---------------------------------------------------------------------
    // O catálogo
    // ---------------------------------------------------------------------

    @Test
    void oCatalogoTemOCampoDeArquivoDaAbaAplicacoesNaQuintaPosicao() {
        MvcTestResult catalogo = entrar(AMARELO).get("/api/campos");

        assertThat(catalogo).hasStatus(HttpStatus.OK);
        assertThat(lerLista(catalogo, "$[*].chave")).containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos",
                "ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos", CHAVE);
        List<Map<String, Object>> achados = lerLista(catalogo, "$[?(@.chave=='" + CHAVE + "')]");
        assertThat(achados).hasSize(1);
        Map<String, Object> campo = achados.get(0);
        assertThat(campo).containsEntry("aba", "APLICACOES").containsEntry("rotulo", "Planilha da agência").containsEntry("tipo", "ARQUIVO")
                .containsEntry("unidade", null).containsEntry("minimo", null).containsEntry("maximo", null)
                .containsEntry("limiteCampo", null);
        assertThat(String.valueOf(campo.get("ajuda"))).contains("planilha").contains("agência");
        assertThat(contar("SELECT ordem FROM campo_catalogo WHERE chave = ?", CHAVE)).isEqualTo(5);
    }

    @Test
    void antesDoEnvioOCampoVemSemValorVigente() {
        Map<String, Object> campo = campoDaPlanilha(entrar(AMARELO), amarelo());

        assertThat(campo).containsEntry("tipo", "ARQUIVO").containsEntry("vigente", null);
    }

    // ---------------------------------------------------------------------
    // O envio
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"contagem.xlsx", "contagem.xls", "contagem.csv", "CONTAGEM.XLSX", "Contagem Final.Csv"})
    void aMarcaEnviaAPlanilhaEORecebeOValorVigenteComOArquivo(String nome) {
        SessaoDeApi marca = entrar(AMARELO);
        byte[] bytes = nome.toLowerCase().endsWith(".xlsx") ? XLSX : nome.toLowerCase().endsWith(".xls") ? XLS : CSV;

        MvcTestResult resposta = enviar(marca, amarelo(), CHAVE, nome, bytes);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        long arquivoId = id(resposta, "$.arquivo.id");
        assertThat(lerJson(resposta, "$.arquivo.nomeOriginal")).isEqualTo(nome);
        assertThat(id(resposta, "$.valor")).isEqualTo(arquivoId);
        assertThat(lerJson(resposta, "$.origem")).isEqualTo("PORTAL");
        assertThat(id(resposta, "$.informadoPor.id")).isEqualTo(idDoUsuario(AMARELO));
        assertThat(lerJson(resposta, "$.informadoPor.nome")).isEqualTo("Marketing Amarelo");
        assertThat(lerJson(resposta, "$.informadoPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat((String) lerJson(resposta, "$.informadoEm")).matches("\\d{4}-\\d{2}-\\d{2}T.*-03:00");
        assertThat(lerLista(resposta, "$.*")).hasSize(5);   // valor, origem, informadoPor, informadoEm, arquivo
        // o arquivo: da parceria, da aba Aplicações, sem entrega, na categoria OUTRO, com hash e no disco
        Map<String, Object> arquivo = jdbc.sql("""
                        SELECT a.parceria_id, ab.codigo AS aba, a.entrega_id, a.categoria, a.nome_original, a.tamanho_bytes, a.hash_sha256,
                               a.caminho, a.enviado_por, a.versao
                        FROM arquivo a LEFT JOIN aba ab ON ab.id = a.aba_id WHERE a.id = :id
                        """).param("id", arquivoId).query().singleRow();
        assertThat(arquivo).containsEntry("parceria_id", amarelo()).containsEntry("aba", "APLICACOES").containsEntry("entrega_id", null)
                .containsEntry("categoria", "OUTRO").containsEntry("nome_original", nome).containsEntry("tamanho_bytes", (long) bytes.length)
                .containsEntry("enviado_por", idDoUsuario(AMARELO)).containsEntry("versao", 1);
        assertThat(String.valueOf(arquivo.get("hash_sha256"))).hasSize(64);
        assertThat(armazenamento.localizar((String) arquivo.get("caminho"))).isPresent();
        // o valor guardado: uma linha em dado_informado, com o arquivo e sem número nem data
        Map<String, Object> dado = jdbc.sql("""
                        SELECT parceria_id, campo_chave, valor_numero, valor_data, arquivo_id, origem, informado_por
                        FROM dado_informado WHERE arquivo_id = :id
                        """).param("id", arquivoId).query().singleRow();
        assertThat(dado).containsEntry("parceria_id", amarelo()).containsEntry("campo_chave", CHAVE).containsEntry("valor_numero", null)
                .containsEntry("valor_data", null).containsEntry("origem", "PORTAL").containsEntry("informado_por", idDoUsuario(AMARELO));
        // o histórico: CRIACAO do dado informado, com o nome do arquivo e o rótulo do campo
        List<Map<String, Object>> historico = jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe FROM historico
                        WHERE acao = 'CRIACAO' AND entidade = 'dado_informado' AND entidade_id = (SELECT id FROM dado_informado WHERE arquivo_id = :id)
                        """).param("id", arquivoId).query().listOfRows();
        assertThat(historico).hasSize(1);
        assertThat(historico.get(0)).containsEntry("usuario_id", idDoUsuario(AMARELO)).containsEntry("parceria_id", amarelo())
                .containsEntry("valor_anterior", null);
        assertThat(historico.get(0)).containsEntry("valor_novo", nome);
        assertThat(String.valueOf(historico.get(0).get("detalhe"))).isEqualTo("Planilha da agência (portal)");
    }

    @Test
    void aRotaDe40MostraOArquivoVigenteELeituraDeOutrosCamposNaoTrazArquivo() {
        SessaoDeApi marca = entrar(AMARELO);
        long arquivoId = id(enviar(marca, amarelo(), CHAVE, "contagem.xlsx", XLSX), "$.arquivo.id");

        MvcTestResult dados = marca.get("/api/parcerias/" + amarelo() + "/dados?aba=APLICACOES");

        assertThat(dados).hasStatus(HttpStatus.OK);
        assertThat(lerLista(dados, "$.campos[*].chave")).containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos",
                "ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos", CHAVE);
        assertThat(lerJson(dados, "$.campos[4].vigente.valor")).isEqualTo((int) arquivoId);
        assertThat(lerJson(dados, "$.campos[4].vigente.arquivo.id")).isEqualTo((int) arquivoId);
        assertThat(lerJson(dados, "$.campos[4].vigente.arquivo.nomeOriginal")).isEqualTo("contagem.xlsx");
        assertThat(lerJson(dados, "$.campos[4].vigente.origem")).isEqualTo("PORTAL");
        // um campo de número que tem valor traz arquivo nulo
        marca.post("/api/parcerias/" + amarelo() + "/dados", "{\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200}]}");
        MvcTestResult depois = marca.get("/api/parcerias/" + amarelo() + "/dados?aba=APLICACOES");
        assertThat(lerJson(depois, "$.campos[0].vigente.valor")).isEqualTo(1200);
        assertThat(corpo(depois)).contains("\"arquivo\":null");
        assertThat(lerLista(depois, "$.campos[0].vigente.*")).hasSize(5);
    }

    @Test
    void umNovoEnvioViraOVigenteEOAnteriorFicaGuardado() {
        SessaoDeApi marca = entrar(AMARELO);
        long primeiro = id(enviar(marca, amarelo(), CHAVE, "contagem-1.csv", CSV), "$.arquivo.id");

        long segundo = id(enviar(marca, amarelo(), CHAVE, "contagem-2.xlsx", XLSX), "$.arquivo.id");

        assertThat(segundo).isGreaterThan(primeiro);
        Map<String, Object> campo = campoDaPlanilha(marca, amarelo());
        assertThat(((Map<?, ?>) ((Map<?, ?>) campo.get("vigente")).get("arquivo")).get("nomeOriginal")).isEqualTo("contagem-2.xlsx");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND campo_chave = ?", amarelo(), CHAVE)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE id IN (?, ?)", primeiro, segundo)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'CRIACAO' AND entidade = 'dado_informado' AND detalhe = ?",
                "Planilha da agência (portal)")).isEqualTo(2);
        // o segundo envio mostra, no histórico, o arquivo que ele trocou
        assertThat(jdbc.sql("SELECT valor_anterior || ' > ' || valor_novo FROM historico WHERE acao = 'CRIACAO' AND entidade = 'dado_informado' "
                + "AND entidade_id = (SELECT max(id) FROM dado_informado WHERE campo_chave = :c)").param("c", CHAVE).query(String.class).single())
                .isEqualTo("contagem-1.csv > contagem-2.xlsx");
        // o mesmo arquivo de novo também é um valor novo: arquivo é prova, não número, e o envio é um ato
        long terceiro = id(enviar(marca, amarelo(), CHAVE, "contagem-2.xlsx", XLSX), "$.arquivo.id");
        assertThat(terceiro).isGreaterThan(segundo);
    }

    @Test
    void aRbsEnviaPeloPortalEFicaComoAutora() {
        long amarelo = amarelo();
        MvcTestResult resposta = enviar(entrar(RBS), amarelo, CHAVE, "da-agencia.csv", CSV);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(id(resposta, "$.informadoPor.id")).isEqualTo(idDoUsuario(RBS));
        assertThat(lerJson(resposta, "$.informadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(resposta, "$.origem")).isEqualTo("PORTAL");
        // a marca vê o que a RBS mandou, com o nome de quem informou
        Map<String, Object> campo = campoDaPlanilha(entrar(AMARELO), amarelo);
        assertThat(((Map<?, ?>) ((Map<?, ?>) campo.get("vigente")).get("informadoPor")).get("nome")).isEqualTo("Equipe B2B RBS");
    }

    // ---------------------------------------------------------------------
    // O que a rota recusa
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"contagem.pdf", "contagem.png", "contagem.txt", "contagem.docx", "contagem.zip", "contagem", "contagem.xlsx.exe",
            "contagem.xlsm", "contagem."})
    void extensaoForaDaListaDa415ComAsTresExtensoes(String nome) {
        MvcTestResult resposta = enviar(entrar(AMARELO), amarelo(), CHAVE, nome, XLSX);

        assertThat(resposta).as(nome).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(TIPOS_ACEITOS);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @Test
    void conteudoQueNaoBateComAExtensaoDa415() {
        SessaoDeApi marca = entrar(AMARELO);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

        assertThat(enviar(marca, amarelo(), CHAVE, "contagem.xlsx", "isto não é uma planilha".getBytes(StandardCharsets.UTF_8)))
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviar(marca, amarelo(), CHAVE, "contagem.xls", XLSX)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);   // zip no lugar do OLE2
        assertThat(enviar(marca, amarelo(), CHAVE, "contagem.xlsx", XLS)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);   // OLE2 no lugar do zip
        MvcTestResult binarioComoCsv = enviar(marca, amarelo(), CHAVE, "contagem.csv", png);
        assertThat(binarioComoCsv).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat((String) lerJson(binarioComoCsv, "$.detail")).contains("CSV");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @ParameterizedTest(name = "CSV em {0} entra: ninguém lê a planilha no servidor")
    @ValueSource(strings = {"ISO-8859-1", "windows-1252", "UTF-8"})
    void csvEmQualquerCodificacaoSemByteNuloEntraNaRota66(String codificacao) {
        // o Excel em português exporta CSV em ANSI (windows-1252) por padrão; os acentos e o "€" não são UTF-8 válido
        byte[] bytes = "hora;visitantes;observação\n10h;120;João e Conceição\n11h;180;ação\n".getBytes(Charset.forName(codificacao));
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult resposta = enviar(marca, amarelo(), CHAVE, "contagem-excel.csv", bytes);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long arquivoId = id(resposta, "$.arquivo.id");
        assertThat(lerJson(resposta, "$.arquivo.nomeOriginal")).isEqualTo("contagem-excel.csv");
        assertThat(contar("SELECT count(*) FROM arquivo WHERE id = ? AND mime_type = 'text/csv'", arquivoId)).isEqualTo(1);
        // o arquivo guardado é o que foi enviado, byte a byte
        assertThat(marca.get("/api/arquivos/" + arquivoId).getResponse().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    void csvComByteNuloContinuaRecusadoNaRota66() {
        SessaoDeApi marca = entrar(AMARELO);
        byte[] utf16 = "hora;visitantes\n10h;120\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16ComMarca = new byte[utf16.length + 2];
        utf16ComMarca[0] = (byte) 0xFF;
        utf16ComMarca[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, utf16ComMarca, 2, utf16.length);
        byte[] nuloNoMeio = "hora;visitantes\n10h;1\u0000\n".getBytes(StandardCharsets.ISO_8859_1);

        for (byte[] bytes : List.of(utf16, utf16ComMarca, nuloNoMeio)) {
            MvcTestResult resposta = enviar(marca, amarelo(), CHAVE, "contagem.csv", bytes);
            assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat((String) lerJson(resposta, "$.detail")).contains("CSV").doesNotContain("UTF-8");
        }
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @Test
    void arquivoAcimaDoLimiteDeTamanhoDoProjetoDa413EOLimiteExatoPassa() {
        SessaoDeApi marca = entrar(AMARELO);
        int limite = 20 * 1024 * 1024;
        byte[] noLimite = new byte[limite];
        System.arraycopy(XLSX, 0, noLimite, 0, XLSX.length);
        byte[] grande = Arrays.copyOf(noLimite, limite + 1);

        assertThat(enviar(marca, amarelo(), CHAVE, "grande.xlsx", grande)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
        MvcTestResult exato = enviar(marca, amarelo(), CHAVE, "exato.xlsx", noLimite);
        assertThat(exato).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void campoQueNaoEDeArquivoDa422ComACausaNoCampo() {
        for (String chave : List.of("ativacao.visitantes_total", "ativacao.brindes_distribuidos")) {
            MvcTestResult resposta = enviar(entrar(AMARELO), amarelo(), chave, "contagem.csv", CSV);

            assertThat(resposta).as(chave).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos['" + chave + "']")).as(chave).isEqualTo("Este campo não recebe arquivo. Informe o número pelo formulário.");
        }
        assertThat(contar("SELECT count(*) FROM arquivo WHERE nome_original = 'contagem.csv'")).isZero();
    }

    @Test
    void chaveQueNaoExisteDa404() {
        MvcTestResult resposta = enviar(entrar(AMARELO), amarelo(), "ativacao.nao_existe", "contagem.csv", CSV);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos este campo.");
    }

    @Test
    void semArquivoOuComArquivoVazioDa422ComOCampoArquivo() {
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult ausente = enviar(marca, amarelo(), CHAVE, null, null);
        assertThat(ausente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ausente, "$.campos.arquivo")).isEqualTo("Envie o arquivo na parte \"arquivo\" do formulário.");
        MvcTestResult vazio = enviar(marca, amarelo(), CHAVE, "vazia.csv", new byte[0]);
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.arquivo")).isNotNull();
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @Test
    void semSessaoDa401ESemCsrfDa403() {
        assertThat(enviar(SessaoDeApi.anonima(mvc), amarelo(), CHAVE, "contagem.csv", CSV)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(pedido(entrar(AMARELO), false, amarelo(), CHAVE, "contagem.csv", CSV)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @Test
    void marcaDeOutraParceriaEParceriaQueNaoExisteDao404ComAMesmaFrase() {
        SessaoDeApi roxo = entrar(ROXO);

        for (Object parceria : List.of(amarelo(), 999_999, "abc")) {
            MvcTestResult resposta = enviar(roxo, parceria, CHAVE, "contagem.csv", CSV);
            assertThat(resposta).as("parceria " + parceria).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).as("parceria " + parceria).isEqualTo("Não encontramos esta parceria.");
        }
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
        assertThat(contar("SELECT count(*) FROM arquivo WHERE nome_original = 'contagem.csv'")).isZero();
    }

    @Test
    void abaFechadaDa403ParaAMarcaEARbsSegue() {
        fecharAAba();

        MvcTestResult marca = enviar(entrar(AMARELO), amarelo(), CHAVE, "contagem.csv", CSV);
        assertThat(marca).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(marca, "$.detail")).isEqualTo(ABA_NAO_LIBERADA);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
        assertThat(enviar(entrar(RBS), amarelo(), CHAVE, "contagem.csv", CSV)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aRota41ContinuaRecusandoOCampoDeArquivoComAMensagemDeAntes() {
        MvcTestResult resposta = entrar(AMARELO).post("/api/parcerias/" + amarelo() + "/dados",
                "{\"valores\":[{\"chave\":\"" + CHAVE + "\",\"valor\":12}]}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos['" + CHAVE + "']")).isEqualTo("Este campo recebe um arquivo, que ainda não é aceito por aqui.");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    // ---------------------------------------------------------------------
    // O download pela rota 18
    // ---------------------------------------------------------------------

    @Test
    void aDonaBaixaAPlanilhaPelaRota18EOutraMarcaRecebe404() {
        long arquivoId = id(enviar(entrar(AMARELO), amarelo(), CHAVE, "contagem final.csv", CSV), "$.arquivo.id");

        MvcTestResult dona = entrar(AMARELO).get("/api/arquivos/" + arquivoId);
        MvcTestResult outra = entrar(ROXO).get("/api/arquivos/" + arquivoId);
        MvcTestResult rbs = entrar(RBS).get("/api/arquivos/" + arquivoId);

        assertThat(dona).hasStatus(HttpStatus.OK);
        assertThat(dona.getResponse().getContentAsByteArray()).isEqualTo(CSV);
        assertThat(dona.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("contagem final.csv");
        assertThat(dona.getResponse().getContentType()).startsWith("text/csv");
        assertThat(dona.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(outra).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(outra, "$.detail")).isEqualTo("Não encontramos este arquivo.");
        assertThat(rbs).hasStatus(HttpStatus.OK);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/arquivos/" + arquivoId)).hasStatus(HttpStatus.UNAUTHORIZED);
        // o download grava DOWNLOAD no histórico, na entidade arquivo
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'arquivo' AND entidade_id = ?", arquivoId))
                .isEqualTo(2);
    }

    @Test
    void comAAbaFechadaAMarcaNaoBaixaAPlanilhaEARbsBaixa() throws Exception {
        long arquivoId = id(enviar(entrar(AMARELO), amarelo(), CHAVE, "contagem.xls", XLS), "$.arquivo.id");
        fecharAAba();

        MvcTestResult marca = entrar(AMARELO).get("/api/arquivos/" + arquivoId);
        MvcTestResult rbs = entrar(RBS).get("/api/arquivos/" + arquivoId);

        assertThat(marca).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs).hasStatus(HttpStatus.OK);
        assertThat(rbs.getResponse().getContentType()).startsWith("application/vnd.ms-excel");
        assertThat(Files.size(armazenamento.localizar(jdbc.sql("SELECT caminho FROM arquivo WHERE id = :id").param("id", arquivoId)
                .query(String.class).single()).orElseThrow())).isEqualTo(XLS.length);
    }

    @Test
    void aRotaNaoAceitaJsonNoLugarDoMultipart() {
        MvcTestResult resposta = entrar(AMARELO).post(uri(amarelo(), CHAVE), "{\"valor\":1}");

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(corpo(resposta)).doesNotContain("Exception");
    }
}
