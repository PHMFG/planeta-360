package br.com.rbs.portal.controller;

import br.com.rbs.portal.dto.Dtos.EmailAutorizadoRequest;
import br.com.rbs.portal.dto.Dtos.NovoPatrocinadorRequest;
import br.com.rbs.portal.service.AcessoService;
import br.com.rbs.portal.service.HistoricoService;
import br.com.rbs.portal.service.UsuarioLogado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Cadastro de patrocinadores, cotas e e-mails autorizados (Admin RBS); consulta da própria parceria. */
@RestController
@RequestMapping("/api")
public class PatrocinadorController {
    /** Hipótese do documento da Sprint 1: abas liberadas por cota (ordem das abas). A confirmar com a RBS. */
    private static final Map<String, String> ABAS_POR_COTA = Map.of(
            "MASTER", "1,2,3,4,5,6,7",
            "SUBCOTA_G", "1,2,3,4,6,7",
            "SUBCOTA", "1,3,6");
    private static final Set<String> VINCULOS = Set.of("MARCA", "AGENCIA");

    private final JdbcTemplate jdbc;
    private final AcessoService acesso;
    private final HistoricoService historico;

    public PatrocinadorController(JdbcTemplate jdbc, AcessoService acesso, HistoricoService historico) {
        this.jdbc = jdbc;
        this.acesso = acesso;
        this.historico = historico;
    }

    @PostMapping("/patrocinadores")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> cadastrar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NovoPatrocinadorRequest req,
                                         HttpServletRequest http) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        String cota = req.cota().toUpperCase();
        List<Integer> cotaIds = jdbc.queryForList("SELECT id FROM cota_tipo WHERE codigo = ?", Integer.class, cota);
        if (cotaIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cota inválida. Use MASTER, SUBCOTA_G ou SUBCOTA");
        }
        Integer evento = jdbc.queryForObject("SELECT count(*) FROM evento WHERE id = ?", Integer.class, req.eventoId());
        if (evento == null || evento == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento não encontrado");
        }
        long patrocinadorId;
        try {
            patrocinadorId = jdbc.queryForObject(
                    "INSERT INTO patrocinador (nome_canonico, segmento) VALUES (?, ?) RETURNING id",
                    Long.class, req.nome().trim(), req.segmento());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um patrocinador com este nome");
        }
        long parceriaId = jdbc.queryForObject(
                "INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, nome_produto) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, req.eventoId(), patrocinadorId, cotaIds.get(0), req.nomeProduto());
        // O menu mostra as 7 abas para todos; a cota define quais ficam liberadas para preenchimento.
        jdbc.update("INSERT INTO parceria_aba (parceria_id, aba_id, visivel, liberada) "
                  + "SELECT ?, a.id, TRUE, a.ordem IN (" + ABAS_POR_COTA.get(cota) + ") FROM aba a", parceriaId);
        historico.registrar(parceriaId, "parceria", parceriaId, "CRIACAO", null,
                req.nome().trim() + " · cota " + cota, u.id(), http.getRemoteAddr());
        return Map.of("patrocinadorId", patrocinadorId, "parceriaId", parceriaId, "cota", cota);
    }

    /** Admin RBS vê todas as parcerias; Admin Patrocinador, só as suas. */
    @GetMapping("/patrocinadores")
    public List<Map<String, Object>> listar(@AuthenticationPrincipal Jwt jwt) {
        UsuarioLogado u = acesso.usuario(jwt);
        String base = """
                SELECT pa.id AS "parceriaId", p.id AS "patrocinadorId", p.nome_canonico AS "nome", ct.codigo AS "cota",
                       e.edicao AS "edicao", pa.situacao AS "situacao", pa.nome_produto AS "produto"
                FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id JOIN evento e ON e.id = pa.evento_id
                """;
        if (u.rbs()) {
            return jdbc.queryForList(base + " ORDER BY e.edicao DESC, p.nome_canonico");
        }
        return jdbc.queryForList(base + " WHERE pa.id IN (SELECT parceria_id FROM parceria_usuario WHERE usuario_id = ?)"
                + " ORDER BY e.edicao DESC, p.nome_canonico", u.id());
    }

    /** Dados da parceria + o menu das 7 abas (com o que está visível e liberado). */
    @GetMapping("/parcerias/{id}")
    public Map<String, Object> detalhe(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirParceria(u, id);
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT pa.id AS "parceriaId", p.id AS "patrocinadorId", p.nome_canonico AS "nome", ct.codigo AS "cota",
                       e.nome AS "evento", e.edicao AS "edicao", pa.situacao AS "situacao", pa.nome_produto AS "produto"
                FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id JOIN evento e ON e.id = pa.evento_id
                WHERE pa.id = ?""", id);
        List<Map<String, Object>> abas = jdbc.queryForList("""
                SELECT a.codigo AS "codigo", a.nome AS "nome", a.ordem AS "ordem", a.prioridade AS "prioridade",
                       pb.visivel AS "visivel", pb.liberada AS "liberada"
                FROM parceria_aba pb JOIN aba a ON a.id = pb.aba_id WHERE pb.parceria_id = ? ORDER BY a.ordem""", id);
        return Map.of("parceria", p, "abas", abas);
    }

    @PostMapping("/parcerias/{id}/emails-autorizados")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> autorizarEmail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                              @Valid @RequestBody EmailAutorizadoRequest req) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        acesso.exigirParceria(u, id);
        String vinculo = req.vinculo().toUpperCase();
        if (!VINCULOS.contains(vinculo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vínculo inválido. Use MARCA ou AGENCIA");
        }
        try {
            long novo = jdbc.queryForObject(
                    "INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, id, req.email().trim(), vinculo, u.id());
            return Map.of("id", novo, "email", req.email().trim(), "vinculo", vinculo);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já autorizado nesta parceria");
        }
    }

    @GetMapping("/parcerias/{id}/emails-autorizados")
    public List<Map<String, Object>> emails(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirRbs(u);
        acesso.exigirParceria(u, id);
        return jdbc.queryForList("""
                SELECT ea.email AS "email", ea.vinculo AS "vinculo", ea.autorizado_em AS "autorizadoEm",
                       (SELECT count(*) > 0 FROM usuario us WHERE lower(us.email) = lower(ea.email) AND us.senha_hash IS NOT NULL) AS "acessoAtivado"
                FROM email_autorizado ea WHERE ea.parceria_id = ? ORDER BY ea.email""", id);
    }
}
