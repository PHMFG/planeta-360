package br.com.rbs.portal.controller;

import br.com.rbs.portal.service.AcessoService;
import br.com.rbs.portal.service.UsuarioLogado;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disponibiliza os dados consolidados pela trilha IA & Dados (views e funções do banco):
 * mídia previsto x exibido, faltantes e cargas. Não recalcula nada: lê o que o banco já trata.
 */
@RestController
@RequestMapping("/api")
public class ConsolidacaoController {
    private final JdbcTemplate jdbc;
    private final AcessoService acesso;

    public ConsolidacaoController(JdbcTemplate jdbc, AcessoService acesso) {
        this.jdbc = jdbc;
        this.acesso = acesso;
    }

    @GetMapping("/parcerias/{id}/consolidacao")
    public Map<String, Object> consolidacao(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UsuarioLogado u = acesso.usuario(jwt);
        acesso.exigirParceria(u, id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("midiaPorUnidade", jdbc.queryForList(
                "SELECT unidade AS \"unidade\", previsto AS \"previsto\", exibido AS \"exibido\", pct_entrega AS \"pctEntrega\" "
              + "FROM vw_midia_unidade WHERE parceria_id = ? ORDER BY unidade", id));
        r.put("midiaPorVeiculo", jdbc.queryForList(
                "SELECT veiculo AS \"veiculo\", unidade AS \"unidade\", previsto AS \"previsto\", exibido AS \"exibido\", "
              + "pct_entrega AS \"pctEntrega\" FROM vw_midia_veiculo WHERE parceria_id = ? ORDER BY previsto DESC", id));
        r.put("entregas", jdbc.queryForList(
                "SELECT total AS \"total\", aprovadas AS \"aprovadas\", pendentes AS \"pendentes\", nao_aplicaveis AS \"naoAplicaveis\", "
              + "pct_concluidas AS \"pctConcluidas\" FROM vw_entregas_resumo WHERE parceria_id = ?", id));
        r.put("aplicacoes", jdbc.queryForList(
                "SELECT previstas AS \"previstas\", comprovadas AS \"comprovadas\", fotos AS \"fotos\" "
              + "FROM vw_aplicacoes WHERE parceria_id = ?", id));
        r.put("faltantes", jdbc.queryForList(
                "SELECT tipo AS \"tipo\", detalhe AS \"detalhe\" FROM vw_faltantes WHERE parceria_id = ? ORDER BY tipo, detalhe", id));
        return r;
    }

    // ---------------- cargas de planilhas (somente Admin RBS)

    @GetMapping("/cargas")
    public List<Map<String, Object>> cargas(@AuthenticationPrincipal Jwt jwt) {
        acesso.exigirRbs(acesso.usuario(jwt));
        return jdbc.queryForList("""
                SELECT id AS "id", tipo AS "tipo", executada_em AS "executadaEm", linhas_lidas AS "lidas",
                       linhas_inseridas AS "inseridas", linhas_duplicadas AS "duplicadas", linhas_rejeitadas AS "rejeitadas"
                FROM carga ORDER BY id""");
    }

    @GetMapping("/cargas/{id}/rejeicoes")
    public List<Map<String, Object>> rejeicoes(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        acesso.exigirRbs(acesso.usuario(jwt));
        return jdbc.queryForList(
                "SELECT linha AS \"linha\", campo AS \"campo\", motivo AS \"motivo\", conteudo AS \"conteudo\" "
              + "FROM carga_rejeicao WHERE carga_id = ? ORDER BY linha", id);
    }

    /**
     * Processa uma carga que já está na área de staging (stg_veiculacao): valida, vincula, deduplica
     * e devolve lidas/inseridas/duplicadas/rejeitadas. Chamada fora de transação de propósito:
     * a função usa tabela temporária ON COMMIT DROP.
     */
    @PostMapping("/cargas/{id}/processar")
    public Map<String, Object> processar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                         @RequestParam long eventoId) {
        acesso.exigirRbs(acesso.usuario(jwt));
        return jdbc.queryForMap("SELECT lidas, inseridas, duplicadas, rejeitadas FROM fn_carregar_veiculacoes(?, ?)", id, eventoId);
    }
}
