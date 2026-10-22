package br.com.rbs.portal.controller;

import br.com.rbs.portal.dto.Dtos.NovaEntregaRequest;
import br.com.rbs.portal.dto.Dtos.StatusRequest;
import br.com.rbs.portal.service.AcessoService;
import br.com.rbs.portal.service.HistoricoService;
import br.com.rbs.portal.service.UsuarioLogado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Materiais e acompanhamento: entregas, envio de arquivos com versão, status e histórico. */
@RestController
@RequestMapping("/api")
public class MaterialController {
    private static final Set<String> STATUS = Set.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "ENVIADO", "EM_ANALISE",
            "AJUSTE_SOLICITADO", "APROVADO", "NAO_APLICAVEL");
    /** Status em que o patrocinador ainda pode enviar (ou reenviar) arquivo. */
    private static final Set<String> STATUS_ENVIO = Set.of("AGUARDANDO_ENVIO", "ENVIADO", "AJUSTE_SOLICITADO");
    private static final Set<String> CATEGORIAS = Set.of("PLANO_COMERCIAL", "MATERIAL_MARCA", "MATERIAL_MIDIA",
            "RELATORIO_VEICULACAO", "COMPROVACAO", "PESQUISA", "CONTRATO", "DOCUMENTO_INGRESSOS",
            "RELATORIO_PARCERIA", "OUTRO");
    /** O que o Admin Patrocinador pode enviar (tabela de permissões da Sprint 1). */
    private static final Set<String> CATEGORIAS_PATROCINADOR = Set.of("MATERIAL_MARCA", "MATERIAL_MIDIA",
            "DOCUMENTO_INGRESSOS", "COMPROVACAO", "OUTRO");

    private final JdbcTemplate jdbc;
    private final AcessoService acesso;
    private final HistoricoService historico;
    private final Path base;

    public MaterialController(JdbcTemplate jdbc, AcessoService acesso, HistoricoService historico,
                              @Value("${portal.armazenamento}") String dir) {
        this.jdbc = jdbc;
        this.acesso = acesso;
        this.historico = historico;
        this.base = Path.of(dir).toAbsolutePath().normalize();
    }

    // ---------------------------------------------------------------- entregas

    @PostMapping("/parcerias/{id}/entregas")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> criarEntrega(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                            @Valid @RequestBody NovaEntregaRequest req, HttpServletRequest http) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        acesso.exigirParceria(u, id);
        String status = req.status() == null ? "NAO_INICIADO" : req.status().toUpperCase();
        if (!STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: " + status);
        }
        List<Integer> aba = jdbc.queryForList("SELECT id FROM aba WHERE codigo = ?", Integer.class, req.abaCodigo().toUpperCase());
        if (aba.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aba inválida: " + req.abaCodigo());
        }
        long entregaId = jdbc.queryForObject(
                "INSERT INTO entrega (parceria_id, aba_id, titulo, descricao, status, prazo) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, id, aba.get(0), req.titulo(), req.descricao(), status, req.prazo());
        historico.registrar(id, "entrega", entregaId, "CRIACAO", null, req.titulo() + " · " + status, u.id(), http.getRemoteAddr());
        return Map.of("entregaId", entregaId, "status", status);
    }

    @GetMapping("/parcerias/{id}/entregas")
    public List<Map<String, Object>> entregas(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                              @RequestParam(required = false) String aba) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirParceria(u, id);
        return jdbc.queryForList("""
                SELECT e.id AS "id", a.codigo AS "aba", e.titulo AS "titulo", e.status AS "status", e.prazo AS "prazo",
                       e.atualizado_em AS "atualizadoEm",
                       (SELECT count(*) FROM arquivo ar WHERE ar.entrega_id = e.id) AS "arquivos"
                FROM entrega e JOIN aba a ON a.id = e.aba_id
                WHERE e.parceria_id = ? AND (?::text IS NULL OR a.codigo = ?)
                ORDER BY a.ordem, e.id""", id, aba == null ? null : aba.toUpperCase(), aba == null ? null : aba.toUpperCase());
    }

    /** Só o Admin RBS altera status (tabela de permissões). O envio do patrocinador muda para ENVIADO sozinho. */
    @PatchMapping("/entregas/{id}/status")
    @Transactional
    public Map<String, Object> alterarStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                             @Valid @RequestBody StatusRequest req, HttpServletRequest http) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        long parceriaId = acesso.parceriaDaEntrega(u, id);
        String novo = req.status().toUpperCase();
        if (!STATUS.contains(novo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: " + novo);
        }
        String anterior = jdbc.queryForObject("SELECT status FROM entrega WHERE id = ?", String.class, id);
        if (!novo.equals(anterior)) {
            jdbc.update("UPDATE entrega SET status = ?, atualizado_em = now() WHERE id = ?", novo, id);
            historico.registrar(parceriaId, "entrega", id, "ALTERACAO_STATUS", anterior, novo, u.id(), http.getRemoteAddr());
        }
        return Map.of("entregaId", id, "statusAnterior", anterior, "status", novo);
    }

    // ---------------------------------------------------------------- arquivos

    @PostMapping(path = "/entregas/{id}/arquivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> enviar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "MATERIAL_MARCA") String categoria,
                                      HttpServletRequest http) throws IOException {
        UsuarioLogado u = acesso.usuario(jwt);
        long parceriaId = acesso.parceriaDaEntrega(u, id);
        acesso.exigirParceria(u, parceriaId);
        categoria = categoria.toUpperCase();
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo vazio");
        }
        if (!CATEGORIAS.contains(categoria)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria inválida: " + categoria);
        }
        Map<String, Object> entrega = jdbc.queryForMap("SELECT aba_id, status FROM entrega WHERE id = ?", id);
        int abaId = ((Number) entrega.get("aba_id")).intValue();
        String status = (String) entrega.get("status");

        if (!u.rbs()) {
            if (!CATEGORIAS_PATROCINADOR.contains(categoria)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este tipo de arquivo é enviado pela RBS");
            }
            List<Boolean> liberada = jdbc.queryForList(
                    "SELECT liberada FROM parceria_aba WHERE parceria_id = ? AND aba_id = ?", Boolean.class, parceriaId, abaId);
            if (liberada.isEmpty() || !liberada.get(0)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta aba não está liberada para a cota da parceria");
            }
            if (!STATUS_ENVIO.contains(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A entrega está em " + status + " e não aceita novo envio");
            }
        }

        String nomeOriginal = nomeSeguro(file.getOriginalFilename());
        String relativo = "parceria-" + parceriaId + "/entrega-" + id + "/" + UUID.randomUUID() + extensao(nomeOriginal);
        Path destino = base.resolve(relativo).normalize();
        if (!destino.startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome de arquivo inválido");
        }
        Files.createDirectories(destino.getParent());
        String hash = gravar(file, destino);
        try {
            int versao = jdbc.queryForObject("SELECT coalesce(max(versao), 0) + 1 FROM arquivo WHERE entrega_id = ?", Integer.class, id);
            String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            long arquivoId = jdbc.queryForObject("""
                    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes,
                                         caminho, hash_sha256, versao, enviado_por)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    Long.class, parceriaId, abaId, id, categoria, nomeOriginal, mime, file.getSize(), relativo, hash, versao, u.id());

            String ip = http.getRemoteAddr();
            historico.registrar(parceriaId, "entrega", id, versao == 1 ? "ENVIO_ARQUIVO" : "NOVA_VERSAO", null,
                    nomeOriginal + " (v" + versao + ")", u.id(), ip);
            String statusFinal = status;
            if (!u.rbs() && !"ENVIADO".equals(status)) {
                jdbc.update("UPDATE entrega SET status = 'ENVIADO', atualizado_em = now() WHERE id = ?", id);
                historico.registrar(parceriaId, "entrega", id, "ALTERACAO_STATUS", status, "ENVIADO", u.id(), ip);
                statusFinal = "ENVIADO";
            } else {
                jdbc.update("UPDATE entrega SET atualizado_em = now() WHERE id = ?", id);
            }
            return Map.of("arquivoId", arquivoId, "versao", versao, "status", statusFinal, "sha256", hash);
        } catch (DuplicateKeyException e) {
            Files.deleteIfExists(destino);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Outro envio simultâneo para esta entrega. Tente novamente");
        } catch (RuntimeException e) {
            Files.deleteIfExists(destino);
            throw e;
        }
    }

    /** Consulta de materiais: quem enviou e quando, por versão (mais recente primeiro). */
    @GetMapping("/entregas/{id}/arquivos")
    public List<Map<String, Object>> arquivosDaEntrega(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirParceria(u, acesso.parceriaDaEntrega(u, id));
        return jdbc.queryForList("""
                SELECT a.id AS "id", a.nome_original AS "nomeOriginal", a.categoria AS "categoria", a.versao AS "versao",
                       a.tamanho_bytes AS "tamanhoBytes", us.nome AS "autor", a.enviado_em AS "enviadoEm"
                FROM arquivo a JOIN usuario us ON us.id = a.enviado_por
                WHERE a.entrega_id = ? ORDER BY a.versao DESC""", id);
    }

    @GetMapping("/arquivos/{id}/download")
    public ResponseEntity<Resource> baixar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UsuarioLogado u = acesso.usuario(jwt);
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT parceria_id, caminho, nome_original, mime_type FROM arquivo WHERE id = ?", id);
        if (r.isEmpty()) {
            throw u.rbs() ? new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo não encontrado") : acesso.negado();
        }
        Map<String, Object> a = r.get(0);
        if (a.get("parceria_id") == null) {
            acesso.exigirRbs(u);            // arquivo geral da RBS
        } else {
            acesso.exigirParceria(u, ((Number) a.get("parceria_id")).longValue());
        }
        Path f = base.resolve((String) a.get("caminho")).normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo físico não encontrado no armazenamento");
        }
        MediaType tipo;
        try {
            tipo = MediaType.parseMediaType((String) a.get("mime_type"));
        } catch (InvalidMediaTypeException e) {
            tipo = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename((String) a.get("nome_original"), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(f));
    }

    // ---------------------------------------------------------------- histórico

    /** Histórico com autor e data. Patrocinador só enxerga o da própria parceria. */
    @GetMapping("/parcerias/{id}/historico")
    public List<Map<String, Object>> historico(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                               @RequestParam(required = false) Long entregaId) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirParceria(u, id);
        return jdbc.queryForList("""
                SELECT h.id AS "id", h.entidade AS "entidade", h.entidade_id AS "entidadeId", h.acao AS "acao",
                       h.valor_anterior AS "valorAnterior", h.valor_novo AS "valorNovo",
                       us.nome AS "autor", h.ocorrido_em AS "ocorridoEm"
                FROM historico h JOIN usuario us ON us.id = h.usuario_id
                WHERE h.parceria_id = ? AND (?::bigint IS NULL OR (h.entidade = 'entrega' AND h.entidade_id = ?))
                ORDER BY h.ocorrido_em DESC, h.id DESC LIMIT 200""", id, entregaId, entregaId);
    }

    // ---------------------------------------------------------------- utilitários

    private String gravar(MultipartFile file, Path destino) throws IOException {
        try (InputStream in = file.getInputStream();
             DigestInputStream din = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            Files.copy(din, destino, StandardCopyOption.REPLACE_EXISTING);
            return HexFormat.of().formatHex(din.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nomeSeguro(String original) {
        if (original == null || original.isBlank()) {
            return "arquivo";
        }
        String n = original.replaceAll("[\\\\/]", "_").trim();
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }

    private static String extensao(String nome) {
        int i = nome.lastIndexOf('.');
        if (i < 0) {
            return "";
        }
        String ext = nome.substring(i);
        return ext.matches("\\.[A-Za-z0-9]{1,8}") ? ext.toLowerCase() : "";
    }
}
