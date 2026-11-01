package br.com.rbs.portal.atlas.busca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.ResultadoDaBusca;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [ATLAS] [ISOLAMENTO] A busca híbrida sobre o kit de documentos que o perfil de teste confirma no banco (M4, critério bloqueante). Esta
 * classe NÃO é {@code @Transactional}: a busca roda numa transação nova como {@code atlas_ro}, e só enxerga o que está confirmado, o
 * kit. Cobre o RRF, o filtro de escopo (as três marcas e a edição de 2026), a falha fechada (sem transação nova, com outro papel),
 * o papel que não vaza para a transação de quem chamou e o que um texto hostil na consulta (aspas, operadores do tsquery, SQL, nulo,
 * só palavras vazias) não consegue.
 */
@TesteDeIntegracao
class BuscaHibridaTest {

    @Autowired
    BuscaHibrida busca;

    @Autowired
    ProvedorDeEmbeddings embeddings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager gerenteDeTransacao;

    Escopo escopo(String cliente, int edicao) {
        return jdbc.sql("""
                        SELECT pa.evento_id, pa.cota_tipo_id, pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :cliente AND e.edicao = :edicao
                        """)
                .param("cliente", cliente).param("edicao", edicao)
                .query((rs, n) -> new Escopo(rs.getLong(1), rs.getInt(2), rs.getLong(3))).single();
    }

    Escopo amarelo() {
        return escopo("Cliente Amarelo", 2027);
    }

    Pedido hibrido(Escopo escopo, String consulta) {
        return new Pedido(escopo, consulta, null, BuscaHibrida.K_PADRAO, embeddings.paraConsulta(consulta), embeddings.modelo());
    }

    Pedido soTexto(Escopo escopo, String consulta) {
        return new Pedido(escopo, consulta, null, BuscaHibrida.K_PADRAO, null, null);
    }

    /** Todos os trechos que a busca viu, nas duas listas. */
    static List<Candidato> tudo(ResultadoDaBusca resultado) {
        List<Candidato> todos = new ArrayList<>(resultado.vetorial());
        resultado.textual().stream().filter(c -> todos.stream().noneMatch(t -> t.trechoId() == c.trechoId())).forEach(todos::add);
        return todos;
    }

    // ---------------------------------------------------------------------
    // RRF e as duas listas
    // ---------------------------------------------------------------------

    @Test
    void aPerguntaDoPlanoAchaOTrechoComAListaTextualLigadaPorOU() {
        // "Até quando preciso informar os geradores na Arena": com E entre os termos, nenhum trecho casaria (conferido em 30/10)
        ResultadoDaBusca resultado = busca.buscar(soTexto(amarelo(), "Até quando preciso informar os geradores na Arena"));

        assertThat(resultado.degradado()).isTrue();
        assertThat(resultado.textual()).isNotEmpty();
        assertThat(resultado.vetorial()).isEmpty();
        assertThat(resultado.escolhidos()).anySatisfy(c -> {
            assertThat(c.documento()).isEqualTo("Manual de Operações 2027");
            assertThat(c.versao()).isEqualTo(2);
            assertThat(c.texto()).contains("04/12/2026");
        });
    }

    @Test
    void oRrfSomaUmSobreSessentaMaisAPosicaoEmCadaLista() {
        ResultadoDaBusca resultado = busca.buscar(hibrido(amarelo(), "geradores prazo informar produção"));

        assertThat(resultado.degradado()).isFalse();
        assertThat(resultado.vetorial()).isNotEmpty();
        assertThat(resultado.textual()).isNotEmpty();
        List<Candidato> emAmbas = resultado.escolhidos().stream().filter(c -> c.posicaoVetorial() != null && c.posicaoTextual() != null).toList();
        assertThat(emAmbas).as("ao menos um trecho entrou nas duas listas").isNotEmpty();
        for (Candidato c : tudo(resultado)) {
            double esperado = (c.posicaoVetorial() == null ? 0 : 1.0 / (60 + c.posicaoVetorial()))
                    + (c.posicaoTextual() == null ? 0 : 1.0 / (60 + c.posicaoTextual()));
            assertThat(c.rrf()).as("rrf do trecho " + c.trechoId()).isCloseTo(esperado, org.assertj.core.data.Offset.offset(1e-12));
        }
        // quem está nas duas listas ganha de quem está numa só na mesma posição
        assertThat(resultado.escolhidos()).isSortedAccordingTo((a, b) -> Double.compare(b.rrf(), a.rrf()));
        assertThat(resultado.escolhidos()).hasSizeLessThanOrEqualTo(BuscaHibrida.K_PADRAO);
    }

