package br.com.rbs.portal.controller;

import br.com.rbs.portal.service.AcessoService;
import br.com.rbs.portal.service.HistoricoService;
import br.com.rbs.portal.service.RelatorioTextoService;
import br.com.rbs.portal.service.UsuarioLogado;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contrato de integração com IA & Dados e consulta do relatório pelo portal.
 * Fluxo: IA lê (GET dados-relatorio) -> RBS gera (POST relatorio, números vindos da base)
 *        -> RBS valida -> RBS disponibiliza -> patrocinador consulta/extrai (GET relatorio).
 */
@RestController
@RequestMapping("/api")
public class RelatorioController {
    private final JdbcTemplate jdbc;
    private final AcessoService acesso;
    private final HistoricoService historico;
    private final RelatorioTextoService texto;

    public RelatorioController(JdbcTemplate jdbc, AcessoService acesso, HistoricoService historico, RelatorioTextoService texto) {
        this.jdbc = jdbc;
        this.acesso = acesso;
        this.historico = historico;
        this.texto = texto;
    }

    /** Leitura pela trilha IA & Dados (conta com perfil ADMIN_RBS). */
    @GetMapping("/patrocinadores/{id}/dados-relatorio")
    public Map<String, Object> dados(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                     @RequestParam(required = false) Integer edicao) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        long parceriaId = acesso.parceriaDoPatrocinador(u, id, edicao);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("patrocinadorId", id);
        r.put("parceriaId", parceriaId);
        r.put("indicadores", jdbc.queryForList(
                "SELECT indicador_codigo AS \"codigo\", recorte AS \"recorte\", valor AS \"valor\", base_calculo AS \"baseCalculo\" "
              + "FROM vw_indicadores_parceria WHERE parceria_id = ? ORDER BY indicador_codigo, recorte", parceriaId));
        r.put("faltantes", jdbc.queryForList(
                "SELECT tipo AS \"tipo\", detalhe AS \"detalhe\" FROM vw_faltantes WHERE parceria_id = ? ORDER BY tipo, detalhe", parceriaId));
        return r;
    }

    /** Gera uma nova versão (RASCUNHO) direto da base, sem montagem manual: chama fn_gerar_relatorio. */
    @PostMapping("/patrocinadores/{id}/relatorio")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> gerar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                     @RequestParam(required = false) Integer edicao) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        long parceriaId = acesso.parceriaDoPatrocinador(u, id, edicao);
        long relatorioId = jdbc.queryForObject("SELECT fn_gerar_relatorio(?, ?)", Long.class, parceriaId, u.id());
        Map<String, Object> rel = jdbc.queryForMap("SELECT versao, status FROM relatorio WHERE id = ?", relatorioId);
        return Map.of("relatorioId", relatorioId, "parceriaId", parceriaId, "versao", rel.get("versao"), "status", rel.get("status"));
    }

    /** Consulta (JSON) ou extração (?formato=csv). Patrocinador só vê versão DISPONIBILIZADA da própria marca. */
    @GetMapping("/patrocinadores/{id}/relatorio")
    public ResponseEntity<?> consultar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                       @RequestParam(required = false) Integer edicao,
                                       @RequestParam(required = false) Integer versao,
                                       @RequestParam(defaultValue = "json") String formato) {
        UsuarioLogado u = acesso.usuario(jwt);
        long parceriaId = acesso.parceriaDoPatrocinador(u, id, edicao);
        acesso.exigirParceria(u, parceriaId);

        List<Map<String, Object>> rels = jdbc.queryForList("""
                SELECT id, versao, status, gerado_em, validado_em FROM relatorio
                WHERE parceria_id = ? AND (?::int IS NULL OR versao = ?) AND (? OR status = 'DISPONIBILIZADO')
                ORDER BY versao DESC LIMIT 1""", parceriaId, versao, versao, u.rbs());
        if (rels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhum relatório disponível para esta parceria");
        }
        Map<String, Object> rel = rels.get(0);
        long relatorioId = ((Number) rel.get("id")).longValue();

        List<Map<String, Object>> inds = jdbc.queryForList("""
                SELECT i.secao AS "secao", i.nome AS "nome", ri.indicador_codigo AS "codigo", ri.recorte AS "recorte",
                       ri.valor AS "valor", i.unidade AS "unidade", ri.base_calculo AS "baseCalculo", ri.disponivel AS "disponivel"
                FROM relatorio_indicador ri JOIN indicador i ON i.codigo = ri.indicador_codigo
                WHERE ri.relatorio_id = ? ORDER BY i.secao, ri.indicador_codigo, ri.recorte""", relatorioId);

        if ("csv".equalsIgnoreCase(formato)) {
            byte[] csv = csv(inds).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("relatorio-parceria-" + parceriaId + "-v" + rel.get("versao") + ".csv").build().toString())
                    .body(csv);
        }

        Map<String, List<Map<String, Object>>> porSecao = new LinkedHashMap<>();
        for (Map<String, Object> i : inds) {
            porSecao.computeIfAbsent((String) i.get("secao"), k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> secoes = new ArrayList<>();
        porSecao.forEach((secao, lista) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("secao", secao);
            s.put("texto", texto.paraSecao(secao, lista));
            s.put("indicadores", lista);
            secoes.add(s);
        });
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("patrocinadorId", id);
        corpo.put("parceriaId", parceriaId);
        corpo.put("relatorioId", relatorioId);
        corpo.put("versao", rel.get("versao"));
        corpo.put("status", rel.get("status"));
        corpo.put("geradoEm", rel.get("gerado_em"));
        corpo.put("validadoEm", rel.get("validado_em"));
        corpo.put("secoes", secoes);
        return ResponseEntity.ok(corpo);
    }

    /** Revisão humana: a RBS valida antes de qualquer patrocinador ver o relatório. */
    @PostMapping("/relatorios/{id}/validar")
    @Transactional
    public Map<String, Object> validar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, HttpServletRequest http) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        long parceriaId = parceriaDoRelatorio(id);
        int n = jdbc.update("UPDATE relatorio SET status = 'VALIDADO', validado_por = ?, validado_em = now() "
                          + "WHERE id = ? AND status = 'RASCUNHO'", u.id(), id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só relatórios em RASCUNHO podem ser validados");
        }
        historico.registrar(parceriaId, "relatorio", id, "VALIDACAO", "RASCUNHO", "VALIDADO", u.id(), http.getRemoteAddr());
        return Map.of("relatorioId", id, "status", "VALIDADO");
    }

    @PostMapping("/relatorios/{id}/disponibilizar")
    @Transactional
    public Map<String, Object> disponibilizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, HttpServletRequest http) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        long parceriaId = parceriaDoRelatorio(id);
        int n = jdbc.update("UPDATE relatorio SET status = 'DISPONIBILIZADO' WHERE id = ? AND status = 'VALIDADO'", id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só relatórios VALIDADOS podem ser disponibilizados");
        }
        historico.registrar(parceriaId, "relatorio", id, "ALTERACAO_STATUS", "VALIDADO", "DISPONIBILIZADO", u.id(), http.getRemoteAddr());
        return Map.of("relatorioId", id, "status", "DISPONIBILIZADO");
    }

    private long parceriaDoRelatorio(long relatorioId) {
        List<Long> r = jdbc.queryForList("SELECT parceria_id FROM relatorio WHERE id = ?", Long.class, relatorioId);
        if (r.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Relatório não encontrado");
        }
        return r.get(0);
    }

    private static String csv(List<Map<String, Object>> inds) {
        StringBuilder sb = new StringBuilder("\uFEFFsecao;indicador;recorte;valor;unidade;base_calculo;disponivel\n");
        for (Map<String, Object> i : inds) {
            Object v = i.get("valor");
            sb.append(cel(i.get("secao"))).append(';')
              .append(cel(i.get("nome"))).append(';')
              .append(cel(i.get("recorte"))).append(';')
              .append(v == null ? "" : ((BigDecimal) v).stripTrailingZeros().toPlainString()).append(';')
              .append(cel(i.get("unidade"))).append(';')
              .append(cel(i.get("baseCalculo"))).append(';')
              .append(i.get("disponivel")).append('\n');
        }
        return sb.toString();
    }

    private static String cel(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString().replace("\"", "\"\"");
        return s.contains(";") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}
