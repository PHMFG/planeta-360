package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemAviso;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemUsuario;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O loop do agente de ponta a ponta contra o banco, com o provedor roteirizado (nenhum provedor de verdade é chamado):
 * caminho feliz (iniciar, cartão, pausa, resposta pela rota 46, editar, finalizar), rodada com duas chamadas, limite de 8
 * chamadas, erro de ferramenta que volta ao modelo, mensagem nova com o cartão aberto, a regra do número no texto do relatório e
 * do chat, o ataque de outra marca, o reasoning_content, a falha do modelo e o histórico sempre válido. O que depende de NÃO haver
 * transação aberta está em LoopSemTransacaoTest. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class LoopDoAgenteTest {

    static final String INICIAR = "relatorio_iniciar";
    static final String SOLICITAR = "relatorio_solicitar_campos";
    static final String EDITAR = "relatorio_editar";
    static final String FINALIZAR = "relatorio_finalizar";
    static final String CONSULTAR = "parceria_consultar";
    static final String BUSCAR = "buscar_documentos";
    static final String LISTAR_ENTREGAS = "listar_entregas";
    static final String CONSULTAR_COTA = "consultar_cota";
    static final String CONSULTAR_PRAZOS = "consultar_prazos";
    static final String CONTAR_DIAS = "contar_dias";
    static final String CALCULAR = "calcular";
    static final String PEDIDO_DE_RELATORIO = "pedido_de_relatorio";
    static final String GERAR_MODELO = "gerar_modelo";
    /** As ferramentas que o modelo recebe no Atlas só chat, na ordem em que o registro as oferece. */
    static final java.util.List<String> DO_CHAT = java.util.List.of(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO);

    static final String ARGS_INICIAR = "{\"modelo\":\"ATIVACAO_EVENTO\",\"objetivo\":\"Relatório da ativação\"}";
    static final String ARGS_CARTAO = "{\"mensagem\":\"Preciso destes números.\",\"campos\":[\"ativacao.visitantes_total\","
            + "\"ativacao.cadastros_consentidos\"]}";
    static final String RESUMO = "A marca {{parc:patrocinador}} teve {{cons:aplicacoes.comprovadas}} de "
            + "{{cons:aplicacoes.previstas}} aplicações comprovadas.";
    static final String PUBLICO = "O espaço recebeu {{dado:ativacao.visitantes_total}} visitantes, e {{calc:taxa_cadastro}} deixaram cadastro.";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    record Mensagem(Long id, String papel, String conteudo, String toolCalls, String toolCallId, String reasoningContent) {
    }

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long abrirConversa() {
        return atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
    }

    ConversaDto falar(UsuarioAutenticado pessoa, long conversa, String texto) {
        return atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    ConversaDto falar(long conversa, String texto) {
        return falar(marca(), conversa, texto);
    }

    ConversaDto responder(long solicitacao, String acao, Object... valoresPlanos) {
        return atlas.responderCartao(marca(), solicitacao, new RespostaDoCartaoRequisicao(acao, valores(valoresPlanos)), requisicao());
    }

    void informarTudo() {
        dados.gravar(marca(), amarelo(), valores(VISITANTES, 1200L, CADASTROS, 1072L), OrigemDoDado.ATLAS, requisicao());
    }

    static String edicao(String... blocoETexto) {
        List<Map<String, String>> operacoes = new java.util.ArrayList<>();
        for (int i = 0; i < blocoETexto.length; i += 2) {
            operacoes.add(Map.of("op", "definir_texto", "bloco", blocoETexto[i], "texto", blocoETexto[i + 1]));
        }
        return AtlasJson.escrever(Map.of("operacoes", operacoes));
    }

    List<Mensagem> mensagens(long conversa) {
        return jdbc.sql("""
                SELECT id, papel, conteudo, CAST(tool_calls AS text) AS tool_calls, tool_call_id, reasoning_content
                FROM atlas_mensagem WHERE conversa_id = :id ORDER BY ordem
                """).param("id", conversa).query(Mensagem.class).list();
    }

    List<String> papeis(long conversa) {
        return mensagens(conversa).stream().map(Mensagem::papel).toList();
    }

    static List<Map<String, Object>> chamadas(Mensagem mensagem) {
        return AtlasJson.ler(mensagem.toolCalls(), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    @SuppressWarnings("unchecked")
    static String idDaChamada(Mensagem mensagem, int posicao) {
        return (String) chamadas(mensagem).get(posicao).get("id");
    }

    @SuppressWarnings("unchecked")
    static String nomeDaChamada(Mensagem mensagem, int posicao) {
        return (String) ((Map<String, Object>) chamadas(mensagem).get(posicao).get("function")).get("name");
    }

    Mensagem respostaDa(long conversa, String chamadaId) {
        return mensagens(conversa).stream().filter(m -> "tool".equals(m.papel()) && chamadaId.equals(m.toolCallId())).findFirst().orElseThrow();
    }

    /** O JSON da resposta de ferramenta como mapa. */
    static Map<String, Object> json(String texto) {
        return AtlasJson.ler(texto, new TypeReference<Map<String, Object>>() {
        });
    }

    String statusDoRelatorio(long conversa) {
        return jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c ORDER BY id DESC LIMIT 1").param("c", conversa)
                .query(String.class).single();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    static List<String> tipos(ConversaDto conversa) {
        return conversa.itens().stream().map(i -> switch (i) {
            case ItemUsuario u -> "usuario";
            case AtlasDtos.ItemRaciocinio r -> "raciocinio";
            case ItemTexto t -> "texto";
            case AtlasDtos.ItemFerramenta f -> "ferramenta";
            case ItemAviso a -> "aviso";
            case ItemCartao c -> "cartao";
            case ItemRelatorioPronto p -> "relatorio_pronto";
            case AtlasDtos.ItemCalculo c -> "calculo";
            case AtlasDtos.ItemPedidoRelatorio r -> "pedido_relatorio";
            case AtlasDtos.ItemModelo m -> "modelo";
        }).toList();
    }

    static String textoDoItem(ConversaDto conversa, int posicao) {
        return ((ItemTexto) conversa.itens().get(posicao)).texto();
    }

    ItemCartao cartaoDe(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------
    // O caminho feliz
    // ---------------------------------------------------------------------

    @Test
    void caminhoFelizIniciaPedeOCartaoPausaRetomaEditaEFinaliza() {
        long conversa = abrirConversa();
        modelo.chamadaComTexto("Vou montar o relatório da ativação.", INICIAR, ARGS_INICIAR)
                .chamada(SOLICITAR, ARGS_CARTAO)
                .chamada(EDITAR, edicao("b-resumo", RESUMO, "b-publico", PUBLICO))
                .chamada(FINALIZAR, "{}");

        // 1. a pessoa pede; o modelo inicia, pede o cartão, e o turno PARA com a chamada do cartão sem resposta
        ConversaDto turno1 = falar(conversa, "Quero o relatório da nossa ativação.");

        assertThat(turno1.llmDisponivel()).isTrue();
        assertThat(tipos(turno1)).containsExactly("usuario", "texto", "cartao");
        assertThat(textoDoItem(turno1, 1)).isEqualTo("Vou montar o relatório da ativação.");
        ItemCartao cartao = cartaoDe(turno1);
        assertThat(cartao.status()).isEqualTo("PENDENTE");
        assertThat(cartao.mensagem()).isEqualTo("Preciso destes números.");
        assertThat(cartao.campos()).extracting("chave").containsExactly(VISITANTES, CADASTROS);
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(nomesDasFerramentas(modelo.pedidos().get(0))).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, INICIAR);
        assertThat(nomesDasFerramentas(modelo.pedidos().get(1))).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);
        assertThat(modelo.pedidos()).allSatisfy(ProvedorRoteirizado::exigirHistoricoValido);

        List<Mensagem> historico = mensagens(conversa);
        assertThat(historico).extracting(Mensagem::papel).containsExactly("user", "assistant", "tool", "assistant");
        String idDoCartao = idDaChamada(historico.get(3), 0);
        assertThat(idDoCartao).startsWith("call_").isNotEqualTo("id-do-modelo");
        assertThat(nomeDaChamada(historico.get(3), 0)).isEqualTo(SOLICITAR);
        assertThat(historico).noneMatch(m -> "tool".equals(m.papel()) && idDoCartao.equals(m.toolCallId()));   // a do cartão está pendente
        assertThat(json(historico.get(2).conteudo())).containsKeys("relatorioId", "secoes", "dados", "lacunasObrigatorias");
        assertThat(statusDoRelatorio(conversa)).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE tool_call_id = ? AND status = 'PENDENTE'", idDoCartao)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.ferramenta = 'relatorio_solicitar_campos' AND t.resultado IS NULL", conversa)).isEqualTo(1);

        // o prompt de sistema leva a data de hoje e o nome da marca, nunca ids
        String sistema = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        assertThat(sistema).isEqualTo(ApoioDosPrompts.esperado(true, "Cliente Amarelo")).contains("Cliente Amarelo")
                .doesNotContain("@MARCA@").doesNotContain("@DATA@").doesNotContain("parceria_id").contains("relatorio_iniciar");
        assertThat(modelo.pedidos().get(0).sessao()).isEqualTo(String.valueOf(conversa));

        // 2. a pessoa responde o cartão (rota 46): a resposta da ferramenta pendente entra com o MESMO tool_call_id e o loop retoma
        ConversaDto turno2 = responder(cartao.solicitacaoId(), "accept", VISITANTES, 1200L, CADASTROS, 1072L);

        assertThat(tipos(turno2)).containsExactly("relatorio_pronto");
        assertThat(modelo.pedidos()).hasSize(4);
        var retomada = modelo.pedidos().get(2).mensagens();
        var respostaDoCartao = retomada.get(retomada.size() - 1);
        assertThat(respostaDoCartao.papel()).isEqualTo("tool");
        assertThat(respostaDoCartao.chamadaId()).isEqualTo(idDoCartao);
        assertThat(respostaDoCartao.ferramenta()).isEqualTo(SOLICITAR);
        assertThat(json(respostaDoCartao.conteudo())).containsEntry("acao", "accept");
        assertThat(respostaDoCartao.conteudo()).contains("\"chave\":\"ativacao.visitantes_total\"", "\"ref\":\"dado:ativacao.visitantes_total\"",
                "\"valor\":1200", "\"valor\":1072");
        assertThat(nomesDasFerramentas(modelo.pedidos().get(2))).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);   // sem iniciar: já há relatório
        assertThat(modelo.pedidos()).allSatisfy(ProvedorRoteirizado::exigirHistoricoValido);
        assertThat(modelo.sobraDoRoteiro()).isZero();

        // 3. o relatório ficou pronto, com os parágrafos redigidos pelo ATLAS e os números preenchidos pelo servidor
        assertThat(statusDoRelatorio(conversa)).isEqualTo("PRONTO");
        assertThat(((ItemRelatorioPronto) turno2.itens().get(0)).titulo()).isEqualTo("Ativação no evento");
        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), ((ItemRelatorioPronto) turno2.itens().get(0)).relatorioId());
        var paragrafos = relatorio.secoes().stream().flatMap(s -> s.blocos().stream()).filter(ParagrafoDto.class::isInstance)
                .map(ParagrafoDto.class::cast).toList();
        assertThat(paragrafos).extracting("id", "redigidoPor").containsExactly(
                org.assertj.core.groups.Tuple.tuple("b-resumo", "ATLAS"), org.assertj.core.groups.Tuple.tuple("b-publico", "ATLAS"));
        assertThat(paragrafos.get(0).texto()).isEqualTo("A marca Cliente Amarelo teve 17 de 19 aplicações comprovadas.");
        assertThat(paragrafos.get(1).texto()).isEqualTo("O espaço recebeu 1.200 visitantes, e 89,3% deixaram cadastro.");

        // o histórico terminou válido e o cartão foi respondido
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "tool");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE tool_call_id = ? AND status = 'RESPONDIDA'", idDoCartao)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.resultado IS NULL AND t.erro IS NULL", conversa)).isZero();
        assertThat(contar("SELECT count(DISTINCT tool_call_id) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id "
                + "WHERE r.conversa_id = ?", conversa)).isEqualTo(1);
    }

    @Test
    void semLacunaObrigatoriaOModeloEditaEFinalizaSemCartao() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao("b-resumo", RESUMO, "b-publico", PUBLICO)).chamada(FINALIZAR, "{}");

        ConversaDto turno = falar(conversa, "Monte o relatório da ativação.");

        assertThat(tipos(turno)).containsExactly("usuario", "relatorio_pronto");
        assertThat(statusDoRelatorio(conversa)).isEqualTo("PRONTO");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ?",
                conversa)).isZero();
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }

    @Test
    void conversaSemRelatorioEUmaConversaDeTexto() {
        long conversa = abrirConversa();
        modelo.texto("Posso montar o relatório de ativação da sua marca. Quer que eu comece?");

        ConversaDto turno = falar(conversa, "Oi, o que você faz?");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Posso montar o relatório de ativação da sua marca. Quer que eu comece?");
        assertThat(((ItemTexto) turno.itens().get(1)).porModelo()).isTrue();
        assertThat(nomesDasFerramentas(modelo.ultimoPedido())).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, INICIAR);
        assertThat(papeis(conversa)).containsExactly("user", "assistant");
    }

    // ---------------------------------------------------------------------
    // Pergunta sobre os números, sem relatório: parceria_consultar
    // ---------------------------------------------------------------------

    @Test
    void perguntaSobreNumerosSemRelatorioConsultaAParceriaERespondeComOsNumeros() {
        long conversa = abrirConversa();
        modelo.chamada(CONSULTAR, "{}")
                .passo(pedido -> {
                    // o modelo recebeu as referências da parceria, com o valor formatado, e responde só com marcadores
                    var resultado = pedido.mensagens().get(pedido.mensagens().size() - 1);
                    assertThat(resultado.papel()).isEqualTo("tool");
                    assertThat(resultado.ferramenta()).isEqualTo(CONSULTAR);
                    return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm("Aplicações de marca previstas: "
                            + "{{cons:aplicacoes.previstas}}; comprovadas no evento: {{cons:aplicacoes.comprovadas}}.", List.of(), null);
                });

        ConversaDto turno = falar(conversa, "Quantas aplicações de marca já foram comprovadas?");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Aplicações de marca previstas: 19; comprovadas no evento: 17.");
        assertThat(nomesDasFerramentas(modelo.pedidos().get(0))).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, INICIAR);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
        assertThat(modelo.sobraDoRoteiro()).isZero();

        // a resposta da ferramenta: os dados com ref, rótulo, valor formatado, unidade e origem; os que faltam, com o motivo
        Map<String, Object> consulta = json(mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst()
                .orElseThrow().conteudo());
        assertThat(consulta).containsEntry("abaFechada", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disponiveis = (List<Map<String, Object>>) consulta.get("dados");
        assertThat(disponiveis).filteredOn(d -> "cons:aplicacoes.comprovadas".equals(d.get("ref"))).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("valor", "17").containsEntry("rotulo", "Aplicações comprovadas")
                        .containsKey("origem").containsKey("unidade"));
        assertThat(disponiveis).extracting(d -> d.get("ref")).contains("parc:patrocinador", "cons:aplicacoes.previstas");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indisponiveis = (List<Map<String, Object>>) consulta.get("indisponiveis");
        assertThat(indisponiveis).extracting(d -> d.get("ref"))
                .contains("dado:ativacao.visitantes_total", "calc:taxa_cadastro", "calc:brindes_pct");

        // só leitura: nenhum relatório, e a trilha da chamada gravada
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isZero();
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.ferramenta = 'parceria_consultar' AND t.resultado IS NOT NULL", conversa)).isEqualTo(1);
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void consultaComOsCalculosFixosResolvidosPelaParceriaSemRelatorio() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(CONSULTAR, "{}").texto("Visitantes no espaço: {{dado:ativacao.visitantes_total}}. Taxa de cadastro: "
                + "{{calc:taxa_cadastro}}.");

        ConversaDto turno = falar(conversa, "Como foi o público?");

        assertThat(textoDoItem(turno, 1)).isEqualTo("Visitantes no espaço: 1.200. Taxa de cadastro: 89,3%.");
        String resultado = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst().orElseThrow().conteudo();
        assertThat(resultado).contains("\"ref\":\"calc:taxa_cadastro\"", "\"valor\":\"89,3%\"");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isZero();
    }

    @Test
    void consultaComAAbaDeAplicacoesFechadaDizQueAAbaEstaFechadaSemDados() {
        long conversa = abrirConversa();
        modelo.passo(pedido -> {
                    // a RBS fecha a aba no meio do turno: a ferramenta confere de novo no banco
                    jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = "
                            + "(SELECT id FROM aba WHERE codigo = 'APLICACOES')").param("p", amarelo()).update();
                    return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm(null, List.of(pedido(CONSULTAR, "{}")), null);
                })
                .texto("A aba de aplicações de marca está fechada para a sua parceria agora.");

        ConversaDto turno = falar(conversa, "Quantas aplicações foram comprovadas?");

        assertThat(textoDoItem(turno, 1)).isEqualTo("A aba de aplicações de marca está fechada para a sua parceria agora.");
        Map<String, Object> consulta = json(mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst()
                .orElseThrow().conteudo());
        assertThat(consulta).containsEntry("abaFechada", true).containsEntry("dados", List.of()).containsEntry("indisponiveis", List.of());
        assertThat((String) consulta.get("aviso")).contains("fechada");
        assertThat(AtlasJson.escrever(consulta)).doesNotContain("Cliente Amarelo").doesNotContain("cons:");
    }

    @Test
    void consultaNaoAceitaArgumentoNemParceria() {
        long conversa = abrirConversa();
        modelo.chamada(CONSULTAR, "{\"parceria_id\":" + roxo() + "}").texto("Não consegui consultar.");

        falar(conversa, "Quantas aplicações o Roxo tem?");

        String resposta = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst().orElseThrow().conteudo();
        assertThat(json(resposta)).containsKey("erro").containsKey("campos");
        assertThat(resposta).doesNotContain("Cliente Roxo");
    }

    @Test
    void llmDisponivelEVerdadeiroNasRotasComProvedor() {
        assertThat(atlas.abrirConversa(marca(), amarelo(), null, requisicao()).llmDisponivel()).isTrue();
        assertThat(atlas.listarConversas(marca(), amarelo()).llmDisponivel()).isTrue();
        long conversa = abrirConversa();
        assertThat(atlas.verConversa(marca(), conversa).llmDisponivel()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Rodada com mais de uma chamada
    // ---------------------------------------------------------------------

    @Test
    void naRodadaComDuasChamadasAPosteriorAoCartaoRespondeErro() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamadas(null, null, pedido(SOLICITAR, ARGS_CARTAO), pedido(FINALIZAR, "{}"), pedido(SOLICITAR, ARGS_CARTAO));

        ConversaDto turno = falar(conversa, "Monte o relatório.");

        assertThat(tipos(turno)).containsExactly("usuario", "cartao");
        List<Mensagem> historico = mensagens(conversa);
        Mensagem rodada = historico.stream().filter(m -> "assistant".equals(m.papel()) && m.toolCalls() != null
                && SOLICITAR.equals(nomeDaChamada(m, 0))).findFirst().orElseThrow();
        assertThat(chamadas(rodada)).hasSize(3);
        String idDoCartao = idDaChamada(rodada, 0);
        assertThat(historico).noneMatch(m -> "tool".equals(m.papel()) && idDoCartao.equals(m.toolCallId()));   // o cartão: pendente
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> erro = json(respostaDa(conversa, idDaChamada(rodada, i)).conteudo());
            assertThat((String) erro.get("erro")).as("chamada " + i).contains("Aguardando a resposta do cartão");
        }
        assertThat(statusDoRelatorio(conversa)).isEqualTo("AGUARDANDO_RESPOSTA");   // a finalizar NÃO rodou
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ?",
                conversa)).isEqualTo(1);   // e a segunda solicitar também não abriu cartão
        assertThat(modelo.pedidos()).hasSize(2);   // o turno parou: nada de nova ida ao modelo

        // depois de responder o cartão, o histórico remontado é válido: 3 pedidos, 2 respostas de erro e a do cartão
        modelo.texto("Tudo certo, faltam os textos.");
        responder(cartaoDe(turno).solicitacaoId(), "decline");
        assertThat(modelo.pedidos()).hasSize(3);
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).count()).isEqualTo(4);
    }

    // ---------------------------------------------------------------------
    // O limite de chamadas por turno
    // ---------------------------------------------------------------------

    @Test
    void depoisDeOitoChamadasDeFerramentaOTurnoParaEOContadorZeraNoTurnoSeguinte() {
        long conversa = abrirConversa();
        String edicaoInutil = edicao("b-inventado", "Texto.");
        modelo.chamada(INICIAR, ARGS_INICIAR);
        for (int i = 0; i < 7; i++) {
            modelo.chamada(EDITAR, edicaoInutil);   // 7 edições: com a abertura, são 8 chamadas executadas
        }
        modelo.chamada(EDITAR, edicaoInutil);       // a 9ª já passa do limite: erro, e o turno acaba

        ConversaDto turno = falar(conversa, "Faça de tudo.");

        assertThat(LoopDoAgente.LIMITE_DE_CHAMADAS_POR_TURNO).isEqualTo(8);
        assertThat(modelo.pedidos()).hasSize(9);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo(LoopDoAgente.FRASE_DO_LIMITE);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ?",
                conversa)).isEqualTo(9);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.erro IS NOT NULL", conversa)).isEqualTo(1);
        List<Mensagem> historico = mensagens(conversa);
        String ultimoPedido = idDaChamada(historico.stream().filter(m -> m.toolCalls() != null).reduce((a, b) -> b).orElseThrow(), 0);
        assertThat((String) json(respostaDa(conversa, ultimoPedido).conteudo()).get("erro")).isEqualTo(LoopDoAgente.ERRO_DO_LIMITE);
        assertThat(historico.get(historico.size() - 1).papel()).isEqualTo("assistant");
        assertThat(historico.get(historico.size() - 1).conteudo()).isEqualTo(LoopDoAgente.FRASE_DO_LIMITE);

        // o turno seguinte começa do zero
        modelo.chamada(EDITAR, edicaoInutil).texto("Pronto para o próximo passo.");
        ConversaDto seguinte = falar(conversa, "Tente de novo.");
        assertThat(tipos(seguinte)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(seguinte, 1)).isEqualTo("Pronto para o próximo passo.");
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    // ---------------------------------------------------------------------
    // Erro de ferramenta volta ao modelo
    // ---------------------------------------------------------------------

    @Test
    void erroDeFerramentaVoltaAoModeloComoMensagemDeFerramentaNuncaComo500() {
        long conversa = abrirConversa();
        modelo.chamada(FINALIZAR, "{}")                                            // ainda não há relatório
                .chamada(INICIAR, "{\"modelo\":\"INVENTADO\"}")                   // modelo desconhecido: 422 do serviço
                .chamada(INICIAR, "{")                                             // JSON quebrado
                .chamada(INICIAR, "")                                              // sem argumento nenhum
                .chamada(INICIAR, "{\"modelo\":42,\"objetivo\":[]}")              // tipos errados
                .chamada("relatorio_calcular", "{\"script\":\"1+1\"}")            // ferramenta que não existe (calcular é do P1)
                .chamada(INICIAR, ARGS_INICIAR)                                    // enfim, certo
                .chamada(INICIAR, ARGS_INICIAR)                                    // já existe relatório em montagem: 409 do serviço
                .texto("Consegui iniciar o relatório.");

        ConversaDto turno = falar(conversa, "Tente tudo.");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Consegui iniciar o relatório.");
        List<Mensagem> respostas = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).toList();
        assertThat(respostas).hasSize(8);
        assertThat((String) json(respostas.get(0).conteudo()).get("erro")).contains("Ainda não há relatório").contains("relatorio_iniciar");
        Map<String, Object> modeloInvalido = json(respostas.get(1).conteudo());
        assertThat((Map<String, String>) modeloInvalido.get("campos")).containsKey("modelo");
        assertThat((String) json(respostas.get(2).conteudo()).get("erro")).contains("não são um objeto JSON válido");
        assertThat((Map<String, String>) json(respostas.get(3).conteudo()).get("campos")).containsKey("modelo");   // "" vira {} e falta o modelo
        assertThat((Map<String, String>) json(respostas.get(4).conteudo()).get("campos")).containsKeys("modelo", "objetivo");
        assertThat((String) json(respostas.get(5).conteudo()).get("erro")).contains("Não existe a ferramenta relatorio_calcular");
        assertThat(json(respostas.get(6).conteudo())).containsKey("relatorioId");
        assertThat((String) json(respostas.get(7).conteudo()).get("erro")).contains("Já existe um relatório sendo montado");
        // as falhas ficam na trilha das ferramentas, com o motivo
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.erro IS NOT NULL", conversa)).isEqualTo(7);
        // o argumento que não é JSON entrou na trilha como texto, e o histórico continua válido
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.argumentos = to_jsonb(CAST('{' AS text))", conversa)).isEqualTo(1);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
        assertThat(statusDoRelatorio(conversa)).isEqualTo("MONTANDO");
    }

    @Test
    void argumentoForaDoEsquemaERecusadoEOContextoNaoMuda() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, "{\"modelo\":\"ATIVACAO_EVENTO\",\"parceria_id\":" + roxo() + ",\"usuario\":\"outro\"}")
                .texto("Não consegui.");

        falar(conversa, "Monte para a parceria de outro.");

        Map<String, Object> erro = json(mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst().orElseThrow().conteudo());
        assertThat((Map<String, String>) erro.get("campos")).containsOnlyKeys("parceria_id", "usuario");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isZero();
    }

    @Test
    void cartaoComCampoDeOutraAbaOuRepetidoVoltaAoModelo() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamada(SOLICITAR, "{\"mensagem\":\"Informe.\",\"campos\":[\"ativacao.visitantes_total\",\"ativacao.visitantes_total\"]}")
                .chamada(SOLICITAR, "{\"mensagem\":\"Informe.\",\"campos\":[\"ativacao.inventado\"]}")
                .chamada(SOLICITAR, "{\"mensagem\":\"Informe.\",\"campos\":[]}")
                .chamada(SOLICITAR, "{\"mensagem\":\"Informe.\",\"campos\":\"ativacao.visitantes_total\"}")
                .chamada(SOLICITAR, ARGS_CARTAO);

        ConversaDto turno = falar(conversa, "Monte.");

        assertThat(tipos(turno)).containsExactly("usuario", "cartao");
        List<Mensagem> respostas = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).toList();
        assertThat(respostas).hasSize(5);   // iniciar + 4 erros; o cartão certo ficou pendente
        for (int i = 1; i <= 4; i++) {
            assertThat(json(respostas.get(i).conteudo())).as("resposta " + i).containsKey("erro");
        }
        assertThat(statusDoRelatorio(conversa)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void depoisDeTresCartoesOModeloNaoRecebeMaisASolicitacaoDeCampos() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, ARGS_CARTAO);
        ItemCartao cartao = cartaoDe(falar(conversa, "Monte o relatório."));
        for (int i = 0; i < 2; i++) {
            modelo.chamada(SOLICITAR, ARGS_CARTAO);
            cartao = cartaoDe(responder(cartao.solicitacaoId(), "cancel"));
        }
        modelo.texto("Combinado.");
        responder(cartao.solicitacaoId(), "cancel");

        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ?",
                conversa)).isEqualTo(3);
        List<List<String>> ofertas = modelo.pedidos().stream().map(ProvedorRoteirizado::nomesDasFerramentas).toList();
        assertThat(ofertas).hasSize(5);
        assertThat(ofertas.get(1)).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);
        assertThat(ofertas.get(2)).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);
        assertThat(ofertas.get(3)).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);
        assertThat(ofertas.get(4)).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, EDITAR, FINALIZAR);   // o terceiro cartão já foi: o limite do plano
    }

    @Test
    void editarComArgumentosMalFormadosVoltaAoModeloSemMexerNoRelatorio() {
        informarTudo();
        long conversa = abrirConversa();
        String onzeOperacoes = AtlasJson.escrever(Map.of("operacoes", java.util.Collections.nCopies(11,
                Map.of("op", "definir_texto", "bloco", "b-resumo", "texto", "x"))));
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamada(EDITAR, "{\"operacoes\":\"b-resumo\"}")
                .chamada(EDITAR, "{\"operacoes\":[]}")
                .chamada(EDITAR, onzeOperacoes)
                .chamada(EDITAR, "{\"operacoes\":[{\"op\":\"apagar_bloco\",\"bloco\":\"b-resumo\",\"texto\":\"x\"}]}")
                .chamada(EDITAR, "{\"operacoes\":[{\"op\":\"definir_texto\",\"texto\":\"x\"}]}")
                .chamada(EDITAR, "{\"operacoes\":[{\"op\":\"definir_texto\",\"bloco\":\"b-resumo\",\"texto\":7}]}")
                .chamada(EDITAR, "{\"operacoes\":[{\"op\":\"definir_texto\",\"bloco\":\"b-resumo\",\"texto\":\"x\",\"parceria_id\":1}]}")
                .chamada(EDITAR, "{\"operacoes\":[\"definir_texto\"],\"relatorio_id\":1}");

        // são 9 chamadas: a 9ª passa do limite do turno, e o turno acaba
        ConversaDto primeiro = falar(conversa, "Monte o relatório.");
        assertThat(textoDoItem(primeiro, 1)).isEqualTo(LoopDoAgente.FRASE_DO_LIMITE);
        modelo.limpar();
        modelo.chamada(FINALIZAR, "{\"relatorio_id\":99}").chamada(FINALIZAR, "{}").texto("Faltam os textos.");
        falar(conversa, "Continue.");

        List<Mensagem> respostas = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).toList();
        assertThat((Map<String, String>) json(respostas.get(1).conteudo()).get("campos")).containsKey("operacoes");
        assertThat((Map<String, String>) json(respostas.get(2).conteudo()).get("campos")).containsKey("operacoes");
        assertThat((Map<String, String>) json(respostas.get(3).conteudo()).get("campos")).containsKey("operacoes");   // 11 > 10
        assertThat((Map<String, String>) json(respostas.get(4).conteudo()).get("campos")).containsKey("operacoes[0].op");
        assertThat((Map<String, String>) json(respostas.get(5).conteudo()).get("campos")).containsKey("operacoes[0].bloco");
        assertThat((Map<String, String>) json(respostas.get(6).conteudo()).get("campos")).containsKey("operacoes[0].texto");
        assertThat((Map<String, String>) json(respostas.get(7).conteudo()).get("campos")).containsKey("operacoes[0].parceria_id");
        assertThat((String) json(respostas.get(8).conteudo()).get("erro")).isEqualTo(LoopDoAgente.ERRO_DO_LIMITE);
        // o relatório não mudou: nenhum bloco ganhou texto
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ? AND versao_especificacao = 1 AND status = 'MONTANDO'",
                conversa)).isEqualTo(1);
        // finalizar com argumento é recusado; sem argumento, o serviço diz que faltam os textos
        assertThat((Map<String, String>) json(respostas.get(respostas.size() - 2).conteudo()).get("campos")).containsKey("relatorio_id");
        assertThat((String) json(respostas.get(respostas.size() - 1).conteudo()).get("erro"))
                .contains("Faltam os textos dos blocos: b-resumo, b-publico");
    }

    // ---------------------------------------------------------------------
    // Mensagem nova com o cartão aberto
    // ---------------------------------------------------------------------

    @Test
    void mensagemNovaComCartaoAbertoFechaOCartaoComCancelAntesDeEntrar() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, ARGS_CARTAO);
        ConversaDto aberta = falar(conversa, "Monte o relatório.");
        ItemCartao cartao = cartaoDe(aberta);
        modelo.texto("Sem problema, o relatório fica como está até você voltar.");

        ConversaDto turno = falar(conversa, "Na verdade, outra pergunta: o que é o Atlas?");

        assertThat(tipos(turno)).containsExactly("aviso", "usuario", "texto");
        assertThat(((ItemAviso) turno.itens().get(0)).texto()).isEqualTo("Cartão fechado. Quando tiver os números, peça para continuar.");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE id = ? AND status = 'CANCELADA'", cartao.solicitacaoId())).isEqualTo(1);
        assertThat(statusDoRelatorio(conversa)).isEqualTo("MONTANDO");
        List<Mensagem> historico = mensagens(conversa);
        // a resposta cancel do cartão vem ANTES da mensagem nova, com o mesmo tool_call_id
        int posicaoDoCancel = -1;
        int posicaoDaNova = -1;
        for (int i = 0; i < historico.size(); i++) {
            if ("tool".equals(historico.get(i).papel()) && historico.get(i).conteudo().contains("\"acao\":\"cancel\"")) {
                posicaoDoCancel = i;
            }
            if ("user".equals(historico.get(i).papel()) && historico.get(i).conteudo().startsWith("Na verdade")) {
                posicaoDaNova = i;
            }
        }
        assertThat(posicaoDoCancel).isPositive().isLessThan(posicaoDaNova);
        assertThat(historico.get(posicaoDoCancel).toolCallId()).isEqualTo(idDaChamada(historico.get(posicaoDoCancel - 1), 0));
        assertThat(json(historico.get(posicaoDoCancel).conteudo())).containsEntry("acao", "cancel").containsEntry("campos", List.of());
        // o histórico que o modelo recebeu é válido, com o cancel no lugar da resposta pendente
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.resultado IS NULL AND t.erro IS NULL", conversa)).isZero();
        // o relatório que estava montando continua o mesmo, e o modelo pode pedir de novo (nenhuma ferramenta perdida)
        assertThat(nomesDasFerramentas(modelo.ultimoPedido())).containsExactly(CONSULTAR, BUSCAR, LISTAR_ENTREGAS, CONSULTAR_COTA, CONSULTAR_PRAZOS, CONTAR_DIAS, CALCULAR, PEDIDO_DE_RELATORIO, GERAR_MODELO, SOLICITAR, EDITAR, FINALIZAR);
    }

    @Test
    void cancelDoCartaoPelaRota46RetomaOModeloQueRecebeAAcao() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, ARGS_CARTAO);
        ItemCartao cartao = cartaoDe(falar(conversa, "Monte o relatório."));
        modelo.texto("Tudo bem, quando quiser a gente retoma.");

        ConversaDto turno = responder(cartao.solicitacaoId(), "cancel");

        assertThat(tipos(turno)).containsExactly("aviso", "texto");
        // com o modelo ligado, o aviso manda continuar pelo chat (sem modelo, é "Use Montar relatório para continuar.")
        assertThat(((ItemAviso) turno.itens().get(0)).texto()).isEqualTo("Cartão fechado. Quando tiver os números, peça para continuar.");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Tudo bem, quando quiser a gente retoma.");
        assertThat(json(modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1).conteudo()))
                .containsEntry("acao", "cancel");
        assertThat(statusDoRelatorio(conversa)).isEqualTo("MONTANDO");
    }

    @Test
    void cartaoDoBotaoRespondidoComModeloContinuaPeloModelo() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), new AbrirConversaRequisicao("MONTAR_RELATORIO", "ATIVACAO_EVENTO"), requisicao())
                .conversaId();
        ItemCartao cartao = cartaoDe(atlas.verConversa(marca(), conversa));
        modelo.chamada(EDITAR, edicao("b-resumo", RESUMO, "b-publico", PUBLICO)).chamada(FINALIZAR, "{}");

        ConversaDto turno = responder(cartao.solicitacaoId(), "accept", VISITANTES, 1200L, CADASTROS, 1072L);

        assertThat(tipos(turno)).containsExactly("relatorio_pronto");
        assertThat(statusDoRelatorio(conversa)).isEqualTo("PRONTO");
        // o histórico do plano B (ids botao_) é lido pelo modelo como qualquer outro, e o relatório sai com o parágrafo do ATLAS
        assertThat(modelo.pedidos().get(0).mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.chamadaId()).toList())
                .allMatch(id -> id.startsWith("botao_"));
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), ((ItemRelatorioPronto) turno.itens().get(0)).relatorioId());
        assertThat(relatorio.secoes().stream().flatMap(s -> s.blocos().stream()).filter(ParagrafoDto.class::isInstance)
                .map(ParagrafoDto.class::cast).map(ParagrafoDto::redigidoPor)).containsOnly("ATLAS");
    }

    // ---------------------------------------------------------------------
    // Número no texto do relatório
    // ---------------------------------------------------------------------

    @Test
    void definirTextoComAlgarismoERecusadoDepoisOBlocoFicaComAFraseModelo() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamada(EDITAR, edicao("b-resumo", "A marca teve 17 aplicações comprovadas.", "b-publico", PUBLICO))
                .chamada(EDITAR, edicao("b-resumo", "Foram dezessete, ou seja 17 de 19."))
                .chamada(FINALIZAR, "{}");

        ConversaDto turno = falar(conversa, "Monte o relatório.");

        assertThat(tipos(turno)).containsExactly("usuario", "relatorio_pronto");
        List<Mensagem> respostas = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).toList();
        Map<String, Object> primeira = json(respostas.get(1).conteudo());
        assertThat((List<Map<String, Object>>) primeira.get("operacoes")).extracting(o -> o.get("resultado")).containsExactly("recusada", "aplicada");
        assertThat((List<String>) primeira.get("blocosSemTexto")).containsExactly("b-resumo");
        assertThat(respostas.get(1).conteudo()).contains("algarismo");
        Map<String, Object> segunda = json(respostas.get(2).conteudo());
        assertThat((List<Map<String, Object>>) segunda.get("operacoes")).extracting(o -> o.get("resultado")).containsExactly("frase_modelo");

        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), ((ItemRelatorioPronto) turno.itens().get(1)).relatorioId());
        var paragrafos = relatorio.secoes().stream().flatMap(s -> s.blocos().stream()).filter(ParagrafoDto.class::isInstance)
                .map(ParagrafoDto.class::cast).toList();
        assertThat(paragrafos).extracting("id", "redigidoPor").containsExactly(
                org.assertj.core.groups.Tuple.tuple("b-resumo", "MODELO"), org.assertj.core.groups.Tuple.tuple("b-publico", "ATLAS"));
        assertThat(paragrafos.get(0).texto()).isEqualTo("Aplicações de marca de Cliente Amarelo previstas no plano: 19; comprovadas no evento: 17.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void definirTextoComNumeroOuDataPorExtensoERecusadoNoRelatorioEDepoisOBlocoFicaComAFraseModelo() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamada(EDITAR, edicao("b-resumo", "A marca teve dezessete aplicações comprovadas.", "b-publico", PUBLICO))
                .chamada(EDITAR, edicao("b-resumo", "A ativação foi no primeiro de dezembro, com vinte e três brindes."))
                .chamada(FINALIZAR, "{}");

        ConversaDto turno = falar(conversa, "Monte o relatório.");

        assertThat(tipos(turno)).containsExactly("usuario", "relatorio_pronto");
        List<Mensagem> respostas = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).toList();
        Map<String, Object> primeira = json(respostas.get(1).conteudo());
        assertThat((List<Map<String, Object>>) primeira.get("operacoes")).extracting(o -> o.get("resultado")).containsExactly("recusada", "aplicada");
        assertThat(respostas.get(1).conteudo()).contains("por extenso").contains("dezessete");
        // a segunda recusa do mesmo bloco põe a frase-modelo, e o número por extenso não entra no relatório
        Map<String, Object> segunda = json(respostas.get(2).conteudo());
        assertThat((List<Map<String, Object>>) segunda.get("operacoes")).extracting(o -> o.get("resultado")).containsExactly("frase_modelo");
        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), ((ItemRelatorioPronto) turno.itens().get(1)).relatorioId());
        var paragrafos = relatorio.secoes().stream().flatMap(s -> s.blocos().stream()).filter(ParagrafoDto.class::isInstance)
                .map(ParagrafoDto.class::cast).toList();
        assertThat(paragrafos).extracting("id", "redigidoPor").containsExactly(
                org.assertj.core.groups.Tuple.tuple("b-resumo", "MODELO"), org.assertj.core.groups.Tuple.tuple("b-publico", "ATLAS"));
        assertThat(paragrafos).extracting("texto").noneMatch(t -> t.toString().contains("dezessete") || t.toString().contains("vinte e três"));
    }

    @Test
    void referenciaInventadaNoTextoDoRelatorioERecusada() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR)
                .chamada(EDITAR, edicao("b-resumo", "Veja {{dado:ativacao.inventado}} e {{cons:aplicacoes.inexistente}}."))
                .texto("Vou tentar de novo depois.");

        falar(conversa, "Monte.");

        Mensagem resposta = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).reduce((a, b) -> b).orElseThrow();
        assertThat(resposta.conteudo()).contains("recusada").contains("não existe nesta parceria");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ? AND especificacao::text LIKE '%inventado%'", conversa)).isZero();
    }

    // ---------------------------------------------------------------------
    // Número no texto do chat
    // ---------------------------------------------------------------------

    @Test
    void textoDoChatComAlgarismoPedeUmaNovaRedacaoEUsaASegunda() {
        long conversa = abrirConversa();
        modelo.passo(pedido -> new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm(
                        "Deu 7 por visitante.", List.of(), null))
                .passo(pedido -> {
                    // a nova redação: o pedido leva a resposta recusada e o motivo, que não entram no histórico gravado
                    var mensagens = pedido.mensagens();
                    assertThat(mensagens.get(mensagens.size() - 2).papel()).isEqualTo("assistant");
                    assertThat(mensagens.get(mensagens.size() - 2).conteudo()).isEqualTo("Deu 7 por visitante.");
                    assertThat(mensagens.get(mensagens.size() - 1).papel()).isEqualTo("user");
                    assertThat(mensagens.get(mensagens.size() - 1).conteudo()).contains("recusada").contains("\"7\"").contains("não tem origem")
                            .contains("citação [T#]").contains("Não encontrei isso nos documentos do portal.")
                            .doesNotContain("sem nenhum algarismo");   // o pedido deixou de mandar escrever sem número
                    return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm(
                            "Aplicações previstas: {{cons:aplicacoes.previstas}}; comprovadas: {{cons:aplicacoes.comprovadas}}.", List.of(), null);
                });

        ConversaDto turno = falar(conversa, "Quanto deu por visitante?");

        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Aplicações previstas: 19; comprovadas: 17.");   // o servidor resolve os marcadores
        List<Mensagem> historico = mensagens(conversa);
        assertThat(historico).extracting(Mensagem::papel).containsExactly("user", "assistant");
        assertThat(historico.get(1).conteudo()).contains("{{cons:aplicacoes.comprovadas}}").doesNotContain("Deu 7");   // grava com os marcadores
        exigirHistoricoValido(modelo.ultimoPedido());
    }

    @Test
    void seASegundaRedacaoTambemTemNumeroOChatFicaComAFraseFixa() {
        long conversa = abrirConversa();
        modelo.texto("Deu 7 por visitante.").texto("Cerca de 7,5 por visitante.");

        ConversaDto turno = falar(conversa, "Quanto deu por visitante?");

        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Não consegui responder isso agora. Pergunte de outro jeito ou monte o relatório da ativação.");
        assertThat(LoopDoAgente.FRASE_FIXA).isEqualTo(textoDoItem(turno, 1));
        assertThat(((ItemTexto) turno.itens().get(1)).porModelo()).isFalse();   // a troca é do servidor, não do modelo
        assertThat(mensagens(conversa)).extracting(Mensagem::conteudo).doesNotContain("Deu 7 por visitante.", "Cerca de 7,5 por visitante.");
        assertThat(turno.itens()).noneMatch(i -> i instanceof ItemTexto t && t.texto().matches(".*\\d.*"));
    }

    @Test
    void chatComMarcadorInventadoTambemPedeNovaRedacao() {
        long conversa = abrirConversa();
        modelo.texto("Veja {{dado:ativacao.inventado}}.").texto("Posso montar o relatório de ativação.");

        ConversaDto turno = falar(conversa, "Me dê os dados.");

        assertThat(textoDoItem(turno, 1)).isEqualTo("Posso montar o relatório de ativação.");
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1).conteudo())
                .contains("não existe nesta parceria");
    }

    @Test
    void chatComChaveCruaPedeNovaRedacaoEDepoisFicaComAFraseFixa() {
        long conversa = abrirConversa();
        modelo.texto("O total está em dado:ativacao.visitantes_total.")
                .texto("Esse dado fica no relatório, com o marcador próprio.");

        ConversaDto turno = falar(conversa, "Quantos visitantes?");

        assertThat(modelo.pedidos()).hasSize(2);
        var pedidoDeNovaRedacao = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        assertThat(pedidoDeNovaRedacao.conteudo()).contains("termo interno").contains("dado:ativacao.visitantes_total");
        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo(LoopDoAgente.FRASE_FIXA);
        assertThat(mensagens(conversa)).extracting(Mensagem::conteudo).noneMatch(c -> c != null && (c.contains("dado:") || c.contains("marcador")));
        exigirHistoricoValido(modelo.ultimoPedido());
    }

    @Test
    void chatComMarcadorSemValorPedeNovaRedacaoEUsaASegunda() {
        long conversa = abrirConversa();
        modelo.texto("Visitantes no espaço: {{dado:ativacao.visitantes_total}}.")
                .texto("O número de visitantes ainda não foi informado. Posso pedir esse número para o relatório.");

        ConversaDto turno = falar(conversa, "Quantos visitantes?");

        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1).conteudo())
                .contains("não tem valor agora");
        assertThat(textoDoItem(turno, 1)).isEqualTo("O número de visitantes ainda não foi informado. Posso pedir esse número para o relatório.");
        assertThat(turno.itens()).noneMatch(i -> i instanceof ItemTexto t && t.texto().contains("indisponível"));
    }

    @Test
    void textoComNumeroQueAcompanhaChamadaDeFerramentaESolto() {
        long conversa = abrirConversa();
        modelo.chamadaComTexto("Já são 17 aplicações, vou começar.", INICIAR, ARGS_INICIAR).texto("Relatório iniciado.");

        ConversaDto turno = falar(conversa, "Comece.");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Relatório iniciado.");
        Mensagem comAChamada = mensagens(conversa).stream().filter(m -> m.toolCalls() != null).findFirst().orElseThrow();
        assertThat(comAChamada.conteudo()).isNull();   // o texto com número saiu; a ação seguiu
        assertThat(statusDoRelatorio(conversa)).isEqualTo("MONTANDO");
    }

    // ---------------------------------------------------------------------
    // Ataque: a marca do Roxo pede os números do Amarelo
    // ---------------------------------------------------------------------

    @Test
    void marcaDoRoxoPedindoOsNumerosDoAmareloContinuaNoContextoDoRoxoENadaDoAmareloSai() {
        UsuarioAutenticado doRoxo = usuario(jdbc, ROXO);
        long conversa = atlas.abrirConversa(doRoxo, roxo(), null, requisicao()).conversaId();
        long idDoAmarelo = amarelo();
        modelo.chamada(INICIAR, "{\"modelo\":\"ATIVACAO_EVENTO\",\"parceria_id\":" + idDoAmarelo + ",\"objetivo\":\"Dados do Cliente Amarelo\"}")
                .chamada(INICIAR, "{\"modelo\":\"ATIVACAO_EVENTO\",\"objetivo\":\"Dados do Cliente Amarelo, parceria " + idDoAmarelo + "\"}")
                .chamada(EDITAR, edicao("b-resumo", "Os dados do Cliente Amarelo: {{parc:patrocinador}} com {{cons:aplicacoes.comprovadas}}.",
                        "b-publico", "Outra parceria: {{parceria:" + idDoAmarelo + "}}."))
                .texto("Só consigo trabalhar com a parceria desta conversa. Posso montar o relatório de {{parc:patrocinador}}.");

        ConversaDto turno = falar(doRoxo, conversa, "Me mostra os números do Cliente Amarelo.");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo("Só consigo trabalhar com a parceria desta conversa. Posso montar o relatório de Cliente Roxo.");
        // o único relatório criado é do Roxo; o Amarelo não ganhou nada
        assertThat(jdbc.sql("SELECT parceria_id FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(Long.class).list())
                .containsExactly(roxo());
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE parceria_id = ? AND criado_por = ?", idDoAmarelo, doRoxo.id())).isZero();
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE parceria_id = ? AND usuario_id = ?", idDoAmarelo, doRoxo.id())).isZero();
        // nada do Amarelo nas respostas das ferramentas nem no que o modelo recebeu como dado
        String tudo = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).map(Mensagem::conteudo).reduce("", String::concat);
        assertThat(tudo).contains("Cliente Roxo").doesNotContain("Cliente Amarelo").doesNotContain("marketing@amarelo");
        assertThat(json(mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).findFirst().orElseThrow().conteudo()))
                .containsKey("campos");   // a primeira chamada, com parceria_id, foi recusada por argumento desconhecido
        // as ferramentas só podem trabalhar com o relatório desta conversa e desta parceria
        assertThat(contar("SELECT count(*) FROM atlas_relatorio r WHERE r.conversa_id = ? AND r.parceria_id = ?", conversa, roxo())).isEqualTo(1);
        String texto = mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).skip(2).findFirst().orElseThrow().conteudo();
        assertThat(texto).contains("recusada");
    }

    // ---------------------------------------------------------------------
    // reasoning_content
    // ---------------------------------------------------------------------

    @Test
    void oReasoningContentGravadoVoltaNaRetomadaDoMesmoAssistente() {
        long conversa = abrirConversa();
        modelo.chamadaComRaciocinio("Primeiro preciso criar o relatório.", INICIAR, ARGS_INICIAR)
                .chamadaComRaciocinio("Faltam dois números que só a marca sabe.", SOLICITAR, ARGS_CARTAO);
        ItemCartao cartao = cartaoDe(falar(conversa, "Monte o relatório."));

        List<Mensagem> historico = mensagens(conversa);
        assertThat(historico.stream().filter(m -> "assistant".equals(m.papel())).map(Mensagem::reasoningContent).toList())
                .containsExactly("Primeiro preciso criar o relatório.", "Faltam dois números que só a marca sabe.");
        assertThat(historico.stream().filter(m -> !"assistant".equals(m.papel())).map(Mensagem::reasoningContent)).containsOnlyNulls();

        // na retomada o histórico é remontado do banco, e o raciocínio volta nas mensagens do assistente
        modelo.texto("Combinado.");
        responder(cartao.solicitacaoId(), "decline");
        var assistentes = modelo.ultimoPedido().mensagens().stream().filter(m -> "assistant".equals(m.papel())).toList();
        assertThat(assistentes).extracting("raciocinio").containsExactly("Primeiro preciso criar o relatório.",
                "Faltam dois números que só a marca sabe.");
        assertThat(assistentes.get(1).chamadas()).extracting("nome").containsExactly(SOLICITAR);
    }

    // ---------------------------------------------------------------------
    // Ids, falhas do modelo e o turno
    // ---------------------------------------------------------------------

    @Test
    void osIdsDePedidoSaoNossosEUnicosNaConversaMesmoQueOModeloRepita() {
        informarTudo();
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao("b-resumo", RESUMO)).chamada(EDITAR, edicao("b-publico", PUBLICO))
                .chamada(FINALIZAR, "{}");   // todas com o id "id-do-modelo"

        falar(conversa, "Monte.");

        List<String> ids = mensagens(conversa).stream().filter(m -> m.toolCalls() != null).map(m -> idDaChamada(m, 0)).toList();
        assertThat(ids).hasSize(4).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).startsWith("call_").hasSize(37));
        assertThat(mensagens(conversa).stream().filter(m -> "tool".equals(m.papel())).map(Mensagem::toolCallId)).containsExactlyElementsOf(ids);
    }

    @Test
    void seOModeloFalharOHistoricoFicaValidoCom200EUmaFraseFixa() {
        long conversa = abrirConversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).falha();

        ConversaDto turno = falar(conversa, "Monte o relatório.");

        assertThat(tipos(turno)).containsExactly("usuario", "texto");
        assertThat(textoDoItem(turno, 1)).isEqualTo(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(statusDoRelatorio(conversa)).isEqualTo("MONTANDO");   // o que já foi feito está salvo
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "assistant");
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NULL", conversa)).isEqualTo(1);

        // a conversa segue: a mensagem seguinte vê o histórico válido
        modelo.texto("Voltei.");
        falar(conversa, "Tente de novo.");
        exigirHistoricoValido(modelo.ultimoPedido());
    }

    @Test
    void respostaVaziaDoModeloViraUmaFraseFixa() {
        long conversa = abrirConversa();
        modelo.vazio();

        ConversaDto turno = falar(conversa, "Oi.");

        assertThat(textoDoItem(turno, 1)).isEqualTo(LoopDoAgente.FRASE_SEM_RESPOSTA);
    }

    @Test
    void turnoEmAndamentoDa409SemGravarNada() {
        long conversa = abrirConversa();
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();

        assertThatThrownBy(() -> falar(conversa, "Oi.")).isInstanceOf(ConflitoException.class)
                .hasMessage("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");

        assertThat(mensagens(conversa)).isEmpty();
        assertThat(modelo.pedidos()).isEmpty();
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NOT NULL", conversa)).isEqualTo(1);   // não limpou o do outro
    }

    @Test
    void oTurnoSolta_seAFerramentaOuOModeloQuebrar() {
        long conversa = abrirConversa();
        modelo.passo(pedido -> {
            throw new IllegalStateException("bug do roteiro");
        });

        ConversaDto turno = falar(conversa, "Oi.");

        assertThat(textoDoItem(turno, 1)).isEqualTo(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NULL", conversa)).isEqualTo(1);
    }

    @Test
    void idsEItensNaoVazamParaOPromptNemParaOModelo() {
        long conversa = abrirConversa();
        modelo.texto("Olá!");

        falar(conversa, "Oi.");

        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        assertThat(prompt).doesNotContain(marca().email()).doesNotContain("parceria_id");
        // o prompt é o do modo (chat com o relatório ligado, e o fim do relatório) com a data de hoje e o nome da marca, e mais nada:
        // id de parceria, de conversa ou de usuário nunca entra
        assertThat(prompt).isEqualTo(ApoioDosPrompts.esperado(true, "Cliente Amarelo"));
    }

    @Test
    void oPromptLigadoTemARotaDoRelatorioEAsDuasRegrasDeNumeroSeparadas() {
        long conversa = abrirConversa();
        modelo.texto("Olá!");

        falar(conversa, "Oi.");

        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        // a rota: relatório da ativação monta aqui; qualquer outro relatório vai para a resposta direta
        assertThat(prompt).contains("Há dois caminhos para um pedido de relatório")
                .contains("chame relatorio_iniciar com o modelo ATIVACAO_EVENTO")
                .contains("Qualquer outro relatório (pós-evento, de mídia, oficial, consolidado): chame pedido_de_relatorio")
                .contains("Se não der para saber qual é, pergunte em uma frase.")
                .contains("chame gerar_modelo com o tipo");
        assertThat(prompt.indexOf("relatorio_iniciar")).isLessThan(prompt.indexOf("pedido_de_relatorio"));
        // a regra do número do chat: copiado da fonte, com a citação
        assertThat(prompt).contains("Números e datas que vêm de documento: copie exatamente como estão no trecho")
                .contains("ponha a citação na mesma frase");
        // a regra do número do texto do relatório: nenhum, só marcador; e a diz que não vale na conversa
        assertThat(prompt).contains("No texto de um bloco (relatorio_editar) nunca escreva número nem data, nem com algarismo nem por extenso")
                .contains("Esta regra vale só para o texto do relatório: na conversa, um número de documento vai copiado da fonte com a citação");
        // as duas regras estão em linhas diferentes
        assertThat(prompt.lines().filter(l -> l.contains("copie exatamente como estão no trecho")).findFirst().orElseThrow())
                .doesNotContain("relatorio_editar");
        assertThat(prompt.lines().filter(l -> l.contains("nunca escreva número nem data")).findFirst().orElseThrow())
                .doesNotContain("copie exatamente como estão no trecho");
        // o antigo "nunca escreve um número, nem na conversa" saiu
        assertThat(prompt).doesNotContain("nunca escreve um número").doesNotContain("nem na conversa, nem nos textos do relatório");
    }

    @Test
    void oPromptLigadoFicaAbaixoDeTrezeMilCaracteresESemMarcadorQueSobrou() {
        long conversa = abrirConversa();
        modelo.texto("Olá!");

        falar(conversa, "Oi.");

        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        assertThat(prompt.length()).as("o prompt do chat com o relatório ligado tem de ficar curto").isLessThan(13000);
        assertThat(ApoioDosPrompts.MARCADOR_QUE_SOBROU.matcher(prompt).find()).as("sobrou um marcador @...@ no prompt").isFalse();
    }

    @Test
    void oPromptEUmArquivoTxtVersionadoEPermitidoPeloGit() {
        for (String nome : List.of("chat", "relatorio", "relatorio-ligado", "relatorio-desligado")) {
            var recurso = new org.springframework.core.io.ClassPathResource("atlas/prompts/" + nome + ".txt");
            assertThat(recurso.exists()).as(nome).isTrue();
            assertThat(new org.springframework.core.io.ClassPathResource("atlas/prompts/" + nome + ".md").exists()).as(nome + ".md").isFalse();
        }
        String relatorio = ApoioDosPrompts.arquivo("relatorio");
        assertThat(relatorio).contains("relatorio_iniciar", "relatorio_finalizar", "relatorio_editar", "relatorio_solicitar_campos");
        assertThat(ApoioDosPrompts.arquivo("chat")).contains(ApoioDosPrompts.MARCADOR, "@MARCA@", "@DATA@");
    }
}
