package br.com.rbs.portal.pesquisa;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa a rota 119 (o perfil da pesquisa) como o front a usa: login pela rota, sessão de verdade. [PERMISSAO] A marca
 * precisa ser Master, da parceria e com a aba PESQUISA aberta; a RBS vê qualquer parceria. [PESQUISA] O perfil da marca
 * vem da pesquisa da ativação, o comparativo da pesquisa GERAL da edição, e a resposta de texto com suspeita não sai para a
 * marca. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class PesquisaPerfilTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    long parceria(String patrocinador) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :nome AND e.edicao = 2027
                        """).param("nome", patrocinador).query(Long.class).single();
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    long usuario(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", email).query(Long.class).single();
    }

    long pesquisaDaAtivacao(long parceriaId) {
        return jdbc.sql("""
                        SELECT p.id FROM pesquisa p JOIN parceria pa ON pa.evento_id = p.evento_id
                        WHERE pa.id = :p AND p.escopo = 'ATIVACAO'
                        """).param("p", parceriaId).query(Long.class).single();
    }

    MvcTestResult perfil(SessaoDeApi sessao, long parceriaId) {
        return sessao.get("/api/parcerias/" + parceriaId + "/pesquisa/perfil");
    }

    void semearPerfil() {
        long amarelo = parceria("Cliente Amarelo");
        long ativacao = pesquisaDaAtivacao(amarelo);
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes)
                        VALUES (:pesquisa, :parceria, 'GENERO', 'Feminino', 56.0, 412),
                               (:pesquisa, :parceria, 'GENERO', 'Masculino', 44.0, 412)
                        """).param("pesquisa", ativacao).param("parceria", amarelo).update();
        long geral = jdbc.sql("""
                        INSERT INTO pesquisa (evento_id, nome, escopo, base_respondentes)
                        VALUES (:evento, 'Pesquisa geral do festival 2027 (teste)', 'GERAL', 1038) RETURNING id
                        """).param("evento", evento(2027)).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes)
                        VALUES (:pesquisa, NULL, 'GENERO', 'Feminino', 62.0, 1038)
                        """).param("pesquisa", geral).update();
    }

    @Test
    void amareloMasterVeOPerfilOComparativoDaGeralEAsRespostasSemSuspeita() {
        long amarelo = parceria("Cliente Amarelo");
        semearPerfil();
        long pergunta = jdbc.sql("SELECT id FROM pesquisa_pergunta WHERE parceria_id = :p AND ordem = 1")
                .param("p", amarelo).query(Long.class).single();
        long pesquisa = pesquisaDaAtivacao(amarelo);
        jdbc.sql("""
                        INSERT INTO pesquisa_resposta_texto (pesquisa_id, parceria_id, pergunta_id, texto, contagem,
                                                             suspeita_dado_pessoal)
                        VALUES (:pesquisa, :parceria, :pergunta, 'Gostei do espaço', 3, FALSE),
                               (:pesquisa, :parceria, :pergunta, 'Meu telefone é (51) 99999-8888', 1, TRUE)
                        """).param("pesquisa", pesquisa).param("parceria", amarelo).param("pergunta", pergunta).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(((Number) lerJson(resposta, "$.pesquisa.id")).longValue()).isEqualTo(pesquisa);
        assertThat(lerJson(resposta, "$.pesquisa.nome")).isEqualTo("Pesquisa nos espaços de ativação 2027");
        assertThat(lerJson(resposta, "$.pesquisa.baseRespondentes")).isEqualTo(412);
        assertThat(lerLista(resposta, "$.perfil[*].dimensao")).containsExactly("GENERO", "GENERO");
        assertThat(lerLista(resposta, "$.perfil[*].categoria")).containsExactly("Feminino", "Masculino");
        assertThat(lerLista(resposta, "$.perfil[*].valor").stream().map(v -> ((Number) v).doubleValue()).toList())
                .containsExactly(56.0, 44.0);
        assertThat(lerJson(resposta, "$.perfil[0].baseRespondentes")).isEqualTo(412);
        assertThat(lerLista(resposta, "$.geral[*].categoria")).containsExactly("Feminino");
        assertThat(lerJson(resposta, "$.geral[0].baseRespondentes")).isEqualTo(1038);
        assertThat(lerLista(resposta, "$.respostasTexto[*].texto")).containsExactly("Gostei do espaço");
        assertThat(((Number) lerJson(resposta, "$.respostasTexto[0].perguntaId")).longValue()).isEqualTo(pergunta);
        assertThat(lerJson(resposta, "$.respostasTexto[0].ordem")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.respostasTexto[0].contagem")).isEqualTo(3);
        assertThat(corpo(resposta)).as("a marca não vê a marca da suspeita").doesNotContain("\"suspeita\"");
    }

    @Test
    void rbsVeAsRespostasComSuspeitaEQualquerParceria() {
        long amarelo = parceria("Cliente Amarelo");
        semearPerfil();
        long pergunta = jdbc.sql("SELECT id FROM pesquisa_pergunta WHERE parceria_id = :p AND ordem = 1")
                .param("p", amarelo).query(Long.class).single();
        long pesquisa = pesquisaDaAtivacao(amarelo);
        jdbc.sql("""
                        INSERT INTO pesquisa_resposta_texto (pesquisa_id, parceria_id, pergunta_id, texto, contagem,
                                                             suspeita_dado_pessoal)
                        VALUES (:pesquisa, :parceria, :pergunta, 'Gostei do espaço', 3, FALSE),
                               (:pesquisa, :parceria, :pergunta, 'Meu telefone é (51) 99999-8888', 1, TRUE)
                        """).param("pesquisa", pesquisa).param("parceria", amarelo).param("pergunta", pergunta).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, RBS), amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.respostasTexto[*].texto")).containsExactly("Gostei do espaço",
                "Meu telefone é (51) 99999-8888");
        assertThat(lerLista(resposta, "$.respostasTexto[*].suspeita")).containsExactly(false, true);
    }

    @Test
    void semPesquisaAbertaOPerfilVemVazioEMasAGeralAindaAparece() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("DELETE FROM pesquisa_pergunta WHERE parceria_id = :p").param("p", amarelo).update();
        jdbc.sql("DELETE FROM pesquisa WHERE escopo = 'ATIVACAO'").update();
        long geral = jdbc.sql("""
                        INSERT INTO pesquisa (evento_id, nome, escopo, base_respondentes)
                        VALUES (:evento, 'Pesquisa geral do festival 2027 (teste)', 'GERAL', 1038) RETURNING id
                        """).param("evento", evento(2027)).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes)
                        VALUES (:pesquisa, NULL, 'GENERO', 'Feminino', 62.0, 1038)
                        """).param("pesquisa", geral).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.pesquisa")).isNull();
        assertThat(lerLista(resposta, "$.perfil")).isEmpty();
        assertThat(lerLista(resposta, "$.respostasTexto")).isEmpty();
        assertThat(lerLista(resposta, "$.geral[*].categoria")).containsExactly("Feminino");
    }

    @Test
    void semSessao401MarcaDeOutraParceria404ENaoMaster403() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");

        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + amarelo + "/pesquisa/perfil"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(perfil(SessaoDeApi.entrar(mvc, AMARELO), roxo)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(perfil(SessaoDeApi.entrar(mvc, AMARELO), 999_999L)).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult doRoxo = perfil(SessaoDeApi.entrar(mvc, ROXO), roxo);
        assertThat(doRoxo).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(doRoxo, "$.detail")).isEqualTo("Esta aba não faz parte da cota da sua parceria.");
        assertThat(perfil(SessaoDeApi.entrar(mvc, RBS), 999_999L)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aAbaFechadaVemAntesDoPerfilParaAMaster() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("""
                        UPDATE parceria_aba SET liberada = FALSE
                        WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')
                        """).param("p", amarelo).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
        assertThat(perfil(SessaoDeApi.entrar(mvc, RBS), amarelo)).as("a RBS passa").hasStatus(HttpStatus.OK);
    }

    @Test
    void oPerfilDaGeralVemSoDaPesquisaGeralDaMesmaEdicao() {
        long amarelo = parceria("Cliente Amarelo");
        semearPerfil();
        // Uma geral de outra edição não entra no comparativo.
        long geral2026 = jdbc.sql("""
                        INSERT INTO pesquisa (evento_id, nome, escopo, base_respondentes)
                        VALUES (:evento, 'Pesquisa geral do festival 2026 (teste)', 'GERAL', 900) RETURNING id
                        """).param("evento", evento(2026)).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes)
                        VALUES (:pesquisa, NULL, 'GENERO', 'Outro', 5.0, 900)
                        """).param("pesquisa", geral2026).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        assertThat(lerLista(resposta, "$.geral[*].categoria")).as("só a geral de 2027").containsExactly("Feminino");
        assertThat(lerLista(resposta, "$.geral[*].baseRespondentes")).containsExactly(1038);
    }

    @Test
    void oPerfilRespeitaADimensaoEAsCategoriasDoBanco() {
        long amarelo = parceria("Cliente Amarelo");
        long pesquisa = pesquisaDaAtivacao(amarelo);
        jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes)
                        VALUES (:pesquisa, :parceria, 'FAIXA_ETARIA', '18 a 24', 30.5, 412),
                               (:pesquisa, :parceria, 'RENDA', 'Até 3 salários', 20.0, 412)
                        """).param("pesquisa", pesquisa).param("parceria", amarelo).update();

        MvcTestResult resposta = perfil(SessaoDeApi.entrar(mvc, AMARELO), amarelo);

        List<Map<String, Object>> perfil = lerLista(resposta, "$.perfil");
        assertThat(perfil).extracting(p -> p.get("dimensao")).containsExactly("FAIXA_ETARIA", "RENDA");
        assertThat(perfil).extracting(p -> p.get("categoria")).containsExactly("18 a 24", "Até 3 salários");
        assertThat(new java.math.BigDecimal(String.valueOf(perfil.get(0).get("valor")))).isEqualByComparingTo("30.5");
        assertThat(perfil.get(0).get("baseRespondentes")).isEqualTo(412);
    }
}
