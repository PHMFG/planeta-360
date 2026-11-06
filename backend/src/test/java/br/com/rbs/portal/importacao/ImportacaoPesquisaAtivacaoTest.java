package br.com.rbs.portal.importacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.RoteiroDeLlmConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Os destinos novos da importação (DADOS_ATIVACAO e PESQUISA) e a rota 117, contra o banco de teste: a planilha da agência
 * vira dado informado com autor e histórico, a pesquisa grava resultado, perfil e texto livre, e a marca importa e confirma
 * só a própria parceria.
 */
@TesteDeIntegracao
@Import(RoteiroDeLlmConfig.class)
class ImportacaoPesquisaAtivacaoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String CORPUS = "importacao/corpus/";
    private static final String PERGUNTA_TEXTO = "Pergunta de texto da trilha T4b";
    private static final String PERGUNTA_FECHADA = "Pergunta fechada da trilha T4b";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private long dadoInformadoMax;
    private long historicoMax;

    @BeforeEach
    void antes() {
        dadoInformadoMax = jdbc.sql("SELECT coalesce(max(id), 0) FROM dado_informado").query(Long.class).single();
        historicoMax = jdbc.sql("SELECT coalesce(max(id), 0) FROM historico").query(Long.class).single();
    }

    @AfterEach
    void limpar() {
        jdbc.sql("DELETE FROM pesquisa_resposta_texto WHERE importacao_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM pesquisa_perfil WHERE importacao_id IS NOT NULL").update();
        jdbc.sql("""
                        DELETE FROM pesquisa_resultado WHERE carga_id IN
                            (SELECT id FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO'))
                        """).update();
        jdbc.sql("DELETE FROM importacao").update();
        jdbc.sql("""
                        DELETE FROM stg_pesquisa WHERE carga_id IN
                            (SELECT id FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO'))
                        """).update();
        jdbc.sql("""
                        DELETE FROM stg_veiculacao WHERE carga_id IN
                            (SELECT id FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO'))
                        """).update();
        jdbc.sql("DELETE FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO')").update();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN "
                + "(SELECT id FROM documento WHERE chave LIKE 'importacao-%')").update();
        jdbc.sql("DELETE FROM documento WHERE chave LIKE 'importacao-%'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'IMPORTACAO'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'DOCUMENTO' AND nome_original IN "
                + "('agencia-amarelo.xlsx', 'pesquisa-ativacao.xlsx', 'planilha-t4b.csv')").update();
        jdbc.sql("DELETE FROM pesquisa_pergunta WHERE texto IN (:texto, :fechada)")
                .param("texto", PERGUNTA_TEXTO).param("fechada", PERGUNTA_FECHADA).update();
        jdbc.sql("DELETE FROM pesquisa WHERE nome = :nome").param("nome", "Pesquisa geral da trilha T4b").update();
        // O dado informado é imutável (o gatilho recusa DELETE); o teste desliga o gatilho só para apagar o que criou.
        jdbc.sql("ALTER TABLE dado_informado DISABLE TRIGGER rn_dado_informado_imutavel").update();
        try {
            jdbc.sql("DELETE FROM dado_informado WHERE id > :max").param("max", dadoInformadoMax).update();
        } finally {
            jdbc.sql("ALTER TABLE dado_informado ENABLE TRIGGER rn_dado_informado_imutavel").update();
        }
        // O histórico que a confirmação gravou (CRIACAO de cada dado informado) também sai: os testes dos dados informados
        // contam as linhas de histórico da parceria.
        jdbc.sql("ALTER TABLE historico DISABLE TRIGGER rn_historico_imutavel").update();
        try {
            jdbc.sql("DELETE FROM historico WHERE id > :max").param("max", historicoMax).update();
        } finally {
            jdbc.sql("ALTER TABLE historico ENABLE TRIGGER rn_historico_imutavel").update();
        }
    }

    private long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    private long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    private static byte[] corpus(String nome) throws IOException {
        try (InputStream entrada = new ClassPathResource(CORPUS + nome).getInputStream()) {
            return entrada.readAllBytes();
        }
    }

    private static byte[] csv(String... linhas) {
        return String.join("\n", linhas).getBytes(StandardCharsets.UTF_8);
    }

    private MvcTestResult enviarRbs(SessaoDeApi sessao, String nome, byte[] bytes, String alcance, Long parceriaId,
                                    String destino) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes))
                .param("eventoId", String.valueOf(evento2027())).param("alcance", alcance)
                .session(sessao.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token());
        if (parceriaId != null) {
            pedido.param("parceriaId", String.valueOf(parceriaId));
        }
        if (destino != null) {
            pedido.param("destino", destino);
        }
        return pedido.exchange();
    }

    private MvcTestResult enviarDaMarca(SessaoDeApi sessao, long parceriaId, String nome, byte[] bytes) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        return mvc.post().uri("/api/parcerias/" + parceriaId + "/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes))
                .session(sessao.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
    }

    private static long id(MvcTestResult resposta) {
        return ((Number) lerJson(resposta, "$.id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itens(SessaoDeApi sessao, long id) {
        return (List<Map<String, Object>>) (List<?>) lerLista(sessao.get("/api/importacoes/" + id), "$.itens");
    }

    private void aceitarTodas(SessaoDeApi sessao, long id) {
        assertThat(sessao.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}"))
                .hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // DADOS_ATIVACAO
    // ---------------------------------------------------------------------

    @Test
    void oDestinoDadosDaAtivacaoGravaComAutorEHistorico() throws IOException {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parceria = amarelo();
        MvcTestResult criada = enviarRbs(rbs, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx"), "PARCERIA", parceria,
                "DADOS_ATIVACAO");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);

        long id = id(criada);
        List<Map<String, Object>> itens = itens(rbs, id);
        assertThat(itens).as("os quatro campos do resumo da agência").hasSize(4);
        assertThat(itens).allSatisfy(item -> assertThat(item.get("situacao")).isEqualTo("PROPOSTA"));
        assertThat(itens.stream().map(item -> String.valueOf(((Map<?, ?>) item.get("dados")).get("campo_chave"))))
                .containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos",
                        "ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos");

        aceitarTodas(rbs, id);
        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");
        assertThat(lerJson(confirmada, "$.resultado.inseridas")).isEqualTo(4);

        MvcTestResult dados = rbs.get("/api/parcerias/" + parceria + "/dados?aba=APLICACOES");
        assertThat(dados).hasStatus(HttpStatus.OK);
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.valor"))
                .containsExactly(8600);
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.origem"))
                .containsExactly("ATLAS");
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.informadoPor.nome"))
                .containsExactly("Equipe B2B RBS");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM historico
                        WHERE entidade = 'dado_informado' AND acao = 'CRIACAO' AND parceria_id = :parceria
                          AND detalhe LIKE '%(Atlas)%'
                        """).param("parceria", parceria).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void oDestinoDadosDaAtivacaoRecusaCampoDesconhecidoArquivoEValorNaoNumerico() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] planilha = csv("campo,valor",
                "ativacao.inexistente,10",
                "ativacao.planilha_agencia,10",
                "ativacao.visitantes_total,abc");
        MvcTestResult criada = enviarRbs(rbs, "planilha-t4b.csv", planilha, "PARCERIA", amarelo(), "DADOS_ATIVACAO");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);

        List<Map<String, Object>> itens = itens(rbs, id(criada));
        assertThat(itens).hasSize(3);
        assertThat(itens).allSatisfy(item -> assertThat(item.get("situacao")).isEqualTo("RECUSADA"));
        assertThat(String.valueOf(itens.get(0).get("motivo"))).contains("catálogo");
        assertThat(String.valueOf(itens.get(1).get("motivo"))).contains("arquivo");
        assertThat(String.valueOf(itens.get(2).get("motivo"))).contains("número");
    }

    @Test
    void aPlanilhaDaAgenciaSoValeNumaImportacaoDeParceria() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviarRbs(rbs, "planilha-t4b.csv", csv("campo,valor", "ativacao.visitantes_total,10"),
                "EDICAO", null, "DADOS_ATIVACAO");
        assertThat(criada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(criada, "$.campos.destino")).isEqualTo("Os dados da ativação são de uma importação de parceria.");
    }

    // ---------------------------------------------------------------------
    // Rota 117
    // ---------------------------------------------------------------------

    @Test
    void aMarcaImportaAplanilhaDaAgenciaEConfirma() throws IOException {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = amarelo();
        MvcTestResult criada = enviarDaMarca(amarelo, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx"));
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        assertThat(lerJson(criada, "$.situacao")).isEqualTo("LENDO");
        assertThat(lerJson(criada, "$.origem")).isEqualTo("MARCA");
        assertThat(lerJson(criada, "$.alcance")).isEqualTo("PARCERIA");
        assertThat(lerJson(criada, "$.destino")).isEqualTo("DADOS_ATIVACAO");
        assertThat(((Number) lerJson(criada, "$.parceria.id")).longValue()).isEqualTo(parceria);

        long id = id(criada);
        MvcTestResult detalhe = amarelo.get("/api/importacoes/" + id);
        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(itens(amarelo, id)).hasSize(4);

        aceitarTodas(amarelo, id);
        MvcTestResult confirmada = amarelo.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");

        MvcTestResult dados = amarelo.get("/api/parcerias/" + parceria + "/dados?aba=APLICACOES");
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.valor"))
                .containsExactly(8600);
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.origem"))
                .containsExactly("ATLAS");
        assertThat(lerLista(dados, "$.campos[?(@.chave == 'ativacao.visitantes_total')].vigente.informadoPor.nome"))
                .containsExactly("Marketing Amarelo");
    }

    @Test
    void aRota117RespeitaAsPermissoes() throws IOException {
        long parceria = amarelo();
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        assertThat(enviarDaMarca(roxo, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx")))
                .as("outra parceria").hasStatus(HttpStatus.NOT_FOUND);

        boolean[] estado = estadoDaAba(parceria, "APLICACOES");
        try {
            fecharAba(parceria, "APLICACOES");
            SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
            assertThat(enviarDaMarca(amarelo, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx")))
                    .as("aba fechada").hasStatus(HttpStatus.FORBIDDEN);
        } finally {
            restaurarAba(parceria, "APLICACOES", estado);
        }

        String situacao = jdbc.sql("SELECT situacao FROM parceria WHERE id = :id").param("id", parceria)
                .query(String.class).single();
        try {
            jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :id").param("id", parceria).update();
            SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
            assertThat(enviarDaMarca(amarelo, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx")))
                    .as("parceria encerrada").hasStatus(HttpStatus.CONFLICT);
        } finally {
            jdbc.sql("UPDATE parceria SET situacao = :situacao WHERE id = :id")
                    .param("situacao", situacao).param("id", parceria).update();
        }

        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        assertThat(mvc.post().uri("/api/parcerias/" + parceria + "/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", "agencia-amarelo.xlsx", "application/octet-stream",
                        corpus("agencia-amarelo.xlsx"))).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange())
                .as("sem sessão").hasStatus(HttpStatus.UNAUTHORIZED);

        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        assertThat(enviarDaMarca(rbs, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx")))
                .as("a RBS também pode chamar").hasStatus(HttpStatus.ACCEPTED);
    }

    @Test
    void aMarcaNaoConfirmaImportacaoDeOutroDestino() throws IOException {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = amarelo();
        long id = id(enviarDaMarca(amarelo, parceria, "agencia-amarelo.xlsx", corpus("agencia-amarelo.xlsx")));
        jdbc.sql("UPDATE importacao SET destino = 'VEICULACAO' WHERE id = :id").param("id", id).update();
        MvcTestResult confirmada = amarelo.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(lerJson(confirmada, "$.detail"))).contains("dados da ativação");
    }

    // ---------------------------------------------------------------------
    // PESQUISA
    // ---------------------------------------------------------------------

    private void prepararPerguntas() {
        long pesquisa = jdbc.sql("SELECT id FROM pesquisa WHERE evento_id = :evento AND escopo = 'ATIVACAO'")
                .param("evento", evento2027()).query(Long.class).single();
        long usuario = jdbc.sql("SELECT id FROM usuario WHERE lower(email) = lower(:email)").param("email", AMARELO)
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pesquisa (evento_id, nome, escopo, base_respondentes)
                        VALUES (:evento, :nome, 'GERAL', 1038)
                        ON CONFLICT (evento_id, nome) DO NOTHING
                        """)
                .param("evento", evento2027()).param("nome", "Pesquisa geral da trilha T4b").update();
        jdbc.sql("""
                        INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, tipo_resposta,
                                                       alternativas, enviada_por, enviada_em)
                        VALUES (:parceria, :pesquisa, 4, :texto, 'APROVADA', 'TEXTO', NULL, :usuario, now())
                        """)
                .param("parceria", amarelo()).param("pesquisa", pesquisa).param("texto", PERGUNTA_TEXTO)
                .param("usuario", usuario).update();
        jdbc.sql("""
                        INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, tipo_resposta,
                                                       alternativas, enviada_por, enviada_em)
                        VALUES (:parceria, :pesquisa, 5, :texto, 'APROVADA', 'ESCOLHA', '["Sim","Não"]'::jsonb, :usuario, now())
                        """)
                .param("parceria", amarelo()).param("pesquisa", pesquisa).param("texto", PERGUNTA_FECHADA)
                .param("usuario", usuario).update();
    }

    private static final String CABECALHO_PESQUISA =
            "tipo,patrocinador,pergunta,alternativa,dimensao,categoria,valor,base_respondentes,unidade";

    @Test
    void oDestinoPesquisaGravaResultadoPerfilETexto() {
        prepararPerguntas();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] planilha = csv(CABECALHO_PESQUISA,
                "PERFIL,Cliente Amarelo,,,GENERO,Feminino,58,412,",
                "PERFIL,,,,GENERO,Feminino,62,1038,",
                "RESULTADO,Cliente Amarelo,5,Sim,,,10,412,PERCENTUAL",
                "TEXTO,Cliente Amarelo,4,Gostei da ativação,,,3,412,",
                "TEXTO,Cliente Amarelo,4,Ligue para 51 99999-8888,,,1,412,");
        MvcTestResult criada = enviarRbs(rbs, "pesquisa-t4b.csv", planilha, "EDICAO", null, "PESQUISA");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);

        long id = id(criada);
        List<Map<String, Object>> itens = itens(rbs, id);
        assertThat(itens).as("as cinco linhas de pesquisa").hasSize(5);
        assertThat(itens).allSatisfy(item -> assertThat(item.get("situacao")).isEqualTo("PROPOSTA"));

        aceitarTodas(rbs, id);
        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_resultado r
                        JOIN pesquisa_pergunta q ON q.id = r.pergunta_id
                        JOIN carga c ON c.id = r.carga_id
                        JOIN importacao i ON i.carga_id = c.id
                        WHERE i.id = :id AND q.ordem = 5 AND r.alternativa = 'Sim' AND r.valor = 10
                        """).param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_perfil WHERE importacao_id = :id
                          AND dimensao = 'GENERO' AND categoria = 'Feminino'
                        """).param("id", id).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_perfil WHERE importacao_id = :id AND parceria_id = :parceria
                          AND valor = 58 AND base_respondentes = 412
                        """).param("id", id).param("parceria", amarelo()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_perfil WHERE importacao_id = :id AND parceria_id IS NULL
                          AND valor = 62 AND base_respondentes = 1038
                        """).param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_resposta_texto WHERE importacao_id = :id
                          AND texto = 'Gostei da ativação' AND contagem = 3 AND NOT suspeita_dado_pessoal
                        """).param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pesquisa_resposta_texto WHERE importacao_id = :id
                          AND texto = 'Ligue para 51 99999-8888' AND suspeita_dado_pessoal
                        """).param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aPerguntaInexistenteEAAlternativaForaDaListaSaoRecusadas() {
        prepararPerguntas();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] planilha = csv(CABECALHO_PESQUISA,
                "RESULTADO,Cliente Amarelo,5,Talvez,,,10,412,PERCENTUAL",
                "RESULTADO,Cliente Amarelo,99,Sim,,,10,412,PERCENTUAL");
        long id = id(enviarRbs(rbs, "pesquisa-t4b.csv", planilha, "EDICAO", null, "PESQUISA"));

        List<Map<String, Object>> itens = itens(rbs, id);
        assertThat(itens).hasSize(2);
        assertThat(itens).allSatisfy(item -> assertThat(item.get("situacao")).isEqualTo("RECUSADA"));
        assertThat(String.valueOf(itens.get(0).get("motivo"))).contains("lista da pergunta");
        assertThat(String.valueOf(itens.get(1).get("motivo"))).contains("não está cadastrada");
    }

    @Test
    void oPerfilSemMarcaSemAPesquisaGeralDaEdicaoVemRecusadoNaPrevia() {
        // A amostra só tem a pesquisa geral de 2026: em 2027 a linha do festival inteiro não tem onde entrar, e a prévia já diz
        // isso, em vez de a confirmação inteira dar 422 (achado da rodada H1).
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] planilha = csv(CABECALHO_PESQUISA,
                "PERFIL,,,,GENERO,Feminino,62,1038,PERCENTUAL",
                "PERFIL,Cliente Amarelo,,,GENERO,Feminino,56,412,PERCENTUAL");
        long id = id(enviarRbs(rbs, "pesquisa-t4b.csv", planilha, "EDICAO", null, "PESQUISA"));

        List<Map<String, Object>> itens = itens(rbs, id);
        assertThat(itens).hasSize(2);
        assertThat(itens.get(0).get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.get(0).get("motivo"))).contains("pesquisa geral do festival");
        assertThat(itens.get(1).get("situacao")).isNotEqualTo("RECUSADA");
    }

    @Test
    void semPesquisaAbertaAConfirmacaoDa422() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long pesquisa = jdbc.sql("SELECT id FROM pesquisa WHERE evento_id = :evento AND escopo = 'ATIVACAO'")
                .param("evento", evento2027()).query(Long.class).single();
        byte[] planilha = csv(CABECALHO_PESQUISA, "RESULTADO,Cliente Amarelo,1,Sim,,,64,412,PERCENTUAL");
        long id = id(enviarRbs(rbs, "pesquisa-t4b.csv", planilha, "EDICAO", null, "PESQUISA"));
        aceitarTodas(rbs, id);
        try {
            jdbc.sql("UPDATE pesquisa SET escopo = 'GERAL' WHERE id = :id").param("id", pesquisa).update();
            MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
            assertThat(confirmada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(String.valueOf(lerJson(confirmada, "$.campos.pesquisa")))
                    .contains("ainda não tem a pesquisa de ativação aberta");
        } finally {
            jdbc.sql("UPDATE pesquisa SET escopo = 'ATIVACAO' WHERE id = :id").param("id", pesquisa).update();
        }
    }

    @Test
    void oCabecalhoDaPesquisaSugereODestino() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] planilha = csv(CABECALHO_PESQUISA, "PERFIL,Cliente Amarelo,,,GENERO,Feminino,58,412,");
        MvcTestResult criada = enviarRbs(rbs, "pesquisa-t4b.csv", planilha, "EDICAO", null, null);
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id(criada));
        assertThat(lerJson(detalhe, "$.destino")).isEqualTo("PESQUISA");
        assertThat(lerJson(detalhe, "$.destinoSugerido")).isEqualTo("PESQUISA");
        assertThat(lerLista(detalhe, "$.itens")).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Ajuda das abas
    // ---------------------------------------------------------------------

    private boolean[] estadoDaAba(long parceriaId, String codigo) {
        Map<String, Object> linha = jdbc.sql("""
                        SELECT pab.liberada, pab.visivel FROM parceria_aba pab
                        JOIN aba a ON a.id = pab.aba_id
                        WHERE pab.parceria_id = :parceria AND a.codigo = :codigo
                        """).param("parceria", parceriaId).param("codigo", codigo)
                .query().singleRow();
        return new boolean[] {Boolean.TRUE.equals(linha.get("liberada")), Boolean.TRUE.equals(linha.get("visivel"))};
    }

    private void fecharAba(long parceriaId, String codigo) {
        jdbc.sql("""
                        UPDATE parceria_aba SET liberada = FALSE, visivel = TRUE
                        WHERE parceria_id = :parceria AND aba_id = (SELECT id FROM aba WHERE codigo = :codigo)
                        """).param("parceria", parceriaId).param("codigo", codigo).update();
    }

    private void restaurarAba(long parceriaId, String codigo, boolean[] estado) {
        jdbc.sql("""
                        UPDATE parceria_aba SET liberada = :liberada, visivel = :visivel
                        WHERE parceria_id = :parceria AND aba_id = (SELECT id FROM aba WHERE codigo = :codigo)
                        """).param("liberada", estado[0]).param("visivel", estado[1])
                .param("parceria", parceriaId).param("codigo", codigo).update();
    }
}
