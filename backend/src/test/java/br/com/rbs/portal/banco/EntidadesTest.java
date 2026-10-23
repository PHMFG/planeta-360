package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.Usuario;
import br.com.rbs.portal.comum.Historico;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.materiais.Arquivo;
import br.com.rbs.portal.materiais.Entrega;
import br.com.rbs.portal.parceria.Parceria;
import br.com.rbs.portal.relatorio.Relatorio;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Confere que as entidades em record (parceria, usuario, entrega, arquivo, historico e relatorio) batem com as colunas
 * das tabelas: o Spring Data JDBC lê todas as linhas da amostra sem erro. Atende o item 1 da Entrega 2 ("definição das entidades").
 */
@TesteDeIntegracao
class EntidadesTest {

    @Autowired
    JdbcAggregateTemplate banco;

    @Autowired
    JdbcClient jdbc;

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    @Test
    void parceriaBateComATabela() {
        List<Parceria> parcerias = banco.findAll(Parceria.class).stream().toList();
        assertThat(parcerias).hasSize((int) contar("parceria")).isNotEmpty();
        assertThat(parcerias).allSatisfy(p -> {
            assertThat(p.id()).isNotNull();
            assertThat(p.eventoId()).isNotNull();
            assertThat(p.cotaTipoId()).isBetween(1, 3);
            assertThat(p.criadoEm()).isNotNull();
        });
    }

    @Test
    void usuarioBateComATabela() {
        List<Usuario> usuarios = banco.findAll(Usuario.class).stream().toList();
        assertThat(usuarios).hasSize(6);
        assertThat(usuarios).filteredOn(u -> u.perfil() == Perfil.ADMIN_RBS).hasSize(2);
        assertThat(usuarios).allSatisfy(u -> assertThat(u.senhaHash()).startsWith("$2a$10$"));
    }

    @Test
    void entregaBateComATabela() {
        List<Entrega> entregas = banco.findAll(Entrega.class).stream().toList();
        assertThat(entregas).hasSize((int) contar("entrega")).isNotEmpty();
        assertThat(entregas).allSatisfy(e -> {
            assertThat(e.titulo()).isNotBlank();
            assertThat(e.status()).isNotBlank();
        });
    }

    @Test
    void arquivoBateComATabela() {
        List<Arquivo> arquivos = banco.findAll(Arquivo.class).stream().toList();
        assertThat(arquivos).hasSize((int) contar("arquivo")).isNotEmpty();
        assertThat(arquivos).allSatisfy(a -> {
            assertThat(a.caminho()).isNotBlank();
            assertThat(a.tamanhoBytes()).isPositive();
            assertThat(a.versao()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void relatorioBateComATabela() {
        List<Relatorio> relatorios = banco.findAll(Relatorio.class).stream().toList();
        assertThat(relatorios).hasSize(3);
        assertThat(relatorios).allSatisfy(r -> assertThat(r.status()).isEqualTo("RASCUNHO"));
    }

    @Test
    void historicoBateComATabela() {
        // O campo ip é inet no banco: a leitura usa host(ip), como o Historico recomenda.
        List<Historico> linhas = jdbc.sql("""
                        SELECT id, parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, detalhe,
                               usuario_id, ocorrido_em, host(ip) AS ip, user_agent
                        FROM historico
                        """)
                .query(Historico.class)
                .list();
        assertThat(linhas).hasSize((int) contar("historico"));
        assertThat(linhas).allSatisfy(h -> {
            assertThat(h.entidade()).isNotBlank();
            assertThat(h.acao()).isNotBlank();
            assertThat(h.ocorridoEm()).isNotNull();
        });
    }
}
