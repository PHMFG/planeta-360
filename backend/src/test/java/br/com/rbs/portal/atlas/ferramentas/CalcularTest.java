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
        assertThat(rodar(contexto, turno, "{\"nome\":\"y\",\"script\":\"a + b\",\"variaveis\":{\"a\":" + insercao + ",\"b\":" + insercao + "}}"))
                .containsEntry("valorFormatado", "10");
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

        for (String nome : List.of("Tarifa", "tarifa-2", "tarifa2", "", " ", "a".repeat(61), "preço", "taxa_cadastro", "brindes_pct")) {
            assertThat(erro(contexto, turno, tarifa(nome, null))).as(nome).isNotBlank();
        }
        rodar(contexto, turno, tarifa("tarifa", null));
        assertThat(erro(contexto, turno, tarifa("tarifa", null))).contains("tarifa");
        assertThat(turno.contas()).hasSize(1);
        assertThat(rodar(contexto, turno, tarifa("outra", null))).containsEntry("marcador", "C2");
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
    void ferramentaSemParceriaOferecidaEmTodoEstadoMenosOCartaoAberto() {
        assertThat(calcular.nome()).isEqualTo("calcular");
        assertThat(calcular.definicao().esquema()).contains("\"additionalProperties\":false").doesNotContain("parceria");
        assertThat(calcular.ordem()).isGreaterThan(0);
    }
}
