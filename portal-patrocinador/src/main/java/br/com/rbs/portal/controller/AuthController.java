package br.com.rbs.portal.controller;

import br.com.rbs.portal.config.JwtService;
import br.com.rbs.portal.dto.Dtos.LoginRequest;
import br.com.rbs.portal.dto.Dtos.PrimeiroAcessoRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** Login individual. Mesma resposta para e-mail inexistente, sem senha ativada ou senha errada. */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        List<Map<String, Object>> us = jdbc.queryForList(
                "SELECT id, nome, email, senha_hash, perfil, ativo FROM usuario WHERE lower(email) = lower(?)", req.email());
        if (us.isEmpty()) {
            throw credenciaisInvalidas();
        }
        Map<String, Object> u = us.get(0);
        String hash = (String) u.get("senha_hash");
        if (hash == null || !Boolean.TRUE.equals(u.get("ativo")) || !encoder.matches(req.senha(), hash)) {
            throw credenciaisInvalidas();
        }
        long id = ((Number) u.get("id")).longValue();
        String perfil = (String) u.get("perfil");
        String email = (String) u.get("email");

        List<Map<String, Object>> parcerias = jdbc.queryForList("""
                SELECT pa.id AS "parceriaId", pa.patrocinador_id AS "patrocinadorId", p.nome_canonico AS "nome",
                       e.edicao AS "edicao", ct.codigo AS "cota"
                FROM parceria_usuario pu
                JOIN parceria pa ON pa.id = pu.parceria_id
                JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN evento e ON e.id = pa.evento_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                WHERE pu.usuario_id = ? ORDER BY e.edicao DESC, pa.id""", id);

        return Map.of(
                "token", jwt.emitir(id, perfil, email),
                "tipo", "Bearer",
                "expiraEmMinutos", jwt.expiraEmMinutos(),
                "usuario", Map.of("id", id, "nome", u.get("nome"), "email", email, "perfil", perfil),
                "parcerias", parcerias);
    }

    /** Ativação de acesso: só funciona para e-mails que a RBS autorizou previamente. */
    @PostMapping("/primeiro-acesso")
    @Transactional
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> primeiroAcesso(@Valid @RequestBody PrimeiroAcessoRequest req) {
        List<Map<String, Object>> aut = jdbc.queryForList(
                "SELECT parceria_id, vinculo FROM email_autorizado WHERE lower(email) = lower(?)", req.email());
        if (aut.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "E-mail não autorizado pela RBS");
        }
        String hash = encoder.encode(req.senha());
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT id, senha_hash, perfil FROM usuario WHERE lower(email) = lower(?)", req.email());
        long usuarioId;
        if (existente.isEmpty()) {
            usuarioId = jdbc.queryForObject(
                    "INSERT INTO usuario (nome, email, senha_hash, perfil) VALUES (?, ?, ?, 'ADMIN_PATROCINADOR') RETURNING id",
                    Long.class, req.nome(), req.email(), hash);
        } else {
            Map<String, Object> u = existente.get(0);
            if (!"ADMIN_PATROCINADOR".equals(u.get("perfil")) || u.get("senha_hash") != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já possui acesso ativado");
            }
            usuarioId = ((Number) u.get("id")).longValue();
            jdbc.update("UPDATE usuario SET senha_hash = ?, nome = ? WHERE id = ?", hash, req.nome(), usuarioId);
        }
        for (Map<String, Object> a : aut) {
            jdbc.update("INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    a.get("parceria_id"), usuarioId, a.get("vinculo"));
        }
        return Map.of("usuarioId", usuarioId, "parcerias", aut.size());
    }

    private ResponseStatusException credenciaisInvalidas() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
    }
}
