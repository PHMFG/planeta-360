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
        // acima de 31 dias o número precisa de origem: 32 e 45 não vêm de graça
        assertThat(camposSemOrigem(new FontesDoTurno("Quanto?", 2027, HOJE), "{\"inicio\":\"2026-11-01\",\"dias\":32}")).containsOnlyKeys("dias");
        assertThat(camposSemOrigem(new FontesDoTurno("Quanto?", 2027, HOJE), "{\"inicio\":\"2026-11-01\",\"dias\":45}")).containsOnlyKeys("dias");
    }

    @Test
    void ateTrintaEUmDiasValeSemOrigemEOPedidoComumDePrazoPassa() {
        FontesDoTurno turno = new FontesDoTurno("Quando fica pronto?", 2027, HOJE);   // a pessoa não escreveu nenhum número

        // "duas semanas depois de 03/11", "uma semana depois de 20/11", "um dia útil depois de 03/11"
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-03\",\"dias\":14}")).containsEntry("resultado", "17/11/2026");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-20\",\"dias\":7}")).containsEntry("resultado", "27/11/2026");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-03\",\"dias\":1,\"uteis\":true}")).containsEntry("resultado", "04/11/2026");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-03\",\"dias\":31}")).containsEntry("resultado", "04/12/2026");
        // "dia 20", "fim do mês", "ontem" e "segunda passada" viram data de hoje menos 31 a hoje mais 90 dias
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-01\",\"fim\":\"2026-11-20\"}")).containsEntry("resultado", "19");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-01\",\"fim\":\"2026-11-30\"}")).containsEntry("resultado", "29");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-10-31\",\"fim\":\"2026-11-03\"}")).containsEntry("resultado", "3");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-10-26\",\"fim\":\"2026-11-01\"}")).containsEntry("resultado", "6");
    }

    @Test
    void oTextoDeUmaContaDoCalcularNaoValeComoOrigemDeDiasNemDeData() {
        FontesDoTurno turno = new FontesDoTurno("Quanto?", 2027, HOJE);
        // uma conta do calcular com 212 e 10/05/2027 no texto dela
        turno.registrarCalculo("energia", "energia", "Valores usados: 212; 10/05/2027. Resultado: 212.", Map.of("ferramenta", "calcular", "nome", "energia"));
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-11-01\",\"dias\":212}")).containsOnlyKeys("dias");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2027-05-10\",\"dias\":1}")).containsOnlyKeys("inicio");
        // o trecho de documento e o registro do portal valem, e o resultado de outra contagem serve de data
        FontesDoTurno comRegistro = new FontesDoTurno("Quanto?", 2027, HOJE);
        comRegistro.registrarRegistro("prazo", 1, "Prazo", "Prazos", "Prazo: 10/05/2027. Faltam 212 dias.", Map.of());
        assertThat(contarComOQueTem(ferramenta, comRegistro, "{\"inicio\":\"2027-05-10\",\"dias\":212}")).containsKey("resultado");
        FontesDoTurno comTrecho = new FontesDoTurno("Quanto?", 2027, HOJE);
        comTrecho.registrarTrecho(1, 1, "Manual", 1, 1, "Prazos", "O reenvio leva 212 dias.");
        assertThat(contarComOQueTem(ferramenta, comTrecho, "{\"inicio\":\"2026-11-01\",\"dias\":212}")).containsEntry("resultado", "01/06/2027");
        // e o C# de uma contagem de dias deste turno serve de início fora da janela
        FontesDoTurno contagem = new FontesDoTurno("Quanto?", 2027, HOJE);
        contagem.registrarCalculo(null, "Contagem de dias", "Contei 100 dias depois de 01/11/2026. Resultado: 09/02/2027.",
                Map.of("ferramenta", "contar_dias", "resultado", "09/02/2027"));
        assertThat(contarComOQueTem(ferramenta, contagem, "{\"inicio\":\"2027-02-09\",\"dias\":7}")).containsEntry("resultado", "16/02/2027");
    }

    @Test
    void inicioEFimQueNaoSaoDeHojeMenos31AHojeMais90NemDeFonteNemDaPessoaSaoErro() {
        FontesDoTurno turno = new FontesDoTurno("Quantos dias?", 2027, HOJE);

        // o ataque: 01/01 a 09/11 são 312 dias, que passariam a valer em qualquer frase
        Map<String, String> campos = camposSemOrigem(turno, "{\"inicio\":\"2026-01-01\",\"fim\":\"2026-11-09\"}");

        assertThat(campos).containsOnlyKeys("inicio");
        assertThat(campos.get("inicio")).contains("01/01/2026", "01/11/2026", "31 dias", "90 dias");
        assertThat(turno.fontes()).isEmpty();
        assertThat(turno.numerosLivres()).isEmpty();
        // de 31 dias atrás a 90 depois valem; um dia a mais ou a menos não
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-10-01\",\"fim\":\"2027-01-30\"}")).containsEntry("resultado", "121");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-09-30\",\"fim\":\"2026-11-03\"}")).as("32 dias atrás").containsOnlyKeys("inicio");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2026-11-01\",\"fim\":\"2027-01-31\"}")).as("91 dias depois").containsOnlyKeys("fim");
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
        registro.registrarRegistro("entrega", 1, "Reenvio do VT", "Mídia", "Reenvio do VT em até 15 segundos. Atualizada em 27/05/2027.", Map.of());
        // a data montada com o 15 e o mês 05 não existe no registro (fora da janela livre de hoje)
        assertThat(camposSemOrigem(registro, "{\"inicio\":\"2027-05-15\",\"fim\":\"2027-05-27\"}")).containsOnlyKeys("inicio");
        assertThat(contarComOQueTem(ferramenta, registro, "{\"inicio\":\"2027-05-27\",\"dias\":3}")).containsEntry("resultado", "30/05/2027");
        FontesDoTurno trecho = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        trecho.registrarTrecho(1, 1, "Manual de Operações 2027", 2, 2, "Datas", "O festival acontece na sexta-feira, 29, e no sábado, 30 de janeiro de 2027.");
        assertThat(contarComOQueTem(ferramenta, trecho, "{\"inicio\":\"2026-11-01\",\"fim\":\"2027-01-29\"}")).containsEntry("resultado", "89");
        FontesDoTurno trechoComPedacos = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        trechoComPedacos.registrarTrecho(1, 1, "Manual", 2, 2, "Datas", "O evento acontece na sexta-feira, 14, e no sábado, 15 de maio de 2027.");
        assertThat(contarComOQueTem(ferramenta, trechoComPedacos, "{\"inicio\":\"2027-05-14\",\"fim\":\"2027-05-15\"}")).containsEntry("resultado", "1");
        // um número solto ("até 15 segundos") não é o dia de uma data
        FontesDoTurno numeroSolto = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        numeroSolto.registrarTrecho(1, 1, "Manual", 2, 2, "Datas", "Reenvio do VT em até 15 segundos. Atualizada em 27/05/2027.");
        assertThat(camposSemOrigem(numeroSolto, "{\"inicio\":\"2027-05-15\",\"fim\":\"2027-05-27\"}")).containsOnlyKeys("inicio");
    }

    @Test
    void osPedacosDeUmaDataSoValemDentroDaMesmaFonte() {
        // T1 "R$ 20,00" e T2 "04/12/2026" aceitavam 20/12 juntando o 20 de uma com o mês da outra
        FontesDoTurno duasFontes = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        duasFontes.registrarTrecho(1, 1, "Tabela", 1, 1, "Valores", "Diária: R$ 20,00 por pessoa. Reunião na sexta-feira, 14.");
        duasFontes.registrarTrecho(2, 1, "Calendário", 1, 2, "Prazos", "A entrega é em 10/05/2027.");
        assertThat(camposSemOrigem(duasFontes, "{\"inicio\":\"2027-05-10\",\"fim\":\"2027-05-20\"}")).containsOnlyKeys("fim");
        assertThat(camposSemOrigem(duasFontes, "{\"inicio\":\"2027-05-10\",\"fim\":\"2027-05-14\"}")).as("o 14 de uma fonte e o mês da outra")
                .containsOnlyKeys("fim");
        // na mesma fonte, o dia depois de "sexta-feira," e o mês da data dela fazem 14/05/2027
        FontesDoTurno mesmaFonte = new FontesDoTurno("Quantos dias?", 2027, HOJE);
        mesmaFonte.registrarTrecho(1, 1, "Manual", 1, 1, "Prazos", "Reunião na sexta-feira, 14. A entrega é em 10/05/2027.");
        assertThat(contarComOQueTem(ferramenta, mesmaFonte, "{\"inicio\":\"2027-05-10\",\"fim\":\"2027-05-14\"}")).containsEntry("resultado", "4");
    }

    @Test
    void foraDaJanelaLivreADataSemAnoSoValeNosAnosDaEdicaoDoAnteriorEDeHoje() {
        // a edição é 2027 e hoje é 2026: valem 2027 e 2026; 2030 não
        FontesDoTurno turno = new FontesDoTurno("A entrega é em 10/05 e a retirada em 20/05.", 2027, HOJE);
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2027-05-10\",\"fim\":\"2027-05-20\"}")).containsEntry("resultado", "10");
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-05-10\",\"fim\":\"2026-05-20\"}")).containsEntry("resultado", "10");
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"2030-05-10\",\"fim\":\"2030-05-20\"}")).containsOnlyKeys("inicio", "fim");
        // a pessoa que escreveu o ano vale como escreveu
        assertThat(contarComOQueTem(ferramenta, new FontesDoTurno("Entre 10/05/2030 e 20/05/2030?", 2027, HOJE),
                "{\"inicio\":\"2030-05-10\",\"fim\":\"2030-05-20\"}")).containsEntry("resultado", "10");
        // o mesmo vale para o registro com a data sem ano
        FontesDoTurno registro = new FontesDoTurno("Quanto?", 2027, HOJE);
        registro.registrarRegistro("prazo", 1, "Prazo", "Prazos", "Prazo: 10/05.", Map.of());
        assertThat(camposSemOrigem(registro, "{\"inicio\":\"2030-05-10\",\"dias\":1}")).containsOnlyKeys("inicio");
        assertThat(contarComOQueTem(ferramenta, registro, "{\"inicio\":\"2027-05-10\",\"dias\":1}")).containsEntry("resultado", "11/05/2027");
    }

    @Test
    void oResultadoDeOutraContagemDoTurnoServeDeInicio() {
        FontesDoTurno turno = new FontesDoTurno("Quando vencem 80 dias úteis depois de 03/11/2026?", 2027, HOJE);

        Map<String, Object> primeira = contarComOQueTem(ferramenta, turno, "{\"inicio\":\"2026-11-03\",\"dias\":80,\"uteis\":true}");
        LocalDate resultado = LocalDate.parse((String) primeira.get("resultado"), DIA);
        assertThat(resultado).as("fora da janela livre de hoje").isAfter(HOJE.plusDays(90));
        // o resultado está no texto de C1 e serve de início
        assertThat(contarComOQueTem(ferramenta, turno, "{\"inicio\":\"" + resultado + "\",\"dias\":18}")).containsKey("resultado");
        // mas uma data que nenhuma contagem deu não serve, fora da janela livre
        assertThat(camposSemOrigem(turno, "{\"inicio\":\"" + resultado.plusDays(1) + "\",\"dias\":18}")).containsOnlyKeys("inicio");
    }

    @Test
    void ointervaloComFimVaiAte800DiasEOsFeriadosListadosSaoTodosOsDoIntervalo() {
        LocalDate inicio = LocalDate.of(2026, 11, 3);
        LocalDate ate800 = inicio.plusDays(800);
        LocalDate ate801 = inicio.plusDays(801);
        FontesDoTurno turno = new FontesDoTurno("Quantos dias?", 2027, HOJE);

        Map<String, Object> maximo = contar(ferramenta, turno, "{\"inicio\":\"" + inicio + "\",\"fim\":\"" + ate800 + "\",\"uteis\":true}");
        assertThat(maximo).containsKey("resultado");
        // sem teto: os feriados que caem em dia de semana dentro dos 800 dias (nenhuma contagem possível chega a 40) vêm todos, em ordem
        List<Map<String, String>> listados = feriados(maximo);
        long emDiaDeSemana = inicio.plusDays(1).datesUntil(ate800.plusDays(1))
                .filter(data -> data.getDayOfWeek().getValue() <= 5 && !new Feriados(false).diaUtil(data)).count();
        assertThat(listados).hasSize((int) emDiaDeSemana).isNotEmpty();
        assertThat(listados).extracting(f -> f.get("data")).isSortedAccordingTo(
                java.util.Comparator.<String, LocalDate>comparing(texto -> LocalDate.parse(texto, DIA)));
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
    void aDefinicaoDizQueAContagemNaoTemMarcadorCalcEQueOResultadoSeCopiaComACitacao() {
        String descricao = ferramenta.definicao().descricao();

        assertThat(descricao).contains("{{calc:", "[C#]").contains("copi");
        assertThat(descricao).as("e a origem dos números e das datas").contains("origem", "de 1 a 31 vale sem fonte", "31 dias antes até 90 dias depois de hoje", "800");
        assertThat(descricao).as("e como virar semana e data relativa em dias e datas").contains("uma semana são 7", "ontem", "fim do mês");
    }

    @Test
    void naoPrecisaDeParceriaNemDeTransacao() {
        // a ferramenta é oferecida em todo estado menos o do cartão aberto, e a definição proíbe argumento fora do esquema
        assertThat(ferramenta.nome()).isEqualTo("contar_dias");
        assertThat(ferramenta.definicao().esquema()).contains("\"additionalProperties\":false").doesNotContain("parceria");
        assertThat(ferramenta.preparar(null, turno, Map.of())).isNull();
    }
}
