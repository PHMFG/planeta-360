package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.FerramentaDeTeste.Comportamento;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [ATLAS] O que só aparece SEM uma transação envolvendo o teste: nenhuma transação aberta durante a ida ao modelo (cada gravação
 * e cada ferramenta confirmam sozinhas) e o turno visível a quem chega no meio da conversa (o 409). Por isso esta classe NÃO é
 * {@code @Transactional}: tudo o que ela faz é confirmado no banco de verdade, e o que ficou é apagado no fim, tabela por tabela
 * (o histórico, os dados informados e o relatório pronto são imutáveis por gatilho; o gatilho é desligado só para esta limpeza,
 * dentro de um try/finally que o liga de novo). Os testes de comportamento do loop estão em LoopDoAgenteTest.
 */
@TesteComLlmRoteirizado
@Import({FerramentaDeTesteConfig.class, EmbeddingsControlavelConfig.class})
class LoopSemTransacaoTest {

    /** Da que depende das outras para a que não depende de ninguém, e os gatilhos de imutabilidade de cada uma. */
    private static final Map<String, String> TABELAS_E_GATILHOS = new LinkedHashMap<>();

    static {
        TABELAS_E_GATILHOS.put("atlas_traco", null);
        TABELAS_E_GATILHOS.put("atlas_fonte", null);
        TABELAS_E_GATILHOS.put("atlas_decisao", null);
        TABELAS_E_GATILHOS.put("atlas_tool_call", null);
        TABELAS_E_GATILHOS.put("atlas_calculo", null);
        TABELAS_E_GATILHOS.put("atlas_solicitacao", null);
        TABELAS_E_GATILHOS.put("atlas_relatorio", "rn_atlas_relatorio_congelado");
        TABELAS_E_GATILHOS.put("atlas_mensagem", null);
        TABELAS_E_GATILHOS.put("atlas_conversa", null);
        TABELAS_E_GATILHOS.put("dado_informado", "rn_dado_informado_imutavel");
        TABELAS_E_GATILHOS.put("historico", "rn_historico_imutavel");
    }

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FerramentaDeTeste ferramentaDeTeste;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    EmbeddingsControlavel embeddings;