    @Test
    void asListasTemAPosicaoDeUmEmOrdemEAteTrinta() {
        ResultadoDaBusca resultado = busca.buscar(hibrido(amarelo(), "energia tarifa kVA geradores portão credenciais prazo"));

        for (List<Candidato> lista : List.of(resultado.vetorial(), resultado.textual())) {
            assertThat(lista).hasSizeLessThanOrEqualTo(BuscaHibrida.TAMANHO_DAS_LISTAS);
        }
        assertThat(resultado.vetorial()).extracting(Candidato::posicaoVetorial).startsWith(1, 2, 3);
        assertThat(resultado.textual()).extracting(Candidato::posicaoTextual).startsWith(1, 2, 3);
        assertThat(resultado.vetorial()).extracting(Candidato::distancia).isSorted();
        assertThat(resultado.textual()).extracting(Candidato::nota).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void kForaDaFaixaEAjustadoEOTipoFiltra() {
        Escopo e = amarelo();
        assertThat(busca.buscar(new Pedido(e, "evento festival", null, 0, null, null)).k()).isEqualTo(1);
        assertThat(busca.buscar(new Pedido(e, "evento festival", null, 999, null, null)).k()).isEqualTo(30);

        ResultadoDaBusca soFaq = busca.buscar(new Pedido(e, "reunião alinhamento cortesia menores animais", "FAQ", 6, null, null));
        assertThat(soFaq.escolhidos()).isNotEmpty().allSatisfy(c -> assertThat(c.tipo()).isEqualTo("FAQ"));
        ResultadoDaBusca soPlano = busca.buscar(new Pedido(e, "cortesias credenciais conteúdo prazo", "PLANO_COMERCIAL", 6, null, null));
        assertThat(soPlano.escolhidos()).isNotEmpty().allSatisfy(c -> assertThat(c.tipo()).isEqualTo("PLANO_COMERCIAL"));
    }

    // ---------------------------------------------------------------------
    // Falha fechada
    // ---------------------------------------------------------------------

    @Test
    void semTransacaoNovaNaoDevolveNada() {
        Pedido pedido = soTexto(amarelo(), "geradores");

        // chamado como se a transação não fosse nova: recusa antes de qualquer consulta
        assertThatThrownBy(() -> busca.buscarNaTransacao(false, pedido)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transação nova").hasMessageContaining("Nenhuma busca foi feita");
        // e fora de qualquer transação: o SET LOCAL ROLE só avisaria e a consulta rodaria como portal, sem RLS
        assertThatThrownBy(() -> busca.buscarNaTransacao(true, pedido)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nenhuma busca foi feita");
    }

    @Test
    void numaTransacaoQueNaoESoDeLeituraTambemRecusa() {
        Pedido pedido = soTexto(amarelo(), "geradores");

        assertThatThrownBy(() -> new TransactionTemplate(gerenteDeTransacao).execute(estado -> busca.buscarNaTransacao(true, pedido)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("só de leitura");
    }

    @Test
    void comOutroPapelNaoBuscaNada() {
        BuscaHibrida comoPortal = new BuscaHibrida(jdbc, gerenteDeTransacao, "portal");

        assertThatThrownBy(() -> comoPortal.buscar(soTexto(amarelo(), "geradores"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tem de rodar como atlas_ro").hasMessageContaining("está rodando como portal")
                .hasMessageContaining("nenhuma busca foi feita");
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal; DROP TABLE documento", "atlas_ro --", "ATLAS_RO", "atlas ro", "", "1papel"})
    void nomeDePapelQueNaoEUmIdentificadorNaoChegaAoBanco(String papel) {
        BuscaHibrida suspeita = new BuscaHibrida(jdbc, gerenteDeTransacao, papel);

        assertThatThrownBy(() -> suspeita.buscar(soTexto(amarelo(), "geradores"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM documento").query(Long.class).single()).isGreaterThan(0);
    }

    @Test
    void aTransacaoDeQuemChamouContinuaComOPapelDeleDepoisDaBusca() {
        Pedido pedido = hibrido(amarelo(), "geradores prazo");
        TransactionTemplate chamador = new TransactionTemplate(gerenteDeTransacao);

        List<Object> vistoPeloChamador = chamador.execute(estado -> {
            String antes = jdbc.sql("SELECT current_user").query(String.class).single();
            ResultadoDaBusca resultado = busca.buscar(pedido);
            String depois = jdbc.sql("SELECT current_user").query(String.class).single();
            // a transação do chamador ainda lê tabelas do portal (o atlas_ro não leria) e ainda grava
            long parcerias = jdbc.sql("SELECT count(*) FROM parceria").query(Long.class).single();
            String contexto = jdbc.sql("SELECT current_setting('app.parceria_id', true)").query(String.class).single();
            estado.setRollbackOnly();
            return List.of(antes, depois, parcerias, resultado.escolhidos().size(), contexto == null ? "" : contexto);
        });

        assertThat(vistoPeloChamador.get(0)).isEqualTo(vistoPeloChamador.get(1)).isNotEqualTo("atlas_ro");
        assertThat((long) vistoPeloChamador.get(2)).isEqualTo(4);
        assertThat((int) vistoPeloChamador.get(3)).isGreaterThan(0);
        assertThat(vistoPeloChamador.get(4)).as("o contexto da busca não vaza para a transação de quem chamou").isEqualTo("");
    }

    @Test
    void depoisDaBuscaUmaConexaoNovaNaoTemContextoNemPapel() {
        busca.buscar(hibrido(amarelo(), "geradores"));

        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("portal");
        assertThat(jdbc.sql("SELECT coalesce(current_setting('app.parceria_id', true), '')").query(String.class).single()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // O que cada marca enxerga
    // ---------------------------------------------------------------------

    private static final List<String> CONSULTAS_DE_CANARIO = List.of(
            "Girassol Elétrico", "girassol eletrico", "qual é o tema do espaço do Cliente Amarelo?", "tema do espaço da ativação",
            "Tempestade Violeta", "tempestade violeta", "qual é o tema da ativação do Cliente Roxo?", "tema do espaço",
            "horário do espaço", "equipe entrada espaço", "montagem do espaço", "área do espaço metros quadrados",
            "energia total do espaço kVA", "anexo da parceria", "tema Girassol", "tema Tempestade");

    @Test
    void osCanariosNuncaSaemParaAPersonaErrada() {
        List<String> falhas = new ArrayList<>();
        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo", "Cliente Verde")) {
            Escopo e = escopo(cliente, 2027);
            for (String consulta : CONSULTAS_DE_CANARIO) {
                for (Pedido pedido : List.of(hibrido(e, consulta), soTexto(e, consulta))) {
                    for (Candidato c : tudo(busca.buscar(pedido))) {
                        String texto = (c.documento() + " " + c.texto()).toLowerCase();
                        if (texto.contains("girassol") && !cliente.equals("Cliente Amarelo")) {
                            falhas.add(cliente + " viu o canário do Amarelo com a consulta \"" + consulta + "\"");
                        }
                        if (texto.contains("tempestade violeta") && !cliente.equals("Cliente Roxo")) {
                            falhas.add(cliente + " viu o canário do Roxo com a consulta \"" + consulta + "\"");
                        }
                    }
                }
            }
        }
        assertThat(falhas).isEmpty();
        // e a dona vê o seu: sem isso o teste passaria com uma busca que não acha nada
        assertThat(tudo(busca.buscar(hibrido(amarelo(), "Girassol Elétrico")))).anySatisfy(c -> assertThat(c.texto()).contains("Girassol"));
        assertThat(tudo(busca.buscar(hibrido(escopo("Cliente Roxo", 2027), "Tempestade Violeta"))))
                .anySatisfy(c -> assertThat(c.texto()).contains("Tempestade Violeta"));
    }

    @Test
    void oPlanoDeOutraCotaNuncaSai() {
        // o plano da Master tem as cortesias do Camarote e do Lounge; os de Subcota G e Subcota têm números diferentes
        for (String cliente : List.of("Cliente Roxo", "Cliente Verde")) {
            for (Candidato c : tudo(busca.buscar(hibrido(escopo(cliente, 2027), "cortesias credenciais prazo de conteúdo plano comercial")))) {
                assertThat(c.documento()).as(cliente).isNotEqualTo("Plano comercial · Cota Master 2027");
            }
        }
        assertThat(tudo(busca.buscar(hibrido(amarelo(), "cortesias credenciais prazo de conteúdo plano comercial"))))
                .anySatisfy(c -> assertThat(c.documento()).isEqualTo("Plano comercial · Cota Master 2027"));
        assertThat(tudo(busca.buscar(hibrido(escopo("Cliente Roxo", 2027), "cortesias credenciais prazo de conteúdo plano comercial"))))
                .anySatisfy(c -> assertThat(c.documento()).isEqualTo("Plano comercial · Subcota G 2027"));
    }

    @Test
    void oManualDe2026NuncaSaiParaUmaParceriaDe2027ENemODe2027ParaA2026() {
        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo", "Cliente Verde")) {
            for (String consulta : List.of("tarifa de energia por kVA", "R$ 280 por kVA", "manual de operações 2026", "energia elétrica")) {
                for (Candidato c : tudo(busca.buscar(hibrido(escopo(cliente, 2027), consulta)))) {
                    assertThat(c.documento()).as(cliente + " / " + consulta).isNotEqualTo("Manual de Operações 2026");
                }
            }
        }
        // a edição de 2026 existe na amostra (o Amarelo, sem usuários): ela só vê o que é de 2026
        Escopo de2026 = escopo("Cliente Amarelo", 2026);
        List<Candidato> vistos = tudo(busca.buscar(hibrido(de2026, "tarifa de energia por kVA")));
        assertThat(vistos).isNotEmpty().allSatisfy(c -> assertThat(c.documento()).isEqualTo("Manual de Operações 2026"));
        assertThat(vistos).anySatisfy(c -> assertThat(c.texto()).contains("280"));
    }

    @Test
    void aVersaoSubstituidaOARevogadoOQueEstaEmErroEOQueAguardaNuncaSaem() {
        List<Candidato> vistos = new ArrayList<>();
        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo", "Cliente Verde")) {
            for (String consulta : List.of("geradores prazo informar", "horário de abastecimento", "circular pulseiras prata revogada",
                    "lista de credenciados CPF", "aviso digitalizado ambulatório recarga celular", "pontos de apoio")) {
                vistos.addAll(tudo(busca.buscar(hibrido(escopo(cliente, 2027), consulta))));
            }
        }
        assertThat(vistos).isNotEmpty();
        assertThat(vistos).noneSatisfy(c -> {
            assertThat(c.documento()).isEqualTo("Manual de Operações 2027");
            assertThat(c.versao()).isEqualTo(1);   // a v1 foi substituída pela v2
        });
        assertThat(vistos).extracting(Candidato::documento).doesNotContain("Lista de credenciados · Cliente Verde",
                "Circular 07/2026 · Pulseiras prata", "Aviso digitalizado · pontos de apoio");
        // a v2 está lá, com o prazo certo
        assertThat(vistos).anySatisfy(c -> assertThat(c.texto()).contains("04/12/2026"));
        assertThat(vistos).noneSatisfy(c -> assertThat(c.texto()).contains("27/11/2026"));
    }

    @Test
    void trechoDeDocumentoIndexadoComOutroModeloNaoEntraNaListaVetorialMasAindaEntraNaTextual() {
        Pedido pedido = new Pedido(amarelo(), "geradores prazo informar produção", null, 6,
                embeddings.paraConsulta("geradores prazo informar produção"), "gemini-embedding-2");

        ResultadoDaBusca resultado = busca.buscar(pedido);

        assertThat(resultado.vetorial()).as("os vetores do kit são de outro modelo").isEmpty();
        assertThat(resultado.trechosDeOutroModelo()).isGreaterThan(0);
        assertThat(resultado.textual()).isNotEmpty();
    }

    // ---------------------------------------------------------------------
    // Consulta que tenta mais do que consultar
    // ---------------------------------------------------------------------

    static Stream<String> consultasHostis() {
        return Stream.of(
                "'; DROP TABLE documento; --", "\" OR 1=1 --", "' OR '1'='1", "a & b | !c <-> d:*", "!!!", "((((", "))))", ":*:*:*", "'''", "\\",
                "\\' OR 1=1", "{{dado:ativacao.visitantes_total}}", "%", "_", "geradores' & 'prazo", "'geradores' | 'prazo'",
                "set_config('app.parceria_id', '1', true)", "SET ROLE portal; SELECT * FROM parceria", "\"geradores prazo\"", "-geradores",
                "geradores OR prazo", "<script>alert(1)</script>", "😀🤖 geradores 🔥", "ｇｅｒａｄｏｒｅｓ", "a".repeat(300),
                "palavra ".repeat(40), "123456789012345678901234567890", "http://x.com/?a=1&b=2", "email@exemplo.invalid",
                "\t\n\r geradores \t\n");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("consultasHostis")
    void consultaComCaracteresEspeciaisNaoQuebraNemAbreOCaminho(String consulta) {
        Escopo e = escopo("Cliente Roxo", 2027);

        for (Pedido pedido : List.of(hibrido(e, consulta), soTexto(e, consulta))) {
            ResultadoDaBusca resultado = busca.buscar(pedido);
            // o que voltar é só o que o Roxo enxerga
            assertThat(tudo(resultado)).noneSatisfy(c -> assertThat((c.documento() + c.texto()).toLowerCase()).contains("girassol"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM documento").query(Long.class).single()).isGreaterThanOrEqualTo(13);
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("portal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"de a o que um para com", "?", "...", "   ", "e ou mas", "de da do", "@@@ ### $$$", "--", "ela"})
    void consultaSemNenhumTermoUtilNaoBuscaNada(String consulta) {
        ResultadoDaBusca resultado = busca.buscar(hibrido(amarelo(), consulta));

        assertThat(resultado.semTermoUtil()).as(consulta).isTrue();
        assertThat(resultado.vetorial()).isEmpty();
        assertThat(resultado.textual()).isEmpty();
        assertThat(resultado.escolhidos()).isEmpty();
    }

    @Test
    void oCaractereNuloNaConsultaNaoDa500EValeComoSeNaoEstivesse() {
        ResultadoDaBusca comNulo = busca.buscar(soTexto(amarelo(), "gera\u0000dores pra\u0000zo informar"));
        ResultadoDaBusca semNulo = busca.buscar(soTexto(amarelo(), "geradores prazo informar"));

        assertThat(comNulo.consulta()).doesNotContain("\u0000");
        assertThat(comNulo.escolhidos()).extracting(Candidato::trechoId).containsExactlyElementsOf(semNulo.escolhidos().stream().map(Candidato::trechoId).toList());
        assertThat(busca.buscar(soTexto(amarelo(), "\u0000")).semTermoUtil()).isTrue();
    }

    @Test
    void consultaDeUmaSoPalavraDoAssuntoAchaOTrecho() {
        ResultadoDaBusca resultado = busca.buscar(hibrido(amarelo(), "shuttle"));

        assertThat(resultado.escolhidos()).anySatisfy(c -> assertThat(c.texto().toLowerCase()).contains("shuttle"));
    }
}
