package br.com.rbs.portal.consolidacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Coluna;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Linha;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testa a leitura do CSV da carga sem banco nem API: cabeçalho, BOM, número da linha no arquivo, células vazias, lotes e
 * os erros 422 (campos.arquivo). Atende o item 5 da Entrega 2. [CARGA] [VALIDACAO]
 */
class LeitorDeCsvTest {

    private static final List<Coluna> COLUNAS = List.of(new Coluna("a", 20), new Coluna("b", 20), new Coluna("c", 20));

    private final List<Linha> lidas = new ArrayList<>();
    private final List<Integer> tamanhosDosLotes = new ArrayList<>();

    private int ler(String csv, int tamanhoDoLote) {
        return LeitorDeCsv.ler(csv.getBytes(StandardCharsets.UTF_8), COLUNAS, tamanhoDoLote, lote -> {
            tamanhosDosLotes.add(lote.size());
            lidas.addAll(lote);
        });
    }

    private int ler(String csv) {
        return ler(csv, 1000);
    }

    private static String erroDoArquivo(Throwable erro) {
        return ((DadosInvalidosException) erro).campos().get("arquivo");
    }

    private List<Integer> numeros() {
        return lidas.stream().map(Linha::numero).toList();
    }

    @Test
    void leOsValoresNaOrdemDasColunasEsperadasMesmoComOCabecalhoEmOutraOrdemEComColunaSobrando() {
        int total = ler("extra,C , b,A\nx,3,2,1\ny,\"x, \"\"y\"\"\",,1\n");

        assertThat(total).isEqualTo(2);
        assertThat(lidas.get(0).valores()).containsExactly("1", "2", "3");
        assertThat(lidas.get(1).valores()).containsExactly("1", null, "x, \"y\"");
    }

    @Test
    void aceitaBomNoInicioETiraOsEspacosDasCelulas() {
        ler("﻿a,b,c\n  1  , 2 ,   \n");

        assertThat(lidas).hasSize(1);
        assertThat(lidas.getFirst().valores()).containsExactly("1", "2", null);
    }

    @Test
    void linhaCurtaTemAsCelulasQueFaltamVazias() {
        ler("a,b,c\n1\n");

        assertThat(lidas.getFirst().valores()).containsExactly("1", null, null);
    }

    @Test
    void numeraAsLinhasPelaPosicaoNoArquivoContandoAsEmBranco() {
        ler("a,b,c\n1,1,1\n\n\n2,2,2\n3,3,3\n\n4,4,4");

        assertThat(numeros()).containsExactly(2, 5, 6, 8);
    }

    @Test
    void numeraCertoComCrlfEComRetornoDeCarroSozinho() {
        ler("a,b,c\r\n1,1,1\r\n\r\n2,2,2\r\n");
        assertThat(numeros()).containsExactly(2, 4);

        lidas.clear();
        ler("a,b,c\r1,1,1\r\r2,2,2\r");
        assertThat(numeros()).containsExactly(2, 4);
    }

    @Test
    void registroComTextoEmVariasLinhasContaPelaLinhaEmQueComeca() {
        ler("a,b,c\n1,\"um\ndois\ntres\",1\n2,2,2\n");

        assertThat(numeros()).containsExactly(2, 5);
        assertThat(lidas.getFirst().valores().get(1)).isEqualTo("um\ndois\ntres");
    }

    @Test
    void entregaAsLinhasEmLotes() {
        StringBuilder csv = new StringBuilder("a,b,c\n");
        for (int i = 0; i < 25; i++) {
            csv.append(i).append(",x,y\n");
        }

        int total = ler(csv.toString(), 10);

        assertThat(total).isEqualTo(25);
        assertThat(tamanhosDosLotes).containsExactly(10, 10, 5);
        assertThat(lidas).hasSize(25);
        assertThat(lidas.getLast().numero()).isEqualTo(26);
    }

    @Test
    void cabecalhoSemColunasDevolveAsQueFaltamNaOrdemEsperada() {
        assertThatThrownBy(() -> ler("c,a\n1,2\n")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).isEqualTo("O cabeçalho da planilha não tem as colunas: b."));
        assertThatThrownBy(() -> ler("x,y,z\n1,2,3\n"))
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("a, b, c"));
    }

    @Test
    void colunaOpcionalAusenteViraNuloEAsObrigatoriasContinuamExigidas() {
        List<Coluna> colunas = List.of(new Coluna("a", 20), new Coluna("b", 20, false));
        List<Linha> lidas = new ArrayList<>();

        int total = LeitorDeCsv.ler("a\n1\n".getBytes(StandardCharsets.UTF_8), colunas, 1000, lidas::addAll);

        assertThat(total).isEqualTo(1);
        assertThat(lidas.getFirst().valores()).containsExactly("1", null);
        assertThatThrownBy(() -> LeitorDeCsv.ler("b\n1\n".getBytes(StandardCharsets.UTF_8), colunas, 1000, lote -> { }))
                .isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).isEqualTo("O cabeçalho da planilha não tem as colunas: a."));
    }

    @Test
    void separadorPontoEVirgulaGanhaUmaDica() {
        assertThatThrownBy(() -> ler("a;b;c\n1;2;3\n"))
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("a, b, c").contains("vírgula"));
    }

    @Test
    void planilhaVaziaOuSoComCabecalhoEhRecusada() {
        assertThatThrownBy(() -> ler("   \n\n")).satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("O cabeçalho"));
        assertThatThrownBy(() -> ler("\n\n")).satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("vazia"));
        assertThatThrownBy(() -> ler("a,b,c\n\n\n")).satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("nenhuma linha de dados"));
    }

    @Test
    void textoMaiorQueOLimiteDaColunaApontaALinhaEAColuna() {
        assertThatThrownBy(() -> ler("a,b,c\n1,2,3\n\n1,2," + "z".repeat(21) + "\n"))
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).isEqualTo("Linha 4: a coluna \"c\" passa de 20 caracteres."));
    }

    @Test
    void csvComAspasSemFecharEhRecusado() {
        assertThatThrownBy(() -> ler("a,b,c\n1,\"2,3\n4,5,6\n"))
                .isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("não é um CSV válido"));
        assertThatThrownBy(() -> ler("a,b,c\n1,\"2\"x,3\n"))
                .satisfies(erro -> assertThat(erroDoArquivo(erro)).contains("não é um CSV válido"));
    }

    @Test
    void naoMexeNoConteudoDaCelulaAlemDeTirarOsEspacosDasPontas() {
        ler("a,b,c\n=SOMA(A1),+1,@x\n");

        assertThat(lidas.getFirst().valores()).containsExactly("=SOMA(A1)", "+1", "@x");
    }
}
