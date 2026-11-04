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
 * R5.1.c: a compra de ingressos com valor de patrocinador. A RBS publica o formulário em branco (rota 73, versionado); a marca envia
 * solicitações (rota 72, papel DOCUMENTO_INGRESSOS) e cada uma é uma entrega com o seu status. [PERMISSAO] [ISOLAMENTO] [HISTORICO]
 * Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class SolicitacoesDeIngressosTest extends ApoioDeEspacos {

    private String envio(long parceria) {
        return "/api/parcerias/" + parceria + "/espacos/DOCUMENTO_INGRESSOS/arquivos";
    }

    private List<Map<String, Object>> solicitacoes(SessaoDeApi pessoa, long parceria) {
        MvcTestResult espacos = pessoa.get("/api/parcerias/" + parceria + "/espacos");
        assertThat(espacos).hasStatus(HttpStatus.OK);
        return lerLista(espacos, "$.solicitacoesIngressos");
    }

    @Test
    void duasSolicitacoesTemCadaUmaASuaSituacaoEARbsAprovaUmaSemMexerNaOutra() {
        long parceria = amarelo();
        SessaoDeApi marca = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);
        long antes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = 'DOCUMENTO_INGRESSOS'", parceria);

        MvcTestResult primeira = enviarPara(marca, envio(parceria), "pedido_a.pdf", pdf("pedido a"), "observacao", "para a diretoria");
        MvcTestResult segunda = enviarPara(marca, envio(parceria), "pedido_b.pdf", pdf("pedido b"));

        assertThat(primeira).hasStatus(HttpStatus.CREATED);
        assertThat(segunda).hasStatus(HttpStatus.CREATED);
        assertThat(entregaDoEspaco(primeira)).as("cada solicitação é uma entrega").isNotEqualTo(entregaDoEspaco(segunda));
        assertThat(lerJson(primeira, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        assertThat(lerJson(primeira, "$.versao")).isEqualTo(1);
        assertThat(lerJson(segunda, "$.versao")).as("a segunda solicitação não é a v2 da primeira").isEqualTo(1);
        assertThat(lerJson(primeira, "$.categoria")).isEqualTo("DOCUMENTO_INGRESSOS");

        List<Map<String, Object>> lista = solicitacoes(marca, parceria);
        assertThat(lista).hasSize((int) antes + 2);
        Map<String, Object> a = lista.get((int) antes);
        Map<String, Object> b = lista.get((int) antes + 1);
        assertThat(a.get("titulo")).isEqualTo("Solicitação de ingressos " + (antes + 1));
        assertThat(b.get("titulo")).isEqualTo("Solicitação de ingressos " + (antes + 2));
        assertThat(a.get("papel")).isEqualTo("DOCUMENTO_INGRESSOS");
        assertThat(a.get("status")).isEqualTo("ENVIADO");
        assertThat(b.get("status")).isEqualTo("ENVIADO");

        // A RBS confirma uma; a outra continua em análise.
        MvcTestResult aprovada = rbs.patch("/api/entregas/" + entregaDoEspaco(primeira) + "/status", "{\"status\":\"APROVADO\"}");
        assertThat(aprovada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(aprovada, "$.papel")).isEqualTo("DOCUMENTO_INGRESSOS");
        lista = solicitacoes(marca, parceria);
        assertThat(lista.get((int) antes).get("status")).isEqualTo("APROVADO");
        assertThat(lista.get((int) antes + 1).get("status")).isEqualTo("ENVIADO");

        // A marca envia outra depois de uma confirmada, e a confirmada continua confirmada.
        MvcTestResult terceira = enviarPara(marca, envio(parceria), "pedido_c.pdf", pdf("pedido c"));
        assertThat(terceira).hasStatus(HttpStatus.CREATED);
        lista = solicitacoes(marca, parceria);
        assertThat(lista).hasSize((int) antes + 3);
        assertThat(lista.get((int) antes).get("status")).isEqualTo("APROVADO");
        assertThat(lista.get((int) antes + 1).get("status")).isEqualTo("ENVIADO");
        assertThat(lista.get((int) antes + 2).get("status")).isEqualTo("ENVIADO");
        assertThat(lerLista(marca.get("/api/parcerias/" + parceria + "/espacos"), "$.solicitacoesIngressos[" + (antes + 2) + "].arquivos"))
                .hasSize(1);
    }

    @Test
    void categoriaDoCorpoEIgnoradaEAMarcaNaoEscolheAbaNemPapel() {
        long parceria = amarelo();

        MvcTestResult resposta = enviarPara(entrar(AMARELO), envio(parceria), "pedido.pdf", pdf("x"), "categoria", "CONTRATO");

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.categoria")).isEqualTo("DOCUMENTO_INGRESSOS");
        assertThat(contar("SELECT count(*) FROM entrega e JOIN aba a ON a.id = e.aba_id WHERE e.id = ? AND a.codigo = 'PERFIL' "
                + "AND e.papel = 'DOCUMENTO_INGRESSOS'", entregaDoEspaco(resposta))).isEqualTo(1);
    }

    @Test
    void historicoGravaACriacaoEOEnvioDeCadaSolicitacao() {
        long parceria = amarelo();
        MvcTestResult resposta = enviarPara(entrar(AMARELO), envio(parceria), "pedido.pdf", pdf("x"));
        long entrega = entregaDoEspaco(resposta);

        List<Map<String, Object>> criacao = historico("CRIACAO", "entrega", entrega);
        List<Map<String, Object>> envio = historico("ENVIO_ARQUIVO", "entrega", entrega);

        assertThat(criacao).hasSize(1);
        assertThat(criacao.getFirst().get("valor_novo")).isEqualTo("ENVIADO");
        assertThat(envio).hasSize(1);
        assertThat(((Number) envio.getFirst().get("usuario_id")).longValue()).isEqualTo(usuarioId(AMARELO));
        assertThat(((Number) envio.getFirst().get("parceria_id")).longValue()).isEqualTo(parceria);
    }

    @Test
    void rbsPublicaOFormularioEmBrancoComVersoesEAMarcaBaixaAsDuas() {
        long parceria = amarelo();
        String rota = "/api/parcerias/" + parceria + "/formulario-ingressos";
        long comPapelAntes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel IS NOT NULL", parceria);
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);

        MvcTestResult v1 = enviarPara(rbs, rota, "formulario_v1.pdf", pdf("form um"));
        MvcTestResult v2 = enviarPara(rbs, rota, "formulario_v2.pdf", pdf("form dois"), "observacao", "novo valor");

        assertThat(v1).hasStatus(HttpStatus.CREATED);
        assertThat(v2).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(v1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(v2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(v1, "$.categoria")).isEqualTo("FORMULARIO_INGRESSOS");
        MvcTestResult espacos = marca.get("/api/parcerias/" + parceria + "/espacos");
        assertThat(lerJson(espacos, "$.formularioIngressos.versoes[0].versao")).isEqualTo(2);
        assertThat(lerJson(espacos, "$.formularioIngressos.versoes[0].nomeOriginal")).isEqualTo("formulario_v2.pdf");
        assertThat(lerLista(espacos, "$.formularioIngressos.versoes")).hasSize(2);
        assertThat(marca.get("/api/arquivos/" + id(v1))).hasStatus(HttpStatus.OK);
        assertThat(marca.get("/api/arquivos/" + id(v2))).hasStatus(HttpStatus.OK);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel IS NOT NULL", parceria))
                .as("o formulário em branco não é entrega").isEqualTo(comPapelAntes);
    }

    @Test
    void marcaNaoPublicaOFormularioEmBrancoRecebe403AntesDoCorpo() {
        SessaoDeApi marca = entrar(AMARELO);
        String rota = "/api/parcerias/" + amarelo() + "/formulario-ingressos";

        assertThat(enviarPara(marca, rota, "formulario.pdf", pdf("x"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(enviarSemArquivo(marca, rota)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.post(rota, "isto nao e json")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void marcaDeOutraParceriaNaoVeNemEnviaRecebe404() {
        long doAmarelo = amarelo();
        SessaoDeApi roxo = entrar(ROXO);

        assertThat(roxo.get("/api/parcerias/" + doAmarelo + "/espacos")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(enviarPara(roxo, envio(doAmarelo), "pedido.pdf", pdf("x"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE nome_original = 'pedido.pdf'")).isZero();
    }

    @Test
    void perfilFechadoDa403ParaAMarcaNoEnvioENaLeitura() {
        long parceria = amarelo();
        fecharAba(parceria, "PERFIL", false, true);
        long antes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria);

        MvcTestResult envio = enviarPara(entrar(AMARELO), envio(parceria), "pedido.pdf", pdf("x"));

        assertThat(envio).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(AMARELO).get("/api/parcerias/" + parceria + "/espacos")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(AMARELO).get("/api/parcerias/" + parceria + "/espacos?aba=PERFIL")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria)).isEqualTo(antes);
    }

    @Test
    void parceriaEncerradaRecusaASolicitacao() {
        long parceria = amarelo();
        encerrar(parceria);
        long antes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria);

        MvcTestResult envio = enviarPara(entrar(AMARELO), envio(parceria), "pedido.pdf", pdf("x"));

        assertThat(envio).hasStatus(HttpStatus.CONFLICT);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", parceria)).isEqualTo(antes);
    }

    @Test
    void arquivoComFormatoOuBytesErradosDa415ESemArquivoDa422() {
        long parceria = amarelo();
        SessaoDeApi marca = entrar(AMARELO);

        assertThat(enviarPara(marca, envio(parceria), "pedido.exe", pdf("x"))).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviarPara(marca, envio(parceria), "pedido.pdf", png())).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviarSemArquivo(marca, envio(parceria))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
