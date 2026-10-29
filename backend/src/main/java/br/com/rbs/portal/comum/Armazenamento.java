package br.com.rbs.portal.comum;

import br.com.rbs.portal.materiais.Arquivo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guarda em disco os arquivos enviados ao portal e registra cada um na tabela arquivo. Atende o item 4 da Entrega 2 e serve
 * também à carga de planilhas (rotas 26 a 28). [UPLOAD] Confere tamanho, extensão e conteúdo; grava com um nome gerado pelo
 * servidor (UUID) na pasta portal.armazenamento.pasta, fora de qualquer pasta pública; guarda caminho, tamanho, tipo e o hash
 * SHA-256 no banco. O nome original só existe no banco e é devolvido no download.
 *
 * <p>Uso simples: {@code Arquivo gravado = armazenamento.gravar(bytes, "planilha.csv", "RELATORIO_VEICULACAO", usuarioId, null)}
 * (parceria nula = arquivo geral da RBS). Chame de dentro de uma transação: se ela der rollback, o arquivo em disco é apagado.
 */
@Service
public class Armazenamento {

    private static final Logger log = LoggerFactory.getLogger(Armazenamento.class);
    private static final int LIMITE_NOME = 255;   // coluna arquivo.nome_original
    private static final String COLUNAS = """
            id, parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
            hash_sha256, versao, enviado_por, enviado_em, observacao""";

    private final JdbcClient jdbc;
    private final Path pasta;
    private final long limiteBytes;

    public Armazenamento(JdbcClient jdbc,
                         @Value("${portal.armazenamento.pasta:./armazenamento}") String pasta,
                         @Value("${portal.armazenamento.tamanho-maximo-bytes:20971520}") long limiteBytes) {
        this.jdbc = jdbc;
        this.pasta = Path.of(pasta).toAbsolutePath().normalize();
        this.limiteBytes = limiteBytes;
    }

