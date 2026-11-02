package br.com.rbs.portal.atlas.contexto;

import br.com.rbs.portal.atlas.diagnostico.Traco;
import br.com.rbs.portal.atlas.jev.JevDoTurno;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * Quem está falando com o Atlas, em qual parceria e em qual conversa, montado no servidor e nunca por argumento vindo de fora.
 * [ATLAS] [ISOLAMENTO] [PERMISSAO] O usuário vem da sessão, a parceria e a conversa vêm da linha {@code atlas_conversa} (cujo
 * dono o service já conferiu), a cota e as abas abertas vêm do banco pelo VerificadorDeAcesso. As operações do relatório recebem
 * este contexto e nenhuma recebe {@code parceria_id}: o modelo de linguagem (na etapa seguinte) não escolhe parceria, não vê o id
 * dela e não consegue pedir os dados de outra marca. {@code requisicao} é o pedido HTTP em curso, só para o histórico gravar IP e
 * navegador; o Atlas roda dentro do pedido, na mesma thread.
 *
 * <p>
 * <b>O Atlas da RBS (M8)</b> não tem parceria: o contexto dele tem {@code parceriaId} {@link #SEM_PARCERIA}, {@code cotaTipoId} zero,
 * {@code cota} nula, nenhuma aba e o JEV desligado ({@link JevDoTurno#NENHUM}), e {@code eventoId} é a edição da conversa. Nenhuma
 * parceria tem id zero, então uma ferramenta que, por engano, usasse {@code parceriaId} não acharia nada; mesmo assim o
 * {@code RegistroDeFerramentas} só deixa rodar as ferramentas da RBS ({@link #daRbs()}).
 *
 * @param usuario     quem pede (a sessão)
 * @param parceriaId  a parceria da conversa ({@link #SEM_PARCERIA} na conversa da RBS)
 * @param eventoId    a edição do festival da parceria (M4): a busca nos documentos só enxerga documentos desta edição
 * @param cotaTipoId  o tipo de cota da parceria (M4): a busca enxerga os documentos da cota dela
 * @param cota        o código da cota da parceria (MASTER, SUBCOTA_G ou SUBCOTA)
 * @param abasAbertas os códigos das abas abertas para este usuário nesta parceria (a RBS: todas)
 * @param conversaId  a conversa em andamento
 * @param requisicao  o pedido HTTP, para o histórico
 * @param traco       o traço do diagnóstico do turno em andamento ({@link Traco#NENHUM} fora de um turno ou com o diagnóstico
 *                    desligado): é por aqui que o loop, a checagem e o resumo do raciocínio deixam as etapas
 * @param jev         o que o JEV guarda durante o turno (M7): o orçamento de requisições, as decisões ainda não gravadas e se o turno
 *                    acabou na preparação. {@link JevDoTurno#NENHUM} fora de um turno: o JEV nunca é consultado
 */
public record AtlasContexto(UsuarioAutenticado usuario, long parceriaId, long eventoId, int cotaTipoId, String cota,
                            Set<String> abasAbertas, long conversaId, HttpServletRequest requisicao, Traco traco, JevDoTurno jev) {

    /** O {@code parceriaId} da conversa da RBS, que não tem parceria. Nenhuma parceria de verdade tem id zero (as chaves começam em 1). */
    public static final long SEM_PARCERIA = 0;

    public AtlasContexto {
        abasAbertas = abasAbertas == null ? Set.of() : Set.copyOf(abasAbertas);
        traco = traco == null ? Traco.NENHUM : traco;
        jev = jev == null ? JevDoTurno.NENHUM : jev;
    }

    /** Um contexto sem traço nem JEV (o diagnóstico desligado, ou fora de um turno). */
    public AtlasContexto(UsuarioAutenticado usuario, long parceriaId, long eventoId, int cotaTipoId, String cota,
                         Set<String> abasAbertas, long conversaId, HttpServletRequest requisicao) {
        this(usuario, parceriaId, eventoId, cotaTipoId, cota, abasAbertas, conversaId, requisicao, Traco.NENHUM, JevDoTurno.NENHUM);
    }

    /** O mesmo contexto, no turno deste traço. */
    public AtlasContexto comTraco(Traco novo) {
        return new AtlasContexto(usuario, parceriaId, eventoId, cotaTipoId, cota, abasAbertas, conversaId, requisicao, novo, jev);
    }

    /** O mesmo contexto, com este estado do JEV. */
    public AtlasContexto comJev(JevDoTurno novo) {
        return new AtlasContexto(usuario, parceriaId, eventoId, cotaTipoId, cota, abasAbertas, conversaId, requisicao, traco, novo);
    }

    /** É o contexto do Atlas da RBS: a conversa de um evento, sem parceria, cota nem abas? */
    public boolean daRbs() {
        return parceriaId == SEM_PARCERIA;
    }

    public boolean abaAberta(String codigoDaAba) {
        return abasAbertas.contains(codigoDaAba);
    }
}
