package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasService;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.relatorio.ContaDoChat;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [VALIDACAO] O {@code calcular} contra o banco da amostra (M5, bloqueante do plano): o resultado com a formatação do portal,
 * o {@code formato} moeda, a origem de cada variável, a fonte {@code [C#]}, as conferências antes de rodar (o valor literal está na
 * fonte citada deste turno ou na mensagem da pessoa; nunca se misturam unidades; nome livre; argumento conhecido), o indisponível
 * (denominador zero, dado não informado: sem marcador e sem fonte, nunca zero) e os ataques ao script, todos recusados. O Amarelo de
 * 2027 tem 19 aplicações previstas e 17 comprovadas. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class CalcularTest {

    private static final String MENSAGEM = "O preço é R$ 312,00 por metro e a área tem 34 metros.";

    @Autowired
    Calcular calcular;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    AtlasContexto contexto() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var pessoa = usuario(jdbc, AMARELO);
        long conversa = atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId();
        return contextos.para(pessoa, parceria, conversa, requisicao());
    }

    FontesDoTurno turno() {
        return new FontesDoTurno(MENSAGEM, 2027, LocalDate.of(2026, 11, 1));
    }

    Map<String, Object> rodar(AtlasContexto contexto, FontesDoTurno turno, String argumentos) {
        Execucao execucao = calcular.executar(contexto, turno, Argumentos.ler(argumentos), "call_x", null);
        return AtlasJson.ler(execucao.resultadoJson(), new TypeReference<Map<String, Object>>() {
        });
    }

    String tarifa(String nome, String formato) {
        return "{\"nome\":\"" + nome + "\",\"script\":\"preco * area\",\"variaveis\":{"
                + "\"preco\":{\"fonte\":\"usuario\",\"valor\":\"312\"},\"area\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}"
                + (formato == null ? "" : ",\"formato\":\"" + formato + "\"") + "}";
    }

    /** Tudo o que o modelo lê do erro: a frase e o motivo de cada campo. */
    String erro(AtlasContexto contexto, FontesDoTurno turno, String argumentos) {
        DadosInvalidosException erro = org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> calcular.executar(contexto, turno, Argumentos.ler(argumentos), "call_x", null));
        return erro.getMessage() + " " + String.join(" ", erro.campos().values());
    }

    void informar(Object... paresChaveValor) {
        dados.gravar(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), valores(paresChaveValor),
                OrigemDoDado.ATLAS, requisicao());
    }

    // ---------------------------------------------------------------------
    // O resultado
    // ---------------------------------------------------------------------

    @Test
    void contaComValoresDaMensagemDaPessoaSaiFormatadaNoPadraoDoPortal() {
        FontesDoTurno turno = turno();

        Map<String, Object> resultado = rodar(contexto(), turno, tarifa("tarifa", null));

        assertThat(resultado).containsEntry("marcador", "C1").containsEntry("ref", "calc:tarifa")
                .containsEntry("valorFormatado", "10.608").containsEntry("script", "preco * area");
        assertThat(new java.math.BigDecimal(resultado.get("valor").toString())).isEqualByComparingTo("10608");
        assertThat(resultado.get("variaveis")).isEqualTo(List.of(
                Map.of("nome", "preco", "origem", "usuario", "valor", "312"),
                Map.of("nome", "area", "origem", "usuario", "valor", "34")));
    }

    @Test
    void formatoMoedaTemSempreDuasCasasESemOSimbolo() {
        assertThat(rodar(contexto(), turno(), tarifa("tarifa", "moeda"))).containsEntry("valorFormatado", "10.608,00");
        // 312 * 34 = 10.608; um valor com centavos e um que arredonda
        FontesDoTurno turno = new FontesDoTurno("Pagamos 1250 por 3 unidades e 7 de taxa.", 2027, LocalDate.of(2026, 11, 1));
        Map<String, Object> media = rodar(contexto(), turno, "{\"nome\":\"media\",\"script\":\"total / n\",\"formato\":\"moeda\","
                + "\"variaveis\":{\"total\":{\"fonte\":\"usuario\",\"valor\":\"1250\"},\"n\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}");
        assertThat(media).containsEntry("valorFormatado", "416,67");
        Map<String, Object> pequeno = rodar(contexto(), turno, "{\"nome\":\"parte\",\"script\":\"taxa / total\",\"formato\":\"moeda\","
                + "\"variaveis\":{\"taxa\":{\"fonte\":\"usuario\",\"valor\":\"7\"},\"total\":{\"fonte\":\"usuario\",\"valor\":\"1250\"}}}");
        assertThat(pequeno).containsEntry("valorFormatado", "0,01");
    }

    @Test
    void formatoNumeroArredondaEmDuasCasasMeioParaCimaESemZerosSobrando() {
        FontesDoTurno turno = new FontesDoTurno("Foram 10 de 3 e 5 de 8.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        Map<String, Object> terco = rodar(contexto, turno, "{\"nome\":\"terco\",\"script\":\"a / b\","
                + "\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"10\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}");
        Map<String, Object> meio = rodar(contexto, turno, "{\"nome\":\"meio\",\"script\":\"a / b\","
                + "\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"5\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"8\"}}}");

        assertThat(terco).containsEntry("valorFormatado", "3,33");
        assertThat(meio).as("0,625 arredonda para cima").containsEntry("valorFormatado", "0,63");
    }

    @Test
    void pctDeReferenciasDoPortalSaiCortadoNaPrimeiraCasa() {
        Map<String, Object> resultado = rodar(contexto(), turno(), "{\"nome\":\"taxa\",\"script\":\"pct(comprovadas, previstas)\","
                + "\"variaveis\":{\"comprovadas\":{\"ref\":\"cons:aplicacoes.comprovadas\"},\"previstas\":{\"ref\":\"cons:aplicacoes.previstas\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "89,4");   // 17 de 19: 89,47 cortado, não arredondado
        assertThat(resultado.get("variaveis")).isEqualTo(List.of(
                Map.of("nome", "comprovadas", "origem", "cons:aplicacoes.comprovadas", "valor", "17"),
                Map.of("nome", "previstas", "origem", "cons:aplicacoes.previstas", "valor", "19")));
    }

    @Test
    void dadoInformadoPelaMarcaEntraPelaReferencia() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        Map<String, Object> resultado = rodar(contexto(), turno(), "{\"nome\":\"cadastros_por_visitante\",\"script\":\"cad / vis\","
                + "\"variaveis\":{\"cad\":{\"ref\":\"dado:ativacao.cadastros_consentidos\"},\"vis\":{\"ref\":\"dado:ativacao.visitantes_total\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "0,89");
    }

    @Test
    void contaFixaDoModeloEntraComoVariavel() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        Map<String, Object> resultado = rodar(contexto(), turno(), "{\"nome\":\"duas_vezes\",\"script\":\"taxa * 2\","
                + "\"variaveis\":{\"taxa\":{\"ref\":\"calc:taxa_cadastro\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "178,6");   // 89,3 * 2
    }

    @Test
    void contaDoMesmoTurnoEntraComoVariavel() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();
        rodar(contexto, turno, tarifa("tarifa", "moeda"));

        Map<String, Object> dobro = rodar(contexto, turno, "{\"nome\":\"dobro\",\"script\":\"t * 2\","
                + "\"variaveis\":{\"t\":{\"ref\":\"calc:tarifa\"}}}");

        assertThat(dobro).containsEntry("marcador", "C2").containsEntry("valorFormatado", "21.216");
        assertThat(dobro.get("variaveis")).isEqualTo(List.of(Map.of("nome", "t", "origem", "calc:tarifa", "valor", "10.608")));
        assertThat(turno.contas()).extracting(Fonte::marcador).containsExactly("C1", "C2");
    }

    // ---------------------------------------------------------------------
    // A fonte [C#]
    // ---------------------------------------------------------------------

    @Test
    void aContaViraFonteComAContaInteiraEOValorFormatadoVaiParaONumeroLivre() {
        FontesDoTurno turno = turno();

        rodar(contexto(), turno, tarifa("tarifa", "moeda"));

        Fonte fonte = turno.fonte("C1").orElseThrow();
        assertThat(fonte.tipo()).isEqualTo(FontesDoTurno.Tipo.CALCULO);
        assertThat(fonte.titulo()).isEqualTo("tarifa");
        assertThat(turno.conta("tarifa")).isPresent();
        assertThat(fonte.dados()).containsEntry("nome", "tarifa").containsEntry("ref", "calc:tarifa")
                .containsEntry("valorFormatado", "10.608,00").containsEntry("script", "preco * area").containsKey("variaveis");
        assertThat(fonte.texto()).contains("312", "34", "10.608,00");
        assertThat(turno.numerosLivres()).contains("10.608,00");
    }

    @Test
    void oTextoDaFonteTemSoOsValoresEOResultadoSemNomeDeVariavelNemDeContaNemScript() {
        // o nome da variável aceita algarismo: n312 não pode fazer o 312 valer como número da resposta
        FontesDoTurno turno = new FontesDoTurno("Quero 5 unidades.", 2027, LocalDate.of(2026, 11, 1));

        rodar(contexto(), turno, "{\"nome\":\"multa\",\"script\":\"n312 * 1\",\"variaveis\":{\"n312\":{\"fonte\":\"usuario\",\"valor\":\"5\"}}}");

        Fonte fonte = turno.fonte("C1").orElseThrow();
        assertThat(fonte.texto()).doesNotContain("312").doesNotContain("n312").doesNotContain("multa").doesNotContain("*");
        assertThat(fonte.texto()).contains("5");
        assertThat(fonte.conteudoCitavel()).doesNotContain("312");
        // os valores verificados e o resultado continuam na fonte, para a conta seguinte e para a citação
        FontesDoTurno tarifa = turno();
        rodar(contexto(), tarifa, tarifa("tarifa", "moeda"));
        assertThat(tarifa.fonte("C1").orElseThrow().texto()).contains("312", "34", "10.608,00").doesNotContain("preco").doesNotContain("area")
                .doesNotContain("tarifa").doesNotContain("*");
    }

    @Test
    void soOAnoDeUmaDataNaoEValorNoLiteralDoCalcular() {
        AtlasContexto contexto = contexto();
        // o ano de uma data da fonte (o 2026 de 04/12/2026) e o da edição, que nenhum texto diz solto, nunca são valor
        FontesDoTurno data = new FontesDoTurno("Quanto?", 2027, LocalDate.of(2026, 11, 1));
        data.registrarRegistro("prazo", 1, "Prazo", null, "Prazo: 04/12/2026.", Map.of());
        assertThat(erro(contexto, data, "{\"nome\":\"d\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"R1\",\"valor\":\"2026\"}}}"))
                .contains("2026");
        assertThat(erro(contexto, data, "{\"nome\":\"e\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"2027\"}}}"))
                .contains("2027");
        assertThat(data.contas()).isEmpty();
        // "R$ 2.026,00" é um valor de verdade na fonte
        FontesDoTurno multa = new FontesDoTurno("Quanto?", 2027, LocalDate.of(2026, 11, 1));
        multa.registrarRegistro("parceria", 1, "Cota da parceria", null, "Cota Master. Multa de R$ 2.026,00 até 04/12/2026.", Map.of());
        assertThat(rodar(contexto, multa, "{\"nome\":\"multa\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"R1\",\"valor\":\"2026\"}}}"))
                .containsEntry("valorFormatado", "4.052");
        // com unidade depois, os quatro dígitos são valor também na mensagem da pessoa
        FontesDoTurno credenciais = new FontesDoTurno("Quero 2027 credenciais.", 2027, LocalDate.of(2026, 11, 1));
        assertThat(rodar(contexto, credenciais, "{\"nome\":\"c\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"2027\"}}}"))
                .containsEntry("valorFormatado", "4.054");
    }

    @Test
    void numeroEntre1900E2099EscritoSoltoValeComoValorNaMensagemDaPessoaENaFonte() {
        AtlasContexto contexto = contexto();
        // "quanto é 10% de 2000?"
        FontesDoTurno porcento = new FontesDoTurno("quanto é 10% de 2000?", 2027, LocalDate.of(2026, 11, 1));
        assertThat(rodar(contexto, porcento, "{\"nome\":\"dez\",\"script\":\"total * p / 100\",\"variaveis\":{"
                + "\"total\":{\"fonte\":\"usuario\",\"valor\":\"2000\"},\"p\":{\"fonte\":\"usuario\",\"valor\":\"10\"}}}"))
                .containsEntry("valorFormatado", "200");
        // "2000 brindes a R$ 3" e "1950 mais 1980"
        FontesDoTurno brindes = new FontesDoTurno("2000 brindes a R$ 3 cada, e 1950 mais 1980 de outro lote.", 2027, LocalDate.of(2026, 11, 1));
        assertThat(rodar(contexto, brindes, "{\"nome\":\"brindes\",\"script\":\"n * preco\",\"variaveis\":{"
                + "\"n\":{\"fonte\":\"usuario\",\"valor\":\"2000\"},\"preco\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}"))
                .containsEntry("valorFormatado", "6.000");
        assertThat(rodar(contexto, brindes, "{\"nome\":\"soma\",\"script\":\"a + b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"1950\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"1980\"}}}"))
                .containsEntry("valorFormatado", "3.930");
        // uma tabela da fonte: "Diária | 1950"
        FontesDoTurno tabela = new FontesDoTurno("Quanto fica com 3 diárias?", 2027, LocalDate.of(2026, 11, 1));
        tabela.registrarTrecho(10, 1, "Tabela de diárias", 1, 2, "Diárias", "Item | Valor\nDiária | 1950\nPrazo: 04/12/2026.");
        assertThat(rodar(contexto, tabela, "{\"nome\":\"diarias\",\"script\":\"d * n\",\"variaveis\":{"
                + "\"d\":{\"fonte\":\"T1\",\"valor\":\"1950\"},\"n\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}"))
                .containsEntry("valorFormatado", "5.850");
    }

    // ---------------------------------------------------------------------
    // Indisponível: nunca zero
    // ---------------------------------------------------------------------

    @Test
    void denominadorZeroDevolveIndisponivelSemMarcadorENemFonte() {
        FontesDoTurno turno = new FontesDoTurno("Foram 10 de 0 e 17.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        for (String script : List.of("a / b", "pct(a, b)")) {
            Map<String, Object> resultado = rodar(contexto, turno, "{\"nome\":\"razao\",\"script\":\"" + script + "\",\"variaveis\":{"
                    + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"10\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"0\"}}}");

            assertThat(resultado).as(script).containsEntry("indisponivel", true).containsKey("motivo")
                    .doesNotContainKeys("marcador", "valor", "valorFormatado", "ref");
        }
        assertThat(turno.fontes()).isEmpty();
        assertThat(turno.numerosLivres()).isEmpty();
        assertThat(turno.conta("razao")).isEmpty();
        // o nome não foi gasto: a mesma conta com um denominador de verdade sai com o primeiro marcador
        assertThat(rodar(contexto, turno, "{\"nome\":\"razao\",\"script\":\"a / b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"10\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"17\"}}}")).containsEntry("marcador", "C1");
    }

    @Test
    void denominadorQueVemDeUmDadoZeradoTambemEIndisponivel() {
        informar(VISITANTES, 0L, CADASTROS, 0L);

        Map<String, Object> resultado = rodar(contexto(), turno(), "{\"nome\":\"taxa\",\"script\":\"pct(cad, vis)\","
                + "\"variaveis\":{\"cad\":{\"ref\":\"dado:ativacao.cadastros_consentidos\"},\"vis\":{\"ref\":\"dado:ativacao.visitantes_total\"}}}");

        assertThat(resultado).containsEntry("indisponivel", true).doesNotContainKey("valor");
    }

    @Test
    void referenciaSemValorDevolveIndisponivelComOMotivoSemFonte() {
        FontesDoTurno turno = turno();

        Map<String, Object> resultado = rodar(contexto(), turno, "{\"nome\":\"x\",\"script\":\"v * 2\","
                + "\"variaveis\":{\"v\":{\"ref\":\"dado:ativacao.visitantes_total\"}}}");

        assertThat(resultado).containsEntry("indisponivel", true).doesNotContainKeys("marcador", "valor");
        assertThat((String) resultado.get("motivo")).contains("Não informado");
        assertThat(turno.fontes()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // O valor tem origem
    // ---------------------------------------------------------------------

    @Test
    void valorLiteralPrecisaEstarNaFonteCitadaDesteTurno() {
        FontesDoTurno turno = turno();
        turno.registrarTrecho(10, 1, "Manual de Operações 2027", 2, 5, "4 Energia", "A tarifa de energia é de R$ 312,00 por kVA.");

        Map<String, Object> certo = rodar(contexto(), turno, "{\"nome\":\"energia\",\"script\":\"t * 2\","
                + "\"variaveis\":{\"t\":{\"fonte\":\"T1\",\"valor\":\"312\"}}}");

        assertThat(certo).containsEntry("valorFormatado", "624");
        assertThat(certo.get("variaveis")).isEqualTo(List.of(Map.of("nome", "t", "origem", "T1", "valor", "312")));
        // um valor que a fonte não tem
        assertThat(erro(contexto(), turno, "{\"nome\":\"outra\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"T1\",\"valor\":\"999\"}}}"))
                .contains("T1", "999");
        // o título e a página não são o texto: o 2027 do título e o 5 da página não valem como valor
        assertThat(erro(contexto(), turno, "{\"nome\":\"ano\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"T1\",\"valor\":\"5\"}}}"))
                .contains("T1");
    }

    @Test
    void fonteQueNaoExisteNesteTurnoEErro() {
        FontesDoTurno turno = turno();

        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"T2\",\"valor\":\"312\"}}}"))
                .contains("T2");
        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"C1\",\"valor\":\"312\"}}}"))
                .contains("C1");
        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"X1\",\"valor\":\"312\"}}}"))
                .contains("X1");
        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"t1\",\"valor\":\"312\"}}}"))
                .contains("t1");
    }

    @Test
    void fonteDeRegistroEDeContaTambemValem() {
        FontesDoTurno turno = turno();
        turno.registrarRegistro("entrega", 3, "Cortesias", null, "Foram entregues 140 cortesias.", Map.of());

        Map<String, Object> resultado = rodar(contexto(), turno, "{\"nome\":\"metade\",\"script\":\"c / 2\","
                + "\"variaveis\":{\"c\":{\"fonte\":\"R1\",\"valor\":\"140\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "70");
        // e a conta feita agora é fonte para a seguinte: o resultado dela está no texto da fonte
        assertThat(rodar(contexto(), turno, "{\"nome\":\"dobro\",\"script\":\"m * 2\",\"variaveis\":{\"m\":{\"fonte\":\"C1\",\"valor\":\"70\"}}}"))
                .containsEntry("valorFormatado", "140");
    }

    @Test
    void valorDaPessoaTemDeEstarNaMensagemDELaNesteTurno() {
        FontesDoTurno turno = turno();

        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"usuario\",\"valor\":\"500\"}}}"))
                .contains("usuario", "500");
        // R$ 312,00 e 312 são o mesmo valor
        assertThat(rodar(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"usuario\",\"valor\":\"312,00\"}}}"))
                .containsEntry("valorFormatado", "624");
    }

    @Test
    void valorDaPessoaTambemPodeEstarNumaMensagemAnteriorDaConversa() {
        // 12 kVA, depois "e se eu subir pra 20 kVA?", depois "coloca junto 2 geradores": o 20 é dela, de outro turno
        FontesDoTurno turno = new FontesDoTurno("Coloca junto o cadastro de 2 geradores. Quanto fica tudo?", 2027, LocalDate.of(2026, 11, 1))
                .comMensagensAnteriores(List.of("E se eu subir pra 20 kVA?", "Quanto pago por 12 kVA?"));

        Map<String, Object> resultado = rodar(contexto(), turno, "{\"nome\":\"total\",\"script\":\"kva * geradores\",\"variaveis\":{"
                + "\"kva\":{\"fonte\":\"usuario\",\"valor\":\"20\"},\"geradores\":{\"fonte\":\"usuario\",\"valor\":\"2\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "40");
        // o que nenhuma mensagem da pessoa tem continua recusado
        assertThat(erro(contexto(), turno, "{\"nome\":\"outro\",\"script\":\"kva * 2\",\"variaveis\":{\"kva\":{\"fonte\":\"usuario\","
                + "\"valor\":\"21\"}}}")).contains("usuario", "21");
        // sem as mensagens anteriores o 20 não tem origem
        assertThat(erro(contexto(), turno(), "{\"nome\":\"outro\",\"script\":\"kva * 2\",\"variaveis\":{\"kva\":{\"fonte\":\"usuario\","
                + "\"valor\":\"20\"}}}")).contains("usuario", "20");
    }

    @Test
    void valorQueNaoEUmNumeroEErro() {
        FontesDoTurno turno = turno();

        for (String valor : List.of("abc", "", "03/11/2026", "312 e 34", "1e3", "trezentos")) {
            assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"usuario\",\"valor\":\""
                    + valor + "\"}}}")).as(valor).isNotBlank();
        }
        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"fonte\":\"usuario\",\"valor\":312}}}"))
                .as("o valor vai como texto").isNotBlank();
    }

    @Test
    void valorEResultadoGrandesDemaisSaoErro() {
        String grande = "123456789012345678901234";
        FontesDoTurno turno = new FontesDoTurno("Foram " + grande + " e 3.", 2027, LocalDate.of(2026, 11, 1));

        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\""
                + grande + "\"}}}")).contains("grande");
        // o resultado que não cabe na coluna também: 99.999.999.999.999 vezes 2
        FontesDoTurno outro = new FontesDoTurno("Foram 99999999999999 pessoas.", 2027, LocalDate.of(2026, 11, 1));
        assertThat(erro(contexto(), outro, "{\"nome\":\"y\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\","
                + "\"valor\":\"99999999999999\"}}}")).contains("grande");
        assertThat(outro.fontes()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Unidades
    // ---------------------------------------------------------------------

    @Test
    void naoMisturaInsercaoCpmEAplicacao() {
        FontesDoTurno turno = new FontesDoTurno("São 5 e 8 e 2.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();
        String insercao = "{\"fonte\":\"usuario\",\"valor\":\"5\",\"unidade\":\"INSERCAO\"}";
        String cpm = "{\"fonte\":\"usuario\",\"valor\":\"8\",\"unidade\":\"CPM\"}";
        String aplicacao = "{\"fonte\":\"usuario\",\"valor\":\"2\",\"unidade\":\"APLICACAO\"}";

        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + insercao + ",\"b\":" + cpm + "}}"))
                .contains("INSERCAO", "CPM");
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + cpm + ",\"b\":" + aplicacao + "}}"))
                .contains("CPM", "APLICACAO");
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + insercao + ",\"b\":" + aplicacao + "}}"))
                .contains("INSERCAO", "APLICACAO");
        // a mesma unidade, ou uma só variável com unidade ao lado de outras sem, passa
        String outraInsercao = "{\"fonte\":\"usuario\",\"valor\":\"8\",\"unidade\":\"INSERCAO\"}";
        assertThat(rodar(contexto, turno, "{\"nome\":\"y\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + insercao + ",\"b\":" + outraInsercao + "}}"))
                .containsEntry("valorFormatado", "13");
        assertThat(rodar(contexto, turno, "{\"nome\":\"z\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + insercao
                + ",\"b\":{\"fonte\":\"usuario\",\"valor\":\"2\"}}}")).containsEntry("valorFormatado", "7");
    }

    @Test
    void aUnidadeDaReferenciaDoPortalVemDeCons() {
        FontesDoTurno turno = new FontesDoTurno("Pagamos 5 por inserção.", 2027, LocalDate.of(2026, 11, 1));

        // cons:aplicacoes.previstas é APLICACAO; somar com INSERCAO é misturar
        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"ref\":\"cons:aplicacoes.previstas\"},"
                + "\"b\":{\"fonte\":\"usuario\",\"valor\":\"5\",\"unidade\":\"INSERCAO\"}}}")).contains("APLICACAO", "INSERCAO");
        // previstas com comprovadas é a mesma unidade
        assertThat(rodar(contexto(), turno, "{\"nome\":\"y\",\"script\":\"a - b\",\"variaveis\":{\"a\":{\"ref\":\"cons:aplicacoes.previstas\"},"
                + "\"b\":{\"ref\":\"cons:aplicacoes.comprovadas\"}}}")).containsEntry("valorFormatado", "2");
    }

    @Test
    void aUnidadeDoRegistroVemDeDadosDaFonteR() {
        FontesDoTurno turno = new FontesDoTurno("Foram 3 insercoes e 4 aplicações.", 2027, LocalDate.of(2026, 11, 1));
        turno.registrarRegistro("midia", 1, "Mídia", null, "Entregues 3 inserções.", Map.of("unidade", "INSERCAO"));
        turno.registrarRegistro("aplicacao", 2, "Aplicação", null, "Comprovadas 4 aplicações.", Map.of("unidade", "APLICACAO"));

        assertThat(erro(contexto(), turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"fonte\":\"R1\",\"valor\":\"3\"},"
                + "\"b\":{\"fonte\":\"R2\",\"valor\":\"4\"}}}")).contains("INSERCAO", "APLICACAO");
    }

    @Test
    void unidadeForaDoConjuntoEErro() {
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\","
                + "\"unidade\":\"METROS\"}}}")).contains("METROS");
    }

    // ---------------------------------------------------------------------
    // O nome, o formato e os argumentos
    // ---------------------------------------------------------------------

    @Test
    void nomeForaDoPadraoOuDeFormulaFixaOuRepetidoNoTurnoEErro() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();

        for (String nome : List.of("Tarifa", "tarifa-2", "20kva", "_tarifa", "", " ", "a".repeat(61), "preço", "taxa_cadastro", "brindes_pct")) {
            assertThat(erro(contexto, turno, tarifa(nome, null))).as(nome).isNotBlank();
        }
        rodar(contexto, turno, tarifa("tarifa", null));
        assertThat(erro(contexto, turno, tarifa("tarifa", null))).contains("tarifa");
        assertThat(turno.contas()).hasSize(1);
        assertThat(rodar(contexto, turno, tarifa("outra", null))).containsEntry("marcador", "C2");
    }

    @Test
    void nomeDeContaPodeTerAlgarismoDepoisDaPrimeiraLetra() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();

        assertThat(rodar(contexto, turno, tarifa("energia_20kva", null))).containsEntry("marcador", "C1").containsEntry("ref", "calc:energia_20kva");
        assertThat(rodar(contexto, turno, tarifa("tarifa2", null))).containsEntry("marcador", "C2").containsEntry("ref", "calc:tarifa2");
        // e o nome com algarismo não faz o algarismo valer como número da resposta
        assertThat(turno.fonte("C1").orElseThrow().conteudoCitavel()).doesNotContain("20");
        assertThat(turno.fonte("C2").orElseThrow().conteudoCitavel()).doesNotContain("tarifa2");
        // uma conta de nome com algarismo entra em outra
        assertThat(rodar(contexto, turno, "{\"nome\":\"dobro2\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"ref\":\"calc:energia_20kva\"}}}"))
                .containsEntry("marcador", "C3");
    }

    @Test
    void nomeDeSessentaCaracteresValeEFormatoDesconhecidoNao() {
        assertThat(rodar(contexto(), turno(), tarifa("a".repeat(60), null))).containsEntry("marcador", "C1");
        assertThat(erro(contexto(), turno(), tarifa("tarifa", "dolar"))).contains("formato");
    }

    @Test
    void argumentoDesconhecidoEErroEParceriaNuncaEArgumento() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();

        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"parceria_id\":3,\"variaveis\":{\"a\":{\"fonte\":\"usuario\","
                + "\"valor\":\"34\"}}}")).contains("parceria_id");
        // nem dentro da variável
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"ref\":\"cons:aplicacoes.previstas\","
                + "\"parceria\":3}}}")).contains("parceria");
        assertThat(turno.fontes()).isEmpty();
    }

    @Test
    void variavelTemDeTerRefOuFonteEValorMasNaoOsDois() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();

        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{}}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"ref\":\"cons:aplicacoes.previstas\","
                + "\"fonte\":\"usuario\",\"valor\":\"34\"}}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\"}}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"valor\":\"34\"}}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":34}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":[]}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\"}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}")).isNotBlank();
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":42,\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}")).isNotBlank();
        assertThat(turno.fontes()).isEmpty();
    }

    @Test
    void soValemRefsCalcDadoECons() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();

        for (String ref : List.of("parc:patrocinador", "parc:cota", "evento:edicao", "lista:aplicacoes", "lista:comprovacoes", "dado:inexistente.x",
                "dado:ativacao.foto", "cons:inexistente", "calc:nao_existe", "qualquer coisa", "")) {
            assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"ref\":\"" + ref + "\"}}}"))
                    .as(ref).isNotBlank();
        }
        assertThat(turno.fontes()).isEmpty();
    }

    @Test
    void referenciaDeUmaDataNaoEUmNumeroParaConta() {
        // dado informado do tipo número vale; a edição é texto
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"ref\":\"evento:edicao\"}}}")).isNotBlank();
    }

    @Test
    void variavelDeclaradaQueOScriptNaoUsaEVariavelQueNaoFoiDeclaradaSaoErro() {
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"a * 2\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"},"
                + "\"b\":{\"fonte\":\"usuario\",\"valor\":\"312\"}}}")).contains("b");
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"a * c\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}"))
                .contains("c");
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"pct * 2\",\"variaveis\":{\"pct\":{\"fonte\":\"usuario\","
                + "\"valor\":\"34\"}}}")).contains("pct");
        assertThat(erro(contexto(), turno(), "{\"nome\":\"x\",\"script\":\"new * 2\",\"variaveis\":{\"new\":{\"fonte\":\"usuario\","
                + "\"valor\":\"34\"}}}")).contains("new");
    }

    // ---------------------------------------------------------------------
    // Fabricar número: redundância, cadeia de constantes e arredondamento em cadeia
    // ---------------------------------------------------------------------

    @Test
    void duasVariaveisComAMesmaOrigemEOMesmoValorQueApareceUmaVezSaoRedundantes() {
        FontesDoTurno turno = new FontesDoTurno("São 34 kVA e 17 geradores.", 2027, LocalDate.of(2026, 11, 1));
        turno.registrarRegistro("entrega", 1, "Geradores", null, "Há 34 geradores.", Map.of());
        AtlasContexto contexto = contexto();

        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"},"
                + "\"b\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}")).contains("a", "b", "mesma origem");
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"fonte\":\"R1\",\"valor\":\"34\"},"
                + "\"b\":{\"fonte\":\"R1\",\"valor\":\"34\"}}}")).contains("mesma origem");
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"ref\":\"cons:aplicacoes.previstas\"},"
                + "\"b\":{\"ref\":\"cons:aplicacoes.previstas\"}}}")).contains("mesma origem");
        assertThat(turno.contas()).isEmpty();
        // o mesmo valor de origens diferentes, e valores diferentes da mesma origem, passam
        assertThat(rodar(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"},"
                + "\"b\":{\"fonte\":\"R1\",\"valor\":\"34\"}}}")).containsEntry("valorFormatado", "68");
        assertThat(rodar(contexto, turno, "{\"nome\":\"y\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"34\"},"
                + "\"b\":{\"fonte\":\"usuario\",\"valor\":\"17\"}}}")).containsEntry("valorFormatado", "51");
    }

    @Test
    void dois19NaMensagemDaPessoaSaoDoisValoresEOMesmoNumeroDuasVezesNumaFonteTambem() {
        AtlasContexto contexto = contexto();
        // "19 previstas e 19 comprovadas", "tinha 40 brindes e distribuí 40": o valor aparece duas vezes, são dois valores
        FontesDoTurno previstas = new FontesDoTurno("19 previstas e 19 comprovadas.", 2027, LocalDate.of(2026, 11, 1));
        assertThat(rodar(contexto, previstas, "{\"nome\":\"cumprimento\",\"script\":\"pct(feitas, previstas)\",\"variaveis\":{"
                + "\"previstas\":{\"fonte\":\"usuario\",\"valor\":\"19\"},\"feitas\":{\"fonte\":\"usuario\",\"valor\":\"19\"}}}"))
                .containsEntry("valorFormatado", "100");
        FontesDoTurno brindes = new FontesDoTurno("Tinha 40 brindes e distribuí 40.", 2027, LocalDate.of(2026, 11, 1));
        assertThat(rodar(contexto, brindes, "{\"nome\":\"sobra\",\"script\":\"tinha - distribuidos\",\"variaveis\":{"
                + "\"tinha\":{\"fonte\":\"usuario\",\"valor\":\"40\"},\"distribuidos\":{\"fonte\":\"usuario\",\"valor\":\"40\"}}}"))
                .containsEntry("valorFormatado", "0");
        // numa fonte: duas linhas com o mesmo número
        FontesDoTurno registro = new FontesDoTurno("Quanto?", 2027, LocalDate.of(2026, 11, 1));
        registro.registrarRegistro("entrega", 1, "Brindes", null, "Previsto: 40 brindes. Entregue: 40 brindes.", Map.of());
        assertThat(rodar(contexto, registro, "{\"nome\":\"total\",\"script\":\"a + b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"R1\",\"valor\":\"40\"},\"b\":{\"fonte\":\"R1\",\"valor\":\"40\"}}}")).containsEntry("valorFormatado", "80");
        // a mesma pessoa em duas mensagens: o número aparece duas vezes na conversa
        FontesDoTurno duasMensagens = new FontesDoTurno("E se distribuir 40?", 2027, LocalDate.of(2026, 11, 1))
                .comMensagensAnteriores(List.of("Tenho 40 brindes."));
        assertThat(rodar(contexto, duasMensagens, "{\"nome\":\"resto\",\"script\":\"tenho - dou\",\"variaveis\":{"
                + "\"tenho\":{\"fonte\":\"usuario\",\"valor\":\"40\"},\"dou\":{\"fonte\":\"usuario\",\"valor\":\"40\"}}}"))
                .containsEntry("valorFormatado", "0");
    }

    @Test
    void aMesmaContaPorRefOuPorMarcadorEAMesmaOrigem() {
        FontesDoTurno turno = turno();
        AtlasContexto contexto = contexto();
        rodar(contexto, turno, tarifa("tarifa", "moeda"));   // C1: o resultado 10.608,00 aparece uma vez no texto da fonte

        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"ref\":\"calc:tarifa\"},"
                + "\"b\":{\"fonte\":\"C1\",\"valor\":\"10.608,00\"}}}")).contains("mesma origem", "C1");
        assertThat(erro(contexto, turno, "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"ref\":\"calc:tarifa\"},"
                + "\"b\":{\"ref\":\"calc:tarifa\"}}}")).contains("mesma origem");
        assertThat(turno.conta("x")).isEmpty();
    }

    @Test
    void oLimiteDeDuasConstantesEDaCadeiaInteiraENaoDeCadaConta() {
        FontesDoTurno turno = turno();
        AtlasContexto contexto = contexto();
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        // c1 tem 1 constante; c2 usa c1 e soma 1; c3 usaria c2 e mais uma: 3 pela cadeia
        rodar(contexto, turno, "{\"nome\":\"ca\",\"script\":\"preco * 2\",\"variaveis\":{\"preco\":{\"fonte\":\"usuario\",\"valor\":\"312\"}}}");
        rodar(contexto, turno, "{\"nome\":\"cb\",\"script\":\"t + 1\",\"variaveis\":{\"t\":{\"ref\":\"calc:ca\"}}}");
        assertThat(ContaDoChat.deDados(turno.fonte("C1").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(1);
        assertThat(ContaDoChat.deDados(turno.fonte("C2").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(2);
        String erro = erro(contexto, turno, "{\"nome\":\"cc\",\"script\":\"t * 2\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"}}}");
        assertThat(erro).contains("3 constantes", "cadeia", "máximo é 2");
        assertThat(turno.conta("cc")).as("a conta recusada não gasta o nome nem vira fonte").isEmpty();
        // sem constante nova, a cadeia continua em 2; a conta c4 não soma nada
        assertThat(rodar(contexto, turno, "{\"nome\":\"cd\",\"script\":\"t + area\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"},"
                + "\"area\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}")).containsEntry("marcador", "C3");
        assertThat(ContaDoChat.deDados(turno.fonte("C3").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(2);
        // a fórmula fixa do modelo conta zero: duas constantes no script e a fórmula fixa passam
        assertThat(rodar(contexto, turno, "{\"nome\":\"ce\",\"script\":\"taxa * 2 + 1\",\"variaveis\":{\"taxa\":{\"ref\":\"calc:taxa_cadastro\"}}}"))
                .containsEntry("valorFormatado", "179,6");
    }

    @Test
    void tresPercentuaisESeuTotalNaoEstouramACadeiaPoisODivisorCemSoEncolhe() {
        FontesDoTurno turno = new FontesDoTurno("Comissão de 10% sobre 1200, 800 e 500; some tudo.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        for (String[] item : new String[][] {{"x", "1200"}, {"y", "800"}, {"z", "500"}}) {
            rodar(contexto, turno, "{\"nome\":\"" + item[0] + "\",\"script\":\"v * p / 100\",\"variaveis\":{"
                    + "\"v\":{\"fonte\":\"usuario\",\"valor\":\"" + item[1] + "\"},\"p\":{\"fonte\":\"usuario\",\"valor\":\"10\"}}}");
        }
        Map<String, Object> total = rodar(contexto, turno, "{\"nome\":\"total\",\"script\":\"x + y + z\",\"variaveis\":{"
                + "\"x\":{\"ref\":\"calc:x\"},\"y\":{\"ref\":\"calc:y\"},\"z\":{\"ref\":\"calc:z\"}}}");

        assertThat(total).containsEntry("valorFormatado", "250");
        assertThat(ContaDoChat.deDados(turno.fonte("C1").orElseThrow().dados()).orElseThrow().constantes()).as("o 100 que divide não se acumula").isZero();
        assertThat(ContaDoChat.deDados(turno.fonte("C4").orElseThrow().dados()).orElseThrow().constantes()).isZero();
        // o 100 que divide ainda conta no limite do próprio script: três constantes num script seguem recusadas
        assertThat(erro(contexto, turno, "{\"nome\":\"tres\",\"script\":\"v / 100 / 100 / 100\",\"variaveis\":{"
                + "\"v\":{\"fonte\":\"usuario\",\"valor\":\"1200\"}}}")).contains("3 constantes");
    }

    @Test
    void oCemQueMultiplicaSeguePesandoNaCadeiaEODivisorNaoLimpaAsConstantesDeAntes() {
        FontesDoTurno turno = new FontesDoTurno("Quero 7 e 5 unidades.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        rodar(contexto, turno, "{\"nome\":\"ca\",\"script\":\"a + 100\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"7\"}}}");
        rodar(contexto, turno, "{\"nome\":\"cb\",\"script\":\"t * 100\",\"variaveis\":{\"t\":{\"ref\":\"calc:ca\"}}}");
        // dividir por 100 não esconde as duas constantes que a cadeia já tem
        assertThat(erro(contexto, turno, "{\"nome\":\"cc\",\"script\":\"t / 100 + 2\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"}}}"))
                .contains("constantes", "cadeia");
        assertThat(rodar(contexto, turno, "{\"nome\":\"cd\",\"script\":\"t / 100\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"}}}"))
                .containsEntry("marcador", "C3");
        assertThat(ContaDoChat.deDados(turno.fonte("C3").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(2);
    }

    @Test
    void oValorLiteralDeUmaContaCDaFonteHerdaAsConstantesDelaComoORef() {
        FontesDoTurno turno = new FontesDoTurno("Quero 7 unidades.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();
        // a + 100 + 100 dá 207, com as duas constantes que o script pode ter
        assertThat(rodar(contexto, turno, "{\"nome\":\"ca\",\"script\":\"a + 100 + 100\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"7\"}}}")).containsEntry("valorFormatado", "207");

        // pelo ref a cadeia já pegava; pelo literal copiado do texto de C1 o limite zerava
        assertThat(erro(contexto, turno, "{\"nome\":\"cb\",\"script\":\"t + 100\",\"variaveis\":{\"t\":{\"ref\":\"calc:ca\"}}}"))
                .contains("constantes", "cadeia");
        assertThat(erro(contexto, turno, "{\"nome\":\"cc\",\"script\":\"t + 100\",\"variaveis\":{\"t\":{\"fonte\":\"C1\",\"valor\":\"207\"}}}"))
                .contains("3 constantes", "cadeia");
        assertThat(turno.contas()).hasSize(1);
        // sem constante nova, o literal da conta passa, e leva as duas adiante
        assertThat(rodar(contexto, turno, "{\"nome\":\"cd\",\"script\":\"t * u\",\"variaveis\":{\"t\":{\"fonte\":\"C1\",\"valor\":\"207\"},"
                + "\"u\":{\"fonte\":\"usuario\",\"valor\":\"7\"}}}")).containsEntry("marcador", "C2");
        assertThat(ContaDoChat.deDados(turno.fonte("C2").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(2);
    }

    @Test
    void oValorLiteralDeUmaContagemDeDiasNaoCarregaConstante() {
        FontesDoTurno turno = new FontesDoTurno("Quero 7 unidades.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();
        turno.registrarCalculo(null, "Contagem de dias", "Contei 5 dias corridos depois de 03/11/2026. Resultado: 08/11/2026.",
                Map.of("ferramenta", "contar_dias", "resultado", "5"));

        Map<String, Object> resultado = rodar(contexto, turno, "{\"nome\":\"cd\",\"script\":\"t * 2 + 1\",\"variaveis\":{"
                + "\"t\":{\"fonte\":\"C1\",\"valor\":\"5\"}}}");

        assertThat(resultado).containsEntry("valorFormatado", "11");
        assertThat(ContaDoChat.deDados(turno.fonte("C2").orElseThrow().dados()).orElseThrow().constantes()).isEqualTo(2);
    }

    @Test
    void oAtaqueDeEncadearContasParaChegarEm312NaoFecha() {
        // x + 100 + 100 numa conta e y + 100 + 2, y + 2 + 2... depois: com a cadeia, a terceira conta já não passa
        FontesDoTurno turno = new FontesDoTurno("Quero 7 e 5 unidades.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        rodar(contexto, turno, "{\"nome\":\"ca\",\"script\":\"a + 100\",\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"7\"}}}");
        rodar(contexto, turno, "{\"nome\":\"cb\",\"script\":\"t + 100\",\"variaveis\":{\"t\":{\"ref\":\"calc:ca\"}}}");

        assertThat(erro(contexto, turno, "{\"nome\":\"cc\",\"script\":\"t + 2\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"}}}"))
                .contains("constantes", "cadeia");
        assertThat(erro(contexto, turno, "{\"nome\":\"cd\",\"script\":\"t + 1\",\"variaveis\":{\"t\":{\"ref\":\"calc:cb\"}}}"))
                .contains("constantes", "cadeia");
        assertThat(turno.contas()).hasSize(2);
    }

    @Test
    void aContaSeguinteUsaOValorSemArredondarSoOQueSaiParaATelaEArredondado() {
        FontesDoTurno turno = new FontesDoTurno("Divida 100 por 3 e depois multiplique por 3.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();

        Map<String, Object> terco = rodar(contexto, turno, "{\"nome\":\"terco\",\"script\":\"a / b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"100\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}");
        Map<String, Object> de_volta = rodar(contexto, turno, "{\"nome\":\"de_volta\",\"script\":\"t * n\",\"variaveis\":{"
                + "\"t\":{\"ref\":\"calc:terco\"},\"n\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}");

        assertThat(terco).containsEntry("valorFormatado", "33,33");
        assertThat(de_volta).as("100/3 vezes 3 é 100, e não 99,99").containsEntry("valorFormatado", "100");
        // a tela mostra a variável arredondada, e a conta guarda o número exato
        assertThat(de_volta.get("variaveis")).isEqualTo(List.of(Map.of("nome", "t", "origem", "calc:terco", "valor", "33,33"),
                Map.of("nome", "n", "origem", "usuario", "valor", "3")));
        ContaDoChat guardada = ContaDoChat.deDados(turno.fonte("C1").orElseThrow().dados()).orElseThrow();
        assertThat(guardada.valor()).isEqualByComparingTo("33.33");
        assertThat(guardada.valorExato().scale()).as("DECIMAL128: 34 dígitos").isGreaterThan(20);
        assertThat(guardada.comoValor().numero()).isEqualByComparingTo(guardada.valorExato());
        assertThat(guardada.comoValor().texto()).isEqualTo("33,33");
        // o que se grava e se lê de volta (a releitura da conversa e o relatório) mantém o exato
        ContaDoChat relida = ContaDoChat.deDados(AtlasJson.ler(AtlasJson.escrever(guardada.comoDados()), new TypeReference<Map<String, Object>>() {
        })).orElseThrow();
        assertThat(relida.valorExato()).isEqualByComparingTo(guardada.valorExato());
        assertThat(relida.constantes()).isZero();
    }

    // ---------------------------------------------------------------------
    // Os ataques ao script (bloqueante do plano): todos recusados, e nada fica
    // ---------------------------------------------------------------------

    @Test
    void osAtaquesAoScriptSaoTodosRecusadosENadaFicaNoTurno() {
        AtlasContexto contexto = contexto();
        FontesDoTurno turno = turno();
        String variavel = "\"variaveis\":{\"x\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}";
        List<String> scripts = List.of(
                "new('java.lang.ProcessBuilder', 'ls')",
                "new('java.lang.ProcessBuilder', x)",
                "''.class",
                "x.getClass()",
                "x.getClass().getClassLoader()",
                "#pragma jexl.import java.lang",
                "while (x) { }",
                "for (i : x) { }",
                "(y) -> y",
                "x.toString()",
                "math:abs(x)",
                "x * 312",
                "'312'",
                "\\\"31\\\" + \\\"2\\\"",
                "100+100+100+2*2*2*2-2-2",
                "x + 100+100+100+2*2*2*2-2-2",
                "x + 1 + 1 + 1",
                "100",
                "'a' =~ 'a'",
                "x * 1e3",
                "x * 0100",
                "x".repeat(1) + " ".repeat(2_000));
        for (String script : scripts) {
            assertThat(erro(contexto, turno, "{\"nome\":\"ataque\",\"script\":\"" + script + "\"," + variavel + "}"))
                    .as(script).isNotBlank();
        }
        assertThat(turno.fontes()).isEmpty();
        assertThat(turno.numerosLivres()).isEmpty();
        assertThat(turno.conta("ataque")).as("o nome não foi gasto").isEmpty();
    }

    @Test
    void asFuncoesPctMinEMaxRodamPeloMotor() {
        FontesDoTurno turno = new FontesDoTurno("Foram 17 de 19 e 3.", 2027, LocalDate.of(2026, 11, 1));
        AtlasContexto contexto = contexto();
        String variaveis = "\"variaveis\":{\"a\":{\"fonte\":\"usuario\",\"valor\":\"17\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"19\"}}";

        assertThat(rodar(contexto, turno, "{\"nome\":\"p\",\"script\":\"pct(a, b)\"," + variaveis + "}")).containsEntry("valorFormatado", "89,4");
        assertThat(rodar(contexto, turno, "{\"nome\":\"menor\",\"script\":\"min(a, b)\"," + variaveis + "}")).containsEntry("valorFormatado", "17");
        assertThat(rodar(contexto, turno, "{\"nome\":\"maior\",\"script\":\"max(a, b)\"," + variaveis + "}")).containsEntry("valorFormatado", "19");
    }

    // ---------------------------------------------------------------------
    // Registro e oferta
    // ---------------------------------------------------------------------

    @Test
    void aDefinicaoDizAoModeloAsRegrasDaOrigemDoScriptEDaCadeia() {
        String descricao = calcular.definicao().descricao();

        assertThat(descricao).contains("nesta conversa", "no máximo 2 vezes", "sempre o mesmo resultado", "pct(parte, todo)", "a * 100 / b é recusado", "mesma origem", "o ano de uma data não é valor",
                "pela cadeia");
        assertThat(calcular.definicao().esquema()).contains("^[a-z][a-z0-9_]{0,59}$");
    }

    @Test
    void ferramentaSemParceriaOferecidaEmTodoEstadoMenosOCartaoAberto() {
        assertThat(calcular.nome()).isEqualTo("calcular");
        assertThat(calcular.definicao().esquema()).contains("\"additionalProperties\":false").doesNotContain("parceria");
        assertThat(calcular.ordem()).isGreaterThan(0);
    }
}
