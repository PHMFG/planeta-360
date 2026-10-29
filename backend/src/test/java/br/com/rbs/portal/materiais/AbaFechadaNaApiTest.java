package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] Aba fechada não entrega dado da própria marca por rota nenhuma: o download de arquivo de entrega e a
 * consolidação seguem a mesma regra da lista de entregas (aba liberada E visível), e o 403 diz o motivo: aba que a cota não
 * inclui ou aba que a RBS ainda não liberou. A RBS continua vendo tudo. Atende o item 3 e o item 4 da Entrega 2.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class AbaFechadaNaApiTest extends ApoioDeMateriais {

    private static final String ABA_FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";
    private static final String FORA_DA_COTA = "Esta aba não faz parte da cota da sua parceria.";

    /** A RBS fecha a aba para a parceria (liberada = falso). */
    void fechar(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    /** A RBS esconde a aba do menu (liberada continua verdadeira, visivel = falso): para a API, é aba fechada. */
    void esconder(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    void abrir(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET liberada = TRUE, visivel = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    long arquivoDaAmostra(long parceriaId, String nome) {
        return jdbc.sql("SELECT id FROM arquivo WHERE parceria_id = :p AND nome_original = :n ORDER BY id DESC LIMIT 1").param("p", parceriaId).param("n", nome)
                .query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // Download de arquivo de entrega (rota 18)
    // ---------------------------------------------------------------------

    @Test
    void arquivoDeEntregaEmAbaFechadaDa403ParaAMarcaEBaixaParaARbs() {
        byte[] bytes = pdf("VT em aba fechada");
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT em aba fechada");
        long arquivo = id(enviar(entrar(AMARELO), entrega, "vt.pdf", bytes));
        SessaoDeApi marca = entrar(AMARELO);
        assertThat(marca.get("/api/arquivos/" + arquivo)).as("aba aberta").hasStatus(HttpStatus.OK);
        long baixadas = contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", arquivo);

        fechar(amarelo(), "MIDIA");
        MvcTestResult fechada = marca.get("/api/arquivos/" + arquivo);

        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(fechada.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(fechada, "$.title")).isEqualTo("Acesso negado");
        assertThat(lerJson(fechada, "$.detail")).as("a mesma frase da lista de entregas").isEqualTo(ABA_FECHADA);
        MvcTestResult lista = marca.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        assertThat(lerJson(lista, "$.detail")).isEqualTo(lerJson(fechada, "$.detail"));
        assertThat(corpo(fechada)).doesNotContain("vt em aba fechada");
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", arquivo))
                .as("o 403 não grava download").isEqualTo(baixadas);
        // A RBS baixa o mesmo arquivo, e a marca volta a baixar quando a aba reabre.
        MvcTestResult daRbs = entrar(RBS).get("/api/arquivos/" + arquivo);
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(daRbs.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        abrir(amarelo(), "MIDIA");
        assertThat(marca.get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.OK);
    }

    @Test
    void arquivosDaAmostraDeAbaFechadaDao403() {
        // Roxo com Cortesias fechada e Verde com Mídia fechada: os exemplos do QA.
        long destinacao = arquivoDaAmostra(roxo(), "destinacao_cortesias.xlsx");
        long vt = arquivoDaAmostra(verde(), "vt_30s_telao.mp4");
        fechar(roxo(), "CORTESIAS");
        fechar(verde(), "MIDIA");

        MvcTestResult doRoxo = entrar(ROXO).get("/api/arquivos/" + destinacao);
        MvcTestResult doVerde = entrar("marketing@verde.example").get("/api/arquivos/" + vt);

        for (MvcTestResult resposta : java.util.List.of(doRoxo, doVerde)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(ABA_FECHADA);
        }
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id IN (?, ?)", destinacao, vt)).isZero();
    }

    @Test
    void abaEscondidaDoMenuTambemFechaODownload() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT escondido");
        long arquivo = id(enviar(entrar(AMARELO), entrega, "vt.pdf", pdf("x")));
        esconder(amarelo(), "MIDIA");

        MvcTestResult resposta = entrar(AMARELO).get("/api/arquivos/" + arquivo);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(ABA_FECHADA);
    }

    @Test
    void arquivoDeAbaForaDaCotaDa403ComAFraseDaCota() {
        // O Roxo (Subcota G) não tem a Pesquisa na cota; a RBS pode ter anexado um arquivo à entrega dessa aba.
        long entrega = entregaDaAmostra(roxo(), "Três perguntas personalizadas");
        long arquivo = id(enviar(entrar(RBS), entrega, "retorno.pdf", pdf("retorno")));

        MvcTestResult daMarca = entrar(ROXO).get("/api/arquivos/" + arquivo);
        MvcTestResult daRbs = entrar(RBS).get("/api/arquivos/" + arquivo);

        assertThat(daMarca).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(daMarca, "$.detail")).isEqualTo(FORA_DA_COTA);
        assertThat(daRbs).hasStatus(HttpStatus.OK);
    }

    @Test
    void arquivoDeOutraParceriaContinuaDando404EntreAbasFechadas() {
        long arquivoDoAmarelo = arquivoDaAmostra(amarelo(), "logo_final.pdf");
        fechar(roxo(), "APLICACOES");

        MvcTestResult resposta = entrar(ROXO).get("/api/arquivos/" + arquivoDoAmarelo);

        assertThat(resposta).as("o isolamento vem antes da aba").hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void arquivoSemAbaContinuaBaixandoComTodasAsAbasFechadas() {
        long geral = armazenamento.gravar(pdf("sem aba"), "anexo.pdf", "OUTRO", usuarioId(RBS), amarelo()).id();
        for (String aba : java.util.List.of("PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO", "CONTRATO")) {
            fechar(amarelo(), aba);
        }

        assertThat(entrar(AMARELO).get("/api/arquivos/" + geral)).hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // Consolidação (rota 29): a parte de cada aba fechada não sai para a marca
    // ---------------------------------------------------------------------

    @Test
    void consolidacaoComMidiaFechadaNaoTrazMidiaParaAMarcaMasTrazParaARbs() {
        SessaoDeApi marca = entrar("marketing@verde.example");
        SessaoDeApi rbs = entrar(RBS);
        String rota = "/api/parcerias/" + verde() + "/consolidacao";
        assertThat(lerLista(marca.get(rota), "$.midia.porUnidade")).as("aba aberta: a marca vê a mídia").isNotEmpty();

        fechar(verde(), "MIDIA");
        MvcTestResult fechada = marca.get(rota);

        assertThat(fechada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(fechada, "$.midia.porUnidade")).isEmpty();
        assertThat(lerLista(fechada, "$.midia.porVeiculo")).isEmpty();
        assertThat(lerLista(fechada, "$.midia.linhas")).isEmpty();
        assertThat(corpo(fechada)).as("nenhuma linha de previsto ou exibido sai").doesNotContain("\"previsto\"").doesNotContain("\"exibido\"");
        // O resto da consolidação (aplicações, entregas, cortesias) continua, porque suas abas estão abertas.
        assertThat(lerJson(fechada, "$.aplicacoes.previstas")).isNotNull();
        assertThat(lerJson(fechada, "$.entregas.total")).isNotNull();
        assertThat(lerJson(fechada, "$.cortesias.disponiveis")).isNotNull();
        // A RBS continua vendo tudo.
        MvcTestResult daRbs = rbs.get(rota);
        assertThat(lerLista(daRbs, "$.midia.porUnidade")).isNotEmpty();
        assertThat(lerLista(daRbs, "$.midia.porVeiculo")).isNotEmpty();
        assertThat(lerLista(daRbs, "$.midia.linhas")).isNotEmpty();
        // Reabrir devolve a mídia à marca.
        abrir(verde(), "MIDIA");
        assertThat(lerLista(marca.get(rota), "$.midia.porUnidade")).isNotEmpty();
    }

    @Test
    void consolidacaoComMidiaEscondidaDoMenuTambemNaoTrazMidia() {
        esconder(verde(), "MIDIA");

        MvcTestResult resposta = entrar("marketing@verde.example").get("/api/parcerias/" + verde() + "/consolidacao");

        assertThat(lerLista(resposta, "$.midia.linhas")).isEmpty();
        assertThat(lerLista(resposta, "$.midia.porUnidade")).isEmpty();
    }

    @Test
    void consolidacaoEscondeAplicacoesECortesiasQuandoAsAbasEstaoFechadas() {
        SessaoDeApi marca = entrar(AMARELO);
        String rota = "/api/parcerias/" + amarelo() + "/consolidacao";
        MvcTestResult aberta = marca.get(rota);
        assertThat(new BigDecimal(String.valueOf(lerJson(aberta, "$.aplicacoes.previstas")))).isEqualByComparingTo("19");
        assertThat(lerJson(aberta, "$.cortesias.disponiveis")).isNotNull();

        fechar(amarelo(), "APLICACOES");
        fechar(amarelo(), "CORTESIAS");
        MvcTestResult fechada = marca.get(rota);

        for (String campo : java.util.List.of("previstas", "comprovadas", "fotos", "percentual")) {
            assertThat(lerJson(fechada, "$.aplicacoes." + campo)).as("aplicacoes." + campo).isNull();
        }
        for (String campo : java.util.List.of("disponiveis", "destinadas", "percentual")) {
            assertThat(lerJson(fechada, "$.cortesias." + campo)).as("cortesias." + campo).isNull();
        }
        assertThat(lerLista(fechada, "$.midia.porUnidade")).as("a mídia, de aba aberta, continua").isNotEmpty();
        MvcTestResult daRbs = entrar(RBS).get(rota);
        assertThat(lerJson(daRbs, "$.aplicacoes.previstas")).isNotNull();
        assertThat(lerJson(daRbs, "$.cortesias.disponiveis")).isNotNull();
    }

    // ---------------------------------------------------------------------
    // As duas frases do 403: cota que não inclui a aba x aba que a RBS ainda não liberou
    // ---------------------------------------------------------------------

    @Test
    void abaForaDaCotaDaAFraseDaCotaEmTodasAsRotas() {
        long roxo = roxo();
        SessaoDeApi marca = entrar(ROXO);
        long entrega = entregaDaAmostra(roxo, "Três perguntas personalizadas");

        MvcTestResult pesquisa = marca.get("/api/parcerias/" + roxo + "/pesquisa");
        MvcTestResult entregas = marca.get("/api/parcerias/" + roxo + "/entregas?aba=PESQUISA");
        MvcTestResult envio = enviar(marca, entrega, "perguntas.pdf", pdf("perguntas"));
        MvcTestResult perguntas = marca.put("/api/parcerias/" + roxo + "/pesquisa/perguntas", "{\"perguntas\":[]}");

        for (MvcTestResult resposta : java.util.List.of(pesquisa, entregas, envio, perguntas)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(FORA_DA_COTA);
        }
    }

    @Test
    void abaDaCotaFechadaPelaRbsDaAFraseDeAindaNaoLiberada() {
        long roxo = roxo();
        long entrega = entregaDaAmostra(roxo, "VT para os telões dos palcos");
        fechar(roxo, "MIDIA");
        SessaoDeApi marca = entrar(ROXO);

        MvcTestResult entregas = marca.get("/api/parcerias/" + roxo + "/entregas?aba=MIDIA");
        MvcTestResult envio = enviar(marca, entrega, "vt.pdf", pdf("vt"));

        for (MvcTestResult resposta : java.util.List.of(entregas, envio)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(ABA_FECHADA);
        }
    }

    @Test
    void pesquisaDaMasterFechadaPelaRbsDaAFraseDeAindaNaoLiberada() {
        // A Pesquisa está na cota Master; a RBS fecha: é "ainda não liberada", e não "fora da cota".
        fechar(amarelo(), "PESQUISA");

        MvcTestResult resposta = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/pesquisa");

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(ABA_FECHADA);
    }
}
