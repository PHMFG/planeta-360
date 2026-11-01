package br.com.rbs.portal.atlas.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] [PRIVACIDADE] O que o JEV recebe no {@code state} (critério B do M7): e-mail, telefone e CPF viram {@code [removido]}, e o
 * nome da marca também; data, hora, valor em reais, tabela de números e número pequeno ficam. Sem contexto do Spring.
 */
class MascaraDoJevTest {

    private static String mascarar(String texto) {
        return MascaraDoJev.mascarar(texto, List.of());
    }

    @Test
    void emailViraRemovido() {
        assertThat(mascarar("Mande para joao.silva+atlas@amarelo.example.com e avise")).isEqualTo("Mande para [removido] e avise");
        assertThat(mascarar("contato: MARKETING@Amarelo.example.")).isEqualTo("contato: [removido].");
    }

    @Test
    void telefoneEmTodosOsFormatosViraRemovido() {
        for (String telefone : List.of("(51) 99999-9999", "(51)3333-4444", "51 99999-9999", "51 3333-4444", "+55 51 99999-9999",
                "+5551999999999", "51999999999", "5133334444", "99999-9999", "3333-4444")) {
            assertThat(mascarar("Me liga no " + telefone + " hoje")).as(telefone).isEqualTo("Me liga no [removido] hoje");
        }
    }

    @Test
    void cpfComEQuemSemPontuacaoViraRemovido() {
        assertThat(mascarar("Meu CPF é 123.456.789-09.")).isEqualTo("Meu CPF é [removido].");
        assertThat(mascarar("cpf 12345678909 ok")).isEqualTo("cpf [removido] ok");
        assertThat(mascarar("CNPJ 12.345.678/0001-90 e 12345678000190")).isEqualTo("CNPJ [removido] e [removido]");
    }

    @Test
    void oRestoDaMensagemFica() {
        String mensagem = "Até quando informo os geradores na Arena? Foram 1.200 visitantes e R$ 1.250.000,00, em 04/12/2026 às 18:30 "
                + "(2026-12-04). Setor 100 200 400 40, 34 kVA, 312 reais, 10 dias.";

        assertThat(mascarar(mensagem)).isEqualTo(mensagem);
    }

    @Test
    void oNomeDaMarcaViraRemovidoEmQualquerCaixa() {
        assertThat(MascaraDoJev.mascarar("O prazo da Cliente Amarelo e da CLIENTE AMARELO?", List.of("Cliente Amarelo")))
                .isEqualTo("O prazo da [removido] e da [removido]?");
    }

    @Test
    void termoComCaractereEspecialDeExpressaoRegularNaoQuebra() {
        assertThat(MascaraDoJev.mascarar("Loja (X+Y) vende", List.of("(X+Y)"))).isEqualTo("Loja [removido] vende");
    }

    @Test
    void nuloEVazioViramTextoVazio() {
        assertThat(MascaraDoJev.mascarar(null, List.of())).isEmpty();
        assertThat(MascaraDoJev.mascarar("", List.of())).isEmpty();
    }
}
