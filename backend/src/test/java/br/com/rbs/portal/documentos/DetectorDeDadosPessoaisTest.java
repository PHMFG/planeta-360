package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.DetectorDeDadosPessoais.Suspeita;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // ---------------------------------------------------------------------
    // M3, rodada de correção: o CPF e o RG que passavam (achado 1)
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] marca: {0}")
    @ValueSource(strings = {
            "O CPF é 52998224725.",
            "portador do CPF 52998224725, residente",
            "Fulano,529.982.247-25,Credenciado",
            "CPF-529.982.247-25",
            "CPF 529982247-25",
            "529.982.247–25",
            "529.982.247—25",
            "CPF: 12345678900",
            "cpf 12345678900",
            "CPF nº 123456789-00",
            "CPF 529 982 247 25",
            "123  456  789   09",
            "CPF 529​982​247​25",
            "CPF ５２９９８２２４７２５",
            "Anexo (CPF 529.982.247-25)",
            "rg: 12345678",
            "R.G. 1234567",
            "r.g. 12.345.678-9",
            "RG 12.345.678-X",
            "RG nº 1234567-8",
            "O RG do titular é 123456789.",
            "RG: 1234567890 SSP/RS",
            "12.345.678-9",
            "Identidade 12.345.678-X",
    })
    void cpfERgQueAntesPassavamMarcam(String linha) {
        assertThat(marca(linha)).as(linha).isTrue();
    }

    @ParameterizedTest(name = "[{index}] não marca: {0}")
    @ValueSource(strings = {
            "Protocolo 12345678900",
            "Código 98765432101 do pedido",
            "11111111111",
            "Unidade RG 2027-2028",
            "RG 2027",
            "R.G. 2027",
            "Apresente o RG na entrada.",
            "O RG e o CPF são exigidos na retirada.",
            "O ARGUMENTO 12345 foi aceito",
            "Lote 10 2026-2027",
            "Lote 11 2026-2027",
            "Edições 2026-2027 e 2027-2028",
            "R$ 312,00 por kVA e R$ 1.200,00 por ocorrência",
            "Cadastro até 15/01/2027, das 09h às 17h.",
            "Pedido 2026.0457.8812-3",
    })
    void oQueNaoEDadoPessoalContinuaLimpo(String linha) {
        assertThat(marca(linha)).as(linha).isFalse();
    }

    @Test
    void aTravaDeDentroDeOutroNumeroSoRecusaDigitoVizinho() {
        assertThat(marca("Contrato 2026/52998224725 do cliente")).as("barra depois de dígito").isFalse();
        assertThat(marca("Série 52998224725-1 do lote")).as("hífen depois de dígito").isFalse();
        assertThat(marca("Pedido 529.982.247-253 recebido")).as("dígito colado").isFalse();
        assertThat(marca("Código 1529.982.247-25")).as("dígito colado antes").isFalse();
        assertThat(marca("Vale 529.982.247-25,5 pontos")).as("vírgula e dígito depois").isFalse();
        assertThat(marca("CPF 529.982.247-25, residente")).as("vírgula depois").isTrue();
        assertThat(marca("CPF 529.982.247-25. Fim.")).as("ponto final depois").isTrue();
        assertThat(marca("titular-52998224725")).as("hífen depois de letra").isTrue();
    }

    @Test
    void cpfSemAPalavraSoMarcaComDigitosVerificadoresCertos() {
        assertThat(marca("Documento 52998224725")).isTrue();
        assertThat(marca("Documento 529982247-25")).isTrue();
        assertThat(marca("Documento 529 982 247 25")).isTrue();
        assertThat(marca("Documento 52998224726")).as("dígito verificador errado e sem a palavra").isFalse();
        assertThat(marca("Documento 529 982 247 26")).isFalse();
        assertThat(marca("CPF do titular, cadastrado em 2026: 52998224726")).as("a palavra está longe (mais de 20 caracteres)").isFalse();
        assertThat(marca("CPF 52998224726")).as("a palavra logo antes basta").isTrue();
    }

    @Test
    void asFormasDeTelefoneNovasContamComoContato() {
        // dois e-mails + um telefone na forma testada = 3 contatos diferentes = lista
        for (String telefone : List.of("51-3333-4444", "(51) 3333 4444", "51 3333 4444", "+5551999998888", "(51)3333-4444", "51 99999-8888",
                "+55 (51) 99999-8888", "+55 51 3333-4444")) {
            assertThat(marca("a@x.invalid", "b@x.invalid", telefone)).as(telefone).isTrue();
            assertThat(marca("a@x.invalid", telefone)).as("só 2 contatos: " + telefone).isFalse();
        }
    }

    @Test
    void oMesmoTelefoneEscritoDeJeitosDiferentesContaUmaVez() {
        assertThat(marca("(51) 3333-4444", "51 3333 4444", "51-3333-4444", "+55 51 3333 4444")).isFalse();
        assertThat(DetectorDeDadosPessoais.contatos("(51) 3333-4444 e 51 3333 4444 e a@x.invalid")).hasSize(2);
    }

    @Test
    void faixaDeAnosOuLoteComDddInvalidoNuncaESeTelefone() {
        assertThat(marca("Lote 11 2026-2027", "Lote 12 2027-2028", "Lote 13 2028-2029")).isFalse();
        assertThat(marca("Lote 10 2026-2027", "Lote 20 2027-2028", "a@x.invalid")).as("DDD de 2 dígitos sem zero").isFalse();
        assertThat(DetectorDeDadosPessoais.contatos("Lote 10 2026-2027 e Lote 11 2026-2027")).isEmpty();
    }

    @Test
    void cpfNaoViraTelefoneNemTelefoneViraCpf() {
        assertThat(DetectorDeDadosPessoais.contatos("CPF 529 982 247 25")).isEmpty();
        assertThat(detector.analisar(List.of(pagina(1, "+5551999998888", "(51) 3333-4444", "51 99999-7777"))))
                .as("três telefones são uma lista de contatos, e só isso").containsExactly(new Suspeita(1, "CONTATOS"));
    }

    @Test
    void numeroDeTelefoneComDddComZeroNaoEDdd() {
        assertThat(marca("Pista 10 3333-4444", "Pista 20 3333-4445", "Pista 30 3333-4446")).isFalse();
    }

    @Test
    void oTextoEhNormalizadoAntesDeProcurar() {
        // hífen condicional, espaço sem quebra, espaço fino, hífen sem quebra, sinal de menos, traço de figura e a marca de ordem de bytes
        assertThat(marca("CPF 529.98­2.247-25")).isTrue();
        assertThat(marca("CPF 529 982 247 25")).isTrue();
        assertThat(marca("CPF 529 982 247 25")).isTrue();
        assertThat(marca("529.982.247‑25")).isTrue();
        assertThat(marca("529.982.247−25")).isTrue();
        assertThat(marca("529.982.247‒25")).isTrue();
        assertThat(marca("R﻿G 12345678")).as("a palavra RG com um caractere invisível no meio").isTrue();
        assertThat(marca("C​P​F 12345678900")).as("a palavra CPF com caracteres invisíveis").isTrue();
    }

    @Test
    void cpfValidoConfereOsDoisDigitos() {
        assertThat(DetectorDeDadosPessoais.cpfValido("529.982.247-25")).isTrue();
        assertThat(DetectorDeDadosPessoais.cpfValido("52998224726")).isFalse();
        assertThat(DetectorDeDadosPessoais.cpfValido("00000000000")).isFalse();
        assertThat(DetectorDeDadosPessoais.cpfValido("123")).isFalse();
    }
}
