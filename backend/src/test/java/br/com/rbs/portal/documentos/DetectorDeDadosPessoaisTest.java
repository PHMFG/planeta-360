package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.DetectorDeDadosPessoais.Suspeita;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Teste unitário do detector de dados pessoais (decisão 13 do plano): CPF e RG marcam sempre; telefone e e-mail só em lista
 * (3 ou mais contatos diferentes na mesma página). Valor em reais e data nunca marcam. Os CPFs dos testes têm os dígitos
 * verificadores certos (são números de teste, não de pessoas).
 */
class DetectorDeDadosPessoaisTest {

    private final DetectorDeDadosPessoais detector = new DetectorDeDadosPessoais();

    static Pagina pagina(int numero, String... linhas) {
        return new Pagina(numero, Arrays.stream(linhas).map(l -> new Linha(l, 62, List.of(62.0), false)).toList());
    }

    private boolean marca(String... linhas) {
        return detector.contemDadosPessoais(List.of(pagina(1, linhas)));
    }

    @Test
    void cpfComPontuacaoMarcaSempre() {
        assertThat(marca("Responsável: Fulano de Tal, CPF 529.982.247-25.")).isTrue();
        assertThat(marca("529.982.247-25")).isTrue();
        // mesmo com os dígitos verificadores errados: um CPF formatado é um CPF
        assertThat(marca("CPF 123.456.789-00")).isTrue();
    }

    @Test
    void cpfSemPontuacaoMarcaQuandoOsDigitosVerificadoresBatem() {
        assertThat(marca("CPF 52998224725")).isTrue();
        assertThat(marca("Documento: 52998224725 (titular)")).isTrue();
        assertThat(marca("Protocolo 12345678900")).as("11 dígitos que não são um CPF").isFalse();
        assertThat(marca("11111111111")).as("um dígito repetido não é CPF").isFalse();
    }

    @Test
    void rgMarcaComAPalavraSeguidaDeNumeroOuNoFormatoDeRg() {
        assertThat(marca("RG 12.345.678-9")).isTrue();
        assertThat(marca("RG: 1234567890 SSP/RS")).isTrue();
        assertThat(marca("Identidade 12.345.678-X")).isTrue();
        assertThat(marca("Apresente o RG na entrada.")).as("citar o RG sem número não é dado pessoal").isFalse();
        assertThat(marca("O ARGUMENTO 12345 foi aceito")).isFalse();
    }

    @Test
    void valoresEmReaisDatasEIntervalosDeAnosNaoMarcam() {
        assertThat(marca("A tarifa é de R$ 312,00 por kVA.", "A taxa de limpeza é de R$ 1.200,00 por ocorrência.",
                "Seguro de R$ 2.000.000,00 por sinistro e R$ 200.000,00 por pessoa.", "Cobrado até 28/02/2027.",
                "O festival acontece em 29/01/2027 e 30/01/2027.", "Edições 2026-2027 e 2027-2028.",
                "Cadastro até 15/01/2027, das 09h às 17h, 25 cortesias por dia e 1.800 profissionais.")).isFalse();
    }

    @Test
    void doisContatosNaPaginaNaoMarcamETresMarcam() {
        assertThat(marca("Fale com operacoes@planeta360.invalid ou pelo telefone (51) 3333-4444.")).as("2 contatos").isFalse();
        assertThat(marca("Fale com operacoes@planeta360.invalid ou pelo telefone (51) 3333-4444.", "Plantão: 51 99999-8888."))
                .as("3 contatos").isTrue();
        assertThat(marca("a@x.invalid", "b@x.invalid", "c@x.invalid")).as("3 e-mails").isTrue();
        assertThat(marca("a@x.invalid", "a@x.invalid", "A@X.invalid")).as("o mesmo contato repetido conta uma vez").isFalse();
    }

    @Test
    void osContatosSaoContadosPorPaginaENaoNoDocumentoTodo() {
        List<Pagina> paginas = List.of(pagina(1, "a@x.invalid", "(51) 3333-4444"), pagina(2, "b@x.invalid", "(51) 3333-5555"));

        assertThat(detector.contemDadosPessoais(paginas)).isFalse();
        assertThat(detector.analisar(List.of(pagina(1, "a@x.invalid", "b@x.invalid", "c@x.invalid"))))
                .containsExactly(new Suspeita(1, "CONTATOS"));
    }

    @Test
    void asSuspeitasDizemOTipoEAPagina() {
        List<Suspeita> suspeitas = detector.analisar(List.of(pagina(1, "texto limpo"), pagina(2, "CPF 529.982.247-25"),
                pagina(3, "RG 12.345.678-9", "a@x.invalid", "b@x.invalid", "c@x.invalid")));

        assertThat(suspeitas).containsExactly(new Suspeita(2, "CPF"), new Suspeita(3, "RG"), new Suspeita(3, "CONTATOS"));
    }

    @Test
    void oKitSoMarcaAListaDeCredenciadosDoD11() {
        for (KitDeDocumentos.Documento documento : KitDeDocumentos.DOCUMENTOS) {
            if ("D12".equals(documento.id())) {
                continue;   // sem texto extraível
            }
            var extracao = new ExtratorDeTexto().extrair(TipoDeArquivo.daExtensao(documento.arquivo()).orElseThrow(), documento.conteudo());
            assertThat(detector.contemDadosPessoais(extracao.paginas())).as(documento.id() + " " + documento.titulo())
                    .isEqualTo("D11".equals(documento.id()));
        }
    }

    @Test
    void oD11TemCpfComESemPontuacaoELista() {
        var extracao = new ExtratorDeTexto().extrairPdf(KitDeDocumentos.por("D11").conteudo());

        assertThat(detector.analisar(extracao.paginas())).extracting(Suspeita::tipo).contains("CPF", "CONTATOS");
    }

    @Test
    void cpfValidoConfereOsDoisDigitos() {
        assertThat(DetectorDeDadosPessoais.cpfValido("529.982.247-25")).isTrue();
        assertThat(DetectorDeDadosPessoais.cpfValido("52998224726")).isFalse();
        assertThat(DetectorDeDadosPessoais.cpfValido("00000000000")).isFalse();
        assertThat(DetectorDeDadosPessoais.cpfValido("123")).isFalse();
    }
}
