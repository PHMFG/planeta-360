package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AGENCIA_DO_AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.DISPONIVEIS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.DISTRIBUIDOS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
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
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.HistoricoDaConversa;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.AvisoIndisponivelDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.BlocoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FontesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ItemKpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiGradeDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.ServicoIndisponivelException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O plano B de ponta a ponta, chamado pelo service como o controller o chama (a sessão HTTP é testada em
 * AtlasControllerTest): o botão abre a conversa e o cartão, a resposta grava os dados com origem ATLAS e entrega o relatório
 * pronto; recusar deixa os números indisponíveis (nunca zero); cancelar volta a montar o mesmo relatório. O histórico é
 * conferido no formato de chat completions que o modelo de linguagem vai usar. Cada teste é desfeito no fim (@Transactional).
 * O Amarelo de 2027 tem dois usuários na amostra (marca e agência), o que prova que a conversa é de quem a abriu.
 */
@TesteDeIntegracao
@Transactional
class AtlasServiceTest {

    private static final AbrirConversaRequisicao BOTAO = new AbrirConversaRequisicao("MONTAR_RELATORIO", "ATIVACAO_EVENTO");
    private static final MensagemRequisicao BOTAO_NA_CONVERSA = new MensagemRequisicao(null, "MONTAR_RELATORIO", "ATIVACAO_EVENTO");

    @Autowired
    AtlasService atlas;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HistoricoDaConversa historico;

    @Autowired
    AtlasContextoFabrica fabrica;

    record Mensagem(String papel, String conteudo, String toolCalls, String toolCallId) {
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    ConversaDto abrirComOBotao() {
        return atlas.abrirConversa(marca(), amarelo(), BOTAO, requisicao());
    }

    ItemCartao cartaoDe(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
    }

    ConversaDto responder(long solicitacaoId, String acao, Object... valoresPlanos) {
        return atlas.responderCartao(marca(), solicitacaoId, new RespostaDoCartaoRequisicao(acao, valores(valoresPlanos)), requisicao());
    }

