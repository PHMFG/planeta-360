package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Os modelos em branco que o Atlas entrega (M5): documentos para a marca preencher por conta própria, lidos de
 * {@code resources/atlas/modelos-em-branco/*.txt} (o conteúdo é Markdown simples; o arquivo é {@code .txt} porque o Git do projeto
 * não aceita {@code .md} novo). [ATLAS] Os modelos não têm algarismo, campo de valor monetário, marca, edição nem data: o que a
 * pessoa preencher é dela, e o Atlas não escreve número sem origem. São a base de {@code gerar_modelo} e da oferta do
 * {@code pedido_de_relatorio}.
 * <p>
 * [PERMISSAO] A Pesquisa é só da Master (decisão do projeto): o modelo de perguntas da pesquisa só é oferecido e entregue a quem tem
 * a aba Pesquisa aberta na parceria, conferida agora no banco, como a {@code ConsultaDaParceria} faz. Os arquivos são lidos uma
 * vez, na subida: arquivo que falta derruba a API na hora, não no meio de uma conversa.
 */
@Component
public class ModelosEmBranco {

    /** A aba da Pesquisa: só quem a tem aberta recebe as perguntas da pesquisa. */
    static final String ABA_PESQUISA = "PESQUISA";

    /** Os tipos de modelo, com o título, o arquivo de onde vêm, o nome do arquivo que a pessoa baixa e a aba que o libera (se houver). */
    public enum Tipo {
        BRIEFING_CONTEUDO("Briefing de conteúdo", "briefing-conteudo.txt", "briefing-de-conteudo.md", null),
        PERGUNTAS_PESQUISA("Perguntas da pesquisa", "perguntas-pesquisa.txt", "perguntas-da-pesquisa.md", ABA_PESQUISA),
        PLANO_OPERACIONAL_ATIVACAO("Plano operacional da ativação", "plano-operacional.txt", "plano-operacional-da-ativacao.md", null);

        private final String titulo;
        private final String arquivo;
        private final String nomeDoArquivo;
        private final String aba;

        Tipo(String titulo, String arquivo, String nomeDoArquivo, String aba) {
            this.titulo = titulo;
            this.arquivo = arquivo;
            this.nomeDoArquivo = nomeDoArquivo;
            this.aba = aba;
        }

        public String titulo() {
            return titulo;
        }

        public String nomeDoArquivo() {
            return nomeDoArquivo;
        }

        /** Os tipos que valem para qualquer parceria (os que não dependem de aba). */
        boolean livre() {
            return aba == null;
        }

        /** Um tipo pelo nome exato do contrato ({@code BRIEFING_CONTEUDO}...), ou nada. */
        public static Optional<Tipo> deNome(String nome) {
            for (Tipo tipo : values()) {
                if (tipo.name().equals(nome)) {
                    return Optional.of(tipo);
                }
            }
            return Optional.empty();
        }
    }

    /** Um modelo oferecido à pessoa: o tipo (o nome do enum) e o título. */
    public record Oferecido(String tipo, String titulo) {
    }

    /** Um modelo entregue: o tipo, o título, o conteúdo em Markdown e o nome do arquivo que o navegador baixa. */
    public record Modelo(Tipo tipo, String titulo, String conteudo, String nomeArquivo) {
    }

    private static final String PASTA = "atlas/modelos-em-branco/";

    private final Map<Tipo, String> conteudos = new EnumMap<>(Tipo.class);
    private final VerificadorDeAcesso verificador;

    public ModelosEmBranco(VerificadorDeAcesso verificador) {
        this.verificador = verificador;
        for (Tipo tipo : Tipo.values()) {
            conteudos.put(tipo, ler(tipo.arquivo));
        }
    }

    /**
     * Os tipos que esta pessoa pode receber nesta parceria, na ordem do contrato. [PERMISSAO] O de perguntas da pesquisa só entra
     * com a aba Pesquisa aberta (a RBS passa em qualquer aba, mas o Atlas é só do patrocinador).
     */
    public List<Tipo> disponiveis(UsuarioAutenticado usuario, long parceriaId) {
        List<Tipo> tipos = new ArrayList<>();
        for (Tipo tipo : Tipo.values()) {
            if (tipo.livre() || verificador.podeVerAba(usuario, parceriaId, tipo.aba)) {
                tipos.add(tipo);
            }
        }
        return tipos;
    }

    /** Os modelos que o {@code pedido_de_relatorio} oferece: os {@link #disponiveis}, com o título. */
    public List<Oferecido> oferecidos(UsuarioAutenticado usuario, long parceriaId) {
        return disponiveis(usuario, parceriaId).stream().map(tipo -> new Oferecido(tipo.name(), tipo.titulo)).toList();
    }

    /** O modelo do tipo, com o conteúdo lido na subida. Não confere acesso: quem chama usa {@link #disponiveis}. */
    public Modelo modelo(Tipo tipo) {
        return new Modelo(tipo, tipo.titulo, conteudos.get(tipo), tipo.nomeDoArquivo);
    }

    private static String ler(String arquivo) {
        String caminho = PASTA + arquivo;
        try {
            return new String(new ClassPathResource(caminho).getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip() + "\n";
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o modelo em branco do Atlas (resources/" + caminho + ").", erro);
        }
    }
}
