package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o passo que dá um arquivo de verdade a cada arquivo da amostra de demonstração (perfil dev). Atende o item 4 da
 * Entrega 2. [UPLOAD] Usa uma pasta temporária no lugar do armazenamento, e a transação do teste (@Transactional) desfaz as
 * mudanças no banco, então nada fica no armazenamento de teste nem na amostra.
 */
@TesteDeIntegracao
@Transactional
class ArquivosDaAmostraTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired(required = false)
    ArquivosDaAmostra noContextoDeTeste;

    @TempDir
    Path pasta;

    Armazenamento armazenamento() {
        return new Armazenamento(jdbc, pasta.toString(), 20L * 1024 * 1024);
    }

    /** Os arquivos da amostra: os que o envio de verdade não criaria (sem nome gerado com UUID no caminho). */
    List<Map<String, Object>> arquivosDaAmostra() {
        return jdbc.sql("SELECT id, nome_original, caminho, tamanho_bytes, hash_sha256 FROM arquivo "
                + "WHERE caminho !~ '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.][a-z0-9]+$' ORDER BY id")
                .query().listOfRows();
    }

    @Test
    void soExisteNosPerfisDevEDemoENuncaNosTestesNemEmProducao() {
        assertThat(ArquivosDaAmostra.class.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("dev", "demo");
        assertThat(noContextoDeTeste).as("o perfil test não carrega o passo").isNull();
    }

    @Test
    void cadaArquivoDaAmostraGanhaUmArquivoValidoParaAExtensaoComTamanhoEHashDeVerdade() throws Exception {
        List<Map<String, Object>> amostra = arquivosDaAmostra();
        assertThat(amostra).as("a amostra tem dezenas de arquivos").hasSizeGreaterThan(50);
        Armazenamento armazenamento = armazenamento();

        new ArquivosDaAmostra(jdbc, armazenamento).run(null);

        for (Map<String, Object> arquivo : arquivosDaAmostra()) {
            String nome = (String) arquivo.get("nome_original");
            Path emDisco = armazenamento.localizar((String) arquivo.get("caminho"))
                    .orElseThrow(() -> new AssertionError("Sem arquivo em disco para " + nome));
            byte[] conteudo = Files.readAllBytes(emDisco);
            TipoDeArquivo tipo = TipoDeArquivo.daExtensao(nome).orElseThrow();
            assertThat(tipo.conteudoConfere(conteudo)).as(nome + " é válido para a extensão").isTrue();
            assertThat(((Number) arquivo.get("tamanho_bytes")).longValue()).as(nome + ": tamanho do banco = do arquivo").isEqualTo(conteudo.length);
            assertThat(arquivo.get("hash_sha256")).as(nome + ": hash do banco = do arquivo").isEqualTo(Armazenamento.sha256(conteudo));
        }
    }

    @Test
    void osPdfsDizemQueSaoArquivoDeExemploComONomeDoArquivo() throws Exception {
        Armazenamento armazenamento = armazenamento();

        new ArquivosDaAmostra(jdbc, armazenamento).run(null);

        Map<String, Object> logo = arquivosDaAmostra().stream().filter(a -> "logo_final.pdf".equals(a.get("nome_original"))).findFirst().orElseThrow();
        String texto = new String(Files.readAllBytes(armazenamento.localizar((String) logo.get("caminho")).orElseThrow()),
                StandardCharsets.ISO_8859_1);
        assertThat(texto).startsWith("%PDF-").contains("Arquivo de exemplo").contains("logo_final.pdf");
    }

    @Test
    void naoSobrescreveUmArquivoQueJaExisteNemMexeNaLinhaDele() throws IOException {
        Armazenamento armazenamento = armazenamento();
        Map<String, Object> primeiro = arquivosDaAmostra().getFirst();
        Path destino = pasta.resolve((String) primeiro.get("caminho"));
        Files.createDirectories(destino.getParent());
        byte[] enviadoDeVerdade = "%PDF-1.4\nconteudo que a marca enviou de verdade\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        Files.write(destino, enviadoDeVerdade);

        new ArquivosDaAmostra(jdbc, armazenamento).run(null);

        assertThat(Files.readAllBytes(destino)).as("o arquivo que já estava lá continua igual").isEqualTo(enviadoDeVerdade);
        Map<String, Object> linha = jdbc.sql("SELECT tamanho_bytes, hash_sha256 FROM arquivo WHERE id = :id")
                .param("id", primeiro.get("id")).query().singleRow();
        assertThat(linha.get("tamanho_bytes")).isEqualTo(primeiro.get("tamanho_bytes"));
        assertThat(linha.get("hash_sha256")).isEqualTo(primeiro.get("hash_sha256"));
    }

    @Test
    void ignoraArquivoEnviadoDeVerdadeMesmoQueSeuArquivoTenhaSumido() {
        long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
        String caminho = "geral/3f2a9c1e-7b44-4c58-9d6a-0e1f2a3b4c5d.pdf";
        jdbc.sql("""
                        INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, hash_sha256, enviado_por)
                        VALUES ('OUTRO', 'enviado.pdf', 'application/pdf', 1234, :caminho, repeat('a', 64), :rbs)
                        """).param("caminho", caminho).param("rbs", rbs).update();
        Armazenamento armazenamento = armazenamento();

        new ArquivosDaAmostra(jdbc, armazenamento).run(null);

        assertThat(armazenamento.localizar(caminho)).as("não inventa conteúdo para o que foi enviado de verdade").isEmpty();
        assertThat(jdbc.sql("SELECT tamanho_bytes FROM arquivo WHERE caminho = :c").param("c", caminho).query(Long.class).single())
                .isEqualTo(1234L);
    }

    @Test
    void rodarDeNovoNaoMudaNada() throws IOException {
        Armazenamento armazenamento = armazenamento();
        ArquivosDaAmostra passo = new ArquivosDaAmostra(jdbc, armazenamento);
        passo.run(null);
        List<Map<String, Object>> depoisDaPrimeira = arquivosDaAmostra();
        Path umArquivo = armazenamento.localizar((String) depoisDaPrimeira.getFirst().get("caminho")).orElseThrow();
        byte[] conteudo = Files.readAllBytes(umArquivo);

        passo.run(null);

        assertThat(arquivosDaAmostra()).isEqualTo(depoisDaPrimeira);
        assertThat(Files.readAllBytes(umArquivo)).isEqualTo(conteudo);
    }
}
