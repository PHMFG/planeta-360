package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
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
 * Testa a mudança de status da entrega (rota 19): só a RBS, tabela de transições, observação obrigatória e ultimaObservacao
 * vinda do histórico. Atende o item 4 da Entrega 2. [PERMISSAO] [ISOLAMENTO] [VALIDACAO] [HISTORICO]
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class StatusDaEntregaTest extends ApoioDeMateriais {

    MvcTestResult mudar(SessaoDeApi pessoa, long entrega, String status, String observacao) {
        String corpo = observacao == null ? "{\"status\":\"%s\"}".formatted(status)
                : "{\"status\":\"%s\",\"observacao\":\"%s\"}".formatted(status, observacao);
        return pessoa.patch("/api/entregas/" + entrega + "/status", corpo);
    }

    /** Uma entrega do Amarelo em MIDIA já em ENVIADO, como depois de um envio do patrocinador. */
    long entregaEnviada() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT para análise");
        enviar(entrar(AMARELO), entrega, "vt.pdf", pdf("vt"));
        return entrega;
    }

    // ---------------------------------------------------------------------
    // [PERMISSAO] e [ISOLAMENTO]
    // ---------------------------------------------------------------------

    @Test
    void soARbsMudaOStatusEOPatrocinadorRecebe403ComQualquerId() {
        long doAmarelo = entregaEnviada();
        SessaoDeApi amarelo = entrar(AMARELO);
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult propria = mudar(amarelo, doAmarelo, "APROVADO", null);
        MvcTestResult deOutro = mudar(roxo, doAmarelo, "APROVADO", null);
        MvcTestResult inexistente = mudar(roxo, 999999, "APROVADO", null);

        for (MvcTestResult resposta : List.of(propria, deOutro, inexistente)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(statusDaEntrega(doAmarelo)).isEqualTo("ENVIADO");
        assertThat(historico("ALTERACAO_STATUS", "entrega", doAmarelo)).as("só a do envio").hasSize(1);
    }

    @Test
    void rbsMudandoOStatusDeUmaEntregaQueNaoExisteRecebe404() {
        // O 403 do patrocinador (teste acima) não revela se o id existe; a RBS, que pode tudo, recebe 404 para o que não existe.
        MvcTestResult resposta = mudar(entrar(RBS), 999999, "APROVADO", null);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void mudarStatusExigeSessaoECsrf() {
        long entrega = entregaEnviada();

        assertThat(mudar(SessaoDeApi.anonima(mvc), entrega, "APROVADO", null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.patch().uri("/api/entregas/{id}/status", entrega).session(entrar(RBS).sessao())
                .contentType("application/json").content("{\"status\":\"APROVADO\"}").exchange()).as("sem token CSRF")
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // Observação obrigatória e ultimaObservacao
    // ---------------------------------------------------------------------

    @Test
    void pedirAjusteSemObservacaoDevolve422EComObservacaoDevolve200ComUltimaObservacao() {
        long entrega = entregaEnviada();
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult sem = mudar(rbs, entrega, "AJUSTE_SOLICITADO", null);
        MvcTestResult emBranco = mudar(rbs, entrega, "AJUSTE_SOLICITADO", "   ");
        MvcTestResult com = mudar(rbs, entrega, "AJUSTE_SOLICITADO", "Enviar o logo em vetor.");

        assertThat(sem).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(sem, "$.campos.observacao")).isNotNull();
        assertThat(emBranco).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(statusDaEntrega(entrega)).as("recusado: nada mudou antes da observação válida").isEqualTo("AJUSTE_SOLICITADO");
        assertThat(com).hasStatus(HttpStatus.OK);
        assertThat(lerJson(com, "$.status")).isEqualTo("AJUSTE_SOLICITADO");
        assertThat(lerJson(com, "$.ultimaObservacao.texto")).isEqualTo("Enviar o logo em vetor.");
        assertThat(lerJson(com, "$.ultimaObservacao.por")).isEqualTo("Equipe B2B RBS");
        assertThat((String) lerJson(com, "$.ultimaObservacao.em")).endsWith("-03:00");
        assertThat(lerJson(com, "$.id")).isEqualTo((int) entrega);
        assertThat(lerJson(com, "$.arquivos[0].nomeOriginal")).isEqualTo("vt.pdf");
        assertThat(lerJson(com, "$.atualizadoEm")).isNotNull();

        // O patrocinador vê o recado na lista de entregas.
        MvcTestResult doPatrocinador = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        assertThat(br.com.rbs.portal.ApoioDeTeste.<String>lerLista(doPatrocinador, "$[?(@.id == " + entrega + ")].ultimaObservacao.texto"))
                .containsExactly("Enviar o logo em vetor.");
    }

    @Test
    void historicoDaMudancaGuardaAnteriorNovoEObservacaoComAutorEIp() {
        long entrega = entregaEnviada();

        mudar(entrar(RBS), entrega, "EM_ANALISE", "Em análise pela equipe de mídia.");

        List<Map<String, Object>> linhas = historico("ALTERACAO_STATUS", "entrega", entrega);
        Map<String, Object> ultima = linhas.getLast();
        assertThat(linhas).hasSize(2);
        assertThat(((Number) ultima.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) ultima.get("parceria_id")).longValue()).isEqualTo(amarelo());
        assertThat(ultima.get("valor_anterior")).isEqualTo("ENVIADO");
        assertThat(ultima.get("valor_novo")).isEqualTo("EM_ANALISE");
        assertThat(ultima.get("detalhe")).isEqualTo("Em análise pela equipe de mídia.");
        assertThat(ultima.get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void ultimaObservacaoSobreviveAUmaMudancaSemTextoENaoViraTextoDoEnvioDoPatrocinador() {
        long entrega = entregaEnviada();
        SessaoDeApi rbs = entrar(RBS);
        mudar(rbs, entrega, "AJUSTE_SOLICITADO", "Falta o áudio.");
        enviar(entrar(AMARELO), entrega, "vt_v2.pdf", pdf("v2"));          // ALTERACAO_STATUS automática do patrocinador

        MvcTestResult aprovada = mudar(rbs, entrega, "APROVADO", null);

        assertThat(aprovada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(aprovada, "$.status")).isEqualTo("APROVADO");
        assertThat(lerJson(aprovada, "$.ultimaObservacao.texto")).as("o recado da RBS continua; o texto do envio não conta")
                .isEqualTo("Falta o áudio.");
        assertThat(lerJson(aprovada, "$.arquivos[0].versao")).isEqualTo(2);
    }

    @Test
    void reabrirEntregaAprovadaExigeObservacao() {
        SessaoDeApi rbs = entrar(RBS);
        long aprovada = entregaDaAmostra(amarelo(), "Logo final para aplicações no evento");

        MvcTestResult sem = mudar(rbs, aprovada, "EM_ANALISE", null);
        MvcTestResult com = mudar(rbs, aprovada, "EM_ANALISE", "Reabrindo: o cliente trocou a marca.");

        assertThat(sem).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(sem, "$.campos.observacao")).isNotNull();
        assertThat(com).hasStatus(HttpStatus.OK);
        assertThat(lerJson(com, "$.status")).isEqualTo("EM_ANALISE");
        assertThat(lerJson(com, "$.ultimaObservacao.texto")).isEqualTo("Reabrindo: o cliente trocou a marca.");
    }

    @Test
    void observacaoDeMaisDe500CaracteresDevolve422() {
        long entrega = entregaEnviada();

        MvcTestResult longa = mudar(entrar(RBS), entrega, "AJUSTE_SOLICITADO", "x".repeat(501));
        MvcTestResult noLimite = mudar(entrar(RBS), entrega, "AJUSTE_SOLICITADO", "x".repeat(500));

        assertThat(longa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longa, "$.campos.observacao")).isNotNull();
        assertThat(noLimite).hasStatus(HttpStatus.OK);
    }

    @Test
    void statusQueNaoExisteOuAusenteDevolve422() {
        long entrega = entregaEnviada();
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult inexistente = mudar(rbs, entrega, "PUBLICADO", null);
        MvcTestResult ausente = rbs.patch("/api/entregas/" + entrega + "/status", "{}");

        assertThat(inexistente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(inexistente, "$.campos.status")).isNotNull();
        assertThat(ausente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ausente, "$.campos.status")).isNotNull();
    }

    // ---------------------------------------------------------------------
    // Tabela de transições
    // ---------------------------------------------------------------------

    @Test
    void todaTransicaoDaTabelaDoContratoFuncionaETodaOutraDa409() {
        Map<String, List<String>> permitidas = Map.of(
                "NAO_INICIADO", List.of("AGUARDANDO_ENVIO", "NAO_APLICAVEL"),
                "AGUARDANDO_ENVIO", List.of("NAO_APLICAVEL"),
                "ENVIADO", List.of("EM_ANALISE", "AJUSTE_SOLICITADO", "APROVADO"),
                "EM_ANALISE", List.of("AJUSTE_SOLICITADO", "APROVADO"),
                "AJUSTE_SOLICITADO", List.of("NAO_APLICAVEL"),
                "APROVADO", List.of("EM_ANALISE"),
                "NAO_APLICAVEL", List.of("NAO_INICIADO"));
        List<String> todos = List.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "ENVIADO", "EM_ANALISE", "AJUSTE_SOLICITADO", "APROVADO",
                "NAO_APLICAVEL");
        SessaoDeApi rbs = entrar(RBS);
        long entrega = criarEntrega(rbs, amarelo(), "PERFIL", "Percorre a tabela");

        for (String de : todos) {
            for (String para : todos) {
                definirStatus(entrega, de);
                MvcTestResult resposta = mudar(rbs, entrega, para, "Observação do teste.");
                if (permitidas.get(de).contains(para)) {
                    assertThat(resposta).as(de + " -> " + para).hasStatus(HttpStatus.OK);
                    assertThat(statusDaEntrega(entrega)).isEqualTo(para);
                } else {
                    assertThat(resposta).as(de + " -> " + para).hasStatus(HttpStatus.CONFLICT);
                    assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta entrega não pode ir de " + de + " para " + para + ".");
                    assertThat(statusDaEntrega(entrega)).as("recusado: o status ficou").isEqualTo(de);
                }
            }
        }
    }

    @Test
    void transicaoProibidaNaoGravaNadaNoHistorico() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "PERFIL", "Sem salto");
        long antes = contar("SELECT count(*) FROM historico WHERE entidade_id = ? AND entidade = 'entrega'", entrega);

        MvcTestResult resposta = mudar(entrar(RBS), entrega, "APROVADO", "Quero pular.");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(corpo(resposta)).doesNotContain("SQL").doesNotContain("rn_");
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade_id = ? AND entidade = 'entrega'", entrega)).isEqualTo(antes);
    }
}
