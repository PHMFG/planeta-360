package br.com.rbs.portal.comum;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A regra central de isolamento: toda rota que recebe o id de uma parceria passa por aqui antes de ler ou gravar.
 * Atende o item 3 da Entrega 2. [ISOLAMENTO] [PERMISSAO] Dois testes, nesta ordem: o perfil pode fazer a operação?
 * O dado é de uma parceria a que o usuário tem acesso?
 */
@Component
public class VerificadorDeAcesso {

    /**
     * A frase do 403 de quem pede uma aba que a cota da parceria INCLUI mas que a RBS ainda não abriu (ou escondeu do menu).
     * A RBS resolve liberando a aba.
     */
    public static final String ABA_NAO_LIBERADA = "Esta aba ainda não foi liberada para a sua parceria.";

    /**
     * A frase do 403 de quem pede uma aba que a cota da parceria NÃO inclui (cota_tipo_aba). Nem a RBS consegue liberar essa
     * aba para esta parceria; é outra situação de "fechada" e por isso tem outra frase.
     */
    public static final String ABA_FORA_DA_COTA = "Esta aba não faz parte da cota da sua parceria.";

    /** A frase do 403 de quem é da RBS e chama uma rota da conversa com o Atlas: só o Admin Patrocinador conversa com ele. */
    public static final String SO_PATROCINADOR_USA_O_ATLAS = "Só o Admin Patrocinador da parceria conversa com o Atlas.";

    /** O estado de uma aba numa parceria: aberta para o patrocinador (liberada E visível) e se a cota a inclui. */
    private record EstadoDaAba(boolean aberta, boolean naCota) {
    }

    private final JdbcClient jdbc;

    public VerificadorDeAcesso(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * [ISOLAMENTO] A equipe da RBS passa em qualquer parceria que exista. O patrocinador só passa se tiver vínculo
     * vigente em parceria_usuario. Quem não passa recebe 404, o mesmo erro de "não existe": assim um patrocinador não
     * descobre quais ids de parceria existem. A consulta roda A CADA PEDIDO e nada é guardado na sessão, porque a
     * revogação do e-mail (que apaga o vínculo no banco) tem de valer na hora, até para quem já estava logado.
     */
    public void exigirParceria(UsuarioAutenticado usuario, long parceriaId) {
        boolean permitido = usuario.ehRbs() ? parceriaExiste(parceriaId) : temVinculoVigente(usuario.id(), parceriaId);
        if (!permitido) {
            throw new NaoEncontradoException("Não encontramos esta parceria.");
        }
    }

    /** [PERMISSAO] Rotas que só a equipe da RBS pode usar: o patrocinador recebe 403. */
    public void exigirRbs(UsuarioAutenticado usuario) {
        if (!usuario.ehRbs()) {
            throw new SemPermissaoException("Esta operação é restrita à equipe da RBS.");
        }
    }

    /** [PERMISSAO] Rotas que só o Admin Patrocinador usa (a RBS não preenche pelo patrocinador): a RBS recebe 403 com a frase dada. */
    public void exigirPatrocinador(UsuarioAutenticado usuario, String mensagem) {
        if (usuario.ehRbs()) {
            throw new SemPermissaoException(mensagem);
        }
    }

    /**
     * [PERMISSAO] O patrocinador só passa numa aba liberada para a parceria dele; a RBS passa em qualquer aba. Como no
     * contrato (objeto Aba), a aba só conta como liberada se estiver liberada E visível. O 403 diz o motivo: aba que a
     * cota não inclui ({@link #ABA_FORA_DA_COTA}) ou aba da cota que a RBS ainda não abriu ({@link #ABA_NAO_LIBERADA}).
     * Chamar depois de exigirParceria.
     */
    public void exigirAbaLiberada(UsuarioAutenticado usuario, long parceriaId, String codigoDaAba) {
        if (usuario.ehRbs()) {
            return;
        }
        EstadoDaAba estado = estadoDaAba(parceriaId, codigoDaAba);
        if (!estado.aberta()) {
            throw new SemPermissaoException(estado.naCota() ? ABA_NAO_LIBERADA : ABA_FORA_DA_COTA);
        }
    }

    /**
     * [PERMISSAO] Quem pode ver o que pertence a uma aba: a RBS sempre; o patrocinador só se a aba estiver aberta (liberada E
     * visível) para a parceria dele. É a versão de "sim ou não" do {@link #exigirAbaLiberada}, para rotas que não negam a
     * resposta inteira, só escondem a parte da aba fechada (por exemplo, a consolidação).
     */
    public boolean podeVerAba(UsuarioAutenticado usuario, long parceriaId, String codigoDaAba) {
        return usuario.ehRbs() || estadoDaAba(parceriaId, codigoDaAba).aberta();
    }

    /**
     * [PERMISSAO] Os códigos das abas abertas para este usuário nesta parceria, na ordem do menu: para o patrocinador, as abas
     * liberadas E visíveis (a mesma regra de {@link #podeVerAba}); para a RBS, todas. É o que o Atlas leva no contexto.
     */
    public Set<String> abasAbertas(UsuarioAutenticado usuario, long parceriaId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT a.codigo FROM aba a
                        LEFT JOIN parceria_aba pab ON pab.parceria_id = :parceriaId AND pab.aba_id = a.id
                        WHERE CAST(:rbs AS boolean) OR coalesce(pab.liberada AND pab.visivel, FALSE)
                        ORDER BY a.ordem
                        """)
                .param("parceriaId", parceriaId)
                .param("rbs", usuario.ehRbs())
                .query(String.class)
                .list());
    }

    /**
     * Aba ou parceria que não existem contam como fechadas (e dentro da cota, para a frase ser a de "ainda não liberada").
     * A parceria sem a linha da aba em parceria_aba também conta como fechada.
     */
    private EstadoDaAba estadoDaAba(long parceriaId, String codigoDaAba) {
        return jdbc.sql("""
                        SELECT coalesce(pab.liberada AND pab.visivel, FALSE) AS aberta,
                               EXISTS (SELECT 1 FROM cota_tipo_aba cta
                                       WHERE cta.cota_tipo_id = pa.cota_tipo_id AND cta.aba_id = a.id) AS na_cota
                        FROM parceria pa
                        JOIN aba a ON a.codigo = :aba
                        LEFT JOIN parceria_aba pab ON pab.parceria_id = pa.id AND pab.aba_id = a.id
                        WHERE pa.id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .param("aba", codigoDaAba)
                .query(EstadoDaAba.class)
                .optional()
                .orElse(new EstadoDaAba(false, true));
    }

    // O usuário precisa estar ativo: desativar uma conta derruba o acesso mesmo com o vínculo ainda gravado.
    private boolean temVinculoVigente(long usuarioId, long parceriaId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM parceria_usuario pu
                            JOIN usuario u ON u.id = pu.usuario_id
                            WHERE pu.parceria_id = :parceriaId AND pu.usuario_id = :usuarioId AND u.ativo)
                        """)
                .param("parceriaId", parceriaId)
                .param("usuarioId", usuarioId)
                .query(Boolean.class)
                .single();
    }

    private boolean parceriaExiste(long parceriaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM parceria WHERE id = :parceriaId)")
                .param("parceriaId", parceriaId)
                .query(Boolean.class)
                .single();
    }
}
