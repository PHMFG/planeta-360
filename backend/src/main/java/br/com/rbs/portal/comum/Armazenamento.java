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
     * Confere um arquivo de documento do Atlas sem gravar nada, para a rota 54 recusar o que não é PDF nem TXT (415), o que passa do
     * limite (413) e o arquivo vazio (422) ANTES de extrair o texto. Devolve o tipo. O mesmo teste roda de novo dentro de
     * {@link #gravarDocumento(byte[], String, long, Long)}.
     */
    public TipoDeArquivo conferirDocumento(byte[] conteudo, String nomeOriginal) {
        return preparar(conteudo, nomeOriginal, Aceitos.DOCUMENTOS).tipo();
    }

    /**
     * Grava o arquivo de uma importação (E1): categoria IMPORTACAO, com os formatos que a rota 109 aceita (planilha, Office, PDF,
     * imagem e texto). {@code parceriaId} nulo = arquivo geral da RBS (alcance EDICAO ou COTA); preenchido, é o arquivo da parceria
     * (alcance PARCERIA). O .csv e o .txt podem vir em Latin-1, como o Excel os grava; o leitor da importação os decodifica. Chame de
     * dentro de uma transação: o rollback apaga o arquivo do disco.
     */
    public Arquivo gravarImportacao(byte[] conteudo, String nomeOriginal, long enviadoPor, Long parceriaId) {
        Preparado pronto = preparar(conteudo, nomeOriginal, Aceitos.IMPORTACOES);
        String caminho = (parceriaId == null ? "geral" : "parcerias/" + parceriaId) + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, enviado_por)
                        VALUES (:parceriaId, 'IMPORTACAO', :nome, :mime, :tamanho, :caminho, :hash, :enviadoPor)
                        RETURNING\s""" + COLUNAS,
                Map.of("nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(), "tamanho", (long) conteudo.length,
                        "caminho", caminho, "hash", pronto.hash(), "enviadoPor", enviadoPor),
                parceriaId, caminho);
    }

    /**
     * Grava uma cópia do arquivo importado como documento (E15): categoria DOCUMENTO, com os formatos da importação. O gatilho
     * {@code rn_documento_coerente} exige a categoria DOCUMENTO no arquivo do documento, e o arquivo da importação é IMPORTACAO;
     * por isso a cópia (mesmo conteúdo, nome novo no disco). {@code parceriaId} nulo nos alcances EDICAO e COTA.
     */
    public Arquivo gravarDocumentoImportado(byte[] conteudo, String nomeOriginal, long enviadoPor, Long parceriaId) {
        Preparado pronto = preparar(conteudo, nomeOriginal, Aceitos.IMPORTACOES);
        String caminho = (parceriaId == null ? "geral" : "parcerias/" + parceriaId) + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, enviado_por)
                        VALUES (:parceriaId, 'DOCUMENTO', :nome, :mime, :tamanho, :caminho, :hash, :enviadoPor)
                        RETURNING\s""" + COLUNAS,
                Map.of("nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(), "tamanho", (long) conteudo.length,
                        "caminho", caminho, "hash", pronto.hash(), "enviadoPor", enviadoPor),
                parceriaId, caminho);
    }

    /**
     * Grava o arquivo de um documento do Atlas (rota 54): categoria DOCUMENTO, só PDF ou TXT (as outras extensões dão 415 com a lista
     * dos dois). {@code parceriaId} nulo = arquivo geral da RBS (documento GLOBAL ou de COTA); preenchido, o arquivo é da parceria
     * (documento PARCERIA) e o gatilho do documento confere que as duas pontas batem. Sem aba, de propósito: a marca nunca baixa
     * este arquivo pela rota 18 (o ArquivoRepositorio sabe que ele é de um documento). Chame de dentro de uma transação, como
     * {@link #gravar(byte[], String, String, long, Long)}.
     */
    public Arquivo gravarDocumento(byte[] conteudo, String nomeOriginal, long enviadoPor, Long parceriaId) {
        Preparado pronto = preparar(conteudo, nomeOriginal, Aceitos.DOCUMENTOS);
        String caminho = (parceriaId == null ? "geral" : "parcerias/" + parceriaId) + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, enviado_por)
                        VALUES (:parceriaId, 'DOCUMENTO', :nome, :mime, :tamanho, :caminho, :hash, :enviadoPor)
                        RETURNING\s""" + COLUNAS,
                Map.of("nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(), "tamanho", (long) conteudo.length,
                        "caminho", caminho, "hash", pronto.hash(), "enviadoPor", enviadoPor),
                parceriaId, caminho);
    }

    /**
     * Grava um arquivo avulso de uma parceria (sem entrega) que pertence a uma aba, por exemplo a foto de comprovação de uma
     * aplicação, na aba APLICACOES. [PERMISSAO] A aba importa: o download (rota 18) só confere se a aba está aberta para a
     * marca quando o arquivo tem aba; sem ela, a marca baixaria o arquivo com a aba fechada. O {@code abaCodigo} é um dos 7
     * códigos de aba; um código que não existe, ou uma aba sem parceria, é erro de quem chama (IllegalArgumentException).
     * Os demais erros são os de {@link #gravar(byte[], String, String, long, Long)}.
     */
    public Arquivo gravar(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, Long parceriaId,
                          String abaCodigo) {
        return gravarNaAba(conteudo, nomeOriginal, categoria, enviadoPor, parceriaId, abaCodigo, Aceitos.COMUNS);
    }

    /**
     * Grava a planilha da agência (rota 66, o campo {@code ativacao.planilha_agencia}): um arquivo avulso da parceria, na aba dela
     * (APLICACOES) e na categoria OUTRO, para o download (rota 18) conferir a parceria e a aba. Aceita só {@code .xlsx}, {@code .xls}
     * e {@code .csv}, conferidos pelo conteúdo (413 acima do limite, 415 fora da lista ou com o conteúdo que não bate, 422 se vazio).
     * Chame de dentro de uma transação, como {@link #gravar(byte[], String, String, long, Long)}.
     */
    public Arquivo gravarPlanilha(byte[] conteudo, String nomeOriginal, long enviadoPor, long parceriaId, String abaCodigo) {
        return gravarNaAba(conteudo, nomeOriginal, "OUTRO", enviadoPor, parceriaId, abaCodigo, Aceitos.PLANILHAS);
    }

    private Arquivo gravarNaAba(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, Long parceriaId,
                                String abaCodigo, Aceitos aceitos) {
        if (parceriaId == null) {
            throw new IllegalArgumentException("Arquivo geral da RBS (sem parceria) não pertence a uma aba.");
        }
        Preparado pronto = preparar(conteudo, nomeOriginal, aceitos);
        Integer abaId = jdbc.sql("SELECT id FROM aba WHERE codigo = :codigo").param("codigo", abaCodigo)
                .query(Integer.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("A aba " + abaCodigo + " não existe."));
        String caminho = "parcerias/" + parceriaId + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, enviado_por)
                        VALUES (:parceriaId, :abaId, :categoria, :nome, :mime, :tamanho, :caminho, :hash, :enviadoPor)
                        RETURNING\s""" + COLUNAS,
                Map.of("abaId", abaId, "categoria", categoria, "nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(),
                        "tamanho", (long) conteudo.length, "caminho", caminho, "hash", pronto.hash(),
                        "enviadoPor", enviadoPor),
                parceriaId, caminho);
    }

    /**
     * Grava um arquivo avulso de uma parceria numa aba, com versão: a maior que a parceria já tem nessa categoria (arquivos sem
     * entrega) mais 1. Serve ao plano comercial e ao formulário de ingressos (rotas 70 e 73). [ISOLAMENTO] Quem chama já travou a
     * linha da parceria (FOR UPDATE), para dois envios ao mesmo tempo não pegarem a mesma versão; o índice uq_arquivo_versao_avulso
     * é a segunda defesa. Os erros são os de {@link #gravar(byte[], String, String, long, Long)}.
     */
    public Arquivo gravarVersionadoNaAba(byte[] conteudo, String nomeOriginal, String categoria, long enviadoPor, long parceriaId,
                                         String abaCodigo, String observacao) {
        Preparado pronto = preparar(conteudo, nomeOriginal, Aceitos.COMUNS);
        Integer abaId = jdbc.sql("SELECT id FROM aba WHERE codigo = :codigo").param("codigo", abaCodigo)
                .query(Integer.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("A aba " + abaCodigo + " não existe."));
        String caminho = "parcerias/" + parceriaId + "/" + pronto.nomeNoDisco();
        escrever(caminho, conteudo);
        return inserir("""
                        INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho,
                                             hash_sha256, versao, enviado_por, observacao)
                        VALUES (:parceriaId, :abaId, :categoria, :nome, :mime, :tamanho, :caminho, :hash,
                                coalesce((SELECT max(a.versao) FROM arquivo a WHERE a.parceria_id = :parceriaId
                                          AND a.categoria = :categoria AND a.entrega_id IS NULL), 0) + 1,
                                :enviadoPor, nullif(CAST(:observacao AS text), ''))
                        RETURNING\s""" + COLUNAS,
                Map.of("abaId", abaId, "categoria", categoria, "nome", pronto.nomeOriginal(), "mime", pronto.tipo().mime(),
                        "tamanho", (long) conteudo.length, "caminho", caminho, "hash", pronto.hash(),
                        "enviadoPor", enviadoPor, "observacao", observacao == null ? "" : observacao),
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
                                             tamanho_bytes, caminho, hash_sha256, versao, enviado_por, observacao, enviado_em)
                        SELECT e.parceria_id, e.aba_id, e.id, :categoria, :nome, :mime, :tamanho, :caminho, :hash,
                               greatest(coalesce((SELECT max(a.versao) FROM arquivo a WHERE a.entrega_id = e.id), 0),
                                        (SELECT count(*) FROM arquivo a WHERE a.entrega_id = e.id)
                                        + (SELECT count(*) FROM entrega_conteudo c WHERE c.entrega_id = e.id)) + 1, :enviadoPor,
                               nullif(CAST(:observacao AS text), ''), clock_timestamp()
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

    /**
     * Os tipos que um envio aceita: os comuns, os do documento do Atlas (rota 54), os da planilha da agência (rota 66) e os da
     * importação (rota 109: planilha, Office, PDF, imagem e texto).
     */
    private enum Aceitos {
        COMUNS(null),
        DOCUMENTOS("pdf, txt"),
        PLANILHAS("xlsx, xls, csv"),
        IMPORTACOES("xlsx, xls, csv, docx, doc, pptx, ppt, pdf, txt, png, jpg, jpeg");

        private final String extensoes;

        Aceitos(String extensoes) {
            this.extensoes = extensoes;
        }

        String extensoes() {
            return this == COMUNS ? TipoDeArquivo.listaDeExtensoes() : extensoes;
        }

        boolean aceita(TipoDeArquivo tipo) {
            return switch (this) {
                case COMUNS -> tipo != TipoDeArquivo.TXT;
                case DOCUMENTOS -> tipo == TipoDeArquivo.PDF || tipo == TipoDeArquivo.TXT;
                case PLANILHAS -> tipo == TipoDeArquivo.XLSX || tipo == TipoDeArquivo.XLS || tipo == TipoDeArquivo.CSV;
                case IMPORTACOES -> tipo == TipoDeArquivo.XLSX || tipo == TipoDeArquivo.XLS || tipo == TipoDeArquivo.CSV
                        || tipo == TipoDeArquivo.DOCX || tipo == TipoDeArquivo.DOC || tipo == TipoDeArquivo.PPTX
                        || tipo == TipoDeArquivo.PPT || tipo == TipoDeArquivo.PDF || tipo == TipoDeArquivo.TXT
                        || tipo == TipoDeArquivo.PNG || tipo == TipoDeArquivo.JPG || tipo == TipoDeArquivo.JPEG;
            };
        }
    }

    /** [UPLOAD] Vazio (422), grande demais (413), extensão fora da lista ou conteúdo que não bate com ela (415). */
    private Preparado preparar(byte[] conteudo, String nomeInformado) {
        return preparar(conteudo, nomeInformado, Aceitos.COMUNS);
    }

    /**
     * O mesmo, para cada lista de tipos: a comum (a de {@link TipoDeArquivo#listaDeExtensoes()}, sem o TXT), a do documento
     * do Atlas (só PDF e TXT) e a da planilha da agência (XLSX, XLS e CSV). [ATLAS] O .txt só existe na rota 54, e o .xls só na rota
     * 66: a rota 17 continua recusando os dois.
     */
    private Preparado preparar(byte[] conteudo, String nomeInformado, Aceitos aceitos) {
        if (conteudo == null || conteudo.length == 0) {
            throw new DadosInvalidosException(Map.of("arquivo", "O arquivo está vazio."));
        }
        if (conteudo.length > limiteBytes) {
            throw new ArquivoRecusadoException(HttpStatus.CONTENT_TOO_LARGE,
                    "O arquivo passa do limite de " + descreverLimite() + ". Envie um arquivo menor.");
        }
        String nome = limparNome(nomeInformado);
        TipoDeArquivo tipo = TipoDeArquivo.daExtensao(nome)
                .filter(aceitos::aceita)
                .orElseThrow(() -> new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Este tipo de arquivo não é aceito. Envie um destes: " + aceitos.extensoes() + "."));
        // A planilha da agência (rota 66) e a importação (rota 109) em CSV/TXT aceitam qualquer codificação sem byte nulo: o leitor
        // da importação decodifica UTF-8 ou Latin-1, e o Excel em português grava em ANSI. Nas outras rotas o CSV precisa ser UTF-8
        // (a carga e a entrega leem ou mostram o texto).
        boolean textoEmQualquerCodificacao = (aceitos == Aceitos.PLANILHAS && tipo == TipoDeArquivo.CSV)
                || (aceitos == Aceitos.IMPORTACOES && (tipo == TipoDeArquivo.CSV || tipo == TipoDeArquivo.TXT));
        if (textoEmQualquerCodificacao ? !TipoDeArquivo.semBytesNulos(conteudo) : !tipo.conteudoConfere(conteudo)) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, frasePorConteudo(tipo, conteudo, textoEmQualquerCodificacao));
        }
        if (Ole2Diretorio.ehOfficeAntigo(tipo)) {
            switch (Ole2Diretorio.inspecionar(conteudo)) {
                case MALFORMADO -> throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        frasePorConteudo(tipo, conteudo, false));
                case COM_MACRO -> throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Ole2Diretorio.FRASE_DE_MACRO);
                case SEM_MACRO -> { }
            }
        }
        return new Preparado(nome, tipo, sha256(conteudo), UUID.randomUUID() + "." + tipo.extensao());
    }

    /**
     * A frase do 415 por conteúdo que não bate com a extensão. Planilha que é texto, mas não está em UTF-8 (o Excel grava
     * em ANSI/Latin-1 ou em UTF-16 quando não se escolhe "CSV UTF-8"), recebe a instrução de como salvar de novo; o que não
     * tem cara de texto (um PNG renomeado para .csv, por exemplo) recebe a frase de arquivo renomeado. O CSV da planilha da agência
     * ({@code csvDaPlanilha}) não tem a frase do UTF-8: a codificação não importa lá, e o que o reprova (byte nulo) é arquivo que não
     * é texto de planilha, ou UTF-16.
     */
    private static String frasePorConteudo(TipoDeArquivo tipo, byte[] conteudo, boolean csvDaPlanilha) {
        if (tipo == TipoDeArquivo.CSV && !csvDaPlanilha && TipoDeArquivo.ehTextoQueNaoEUtf8(conteudo)) {
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