    /** A conversa até o cartão, respondida com accept dos quatro campos. Devolve a conversa do accept. */
    ConversaDto montarComTodosOsDados() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());
        return responder(cartao.solicitacaoId(), "accept", VISITANTES, 1200L, CADASTROS, 1072L, DISPONIVEIS, 300L, DISTRIBUIDOS, 150L);
    }

    List<Mensagem> mensagens(long conversaId) {
        return jdbc.sql("""
                SELECT papel, conteudo, CAST(tool_calls AS text) AS tool_calls, tool_call_id
                FROM atlas_mensagem WHERE conversa_id = :id ORDER BY ordem
                """).param("id", conversaId).query(Mensagem.class).list();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    String status(String tabela, long id) {
        return jdbc.sql("SELECT status FROM " + tabela + " WHERE id = :id").param("id", id).query(String.class).single();
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

    static List<Map<String, Object>> chamadas(Mensagem mensagem) {
        return AtlasJson.ler(mensagem.toolCalls(), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /** "Toda tool_call tem resposta", menos a do cartão que está esperando. */
    void confereQueToolCallTemResposta(long conversaId, int pendentes) {
        List<Mensagem> todas = mensagens(conversaId);
        List<String> pedidos = new ArrayList<>();
        todas.stream().filter(m -> m.toolCalls() != null).forEach(m -> chamadas(m).forEach(c -> pedidos.add((String) c.get("id"))));
        List<String> respondidos = todas.stream().filter(m -> "tool".equals(m.papel())).map(Mensagem::toolCallId).toList();
        assertThat(respondidos).as("respostas de ferramenta").allMatch(pedidos::contains);
        assertThat(pedidos.stream().filter(id -> !respondidos.contains(id)).count()).as("pedidos sem resposta").isEqualTo(pendentes);
    }

    BlocoDto bloco(RelatorioAtlasDto relatorio, String id) {
        return relatorio.secoes().stream().flatMap(s -> s.blocos().stream()).filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    void fecharAbaDeAplicacoes() {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();
    }

    // ---------------------------------------------------------------------
    // O botão abre a conversa e o cartão
    // ---------------------------------------------------------------------

    @Test
    void conversaVaziaNaoTemItens() {
        ConversaDto conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao());

        assertThat(conversa.itens()).isEmpty();
        assertThat(conversa.llmDisponivel()).isFalse();
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND usuario_id = ?", conversa.conversaId(), marca().id())).isEqualTo(1);
        assertThat(mensagens(conversa.conversaId())).isEmpty();
        assertThat(atlas.abrirConversa(marca(), amarelo(), new AbrirConversaRequisicao(null, null), requisicao()).itens()).isEmpty();
    }

    @Test
    void botaoAbreAConversaComOCartaoDasLacunas() {
        ConversaDto conversa = abrirComOBotao();

        assertThat(conversa.llmDisponivel()).isFalse();
        assertThat(tipos(conversa)).containsExactly("usuario", "texto", "cartao");
        assertThat(((ItemUsuario) conversa.itens().get(0)).texto()).isEqualTo("Montar o relatório da ativação");
        assertThat(((ItemTexto) conversa.itens().get(1)).texto()).startsWith("Montei a base do relatório de ativação");
        assertThat(((ItemTexto) conversa.itens().get(1)).porModelo()).isFalse();
        ItemCartao cartao = cartaoDe(conversa);
        assertThat(cartao.status()).isEqualTo("PENDENTE");
        assertThat(cartao.mensagem()).isEqualTo("Para fechar a seção de público, preciso destes números.");
        assertThat(cartao.campos()).extracting("chave", "obrigatorio", "tipo", "unidade", "minimo", "vigente")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VISITANTES, true, "INTEIRO", "pessoas", 0L, null),
                        org.assertj.core.groups.Tuple.tuple(CADASTROS, true, "INTEIRO", "cadastros", 0L, null),
                        org.assertj.core.groups.Tuple.tuple(DISPONIVEIS, false, "INTEIRO", "brindes", 0L, null),
                        org.assertj.core.groups.Tuple.tuple(DISTRIBUIDOS, false, "INTEIRO", "brindes", 0L, null));
        assertThat(cartao.campos().get(0).rotulo()).isEqualTo("Visitantes no espaço");
        assertThat(cartao.campos().get(1).limiteCampo()).isEqualTo(VISITANTES);
        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", conversa.conversaId())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void oHistoricoSaiNoFormatoDeChatCompletionsComoSeOModeloTivesseChamadoAsFerramentas() {
        ConversaDto conversa = abrirComOBotao();

        List<Mensagem> historico = mensagens(conversa.conversaId());

        assertThat(historico).extracting(Mensagem::papel).containsExactly("user", "assistant", "tool", "assistant");
        assertThat(historico.get(0).conteudo()).isEqualTo("Montar o relatório da ativação (botão)");

        List<Map<String, Object>> iniciar = chamadas(historico.get(1));
        assertThat(iniciar).hasSize(1);
        assertThat((String) iniciar.get(0).get("id")).startsWith("botao_");
        assertThat(iniciar.get(0).get("type")).isEqualTo("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> funcao = (Map<String, Object>) iniciar.get(0).get("function");
        assertThat(funcao.get("name")).isEqualTo("relatorio_iniciar");
        assertThat(funcao.get("arguments")).as("os argumentos são um texto JSON, como no chat completions").isInstanceOf(String.class);
        assertThat((String) funcao.get("arguments")).contains("\"modelo\":\"ATIVACAO_EVENTO\"");

        assertThat(historico.get(2).toolCallId()).isEqualTo(iniciar.get(0).get("id"));
        assertThat(historico.get(2).conteudo()).contains("\"lacunasObrigatorias\"").contains("\"dados\"").contains("\"secoes\"");

        assertThat(historico.get(3).conteudo()).startsWith("Montei a base do relatório de ativação");
        assertThat(historico.get(3).toolCalls()).contains("relatorio_solicitar_campos").contains("ativacao.visitantes_total");
        confereQueToolCallTemResposta(conversa.conversaId(), 1);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ?",
                conversa.conversaId())).as("iniciar e solicitar_campos").isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id "
                + "WHERE m.conversa_id = ? AND t.ferramenta = 'relatorio_solicitar_campos' AND t.resultado IS NULL", conversa.conversaId()))
                .as("o cartão espera o resultado").isEqualTo(1);
    }

    @Test
    void oResumoDeIniciarTraDadosDisponiveisELacunas() {
        ConversaDto conversa = abrirComOBotao();

        String resumo = mensagens(conversa.conversaId()).get(2).conteudo();
        Map<String, Object> corpo = AtlasJson.ler(resumo, new TypeReference<Map<String, Object>>() {
        });

        assertThat(corpo.get("titulo")).isEqualTo("Ativação no evento");
        assertThat(resumo).contains("\"ref\":\"cons:aplicacoes.previstas\"", "\"valor\":\"19\"", "\"ref\":\"parc:patrocinador\"");
        assertThat(resumo).contains("ativacao.visitantes_total", "ativacao.brindes_distribuidos");
        assertThat(resumo).as("a origem de cada dado vem junto").contains("Consolidação do portal");
    }

    // ---------------------------------------------------------------------
    // accept: dados com origem ATLAS e o relatório pronto
    // ---------------------------------------------------------------------

    @Test
    void acceptGravaOsDadosComOrigemAtlasEEntregaORelatorioPronto() {
        ConversaDto aberta = abrirComOBotao();
        ItemCartao cartao = cartaoDe(aberta);

        ConversaDto pronta = responder(cartao.solicitacaoId(), "accept", VISITANTES, 1200L, CADASTROS, 1072L);

        assertThat(tipos(pronta)).containsExactly("texto", "relatorio_pronto");
        assertThat(((ItemTexto) pronta.itens().get(0)).texto())
                .isEqualTo("Pronto: o relatório de ativação está montado. A origem de cada número está em Fontes e cálculos.");
        ItemRelatorioPronto item = (ItemRelatorioPronto) pronta.itens().get(1);
        assertThat(item.titulo()).isEqualTo("Ativação no evento");
        assertThat(item.relatorioId()).isEqualTo(cartao.relatorioId());
        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("PRONTO");
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("RESPONDIDA");

        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND origem = 'ATLAS' AND informado_por = ?",
                amarelo(), marca().id())).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND origem = 'PORTAL'", amarelo())).isZero();
        assertThat(contar("SELECT valor_numero FROM vw_dado_vigente WHERE parceria_id = ? AND campo_chave = ?", amarelo(), VISITANTES))
                .isEqualTo(1200);
    }

    @Test
    void oRelatorioProntoTemValoresFormatadosFontesECalculos() {
        ConversaDto pronta = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) pronta.itens().get(1)).relatorioId();

        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), relatorioId);

        assertThat(relatorio.status()).isEqualTo("PRONTO");
        assertThat(relatorio.parceriaId()).isEqualTo(amarelo());
        assertThat(relatorio.patrocinador()).isEqualTo("Cliente Amarelo");
        assertThat(relatorio.modelo()).isEqualTo("ATIVACAO_EVENTO");
        assertThat(relatorio.criadoPor().nome()).isEqualTo("Marketing Amarelo");
        assertThat(relatorio.finalizadoEm()).isNotNull();
        assertThat(relatorio.selo()).isEqualTo("Documento de trabalho da marca, montado pelo Atlas. Não validado pela RBS.");
        KpiGradeDto publico = (KpiGradeDto) bloco(relatorio, "b-kpi-publico");
        assertThat(publico.itens()).extracting(ItemKpiDto::rotulo, ItemKpiDto::valor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Visitantes", "1.200"),
                        org.assertj.core.groups.Tuple.tuple("Cadastros", "1.072"),
                        org.assertj.core.groups.Tuple.tuple("Taxa de cadastro", "89,3%"),
                        org.assertj.core.groups.Tuple.tuple("Brindes distribuídos", "50%"));
        assertThat(((ParagrafoDto) bloco(relatorio, "b-publico")).texto())
                .isEqualTo("Visitantes no espaço da marca: 1.200. Taxa de cadastro com consentimento: 89,3%.");
        assertThat(((ParagrafoDto) bloco(relatorio, "b-publico")).redigidoPor()).isEqualTo("MODELO");
        FontesDto fontes = (FontesDto) bloco(relatorio, "b-fontes");
        assertThat(fontes.itens()).extracting("ref").contains("dado:" + VISITANTES, "calc:taxa_cadastro", "cons:aplicacoes.percentual");
        assertThat(fontes.itens()).filteredOn(f -> f.ref().equals("calc:taxa_cadastro")).extracting("origem")
                .containsExactly("pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200");
    }

    @Test
    void aTaxaDoRelatorioEAMesmaDoBancoEAsContasFicamEmAtlasCalculo() {
        ConversaDto pronta = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) pronta.itens().get(1)).relatorioId();

        var contas = jdbc.sql("SELECT nome, script, resultado, unidade, CAST(variaveis AS text) AS variaveis FROM atlas_calculo "
                + "WHERE relatorio_id = :id ORDER BY nome").param("id", relatorioId)
                .query((rs, n) -> new Object[]{rs.getString("nome"), rs.getString("script"), rs.getBigDecimal("resultado"),
                        rs.getString("unidade"), rs.getString("variaveis")}).list();

        assertThat(contas).extracting(c -> c[0]).containsExactly("brindes_pct", "taxa_cadastro");
        Object[] taxa = contas.get(1);
        assertThat(taxa[1]).isEqualTo("pct(cadastros, visitantes)");
        assertThat((java.math.BigDecimal) taxa[2]).isEqualByComparingTo(jdbc.sql("SELECT fn_pct_truncado(100 * 1072 / CAST(1200 AS numeric))")
                .query(java.math.BigDecimal.class).single());
        assertThat((java.math.BigDecimal) taxa[2]).isEqualByComparingTo("89.3");
        assertThat(taxa[3]).isEqualTo("%");
        assertThat((String) taxa[4]).contains("dado:ativacao.cadastros_consentidos").contains("1072");
    }

    @Test
    void oRelatorioProntoFicaCongeladoNoBanco() {
        ConversaDto pronta = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) pronta.itens().get(1)).relatorioId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE atlas_relatorio SET snapshot = CAST('{}' AS jsonb) WHERE id = :id")
                .param("id", relatorioId).update())
                .hasMessageContaining("pronto não muda mais");
    }

    @Test
    void aoFinalizarGravaCriacaoEEdicaoNoHistoricoDaParceria() {
        ConversaDto pronta = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) pronta.itens().get(1)).relatorioId();

        var linhas = jdbc.sql("SELECT acao, valor_anterior, valor_novo, usuario_id FROM historico "
                + "WHERE entidade = 'atlas_relatorio' AND entidade_id = :id ORDER BY id").param("id", relatorioId)
                .query((rs, n) -> new Object[]{rs.getString("acao"), rs.getString("valor_anterior"), rs.getString("valor_novo"),
                        rs.getLong("usuario_id")}).list();

        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0)[0]).isEqualTo("CRIACAO");
        assertThat(linhas.get(0)[2]).isEqualTo("MONTANDO");
        assertThat(linhas.get(1)[0]).isEqualTo("EDICAO");
        assertThat(linhas.get(1)[1]).isEqualTo("MONTANDO");
        assertThat(linhas.get(1)[2]).isEqualTo("PRONTO");
        assertThat(linhas.get(1)[3]).isEqualTo(marca().id());
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'dado_informado' AND parceria_id = ?", amarelo()))
                .as("cada dado gravado também entra no histórico").isEqualTo(4);
    }

    @Test
    void depoisDoAcceptOHistoricoFechaTodaChamadaDeFerramenta() {
        ConversaDto pronta = montarComTodosOsDados();

        List<Mensagem> historico = mensagens(pronta.conversaId());

        assertThat(historico).extracting(Mensagem::papel).containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant", "tool");
        confereQueToolCallTemResposta(pronta.conversaId(), 0);
        assertThat(historico.get(4).conteudo()).contains("\"acao\":\"accept\"").contains("\"ref\":\"dado:ativacao.visitantes_total\"")
                .contains("\"valor\":1200");
        assertThat(chamadas(historico.get(5)).get(0).toString()).contains("relatorio_finalizar");
        assertThat(historico.get(6).conteudo()).contains("\"status\":\"PRONTO\"");
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id "
                + "WHERE m.conversa_id = ? AND t.resultado IS NULL", pronta.conversaId())).as("nenhuma ferramenta ficou sem resultado").isZero();
    }

    @Test
    void aConversaInteiraSaiDoHistoricoComOCartaoNoEstadoDeAgora() {
        ConversaDto pronta = montarComTodosOsDados();

        ConversaDto inteira = atlas.verConversa(marca(), pronta.conversaId());

        assertThat(tipos(inteira)).containsExactly("usuario", "texto", "cartao", "texto", "relatorio_pronto");
        ItemCartao cartao = (ItemCartao) inteira.itens().get(2);
        assertThat(cartao.status()).isEqualTo("RESPONDIDA");
        assertThat(cartao.campos().get(0).vigente()).isNotNull();
        assertThat(cartao.campos().get(0).vigente().valor()).isEqualTo(1200L);
        assertThat(cartao.campos().get(0).vigente().origem()).isEqualTo("ATLAS");
        assertThat(inteira.conversaId()).isEqualTo(pronta.conversaId());
    }

    @Test
    void oBotaoNaoPedeOQueJaTem() {
        long amarelo = amarelo();
        // a marca já informou tudo o que é obrigatório pelo formulário da aba: não há cartão, o relatório sai direto
        jdbc.sql("""
                INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
                SELECT :p, v.chave, v.valor, 'PORTAL', :u FROM (VALUES ('ativacao.visitantes_total', 500), ('ativacao.cadastros_consentidos', 250)) v(chave, valor)
                """).param("p", amarelo).param("u", marca().id()).update();

        ConversaDto conversa = abrirComOBotao();

        assertThat(tipos(conversa)).containsExactly("usuario", "texto", "relatorio_pronto");
        long relatorioId = ((ItemRelatorioPronto) conversa.itens().get(2)).relatorioId();
        assertThat(status("atlas_relatorio", relatorioId)).isEqualTo("PRONTO");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ?", relatorioId)).isZero();
        confereQueToolCallTemResposta(conversa.conversaId(), 0);
        KpiGradeDto publico = (KpiGradeDto) bloco(atlas.verRelatorio(marca(), relatorioId), "b-kpi-publico");
        assertThat(publico.itens().get(2).valor()).isEqualTo("50%");
    }

    // ---------------------------------------------------------------------
    // decline e cancel
    // ---------------------------------------------------------------------

    @Test
    void declineDeixaOsBlocosIndisponiveisENuncaZero() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        ConversaDto pronta = responder(cartao.solicitacaoId(), "decline");

        assertThat(tipos(pronta)).containsExactly("texto", "relatorio_pronto");
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("RECUSADA");
        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("PRONTO");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", amarelo())).as("nada foi gravado").isZero();

        RelatorioAtlasDto relatorio = atlas.verRelatorio(marca(), cartao.relatorioId());
        KpiGradeDto publico = (KpiGradeDto) bloco(relatorio, "b-kpi-publico");
        assertThat(publico.itens()).allSatisfy(item -> {
            assertThat(item.valor()).isNull();
            assertThat(item.indisponivel()).isNotBlank();
        });
        assertThat(publico.itens().get(0).indisponivel()).isEqualTo("Não informado pela marca.");
        assertThat(bloco(relatorio, "b-publico")).isInstanceOf(AvisoIndisponivelDto.class);
        assertThat(AtlasJson.escrever(relatorio.secoes())).doesNotContain("\"valor\":\"0\"");
        assertThat(bloco(relatorio, "b-resumo")).as("o resumo só depende da consolidação").isInstanceOf(ParagrafoDto.class);
        assertThat(contar("SELECT count(*) FROM atlas_calculo WHERE relatorio_id = ? AND resultado IS NULL", cartao.relatorioId())).isEqualTo(2);
    }

    @Test
    void cancelVoltaAMontarEOTurnoTerminaComUmAviso() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        ConversaDto resposta = responder(cartao.solicitacaoId(), "cancel");

        assertThat(tipos(resposta)).containsExactly("aviso");
        assertThat(((ItemAviso) resposta.itens().get(0)).texto()).isEqualTo("Cartão fechado. Use Montar relatório para continuar.");   // sem o modelo de linguagem
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("CANCELADA");
        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("MONTANDO");
        confereQueToolCallTemResposta(resposta.conversaId(), 0);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", amarelo())).isZero();
    }

    @Test
    void depoisDoCancelOBotaoContinuaOMesmoRelatorioEReabreOCartao() {
        ConversaDto primeira = abrirComOBotao();
        ItemCartao cartao = cartaoDe(primeira);
        responder(cartao.solicitacaoId(), "cancel");

        ConversaDto segunda = atlas.enviarMensagem(marca(), primeira.conversaId(), BOTAO_NA_CONVERSA, requisicao());

        assertThat(tipos(segunda)).containsExactly("usuario", "texto", "cartao");
        assertThat(((ItemTexto) segunda.itens().get(1)).texto()).startsWith("Continuo o relatório de ativação");
        ItemCartao novo = cartaoDe(segunda);
        assertThat(novo.relatorioId()).as("o mesmo relatório").isEqualTo(cartao.relatorioId());
        assertThat(novo.solicitacaoId()).isNotEqualTo(cartao.solicitacaoId());
        assertThat(novo.campos()).hasSize(4);
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", primeira.conversaId())).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ?", cartao.relatorioId())).isEqualTo(2);
        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(mensagens(primeira.conversaId()).stream().filter(m -> m.toolCalls() != null && m.toolCalls().contains("relatorio_iniciar")))
                .as("o relatório não é iniciado de novo").hasSize(1);
        confereQueToolCallTemResposta(primeira.conversaId(), 1);

        ConversaDto pronta = responder(novo.solicitacaoId(), "accept", VISITANTES, 100L, CADASTROS, 50L);
        assertThat(tipos(pronta)).containsExactly("texto", "relatorio_pronto");
        assertThat(((ItemRelatorioPronto) pronta.itens().get(1)).relatorioId()).isEqualTo(cartao.relatorioId());
    }

    @Test
    void depoisDeUmRelatorioProntoOBotaoComecaOutro() {
        ConversaDto pronta = montarComTodosOsDados();

        ConversaDto outra = atlas.enviarMensagem(marca(), pronta.conversaId(), BOTAO_NA_CONVERSA, requisicao());

        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", pronta.conversaId())).isEqualTo(2);
        assertThat(tipos(outra)).as("os dados já estão informados: sai direto").containsExactly("usuario", "texto", "relatorio_pronto");
    }

    // ---------------------------------------------------------------------
    // Os erros do cartão e do turno
    // ---------------------------------------------------------------------

    @Test
    void cartaoAbertoNaConversaRecusaOBotaoComSegundoCartao() {
        ConversaDto aberta = abrirComOBotao();

        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), aberta.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(ConflitoException.class).hasMessage(PlanoBSemLlm.CARTAO_ABERTO);
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ?", cartaoDe(aberta).relatorioId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", aberta.conversaId())
                .query(Boolean.class).single()).as("o turno termina também em erro").isTrue();
    }

    @Test
    void cartaoJaRespondidoDaConflito() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());
        responder(cartao.solicitacaoId(), "decline");

        for (String acao : List.of("accept", "decline", "cancel")) {
            assertThatThrownBy(() -> responder(cartao.solicitacaoId(), acao, VISITANTES, 1L, CADASTROS, 1L))
                    .isInstanceOf(ConflitoException.class).hasMessage("Este cartão já foi respondido.");
        }
    }

    @Test
    void turnoEmAndamentoDaConflitoMasOQueVenceuOsCincoMinutosPassa() {
        ConversaDto conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao());
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa.conversaId()).update();

        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(ConflitoException.class).hasMessage("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");
        assertThat(mensagens(conversa.conversaId())).as("nada foi gravado").isEmpty();
        assertThat(jdbc.sql("SELECT turno_desde IS NOT NULL FROM atlas_conversa WHERE id = :id").param("id", conversa.conversaId())
                .query(Boolean.class).single()).as("o turno de quem já estava rodando não é desfeito").isTrue();

        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() - interval '6 minutes' WHERE id = :id").param("id", conversa.conversaId()).update();
        assertThat(tipos(atlas.enviarMensagem(marca(), conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao())))
                .containsExactly("usuario", "texto", "cartao");
    }

    @Test
    void turnoEmAndamentoTambemBarraAResposta() {
        ConversaDto aberta = abrirComOBotao();
        ItemCartao cartao = cartaoDe(aberta);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", aberta.conversaId()).update();

        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "decline")).isInstanceOf(ConflitoException.class)
                .hasMessage(AtlasService.TURNO_EM_ANDAMENTO);
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
    }

    @Test
    void textoSemModeloDeLinguagemDa503ComAFraseDoBotao() {
        ConversaDto conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao());

        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa.conversaId(), new MensagemRequisicao("Quanto deu?", null, null), requisicao()))
                .isInstanceOf(ServicoIndisponivelException.class)
                .hasMessage("O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório.");
        assertThat(mensagens(conversa.conversaId())).isEmpty();
    }

    @Test
    void textoVazioOuLongoDemaisOuMisturadoCom() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();

        for (String texto : List.of("", "   ", "x".repeat(1001))) {
            assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao()))
                    .as("texto de " + texto.length()).isInstanceOfSatisfying(DadosInvalidosException.class,
                            e -> assertThat(e.campos()).containsKey("texto"));
        }
        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao("x".repeat(1000), null, null), requisicao()))
                .as("1.000 caracteres ainda valem: chega no 503").isInstanceOf(ServicoIndisponivelException.class);
        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(null, null, null), requisicao()))
                .isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao("oi", "MONTAR_RELATORIO", "ATIVACAO_EVENTO"),
                requisicao())).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void atalhoOuModeloDesconhecidoDa422() {
        for (AbrirConversaRequisicao corpo : List.of(new AbrirConversaRequisicao("OUTRO", "ATIVACAO_EVENTO"),
                new AbrirConversaRequisicao("MONTAR_RELATORIO", "OUTRO_MODELO"), new AbrirConversaRequisicao("MONTAR_RELATORIO", null),
                new AbrirConversaRequisicao(null, "ATIVACAO_EVENTO"))) {
            assertThatThrownBy(() -> atlas.abrirConversa(marca(), amarelo(), corpo, requisicao())).as(corpo.toString())
                    .isInstanceOf(DadosInvalidosException.class);
        }
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE usuario_id = ?", marca().id())).as("nenhuma conversa ficou").isZero();
    }

    @Test
    void acaoDoCartaoDesconhecidaDa422() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        for (String acao : java.util.Arrays.asList(null, "", "ACCEPT", "aceitar")) {
            assertThatThrownBy(() -> responder(cartao.solicitacaoId(), acao)).as("ação " + acao)
                    .isInstanceOfSatisfying(DadosInvalidosException.class, e -> assertThat(e.campos()).containsKey("acao"));
        }
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
    }

    // ---------------------------------------------------------------------
    // Os valores do accept
    // ---------------------------------------------------------------------

    @Test
    void acceptComChaveForaDoCartaoDa422PelaChave() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "accept", VISITANTES, 10L, CADASTROS, 5L, "ativacao.nao_existe", 1L))
                .isInstanceOfSatisfying(DadosInvalidosException.class,
                        e -> assertThat(e.campos()).containsEntry("ativacao.nao_existe", "Este campo não está no cartão."));
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", amarelo())).as("tudo ou nada").isZero();
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
    }

    @Test
    void acceptSemValorNumaObrigatoriaDa422() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "accept", VISITANTES, 10L))
                .isInstanceOfSatisfying(DadosInvalidosException.class,
                        e -> assertThat(e.campos()).containsEntry(CADASTROS, "Informe este número.").doesNotContainKey(VISITANTES));
        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "accept"))
                .isInstanceOfSatisfying(DadosInvalidosException.class,
                        e -> assertThat(e.campos()).containsOnlyKeys(VISITANTES, CADASTROS));
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", amarelo())).isZero();
    }

    @Test
    void acceptUsaAsMesmasValidacoesDaRota41() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "accept", VISITANTES, 100L, CADASTROS, 101L))
                .isInstanceOfSatisfying(DadosInvalidosException.class, e -> assertThat(e.campos())
                        .containsEntry(CADASTROS, "Não pode passar de Visitantes no espaço (100)."));
        assertThatThrownBy(() -> responder(cartao.solicitacaoId(), "accept", VISITANTES, "1200", CADASTROS, -1L))
                .isInstanceOfSatisfying(DadosInvalidosException.class, e -> assertThat(e.campos())
                        .containsEntry(VISITANTES, "Informe um número inteiro.").containsEntry(CADASTROS, "O valor mínimo é 0."));
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ?", amarelo())).isZero();
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
    }

    @Test
    void acceptComOsOpcionaisEmBrancoDeixaSoOsBrindesIndisponiveis() {
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        ConversaDto pronta = responder(cartao.solicitacaoId(), "accept", VISITANTES, 1200L, CADASTROS, 1072L);

        assertThat(tipos(pronta)).containsExactly("texto", "relatorio_pronto");
        KpiGradeDto publico = (KpiGradeDto) bloco(atlas.verRelatorio(marca(), cartao.relatorioId()), "b-kpi-publico");
        assertThat(publico.itens().get(2).valor()).isEqualTo("89,3%");
        assertThat(publico.itens().get(3).valor()).isNull();
        assertThat(publico.itens().get(3).indisponivel()).startsWith("Faltam dados para o cálculo");
    }

    @Test
    void oCartaoNaoPedeOQueJaTemValorVigente() {
        long amarelo = amarelo();
        jdbc.sql("INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por) VALUES (:p, :c, 900, 'PORTAL', :u)")
                .param("p", amarelo).param("c", VISITANTES).param("u", marca().id()).update();
        ItemCartao cartao = cartaoDe(abrirComOBotao());

        assertThat(cartao.campos()).extracting("chave").as("o que já tem valor não é pedido de novo")
                .containsExactly(CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
        ConversaDto pronta = responder(cartao.solicitacaoId(), "accept", CADASTROS, 800L);

        assertThat(tipos(pronta)).containsExactly("texto", "relatorio_pronto");
        assertThat(contar("SELECT valor_numero FROM vw_dado_vigente WHERE parceria_id = ? AND campo_chave = ?", amarelo, VISITANTES)).isEqualTo(900);
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE parceria_id = ? AND origem = 'ATLAS'", amarelo)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Isolamento, permissão e aba
    // ---------------------------------------------------------------------

    @Test
    void marcaDeOutraParceriaNaoAcessaNada() {
        ConversaDto conversa = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) conversa.itens().get(1)).relatorioId();
        UsuarioAutenticado doRoxo = usuario(jdbc, ROXO);
        ItemCartao cartao = (ItemCartao) atlas.verConversa(marca(), conversa.conversaId()).itens().get(2);

        assertThatThrownBy(() -> atlas.abrirConversa(doRoxo, amarelo(), BOTAO, requisicao())).isInstanceOf(NaoEncontradoException.class)
                .hasMessage("Não encontramos esta parceria.");
        assertThatThrownBy(() -> atlas.listarConversas(doRoxo, amarelo())).isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> atlas.listarRelatorios(doRoxo, amarelo())).isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> atlas.verConversa(doRoxo, conversa.conversaId())).isInstanceOf(NaoEncontradoException.class)
                .hasMessage("Não encontramos esta conversa.");
        assertThatThrownBy(() -> atlas.enviarMensagem(doRoxo, conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(NaoEncontradoException.class).hasMessage("Não encontramos esta conversa.");
        assertThatThrownBy(() -> atlas.responderCartao(doRoxo, cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("cancel", null), requisicao()))
                .isInstanceOf(NaoEncontradoException.class).hasMessage("Não encontramos esta solicitação.");
        assertThatThrownBy(() -> atlas.verRelatorio(doRoxo, relatorioId)).isInstanceOf(NaoEncontradoException.class)
                .hasMessage("Não encontramos este relatório.");
    }

    @Test
    void idsQueNaoExistemTemAMesmaFraseDosDeOutraParceria() {
        UsuarioAutenticado doRoxo = usuario(jdbc, ROXO);

        assertThatThrownBy(() -> atlas.verConversa(doRoxo, 999_999)).hasMessage("Não encontramos esta conversa.");
        assertThatThrownBy(() -> atlas.responderCartao(doRoxo, 999_999, new RespostaDoCartaoRequisicao("cancel", null), requisicao()))
                .hasMessage("Não encontramos esta solicitação.");
        assertThatThrownBy(() -> atlas.verRelatorio(doRoxo, 999_999)).hasMessage("Não encontramos este relatório.");
        assertThatThrownBy(() -> atlas.verRelatorio(usuario(jdbc, RBS), 999_999)).hasMessage("Não encontramos este relatório.");
    }

    @Test
    void conversaESolicitacaoDeOutroUsuarioDaMesmaParceriaTambemDao404() {
        ConversaDto conversa = abrirComOBotao();
        ItemCartao cartao = cartaoDe(conversa);
        UsuarioAutenticado agencia = usuario(jdbc, AGENCIA_DO_AMARELO);

        assertThatThrownBy(() -> atlas.verConversa(agencia, conversa.conversaId())).isInstanceOf(NaoEncontradoException.class)
                .hasMessage("Não encontramos esta conversa.");
        assertThatThrownBy(() -> atlas.enviarMensagem(agencia, conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> atlas.responderCartao(agencia, cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("cancel", null),
                requisicao())).isInstanceOf(NaoEncontradoException.class).hasMessage("Não encontramos esta solicitação.");
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("PENDENTE");
        assertThat(atlas.listarConversas(agencia, amarelo()).conversas()).as("a lista é só das conversas de quem pede").isEmpty();
        assertThat(atlas.listarConversas(marca(), amarelo()).conversas()).hasSize(1);
    }

    @Test
    void oRelatorioEDaParceriaEQualquerUsuarioDelaEARbsOVeem() {
        ConversaDto conversa = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) conversa.itens().get(1)).relatorioId();

        for (String email : List.of(AMARELO, AGENCIA_DO_AMARELO, RBS)) {
            UsuarioAutenticado quem = usuario(jdbc, email);
            assertThat(atlas.verRelatorio(quem, relatorioId).status()).as(email).isEqualTo("PRONTO");
            assertThat(atlas.listarRelatorios(quem, amarelo())).as(email).hasSize(1);
        }
        var resumo = atlas.listarRelatorios(marca(), amarelo()).get(0);
        assertThat(resumo.id()).isEqualTo(relatorioId);
        assertThat(resumo.titulo()).isEqualTo("Ativação no evento");
        assertThat(resumo.modelo()).isEqualTo("ATIVACAO_EVENTO");
        assertThat(resumo.criadoPor().nome()).isEqualTo("Marketing Amarelo");
        assertThat(resumo.finalizadoEm()).isNotNull();
    }

    @Test
    void aListaDeRelatoriosVemDoMaisNovoParaOMaisAntigo() {
        ConversaDto primeira = montarComTodosOsDados();
        ConversaDto segunda = atlas.abrirConversa(marca(), amarelo(), BOTAO, requisicao());
        long idDoPrimeiro = ((ItemRelatorioPronto) primeira.itens().get(1)).relatorioId();
        long idDoSegundo = ((ItemRelatorioPronto) segunda.itens().get(2)).relatorioId();

        assertThat(atlas.listarRelatorios(marca(), amarelo())).extracting("id").containsExactly(idDoSegundo, idDoPrimeiro);
    }

    @Test
    void relatorioEmMontagemEResolvidoAoVivoParaQuemPedeEPodeSerVistoPorOutroUsuarioDaParceria() {
        ConversaDto aberta = abrirComOBotao();
        long relatorioId = cartaoDe(aberta).relatorioId();

        RelatorioAtlasDto doOutro = atlas.verRelatorio(usuario(jdbc, AGENCIA_DO_AMARELO), relatorioId);

        assertThat(doOutro.status()).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(doOutro.finalizadoEm()).isNull();
        KpiGradeDto aplicacoes = (KpiGradeDto) bloco(doOutro, "b-kpi-aplic");
        assertThat(aplicacoes.itens().get(0).valor()).isEqualTo("19");
        KpiGradeDto publico = (KpiGradeDto) bloco(doOutro, "b-kpi-publico");
        assertThat(publico.itens().get(0).valor()).isNull();
        assertThat(((ParagrafoDto) bloco(doOutro, "b-resumo")).redigidoPor()).as("a prévia usa a frase-modelo").isEqualTo("MODELO");
    }

    @Test
    void aRbsNaoConversaComOAtlas() {
        UsuarioAutenticado rbs = usuario(jdbc, RBS);
        ConversaDto conversa = abrirComOBotao();

        assertThatThrownBy(() -> atlas.abrirConversa(rbs, amarelo(), BOTAO, requisicao())).isInstanceOf(SemPermissaoException.class)
                .hasMessage(VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        assertThatThrownBy(() -> atlas.listarConversas(rbs, amarelo())).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.verConversa(rbs, conversa.conversaId())).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.enviarMensagem(rbs, conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.responderCartao(rbs, cartaoDe(conversa).solicitacaoId(), new RespostaDoCartaoRequisicao("cancel", null),
                requisicao())).isInstanceOf(SemPermissaoException.class);
        assertThat(status("atlas_solicitacao", cartaoDe(conversa).solicitacaoId())).isEqualTo("PENDENTE");
    }

    @Test
    void comAAbaDeAplicacoesFechadaAMarcaTomaOs403DoProjetoEARbsPassa() {
        ConversaDto conversa = montarComTodosOsDados();
        long relatorioId = ((ItemRelatorioPronto) conversa.itens().get(1)).relatorioId();
        fecharAbaDeAplicacoes();

        assertThatThrownBy(() -> atlas.abrirConversa(marca(), amarelo(), BOTAO, requisicao())).isInstanceOf(SemPermissaoException.class)
                .hasMessage(VerificadorDeAcesso.ABA_NAO_LIBERADA);
        assertThatThrownBy(() -> atlas.listarConversas(marca(), amarelo())).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.verConversa(marca(), conversa.conversaId())).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.enviarMensagem(marca(), conversa.conversaId(), BOTAO_NA_CONVERSA, requisicao()))
                .isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> atlas.verRelatorio(marca(), relatorioId)).as("fechar a aba esconde o relatório pronto")
                .isInstanceOf(SemPermissaoException.class).hasMessage(VerificadorDeAcesso.ABA_NAO_LIBERADA);
        assertThatThrownBy(() -> atlas.listarRelatorios(usuario(jdbc, AGENCIA_DO_AMARELO), amarelo())).isInstanceOf(SemPermissaoException.class);
        assertThat(atlas.verRelatorio(usuario(jdbc, RBS), relatorioId).status()).isEqualTo("PRONTO");
        assertThat(atlas.listarRelatorios(usuario(jdbc, RBS), amarelo())).hasSize(1);
    }

    @Test
    void abaForaDaCotaDizOutraFrase() {
        jdbc.sql("DELETE FROM parceria_aba WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();
        jdbc.sql("DELETE FROM cota_tipo_aba WHERE cota_tipo_id = (SELECT cota_tipo_id FROM parceria WHERE id = :p) "
                + "AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')").param("p", amarelo()).update();

        assertThatThrownBy(() -> atlas.abrirConversa(marca(), amarelo(), BOTAO, requisicao())).isInstanceOf(SemPermissaoException.class)
                .hasMessage(VerificadorDeAcesso.ABA_FORA_DA_COTA);
    }

    // ---------------------------------------------------------------------
    // Conversas
    // ---------------------------------------------------------------------

    @Test
    void aListaDeConversasTraSoAsDoUsuarioDaMaisRecenteParaAMaisAntigaComORelatorio() {
        ConversaDto vazia = atlas.abrirConversa(marca(), amarelo(), null, requisicao());
        ConversaDto comCartao = abrirComOBotao();
        atlas.abrirConversa(usuario(jdbc, AGENCIA_DO_AMARELO), amarelo(), null, requisicao());

        var lista = atlas.listarConversas(marca(), amarelo());

        assertThat(lista.llmDisponivel()).isFalse();
        assertThat(lista.conversas()).extracting("id").containsExactly(comCartao.conversaId(), vazia.conversaId());
        assertThat(lista.conversas().get(0).relatorio().status()).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(lista.conversas().get(0).relatorio().id()).isEqualTo(cartaoDe(comCartao).relatorioId());
        assertThat(lista.conversas().get(1).relatorio()).isNull();
        assertThat(lista.conversas().get(0).criadaEm()).isNotNull();
        assertThat(lista.conversas().get(0).atualizadaEm()).isAfterOrEqualTo(lista.conversas().get(0).criadaEm());
    }

    @Test
    void aListaTemNoMaximoVinteConversas() {
        for (int i = 0; i < 22; i++) {
            atlas.abrirConversa(marca(), amarelo(), null, requisicao());
        }

        assertThat(atlas.listarConversas(marca(), amarelo()).conversas()).hasSize(20);
    }

    @Test
    void textoDoAssistenteComMarcadorSaiComOValorOuIndisponivel() {
        ConversaDto conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao());
        historico.assistente(conversa.conversaId(), "A marca {{parc:patrocinador}} tem {{cons:aplicacoes.previstas}} aplicações e "
                + "{{dado:" + VISITANTES + "}} visitantes.", List.of());

        ConversaDto inteira = atlas.verConversa(marca(), conversa.conversaId());

        assertThat(((ItemTexto) inteira.itens().get(0)).texto())
                .isEqualTo("A marca Cliente Amarelo tem 19 aplicações e indisponível visitantes.");
    }

    // ---------------------------------------------------------------------
    // Descarte
    // ---------------------------------------------------------------------

    private void envelhecer(long conversaId, int dias) {
        jdbc.sql("UPDATE atlas_mensagem SET criada_em = now() - make_interval(days => :d) WHERE conversa_id = :c")
                .param("d", dias).param("c", conversaId).update();
        jdbc.sql("UPDATE atlas_relatorio SET criado_em = now() - make_interval(days => :d) WHERE conversa_id = :c")
                .param("d", dias).param("c", conversaId).update();
    }

    @Test
    void relatorioParadoHaMaisDeSeteDiasEDescartadoAoAbrirAConversa() {
        ConversaDto aberta = abrirComOBotao();
        ItemCartao cartao = cartaoDe(aberta);
        envelhecer(aberta.conversaId(), 8);

        ConversaDto inteira = atlas.verConversa(marca(), aberta.conversaId());

        assertThat(status("atlas_relatorio", cartao.relatorioId())).isEqualTo("DESCARTADO");
        assertThat(status("atlas_solicitacao", cartao.solicitacaoId())).isEqualTo("CANCELADA");
        assertThat(tipos(inteira)).containsExactly("usuario", "texto", "cartao", "aviso");
        assertThat(((ItemCartao) inteira.itens().get(2)).status()).isEqualTo("CANCELADA");
        confereQueToolCallTemResposta(aberta.conversaId(), 0);
        assertThat(atlas.verRelatorio(marca(), cartao.relatorioId()).status()).isEqualTo("DESCARTADO");
    }

    @Test
    void relatorioParadoEDescartadoTambemAoListarAsConversas() {
        ConversaDto aberta = abrirComOBotao();
        long relatorioId = cartaoDe(aberta).relatorioId();
        envelhecer(aberta.conversaId(), 30);

        var lista = atlas.listarConversas(marca(), amarelo());

        assertThat(lista.conversas().get(0).relatorio().status()).isEqualTo("DESCARTADO");
        assertThat(status("atlas_relatorio", relatorioId)).isEqualTo("DESCARTADO");
    }

    @Test
    void relatorioMexidoHaMenosDeSeteDiasNaoEDescartadoENemOPronto() {
        ConversaDto recente = abrirComOBotao();
        envelhecer(recente.conversaId(), 6);
        ConversaDto antiga = montarComTodosOsDados();
        envelhecer(antiga.conversaId(), 40);

        atlas.listarConversas(marca(), amarelo());

        assertThat(status("atlas_relatorio", cartaoDe(recente).relatorioId())).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(status("atlas_relatorio", ((ItemRelatorioPronto) antiga.itens().get(1)).relatorioId())).isEqualTo("PRONTO");
    }

    @Test
    void depoisDoDescarteOBotaoComecaUmRelatorioNovo() {
        ConversaDto aberta = abrirComOBotao();
        envelhecer(aberta.conversaId(), 8);
        atlas.verConversa(marca(), aberta.conversaId());

        ConversaDto nova = atlas.enviarMensagem(marca(), aberta.conversaId(), BOTAO_NA_CONVERSA, requisicao());

        assertThat(tipos(nova)).containsExactly("usuario", "texto", "cartao");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", aberta.conversaId())).isEqualTo(2);
        assertThat(cartaoDe(nova).relatorioId()).isNotEqualTo(cartaoDe(aberta).relatorioId());
    }

    // ---------------------------------------------------------------------
    // Contexto e modelo
    // ---------------------------------------------------------------------

    @Test
    void oContextoVemDaSessaoENaoDeArgumento() {
        var contexto = fabrica.para(marca(), amarelo(), 1, requisicao());

        assertThat(contexto.cota()).isEqualTo("MASTER");
        assertThat(contexto.abasAbertas()).containsExactlyInAnyOrder("PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO",
                "PESQUISA", "CONTRATO");
        var doRoxo = fabrica.para(usuario(jdbc, ROXO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo"), 1, requisicao());
        assertThat(doRoxo.cota()).isEqualTo("SUBCOTA_G");
        assertThat(doRoxo.abaAberta("PESQUISA")).as("a pesquisa é só da Master").isFalse();
        assertThat(doRoxo.abaAberta("APLICACOES")).isTrue();
        assertThatThrownBy(() -> fabrica.para(usuario(jdbc, ROXO), amarelo(), 1, requisicao())).isInstanceOf(NaoEncontradoException.class);
        fecharAbaDeAplicacoes();
        assertThat(fabrica.para(marca(), amarelo(), 1, requisicao()).abaAberta("APLICACOES")).isFalse();
        assertThat(fabrica.para(usuario(jdbc, RBS), amarelo(), 1, requisicao()).abasAbertas()).as("a RBS vê todas").hasSize(7);
    }

    @Test
    void asChavesDoModeloEstaoNoCatalogoDaAba() {
        var catalogo = jdbc.sql("SELECT c.chave FROM campo_catalogo c JOIN aba a ON a.id = c.aba_id WHERE a.codigo = 'APLICACOES'")
                .query(String.class).list();
        var modelo = new ModeloBase().obter("ATIVACAO_EVENTO").orElseThrow();

        assertThat(catalogo).containsAll(modelo.chavesDeLacuna());
        modelo.calculos().values().forEach(c -> c.variaveis().values().forEach(v -> {
            assertThat(catalogo).contains(Referencias.nome(v.ref()));
        }));
    }

    @Test
    void asOperacoesQueViramFerramentaSoRecebemOContextoENuncaAParceria() {
        // A etapa seguinte embrulha estas operações como ferramentas @Tool: o modelo não escolhe parceria.
        List<String> operacoes = List.of("iniciar", "solicitarCampos", "solicitarCamposDoBotao", "responder", "preencherFrasesModelo",
                "finalizar", "lacunas");
        for (var metodo : RelatorioAtlasService.class.getDeclaredMethods()) {
            if (operacoes.contains(metodo.getName())) {
                assertThat(metodo.getParameterTypes()[0]).as(metodo.getName()).isEqualTo(AtlasContexto.class);
                assertThat(metodo.getParameterTypes()).as(metodo.getName()).doesNotContain(UsuarioAutenticado.class);
            }
        }
    }
}
