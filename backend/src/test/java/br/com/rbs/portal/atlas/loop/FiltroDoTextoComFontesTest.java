package br.com.rbs.portal.atlas.loop;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.checagem.ChecagemDeFontes;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [VALIDACAO] O filtro do fluxo segura a frase inteira e só então confere e repassa (M4, critério bloqueante). Direto sobre o
 * {@code FiltroDoTexto}, com os pedaços que o modelo mandaria: um número cuja citação vem no fim da frase, ou logo depois do ponto,
 * passa; {@code 1.250,00} e {@code 3.2} não cortam; a frase sem a citação do número dispara o {@code texto_recusado}; a citação de
 * outra frase não apoia esta; e a citação que chega letra a letra não deixa escapar o que veio antes. O fluxo HTTP inteiro está no
 * AtlasFluxoTest.
 */
@TesteDeIntegracao
@Transactional
class FiltroDoTextoComFontesTest {

    private static final String PRAZO = "Os geradores devem ser informados à produção até 04/12/2026, às 18h.";
    private static final String TARIFA = "A tarifa de energia é de R$ 312,00 por kVA e o seguro é de R$ 1.250,00. A seção 3.2 trata disso.";

    @Autowired
    ChecagemDeFontes checagem;

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    ModeloBase modelos;

    @Autowired
    JdbcClient jdbc;

    /** Anota o que vai à tela, como "texto:..." e "texto_recusado". */
    static final class Anotador implements OuvinteDoTurno {

        final List<String> eventos = new ArrayList<>();

        @Override
        public void pensando() {
            eventos.add("pensando");
        }

        @Override
        public void resumo(String texto) {
            eventos.add("resumo:" + texto);
        }

        @Override
        public void etapa(String texto) {
            eventos.add("etapa:" + texto);
        }

        @Override
        public void texto(String pedaco) {
            eventos.add("texto:" + pedaco);
        }

        @Override
        public void textoRecusado() {
            eventos.add("texto_recusado");
        }
    }

    Resolucao doAmarelo() {
        return resolvedor.abrir(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"),
                modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
    }

    FontesDoTurno turno(String... trechos) {
        FontesDoTurno turno = new FontesDoTurno("Pergunta.", 2027, LocalDate.of(2026, 11, 1));
        for (int i = 0; i < trechos.length; i++) {
            turno.registrarTrecho(100 + i, 10, "Manual de Operações 2027", 2, 5, "4 Energia › 4.1 Tarifa", trechos[i]);
        }
        return turno;
    }

    /** Manda os pedaços ao filtro, fecha a ida, e devolve os eventos e o filtro. */
    record Passagem(List<String> eventos, FiltroDoTexto filtro) {
    }

    Passagem passar(FontesDoTurno turno, String... pedacos) {
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);
        for (String pedaco : pedacos) {
            filtro.receber(pedaco);
        }
        filtro.fechar();
        return new Passagem(anotador.eventos, filtro);
    }

    @Test
    void numeroComACitacaoNoFimDaFraseSoSaiQuandoAFraseTermina() {
        FontesDoTurno turno = turno(PRAZO);
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        filtro.receber("O prazo é ");
        filtro.receber("04/12/2026 ");
        filtro.receber("[T1]");
        assertThat(anotador.eventos).as("a frase ainda não terminou: nada sai").isEmpty();
        filtro.receber(". Depois disso, nada muda.");
        assertThat(anotador.eventos).as("a primeira frase terminou quando a segunda começou").containsExactly("texto:O prazo é 04/12/2026 [T1].");
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto:O prazo é 04/12/2026 [T1].", "texto: Depois disso, nada muda.");
        assertThat(filtro.recusado()).isFalse();
    }

    @Test
    void citacaoLogoDepoisDoPontoFicaPresaAFraseDeAntes() {
        FontesDoTurno turno = turno(PRAZO);
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        filtro.receber("O prazo é 04/12/2026.");
        filtro.receber(" ");
        assertThat(anotador.eventos).as("depois do ponto pode vir a citação").isEmpty();
        filtro.receber("[");
        filtro.receber("T");
        filtro.receber("1");
        assertThat(anotador.eventos).as("a citação ainda está chegando").isEmpty();
        filtro.receber("]");
        filtro.receber(" ");
        assertThat(anotador.eventos).as("depois da citação pode vir outra").isEmpty();
        filtro.receber("Depois, nada.");

        assertThat(anotador.eventos).containsExactly("texto:O prazo é 04/12/2026. [T1]");
        filtro.fechar();
        assertThat(anotador.eventos).containsExactly("texto:O prazo é 04/12/2026. [T1]", "texto: Depois, nada.");
    }

    @Test
    void milharDecimalESecaoNaoCortamAFrase() {
        FontesDoTurno turno = turno(TARIFA);
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        filtro.receber("A tarifa é de R$ 312,00 e o seguro de R$ 1.");
        filtro.receber("250,00, na seção 3.");
        filtro.receber("2 [T1] ");
        assertThat(anotador.eventos).as("os pontos de 1.250,00 e 3.2 não terminam a frase").isEmpty();
        filtro.receber("do manual.");
        assertThat(anotador.eventos).as("o ponto final ainda pode ser seguido de citação").isEmpty();
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto:A tarifa é de R$ 312,00 e o seguro de R$ 1.250,00, na seção 3.2 [T1] do manual.");
    }

