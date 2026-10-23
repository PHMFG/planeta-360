package br.com.rbs.portal.pesquisa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas de pesquisa (23 a 25), todos em record. Atende o item 5 da Entrega 2.
 * O formato é o contrato com o front (veja docs/api.md): camelCase, datas com fuso de Brasília, listas sempre como lista.
 */
public final class PesquisaDtos {

    /** O máximo de perguntas por marca; o banco também limita a ordem a 1..3 (CHECK em pesquisa_pergunta). */
    public static final int LIMITE_DE_PERGUNTAS = 3;

    private PesquisaDtos() {
    }

    /** Quem fez algo, como no resto da API: id, nome e perfil. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /** A pesquisa da ativação da edição. {@code nivelConfianca} fica nulo quando a RBS ainda não informou. */
    public record PesquisaDto(Long id, String nome, String escopo, String metodologia, Integer baseRespondentes,
                              BigDecimal nivelConfianca) {
    }

    /**
     * Uma pergunta da marca. {@code status}: RASCUNHO (não enviada, ou devolvida para ajuste), ENVIADA (espera a RBS) ou
     * APROVADA. {@code observacaoRbs} é o recado da RBS na última decisão, enquanto ela ainda vale.
     */
    public record PerguntaDto(Long id, Integer ordem, String texto, String status, String observacaoRbs,
                              UsuarioResumoDto enviadaPor, OffsetDateTime enviadaEm) {
    }

    /** [VALIDACAO] Um resultado agregado de pergunta APROVADA. A base de respondentes vai sempre junto do número. */
    public record ResultadoDto(Long perguntaId, Integer ordem, String alternativa, BigDecimal valor, String unidade,
                               Integer baseRespondentes) {
    }

    /** A resposta das rotas 23 e 24: a pesquisa (ou nulo), as perguntas da parceria e os resultados das aprovadas. */
    public record PesquisaDaParceriaDto(Long parceriaId, boolean abaLiberada, int limitePerguntas, PesquisaDto pesquisa,
                                        List<PerguntaDto> perguntas, List<ResultadoDto> resultados) {
    }

    // ---------------------------------------------------------------------
    // Corpos de entrada
    // ---------------------------------------------------------------------

    /**
     * [VALIDACAO] Uma pergunta do PUT. O texto vale sem os espaços das pontas. Texto vazio e texto curto caem na mesma
     * regra (10 a 300 caracteres), para o campo ter uma mensagem só.
     */
    public record PerguntaRequisicao(
            @NotNull(message = "Informe a ordem da pergunta (1, 2 ou 3).")
            @Min(value = 1, message = "A ordem da pergunta vai de 1 a 3.")
            @Max(value = LIMITE_DE_PERGUNTAS, message = "A ordem da pergunta vai de 1 a 3.")
            Integer ordem,
            @NotNull(message = "Escreva a pergunta.")
            @Size(min = 10, max = 300, message = "A pergunta precisa ter de 10 a 300 caracteres.")
            String texto) {

        public PerguntaRequisicao {
            texto = texto == null ? null : texto.trim();
        }
    }

    /** [VALIDACAO] O corpo do PUT: a lista completa, de 1 a 3 perguntas. A ordem repetida o Service confere. */
    public record EnviarPerguntasRequisicao(
            @NotNull(message = "Envie a lista de perguntas.")
            @Size(min = 1, max = LIMITE_DE_PERGUNTAS, message = "Envie de 1 a 3 perguntas.")
            List<@NotNull(message = "Pergunta vazia.") @Valid PerguntaRequisicao> perguntas) {
    }

    /** [VALIDACAO] Corpo da rota 25. Que a observação é obrigatória no PEDIR_AJUSTE o Service confere. */
    public record DecisaoRequisicao(
            @NotBlank(message = "Informe a decisão.")
            @Pattern(regexp = "APROVAR|PEDIR_AJUSTE", message = "Decisão inválida. Use APROVAR ou PEDIR_AJUSTE.")
            String decisao,
            @Size(max = 1000, message = "A observação pode ter até 1000 caracteres.")
            String observacao) {

        /** Tira os espaços das pontas antes da validação; observação só com espaços vale como ausente. */
        public DecisaoRequisicao {
            decisao = decisao == null ? null : decisao.trim();
            observacao = observacao == null || observacao.isBlank() ? null : observacao.trim();
        }
    }
}
