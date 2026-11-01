package br.com.rbs.portal.comum;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Dá um arquivo de verdade a cada arquivo da amostra de demonstração. Atende o item 4 da Entrega 2. A amostra
 * (db/dev/R__amostra.sql) só cria as LINHAS da tabela arquivo; sem este passo, "Baixar" num arquivo da amostra responderia
 * 404 ("Este arquivo não está mais disponível"). Ao subir a API, cada arquivo da amostra que não existe no armazenamento
 * ganha um conteúdo pequeno e válido para a extensão (veja ConteudoDeExemplo), e a linha do banco passa a ter o tamanho e o
 * hash do arquivo criado.
 *
 * <p>[UPLOAD] Só roda nos perfis dev e demo (o da apresentação, M8): em produção a classe nem entra no contexto. Nunca mexe em arquivo enviado de verdade:
 * o envio de verdade grava com nome gerado (UUID), e este passo ignora esses caminhos; e a criação é CREATE_NEW, que não
 * sobrescreve nada que já exista. Rodar de novo não muda nada.
 */
@Component
@Profile({"dev", "demo"})
public class ArquivosDaAmostra implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ArquivosDaAmostra.class);

    /** O nome que o envio de verdade gera: um UUID e a extensão (veja Armazenamento). A amostra nunca usa esse formato. */
    private static final Pattern GERADO_PELO_SERVIDOR =
            Pattern.compile("(^|/)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]+$");

    private record Linha(Long id, String nomeOriginal, String caminho) {
    }

    private final JdbcClient jdbc;
    private final Armazenamento armazenamento;

    public ArquivosDaAmostra(JdbcClient jdbc, Armazenamento armazenamento) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        List<Linha> arquivos = jdbc.sql("SELECT id, nome_original, caminho FROM arquivo ORDER BY id").query(Linha.class).list();
        int criados = 0;
        for (Linha arquivo : arquivos) {
            if (criarSeFaltar(arquivo)) {
                criados++;
            }
        }
        if (criados > 0) {
            log.info("Arquivos da amostra: {} arquivos de exemplo criados no armazenamento.", criados);
        }
    }

    private boolean criarSeFaltar(Linha arquivo) {
        if (GERADO_PELO_SERVIDOR.matcher(arquivo.caminho()).find() || armazenamento.localizar(arquivo.caminho()).isPresent()) {
            return false;
        }
        byte[] conteudo = ConteudoDeExemplo.para(arquivo.nomeOriginal()).orElse(null);
        if (conteudo == null) {
            log.warn("Arquivo da amostra sem exemplo para a extensão: {}", arquivo.nomeOriginal());
            return false;
        }
        if (!armazenamento.criarSeNaoExiste(arquivo.caminho(), conteudo)) {
            return false;
        }
        // A linha da amostra tem um tamanho inventado; passa a dizer o que o arquivo criado realmente tem.
        jdbc.sql("UPDATE arquivo SET tamanho_bytes = :tamanho, hash_sha256 = :hash WHERE id = :id")
                .param("tamanho", (long) conteudo.length)
                .param("hash", Armazenamento.sha256(conteudo))
                .param("id", arquivo.id())
                .update();
        return true;
    }
}
