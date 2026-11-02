package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.PilhaPequena;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] [VALIDACAO] O texto do modelo com milhares de repetições não estoura a pilha (M8, CI no Linux): a conferência de forma e a
 * conta dos números rodam numa thread de pilha de 256 KB, que estoura no Mac e no Linux (a pilha padrão é de 2 MB no Mac arm64 e de
 * 1 MB no Linux x64, e a repetição de um grupo do {@code java.util.regex} gasta uma volta de pilha por repetição). Conferem também
 * que a correção não afrouxou nada: a cadeia de domínio e a chave interna longas seguem recusadas, e o número e a data seguem sendo
 * achados (o significado para o texto normal é o dos testes do normalizador, que não mudaram). Unitário, sem Spring. Os padrões que não são alcançados por essas rotas estão no
 * {@code ExpressoesRegularesSemRecursaoTest}.
 */
class TextoLongoNaoEstouraAPilhaTest {

    private static final int REPETICOES = 5_000;

    @Test
    void aConferenciaDeFormaRecusaDominioEChaveInternaComMilharesDeRotulosSemEstourar() {
        String dominio = "Veja a" + ".b".repeat(REPETICOES) + ".com hoje.";
        String curto = "Veja a" + ".b".repeat(40) + ".com hoje.";
        String chave = "O total está em dado:a" + ".b".repeat(REPETICOES) + " hoje.";
        String email = "Escreva para ab@cd" + ".ef".repeat(REPETICOES) + " hoje.";
        PilhaPequena.rodar(() -> {
            assertThat(ChecagemDeTexto.motivoDeForma(dominio, dominio)).as("domínio com 5.000 rótulos").isPresent();
            assertThat(ChecagemDeTexto.motivoDeForma(curto, curto)).as("domínio com 40 rótulos, acima do limite fixo").isPresent();
            assertThat(ChecagemDeTexto.motivoDeForma(chave, chave)).as("chave interna com 5.000 rótulos").isPresent();
            assertThat(ChecagemDeTexto.motivoDeForma(email, email)).as("e-mail com 5.000 rótulos").isPresent();
        });
    }

    @Test
    void aContaDosNumerosAchaONumeroEADataComMilharesDeRepeticoesSemEstourar() {
        String decimais = "Deu 1" + ".1".repeat(REPETICOES) + " hoje.";
        String dias = "Vale " + "1" + ", 1".repeat(REPETICOES) + " de janeiro.";
        String diasComE = "Vale " + "1" + " e 1".repeat(REPETICOES) + " de dezembro de 2026.";
        String numerais = "Foram " + "dois" + " e dois".repeat(REPETICOES) + " de janeiro.";
        String escalas = "Foram " + "mil" + " mil".repeat(REPETICOES) + " reais.";
        PilhaPequena.rodar(() -> {
            assertThat(NormalizadorDeNumeros.extrair(decimais, false)).as("decimais encadeados").isNotEmpty();
            assertThat(NormalizadorDeNumeros.extrair(dias, false)).as("dias separados por vírgula").isNotEmpty();
            assertThat(NormalizadorDeNumeros.extrair(diasComE, false)).as("dias separados por e").isNotEmpty();
            assertThat(NormalizadorDeNumeros.extrair(numerais, false)).as("numerais seguidos de mês").isNotEmpty();
            assertThat(NormalizadorDeNumeros.extrair(escalas, false)).as("escalas em sequência").isNotEmpty();
        });
    }
}
