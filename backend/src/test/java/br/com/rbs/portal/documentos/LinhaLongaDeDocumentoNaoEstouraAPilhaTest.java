package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.PilhaPequena;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Uma linha de documento com milhares de repetições não estoura a pilha (M8, CI no Linux): o detector de dados pessoais, que
 * lê e-mails em todo o texto da página, roda numa thread de pilha de 256 KB, que estoura no Mac e no Linux. Um PDF ou TXT enviado pela
 * RBS pode trazer uma linha assim, e o {@link StackOverflowError} derrubava o envio do documento. O divisor de trechos tem o mesmo
 * padrão de grupo repetido na numeração do título, mas só o lê em linha de até 110 caracteres, então não chega a estourar por
 * documento; o {@code ExpressoesRegularesSemRecursaoTest} confere o padrão dele de qualquer jeito. Unitário, sem Spring.
 */
class LinhaLongaDeDocumentoNaoEstouraAPilhaTest {

    private static final int REPETICOES = 20_000;

    private static Pagina pagina(int numero, String... linhas) {
        return new Pagina(numero, Arrays.stream(linhas).map(l -> new Linha(l, 62, List.of(62.0), false)).toList());
    }

    @Test
    void oDetectorLeUmEmailComMilharesDeRotulosSemEstourar() {
        String email = "Contato: a@b" + ".c".repeat(REPETICOES);
        PilhaPequena.rodar(() -> assertThat(new DetectorDeDadosPessoais().contemDadosPessoais(List.of(pagina(1, email)))).isFalse());
    }

    @Test
    void tresEmailsDeVerdadeNaMesmaPaginaSeguemMarcandoComoLista() {
        PilhaPequena.rodar(() -> assertThat(new DetectorDeDadosPessoais().contemDadosPessoais(List.of(pagina(1,
                "Contatos: ana@exemplo.com.br, bia@exemplo.com.br e caio@exemplo.com.br")))).isTrue());
    }
}
