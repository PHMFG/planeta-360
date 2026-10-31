package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.etapas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.exigirCoerencia;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.resultados;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.todas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDaConversa;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDoTurno;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] O diagnóstico (M2): as etapas que cada turno do loop roteirizado deixa em {@code atlas_traco}, inclusive nos erros. Com
 * {@code atlas.diagnostico.ligado=true}, cada turno tem G1_LIMITES, uma LLM_IDA por ida ao modelo, uma FERRAMENTA por pedido, a
 * CHECAGEM do texto, o RESUMO do raciocínio (em paralelo) e a SAIDA; recusa, falha do modelo, estouro do limite de chamadas, o teto
 * diário (429), a conversa ocupada (409) e a exceção no meio do turno deixam o traço do que aconteceu. Em todos, as etapas
 * sequenciais cabem no turno e a soma delas não passa do tempo dele (seção 8.4 do plano).
 * <p>
 * Esta classe NÃO é {@code @Transactional}: o resumo do raciocínio é gravado por outra thread, e o traço do resumo atrasado é
 * atualizado depois do fim do turno. O que o teste confirma no banco é apagado no fim, como no LoopSemTransacaoTest.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class DiagnosticoDoTurnoTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    AtlasRepositorio repositorio;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long abrir() {
        return atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    List<Long> mensagensDoAssistente(long conversa) {
        return jdbc.sql("SELECT id FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem")
                .param("c", conversa).query(Long.class).list();
    }

    long usadasHoje() {
        return repositorio.mensagensDoModeloHoje(amarelo());
    }

    // ---------------------------------------------------------------------
    // As etapas de um turno que dá certo
    // ---------------------------------------------------------------------

    @Test
    void nadaVaiAoBancoNemFicaTransacaoAbertaDuranteAChamadaAoProvedor() {
        long conversa = abrir();
        List<Long> linhasDuranteAIda = new ArrayList<>();
        // o traço só vai ao banco no fim do turno: durante cada ida ao modelo, o banco ainda não tem uma etapa sequer
        modelo.passo(pedido -> {
            linhasDuranteAIda.add(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c").param("c", conversa)
                    .query(Long.class).single());
            return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm(null,
                    List.of(pedido(LoopDoAgenteTest.CONSULTAR, "{}")), null);
        });
        modelo.passo(pedido -> {
            linhasDuranteAIda.add(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c").param("c", conversa)
                    .query(Long.class).single());
            return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm("Os dados estão no portal.", List.of(), null);
        });

        falar(conversa, "Como estou?");

        assertThat(linhasDuranteAIda).containsExactly(0L, 0L);
        assertThat(modelo.transacaoAtivaEmCadaPedido()).as("transação aberta em cada ida ao modelo").containsOnly(false);
        assertThat(tracoDoTurno(jdbc, conversa)).hasSize(6);   // e no fim do turno, de uma vez, ele está lá
    }

    @Test
    void semListCostOCustoVemDoPrecoConfiguradoPorMilhaoDeTokens() {
        long conversa = abrir();
        modelo.textoComUso("Posso ajudar.", 1000, 500, null);

        falar(conversa, "Oi");

        // 1.000 x 0,30 + 500 x 1,20 = 900 por milhão de tokens = US$ 0,0009 (os padrões do application.yml)
        Linha ida = primeira(tracoDoTurno(jdbc, conversa), "LLM_IDA");
        assertThat(ida.dados()).containsEntry("tokensEntrada", 1000).containsEntry("tokensSaida", 500)
                .containsEntry("custoOrigem", "PRECO_CONFIGURADO").containsEntry("modelo", "modelo-do-roteiro");
        assertThat(new java.math.BigDecimal(String.valueOf(ida.dados().get("custoUsd")))).isEqualByComparingTo("0.0009");
    }

    @Test
    void turnoSoDeTextoTemLimitesIdaChecagemESaida() {
        long conversa = abrir();
        long usadasAntes = usadasHoje();
        modelo.texto("Posso ajudar com os dados da sua parceria.");

        ConversaDto resposta = falar(conversa, "Oi");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "CHECAGEM", "SAIDA");
        assertThat(resultados(traco)).containsOnly("OK");
        exigirCoerencia(traco);
        long mensagem = mensagensDoAssistente(conversa).get(0);
        assertThat(primeira(traco, "G1_LIMITES").dados()).containsEntry("tetoPorParceria", 200).containsEntry("usadasHoje", (int) usadasAntes)
                .doesNotContainKey("motivo");
        assertThat(primeira(traco, "G1_LIMITES").mensagemId()).isNull();
        Linha ida = primeira(traco, "LLM_IDA");
        assertThat(ida.mensagemId()).isEqualTo(mensagem);
        // o roteiro dos testes não mede tokens: só o que o loop sabe (o nome do modelo é o do provedor, que o roteiro não tem)
        assertThat(ida.dados()).containsEntry("ida", 1).containsEntry("emFluxo", false).containsEntry("mensagens", 2)
                .containsEntry("chamadas", 0).containsEntry("comTexto", true).containsEntry("comRaciocinio", false)
                .containsEntry("ferramentas", List.of("parceria_consultar", "relatorio_iniciar")).doesNotContainKey("tokensEntrada")
                .doesNotContainKey("novaRedacao");
        assertThat(primeira(traco, "CHECAGEM").dados()).containsEntry("origem", "RESPOSTA").containsEntry("aprovado", true)
                .containsEntry("caracteres", "Posso ajudar com os dados da sua parceria.".length());
        Linha saida = primeira(traco, "SAIDA");
        assertThat(saida.mensagemId()).isEqualTo(mensagem);
        assertThat(saida.dados()).containsEntry("tipo", "TEXTO").containsEntry("idasAoModelo", 1).containsEntry("chamadasDeFerramenta", 0)
                .containsEntry("resumosPendentes", 0).containsKey("esperaDosResumosMs").doesNotContainKey("fluxo");
        // os itens do assistente trazem o id do turno (a marca vê o diagnóstico das próprias conversas)
        assertThat(resposta.diagnosticoDisponivel()).isTrue();
        assertThat(resposta.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::turno))
                .containsExactly(traco.get(0).turno().toString());
    }

    @Test
    void turnoComFerramentaTemIdaFerramentaIdaChecagemESaida() {
        long conversa = abrir();
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}").texto("Os números estão no portal.");

        falar(conversa, "Como estou?");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "FERRAMENTA", "LLM_IDA", "CHECAGEM", "SAIDA");
        assertThat(resultados(traco)).containsOnly("OK");
        exigirCoerencia(traco);
        List<Long> mensagens = mensagensDoAssistente(conversa);
        Linha primeiraIda = todas(traco, "LLM_IDA").get(0);
        assertThat(primeiraIda.mensagemId()).isEqualTo(mensagens.get(0));
        assertThat(primeiraIda.dados()).containsEntry("chamadas", 1).containsEntry("comTexto", false).containsEntry("mensagens", 2);
        assertThat(todas(traco, "LLM_IDA").get(1).dados()).containsEntry("ida", 2).containsEntry("mensagens", 4)
                .containsEntry("ferramentas", List.of("parceria_consultar", "relatorio_iniciar"));
        Linha ferramenta = primeira(traco, "FERRAMENTA");
        assertThat(ferramenta.mensagemId()).isEqualTo(mensagens.get(0));
        assertThat(ferramenta.dados()).containsEntry("ferramenta", "parceria_consultar").containsEntry("argumentos", "{}")
                .containsKey("resultado");
        assertThat((String) ferramenta.dados().get("resultado")).contains("\"dados\"");
        assertThat(todas(traco, "LLM_IDA").get(1).mensagemId()).isEqualTo(mensagens.get(1));
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("idasAoModelo", 2).containsEntry("chamadasDeFerramenta", 1);
    }

    @Test
    void ferramentaQueDaErroFicaComoErroNoTracoEOTurnoSegue() {
        long conversa = abrir();
        modelo.chamada(LoopDoAgenteTest.FINALIZAR, "{}").texto("Ainda não há um relatório em montagem.");

        falar(conversa, "Finalize.");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "FERRAMENTA", "LLM_IDA", "CHECAGEM", "SAIDA");
        assertThat(resultados(traco)).containsExactly("OK", "OK", "ERRO", "OK", "OK", "OK");
        assertThat((String) primeira(traco, "FERRAMENTA").dados().get("resultado")).contains("\"erro\"");
        exigirCoerencia(traco);
    }

    // ---------------------------------------------------------------------
    // O que dá errado
    // ---------------------------------------------------------------------

    @Test
    void textoRecusadoDuasVezesDeixaAsDuasChecagensRecusadasEASaidaRecusada() {
        long conversa = abrir();
        modelo.texto("Foram 12 aplicações comprovadas.").texto("Foram 13 aplicações comprovadas.");

        falar(conversa, "Quantas aplicações?");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "CHECAGEM", "LLM_IDA", "CHECAGEM", "SAIDA");
        assertThat(resultados(traco)).containsExactly("OK", "OK", "RECUSADO", "OK", "RECUSADO", "RECUSADO");
        exigirCoerencia(traco);
        assertThat(todas(traco, "LLM_IDA").get(1).dados()).containsEntry("novaRedacao", true);
        assertThat((String) todas(traco, "CHECAGEM").get(0).dados().get("motivo")).contains("algarismo");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "TEXTO_RECUSADO");
    }

    @Test
    void falhaDoModeloDeixaAIdaComErroEASaidaComErro() {
        long conversa = abrir();
        modelo.falha();

        falar(conversa, "Oi");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "SAIDA");
        assertThat(resultados(traco)).containsExactly("OK", "ERRO", "ERRO");
        assertThat(primeira(traco, "LLM_IDA").dados()).containsEntry("erro", "ProvedorIndisponivelException")
                .containsEntry("detalhe", "o provedor do teste não respondeu").doesNotContainKey("comTexto");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "FALHA_DO_MODELO");
        assertThat(primeira(traco, "SAIDA").mensagemId()).isEqualTo(mensagensDoAssistente(conversa).get(0));
        exigirCoerencia(traco);
    }

    @Test
    void nonaChamadaDeFerramentaFicaRecusadaEOTurnoTerminaComALinhaDoLimite() {
        long conversa = abrir();
        List<ChamadaDeFerramenta> nove = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nove.add(pedido(LoopDoAgenteTest.CONSULTAR, "{}"));
        }
        modelo.chamadas(null, null, nove.toArray(new ChamadaDeFerramenta[0]));

        falar(conversa, "Consulte tudo.");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "FERRAMENTA", "FERRAMENTA", "FERRAMENTA", "FERRAMENTA",
                "FERRAMENTA", "FERRAMENTA", "FERRAMENTA", "FERRAMENTA", "FERRAMENTA", "SAIDA");
        assertThat(todas(traco, "FERRAMENTA")).extracting(Linha::resultado).containsExactly("OK", "OK", "OK", "OK", "OK", "OK", "OK", "OK",
                "RECUSADO");
        assertThat(todas(traco, "FERRAMENTA").get(8).dados()).containsEntry("motivo", "LIMITE_DE_CHAMADAS");
        assertThat(primeira(traco, "SAIDA").resultado()).isEqualTo("RECUSADO");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("motivo", "LIMITE_DE_CHAMADAS").containsEntry("chamadasDeFerramenta", 9);
        exigirCoerencia(traco);
    }

    @Test
    void excecaoNoMeioDoTurnoGravaOErroESaidaInterrompidaESoltaATrava() {
        long conversa = abrir();
        // o roteiro acabou: o provedor de teste lança um AssertionError, que o loop não trata (não é falha do modelo)

        assertThatThrownBy(() -> falar(conversa, "Oi")).isInstanceOf(AssertionError.class);

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "ERRO", "SAIDA");
        assertThat(resultados(traco)).containsExactly("OK", "ERRO", "ERRO");
        assertThat(primeira(traco, "ERRO").dados()).containsEntry("excecao", "AssertionError");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "INTERROMPIDO");
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).as("o turno terminou também em erro").isTrue();
    }

    @Test
    void tetoDiarioAtingidoDeixaOTracoDaRecusaEPoupaOModelo() {
        long conversa = abrir();
        long outra = abrir();
        jdbc.sql("INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) SELECT :c, g, 'assistant', 'x' FROM generate_series(1, 200) g")
                .param("c", outra).update();

        assertThatThrownBy(() -> falar(conversa, "Oi")).isInstanceOf(LimiteDeUsoException.class);

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "SAIDA");
        assertThat(resultados(traco)).containsExactly("RECUSADO", "RECUSADO");
        assertThat(primeira(traco, "G1_LIMITES").dados()).containsEntry("motivo", "TETO_DIARIO").containsEntry("tetoPorParceria", 200);
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "RECUSADO_ANTES_DO_TURNO").containsEntry("motivo", "TETO_DIARIO");
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void conversaOcupadaDeixaOTracoDaRecusaDoTurnoEmAndamento() {
        long conversa = abrir();
        assertThat(repositorio.iniciarTurno(conversa)).isTrue();   // outro turno está rodando
        try {
            assertThatThrownBy(() -> falar(conversa, "Oi")).isInstanceOf(ConflitoException.class);
        } finally {
            repositorio.encerrarTurno(conversa);
        }

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "SAIDA");
        assertThat(resultados(traco)).containsExactly("RECUSADO", "RECUSADO");
        assertThat(primeira(traco, "G1_LIMITES").dados()).containsEntry("motivo", "TURNO_EM_ANDAMENTO");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("motivo", "TURNO_EM_ANDAMENTO");
    }

    // ---------------------------------------------------------------------
    // O resumo do raciocínio: em paralelo, e pendente se o turno acaba antes
    // ---------------------------------------------------------------------

    @Test
    void resumoDoRaciocinioEUmaEtapaEmParaleloLigadaAMensagemDele() {
        long conversa = abrir();
        modelo.chamadaComRaciocinio("Preciso consultar os dados.", LoopDoAgenteTest.CONSULTAR, "{}").texto("Os dados estão no portal.");

        falar(conversa, "Como estou?");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "RESUMO", "FERRAMENTA", "LLM_IDA", "CHECAGEM", "SAIDA");
        Linha resumo = primeira(traco, "RESUMO");
        assertThat(resumo.resultado()).isEqualTo("OK");
        assertThat(resumo.mensagemId()).isEqualTo(mensagensDoAssistente(conversa).get(0));
        assertThat(resumo.dados()).containsEntry("caracteres", ProvedorRoteirizado.RESUMO_PADRAO.length()).doesNotContainKey("pendente");
        assertThat(primeira(traco, "LLM_IDA").dados()).containsEntry("comRaciocinio", true);
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("resumosPendentes", 0);
        exigirCoerencia(traco);   // o resumo fica fora da soma das etapas sequenciais
    }

    @Test
    void resumoQueNaoTerminaATempoSaiPendenteESeAtualizaQuandoFicaPronto() throws Exception {
        long conversa = abrir();
        CountDownLatch liberar = new CountDownLatch(1);
        modelo.resumindoCom(pedidoDeResumo -> {
            try {
                liberar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrompida) {
                Thread.currentThread().interrupt();
            }
            return "Conferi os dados e respondi.";
        });
        modelo.chamadaComRaciocinio("Preciso consultar.", LoopDoAgenteTest.CONSULTAR, "{}").texto("Pronto, os dados estão aí.");

        falar(conversa, "Como estou?");

        // o turno terminou sem esperar o resumo (prazo de 0,5 s): a linha dele está pendente
        List<Linha> noFim = tracoDoTurno(jdbc, conversa);
        Linha pendente = primeira(noFim, "RESUMO");
        assertThat(pendente.resultado()).isEqualTo("PULADO");
        assertThat(pendente.dados()).containsEntry("pendente", true);
        assertThat(primeira(noFim, "SAIDA").dados()).containsEntry("resumosPendentes", 1);
        exigirCoerencia(noFim);

        // fica pronto depois: a mesma linha passa a OK, sem o aviso de pendente, com a duração de verdade
        liberar.countDown();
        Linha pronta = null;
        for (int tentativa = 0; tentativa < 100 && (pronta == null || !"OK".equals(pronta.resultado())); tentativa++) {
            Thread.sleep(50);
            pronta = primeira(tracoDaConversa(jdbc, conversa), "RESUMO");
        }
        assertThat(pronta.resultado()).isEqualTo("OK");
        assertThat(pronta.id()).isEqualTo(pendente.id());
        assertThat(pronta.dados()).doesNotContainKey("pendente").containsEntry("noPrazo", false);
        assertThat(pronta.duracaoMs()).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.sql("SELECT resumo_do_raciocinio FROM atlas_mensagem WHERE id = :m").param("m", pronta.mensagemId())
                .query(String.class).single()).isEqualTo("Conferi os dados e respondi.");
    }

    // ---------------------------------------------------------------------
    // Em fluxo
    // ---------------------------------------------------------------------

    @Test
    void turnoEmFluxoDeixaOQueOFluxoTransmitiuNaSaidaEOIdDoTurnoNoFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.textoEmPedacos("Vou responder.", "Posso ", "ajudar ", "você.");

        MvcTestResult resposta = falarEmFluxo(pessoa, conversa, "Oi");

        List<Linha> traco = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(traco)).containsExactly("G1_LIMITES", "LLM_IDA", "CHECAGEM", "RESUMO", "SAIDA");
        assertThat(resultados(traco)).containsOnly("OK");
        assertThat(primeira(traco, "LLM_IDA").dados()).containsEntry("emFluxo", true);
        assertThat(primeira(traco, "SAIDA").dados().get("fluxo")).isInstanceOfSatisfying(Map.class, fluxo -> {
            assertThat(fluxo).containsEntry("clienteSaiu", false).containsKey("primeiroTextoMs");
            assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) fluxo.get("eventos")).keySet())).contains("texto", "pensando");
        });
        exigirCoerencia(traco);
        // o evento fim já traz o id do turno nos itens do assistente (o traço foi gravado antes dele)
        assertThat(fim(eventos(resposta)).<List<String>>ler("$.itens[?(@.tipo=='texto')].turno"))
                .containsExactly(traco.get(0).turno().toString());
    }
}
