package br.com.rbs.portal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Trilha de auditoria: quem fez o quê, quando e de onde (tabela historico). */
@Service
public class HistoricoService {
    private final JdbcTemplate jdbc;

    public HistoricoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(Long parceriaId, String entidade, long entidadeId, String acao,
                          String anterior, String novo, long usuarioId, String ip) {
        jdbc.update("INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, usuario_id, ip) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::inet)",
                parceriaId, entidade, entidadeId, acao, anterior, novo, usuarioId, ip);
    }
}