    private final Map<String, Long> ultimoIdAntes = new LinkedHashMap<>();

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        ferramentaDeTeste.limpar();
        embeddings.limpar();
        TABELAS_E_GATILHOS.keySet().forEach(tabela -> ultimoIdAntes.put(tabela,
                jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela).query(Long.class).single()));
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        provedorAtual.esquecerChaveDoDebug();
        TABELAS_E_GATILHOS.forEach((tabela, gatilho) -> {
            if (gatilho != null) {
                jdbc.sql("ALTER TABLE " + tabela + " DISABLE TRIGGER " + gatilho).update();
            }
            try {
                jdbc.sql("DELETE FROM " + tabela + " WHERE id > :ultimo").param("ultimo", ultimoIdAntes.get(tabela)).update();
            } finally {
                if (gatilho != null) {
                    jdbc.sql("ALTER TABLE " + tabela + " ENABLE TRIGGER " + gatilho).update();
                }
            }
        });
        TABELAS_E_GATILHOS.keySet().forEach(tabela -> assertThat(jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela)
                .query(Long.class).single()).as("a limpeza deixou sobra em " + tabela).isEqualTo(ultimoIdAntes.get(tabela)));
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    @Test
    void nenhumaTransacaoFicaAbertaDuranteAIdaAoModeloEOTurnoApareceParaQuemChegaNoMeio() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        String edicao = AtlasJson.escrever(Map.of("operacoes", List.of(
                Map.of("op", "definir_texto", "bloco", "b-resumo", "texto", LoopDoAgenteTest.RESUMO),
                Map.of("op", "definir_texto", "bloco", "b-publico", "texto", LoopDoAgenteTest.PUBLICO))));

        modelo.chamada(LoopDoAgenteTest.FINALIZAR, "{}")   // erro de ferramenta no meio: o que ela fez desfaz, a resposta é gravada
                .chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                // no meio da ida ao modelo, outra mensagem na mesma conversa encontra o turno em andamento (confirmado de verdade)
                .passo(pedido -> {
                    assertThatThrownBy(() -> atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Oi?", null, null), requisicao()))
                            .isInstanceOf(ConflitoException.class)
                            .hasMessage("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");
                    return new RespostaDoLlm(null, List.of(ProvedorRoteirizado.pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)), null);
                })
                .chamada(LoopDoAgenteTest.EDITAR, edicao)
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}");

        ConversaDto aberta = atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Monte o relatório.", null, null), requisicao());
        ItemCartao cartao = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        // o cartão aberto já está confirmado no banco, visto por outra conexão, e o turno terminou
        assertThat(jdbc.sql("SELECT status FROM atlas_solicitacao WHERE id = :id").param("id", cartao.solicitacaoId())
                .query(String.class).single()).isEqualTo("PENDENTE");
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", conversa)
                .query(Boolean.class).single()).isTrue();

        ConversaDto pronta = atlas.responderCartao(pessoa, cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("accept",
                valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());

        assertThat(pronta.itens()).extracting(i -> i.getClass().getSimpleName()).containsExactly("ItemRelatorioPronto");
        assertThat(modelo.pedidos()).hasSize(5);
        assertThat(modelo.transacaoAtivaEmCadaPedido()).as("transação aberta em cada ida ao modelo").containsOnly(false);
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("PRONTO");
        // a primeira ferramenta falhou (não havia relatório): a falha foi gravada como resposta, sem deixar nada pela metade
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c "
                + "AND t.erro IS NOT NULL").param("c", conversa).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .isEqualTo(1);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    // ---------------------------------------------------------------------
    // Ferramenta em duas fases (M4): o preparo, sem transação; a execução, dentro de uma
    // ---------------------------------------------------------------------

    @Test
    void aFerramentaPreparaSemTransacaoEExecutaDentroDeUma() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Tudo certo.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Rode a ferramenta.", null, null), requisicao());

        assertThat(ferramentaDeTeste.registros()).extracting(FerramentaDeTeste.Registro::fase).containsExactly("preparar", "executar");
        assertThat(ferramentaDeTeste.registros().get(0).transacaoAberta()).as("transação aberta na fase 1 (preparar)").isFalse();
        assertThat(ferramentaDeTeste.registros().get(1).transacaoAberta()).as("transação aberta na fase 2 (executar)").isTrue();
        assertThat(jdbc.sql("SELECT resultado ->> 'preparado' FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id "
                + "WHERE m.conversa_id = :c").param("c", conversa).query(String.class).single()).isEqualTo("preparado");
        assertThat(modelo.transacaoAtivaEmCadaPedido()).containsOnly(false);
    }

    @Test
    void falhaNoPreparoVoltaAoModeloComoErroSemChegarAExecucao() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        ferramentaDeTeste.comportamento(Comportamento.FALHAR_NO_PREPARO);
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Não deu.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Rode a ferramenta.", null, null), requisicao());

        assertThat(ferramentaDeTeste.registros()).extracting(FerramentaDeTeste.Registro::fase).containsExactly("preparar");
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single()).contains("O preparo da ferramenta de teste falhou.");
        assertThat(jdbc.sql("SELECT erro FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).single()).isEqualTo("O preparo da ferramenta de teste falhou.");
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    @Test
    void falhaNaExecucaoDesfazOQueELaFezEAFalhaVoltaAoModelo() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        ferramentaDeTeste.comportamento(Comportamento.FALHAR_NA_EXECUCAO);
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Não deu.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Rode a ferramenta.", null, null), requisicao());

        assertThat(ferramentaDeTeste.registros()).extracting(FerramentaDeTeste.Registro::fase).containsExactly("preparar", "executar");
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single()).contains("A execução da ferramenta de teste falhou.");
        assertThat(jdbc.sql("SELECT resultado IS NULL FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id "
                + "WHERE m.conversa_id = :c").param("c", conversa).query(Boolean.class).single()).isTrue();
    }

    @Test
    void falhaNaGravacaoDoResultadoNaoDeixaOModeloCitarOMarcadorQueNuncaRecebeu() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        // um trecho real, com a data: o texto abaixo só passaria na checagem se o marcador T1 existisse no turno
        Map<String, Object> trecho = jdbc.sql("""
                SELECT t.id, t.documento_id, d.titulo, t.versao, t.pagina_inicio, t.secao_caminho, t.texto
                FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                WHERE t.ativo AND t.texto LIKE '%04/12/2026%' ORDER BY t.id LIMIT 1
                """).query().singleRow();
        ferramentaDeTeste.fonteQueNaoGrava(new FerramentaDeTeste.TrechoDeTeste(((Number) trecho.get("id")).longValue(),
                ((Number) trecho.get("documento_id")).longValue(), (String) trecho.get("titulo"), ((Number) trecho.get("versao")).intValue(),
                ((Number) trecho.get("pagina_inicio")).intValue(), (String) trecho.get("secao_caminho"), (String) trecho.get("texto")));
        // o modelo recebe um erro, e não o trecho; mesmo assim cita o T1 que adivinhou (a numeração é previsível)
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Os geradores devem ser informados até 04/12/2026 [T1].")
                .texto("Não consegui consultar isso agora.");

        ConversaDto resposta = atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Qual o prazo?", null, null), requisicao());

        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("erro").doesNotContain("04/12/2026");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c "
                + "AND t.erro IS NOT NULL").param("c", conversa).query(Long.class).single()).isEqualTo(1);
        // o T1 nunca chegou ao modelo: a citação é recusada (uma nova redação), e nada é gravado como fonte
        assertThat(modelo.pedidos()).hasSize(3);
        MensagemDeChat pedidoDeRedacao = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        assertThat(pedidoDeRedacao.conteudo()).contains("recusada").contains("[T1]").contains("não existe");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
        assertThat(resposta.itens().stream().filter(AtlasDtos.ItemTexto.class::isInstance).map(AtlasDtos.ItemTexto.class::cast)
                .map(AtlasDtos.ItemTexto::texto).toList()).containsExactly("Não consegui consultar isso agora.");
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    /** O modelo lento e seguro para várias threads: pede a ferramenta e, com o resultado na mão, responde. Anota a transação de cada ida. */
    static final class ModeloLento implements ProvedorDeLlm {

        final List<Boolean> transacaoAtiva = new CopyOnWriteArrayList<>();
        final long demoraEmMs;
        final String ferramenta;
        final String argumentos;

        ModeloLento(long demoraEmMs) {
            this(demoraEmMs, FerramentaDeTeste.NOME, "{}");
        }

        ModeloLento(long demoraEmMs, String ferramenta, String argumentos) {
            this.demoraEmMs = demoraEmMs;
            this.ferramenta = ferramenta;
            this.argumentos = argumentos;
        }

        @Override
        public RespostaDoLlm responder(PedidoAoLlm pedido) {
            transacaoAtiva.add(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                TimeUnit.MILLISECONDS.sleep(demoraEmMs);
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
            MensagemDeChat ultima = pedido.mensagens().get(pedido.mensagens().size() - 1);
            if ("tool".equals(ultima.papel())) {
                return new RespostaDoLlm("Pronto.", List.of(), null);
            }
            return new RespostaDoLlm(null, List.of(ProvedorRoteirizado.pedido(ferramenta, argumentos)), null);
        }
    }

    @Test
    void dozeTurnosSimultaneosComModeloEFerramentaLentosTerminamSemTimeoutDeConexao() throws Exception {
        UsuarioAutenticado pessoa = marca();
        ModeloLento lento = new ModeloLento(300);
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(lento, "http://127.0.0.1", "modelo-lento", false, "xxxx", null, null));
        ferramentaDeTeste.demora(300);
        List<Long> conversas = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            conversas.add(atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId());
        }
        ExecutorService threads = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> turnos = new ArrayList<>();
            for (long conversa : conversas) {
                turnos.add(threads.submit(() -> {
                    atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Rode.", null, null), requisicao());
                    return true;
                }));
            }
            for (Future<Boolean> turno : turnos) {
                assertThat(turno.get(40, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(lento.transacaoAtiva).as("idas ao modelo").hasSize(24).containsOnly(false);
        assertThat(ferramentaDeTeste.registros().stream().filter(r -> r.fase().equals("preparar"))).hasSize(12)
                .allSatisfy(r -> assertThat(r.transacaoAberta()).isFalse());
        for (long conversa : conversas) {
            assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem DESC LIMIT 1")
                    .param("c", conversa).query(String.class).single()).isEqualTo("Pronto.");
            assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa).query(Boolean.class)
                    .single()).isTrue();
        }
    }

    // ---------------------------------------------------------------------
    // buscar_documentos: o embedding é pedido sem transação, e sem embeddings a busca segue
    // ---------------------------------------------------------------------

    private String resultadoDaBusca(long conversa) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem LIMIT 1")
                .param("c", conversa).query(String.class).single();
    }

    @Test
    void oEmbeddingDaConsultaEPedidoSemTransacaoENaoSegura() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        modelo.chamada("buscar_documentos", "{\"consulta\":\"prazo para informar os geradores\"}")
                .chamada("buscar_documentos", "{\"consulta\":\"tarifa de energia\"}").texto("Veja o manual.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Prazo dos geradores e energia?", null, null), requisicao());

        assertThat(embeddings.consultas()).containsExactly("prazo para informar os geradores", "tarifa de energia");
        assertThat(embeddings.transacaoNaConsulta()).as("transação aberta em cada pedido de embedding").containsOnly(false);
        assertThat(modelo.transacaoAtivaEmCadaPedido()).containsOnly(false);
        assertThat(resultadoDaBusca(conversa)).contains("04/12/2026");
    }

    @Test
    void semEmbeddingsABuscaSegueSoComAListaTextualEAindaAchaOTrecho() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        embeddings.desligado(true);
        modelo.chamada("buscar_documentos", "{\"consulta\":\"Até quando preciso informar os geradores na Arena\"}").texto("Veja o manual.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Até quando informo os geradores?", null, null), requisicao());

        assertThat(embeddings.consultas()).as("desligado: o provedor nem é chamado").isEmpty();
        assertThat(resultadoDaBusca(conversa)).contains("04/12/2026").contains("Manual de Operações 2027");
    }

    @Test
    void seOEmbeddingFalhaOTurnoNaoCaiEABuscaSegueSoComATextual() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        embeddings.falharNaConsulta(true);
        modelo.chamada("buscar_documentos", "{\"consulta\":\"prazo para informar os geradores\"}").texto("Veja o manual.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Prazo dos geradores?", null, null), requisicao());

        assertThat(embeddings.transacaoNaConsulta()).containsExactly(false);
        assertThat(resultadoDaBusca(conversa)).contains("04/12/2026").doesNotContain("\"erro\"");
        assertThat(jdbc.sql("SELECT erro IS NULL FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Boolean.class).single()).isTrue();
    }

    @Test
    void semEmbeddingsUmaConsultaSemNenhumTrechoQueResponderVoltaVaziaEComUmAviso() {
        UsuarioAutenticado pessoa = marca();
        long conversa = atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId();
        embeddings.desligado(true);
        modelo.chamada("buscar_documentos", "{\"consulta\":\"zzzqqq xyzzy\"}").texto("Veja o manual.");

        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Quanto custa o zzzqqq?", null, null), requisicao());

        assertThat(resultadoDaBusca(conversa)).contains("\"trechos\":[]").contains("Nenhum trecho dos documentos do portal responde");
    }

    @Test
    void dozeTurnosSimultaneosComEmbeddingELlmLentosBuscandoDeVerdadeTerminamSemTimeoutDeConexao() throws Exception {
        UsuarioAutenticado pessoa = marca();
        ModeloLento lento = new ModeloLento(300, "buscar_documentos", "{\"consulta\":\"prazo para informar os geradores\"}");
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(lento, "http://127.0.0.1", "modelo-lento", false, "xxxx", null, null));
        embeddings.demora(300);
        List<Long> conversas = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            conversas.add(atlas.abrirConversa(pessoa, amarelo(), null, requisicao()).conversaId());
        }
        ExecutorService threads = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> turnos = new ArrayList<>();
            for (long conversa : conversas) {
                turnos.add(threads.submit(() -> {
                    atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Rode.", null, null), requisicao());
                    return true;
                }));
            }
            for (Future<Boolean> turno : turnos) {
                assertThat(turno.get(40, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(lento.transacaoAtiva).as("idas ao modelo").hasSize(24).containsOnly(false);
        assertThat(embeddings.transacaoNaConsulta()).as("pedidos de embedding").hasSize(12).containsOnly(false);
        for (long conversa : conversas) {
            assertThat(resultadoDaBusca(conversa)).contains("04/12/2026");
            assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem DESC LIMIT 1")
                    .param("c", conversa).query(String.class).single()).isEqualTo("Pronto.");
        }
    }
}
