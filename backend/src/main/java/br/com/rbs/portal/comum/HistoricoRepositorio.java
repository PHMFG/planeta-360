package br.com.rbs.portal.comum;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Grava uma linha na tabela historico. Atende os itens 3 e 5 da Entrega 2.
 * [HISTORICO] Só existe INSERT: o gatilho rn_historico_imutavel do banco recusa qualquer alteração depois.
 */
@Repository
public class HistoricoRepositorio {

    private final JdbcClient jdbc;

    public HistoricoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void inserir(Long parceriaId, String entidade, Long entidadeId, AcaoHistorico acao, String valorAnterior,
                        String valorNovo, String detalhe, Long usuarioId, String ip, String userAgent) {
        // O CAST é necessário porque a coluna ip é do tipo inet e o driver envia o texto como varchar.
        jdbc.sql("""
                        INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo,
                                               detalhe, usuario_id, ip, user_agent)
                        VALUES (:parceriaId, :entidade, :entidadeId, :acao, :valorAnterior, :valorNovo,
                                :detalhe, :usuarioId, CAST(:ip AS inet), :userAgent)
                        """)
                .param("parceriaId", parceriaId)
                .param("entidade", entidade)
                .param("entidadeId", entidadeId)
                .param("acao", acao.name())
                .param("valorAnterior", valorAnterior)
                .param("valorNovo", valorNovo)
                .param("detalhe", detalhe)
                .param("usuarioId", usuarioId)
                .param("ip", ip)
                .param("userAgent", userAgent)
                .update();
    }
}
