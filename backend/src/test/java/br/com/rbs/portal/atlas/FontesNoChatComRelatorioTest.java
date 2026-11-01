package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.todas;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [VALIDACAO] A checagem das fontes e dos números do texto do chat vale igual com o relatório ligado (M6): é a mesma bateria
 * de {@link FontesNoChatTest}, rodada com {@code atlas.relatorio.ligado=true}, com o prompt do modo (a base do chat e o fim do
 * relatório) e a frase fixa do modo. A resposta da conversa com número e citação não pode voltar a ser recusada só porque o
 * relatório está ligado, e a que tem número sem origem continua recusada, também com um relatório em montagem na conversa. O contexto
 * é outro por causa da propriedade; o resto (diagnóstico, resumo e embeddings) é o da classe de cima.
 */
@TestPropertySource(properties = {"atlas.relatorio.ligado=true"})
class FontesNoChatComRelatorioTest extends FontesNoChatTest {

    private static final String ARGS_INICIAR = "{\"modelo\":\"ATIVACAO_EVENTO\"}";

    @Override
    int limiteDoPrompt() {
        return 13000;
    }

    @Override
    String fraseFixa() {
        return LoopDoAgente.FRASE_FIXA;
    }

    /** Uma conversa com o relatório da ativação em montagem (o modelo o iniciou e parou num texto). */
    long conversaComRelatorioEmMontagem() {
        long conversa = abrir();
        modelo.chamada("relatorio_iniciar", ARGS_INICIAR).texto("Comecei o relatório da ativação.");
        falar(conversa, "Comece o relatório da ativação.");
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("MONTANDO");
        modelo.limpar();
        return conversa;
    }

    @Test
    void oModeloRecebeAsFerramentasDoChatJuntoComAsDoRelatorioEOPromptDosDoisModos() {
        long conversa = abrir();
        modelo.texto("Olá!");

        falar(conversa, "Oi");

        List<String> esperadas = new ArrayList<>(LoopDoAgenteTest.DO_CHAT);
        esperadas.add(LoopDoAgenteTest.INICIAR);
        assertThat(nomesDasFerramentas(modelo.pedidos().get(0))).containsExactlyElementsOf(esperadas);
        assertThat(modelo.pedidos().get(0).mensagens().get(0).conteudo()).isEqualTo(ApoioDosPrompts.esperado(true, "Cliente Amarelo"));
    }

    @Test
    void comORelatorioEmMontagemAPerguntaSobreOsDocumentosContinuaComFonteENumeroDaFonte() {
        long conversa = conversaComRelatorioEmMontagem();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).matches("Os geradores próprios devem ser informados à produção até 04/12/2026 \\[T[1-9]\\]\\.");
        assertThat(texto.fontes()).hasSize(1);
        assertThat(texto.porModelo()).isTrue();
        // o modelo recebeu as ferramentas do chat e as do relatório em montagem, na mesma oferta
        List<String> esperadas = new ArrayList<>(LoopDoAgenteTest.DO_CHAT);
        esperadas.addAll(List.of(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.FINALIZAR));
        assertThat(nomesDasFerramentas(modelo.pedidos().get(0))).containsExactlyElementsOf(esperadas);
    }

    @Test
    void comORelatorioEmMontagemONumeroSemOrigemContinuaRecusadoENaoChegaAoHistorico() {
        long conversa = conversaComRelatorioEmMontagem();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> "Os geradores devem ser informados até 17/08/2031 [" + marcador(pedido, PRAZO, true) + "].");
        escrever(pedido -> "Os geradores devem ser informados até 18/08/2031 [" + marcador(pedido, PRAZO, true) + "].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(textosDoAssistente(conversa)).doesNotContain("17/08/2031").doesNotContain("18/08/2031");
    }

    // ---------------------------------------------------------------------
    // "Monta o relatório e me diz até quando informo os geradores": a pergunta e o cartão no mesmo turno
    // ---------------------------------------------------------------------

    private static final String PEDIDO_MISTO = "Monta o relatório da ativação e me diz até quando preciso informar os geradores.";

    @Test
    void pedidoMistoOTextoComCitacaoEscritoNaIdaDoCartaoPassaPelaChecagemEAparecePrimeiro() {
        long conversa = abrir();
        // ida 1: abre o relatório e busca; ida 2, já com o trecho na mão: responde por escrito e pede os números na mesma resposta
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.INICIAR, ARGS_INICIAR), pedido(BUSCAR, busca(CONSULTA)));
        modelo.passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)),
                List.of(pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)), null));

        ConversaDto turno = falar(conversa, PEDIDO_MISTO);

        // a resposta da pergunta vem antes do cartão, com a fonte; o turno para no cartão
        assertThat(LoopDoAgenteTest.tipos(turno)).containsExactly("usuario", "texto", "cartao");
        ItemTexto texto = textoFinal(turno);
        assertThat(texto.texto()).matches("Os geradores próprios devem ser informados à produção até 04/12/2026 \\[T[1-9]\\]\\.");
        assertThat(texto.fontes()).hasSize(1);
        assertThat(texto.porModelo()).isTrue();
        assertThat(turno.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow().status())
                .isEqualTo("PENDENTE");
        assertThat(fontesGravadas(conversa)).hasSize(1);
        // o texto da ida do cartão foi conferido (etapa CHECAGEM) e aprovado, e o turno terminou no cartão
        List<Linha> checagens = todas(ultimoTurno(conversa), "CHECAGEM");
        assertThat(checagens).singleElement().satisfies(linha -> {
            assertThat(linha.dados()).containsEntry("origem", "TEXTO_COM_FERRAMENTAS").containsEntry("aprovado", true);
            assertThat(linha.resultado()).isEqualTo("OK");
        });
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("tipo", "CARTAO");
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }

    @Test
    void numeroSemOrigemEscritoNaIdaDoCartaoNaoChegaATelaNemAoHistoricoEOCartaoAbre() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.INICIAR, ARGS_INICIAR), pedido(BUSCAR, busca(CONSULTA)));
        modelo.passo(pedido -> new RespostaDoLlm("Os geradores devem ser informados até 17/08/2031 [" + marcador(pedido, PRAZO, true) + "].",
                List.of(pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)), null));

        ConversaDto turno = falar(conversa, PEDIDO_MISTO);

        // a mesma checagem do texto do chat vale na ida do cartão: o texto com número que a fonte não tem sai, e a ação segue
        assertThat(LoopDoAgenteTest.tipos(turno)).containsExactly("usuario", "cartao");
        assertThat(textosDoAssistente(conversa)).doesNotContain("17/08/2031");
        assertThat(todas(ultimoTurno(conversa), "CHECAGEM")).extracting(Linha::resultado).containsExactly("RECUSADO");
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("tipo", "CARTAO");
    }
}
