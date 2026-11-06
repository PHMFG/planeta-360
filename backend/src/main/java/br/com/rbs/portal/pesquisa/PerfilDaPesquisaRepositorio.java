package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePesquisa;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * As consultas da rota 119: a pesquisa GERAL da edição, o perfil demográfico e as respostas de texto. [PESQUISA] O perfil da
 * marca sai de pesquisa_perfil com a parceria; o da pesquisa geral, com parceria nula. A resposta com suspeita de dado
 * pessoal só sai quando quem pede é a RBS.
 */
@Repository
public class PerfilDaPesquisaRepositorio {

    public record LinhaDePerfil(String dimensao, String categoria, BigDecimal valor, Integer baseRespondentes) {
    }

    public record LinhaDeRespostaTexto(Long perguntaId, Integer ordem, String texto, Integer contagem, boolean suspeita) {
    }

    private final JdbcClient jdbc;

    public PerfilDaPesquisaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O código da cota da parceria (MASTER, SUBCOTA_G ou SUBCOTA). Vazio se a parceria não existe. */
    public Optional<String> cota(long parceriaId) {
        return jdbc.sql("""
                        SELECT ct.codigo FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .query(String.class)
                .optional();
    }

    /** A pesquisa GERAL da edição da parceria (a mais antiga, se houver mais de uma). Vazia sem ela. */
    public Optional<LinhaDePesquisa> pesquisaGeral(long parceriaId) {
        return jdbc.sql("""
                        SELECT p.id, p.nome, p.escopo, p.metodologia, p.base_respondentes, p.nivel_confianca,
                               p.limite_perguntas
                        FROM pesquisa p JOIN parceria pa ON pa.evento_id = p.evento_id
                        WHERE pa.id = :parceriaId AND p.escopo = 'GERAL'
                        ORDER BY p.id LIMIT 1
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDePesquisa.class)
                .optional();
    }

    /** O perfil de uma pesquisa: as categorias da parceria (ou as da pesquisa geral, quando {@code parceriaId} é nulo). */
    public List<LinhaDePerfil> perfil(long pesquisaId, Long parceriaId) {
        return jdbc.sql("""
                        SELECT dimensao, categoria, valor, base_respondentes
                        FROM pesquisa_perfil
                        WHERE pesquisa_id = :pesquisaId
                          AND parceria_id IS NOT DISTINCT FROM CAST(:parceriaId AS bigint)
                        ORDER BY dimensao, categoria
                        """)
                .param("pesquisaId", pesquisaId)
                .param("parceriaId", parceriaId)
                .query(LinhaDePerfil.class)
                .list();
    }

    /**
     * [PESQUISA] As respostas de texto das perguntas APROVADAS da parceria, agregadas. Para a marca, a resposta com suspeita
     * de dado pessoal não sai daqui; a RBS a recebe com a marca da suspeita.
     */
    public List<LinhaDeRespostaTexto> respostasTexto(long pesquisaId, long parceriaId, boolean incluirSuspeita) {
        return jdbc.sql("""
                        SELECT r.pergunta_id, q.ordem, r.texto, r.contagem, r.suspeita_dado_pessoal AS suspeita
                        FROM pesquisa_resposta_texto r JOIN pesquisa_pergunta q ON q.id = r.pergunta_id
                        WHERE r.pesquisa_id = :pesquisaId AND r.parceria_id = :parceriaId
                          AND q.status = 'APROVADA'
                          AND (CAST(:incluirSuspeita AS boolean) OR NOT r.suspeita_dado_pessoal)
                        ORDER BY q.ordem, r.id
                        """)
                .param("pesquisaId", pesquisaId)
                .param("parceriaId", parceriaId)
                .param("incluirSuspeita", incluirSuspeita)
                .query(LinhaDeRespostaTexto.class)
                .list();
    }
}