    @Test
    void fraseComNumeroSemCitacaoDisparaOTextoRecusadoENadaChegaATela() {
        Passagem passagem = passar(turno(PRAZO), "O prazo é 04/12/2026 e ", "não muda mais.");

        assertThat(passagem.eventos()).containsExactly("texto_recusado");
        assertThat(passagem.filtro().recusado()).isTrue();
        assertThat(passagem.filtro().motivoDaRecusa()).contains("04/12/2026").contains("não cita nenhuma fonte").contains("[T1]");
        assertThat(passagem.filtro().caracteresNaRecusa()).isEqualTo("O prazo é 04/12/2026 e não muda mais.".length());
    }

    @Test
    void aCitacaoDeOutraFraseNaoApoiaEstaEAFraseBoaDeAntesJaSaiu() {
        FontesDoTurno turno = turno(PRAZO, "A taxa de cadastro de gerador é de R$ 450,00.");
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        filtro.receber("Tudo certo com o pedido. ");
        filtro.receber("O prazo é 04/12/2026. ");
        filtro.receber("A taxa é R$ 450 [T2]. ");
        filtro.fechar();

        // a primeira frase não tem número e saiu; a segunda tem o prazo sem a citação do prazo e foi recusada, e com ela o resto
        assertThat(anotador.eventos).containsExactly("texto:Tudo certo com o pedido.", "texto_recusado");
    }

    @Test
    void citacaoQueNaoExisteNesteTurnoEhRecusadaMesmoSemNumero() {
        Passagem passagem = passar(turno(PRAZO), "Veja o manual [T7].");

        assertThat(passagem.eventos()).containsExactly("texto_recusado");
        assertThat(passagem.filtro().motivoDaRecusa()).contains("[T7]").contains("não existe");
    }

    @Test
    void citacaoQueChegaLetraALetraNaoDeixaEscaparOQueVeioAntes() {
        FontesDoTurno turno = turno(PRAZO);
        String texto = "O prazo é 04/12/2026 [T1]. A regra vale para todas as marcas. Outro prazo é 05/12/2026 [T1].";
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        for (char c : texto.toCharArray()) {
            filtro.receber(String.valueOf(c));
        }
        filtro.fechar();

        // as duas primeiras frases passaram, cada uma quando a seguinte começou; a terceira tem a data errada e foi recusada inteira
        assertThat(anotador.eventos).containsExactly("texto:O prazo é 04/12/2026 [T1].", "texto: A regra vale para todas as marcas.",
                "texto_recusado");
        assertThat(filtro.recusado()).isTrue();
    }

    @Test
    void citacaoLongaComEspacosQueChegaLetraALetraNaoDisparaTextoRecusado() {
        // seis trechos: [ T1 , T2 , ... T6 ] passa de 24 caracteres, e antes o filtro cortava a frase no meio dela
        FontesDoTurno turno = turno(PRAZO, "Segundo trecho.", "Terceiro trecho.", "Quarto trecho.", "Quinto trecho.", "Sexto trecho.");
        String texto = "O prazo é 04/12/2026. [ T1 , T2 , T3 , T4 , T5 , T6 ]";
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        for (char c : texto.toCharArray()) {
            filtro.receber(String.valueOf(c));
        }
        filtro.fechar();

        assertThat(anotador.eventos).doesNotContain("texto_recusado");
        assertThat(filtro.recusado()).isFalse();
        assertThat(String.join("", anotador.eventos.stream().map(e -> e.substring("texto:".length())).toList())).isEqualTo(texto);
        // e o texto inteiro, de uma vez, aprova: o fluxo e a checagem final concordam
        assertThat(checagem.chat(doAmarelo(), turno, texto).aprovado()).isTrue();
    }

    @Test
    void caractereInvisivelNoMeioDoNumeroSaiDoTextoQueVaiATela() {
        FontesDoTurno turno = turno(PRAZO);
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        filtro.receber("O prazo é 04/12/20");
        filtro.receber("\u200B");
        filtro.receber("26\u202E [T1]. Fim.");
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto:O prazo é 04/12/2026 [T1].", "texto: Fim.");
        assertThat(filtro.recusado()).isFalse();
        // o que tem número em outra escrita, sem equivalente, é recusado na tela também
        assertThat(passar(turno(PRAZO), "São Ⅳ dias [T1].").eventos()).containsExactly("texto_recusado");
    }

    @Test
    void negritoFechaAFraseNoFluxoEACitacaoDaOutraNaoApoiaONumero() {
        FontesDoTurno turno = turno("A multa era 09/09/2026.", "A tarifa é R$ 312,00.");
        String texto = "**A multa é 09/09/2026.** A tarifa é R$ 312,00 [T2].";
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        for (char c : texto.toCharArray()) {
            filtro.receber(String.valueOf(c));
        }
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto_recusado");
        assertThat(filtro.motivoDaRecusa()).contains("09/09/2026");
    }

