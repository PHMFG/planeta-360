package br.com.rbs.portal.atlas.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O {@code contar_dias} (M5, bloqueante do plano): o dia de início não conta e o fim conta; os exemplos do gabarito (18 dias
 * úteis depois de 03/11/2026 dá 30/11/2026, 20 dá 02/12/2026, 10 úteis depois de 16/11/2026 dá 01/12/2026); os dois sentidos; o fim
 * em vez da quantidade; os feriados que ficaram de fora; a fonte {@code [C#]} com tudo o que a resposta pode citar; e todo
 * argumento errado volta ao modelo como erro. Unitário, sem banco: a ferramenta não usa a conversa.
 */
class ContarDiasTest {

    private final ContarDias ferramenta = new ContarDias(new Feriados(false));
    private final FontesDoTurno turno = new FontesDoTurno("Quantos dias?", 2027, LocalDate.of(2026, 11, 1));

    Map<String, Object> contar(String argumentos) {
        return contar(ferramenta, turno, argumentos);
    }

    /**
     * Conta com a origem em ordem: a pessoa "escreveu" as datas e a quantidade que os argumentos usam. Os testes da conta não são sobre
     * a origem (veja os de "A origem dos números e das datas", que usam {@link #contarComOQueTem}).
     */
    static Map<String, Object> contar(ContarDias ferramenta, FontesDoTurno turno, String argumentos) {
        comOrigem(turno, argumentos);
        return contarComOQueTem(ferramenta, turno, argumentos);
    }

    static Map<String, Object> contarComOQueTem(ContarDias ferramenta, FontesDoTurno turno, String argumentos) {
        Execucao execucao = ferramenta.executar(null, turno, Argumentos.ler(argumentos), "call_x", null);
        assertThat(execucao.pausa()).isFalse();
        return AtlasJson.ler(execucao.resultadoJson(), new TypeReference<Map<String, Object>>() {
        });
    }

    static void comOrigem(FontesDoTurno turno, String argumentos) {
        Map<String, Object> lidos = Argumentos.ler(argumentos);
        StringBuilder escrito = new StringBuilder();
        for (String campo : List.of("inicio", "fim")) {
            if (lidos.get(campo) instanceof String texto) {
                try {
                    escrito.append(DIA.format(LocalDate.parse(texto))).append(". ");
                } catch (RuntimeException invalida) {
                    // data que não existe: o erro de formato vem antes da origem
                }
            }
        }
        if (lidos.get("dias") instanceof Number dias) {
            escrito.append(dias).append(" dias.");
        }
        List<String> mensagens = new java.util.ArrayList<>(turno.mensagensAnterioresDaPessoa());
        mensagens.add(escrito.toString());
        turno.comMensagensAnteriores(mensagens);
    }

    private static final java.time.format.DateTimeFormatter DIA = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 1);

    private Map<String, String> camposSemOrigem(FontesDoTurno turno, String argumentos) {
        return org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> ferramenta.executar(null, turno, Argumentos.ler(argumentos), "call_x", null)).campos();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> feriados(Map<String, Object> resultado) {
        return (List<Map<String, String>>) resultado.get("feriadosConsiderados");
    }

    /** Tudo o que o modelo lê do erro: a frase e o motivo de cada campo. */
    String erro(String argumentos) {
        comOrigem(turno, argumentos);
        DadosInvalidosException erro = org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> ferramenta.executar(null, turno, Argumentos.ler(argumentos), "call_x", null));
        return erro.getMessage() + " " + String.join(" ", erro.campos().values());
    }

    Map<String, String> campos(String argumentos) {
        comOrigem(turno, argumentos);
        return org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> ferramenta.executar(null, turno, Argumentos.ler(argumentos), "call_x", null)).campos();
    }

    // ---------------------------------------------------------------------
    // Os exemplos do gabarito
    // ---------------------------------------------------------------------

    @Test
    void dezoitoDiasUteisDepoisDe03_11_2026DaOUltimoDiaDeNovembro() {
        Map<String, Object> resultado = contar("{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}");

        assertThat(resultado).containsEntry("marcador", "C1").containsEntry("resultado", "30/11/2026");
        assertThat(feriados(resultado)).containsExactly(Map.of("data", "20/11/2026", "nome", "Dia da Consciência Negra"));
    }

    @Test
    void vinteDiasUteisDepoisDe03_11_2026Dao02_12_2026() {
        Map<String, Object> resultado = contar("{\"inicio\":\"2026-11-03\",\"dias\":20,\"uteis\":true}");

        assertThat(resultado).containsEntry("resultado", "02/12/2026");
    }

    @Test
    void dezDiasUteisDepoisDe16_11_2026Dao01_12_2026() {
        Map<String, Object> resultado = contar("{\"inicio\":\"2026-11-16\",\"dias\":10,\"uteis\":true}");

        assertThat(resultado).containsEntry("resultado", "01/12/2026");
        assertThat(feriados(resultado)).extracting(f -> f.get("data")).containsExactly("20/11/2026");
    }

    @Test
    void oDiaDeInicioNaoContaMesmoQuandoENaoUtil() {
        // 07/11/2026 é sábado: 1 dia útil depois dele é segunda, 09/11/2026
        assertThat(contar("{\"inicio\":\"2026-11-07\",\"dias\":1,\"uteis\":true}")).containsEntry("resultado", "09/11/2026");
        // e 1 dia corrido depois do dia 03 é o dia 04
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":1}")).containsEntry("resultado", "04/11/2026");
    }

    // ---------------------------------------------------------------------
    // Sentido, dias corridos e fim
    // ---------------------------------------------------------------------

    @Test
    void diasCorridosDepoisEAntes() {
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":30}")).containsEntry("resultado", "03/12/2026");
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":10,\"sentido\":\"antes\"}")).containsEntry("resultado", "24/10/2026");
        assertThat(feriados(contar("{\"inicio\":\"2026-11-03\",\"dias\":30}"))).as("feriado só aparece com dias úteis").isEmpty();
    }

    @Test
    void diasUteisAntesContaParaTras() {
        Map<String, Object> cinco = contar("{\"inicio\":\"2026-11-30\",\"dias\":5,\"uteis\":true,\"sentido\":\"antes\"}");
        Map<String, Object> dez = contar("{\"inicio\":\"2026-12-02\",\"dias\":10,\"uteis\":true,\"sentido\":\"antes\"}");

        assertThat(cinco).containsEntry("resultado", "23/11/2026");
        assertThat(feriados(cinco)).as("20/11 está entre 23/11 e 30/11? não: fica fora do intervalo").isEmpty();
        assertThat(dez).containsEntry("resultado", "17/11/2026");
        assertThat(feriados(dez)).extracting(f -> f.get("data")).containsExactly("20/11/2026");
    }

    @Test
    void comFimDevolveAQuantidadeDeDias() {
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\"}")).containsEntry("resultado", "27");
        Map<String, Object> uteis = contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\",\"uteis\":true}");
        assertThat(uteis).containsEntry("resultado", "18");
        assertThat(feriados(uteis)).extracting(f -> f.get("data")).containsExactly("20/11/2026");
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-03\"}")).as("do dia até o mesmo dia").containsEntry("resultado", "0");
    }

    @Test
    void fimEmDiaNaoUtilComUteisNaoEContado() {
        // 21/11/2026 é sábado: de 03/11 até lá são 12 dias úteis (o 20 é feriado)
        Map<String, Object> resultado = contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-21\",\"uteis\":true}");

        assertThat(resultado).containsEntry("resultado", "12");
        assertThat(feriados(resultado)).extracting(f -> f.get("data")).containsExactly("20/11/2026");
    }

    @Test
    void feriadoQueCaiNoFimDeSemanaNaoEPulado() {
        // 15/11/2026 é domingo: contar até 16/11 (segunda) não lista a Proclamação da República
        Map<String, Object> resultado = contar("{\"inicio\":\"2026-11-09\",\"fim\":\"2026-11-16\",\"uteis\":true}");

        assertThat(resultado).containsEntry("resultado", "5");
        assertThat(feriados(resultado)).isEmpty();
    }

    @Test
    void oPontoFacultativoSoEntraQuandoLigado() {
        ContarDias com = new ContarDias(new Feriados(true));

        // 13/02/2026 é sexta, e a segunda e a terça seguintes são o Carnaval: 5 dias úteis depois
        String argumentos = "{\"inicio\":\"2026-02-13\",\"dias\":5,\"uteis\":true}";
        assertThat(contar(com, turno, argumentos)).containsEntry("resultado", "24/02/2026");
        assertThat(contar(ferramenta, new FontesDoTurno("x", 2027, LocalDate.of(2026, 11, 1)), argumentos)).containsEntry("resultado", "20/02/2026");
    }

    @Test
    void asDatasValemParaOsAnosDeLimite() {
        assertThat(contar("{\"inicio\":\"2000-01-01\",\"dias\":1}")).containsEntry("resultado", "02/01/2000");
        assertThat(contar("{\"inicio\":\"2100-12-30\",\"dias\":1}")).containsEntry("resultado", "31/12/2100");
    }

    // ---------------------------------------------------------------------
    // A regra, a fonte e o que a resposta pode citar
    // ---------------------------------------------------------------------

    @Test
    void aRegraDizInicioQuantidadeFimEOQueFicaDeFora() {
        String uteis = (String) contar("{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}").get("regra");
        String corridos = (String) contar("{\"inicio\":\"2026-11-03\",\"dias\":30,\"sentido\":\"antes\"}").get("regra");
        String ate = (String) contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\",\"uteis\":true}").get("regra");

        assertThat(uteis).contains("18", "dias úteis", "depois de 03/11/2026", "sem contar o dia de início");
        assertThat(corridos).contains("30", "dias corridos", "antes de 03/11/2026", "sem contar o dia de início");
        assertThat(ate).contains("03/11/2026", "30/11/2026", "dias úteis", "sem contar o dia de início");
    }

    @Test
    void aFonteTrazTudoOQueARespostaPodeCitarEOResultadoValeEmQualquerFrase() {
        contar("{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}");

        Fonte fonte = turno.fonte("C1").orElseThrow();
        assertThat(fonte.tipo()).isEqualTo(FontesDoTurno.Tipo.CALCULO);
        assertThat(fonte.titulo()).isEqualTo("Contagem de dias");
        assertThat(fonte.texto()).contains("03/11/2026", "18", "30/11/2026", "20/11/2026", "Dia da Consciência Negra");
        assertThat(fonte.dados()).containsEntry("resultado", "30/11/2026").containsKey("regra").containsKey("feriadosConsiderados");
        assertThat(turno.numerosLivres()).contains("30/11/2026");
        assertThat(turno.contas()).as("a contagem de dias não é uma conta com nome").isEmpty();
    }

    @Test
    void oResultadoEmQuantidadeTambemViraNumeroLivre() {
        contar("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\",\"uteis\":true}");

        assertThat(turno.numerosLivres()).contains("18");
    }

    @Test
    void cadaContagemGanhaOProximoMarcadorDeC() {
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":1}")).containsEntry("marcador", "C1");
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":2}")).containsEntry("marcador", "C2");
        assertThat(turno.existe("C2")).isTrue();
    }

    // ---------------------------------------------------------------------
    // A origem dos números e das datas (critério bloqueante: número sem origem)
    // ---------------------------------------------------------------------

    @Test
    void diasQueNenhumaFonteNemMensagemDaPessoaTemNaoValeEOResultadoNaoViraNumeroLivre() {
        FontesDoTurno vazio = new FontesDoTurno("Quanto fica?", 2027, HOJE);

        Map<String, String> campos = camposSemOrigem(vazio, "{\"inicio\":\"2026-11-01\",\"dias\":312}");

        assertThat(campos).containsOnlyKeys("dias");
        assertThat(campos.get("dias")).contains("312", "pessoa escreveu", "fonte");
        assertThat(vazio.fontes()).isEmpty();
        assertThat(vazio.numerosLivres()).isEmpty();
    }

    @Test
    void diasValeSeEstaNaMensagemDaPessoaEmUmaMensagemAnteriorOuEmQualquerFonteDoTurno() {
        String argumentos = "{\"inicio\":\"2026-11-01\",\"dias\":312}";

        assertThat(contarComOQueTem(ferramenta, new FontesDoTurno("Daqui a 312 dias, que dia cai?", 2027, HOJE), argumentos))
                .containsEntry("resultado", "09/09/2027");
        assertThat(contarComOQueTem(ferramenta, new FontesDoTurno("E agora?", 2027, HOJE).comMensagensAnteriores(List.of("Faltam 312 dias?")),
                argumentos)).containsKey("resultado");
        FontesDoTurno comRegistro = new FontesDoTurno("Quanto?", 2027, HOJE);
        comRegistro.registrarRegistro("prazo", 1, "Prazo", "Prazos", "Prazo: 09/09/2027. Faltam 312 dias.", Map.of());
        assertThat(contarComOQueTem(ferramenta, comRegistro, argumentos)).containsKey("resultado");
        // o título e a página de uma fonte não são o texto dela, como no calcular
        FontesDoTurno soNoTitulo = new FontesDoTurno("Quanto?", 2027, HOJE);
        soNoTitulo.registrarTrecho(1, 1, "Manual 312", 312, 312, "Seção 312", "Um texto sem número.");
        assertThat(camposSemOrigem(soNoTitulo, argumentos)).containsOnlyKeys("dias");
        // o ano não é valor: o 2027 de "Para 2027" não autoriza dias 27
        FontesDoTurno soAno = new FontesDoTurno("Para 2027, quantos?", 2027, HOJE);
        assertThat(camposSemOrigem(soAno, "{\"inicio\":\"2026-11-01\",\"dias\":27}")).containsOnlyKeys("dias");
    }

    @Test
    void inicioEFimQueNaoSaoDeHojeAteSeteDiasDepoisNemDeFonteNemDaPessoaSaoErro() {
        FontesDoTurno turno = new FontesDoTurno("Quantos dias?", 2027, HOJE);

        // o ataque: 01/01 a 09/11 são 312 dias, que passariam a valer em qualquer frase
        Map<String, String> campos = camposSemOrigem(turno, "{\"inicio\":\"2026-01-01\",\"fim\":\"2026-11-09\"}");

        assertThat(campos).containsOnlyKeys("inicio", "fim");
        assertThat(campos.get("inicio")).contains("01/01/2026", "01/11/2026");
        assertThat(turno.fontes()).isEmpty();
        assertThat(turno.numerosLivres()).isEmpty();
        // hoje e os sete dias seguintes valem; o oitavo não
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-01\",\"fim\":\"2026-11-08\"}")).containsEntry("resultado", "7");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-11-01\",\"fim\":\"2026-11-09\"}")).containsOnlyKeys("fim");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-10-31\",\"fim\":\"2026-11-03\"}")).as("ontem não é de hoje").containsOnlyKeys("inicio");
    }

    @Test
    void aDataSemAnoDaPessoaOuDaFonteValeEOAnoCompletoDaFonteTambem() {
        // 03/11 sem ano na mensagem, e as datas completas
        assertThat(contarComOQueTem(ferramenta, new FontesDoTurno("Se o briefing sair em 03/11 e a entrega for 20/11, quanto dá?", 2027, HOJE),
                "{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-20\"}")).containsEntry("resultado", "17");
        FontesDoTurno fonte = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        fonte.registrarRegistro("prazo", 1, "Geradores", "Prazos", "Prazo: 04/12/2026 às 18:00. Faltam 33 dias.", Map.of());
        assertThat(contarComOQueTem(ferramenta, fonte, "{\"inicio\":\"2026-11-01\",\"fim\":\"2026-12-04\"}")).containsEntry("resultado", "33");
        // a data de outro ano que a fonte disse com ano não vale
        assertThat(camposSemOrigem(fonte, "{\"inicio\":\"2026-11-01\",\"fim\":\"2027-12-04\"}")).containsOnlyKeys("fim");
    }

    @Test
    void registroEContaValemPeloQueDizemPorInteiroEOTrechoDizOsPedacos() {
        FontesDoTurno registro = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        registro.registrarRegistro("entrega", 1, "Reenvio do VT", "Mídia", "Reenvio do VT em até 30 segundos. Atualizada em 27/10/2026.", Map.of());
        // a data montada com o 30 e o mês 10 não existe na fonte
        assertThat(camposSemOrigem(registro, "{\"inicio\":\"2026-10-30\",\"fim\":\"2026-11-01\"}")).containsOnlyKeys("inicio");
        FontesDoTurno trecho = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        trecho.registrarTrecho(1, 1, "Manual de Operações 2027", 2, 2, "Datas", "O festival acontece na sexta-feira, 29, e no sábado, 30 de janeiro de 2027.");
        assertThat(contarComOQueTem(ferramenta, trecho, "{\"inicio\":\"2026-11-01\",\"fim\":\"2027-01-29\"}")).containsEntry("resultado", "89");
    }

    @Test
    void oResultadoDeOutraContagemDoTurnoServeDeInicio() {
        FontesDoTurno turno = new FontesDoTurno("Quando vencem 18 dias úteis depois de 03/11/2026?", 2027, HOJE);

        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}")).containsEntry("resultado", "30/11/2026");
        // o 30/11/2026 está no texto de C1; e os 18 dias, na mensagem da pessoa
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-30\",\"dias\":18}")).containsEntry("resultado", "18/12/2026");
        // mas um resultado que nenhuma contagem deu não serve
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-12-01\",\"dias\":18}")).containsOnlyKeys("inicio");
    }

    @Test
    void ointervaloComFimVaiAte800DiasEOsFeriadosListadosNuncaPassamDe40() {
        LocalDate inicio = LocalDate.of(2026, 11, 3);
        LocalDate ate800 = inicio.plusDays(800);
        LocalDate ate801 = inicio.plusDays(801);
        FontesDoTurno turno = new FontesDoTurno("Quantos dias?", 2027, HOJE);

        Map<String, Object> maximo = contar(ferramenta, turno, "{\"inicio\":\"" + inicio + "\",\"fim\":\"" + ate800 + "\",\"uteis\":true}");
        assertThat(maximo).containsKey("resultado");
        assertThat(feriados(maximo)).isNotEmpty().hasSizeLessThanOrEqualTo(40);
        comOrigem(turno, "{\"inicio\":\"" + inicio + "\",\"fim\":\"" + ate801 + "\"}");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"" + inicio + "\",\"fim\":\"" + ate801 + "\"}")).containsOnlyKeys("fim");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"" + inicio + "\",\"fim\":\"" + ate801 + "\"}").get("fim")).contains("801", "800");
        // o intervalo de um século inteiro, que devolvia 819 feriados e 41 KB
        comOrigem(turno, "{\"inicio\":\"2000-01-01\",\"fim\":\"2100-12-31\"}");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2000-01-01\",\"fim\":\"2100-12-31\",\"uteis\":true}")).containsOnlyKeys("fim");
    }

    // ---------------------------------------------------------------------
    // Argumentos errados voltam ao modelo
    // ---------------------------------------------------------------------

    @Test
    void argumentoDesconhecidoEErro() {
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"parceria_id\":3}")).containsKey("parceria_id");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"feriados\":[\"2026-11-04\"]}")).containsKey("feriados");
    }

    @Test
    void dataInvalidaOuForaDoFormatoEErro() {
        for (String data : List.of("2026-02-30", "2026-13-01", "03/11/2026", "amanhã", "2026-11-3", "", " ", "2026-11-03T10:00")) {
            assertThat(campos("{\"inicio\":\"" + data + "\",\"dias\":1}")).as(data).containsKey("inicio");
        }
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-02-30\"}")).containsKey("fim");
        assertThat(campos("{\"inicio\":20261103,\"dias\":1}")).as("a data tem de ser texto").containsKey("inicio");
        assertThat(campos("{\"dias\":1}")).as("sem início").containsKey("inicio");
    }

    @Test
    void anoForaDe2000A2100EErro() {
        assertThat(campos("{\"inicio\":\"1999-12-31\",\"dias\":1}")).containsKey("inicio");
        assertThat(campos("{\"inicio\":\"2101-01-01\",\"dias\":1}")).containsKey("inicio");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"fim\":\"2101-01-01\"}")).containsKey("fim");
        // o resultado também fica dentro dos anos que a ferramenta conhece
        assertThat(erro("{\"inicio\":\"2100-12-31\",\"dias\":5}")).contains("2100");
        assertThat(erro("{\"inicio\":\"2000-01-02\",\"dias\":5,\"sentido\":\"antes\"}")).contains("2000");
    }

    @Test
    void fimAntesDoInicioEErro() {
        assertThat(campos("{\"inicio\":\"2026-11-30\",\"fim\":\"2026-11-03\"}")).containsKey("fim");
    }

    @Test
    void exatamenteUmDeFimOuDias() {
        assertThat(erro("{\"inicio\":\"2026-11-03\"}")).contains("fim", "dias");
        assertThat(erro("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\",\"dias\":5}")).contains("fim", "dias");
    }

    @Test
    void diasTemDeSerInteiroDe1A400() {
        for (String dias : List.of("0", "-3", "401", "1000", "2.5", "\"5\"", "true", "null")) {
            assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":" + dias + "}")).as(dias).isNotEmpty();
        }
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":400}")).containsKey("resultado");
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":1}")).containsKey("resultado");
    }

    @Test
    void uteisTemDeSerBooleanoESentidoDepoisOuAntesESoComDias() {
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"uteis\":\"sim\"}")).containsKey("uteis");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"uteis\":1}")).containsKey("uteis");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"sentido\":\"ontem\"}")).containsKey("sentido");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"dias\":1,\"sentido\":5}")).containsKey("sentido");
        assertThat(campos("{\"inicio\":\"2026-11-03\",\"fim\":\"2026-11-30\",\"sentido\":\"antes\"}")).as("sentido só vale com dias")
                .containsKey("sentido");
    }

    @Test
    void erroNaoGastaMarcadorNemDeixaFonte() {
        assertThatThrownBy(() -> ferramenta.executar(null, turno, Argumentos.ler("{\"inicio\":\"2026-02-30\",\"dias\":1}"), "x", null))
                .isInstanceOf(DadosInvalidosException.class);

        assertThat(turno.fontes()).isEmpty();
        assertThat(turno.numerosLivres()).isEmpty();
        assertThat(contar("{\"inicio\":\"2026-11-03\",\"dias\":1}")).containsEntry("marcador", "C1");
    }

    @Test
    void naoPrecisaDeParceriaNemDeTransacao() {
        // a ferramenta é oferecida em todo estado menos o do cartão aberto, e a definição proíbe argumento fora do esquema
        assertThat(ferramenta.nome()).isEqualTo("contar_dias");
        assertThat(ferramenta.definicao().esquema()).contains("\"additionalProperties\":false").doesNotContain("parceria");
        assertThat(ferramenta.preparar(null, turno, Map.of())).isNull();
    }
}
