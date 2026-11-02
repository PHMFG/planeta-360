package br.com.rbs.portal.dados;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do catálogo de campos e dos dados informados (rotas 39 a 41): lê o catálogo, o valor vigente de cada campo de uma
 * parceria (vw_dado_vigente) e grava um valor novo. [HISTORICO] Dado informado nunca muda: corrigir é inserir uma linha nova
 * (o gatilho rn_dado_informado_imutavel recusa UPDATE e DELETE), e a visão devolve a mais recente. [ISOLAMENTO] Toda consulta
 * de valores recebe o id da parceria; quem decide se o usuário pode vê-la é o DadoInformadoService.
 */
@Repository
public class DadoInformadoRepositorio {

    /** Um campo do catálogo, com o código da aba (e não o id) e o tipo como o banco guarda. */
    public record LinhaDeCampo(String chave, String aba, String rotulo, String tipo, String unidade, BigDecimal minimo,
                               BigDecimal maximo, String ajuda, String limiteCampo) {
    }

    /** O valor vigente de um campo, com o tipo do campo (para escolher a coluna certa) e o autor já resumido. */
    public record LinhaDeVigente(String campoChave, String tipo, BigDecimal valorNumero, LocalDate valorData, Long arquivoId,
                                 String arquivoNome, String origem, Long usuarioId, String usuarioNome, String usuarioPerfil,
                                 OffsetDateTime informadoEm) {
    }

    private static final String CAMPOS = """
            SELECT c.chave, a.codigo AS aba, c.rotulo, c.tipo, c.unidade, c.minimo, c.maximo, c.ajuda, c.limite_campo
            FROM campo_catalogo c JOIN aba a ON a.id = c.aba_id
            """;

    private final JdbcClient jdbc;

    public DadoInformadoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Catálogo
    // ---------------------------------------------------------------------

    /** Os campos do catálogo na ordem do menu e do formulário (aba, ordem, chave); {@code aba} nula traz todas as abas. */
    public List<LinhaDeCampo> campos(String abaCodigo) {
        return jdbc.sql(CAMPOS + """
                        WHERE (CAST(:aba AS text) IS NULL OR a.codigo = :aba)
                        ORDER BY a.ordem, c.ordem, c.chave
                        """)
                .param("aba", abaCodigo)
                .query(LinhaDeCampo.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Valores
    // ---------------------------------------------------------------------

    /**
     * Trava a linha da parceria até o fim da transação. Dois pedidos da mesma parceria esperam um pelo outro; sem isso, cada um
     * leria os mesmos valores vigentes e os dois passariam pela conferência de limite, deixando cadastros acima de visitantes.
     * FOR NO KEY UPDATE trava os concorrentes entre si sem atrapalhar quem só aponta para a parceria (a chave estrangeira).
     */
    public void travarParceria(long parceriaId) {
        jdbc.sql("SELECT id FROM parceria WHERE id = :id FOR NO KEY UPDATE").param("id", parceriaId).query(Long.class).optional();
    }

    /** O valor vigente de cada campo que a parceria já informou (os campos sem valor não aparecem). */
    public List<LinhaDeVigente> vigentes(long parceriaId) {
        return jdbc.sql("""
                        SELECT v.campo_chave, c.tipo, v.valor_numero, v.valor_data, v.arquivo_id, a.nome_original AS arquivo_nome, v.origem,
                               u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil, v.informado_em
                        FROM vw_dado_vigente v
                        JOIN campo_catalogo c ON c.chave = v.campo_chave
                        JOIN usuario u ON u.id = v.informado_por
                        LEFT JOIN arquivo a ON a.id = v.arquivo_id
                        WHERE v.parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeVigente.class)
                .list();
    }

    /** Grava um valor numérico novo e devolve o id da linha. A data e a origem (PORTAL ou ATLAS) vêm de quem chama e do banco. */
    public long inserirNumero(long parceriaId, String chave, BigDecimal valor, String origem, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
                        VALUES (:parceria, :chave, :valor, :origem, :usuario) RETURNING id
                        """)
                .param("parceria", parceriaId).param("chave", chave).param("valor", valor)
                .param("origem", origem).param("usuario", usuarioId)
                .query(Long.class).single();
    }

    /** O mesmo, para um campo de arquivo (a planilha da agência, rota 66): o arquivo já gravado, da mesma parceria (chave composta). */
    public long inserirArquivo(long parceriaId, String chave, long arquivoId, String origem, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por)
                        VALUES (:parceria, :chave, :arquivo, :origem, :usuario) RETURNING id
                        """)
                .param("parceria", parceriaId).param("chave", chave).param("arquivo", arquivoId)
                .param("origem", origem).param("usuario", usuarioId)
                .query(Long.class).single();
    }

    /** O mesmo, para um campo de data. */
    public long inserirData(long parceriaId, String chave, LocalDate valor, String origem, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO dado_informado (parceria_id, campo_chave, valor_data, origem, informado_por)
                        VALUES (:parceria, :chave, :valor, :origem, :usuario) RETURNING id
                        """)
                .param("parceria", parceriaId).param("chave", chave).param("valor", valor)
                .param("origem", origem).param("usuario", usuarioId)
                .query(Long.class).single();
    }
}
