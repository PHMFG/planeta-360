package br.com.rbs.portal.atlas.jev;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PedidoAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PerguntaAoJev;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] O arquivo de perguntas do JEV ({@code atlas/jev/perguntas.json}), que o servidor e a calibração leem: os seis pontos, as
 * opções do plano na ordem do plano ({@code outro} por último na intenção), os marcadores que cada molde usa e a repetição do molde
 * da resposta por frase. Sem contexto do Spring.
 */
class PerguntasDoJevTest {

    private final PerguntasDoJev perguntas = new PerguntasDoJev();

    @Test
    void temOsSeisPontosCadaUmComEstadoEPerguntas() {
        for (PontoDoJev ponto : PontoDoJev.values()) {
            assertThat(perguntas.moldeDoEstado(ponto)).as(ponto.nome()).isNotBlank();
            assertThat(perguntas.perguntas(ponto)).as(ponto.nome()).isNotEmpty();
        }
        assertThat(perguntas.versao()).isEqualTo(1);
    }

    @Test
    void aIntencaoTemAsDezOpcoesDoPlanoComOutroPorUltimo() {
        PerguntaAoJev intencao = perguntas.perguntas(PontoDoJev.INTENCAO).get("intencao");

        assertThat(intencao.tipo()).isEqualTo("choice");
        assertThat(intencao.criterios().keySet()).containsExactly("pergunta_documentos", "dados_parceria", "dados_de_midia", "misto",
                "montar_relatorio", "outro_relatorio", "modelo_em_branco", "fora_de_escopo", "saudacao_uso", "outro");
        assertThat(intencao.criterios().get("dados_de_midia")).contains("mídia", "veiculação", "inserções", "impacto",
                "contratado x entregue");
    }

    @Test
    void aIntencaoPerguntaAindaOTipoDoModeloEOSinalDeInjecao() {
        Map<String, PerguntaAoJev> doPonto = perguntas.perguntas(PontoDoJev.INTENCAO);

        assertThat(doPonto.keySet()).containsExactly("intencao", "tipo_modelo", "sinal_injecao");
        assertThat(doPonto.get("tipo_modelo").tipo()).isEqualTo("choice");
        assertThat(doPonto.get("tipo_modelo").criterios().keySet())
                .containsExactly("briefing_conteudo", "perguntas_pesquisa", "plano_operacional_ativacao", "nenhum");
        assertThat(doPonto.get("sinal_injecao").tipo()).isEqualTo("noul");
        assertThat(doPonto.get("sinal_injecao").criterios()).isEmpty();
    }

    @Test
    void asOpcoesDosOutrosPontosSaoAsDoPlano() {
        assertThat(perguntas.perguntas(PontoDoJev.CARTAO).get("cartao").criterios().keySet())
                .containsExactly("responde_ao_cartao", "outro_assunto", "cancelar");
        assertThat(perguntas.perguntas(PontoDoJev.RESPOSTA).get("frase").criterios().keySet())
                .containsExactly("apoia", "contradiz", "nao_diz");
        assertThat(perguntas.perguntas(PontoDoJev.COERENCIA).get("coerencia").criterios().keySet())
                .containsExactly("coerente", "incoerente");
        assertThat(perguntas.perguntas(PontoDoJev.FONTES).get("relevante").tipo()).isEqualTo("noul");
        assertThat(perguntas.perguntas(PontoDoJev.LACUNAS).get("vale_perguntar").tipo()).isEqualTo("noul");
    }

    @Test
    void todaPerguntaTemInstrucaoETodaChoiceTemOpcoesDescritas() {
        for (PontoDoJev ponto : PontoDoJev.values()) {
            perguntas.perguntas(ponto).forEach((nome, pergunta) -> {
                assertThat(pergunta.instrucoes()).as(ponto.nome() + "." + nome).isNotBlank();
                if (pergunta.ehEscolha()) {
                    assertThat(pergunta.criterios()).as(ponto.nome() + "." + nome).hasSizeGreaterThanOrEqualTo(2);
                    assertThat(pergunta.criterios().values()).as(ponto.nome() + "." + nome).allSatisfy(d -> assertThat(d).isNotBlank());
                } else {
                    assertThat(pergunta.criterios()).isEmpty();
                }
            });
        }
    }

    @Test
    void oEstadoTrocaOsMarcadoresDeUmaVezSoSemTrocarOQueVemDentroDoValor() {
        String estado = perguntas.estado(PontoDoJev.FONTES, Map.of("pergunta", "Qual o prazo {trecho}?", "trecho", "Informe até 04/12/2026."));

        assertThat(estado).isEqualTo("Pergunta: Qual o prazo {trecho}?\n\nTrecho de documento:\nInforme até 04/12/2026.");
    }

    @Test
    void oPedidoDaIntencaoLevaSoAMensagemNoEstado() {
        PedidoAoJev pedido = perguntas.pedido(PontoDoJev.INTENCAO, Map.of("mensagem", "oi, tudo bem?"));

        assertThat(pedido.estado()).isEqualTo("oi, tudo bem?");
        assertThat(pedido.perguntas().keySet()).containsExactly("intencao", "tipo_modelo", "sinal_injecao");
    }

    @Test
    void oMoldeDaRespostaViraUmaPerguntaPorFraseAteOito() {
        List<String> frases = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            frases.add("Frase número " + i + " [T1].");
        }

        Map<String, PerguntaAoJev> perguntasDeFrases = perguntas.perguntasDeFrases(frases);

        assertThat(perguntasDeFrases.keySet()).containsExactly("frase_1", "frase_2", "frase_3", "frase_4", "frase_5", "frase_6", "frase_7",
                "frase_8");
        assertThat(perguntasDeFrases.get("frase_3").instrucoes()).contains("Frase: Frase número 3 [T1].").doesNotContain("{frase}");
        assertThat(perguntasDeFrases.get("frase_3").criterios().keySet()).containsExactly("apoia", "contradiz", "nao_diz");
    }
}
