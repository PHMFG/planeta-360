package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5.1.b: o plano comercial do Perfil (rotas 69 e 70). A RBS envia versões; a marca vê a última e baixa todas; o plano é arquivo
 * avulso, não entrega, e por isso não conta como pendente. [ISOLAMENTO] [PERMISSAO] [UPLOAD] [HISTORICO] Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class PlanoComercialDoPerfilTest extends ApoioDeEspacos {

    private String rota(long parceria) {
        return "/api/parcerias/" + parceria + "/plano-comercial";
    }

    private int maiorVersao(long parceria) {
        return (int) contar("SELECT coalesce(max(versao), 0) FROM arquivo WHERE parceria_id = ? AND categoria = 'PLANO_COMERCIAL' "
                + "AND entrega_id IS NULL", parceria);
    }

    @Test
    void rbsEnviaDuasVersoesAMarcaVeAUltimaEBaixaAsDuas() {
        long parceria = amarelo();
        int antes = maiorVersao(parceria);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult v1 = enviarPara(rbs, rota(parceria), "plano_v1.pdf", pdf("plano um"), "observacao", "primeira versão");
        MvcTestResult v2 = enviarPara(rbs, rota(parceria), "plano_v2.pdf", pdf("plano dois"));

        assertThat(v1).hasStatus(HttpStatus.CREATED);
        assertThat(v2).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(v1, "$.versao")).isEqualTo(antes + 1);
        assertThat(lerJson(v2, "$.versao")).isEqualTo(antes + 2);
        assertThat(lerJson(v1, "$.categoria")).isEqualTo("PLANO_COMERCIAL");
        assertThat(lerJson(v1, "$.entregaId")).isNull();
        assertThat(lerJson(v1, "$.observacao")).isEqualTo("primeira versão");
        assertThat(v2.getResponse().getHeader("Location")).isEqualTo("/api/arquivos/" + id(v2));

        SessaoDeApi marca = entrar(AMARELO);
        MvcTestResult lista = marca.get(rota(parceria));
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.parceriaId")).isEqualTo((int) parceria);
        List<Map<String, Object>> versoes = lerLista(lista, "$.versoes");
        assertThat(versoes.getFirst().get("versao")).as("a mais nova primeiro").isEqualTo(antes + 2);
        assertThat(versoes.getFirst().get("nomeOriginal")).isEqualTo("plano_v2.pdf");
        assertThat(versoes.get(1).get("versao")).isEqualTo(antes + 1);
        assertThat(marca.get("/api/arquivos/" + id(v1))).hasStatus(HttpStatus.OK);
        assertThat(marca.get("/api/arquivos/" + id(v2))).hasStatus(HttpStatus.OK);
    }

    @Test
    void oPlanoNaoEEntregaENaoContaComoPendente() {
        long parceria = amarelo();
        long entregasAntes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria);
        long pendentesAntes = contar("SELECT coalesce(pendentes, 0) FROM vw_entregas_resumo WHERE parceria_id = ?", parceria);

        MvcTestResult enviado = enviarPara(entrar(RBS), rota(parceria), "plano.pdf", pdf("x"));

        assertThat(enviado).hasStatus(HttpStatus.CREATED);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria)).isEqualTo(entregasAntes);
        assertThat(contar("SELECT coalesce(pendentes, 0) FROM vw_entregas_resumo WHERE parceria_id = ?", parceria))
                .isEqualTo(pendentesAntes);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE id = ? AND entrega_id IS NULL AND aba_id = "
                + "(SELECT id FROM aba WHERE codigo = 'PERFIL')", id(enviado))).isEqualTo(1);
    }

    @Test
    void marcaRecebe403NoEnvioAntesDeLerOCorpoComQualquerParceria() {
        SessaoDeApi marca = entrar(AMARELO);
        long versoesAntes = contar("SELECT count(*) FROM arquivo WHERE categoria = 'PLANO_COMERCIAL'");

        for (long parceria : new long[] {amarelo(), roxo(), 999_999}) {
            assertThat(enviarPara(marca, rota(parceria), "plano.pdf", pdf("x"))).as("com arquivo, parceria " + parceria)
                    .hasStatus(HttpStatus.FORBIDDEN);
            assertThat(enviarSemArquivo(marca, rota(parceria))).as("sem arquivo, parceria " + parceria).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(marca.post(rota(parceria), "{\"isto\":\"nao e multipart\"}")).as("JSON, parceria " + parceria)
                    .hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(contar("SELECT count(*) FROM arquivo WHERE categoria = 'PLANO_COMERCIAL'")).isEqualTo(versoesAntes);
    }

    @Test
    void marcaDeOutraParceriaRecebe404NosDoisEMesmaFraseDeParceriaInexistente() {
        long doAmarelo = amarelo();
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult leitura = roxo.get(rota(doAmarelo));
        MvcTestResult inexistente = roxo.get(rota(999_999));

        assertThat(leitura).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(leitura, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(enviarPara(entrar(RBS), rota(999_999), "plano.pdf", pdf("x"))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void semSessao401ESemCsrf403() {
        long parceria = amarelo();

        assertThat(SessaoDeApi.anonima(mvc).get(rota(parceria))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enviarSemSessao(rota(parceria), "plano.pdf", pdf("x"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enviarSemCsrf(entrar(RBS), rota(parceria), "plano.pdf", pdf("x"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void formatoEConteudoErradosDao415EArquivoAusenteOuVazioDa422() {
        SessaoDeApi rbs = entrar(RBS);
        long parceria = amarelo();
        long antes = contar("SELECT count(*) FROM arquivo WHERE categoria = 'PLANO_COMERCIAL'");

        MvcTestResult extensao = enviarPara(rbs, rota(parceria), "plano.exe", pdf("x"));
        MvcTestResult bytes = enviarPara(rbs, rota(parceria), "plano.pdf", png());
        MvcTestResult vazio = enviarPara(rbs, rota(parceria), "plano.pdf", new byte[0]);
        MvcTestResult ausente = enviarSemArquivo(rbs, rota(parceria));
        MvcTestResult longa = enviarPara(rbs, rota(parceria), "plano.pdf", pdf("x"), "observacao", "x".repeat(501));

        assertThat(extensao).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(bytes).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ausente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ausente, "$.campos.arquivo")).isNotNull();
        assertThat(longa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longa, "$.campos.observacao")).isNotNull();
        assertThat(contar("SELECT count(*) FROM arquivo WHERE categoria = 'PLANO_COMERCIAL'")).isEqualTo(antes);
    }

    @Test
    void historicoGravaOEnvioSemDadoPessoalEADownloadGravaDownload() {
        long parceria = amarelo();
        int antes = maiorVersao(parceria);
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult v1 = enviarPara(rbs, rota(parceria), "plano.pdf", pdf("um"));
        MvcTestResult v2 = enviarPara(rbs, rota(parceria), "plano.pdf", pdf("dois"));

        List<Map<String, Object>> primeiro = historico(antes == 0 ? "ENVIO_ARQUIVO" : "NOVA_VERSAO", "arquivo", id(v1));
        List<Map<String, Object>> segundo = historico("NOVA_VERSAO", "arquivo", id(v2));

        assertThat(primeiro).hasSize(1);
        assertThat(segundo).hasSize(1);
        assertThat(segundo.getFirst().get("detalhe")).isEqualTo("Plano comercial");
        assertThat(((Number) segundo.getFirst().get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) segundo.getFirst().get("parceria_id")).longValue()).isEqualTo(parceria);
        assertThat(entrar(AMARELO).get("/api/arquivos/" + id(v2))).hasStatus(HttpStatus.OK);
        assertThat(historico("DOWNLOAD", "arquivo", id(v2))).hasSize(1);
    }

    @Test
    void perfilFechadoOuOcultoDa403NaLeituraDaMarcaEARbsAindaLe() {
        long parceria = amarelo();
        fecharAba(parceria, "PERFIL", false, true);

        MvcTestResult fechada = entrar(AMARELO).get(rota(parceria));
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");

        fecharAba(parceria, "PERFIL", true, false);
        assertThat(entrar(AMARELO).get(rota(parceria))).as("oculta").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(RBS).get(rota(parceria))).hasStatus(HttpStatus.OK);
    }

    @Test
    void parceriaEncerradaRecusaOEnvioMasAindaDeixaLer() {
        long parceria = amarelo();
        encerrar(parceria);

        MvcTestResult envio = enviarPara(entrar(RBS), rota(parceria), "plano.pdf", pdf("x"));

        assertThat(envio).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(envio, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita novos envios.");
        assertThat(entrar(AMARELO).get(rota(parceria))).hasStatus(HttpStatus.OK);
    }

    @Test
    void naoHaPatchNemDeleteDoPlano() {
        long parceria = amarelo();
        SessaoDeApi rbs = entrar(RBS);

        assertThat(rbs.patch(rota(parceria), "{}")).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(rbs.delete(rota(parceria))).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
