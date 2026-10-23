package br.com.rbs.portal.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regra central de isolamento. Todo endpoint passa por aqui, nesta ordem:
 * 1) o perfil pode fazer a operação; 2) o dado pertence à parceria do usuário.
 * Para o patrocinador, "não existe" e "é de outro" respondem igual (403), para não revelar IDs alheios.
 */
@Service
public class AcessoService {
    private final JdbcTemplate jdbc;

    public AcessoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UsuarioLogado usuario(Jwt jwt) {
        return new UsuarioLogado(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("perfil"));
    }

    public void exigirRbs(UsuarioLogado u) {
        if (!u.rbs()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operação restrita ao Admin RBS");
        }
    }

    public void exigirParceria(UsuarioLogado u, long parceriaId) {
        if (u.rbs()) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM parceria WHERE id = ?", Integer.class, parceriaId);
            if (n == null || n == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parceria não encontrada");
            }
            return;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM parceria_usuario WHERE parceria_id = ? AND usuario_id = ?",
                Integer.class, parceriaId, u.id());
        if (n == null || n == 0) {
            throw negado();
        }
    }

    public long parceriaDaEntrega(UsuarioLogado u, long entregaId) {
        List<Long> r = jdbc.queryForList("SELECT parceria_id FROM entrega WHERE id = ?", Long.class, entregaId);
        if (r.isEmpty()) {
            throw u.rbs() ? new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrega não encontrada") : negado();
        }
        return r.get(0);
    }

    /** Resolve a parceria de um patrocinador (a mais recente, ou a da edição pedida). */
    public long parceriaDoPatrocinador(UsuarioLogado u, long patrocinadorId, Integer edicao) {
        List<Long> r = jdbc.queryForList(
                "SELECT pa.id FROM parceria pa JOIN evento e ON e.id = pa.evento_id "
              + "WHERE pa.patrocinador_id = ? AND (?::int IS NULL OR e.edicao = ?) ORDER BY e.edicao DESC",
                Long.class, patrocinadorId, edicao, edicao);
        if (r.isEmpty()) {
            throw u.rbs() ? new ResponseStatusException(HttpStatus.NOT_FOUND, "Parceria não encontrada") : negado();
        }
        return r.get(0);
    }

    public ResponseStatusException negado() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso negado a este registro");
    }
}
