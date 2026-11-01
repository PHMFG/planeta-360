package br.com.rbs.portal.atlas.checagem;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [VALIDACAO] A checagem do texto do chat com fontes (M4, critério bloqueante: pelo menos 25 casos). Cada regra tem o caso que
 * a cumpre e o que tenta furá-la: número com e sem fonte, número só parecido com o da fonte, citação de outra frase, de outro turno,
 * inexistente ou inventada, data em outro formato, fonte com um número e resposta com outro, URL, HTML, nome de ferramenta,
 * algarismo de outro alfabeto. O banco da amostra serve só para os marcadores {@code {{ref}}} resolverem de verdade. Cada teste é
 * desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ChecagemDeFontesTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 1);
    private static final String TARIFA = "A tarifa de energia é de R$ 312,00 por kVA, cobrada sobre a carga contratada.";
    private static final String PRAZO = "Os geradores devem ser informados à produção até 04/12/2026, às 18h, no horário de Brasília.";

    @Autowired
    ChecagemDeFontes checagem;

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    ModeloBase modelos;

    @Autowired
    JdbcClient jdbc;

    Resolucao doAmarelo() {
        return resolvedor.abrir(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"),
                modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
    }

    /** O turno com os trechos dados (T1, T2...), todos da página 5 da seção "4 Energia › 4.1 Tarifa" do manual v2. */
    FontesDoTurno turno(String mensagemDaPessoa, String... trechos) {
        FontesDoTurno turno = new FontesDoTurno(mensagemDaPessoa, 2027, HOJE);
        for (int i = 0; i < trechos.length; i++) {
            turno.registrarTrecho(100 + i, 10, "Manual de Operações 2027", 2, 5, "4 Energia › 4.1 Tarifa", trechos[i]);
        }
        return turno;
    }

    Veredito chat(FontesDoTurno turno, String texto) {
        return checagem.chat(doAmarelo(), turno, texto);
    }

    void aprova(FontesDoTurno turno, String texto) {
        Veredito veredito = chat(turno, texto);
        assertThat(veredito.aprovado()).as("\"" + texto + "\" devia passar, mas: " + veredito.motivo()).isTrue();
    }

    String recusa(FontesDoTurno turno, String texto) {
        Veredito veredito = chat(turno, texto);
        assertThat(veredito.aprovado()).as("\"" + texto + "\" devia ser recusado").isFalse();
        assertThat(veredito.texto()).isNull();
        return veredito.motivo();
    }

    // ---------------------------------------------------------------------
    // Número com e sem fonte
    // ---------------------------------------------------------------------

    @Test
    void numeroQueEstaNaFonteCitadaNaMesmaFrasePassa() {
        aprova(turno("Quanto é a tarifa?", TARIFA), "A tarifa é de R$ 312,00 por kVA [T1].");
        aprova(turno("Qual o prazo?", PRAZO), "O prazo é 04/12/2026 [T1].");
    }

    @Test
    void numeroSemNenhumaCitacaoEhRecusadoEOMotivoDizOQueFazer() {
        String motivo = recusa(turno("Quanto?", TARIFA), "A tarifa é de R$ 312,00 por kVA.");

        assertThat(motivo).contains("312").contains("não cita nenhuma fonte").contains("como [T1]")
                .contains("Não encontrei isso nos documentos do portal.");
    }

    @Test
    void numeroSoParecidoComODaFonteEhRecusado() {
        FontesDoTurno turno = turno("Quanto?", TARIFA);

        assertThat(recusa(turno, "A tarifa é de R$ 321 por kVA [T1].")).contains("321").contains("[T1]");
        recusa(turno, "A tarifa é de R$ 31 por kVA [T1].");
        recusa(turno, "A tarifa é de R$ 3.120 por kVA [T1].");
        recusa(turno, "A tarifa é de R$ 3,12 por kVA [T1].");
        recusa(turno, "A tarifa é de R$ 312,01 por kVA [T1].");
    }

    @Test
    void fonteComUmNumeroERespostaComOutroEhRecusada() {
        recusa(turno("Quanto?", TARIFA), "A taxa de cadastro é de R$ 450 [T1].");
        recusa(turno("Quanto?", TARIFA), "A tarifa é de R$ 312 por kVA e a taxa é de R$ 450 [T1].");
    }

    @Test
    void citarOutraFonteQueNaoTemONumeroNaoValeParaAFrase() {
        FontesDoTurno turno = turno("Quanto?", TARIFA, "A taxa de cadastro de gerador é de R$ 450,00.");

        aprova(turno, "A taxa é de R$ 450 [T2].");
        recusa(turno, "A taxa é de R$ 450 [T1].");
        aprova(turno, "A tarifa é de R$ 312 [T1] e a taxa é de R$ 450 [T2].");
        aprova(turno, "A tarifa é de R$ 312 e a taxa é de R$ 450 [T1, T2].");
    }

    @Test
    void aCitacaoDeOutraFraseNaoApoiaEstaFrase() {
        FontesDoTurno turno = turno("Quanto?", TARIFA + " " + PRAZO);

        recusa(turno, "O prazo é 04/12/2026. A tarifa é de R$ 312 [T1].");
        recusa(turno, "A tarifa é de R$ 312 [T1]. O prazo é 04/12/2026.");
        recusa(turno, "A tarifa é de R$ 312.\nCom o prazo de 04/12/2026 [T1]");
        aprova(turno, "O prazo é 04/12/2026 [T1]. A tarifa é de R$ 312 [T1].");
        aprova(turno, "O prazo é 04/12/2026 [T1]\nA tarifa é de R$ 312 [T1]");
    }

    @Test
    void pontoDeVerEOFimDaFraseENegritoFechaAFraseDepoisDoPonto() {
        // a mesma fonte traz tudo; o que decide é qual frase a cita
        FontesDoTurno turno = turno("Quanto?", "R$ 312,00, R$ 999,00 e 09/09/2026");

        // "ver." não é abreviatura: o 999 fica numa frase sem citação, e a citação da seguinte não o apoia
        recusa(turno, "A multa é R$ 999,00 como dá para ver. A tarifa é R$ 312,00 [T1].");
        aprova(turno, "A multa é R$ 999,00 [T1] como dá para ver. A tarifa é R$ 312,00 [T1].");
        aprova(turno, "A multa é como dá para ver. A tarifa é R$ 312,00 [T1].");
        // o negrito, o itálico e o sublinhado fecham a frase depois do ponto
        recusa(turno, "**A multa é 09/09/2026.** A tarifa é R$ 312,00 [T1].");
        recusa(turno, "_A multa é 09/09/2026._ A tarifa é R$ 312,00 [T1].");
        recusa(turno, "*A multa é R$ 999,00.* A tarifa é R$ 312,00 [T1].");
        aprova(turno, "**A multa é 09/09/2026.** [T1] A tarifa é R$ 312,00 [T1].");
        aprova(turno, "**A multa é 09/09/2026 [T1].** A tarifa é R$ 312,00 [T1].");
        aprova(turno, "**Atenção!** A tarifa é R$ 312,00 [T1].");
    }

    @Test
    void aCitacaoDepoisDoPontoFicaComAFraseDeAntes() {
        FontesDoTurno turno = turno("Qual o prazo?", PRAZO);

        aprova(turno, "O prazo é 04/12/2026. [T1] Depois disso, não há mais prorrogação.");
        aprova(turno, "O prazo é 04/12/2026. [T1] [T1]");
        aprova(turno, "O prazo é 04/12/2026.[T1]");
        recusa(turno, "O prazo é 04/12/2026. Depois disso, o limite é 05/12/2026. [T1]");
        aprova(turno, "O prazo é 04/12/2026. [ T1 , T1 , T1 , T1 , T1 , T1 ]");
    }

    @Test
    void numeroComMilharEDecimalNaoCortaAFrase() {
        FontesDoTurno turno = turno("Quanto?", "Seguro de R$ 2.000.000,00 e tarifa de R$ 312,00. A seção 3.2 trata disso.");

        aprova(turno, "O seguro é de R$ 2.000.000,00 e a tarifa de R$ 312,00 [T1].");
        aprova(turno, "O seguro é de R$ 2.000.000 [T1].");
        aprova(turno, "O seguro é de 2 milhões de reais [T1].");
        recusa(turno, "O seguro é de R$ 200.000,00 [T1].");
    }

    @Test
    void paginaSecaoVersaoETituloDaFonteValemComoParteDela() {
        FontesDoTurno turno = turno("Onde fica?", "Os geradores precisam de aviso prévio.");

        aprova(turno, "Está na página 5 [T1].");
        aprova(turno, "Está na seção 4.1 [T1].");
        aprova(turno, "É a versão 2 do manual de 2027 [T1].");
        recusa(turno, "Está na página 6 [T1].");
        recusa(turno, "Está na seção 4.2 [T1].");
        recusa(turno, "Está na página 5.");
    }

    @Test
    void numeracaoDeListaNoComecoDaLinhaNaoEhNumeroSemOrigem() {
        FontesDoTurno turno = turno("Quais?", "A tarifa é de R$ 312,00 e a taxa é de R$ 450,00.");

        aprova(turno, "Veja:\n1. A tarifa é de R$ 312 [T1]\n2. A taxa é de R$ 450 [T1]");
        aprova(turno, "**1.** A tarifa é de R$ 312 [T1]");
        recusa(turno, "Veja:\n1. A tarifa é de R$ 312 [T1]\n2. A taxa é de R$ 451 [T1]");
        recusa(turno, "Veja o item 1. A tarifa é de R$ 312 [T1]");
    }

    @Test
    void numeroNoComecoDaLinhaForaDeSequenciaNaoEListaEPrecisaDeOrigem() {
        recusa(turno("Quanto?"), "312. É o valor da tarifa em reais.");
        recusa(turno("Quanto?"), "99) kVA é o limite.");
        recusa(turno("Quanto?"), "**500.** reais é a multa.");
        recusa(turno("Quanto?"), "2. Sem o primeiro item.");
        recusa(turno("Quanto?", TARIFA), "1. A tarifa é de R$ 312 [T1]\n3. A taxa é de R$ 450 [T1]");
        aprova(turno("Quanto?", "312"), "312 é o valor da tarifa em reais [T1].");   // com origem, é só um número
    }

    @Test
    void listaEmSequenciaNosFormatosQueOModeloUsaNaoContaComoNumero() {
        FontesDoTurno turno = turno("Qual o prazo?", PRAZO);

        aprova(turno, "### 1. Prazo\nAté 04/12/2026 [T1].");
        aprova(turno, "1 - O prazo é 04/12/2026 [T1].");
        aprova(turno, "1. Prazo até 04/12/2026 [T1].\n2. Às 18h [T1].");
        aprova(turno, "1) Prazo até 04/12/2026 [T1]\n2) Às 18h [T1]\n3) No horário de Brasília [T1]");
        aprova(turno, "**1.** Prazo até 04/12/2026 [T1]\n**2.** Às 18h [T1]");
        aprova(turno, "1. Prazo até 04/12/2026 [T1].\n2. Às 18h [T1].\n1. Outra lista: 18h [T1].\n2. Fim [T1].");
    }

    @Test
    void abreviaturaComPontoNaoCortaAFrase() {
        aprova(turno("Onde?", "A regra está descrita na página 5 do manual."), "Veja a p. 5 do manual [T1].");
        aprova(turno("Onde?", "O prazo é 04/12/2026 conforme a página 5."), "O prazo é 04/12/2026 conforme a p. 5 [T1].");
    }

    // ---------------------------------------------------------------------
    // Formatos: a mesma data, a mesma hora, o mesmo número
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" é a data da fonte")
    @ValueSource(strings = {"04/12/2026", "4/12", "4 de dezembro", "4 de dezembro de 2026", "2026-12-04", "04/12/26", "4 de Dezembro"})
    void dataEmOutroFormatoDaFonteTemOrigem(String data) {
        aprova(turno("Quando?", PRAZO), "O prazo é " + data + " [T1].");
    }

    @ParameterizedTest(name = "\"{0}\" não é a data da fonte")
    @ValueSource(strings = {"04/12/2027", "14/12/2026", "4 de novembro", "05/12/2026", "2026-12-14", "4 de dezembro de 2025", "04/01/2026"})
    void dataParecidaQueNaoEADaFonteEhRecusada(String data) {
        recusa(turno("Quando?", PRAZO), "O prazo é " + data + " [T1].");
    }

    @ParameterizedTest(name = "\"{0}\" é a hora da fonte")
    @ValueSource(strings = {"18h", "18:00", "18h00", "18 horas"})
    void horaEmOutroFormatoTemOrigem(String hora) {
        aprova(turno("Que horas?", PRAZO), "Vale até as " + hora + " de 04/12/2026 [T1].");
    }

    @Test
    void horaDiferenteEhRecusada() {
        recusa(turno("Que horas?", PRAZO), "Vale até as 19h de 04/12/2026 [T1].");
        recusa(turno("Que horas?", PRAZO), "Vale até as 18h30 de 04/12/2026 [T1].");
    }

    @Test
    void percentualEMoedaIguaisSemOSimbolo() {
        aprova(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "Foi 89,4 por cento do previsto [T1].");
        aprova(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "Foi 89,4% do previsto [T1].");
        recusa(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "Foi 89,5% do previsto [T1].");
        recusa(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "Foi 94,4% do previsto [T1].");
    }

    // ---------------------------------------------------------------------
    // Origens que não são a fonte: a pessoa, hoje, a edição, as contas
    // ---------------------------------------------------------------------

    @Test
    void ONumeroDaPropriaPerguntaDaPessoaTemOrigem() {
        FontesDoTurno turno = turno("Quanto pago por 34 kVA?", TARIFA);

        aprova(turno, "Por 34 kVA, a tarifa é de R$ 312 por kVA [T1].");
        recusa(turno, "Por 35 kVA, a tarifa é de R$ 312 por kVA [T1].");
        aprova(turno("Se o briefing sair em 03/11, até quando fica pronto?"), "Com o briefing em 03/11, o prazo conta a partir dali.");
        aprova(turno("Se o briefing sair em 03/11, até quando fica pronto?"), "Com o briefing em 3 de novembro de 2026.");
    }

    @Test
    void oNumeroDeUmTurnoAnteriorDaPessoaNaoValeNesteTurno() {
        // a mensagem da pessoa neste turno é o que vale: quem monta o turno passa só a dela
        recusa(turno("Qual o prazo?"), "O prazo para 34 kVA é 04/12/2026.");
    }

    @Test
    void aDataDeHojeEOAnoDaEdicaoValemSemFonte() {
        FontesDoTurno turno = turno("Que dia é hoje?");

        aprova(turno, "Hoje é 01/11/2026.");
        aprova(turno, "Hoje é 1º de novembro de 2026.");
        aprova(turno, "Estamos falando da edição de 2027.");
        recusa(turno, "Hoje é 02/11/2026.");
        recusa(turno, "Hoje é 01/11/2025.");
        recusa(turno, "Estamos falando da edição de 2028.");
        aprova(turno, "Estamos em 2026, e a edição é a de 2027.");   // o ano de hoje faz parte da data de hoje
        recusa(turno, "Estamos falando da edição de 2025.");
    }

    @Test
    void dataSemAnoDaPessoaNaoLiberaOutroAnoContraAFonteCitada() {
        // a pessoa escreveu "04/12" sem ano; a fonte diz 04/12/2026: vale o ano da fonte
        recusa(turno("É em 04/12?", PRAZO), "O prazo é 04/12/2099 [T1].");
        recusa(turno("É em 04/12?", PRAZO), "O prazo é 04/12/2027 [T1].");
        recusa(turno("É em 04/12?", PRAZO), "O prazo é 04/12/2025 [T1].");
        aprova(turno("É em 04/12?", PRAZO), "O prazo é 04/12/2026 [T1].");
        aprova(turno("É em 04/12?", PRAZO), "O prazo é 04/12 [T1].");
        aprova(turno("É em 04/12?", PRAZO), "O prazo é 4 de dezembro de 2026 [T1].");
        // sem fonte, a data sem ano da pessoa só libera o ano da edição, o anterior a ela e o de hoje
        recusa(turno("É em 04/12?"), "É em 04/12/2099.");
        recusa(turno("É em 04/12?"), "É em 04/12/2025.");
        recusa(turno("É em 04/12?"), "É em 04/12/2031.");
        aprova(turno("É em 04/12?"), "É em 04/12/2026.");
        aprova(turno("É em 04/12?"), "É em 04/12/2027.");
        aprova(turno("É em 04/12?"), "É em 04/12.");
    }

    @Test
    void dataSemAnoNaFonteTambemSoLiberaOsAnosAceitos() {
        FontesDoTurno turno = turno("Quando?", "Informe até 04/12 às 18h.");

        aprova(turno, "O prazo é 04/12/2026 [T1].");
        aprova(turno, "O prazo é 04/12/2027 [T1].");
        recusa(turno, "O prazo é 04/12/2099 [T1].");
        recusa(turno, "O prazo é 04/12/2025 [T1].");
    }

    @Test
    void dataComAnoDaPessoaValeComOAnoQueEscreveu() {
        aprova(turno("Posso entregar em 04/12/2026?"), "Em 04/12/2026 depende do combinado.");
        recusa(turno("Posso entregar em 04/12/2026?"), "Em 04/12/2027 depende do combinado.");
        recusa(turno("Posso entregar em 04/12/2026?"), "Em 05/12/2026 depende do combinado.");
    }

    @Test
    void oAnoDaEdicaoEODeHojeSoValemComoAnoNuncaComoValor() {
        FontesDoTurno turno = turno("Quanto?");

        aprova(turno, "A edição 2027 acontece em breve.");
        aprova(turno, "Estamos em 2026, e a edição é a de 2027.");
        aprova(turno, "Planeta Atlântida 2027 acontece em breve.");
        aprova(turno, "Desde 2026 o portal existe, e vale até 2027.");
        aprova(turno, "Edição: 2027.");
        // só vale como ano se a palavra de antes for de ano: sem ela, é um valor
        recusa(turno, "São 2027 geradores.");
        recusa(turno, "São 2026 geradores.");
        recusa(turno, "A tarifa é 2027.");
        recusa(turno, "2027 credenciais.");
        recusa(turno, "Tarifa 2027 por kVA.");
        recusa(turno, "A multa é de R$ 2.027,00.");
        recusa(turno, "A multa é de R$ 2027.");
        recusa(turno, "A multa é de R$ 2.026,00.");
        recusa(turno, "São 2026 credenciais.");
        recusa(turno, "São 2027 reais.");
        recusa(turno, "São 2.027 credenciais.");
        recusa(turno, "São 2027 por cento.");
        recusa(turno, "A edição de 2028 acontece em breve.");
        // com a fonte que traz o valor, ele passa
        aprova(turno("Quanto?", "A multa é de R$ 2.027,00."), "A multa é de R$ 2.027,00 [T1].");
    }

    @Test
    void oResultadoDeUmaContaOuDeUmaContagemDeDiasDesteTurnoValeNaFraseToda() {
        FontesDoTurno turno = turno("Quanto pago por 34 kVA?");
        turno.adicionarNumeroLivre("34 * 312 = 10608");
        turno.adicionarNumeroLivre("30/11/2026");

        aprova(turno, "Dá R$ 10.608,00 no total.");
        aprova(turno, "O conteúdo fica pronto em 30/11/2026.");
        recusa(turno, "Dá R$ 10.680,00 no total.");
        recusa(turno, "O conteúdo fica pronto em 01/12/2026.");
    }

    // ---------------------------------------------------------------------
    // Citações
    // ---------------------------------------------------------------------

    @Test
    void citacaoQueNaoExisteNesteTurnoEhRecusada() {
        FontesDoTurno turno = turno("Quanto?", TARIFA);

        assertThat(recusa(turno, "A tarifa é de R$ 312 por kVA [T9].")).contains("[T9]").contains("não existe").contains("T1");
        recusa(turno, "A tarifa é de R$ 312 por kVA [R1].");
        recusa(turno, "A tarifa é de R$ 312 por kVA [C1].");
        recusa(turno, "A tarifa é de R$ 312 por kVA [T01].");
        recusa(turno, "A tarifa é de R$ 312 por kVA [T1, T2].");
        recusa(turno, "Veja o manual [T2].");
    }

    @Test
    void citacaoDeUmTurnoAnteriorNaoValeNoTurnoNovo() {
        // o turno novo ainda não buscou nada: os T de antes não existem
        FontesDoTurno turnoNovo = turno("E na Arena?");

        assertThat(recusa(turnoNovo, "Na Arena, vale o mesmo prazo [T1].")).contains("nenhuma ferramenta devolveu fonte");
        recusa(turnoNovo, "O mesmo vale na Arena [T1, T2].");
    }

    @ParameterizedTest(name = "\"{0}\" não é uma citação válida")
    @ValueSource(strings = {"[X1]", "[t1]", "[T 1]", "[1]", "[T-1]", "[T1;T2]", "[T1T2]", "[S1]", "[T1a]", "[[T1]]x[1]", "[T1", "[T", "[nota 5]"})
    void marcadorInventadoOuForaDoFormatoEhRecusado(String invento) {
        recusa(turno("Quanto?", TARIFA), "A tarifa é de R$ 312 por kVA " + invento + ".");
    }

    @Test
    void colcheteSemNumeroNaoEUmaCitacaoEPassa() {
        aprova(turno("Como é?", TARIFA), "Veja o manual [T1] e a [nota] ao fim.");
    }

    @Test
    void citacaoSemNumeroNaFraseTambemPrecisaExistir() {
        recusa(turno("Como é?", TARIFA), "Isso está descrito no manual [T3].");
        aprova(turno("Como é?", TARIFA), "Isso está descrito no manual [T1].");
    }

    @Test
    void citacaoColadaAUmParentesEhLinkEmMarkdown() {
        recusa(turno("Como é?", TARIFA), "Veja o manual [T1](https://exemplo.com).");
        recusa(turno("Como é?", TARIFA), "Veja o manual [T1](/api/documentos/1/conteudo).");
        aprova(turno("Como é?", "A seção 3.2 trata disso."), "Veja o manual [T1] (seção 3.2).");
    }

    // ---------------------------------------------------------------------
    // Forma: URL, HTML, termo interno, marcador
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Veja https://exemplo.com [T1].", "Veja http://exemplo.com/x [T1].", "Veja www.exemplo.com [T1].",
            "Veja exemplo.com.br [T1].", "Veja ![img](x) [T1].", "Veja [aqui](x) [T1].", "Veja <b>isso</b> [T1].", "Veja javascript:alert [T1].",
            "Veja mailto:a@b.invalid [T1].", "Veja ftp://x [T1]."})
    void urlLinkImagemEHtmlSaoRecusados(String texto) {
        recusa(turno("Como é?", TARIFA), texto);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Chamei buscar_documentos [T1].", "Usei a parceria_consultar [T1].", "Rodei contar_dias [T1].", "Veja gerar_modelo [T1].",
            "Fiz pedido_de_relatorio [T1].", "Rodei listar_entregas [T1].", "Rodei consultar_cota [T1].", "Veja dado:ativacao.visitantes_total [T1].",
            "O marcador [T1] aparece.", "Veja o tool [T1]."})
    void nomeDeFerramentaChaveCruaETermoInternoSaoRecusados(String texto) {
        recusa(turno("Como é?", TARIFA), texto);
    }

    @Test
    void palavrasComunsQueLembramFerramentaPassam() {
        aprova(turno("Como é?", TARIFA), "Para calcular o valor, use a tarifa por kVA [T1].");
        aprova(turno("Como é?", TARIFA), "É preciso consultar a produção e contar com a ajuda da equipe [T1].");
    }

    @Test
    void marcadorDeDadoDaParceriaResolveEPassaSemFonte() {
        aprova(turno("Quantas?"), "As aplicações comprovadas são {{cons:aplicacoes.comprovadas}} de {{cons:aplicacoes.previstas}}.");
        aprova(turno("Quem?"), "A marca {{parc:patrocinador}} participa da edição {{evento:edicao}}.");
        recusa(turno("Quantas?"), "As aplicações são {{cons:aplicacoes.inventada}}.");
        recusa(turno("Quantas?"), "Os visitantes foram {{dado:ativacao.visitantes_total}}.");
        recusa(turno("Quantas?"), "Os visitantes foram {{dado:ativacao.visitantes_total.");
        recusa(turno("Quantas?"), "Há uma chave solta } aqui.");
    }

    @Test
    void numeroForaDoMarcadorContinuaPrecisandoDeOrigemEvaleDeNovoSeEstiverNaFonte() {
        aprova(turno("Quantas?", "Foram vendidas 17 cotas."), "Foram {{cons:aplicacoes.comprovadas}} aplicações e 17 cotas [T1].");
        recusa(turno("Quantas?", "Foram vendidas 17 cotas."), "Foram {{cons:aplicacoes.comprovadas}} aplicações e 18 cotas [T1].");
        recusa(turno("Quantas?"), "As aplicações comprovadas são 17.");
    }

    // ---------------------------------------------------------------------
    // Por extenso e algarismos de outros alfabetos
    // ---------------------------------------------------------------------

    @Test
    void numeralPorExtensoComUnidadeContaComoNumero() {
        recusa(turno("Quantas?"), "Foram comprovadas dezessete aplicações.");
        recusa(turno("Quanto?", TARIFA), "A tarifa é de trezentos e vinte e um reais por kVA [T1].");
        recusa(turno("Quanto?"), "A tarifa é de três mil reais.");
        aprova(turno("Quanto?", TARIFA), "A tarifa é de trezentos e doze reais por kVA [T1].");
        aprova(turno("Quanto?", "A cota dá direito a quarenta cortesias."), "A cota tem 40 cortesias [T1].");
        aprova(turno("Quanto?"), "Os dois manuais são válidos.");
        aprova(turno("Quanto?"), "Vale um dia de antecedência.");
    }

    @Test
    void dataPorExtensoPrecisaDeOrigemComoQualquerOutraData() {
        // a regra do furo: "dez de dezembro" não é "4 de dezembro" só porque a fonte tem uma data em dezembro
        assertThat(recusa(turno("Quando?", PRAZO), "O prazo é dez de dezembro [T1].")).contains("dez de dezembro");
        recusa(turno("Quando?", PRAZO), "O prazo é vinte e cinco de dezembro [T1].");
        recusa(turno("Quando?"), "O prazo é vinte e cinco de dezembro.");
        recusa(turno("Quando?", PRAZO), "O prazo é quatro de novembro [T1].");
        aprova(turno("Quando?", PRAZO), "O prazo é quatro de dezembro [T1].");
        aprova(turno("Quando?", PRAZO), "O prazo é quatro de dezembro de 2026 [T1].");
        recusa(turno("Quando?", PRAZO), "O prazo é quatro de dezembro de 2027 [T1].");
        aprova(turno("Quando?", "Os dias sete e oito de dezembro"), "Valem sete de dezembro e 8 de dezembro [T1].");
    }

    @Test
    void numeralPorExtensoSemUnidadeTambemContaComoNumero() {
        recusa(turno("Quanto?"), "A tarifa é de trezentos e doze por kVA.");
        recusa(turno("Quanto?"), "A tarifa é de R$ trezentos e doze por kVA.");
        recusa(turno("Quanto?"), "O limite é vinte %.");
        recusa(turno("Quanto?"), "O limite é vinte porcento.");
        recusa(turno("Quanto?"), "A multa é de dois milhoes de reais.");
        recusa(turno("Quanto?"), "A multa é de dois milhões de reais.");
        recusa(turno("Quanto?"), "São doze convites.");
        recusa(turno("Quanto?"), "São cem.");
        aprova(turno("Quanto?", "São 312"), "São trezentos e doze [T1].");
        aprova(turno("Quanto?", "São doze convites por cota."), "São 12 convites [T1].");
        aprova(turno("Quanto?", "São 12 convites por cota."), "São doze convites [T1].");
    }

    @Test
    void gruposSeparadosPorEspacoSaoUmNumeroSoEPrecisamDeOrigemComoUm() {
        FontesDoTurno turno = turno("Quanto?", "O gerador custa R$ 220,00 por dia, e a cota dá direito a 2 geradores.");

        recusa(turno, "O custo total é R$ 2 220,00 [T1].");
        recusa(turno, "O custo total é R$ 2 220 [T1].");
        recusa(turno, "O custo total é R$ 2\u00A0220,00 [T1].");
        aprova(turno, "O gerador custa R$ 220,00 [T1].");
        aprova(turno, "A cota dá direito a 2 geradores [T1].");
        aprova(turno, "O gerador custa R$ 220,00 e a cota dá direito a 2 geradores [T1].");
    }

    @Test
    void numeroComEspacoDeMilharNaFonteVaiPorInteiroEPorPartes() {
        FontesDoTurno turno = turno("Quanto?", "A multa é de R$ 2 220,00.");

        aprova(turno, "A multa é de R$ 2.220,00 [T1].");
        aprova(turno, "A multa é de R$ 2 220,00 [T1].");
        aprova(turno, "A multa é de R$ 2220 [T1].");
        aprova(turno, "A multa tem a parcela de R$ 220,00 [T1].");   // coluna de tabela: as partes valem
        recusa(turno, "A multa é de R$ 22 200,00 [T1].");
    }

    @Test
    void zeroPorExtensoContaComoNumero() {
        recusa(turno("Quantos?"), "Hoje há zero geradores.");
        recusa(turno("Quantos?", "Pode ter até 2 geradores."), "Hoje há zero geradores [T1].");
        aprova(turno("Quantos?", "Hoje há 0 geradores."), "Hoje há zero geradores [T1].");
        aprova(turno("Quantos?", "Hoje há zero geradores."), "Hoje há 0 geradores [T1].");
    }

    @Test
    void umEUmaSaoArtigoENuncaSaoOrigemDe1() {
        aprova(turno("Quanto?"), "Vale um dia de antecedência e uma credencial por pessoa.");
        // "uma credencial" na pergunta não é o 1 de "R$ 1,00"
        recusa(turno("Qual é a multa por uma credencial?"), "A multa é de R$ 1,00.");
        recusa(turno("Quanto?", "um valor qualquer da tabela"), "A multa é de R$ 1,00 [T1].");
        recusa(turno("Quanto?", "uma cota por marca"), "São 1 cota [T1].");
        aprova(turno("Qual é a multa por 1 credencial?"), "Para 1 credencial, a multa vale o combinado.");
    }

    @Test
    void numeraisForaDeOrdemDeGrandezaSaoNumerosSeparadosEcadaUmTemDeTerOrigem() {
        recusa(turno("Quantos?", "Os lotes três e quatro abrem antes"), "São 7 lotes [T1].");
        aprova(turno("Quantos?", "Os lotes três e quatro abrem antes"), "O lote 3 [T1].");
        aprova(turno("Quantos?", "Os lotes três e quatro abrem antes"), "Os lotes 3 e 4 abrem antes [T1].");
        recusa(turno("Quantos?", "Os dias sete e oito de dezembro"), "É no dia 15 [T1].");
        aprova(turno("Quantos?", "Os dias sete e oito de dezembro"), "É no dia 8 de dezembro [T1].");
        recusa(turno("Quantos?", "Os valores dez e trinta"), "São 40 [T1].");
        aprova(turno("Quantos?", "Os valores dez e trinta"), "São 10 e 30 [T1].");
    }

    @Test
    void porCentoDepoisDeAlgarismoNaoEONumeroCem() {
        aprova(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "São 89,4 por cento [T1].");
        aprova(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "São 89,4 por  cento [T1].");
        recusa(turno("Quanto?", "O percentual comprovado foi de 89,4% do previsto."), "São cem por cento [T1].");
    }

    @Test
    void algarismoDeOutroAlfabetoDeLarguraTotalOuSobrescritoNaoEscapa() {
        recusa(turno("Quanto?"), "A tarifa é de ٣١٢ por kVA.");   // árabe-índico
        recusa(turno("Quanto?"), "A tarifa é de ３１２ por kVA.");   // largura total
        recusa(turno("Quanto?"), "A tarifa é de ३१२ por kVA.");   // devanágari
        recusa(turno("Quanto?"), "É o limite de 3² metros.");   // sobrescrito
        aprova(turno("Quanto?", TARIFA), "A tarifa é de ３１２ por kVA [T1].");
        aprova(turno("Quanto?", TARIFA), "A tarifa é de ٣١٢ por kVA [T1].");
    }

    @Test
    void digitoPartidoPorCaractereInvisivelNaoVira17() {
        recusa(turno("Quantas?", "Foram 17 cotas."), "Foram 1​7 cotas [T1].");
        recusa(turno("Quantas?", "Foram 17 cotas."), "Foram 1­7 cotas [T1].");
    }

    // ---------------------------------------------------------------------
    // Texto vazio e nulo
    // ---------------------------------------------------------------------

    @Test
    void textoVazioEmBrancoOuNuloEhRecusado() {
        FontesDoTurno turno = turno("Oi");

        assertThat(chat(turno, null).aprovado()).isFalse();
        assertThat(chat(turno, "").motivo()).isEqualTo("O texto está vazio.");
        assertThat(chat(turno, "   \n\t ").aprovado()).isFalse();
    }

    @Test
    void respostaSemNumeroNemCitacaoPassa() {
        aprova(turno("Oi"), "Olá! Posso ajudar com os documentos e os dados da sua parceria.");
        aprova(turno("Oi"), "Não encontrei isso nos documentos do portal.");
    }

    @Test
    void oTextoAprovadoVoltaComOsEspacosNormalizados() {
        Veredito veredito = chat(turno("Quanto?", TARIFA), "  A tarifa é\nR$ 312   por kVA [T1].  ");

        assertThat(veredito.aprovado()).isTrue();
        assertThat(veredito.texto()).isEqualTo("A tarifa é R$ 312 por kVA [T1].");
    }
}
