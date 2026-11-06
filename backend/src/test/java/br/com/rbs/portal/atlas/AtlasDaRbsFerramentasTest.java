package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.SemPermissaoException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [ISOLAMENTO] [PERMISSAO] As ferramentas do Atlas da RBS (M8): só {@code buscar_documentos} (nos documentos {@code GLOBAL}),
 * {@code contar_dias}, {@code calcular} e {@code gerar_modelo}, nesta ordem. Nenhuma ferramenta de parceria, de registros ou de
 * relatório é oferecida, e o pedido de uma delas pelo nome volta como "não existe". Aqui o registro e as ferramentas rodam sem o
 * loop, com o contexto da RBS (sem parceria, cota nem abas, e sem JEV). Cada teste é desfeito no fim (@Transactional).
 */
@TesteDoAtlasDaRbs
@Transactional
class AtlasDaRbsFerramentasTest {

    private static final List<String> FERRAMENTAS_DA_RBS = List.of("buscar_documentos", "listar_entregas", "consultar_midia",
            "contar_dias", "calcular", "gerar_modelo");
    private static final List<String> FERRAMENTAS_DE_MARCA = List.of("parceria_consultar", "consultar_cota",
            "consultar_prazos", "pedido_de_relatorio", "relatorio_iniciar", "relatorio_solicitar_campos", "relatorio_editar",
            "relatorio_finalizar");

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasDaRbsService rbs;

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    JdbcClient jdbc;

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :edicao").param("edicao", edicao).query(Long.class).single();
    }

    AtlasContexto contextoDaRbs() {
        var pessoa = usuario(jdbc, RBS);
        long conversa = rbs.abrir(pessoa, evento(2027)).conversaId();
        return contextos.paraARbs(pessoa, evento(2027), conversa, requisicao());
    }

    static FontesDoTurno turno(String mensagem) {
        return new FontesDoTurno(mensagem, 2027, LocalDate.of(2026, 11, 1));
    }

    Execucao rodar(AtlasContexto contexto, FontesDoTurno turno, String ferramenta, String argumentos) {
        var preparo = registro.preparar(contexto, turno, ferramenta, argumentos);
        return registro.executar(contexto, turno, preparo, "call_x");
    }

    static List<String> nomes(List<DefinicaoDeFerramenta> ferramentas) {
        return ferramentas.stream().map(DefinicaoDeFerramenta::nome).toList();
    }

    static Map<String, Object> json(String texto) {
        return AtlasJson.ler(texto, new TypeReference<Map<String, Object>>() {
        });
    }

    // ---------------------------------------------------------------------
    // O contexto
    // ---------------------------------------------------------------------

    @Test
    void oContextoDaRbsNaoTemParceriaCotaAbasNemJev() {
        AtlasContexto contexto = contextoDaRbs();

        assertThat(contexto.daRbs()).isTrue();
        assertThat(contexto.parceriaId()).isEqualTo(AtlasContexto.SEM_PARCERIA).isZero();
        assertThat(contexto.eventoId()).isEqualTo(evento(2027));
        assertThat(contexto.cotaTipoId()).isZero();
        assertThat(contexto.cota()).isNull();
        assertThat(contexto.abasAbertas()).isEmpty();
        assertThat(contexto.jev().ativo()).as("o JEV está desligado na conversa da RBS").isFalse();
        assertThat(contexto.jev().restante()).isZero();
    }

    @Test
    void oContextoDeUmaConversaDeMarcaNaoEDaRbs() {
        var pessoa = usuario(jdbc, AMARELO);
        long parceria = br.com.rbs.portal.acesso.SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = jdbc.sql("INSERT INTO atlas_conversa (parceria_id, usuario_id) VALUES (:p, :u) RETURNING id").param("p", parceria)
                .param("u", pessoa.id()).query(Long.class).single();

        AtlasContexto contexto = contextos.para(pessoa, parceria, conversa, requisicao());

        assertThat(contexto.daRbs()).isFalse();
        assertThat(contexto.jev().ativo()).isTrue();
    }

    @Test
    void aMarcaNaoMontaOContextoDaRbs() {
        var marca = usuario(jdbc, AMARELO);

        assertThatThrownBy(() -> contextos.paraARbs(marca, evento(2027), 1, requisicao())).isInstanceOf(SemPermissaoException.class);
    }

    // ---------------------------------------------------------------------
    // O que o modelo recebe
    // ---------------------------------------------------------------------

    @Test
    void oModeloDaRbsRecebeAsSeisFerramentasDela() {
        assertThat(nomes(registro.oferecidasDaRbs(false))).containsExactlyElementsOf(FERRAMENTAS_DA_RBS);
    }

    @Test
    void semAsDiretasFicamCincoEAGeracaoDeModeloSai() {
        assertThat(nomes(registro.oferecidasDaRbs(true))).containsExactly("buscar_documentos", "listar_entregas", "consultar_midia",
                "contar_dias", "calcular");
    }

    @Test
    void nenhumaDefinicaoDaRbsFalaDeRelatorioNemAceitaIdDeParceria() {
        for (DefinicaoDeFerramenta definicao : registro.oferecidasDaRbs(false)) {
            String tudo = (definicao.descricao() + " " + definicao.esquema()).toLowerCase();
            assertThat(tudo).as(definicao.nome()).doesNotContain("parceria_id").doesNotContain("cons:")
                    .doesNotContain("dado:ativacao").doesNotContain("dado:...").doesNotContain("registro r#")
                    .doesNotContain("relatorio_iniciar").doesNotContain("relatorio_editar").doesNotContain("relatorio_finalizar")
                    .doesNotContain("relatorio_solicitar");
        }
        DefinicaoDeFerramenta busca = registro.oferecidasDaRbs(false).get(0);
        assertThat(busca.descricao()).contains("documentos da edição").contains("nome da marca");
        assertThat(registro.oferecidasDaRbs(false).stream().filter(d -> d.nome().equals("consultar_midia")).findFirst().orElseThrow()
                .esquema()).contains("\"parceria\"", "\"cota\"");
    }

    @Test
    void oGerarModeloDaRbsListaOsQuatroTiposComOEsqueleto() {
        DefinicaoDeFerramenta gerar = registro.oferecidasDaRbs(false).stream().filter(d -> d.nome().equals("gerar_modelo")).findFirst()
                .orElseThrow();

        assertThat(gerar.esquema()).contains("BRIEFING_CONTEUDO", "PERGUNTAS_PESQUISA", "PLANO_OPERACIONAL_ATIVACAO",
                "ESQUELETO_RELATORIO_COTA");
        assertThat(gerar.descricao()).contains("ESQUELETO_RELATORIO_COTA");
    }

    @Test
    void asDefinicoesDaMarcaNaoMudaramENaoNomeiamOEsqueleto() {
        // as da marca seguem as de sempre: quem pede as oferecidas de uma conversa de marca não vê o tipo da RBS
        String ofertaDaMarca = registro.oferecidas(Optional.empty(), true).toString();

        assertThat(ofertaDaMarca).doesNotContain("ESQUELETO_RELATORIO_COTA").contains("parceria_consultar");
    }

    // ---------------------------------------------------------------------
    // Ferramenta de marca na conversa da RBS: não existe
    // ---------------------------------------------------------------------

    @Test
    void cadaFerramentaDeMarcaPedidaPeloNomeNaConversaDaRbsVoltaComoErroENadaRoda() {
        AtlasContexto contexto = contextoDaRbs();
        long relatorios = jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single();

        for (String ferramenta : FERRAMENTAS_DE_MARCA) {
            for (String argumentos : List.of("{}", "{\"parceria_id\":1}", "{\"modelo\":\"ATIVACAO_EVENTO\"}")) {
                assertThatThrownBy(() -> registro.preparar(contexto, turno("Oi"), ferramenta, argumentos)).as(ferramenta + " " + argumentos)
                        .isInstanceOf(DadosInvalidosException.class)
                        .hasMessage("Não existe a ferramenta " + ferramenta + ". Use só as ferramentas oferecidas.");
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single())
                .as("nenhum relatório nasceu da conversa da RBS").isEqualTo(relatorios);
    }

    @Test
    void ferramentaQueNaoExisteContinuaSendoErroNaConversaDaRbs() {
        AtlasContexto contexto = contextoDaRbs();

        assertThatThrownBy(() -> registro.preparar(contexto, turno("Oi"), "apagar_tudo", "{}")).isInstanceOf(DadosInvalidosException.class)
                .hasMessage("Não existe a ferramenta apagar_tudo. Use só as ferramentas oferecidas.");
    }

    // ---------------------------------------------------------------------
    // buscar_documentos: todos os escopos da edição
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> trechos(Execucao execucao) {
        return (List<Map<String, Object>>) json(execucao.resultadoJson()).get("trechos");
    }

    @Test
    void aBuscaDaRbsAchaOManualDaEdicaoComAFonte() {
        AtlasContexto contexto = contextoDaRbs();

        Execucao execucao = rodar(contexto, turno("prazo dos geradores"), "buscar_documentos",
                "{\"consulta\":\"Até quando preciso informar os geradores na Arena\"}");

        assertThat(trechos(execucao)).isNotEmpty().anySatisfy(trecho -> {
            assertThat(trecho.get("documento")).isEqualTo("Manual de Operações 2027");
            assertThat((String) trecho.get("texto")).contains("04/12/2026");
        });
    }

    @Test
    void aBuscaDaRbsAlcancaOsDocumentosDeCotaEDeParceria() {
        AtlasContexto contexto = contextoDaRbs();

        Execucao plano = rodar(contexto, turno("plano"), "buscar_documentos",
                "{\"consulta\":\"cortesias credenciais plano comercial\",\"tipo\":\"PLANO_COMERCIAL\"}");
        Execucao anexo = rodar(contexto, turno("anexo"), "buscar_documentos",
                "{\"consulta\":\"tema do espaço da ativação\",\"tipo\":\"ANEXO_PARCERIA\"}");

        assertThat(trechos(plano)).isNotEmpty();
        assertThat(trechos(anexo)).isNotEmpty();
        assertThat(trechos(plano)).allSatisfy(trecho -> {
            String escopo = jdbc.sql("SELECT escopo FROM documento WHERE titulo = :t AND versao = :v")
                    .param("t", trecho.get("documento")).param("v", trecho.get("versao")).query(String.class).single();
            assertThat(escopo).isEqualTo("COTA");
            assertThat(trecho).doesNotContainKey("parceria");
        });
        assertThat(trechos(anexo)).anySatisfy(trecho -> {
            String escopo = jdbc.sql("SELECT escopo FROM documento WHERE titulo = :t AND versao = :v")
                    .param("t", trecho.get("documento")).param("v", trecho.get("versao")).query(String.class).single();
            assertThat(escopo).isEqualTo("PARCERIA");
            assertThat(trecho).containsKey("parceria");
        });
    }

    @Test
    void aBuscaDaRbsNaoAceitaParceriaCotaNemEscopoComoArgumento() {
        AtlasContexto contexto = contextoDaRbs();

        for (String lixo : List.of("\"parceria_id\":1", "\"parceriaId\":1", "\"cota\":\"MASTER\"", "\"escopo\":\"COTA\"", "\"evento_id\":1")) {
            assertThatThrownBy(() -> rodar(contexto, turno("x"), "buscar_documentos", "{\"consulta\":\"geradores\"," + lixo + "}")).as(lixo)
                    .isInstanceOf(DadosInvalidosException.class).satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos())
                            .hasSize(1).allSatisfy((campo, frase) -> assertThat(frase).startsWith("Argumento desconhecido")));
        }
    }

    // ---------------------------------------------------------------------
    // consultar_midia e listar_entregas na base toda
    // ---------------------------------------------------------------------

    @Test
    void aMidiaDaRbsFiltraPorMarcaECotaEDevolveAMarcaEmCadaLinha() {
        AtlasContexto contexto = contextoDaRbs();
        long amarelo = br.com.rbs.portal.acesso.SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");

        Execucao execucao = rodar(contexto, turno("mídia do amarelo"), "consultar_midia",
                "{\"parceria\":\"cliente amarelo\",\"cota\":\"MASTER\"}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> registros = (List<Map<String, Object>>) json(execucao.resultadoJson()).get("registros");

        assertThat(registros).isNotEmpty().allSatisfy(registro -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> parceria = (Map<String, Object>) registro.get("parceria");
            assertThat(parceria).containsEntry("id", (int) amarelo).containsEntry("patrocinador", "Cliente Amarelo")
                    .containsEntry("cota", "MASTER");
        });
        // o mesmo filtro pelo id
        Execucao porId = rodar(contexto, turno("mídia"), "consultar_midia", "{\"parceria\":\"" + amarelo + "\"}");
        assertThat((String) porId.resultadoJson()).contains("\"patrocinador\":\"Cliente Amarelo\"");
        assertThatThrownBy(() -> rodar(contexto, turno("x"), "consultar_midia", "{\"parceria\":\"Marca Que Não Existe\"}"))
                .isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos()).containsEntry("parceria",
                        "Não encontramos esta parceria nesta edição."));
    }

    @Test
    void aMidiaDaRbsSemFiltroLeAsMarcasDaEdicaoEAceitaOCortePorCota() {
        AtlasContexto contexto = contextoDaRbs();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todas = (List<Map<String, Object>>) json(rodar(contexto, turno("mídia"), "consultar_midia", "{}")
                .resultadoJson()).get("registros");
        assertThat(todas).extracting(r -> ((Map<?, ?>) r.get("parceria")).get("patrocinador"))
                .contains("Cliente Amarelo", "Cliente Roxo", "Cliente Verde");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subcotaG = (List<Map<String, Object>>) json(rodar(contexto, turno("mídia"),
                "consultar_midia", "{\"cota\":\"SUBCOTA_G\"}").resultadoJson()).get("registros");
        assertThat(subcotaG).isNotEmpty().allSatisfy(registro -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> parceria = (Map<String, Object>) registro.get("parceria");
            assertThat(parceria).containsEntry("patrocinador", "Cliente Roxo").containsEntry("cota", "SUBCOTA_G");
        });
    }

    @Test
    void oFaltaReceberDaRbsTrazSoOQueEsperaEnvioAgrupadoPorMarca() {
        AtlasContexto contexto = contextoDaRbs();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> registros = (List<Map<String, Object>>) json(rodar(contexto, turno("o que falta receber"),
                "listar_entregas", "{\"faltaReceber\":true}").resultadoJson()).get("registros");

        assertThat(registros).isNotEmpty().allSatisfy(registro -> {
            assertThat(registro.get("status")).isIn("AGUARDANDO_ENVIO", "AJUSTE_SOLICITADO");
            assertThat(registro).containsKey("marca");
        });
        assertThat(registros).extracting(r -> r.get("marca")).isSorted();
        assertThat(registros).extracting(r -> r.get("marca")).contains("Cliente Amarelo", "Cliente Roxo");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> doAmarelo = (List<Map<String, Object>>) json(rodar(contexto, turno("o que falta do amarelo"),
                "listar_entregas", "{\"parceria\":\"Cliente Amarelo\",\"faltaReceber\":true}").resultadoJson()).get("registros");
        assertThat(doAmarelo).isNotEmpty().allSatisfy(registro -> assertThat(registro).containsEntry("marca", "Cliente Amarelo"));
    }

    // ---------------------------------------------------------------------
    // gerar_modelo
    // ---------------------------------------------------------------------

    @Test
    void aRbsRecebeOsQuatroModelosInclusiveOEsqueletoEAPesquisa() {
        AtlasContexto contexto = contextoDaRbs();

        for (String tipo : List.of("BRIEFING_CONTEUDO", "PERGUNTAS_PESQUISA", "PLANO_OPERACIONAL_ATIVACAO", "ESQUELETO_RELATORIO_COTA")) {
            Execucao execucao = rodar(contexto, turno("modelo"), "gerar_modelo", "{\"tipo\":\"" + tipo + "\"}");

            assertThat(execucao.direta()).as(tipo).isTrue();
            assertThat(json(execucao.resultadoJson())).containsEntry("tipo", "modelo").containsEntry("modelo", tipo);
        }
        Map<String, Object> esqueleto = json(rodar(contexto, turno("modelo"), "gerar_modelo", "{\"tipo\":\"ESQUELETO_RELATORIO_COTA\"}")
                .resultadoJson());
        assertThat(esqueleto).containsEntry("titulo", "Esqueleto do relatório da cota")
                .containsEntry("nomeArquivo", "esqueleto-do-relatorio-da-cota.md");
        assertThat((String) esqueleto.get("conteudo")).contains("## Cumprimento das entregas").contains("## O festival em números")
                .doesNotContainPattern("[0-9]");
    }

    @Test
    void tipoDesconhecidoNaConversaDaRbsListaOsQuatro() {
        AtlasContexto contexto = contextoDaRbs();

        assertThatThrownBy(() -> rodar(contexto, turno("x"), "gerar_modelo", "{\"tipo\":\"FAQ\"}")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos().get("tipo")).contains("BRIEFING_CONTEUDO",
                        "PERGUNTAS_PESQUISA", "PLANO_OPERACIONAL_ATIVACAO", "ESQUELETO_RELATORIO_COTA"));
    }

    // ---------------------------------------------------------------------
    // calcular e contar_dias: sem ref de parceria
    // ---------------------------------------------------------------------

    @Test
    void aRbsFazContaComOQueEscreveuEComUmTrecho() {
        AtlasContexto contexto = contextoDaRbs();

        Execucao conta = rodar(contexto, turno("Quanto são 3 vezes 450?"), "calcular",
                "{\"nome\":\"total\",\"script\":\"a * b\",\"formato\":\"moeda\",\"variaveis\":{"
                        + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"3\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"450\"}}}");

        assertThat(json(conta.resultadoJson())).containsEntry("valorFormatado", "1.350,00").containsEntry("ref", "calc:total");
    }

    @Test
    void referenciaDeParceriaNaContaDaRbsVoltaComoErro() {
        AtlasContexto contexto = contextoDaRbs();

        for (String ref : List.of("cons:aplicacoes.previstas", "dado:ativacao.visitantes_total", "parc:patrocinador", "evento:edicao",
                "calc:conta_que_nao_existe")) {
            String argumentos = "{\"nome\":\"x\",\"script\":\"a + b\",\"variaveis\":{\"a\":{\"ref\":\"" + ref
                    + "\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"3\"}}}";
            assertThatThrownBy(() -> rodar(contexto, turno("some com 3"), "calcular", argumentos)).as(ref)
                    .isInstanceOf(DadosInvalidosException.class);
        }
    }

    @Test
    void aRbsContaDiasComAsDatasLivres() {
        AtlasContexto contexto = contextoDaRbs();

        Execucao dias = rodar(contexto, turno("20 dias úteis depois de hoje"), "contar_dias",
                "{\"inicio\":\"2026-11-01\",\"dias\":20,\"uteis\":true}");

        assertThat(json(dias.resultadoJson())).containsKey("resultado").containsKey("regra");
    }

    // ---------------------------------------------------------------------
    // A resolução de marcadores: sem parceria, só as contas
    // ---------------------------------------------------------------------

    @Test
    void aResolucaoDaRbsNaoResolveMarcadorDeParceria() {
        AtlasContexto contexto = contextoDaRbs();
        ResolvedorDeReferencias.Resolucao resolucao = resolvedor.abrir(contexto, null);

        for (String ref : List.of("parc:patrocinador", "parc:cota", "evento:edicao", "cons:aplicacoes.previstas", "cons:aplicacoes.percentual",
                "dado:ativacao.visitantes_total", "calc:taxa_cadastro")) {
            assertThatThrownBy(() -> resolucao.valor(ref)).as(ref).isInstanceOf(ReferenciaDesconhecidaException.class);
        }
        assertThatThrownBy(() -> resolucao.tabela("lista:aplicacoes")).isInstanceOf(ReferenciaDesconhecidaException.class);
        assertThatThrownBy(() -> resolucao.fotos("lista:comprovacoes")).isInstanceOf(ReferenciaDesconhecidaException.class);
        assertThat(resolucao.paraExibicao("Eu sou {{parc:patrocinador}} e vi {{cons:aplicacoes.previstas}}."))
                .as("na tela o marcador de parceria vira indisponível, nunca um dado").isEqualTo("Eu sou indisponível e vi indisponível.");
        assertThat(resolucao.calculos()).isEmpty();
    }
}
