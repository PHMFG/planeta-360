package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/**
 * Ajudas dos testes das ferramentas de consulta ao portal (M5: {@code listar_entregas}, {@code consultar_cota} e
 * {@code consultar_prazos}): o contexto de uma marca, o turno com a data de hoje que o teste escolher, a chamada direta pelo registro
 * (as duas fases, como o loop) e a leitura do resultado. Os testes que herdam daqui são {@code @Transactional}: o que alteram no
 * banco (fechar uma aba, gravar uma entrega ou um prazo) é desfeito no fim. Não faz parte da API.
 */
abstract class ApoioDasConsultas {

    static final String AMARELO = ApoioDoAtlas.AMARELO;
    static final String ROXO = ApoioDoAtlas.ROXO;
    static final String VERDE = ApoioDoAtlas.VERDE;
    /** Um domingo; os testes dos prazos contam dias a partir dele. */
    static final LocalDate HOJE = LocalDate.of(2026, 11, 1);

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    JdbcClient jdbc;

    AtlasContexto contexto(String email, String cliente) {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);
        var pessoa = usuario(jdbc, email);
        long conversa = atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId();
        return contextos.para(pessoa, parceria, conversa, requisicao());
    }

    FontesDoTurno turno() {
        return turno(HOJE);
    }

    FontesDoTurno turno(LocalDate hoje) {
        return new FontesDoTurno("Oi", 2027, hoje);
    }

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    /** Chama a ferramenta como o loop: a fase 1 e a fase 2. Erro de argumento sobe como exceção. */
    Execucao chamar(AtlasContexto contexto, FontesDoTurno turno, String ferramenta, String argumentos) {
        var preparo = registro.preparar(contexto, turno, ferramenta, argumentos);
        return registro.executar(contexto, turno, preparo, "call_teste");
    }

    /** O mesmo, devolvendo o resultado como objeto. */
    Map<String, Object> resultado(AtlasContexto contexto, FontesDoTurno turno, String ferramenta, String argumentos) {
        return objeto(chamar(contexto, turno, ferramenta, argumentos).resultadoJson());
    }

    static Map<String, Object> objeto(String json) {
        return AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> registros(Map<String, Object> resultado) {
        return (List<Map<String, Object>>) resultado.get("registros");
    }

    /** Fecha a aba da parceria: o patrocinador deixa de vê-la (liberada = falso). */
    void fecharAba(long parceriaId, String codigoDaAba) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", codigoDaAba).update();
    }

    /** Esconde a aba do menu da parceria (visível = falso, mesmo liberada). */
    void esconderAba(long parceriaId, String codigoDaAba) {
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", codigoDaAba).update();
    }

    /** Grava uma entrega da parceria na aba, com o status e o prazo dados. Devolve o id. */
    long entrega(long parceriaId, String aba, String titulo, String status, LocalDate prazo) {
        return jdbc.sql("""
                        INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo)
                        VALUES (:p, (SELECT id FROM aba WHERE codigo = :a), :t, :s, :prazo) RETURNING id
                        """)
                .param("p", parceriaId).param("a", aba).param("t", titulo).param("s", status).param("prazo", prazo).query(Long.class).single();
    }

    long documento(String titulo, int versao) {
        return jdbc.sql("SELECT id FROM documento WHERE titulo = :t AND versao = :v").param("t", titulo).param("v", versao)
                .query(Long.class).single();
    }

    /** Muda o status de um documento do kit (o teste desfaz no fim). REVOGADO leva quem revogou, como a rota 57 grava. */
    void mudarStatusDoDocumento(long documentoId, String status) {
        if ("REVOGADO".equals(status)) {
            jdbc.sql("""
                    UPDATE documento SET status = 'REVOGADO', revogado_por = (SELECT id FROM usuario WHERE email = 'b2b@rbs.example'),
                                         revogado_em = now() WHERE id = :id
                    """).param("id", documentoId).update();
        } else {
            jdbc.sql("UPDATE documento SET status = :s WHERE id = :id").param("s", status).param("id", documentoId).update();
        }
    }
}
