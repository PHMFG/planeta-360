package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.materiais.Arquivo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prova que o arquivo em disco acompanha a transação: com rollback ele é apagado, com commit ele fica. Atende o item 4 da
 * Entrega 2. [UPLOAD] Este teste NÃO usa @Transactional (precisa de transações de verdade) e limpa o que commita.
 */
@TesteDeIntegracao
class ArmazenamentoRollbackTest {

    @Autowired
    Armazenamento armazenamento;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager gerenteDeTransacao;

    long rbs() {
        return jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
    }

    @Test
    void rollbackApagaOArquivoDoDiscoEOBancoNaoGuardaNada() {
        long antes = jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single();
        Object[] gravado = new Object[2];

        new TransactionTemplate(gerenteDeTransacao).executeWithoutResult(status -> {
            Arquivo arquivo = armazenamento.gravar("%PDF-1.4 rollback".getBytes(StandardCharsets.US_ASCII), "r.pdf", "OUTRO", rbs(), null);
            gravado[0] = armazenamento.localizar(arquivo.caminho()).orElseThrow();
            gravado[1] = arquivo.caminho();
            status.setRollbackOnly();
        });

        assertThat(((Path) gravado[0])).doesNotExist();
        assertThat(armazenamento.localizar((String) gravado[1])).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void commitMantemOArquivo() {
        Arquivo[] gravado = new Arquivo[1];

        new TransactionTemplate(gerenteDeTransacao).executeWithoutResult(status ->
                gravado[0] = armazenamento.gravar("%PDF-1.4 commit".getBytes(StandardCharsets.US_ASCII), "c.pdf", "OUTRO", rbs(), null));

        try {
            assertThat(armazenamento.localizar(gravado[0].caminho())).isPresent();
        } finally {
            // Limpeza: este teste commitou de verdade, então apaga a linha e o arquivo (o banco é recriado no próximo ciclo de testes).
            jdbc.sql("DELETE FROM arquivo WHERE id = :id").param("id", gravado[0].id()).update();
            armazenamento.localizar(gravado[0].caminho()).ifPresent(caminho -> caminho.toFile().delete());
        }
    }
}