    @Test
    void textoBomPedacoAPedacoSaiInteiroEEmFrasesCompletas() {
        FontesDoTurno turno = turno(PRAZO);
        String texto = "O prazo é 04/12/2026 [T1]. Vale até as 18h [T1]. Na dúvida, fale com a produção.";
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno, anotador);

        for (char c : texto.toCharArray()) {
            filtro.receber(String.valueOf(c));
        }
        filtro.fechar();

        assertThat(String.join("", anotador.eventos.stream().map(e -> e.substring("texto:".length())).toList())).isEqualTo(texto);
        // nada sai no meio de uma frase: cada pedaço (menos o último) termina no ponto ou na citação do ponto
        for (String evento : anotador.eventos.subList(0, anotador.eventos.size() - 1)) {
            assertThat(evento.strip()).matches(".*(\\.|\\])$");
        }
        assertThat(anotador.eventos).hasSize(3);
    }

    @Test
    void respostaSemPontuacaoSaiNoFecharEMarcadorAbertoSegura() {
        assertThat(passar(turno(), "Isto não ", "tem ponto ", "final").eventos()).containsExactly("texto:Isto não tem ponto final");
        assertThat(passar(turno(), "Para ", "{{parc:patrocinador e fim.").eventos()).containsExactly("texto_recusado");
        assertThat(passar(turno(), "Para {{parc:patrocinador}}, ", "tudo certo.").eventos()).containsExactly("texto:Para Cliente Amarelo, tudo certo.");
    }

    @Test
    void depoisDeRecusarOFiltroIgnoraOQueChegaERecusarDuasVezesAvisaUmaSo() {
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(doAmarelo(), checagem, turno(), anotador);

        filtro.receber("Foram 17 aplicações. ");
        filtro.receber("Mais texto. Mais outro.");
        filtro.recusar();
        filtro.recusar();
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto_recusado");
    }

    @Test
    void numeroPorExtensoComUnidadeTambemEhRecusadoNaTela() {
        Passagem passagem = passar(turno(PRAZO), "Foram comprovadas dezessete aplicações.");

        assertThat(passagem.eventos()).containsExactly("texto_recusado");
    }

    // ---------------------------------------------------------------------
    // M5: a conta do calcular na tela ao vivo
    // ---------------------------------------------------------------------

    /** Uma resolução do Amarelo que já recebeu a conta {@code tarifa} do turno (R$ 10.608,00), como a que o loop abre a cada ida. */
    Resolucao comATarifa() {
        Resolucao resolucao = doAmarelo();
        resolucao.registrarConta(new br.com.rbs.portal.atlas.relatorio.ContaDoChat("tarifa", new java.math.BigDecimal("10608.00"),
                "10.608,00", "preco * area", null, "moeda", List.of()));
        return resolucao;
    }

    FontesDoTurno turnoComATarifa() {
        FontesDoTurno turno = turno();
        turno.registrarCalculo("tarifa", "tarifa", "Conta tarifa: preco = 312; area = 34; resultado = 10.608,00.", java.util.Map.of());
        return turno;
    }

    @Test
    void oMarcadorDaContaDoChatSaiResolvidoNaTelaAoVivoENuncaComoIndisponivel() {
        Anotador anotador = new Anotador();
        FiltroDoTexto filtro = new FiltroDoTexto(comATarifa(), checagem, turnoComATarifa(), anotador);

        filtro.receber("A tarifa é R$ {{calc:");
        filtro.receber("tarifa}} [C");
        filtro.receber("1]. Fora isso, nada muda.");
        filtro.fechar();

        assertThat(anotador.eventos).containsExactly("texto:A tarifa é R$ 10.608,00 [C1].", "texto: Fora isso, nada muda.");
        assertThat(anotador.eventos).noneMatch(evento -> evento.contains("indisponível") || evento.contains("{{"));
        assertThat(filtro.recusado()).isFalse();
    }

    @Test
    void semAContaNaResolucaoOMarcadorEhRecusadoEUmaCitacaoDeContaQueNaoExisteTambem() {
        Anotador sem = new Anotador();
        FiltroDoTexto semConta = new FiltroDoTexto(doAmarelo(), checagem, turno(), sem);
        semConta.receber("A tarifa é R$ {{calc:tarifa}}. Fim.");
        semConta.fechar();

        assertThat(semConta.recusado()).isTrue();
        assertThat(sem.eventos).containsExactly("texto_recusado");
        assertThat(semConta.motivoDaRecusa()).contains("calc:tarifa");

        Anotador citacao = new Anotador();
        FiltroDoTexto comC9 = new FiltroDoTexto(comATarifa(), checagem, turnoComATarifa(), citacao);
        comC9.receber("A tarifa é R$ {{calc:tarifa}} [C9]. Fim.");
        comC9.fechar();

        assertThat(comC9.recusado()).isTrue();
        assertThat(citacao.eventos).containsExactly("texto_recusado");
        assertThat(comC9.motivoDaRecusa()).contains("C9");
    }
}
