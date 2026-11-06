package br.com.rbs.portal.acesso;

import br.com.rbs.portal.acesso.AcessoDtos.AbaDto;
import br.com.rbs.portal.acesso.AcessoDtos.CotaDto;
import br.com.rbs.portal.acesso.AcessoDtos.EventoDto;
import br.com.rbs.portal.acesso.AcessoDtos.ParceriaDoUsuarioDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas do login: as parcerias do usuário com cota, evento e abas, e a regra "esta pessoa ainda tem acesso?".
 * Atende o item 3 da Entrega 2. [PERMISSAO] Tudo é lido do banco a cada chamada; nada fica guardado na sessão.
 */
@Repository
public class AcessoRepositorio {

    private final JdbcClient jdbc;

    public AcessoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * [PERMISSAO] Uma conta "tem acesso" quando está ativa, já definiu a senha e, se for de patrocinador, ainda tem
     * pelo menos um vínculo com parceria. Revogar o e-mail apaga o vínculo (gatilho rn_email_revogado), então um
     * e-mail revogado em todas as parcerias deixa de entrar. A RBS não depende de vínculo.
     */
    public boolean temAcesso(long usuarioId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT u.ativo AND u.senha_hash IS NOT NULL
                                                AND (u.perfil = 'ADMIN_RBS'
                                                     OR EXISTS (SELECT 1 FROM parceria_usuario pu WHERE pu.usuario_id = u.id))
                                           FROM usuario u WHERE u.id = :usuarioId), FALSE)
                        """)
                .param("usuarioId", usuarioId)
                .query(Boolean.class)
                .single();
    }

    /**
     * As parcerias do usuário, da edição mais nova para a mais antiga, cada uma com as 7 abas.
     * Uma aba só conta como liberada se estiver liberada E visível (parceria_aba.liberada e visivel).
     * vinculo = MARCA ou AGENCIA, de parceria_usuario (como quem está logado entra nesta parceria).
     */
    public List<ParceriaDoUsuarioDto> parceriasDoUsuario(long usuarioId) {
        record LinhaParceria(Long id, String patrocinador, String cotaCodigo, String cotaNome,
                             Long eventoId, String eventoNome, Integer edicao, String vinculo,
                             String atendimentoNome, String atendimentoEmail) {
        }
        List<LinhaParceria> linhas = jdbc.sql("""
                        SELECT pa.id, pat.nome_canonico AS patrocinador, ct.codigo AS cota_codigo, ct.nome AS cota_nome,
                               e.id AS evento_id, e.nome AS evento_nome, e.edicao, pu.vinculo,
                               pa.atendimento_nome, pa.atendimento_email
                        FROM parceria_usuario pu
                        JOIN parceria pa ON pa.id = pu.parceria_id
                        JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        JOIN evento e ON e.id = pa.evento_id
                        WHERE pu.usuario_id = :usuarioId
                        ORDER BY e.edicao DESC, pa.id
                        """)
                .param("usuarioId", usuarioId)
                .query(LinhaParceria.class)
                .list();
        if (linhas.isEmpty()) {
            return List.of();
        }

        List<Long> ids = linhas.stream().map(LinhaParceria::id).toList();
        record LinhaAba(Long parceriaId, String codigo, String nome, int ordem, boolean liberada, boolean incluidaNaCota,
                          boolean visivel) {
        }
        List<LinhaAba> abas = jdbc.sql("""
                        SELECT pab.parceria_id, a.codigo, a.nome, a.ordem, (pab.liberada AND pab.visivel) AS liberada, pab.visivel,
                               EXISTS (SELECT 1 FROM cota_tipo_aba cta
                                       WHERE cta.cota_tipo_id = pa.cota_tipo_id AND cta.aba_id = pab.aba_id) AS incluida_na_cota
                        FROM parceria_aba pab
                        JOIN parceria pa ON pa.id = pab.parceria_id
                        JOIN aba a ON a.id = pab.aba_id
                        WHERE pab.parceria_id IN (:ids)
                        ORDER BY pab.parceria_id, a.ordem
                        """)
                .param("ids", ids)
                .query(LinhaAba.class)
                .list();

        Map<Long, List<AbaDto>> abasPorParceria = new LinkedHashMap<>();
        for (LinhaAba aba : abas) {
            abasPorParceria.computeIfAbsent(aba.parceriaId(), k -> new ArrayList<>())
                    .add(new AbaDto(aba.codigo(), aba.nome(), aba.ordem(), aba.liberada(), aba.incluidaNaCota(), aba.visivel()));
        }
        return linhas.stream()
                .map(l -> new ParceriaDoUsuarioDto(l.id(), l.patrocinador(), new CotaDto(l.cotaCodigo(), l.cotaNome()),
                        new EventoDto(l.eventoId(), l.eventoNome(), l.edicao()),
                        abasPorParceria.getOrDefault(l.id(), List.of()), l.vinculo(), l.atendimentoNome(),
                        l.atendimentoEmail()))
                .toList();
    }
}
