package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.ArquivosDaAmostra;
import br.com.rbs.portal.documentos.DocumentosDaAmostra;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;

/**
 * [ATLAS] O perfil {@code demo} (M8, decisão 17a do plano), lido do {@code application.yml} de verdade, sem subir o contexto: a
 * apresentação roda com a amostra, o kit de documentos, o relatório e o diagnóstico ligados, os pontos do JEV da calibração (os
 * mesmos do {@code dev}, sem deixar os dois divergirem) e SEM o debug da chave nem o das ferramentas. Que ele sobe do zero e responde
 * 404 nas rotas de debug da chave se prova subindo a API num banco novo (README do M8).
 */
class PerfilDemoTest {

    /** Todas as propriedades do bloco do perfil {@code perfil} do application.yml, achatadas. */
    private static Map<String, Object> bloco(String perfil) throws IOException {
        List<PropertySource<?>> documentos = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        for (PropertySource<?> documento : documentos) {
            if (documento instanceof EnumerablePropertySource<?> lista && perfil.equals(documento.getProperty("spring.config.activate.on-profile"))) {
                Map<String, Object> propriedades = new TreeMap<>();
                for (String nome : lista.getPropertyNames()) {
                    propriedades.put(nome, lista.getProperty(nome));
                }
                return propriedades;
            }
        }
        throw new AssertionError("O application.yml não tem o bloco do perfil " + perfil + ".");
    }

    private static Map<String, Object> comPrefixo(Map<String, Object> propriedades, String prefixo) {
        Map<String, Object> filtradas = new LinkedHashMap<>();
        propriedades.forEach((nome, valor) -> {
            if (nome.startsWith(prefixo)) {
                filtradas.put(nome, valor);
            }
        });
        return filtradas;
    }

    @Test
    void aAmostraOsArquivosDeExemploEOKitSoSobemNosPerfisDevEDemo() {
        assertThat(ArquivosDaAmostra.class.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("dev", "demo");
        assertThat(DocumentosDaAmostra.class.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("dev", "demo");
    }

    @Test
    void odemoCarregaAAmostraEOsSegredosPeloMesmoArquivoDoDev() throws IOException {
        Map<String, Object> demo = bloco("demo");
        Map<String, Object> dev = bloco("dev");

        assertThat(String.valueOf(demo.get("spring.flyway.locations"))).contains("classpath:db/migration").contains("classpath:db/dev");
        assertThat(demo.get("spring.config.import")).isEqualTo("optional:file:./config/segredos.local.properties")
                .isEqualTo(dev.get("spring.config.import"));
    }

    @Test
    void odemoLigaORelatorioEODiagnosticoEDesligaOsDebugs() throws IOException {
        Map<String, Object> demo = bloco("demo");

        assertThat(demo.get("atlas.debug.chaves")).as("as rotas de debug da chave (404)").isEqualTo(false);
        assertThat(demo.get("atlas.debug.ferramentas")).as("a caixa de debug das ferramentas").isEqualTo(false);
        assertThat(String.valueOf(demo.get("atlas.relatorio.ligado"))).contains("true");
        assertThat(String.valueOf(demo.get("atlas.diagnostico.ligado"))).contains("true");
        assertThat(String.valueOf(demo.get("atlas.diagnostico.marca"))).contains("true");
        // e o dev continua com os debugs ligados: a diferença entre os dois perfis é exatamente essa
        Map<String, Object> dev = bloco("dev");
        assertThat(dev.get("atlas.debug.chaves")).isEqualTo(true);
        assertThat(String.valueOf(dev.get("atlas.debug.ferramentas"))).contains("true");
    }

    @Test
    void osPontosDoJevDoDemoSaoOsMesmosDoDev() throws IOException {
        Map<String, Object> demo = comPrefixo(bloco("demo"), "atlas.jev.");
        Map<String, Object> dev = comPrefixo(bloco("dev"), "atlas.jev.");

        assertThat(demo).as("os sete pontos, com modo e limiar").hasSize(14);
        assertThat(demo).isEqualTo(dev);
        assertThat(demo.keySet()).anyMatch(nome -> nome.equals("atlas.jev.intencao.limiar"));
    }

    @Test
    void oPerfilDemoNaoToleraVariavelDeDebugEscondida() throws IOException {
        // atlas.debug.chaves não lê variável de ambiente no demo: o valor é fixo, para ninguém ligar as rotas por engano na apresentação
        Map<String, Object> demo = bloco("demo");
        assertThat(String.valueOf(demo.get("atlas.debug.chaves"))).doesNotContain("${");
        assertThat(String.valueOf(demo.get("atlas.debug.ferramentas"))).doesNotContain("${");
    }
}
