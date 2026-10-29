package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Datas;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 14, 15 e 16 (entregas da parceria, criar entrega, fila da RBS) com o isolamento entre o Roxo e o Amarelo.
 * Atende o item 4 da Entrega 2. [ISOLAMENTO] [PERMISSAO] [VALIDACAO] [HISTORICO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class EntregasTest extends ApoioDeMateriais {

    // ---------------------------------------------------------------------
    // Rota 15: criar entrega (só a RBS)
    // ---------------------------------------------------------------------

    @Test
    void rbsCriaEntregaEHistoricoGravaCriacao() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = rbs.post("/api/parcerias/" + amarelo() + "/entregas", """
                {"abaCodigo":"MIDIA","titulo":"VT para os telões dos palcos","descricao":"Arquivo em MP4, até 30 segundos.",
                 "prazo":"2026-12-01"}
                """);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.parceriaId")).isEqualTo((int) amarelo());
        assertThat(lerJson(resposta, "$.abaCodigo")).isEqualTo("MIDIA");
        assertThat(lerJson(resposta, "$.titulo")).isEqualTo("VT para os telões dos palcos");
        assertThat(lerJson(resposta, "$.descricao")).isEqualTo("Arquivo em MP4, até 30 segundos.");
        assertThat(lerJson(resposta, "$.status")).as("o padrão é AGUARDANDO_ENVIO").isEqualTo("AGUARDANDO_ENVIO");
        assertThat(lerJson(resposta, "$.prazo")).isEqualTo("2026-12-01");
        assertThat(lerJson(resposta, "$.criadoEm").toString()).endsWith("-03:00");
        assertThat(lerLista(resposta, "$.arquivos")).isEmpty();
        assertThat(lerJson(resposta, "$.ultimaObservacao")).isNull();

        // [HISTORICO] CRIACAO com autor, parceria, IP e navegador.
        List<Map<String, Object>> criacao = historico("CRIACAO", "entrega", id(resposta));
        assertThat(criacao).hasSize(1);
        assertThat(((Number) criacao.getFirst().get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) criacao.getFirst().get("parceria_id")).longValue()).isEqualTo(amarelo());
        assertThat(criacao.getFirst().get("valor_novo")).isEqualTo("AGUARDANDO_ENVIO");
        assertThat(criacao.getFirst().get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void statusInicialPodeSerNaoIniciadoOuNaoAplicavel() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult naoIniciado = rbs.post("/api/parcerias/" + amarelo() + "/entregas",
                "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"A\",\"status\":\"NAO_INICIADO\"}");
        MvcTestResult naoAplicavel = rbs.post("/api/parcerias/" + amarelo() + "/entregas",
                "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"B\",\"status\":\"NAO_APLICAVEL\"}");
        MvcTestResult aprovado = rbs.post("/api/parcerias/" + amarelo() + "/entregas",
                "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"C\",\"status\":\"APROVADO\"}");

        assertThat(lerJson(naoIniciado, "$.status")).isEqualTo("NAO_INICIADO");
        assertThat(lerJson(naoAplicavel, "$.status")).isEqualTo("NAO_APLICAVEL");
        assertThat(aprovado).as("APROVADO não é um status inicial").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(aprovado, "$.campos.status")).isNotNull();
    }

    @Test
    void criarEntregaSemTituloOuComAbaQueNaoExisteDevolve422ComCampos() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult semTitulo = rbs.post("/api/parcerias/" + amarelo() + "/entregas", "{\"abaCodigo\":\"MIDIA\",\"titulo\":\" \"}");
        MvcTestResult abaInvalida = rbs.post("/api/parcerias/" + amarelo() + "/entregas", "{\"abaCodigo\":\"FINANCEIRO\",\"titulo\":\"X\"}");
        MvcTestResult tituloLongo = rbs.post("/api/parcerias/" + amarelo() + "/entregas",
                "{\"abaCodigo\":\"MIDIA\",\"titulo\":\"" + "x".repeat(161) + "\"}");

        assertThat(semTitulo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semTitulo, "$.campos.titulo")).isNotNull();
        assertThat(abaInvalida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(abaInvalida, "$.campos.abaCodigo")).isNotNull();
        assertThat(tituloLongo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(tituloLongo, "$.campos.titulo")).isNotNull();
    }

    @Test
    void criarEntregaEmParceriaQueNaoExisteDevolve404() {
        MvcTestResult resposta = entrar(RBS).post("/api/parcerias/999999/entregas", "{\"abaCodigo\":\"MIDIA\",\"titulo\":\"X\"}");

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void patrocinadorNaoCriaEntregaNemNaPropriaParceriaNemNaDeOutroNemEmIdInexistente() {
        SessaoDeApi roxo = entrar(ROXO);
        String corpo = "{\"abaCodigo\":\"MIDIA\",\"titulo\":\"Entrega do patrocinador\"}";
        long entregasAntes = contar("SELECT count(*) FROM entrega");

        MvcTestResult propria = roxo.post("/api/parcerias/" + roxo() + "/entregas", corpo);
        MvcTestResult doAmarelo = roxo.post("/api/parcerias/" + amarelo() + "/entregas", corpo);
        MvcTestResult inexistente = roxo.post("/api/parcerias/999999/entregas", corpo);

        // [PERMISSAO] O perfil vem antes do isolamento: o 403 é igual para os três e não revela se o id existe.
        for (MvcTestResult resposta : List.of(propria, doAmarelo, inexistente)) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(contar("SELECT count(*) FROM entrega")).isEqualTo(entregasAntes);
    }

    @Test
    void criarEntregaExigeSessaoECsrf() {
        assertThat(SessaoDeApi.anonima(mvc).post("/api/parcerias/" + amarelo() + "/entregas", "{}")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(entrar(RBS).postSemCsrf("/api/parcerias/" + amarelo() + "/entregas",
                "{\"abaCodigo\":\"MIDIA\",\"titulo\":\"X\"}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // Rota 14: entregas de uma parceria
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorVeAsEntregasDaPropriaParceriaNaOrdemDaAbaENoFormatoDoContrato() {
        MvcTestResult resposta = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/entregas");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<String> abas = lerLista(resposta, "$[*].abaCodigo");
        assertThat(abas).as("11 entregas do Amarelo na amostra, na ordem das abas").hasSize(11);
        assertThat(abas).isSortedAccordingTo((a, b) -> Integer.compare(ordemDaAba(a), ordemDaAba(b)));
        Map<String, Object> logo = lerLista(resposta, "$[?(@.titulo == 'Logo final para aplicações no evento')]").stream()
                .map(o -> (Map<String, Object>) o).findFirst().orElseThrow();
        assertThat(logo).containsEntry("status", "APROVADO").containsEntry("prazo", "2026-10-20")
                .containsKeys("id", "parceriaId", "criadoEm", "atualizadoEm", "arquivos", "ultimaObservacao");
        List<Map<String, Object>> arquivos = (List<Map<String, Object>>) logo.get("arquivos");
        assertThat(arquivos).as("as duas versões, da mais nova para a mais antiga").extracting(a -> a.get("versao"))
                .containsExactly(2, 1);
        assertThat(arquivos.getFirst()).containsEntry("nomeOriginal", "logo_final.pdf").containsEntry("mimeType", "application/pdf")
                .containsEntry("tamanhoBytes", 501877).containsEntry("categoria", "MATERIAL_MARCA");
        assertThat((String) arquivos.getFirst().get("enviadoEm")).endsWith("-03:00");
        assertThat(OffsetDateTime.parse((String) arquivos.getFirst().get("enviadoEm")))
                .as("a v2 foi enviada depois da v1, e as duas no passado")
                .isAfter(OffsetDateTime.parse((String) arquivos.get(1).get("enviadoEm"))).isBefore(OffsetDateTime.now());
        assertThat((Map<String, Object>) arquivos.getFirst().get("enviadoPor")).containsEntry("nome", "Marketing Amarelo")
                .containsEntry("perfil", "ADMIN_PATROCINADOR");
    }

    @Test
    void cadaVersaoDoArquivoTrazAObservacaoQueAMarcaEscreveuNaquelaVersao() {
        MvcTestResult resposta = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/entregas");

        Map<String, Object> logo = lerLista(resposta, "$[?(@.titulo == 'Logo final para aplicações no evento')]").stream()
                .map(o -> (Map<String, Object>) o).findFirst().orElseThrow();
        List<Map<String, Object>> arquivos = (List<Map<String, Object>>) logo.get("arquivos");
        assertThat(arquivos.get(0)).containsEntry("versao", 2).containsEntry("observacao", "Logo em vetor, como pedido.");
        assertThat(arquivos.get(1)).containsEntry("versao", 1).containsEntry("observacao", "Primeira versão do logo, em PDF.");
        // Quem não escreveu nada recebe null, e o campo existe em toda versão.
        List<Object> todas = lerLista(resposta, "$[*].arquivos[*].observacao");
        assertThat(todas).contains("Manual de marca e logos em alta resolução.").containsNull();
        assertThat(lerLista(resposta, "$[*].arquivos[*].versao")).hasSameSizeAs(todas);
    }

    @Test
    void filtrosPorAbaEStatus() {
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult midia = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        MvcTestResult emAnalise = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?status=EM_ANALISE");
        MvcTestResult midiaAprovada = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA&status=APROVADO");

        assertThat(lerLista(midia, "$[*].titulo")).containsExactlyInAnyOrder("Briefing completo para ações de conteúdo",
                "Peça de 30\" para mídia própria");
        assertThat(lerLista(emAnalise, "$[*].titulo")).containsExactly("Peça de 30\" para mídia própria");
        assertThat(lerLista(midiaAprovada, "$[*].titulo")).containsExactly("Briefing completo para ações de conteúdo");
    }

    @Test
    void abaOuStatusQueNaoExistemDevolve422() {
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult aba = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?aba=FINANCEIRO");
        MvcTestResult status = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?status=PUBLICADO");

        assertThat(aba).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(aba, "$.campos.aba")).isNotNull();
        assertThat(status).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(status, "$.campos.status")).isNotNull();
    }

    @Test
    void roxoListandoEntregasDaAbaPesquisaFechadaRecebe403EsemAbaNaoVeEssasEntregas() {
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult fechada = roxo.get("/api/parcerias/" + roxo() + "/entregas?aba=PESQUISA");
        MvcTestResult todas = roxo.get("/api/parcerias/" + roxo() + "/entregas");

        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo("Esta aba não faz parte da cota da sua parceria.");
        assertThat(todas).hasStatus(HttpStatus.OK);
        assertThat(lerLista(todas, "$[*].abaCodigo")).as("a aba fechada some da lista geral").doesNotContain("PESQUISA").isNotEmpty();
        // A RBS vê a aba fechada normalmente.
        MvcTestResult daRbs = entrar(RBS).get("/api/parcerias/" + roxo() + "/entregas?aba=PESQUISA");
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(lerLista(daRbs, "$[*].titulo")).containsExactly("Três perguntas personalizadas");
    }

    @Test
    void abaLiberadaNoBancoMasInvisivelContaComoFechada() {
        long parceria = roxo();
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                .param("p", parceria).update();

        MvcTestResult resposta = entrar(ROXO).get("/api/parcerias/" + parceria + "/entregas?aba=MIDIA");

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void roxoListandoEntregasDoAmareloRecebe404IgualAParceriaInexistente() {
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult doAmarelo = roxo.get("/api/parcerias/" + amarelo() + "/entregas");
        MvcTestResult inexistente = roxo.get("/api/parcerias/999999/entregas");

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("Amarelo");
    }

    @Test
    void rbsVeTodasAsEntregasEAbasFechadasInclusive() {
        MvcTestResult resposta = entrar(RBS).get("/api/parcerias/" + roxo() + "/entregas");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$[*].abaCodigo")).contains("PESQUISA");
        assertThat(lerLista(resposta, "$[*].titulo")).hasSize(8);
    }

    @Test
    void listaExigeSessao() {
        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo() + "/entregas")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private static int ordemDaAba(String codigo) {
        return List.of("PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO", "PESQUISA", "CONTRATO").indexOf(codigo);
    }

    // ---------------------------------------------------------------------
    // Rota 16: fila de trabalho da RBS
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorNaoVeAFilaDaRbs() {
        SessaoDeApi roxo = entrar(ROXO);

        assertThat(roxo.get("/api/entregas")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(roxo.get("/api/entregas?parceriaId=" + roxo())).as("nem a da própria parceria").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/entregas")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void filaTemOFormatoDoContratoEPaginacaoPadrao() {
        MvcTestResult resposta = entrar(RBS).get("/api/entregas");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.pagina")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.tamanho")).isEqualTo(50);
        assertThat(lerJson(resposta, "$.total")).as("11 + 8 + 9 entregas da amostra").isEqualTo(28);
        assertThat(lerLista(resposta, "$.itens")).hasSize(28);
        Map<String, Object> peca = lerLista(resposta, "$.itens[?(@.titulo == 'Peça de 30\" para mídia própria')]").stream()
                .map(o -> (Map<String, Object>) o).findFirst().orElseThrow();
        assertThat(peca).containsEntry("status", "EM_ANALISE").containsEntry("abaCodigo", "MIDIA").containsEntry("atrasada", false)
                .containsKeys("id", "prazo", "atualizadoEm", "ultimoEnvioEm");
        assertThat((Map<String, Object>) peca.get("parceria")).containsEntry("patrocinador", "Cliente Amarelo")
                .containsEntry("cota", "MASTER").containsEntry("id", (int) amarelo());
        assertThat(peca.get("atualizadoEm").toString()).endsWith("-03:00");
    }

    @Test
    void filaPoeQuemEsperaAnaliseNaFrenteDaMaisAntigaParaAMaisNova() {
        SessaoDeApi rbs = entrar(RBS);
        // Duas entregas esperando análise, uma de cada parceria, com o último envio em dias diferentes.
        long maisNova = criarEntrega(rbs, amarelo(), "MIDIA", "Espera há pouco");
        long maisAntiga = criarEntrega(rbs, roxo(), "MIDIA", "Espera há muito");
        definirStatus(maisNova, "ENVIADO");
        definirStatus(maisAntiga, "ENVIADO");
        jdbc.sql("UPDATE entrega SET atualizado_em = now() - interval '1 day' WHERE id = :id").param("id", maisNova).update();
        jdbc.sql("UPDATE entrega SET atualizado_em = now() - interval '20 days' WHERE id = :id").param("id", maisAntiga).update();

        MvcTestResult resposta = rbs.get("/api/entregas?tamanho=200");

        List<Integer> ids = lerLista(resposta, "$.itens[*].id");
        List<String> status = lerLista(resposta, "$.itens[*].status");
        int ultimaEmAnalise = 0;
        for (int i = 0; i < status.size(); i++) {
            if (status.get(i).equals("ENVIADO") || status.get(i).equals("EM_ANALISE")) {
                ultimaEmAnalise = i;
            }
        }
        assertThat(status.subList(0, ultimaEmAnalise + 1)).as("as que esperam análise vêm todas antes das outras")
                .allMatch(s -> s.equals("ENVIADO") || s.equals("EM_ANALISE"));
        assertThat(ids.indexOf((int) maisAntiga)).as("a que espera há mais tempo vem primeiro").isLessThan(ids.indexOf((int) maisNova));
        assertThat(ids.indexOf((int) maisNova)).isLessThanOrEqualTo(ultimaEmAnalise);
    }

    @Test
    void filaDepoisDasEmAnaliseSegueOPrazoMaisProximoEDeixaSemPrazoPorUltimo() {
        SessaoDeApi rbs = entrar(RBS);
        long longe = criarEntrega(rbs, amarelo(), "PERFIL", "Prazo longe");
        long semPrazo = criarEntrega(rbs, amarelo(), "PERFIL", "Sem prazo");
        long perto = criarEntrega(rbs, amarelo(), "PERFIL", "Prazo perto");
        jdbc.sql("UPDATE entrega SET prazo = date '2035-12-01' WHERE id = :id").param("id", longe).update();
        jdbc.sql("UPDATE entrega SET prazo = date '2035-01-01' WHERE id = :id").param("id", perto).update();

        MvcTestResult resposta = rbs.get("/api/entregas?status=AGUARDANDO_ENVIO&tamanho=200");

        assertThat(lerLista(resposta, "$.itens[*].id")).as("prazo mais próximo primeiro, sem prazo por último")
                .containsExactly((int) perto, (int) longe, (int) semPrazo);
    }

    @Test
    void filaFiltraPorStatusMultiploParceriaEventoAbaETitulo() {
        SessaoDeApi rbs = entrar(RBS);
        long evento2027 = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();

        MvcTestResult analise = rbs.get("/api/entregas?status=ENVIADO,EM_ANALISE");
        MvcTestResult doRoxo = rbs.get("/api/entregas?parceriaId=" + roxo());
        MvcTestResult deEvento = rbs.get("/api/entregas?eventoId=" + evento2027);
        MvcTestResult deOutroEvento = rbs.get("/api/entregas?eventoId=999999");
        MvcTestResult pesquisa = rbs.get("/api/entregas?abaCodigo=PESQUISA");
        MvcTestResult busca = rbs.get("/api/entregas?q=VT&parceriaId=" + roxo());
        MvcTestResult buscaComCuringa = rbs.get("/api/entregas?q=%");

        assertThat(lerLista(analise, "$.itens[*].titulo")).containsExactlyInAnyOrder("Peça de 30\" para mídia própria",
                "Destinação das cortesias");
        assertThat(lerJson(doRoxo, "$.total")).isEqualTo(8);
        assertThat(lerLista(doRoxo, "$.itens[*].parceria.patrocinador")).containsOnly("Cliente Roxo");
        assertThat(lerJson(deEvento, "$.total")).as("todas as parcerias da amostra são de 2027").isEqualTo(28);
        assertThat(lerJson(deOutroEvento, "$.total")).isEqualTo(0);
        assertThat(lerLista(deOutroEvento, "$.itens")).isEmpty();
        assertThat(lerLista(pesquisa, "$.itens[*].abaCodigo")).hasSize(3).containsOnly("PESQUISA");
        assertThat(lerLista(busca, "$.itens[*].titulo")).containsExactly("VT para os telões dos palcos");
        assertThat(lerJson(buscaComCuringa, "$.total")).as("% é texto, não curinga").isEqualTo(0);
    }

    @Test
    void filaMostraAtrasadasSoQuandoOPrazoVenceuEAEntregaAindaEstaPendente() {
        SessaoDeApi rbs = entrar(RBS);
        long pendente = criarEntrega(rbs, amarelo(), "PERFIL", "Venceu e está pendente");
        long aprovada = criarEntrega(rbs, amarelo(), "PERFIL", "Venceu mas foi aprovada");
        long futura = criarEntrega(rbs, amarelo(), "PERFIL", "Ainda dentro do prazo");
        long hoje = criarEntrega(rbs, amarelo(), "PERFIL", "Vence hoje");
        definirStatus(aprovada, "APROVADO");
        jdbc.sql("UPDATE entrega SET prazo = :prazo WHERE id IN (:a, :b)").param("prazo", Datas.hoje().minusDays(1))
                .param("a", pendente).param("b", aprovada).update();
        jdbc.sql("UPDATE entrega SET prazo = :prazo WHERE id = :id").param("prazo", Datas.hoje().plusDays(1)).param("id", futura).update();
        jdbc.sql("UPDATE entrega SET prazo = :prazo WHERE id = :id").param("prazo", Datas.hoje()).param("id", hoje).update();

        MvcTestResult atrasadas = rbs.get("/api/entregas?atrasadas=true&tamanho=200");
        MvcTestResult doDia = rbs.get("/api/entregas?q=Vence hoje");

        List<Integer> ids = lerLista(atrasadas, "$.itens[*].id");
        assertThat(ids).contains((int) pendente).doesNotContain((int) aprovada, (int) futura, (int) hoje);
        assertThat(lerLista(atrasadas, "$.itens[*].atrasada")).containsOnly(true);
        assertThat(lerLista(rbs.get("/api/entregas?q=Venceu&tamanho=200"), "$.itens[?(@.titulo == 'Venceu mas foi aprovada')].atrasada"))
                .containsExactly(false);
        assertThat(lerLista(doDia, "$.itens[*].atrasada")).as("vence hoje ainda não está atrasada").containsExactly(false);
    }

    @Test
    void filaPaginaEOTotalContinuaIgual() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult primeira = rbs.get("/api/entregas?tamanho=10&pagina=0");
        MvcTestResult ultima = rbs.get("/api/entregas?tamanho=10&pagina=2");
        MvcTestResult alemDoFim = rbs.get("/api/entregas?tamanho=10&pagina=9");

        assertThat(lerLista(primeira, "$.itens")).hasSize(10);
        assertThat(lerJson(primeira, "$.total")).isEqualTo(28);
        assertThat(lerLista(ultima, "$.itens")).hasSize(8);
        assertThat(lerJson(ultima, "$.pagina")).isEqualTo(2);
        assertThat(lerJson(ultima, "$.tamanho")).isEqualTo(10);
        assertThat(lerList(alemDoFim)).isEmpty();
        assertThat(lerJson(alemDoFim, "$.total")).isEqualTo(28);
        List<Integer> idsDaPrimeira = lerLista(primeira, "$.itens[*].id");
        List<Integer> idsDaUltima = lerLista(ultima, "$.itens[*].id");
        assertThat(idsDaPrimeira).doesNotContainAnyElementsOf(idsDaUltima);
    }

    @Test
    void filaRecusaFiltroQueNaoExisteComOCampoErrado() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult status = rbs.get("/api/entregas?status=ENVIADO,PUBLICADO");
        MvcTestResult aba = rbs.get("/api/entregas?abaCodigo=FINANCEIRO");
        MvcTestResult grande = rbs.get("/api/entregas?tamanho=201");
        MvcTestResult zero = rbs.get("/api/entregas?tamanho=0");
        MvcTestResult negativa = rbs.get("/api/entregas?pagina=-1");

        assertThat(status).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(status, "$.campos.status")).isNotNull();
        assertThat(aba).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(aba, "$.campos.abaCodigo")).isNotNull();
        assertThat(grande).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(grande, "$.campos.tamanho")).isNotNull();
        assertThat(zero).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(negativa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(negativa, "$.campos.pagina")).isNotNull();
    }

    private static List<Object> lerList(MvcTestResult resposta) {
        return lerLista(resposta, "$.itens");
    }
}