    /**
     * Grava um arquivo avulso (sem entrega) e devolve o registro. {@code parceriaId} nulo = arquivo geral da RBS.
     * A categoria é um dos códigos do banco (por exemplo RELATORIO_VEICULACAO, PESQUISA, COMPROVACAO); quem chama confere
     * se o usuário pode usá-la. Erros: ArquivoRecusadoException (413 grande demais, 415 tipo ou conteúdo fora da lista) e
     * DadosInvalidosException (422, arquivo vazio).
     */
    public Arquivo gravar(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, Long parceriaId) {
        Preparado pronto = preparar(conteudo, nomeOriginal);
        String caminho = (parceriaId == null ? "geral" : "parcerias/" + parceriaId) + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, enviado_por)
                        VALUES (:parceriaId, :categoria, :nome, :mime, :tamanho, :caminho, :hash, :enviadoPor)
                        RETURNING\s""" + COLUNAS,
                Map.of("categoria", categoria, "nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(),
                        "tamanho", (long) conteudo.length, "caminho", caminho, "hash", pronto.hash(),
                        "enviadoPor", enviadoPor),
                parceriaId, caminho);
    }

    /**
     * Grava uma nova versão de uma entrega: a parceria e a aba vêm da entrega, e a versão é a maior que ela já tem mais 1.
     * [ISOLAMENTO] Quem chama já conferiu que o usuário pode mexer nesta entrega e a travou (FOR UPDATE), para dois envios
     * ao mesmo tempo não pegarem a mesma versão.
     */
    public Arquivo gravarNaEntrega(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, long entregaId) {
        return gravarNaEntrega(conteudo, nomeOriginal, categoria, enviadoPor, entregaId, null);
    }

    /**
     * O mesmo, com a observação de quem enviou (pode ser nula): ela fica ligada a esta versão e aparece na lista de
     * versões da entrega.
     */
    public Arquivo gravarNaEntrega(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, long entregaId,
                                   String observacao) {
        Preparado pronto = preparar(conteudo, nomeOriginal);
        Long parceriaId = jdbc.sql("SELECT parceria_id FROM entrega WHERE id = :id").param("id", entregaId)
                .query(Long.class).optional().orElseThrow(() -> new NaoEncontradoException("Não encontramos esta entrega."));
        String caminho = "parcerias/" + parceriaId + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type,
                                             tamanho_bytes, caminho, hash_sha256, versao, enviado_por, observacao)
                        SELECT e.parceria_id, e.aba_id, e.id, :categoria, :nome, :mime, :tamanho, :caminho, :hash,
                               coalesce((SELECT max(a.versao) FROM arquivo a WHERE a.entrega_id = e.id), 0) + 1, :enviadoPor,
                               nullif(CAST(:observacao AS text), '')
                        FROM entrega e WHERE e.id = :entregaId
                        RETURNING\s""" + COLUNAS,
                Map.of("categoria", categoria, "nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(),
                        "tamanho", (long) conteudo.length, "caminho", caminho, "hash", pronto.hash(),
                        "enviadoPor", enviadoPor, "entregaId", entregaId, "observacao", observacao == null ? "" : observacao),
                parceriaId, caminho);
    }

    /**
     * O arquivo em disco para o caminho gravado no banco, ou vazio se ele não existe (sumiu do armazenamento) ou se o
     * caminho tentar sair da pasta de armazenamento.
     */
    public Optional<Path> localizar(String caminho) {
        try {
            Path destino = pasta.resolve(caminho).normalize();
            return destino.startsWith(pasta) && Files.isRegularFile(destino) ? Optional.of(destino) : Optional.empty();
        } catch (InvalidPathException caminhoInvalido) {
            return Optional.empty();
        }
    }

    /**
     * Cria o arquivo no caminho dado SEM sobrescrever: se já existe um arquivo ali, não faz nada e devolve falso. Serve para
     * dar conteúdo de exemplo aos arquivos da amostra de demonstração (veja ArquivosDaAmostra); o envio de verdade usa
     * {@link #gravar} e {@link #gravarNaEntrega}. O caminho não pode sair da pasta de armazenamento.
     */
    boolean criarSeNaoExiste(String caminho, byte[] conteudo) {
        Path destino = pasta.resolve(caminho).normalize();
        if (!destino.startsWith(pasta)) {
            throw new IllegalArgumentException("O caminho sai da pasta de armazenamento: " + caminho);
        }
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo, StandardOpenOption.CREATE_NEW);
            return true;
        } catch (FileAlreadyExistsException jaExiste) {
            return false;
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui gravar o arquivo de exemplo no armazenamento", erro);
        }
    }

    public long limiteBytes() {
        return limiteBytes;
    }

    // ---------------------------------------------------------------------
    // Conferência do arquivo
    // ---------------------------------------------------------------------

    private record Preparado(String nomeOriginal, TipoDeArquivo tipo, String hash, String nomeNoDisco) {
    }

    /** [UPLOAD] Vazio (422), grande demais (413), extensão fora da lista ou conteúdo que não bate com ela (415). */
    private Preparado preparar(byte[] conteudo, String nomeInformado) {
        if (conteudo == null || conteudo.length == 0) {
            throw new DadosInvalidosException(Map.of("arquivo", "O arquivo está vazio."));
        }
        if (conteudo.length > limiteBytes) {
            throw new ArquivoRecusadoException(HttpStatus.CONTENT_TOO_LARGE,
                    "O arquivo passa do limite de " + descreverLimite() + ". Envie um arquivo menor.");
        }
        String nome = limparNome(nomeInformado);
        TipoDeArquivo tipo = TipoDeArquivo.daExtensao(nome).orElseThrow(() -> new ArquivoRecusadoException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Este tipo de arquivo não é aceito. Envie um destes: " + TipoDeArquivo.listaDeExtensoes() + "."));
        if (!tipo.conteudoConfere(conteudo)) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, frasePorConteudo(tipo, conteudo));
        }
        return new Preparado(nome, tipo, sha256(conteudo), UUID.randomUUID() + "." + tipo.extensao());
    }

    /**
     * A frase do 415 por conteúdo que não bate com a extensão. Planilha que é texto, mas não está em UTF-8 (o Excel grava
     * em ANSI/Latin-1 ou em UTF-16 quando não se escolhe "CSV UTF-8"), recebe a instrução de como salvar de novo; o que não
     * tem cara de texto (um PNG renomeado para .csv, por exemplo) recebe a frase de arquivo renomeado.
     */
    private static String frasePorConteudo(TipoDeArquivo tipo, byte[] conteudo) {
        if (tipo == TipoDeArquivo.CSV && TipoDeArquivo.ehTextoQueNaoEUtf8(conteudo)) {
            return "A planilha não está em UTF-8 (por isso os acentos não seriam lidos certo). No Excel, use Salvar como e "
                    + "escolha \"CSV UTF-8 (separado por vírgulas)\"; depois envie de novo.";
        }
        return "O conteúdo do arquivo não é um " + tipo.extensao().toUpperCase(Locale.ROOT) + " de verdade. "
                + "Confira se o arquivo não foi renomeado.";
    }

    /**
     * Só o nome do arquivo, sem pasta (o navegador pode mandar "C:\\fotos\\logo.png"), sem caracteres de controle e com no
     * máximo 255 letras (a coluna do banco), preservando a extensão. O nome nunca é usado como caminho em disco.
     */
    static String limparNome(String informado) {
        String nome = informado == null ? "" : informado;
        nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);
        nome = nome.codePoints().filter(c -> !Character.isISOControl(c)).collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString().trim();
        if (nome.length() > LIMITE_NOME) {
            String extensao = TipoDeArquivo.extensaoDe(nome);
            int corte = LIMITE_NOME - extensao.length() - 1;
            nome = extensao.isEmpty() || extensao.length() > 10 ? nome.substring(0, LIMITE_NOME)
                    : nome.substring(0, corte) + "." + extensao;
        }
        return nome;
    }

    private String descreverLimite() {
        long mega = 1024L * 1024L;
        return limiteBytes % mega == 0 ? limiteBytes / mega + " MB" : limiteBytes + " bytes";
    }

    static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException("SHA-256 não está disponível nesta JVM", erro);
        }
    }

    // ---------------------------------------------------------------------
    // Disco e banco
    // ---------------------------------------------------------------------

    /** CREATE_NEW: nunca sobrescreve um arquivo que já existe. Se a transação der rollback, o arquivo é apagado. */
    private void escrever(String caminho, byte[] conteudo) {
        Path destino = pasta.resolve(caminho).normalize();
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo, StandardOpenOption.CREATE_NEW);
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui gravar o arquivo no armazenamento", erro);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        apagar(destino);
                    }
                }
            });
        }
    }

    private Arquivo inserir(String sql, Map<String, Object> parametros, Long parceriaId, String caminho) {
        try {
            return jdbc.sql(sql).param("parceriaId", parceriaId).params(parametros).query(Arquivo.class).single();
        } catch (RuntimeException erro) {
            apagar(pasta.resolve(caminho).normalize());
            throw erro;
        }
    }

    private static void apagar(Path destino) {
        try {
            Files.deleteIfExists(destino);
        } catch (IOException erro) {
            log.warn("Não consegui apagar o arquivo órfão {}", destino, erro);
        }
    }
}
