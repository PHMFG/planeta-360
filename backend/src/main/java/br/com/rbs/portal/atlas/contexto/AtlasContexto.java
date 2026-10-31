package br.com.rbs.portal.atlas.contexto;

import br.com.rbs.portal.atlas.diagnostico.Traco;
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
 * @param usuario     quem pede (a sessão)
 * @param parceriaId  a parceria da conversa
 * @param cota        o código da cota da parceria (MASTER, SUBCOTA_G ou SUBCOTA)
 * @param abasAbertas os códigos das abas abertas para este usuário nesta parceria (a RBS: todas)
 * @param conversaId  a conversa em andamento
 * @param requisicao  o pedido HTTP, para o histórico
 * @param traco       o traço do diagnóstico do turno em andamento ({@link Traco#NENHUM} fora de um turno ou com o diagnóstico
 *                    desligado): é por aqui que o loop, a checagem e o resumo do raciocínio deixam as etapas
 */
public record AtlasContexto(UsuarioAutenticado usuario, long parceriaId, String cota, Set<String> abasAbertas, long conversaId,
                            HttpServletRequest requisicao, Traco traco) {

    public AtlasContexto {
        abasAbertas = abasAbertas == null ? Set.of() : Set.copyOf(abasAbertas);
        traco = traco == null ? Traco.NENHUM : traco;
    }

    /** Um contexto sem traço (o diagnóstico desligado, ou fora de um turno). */
    public AtlasContexto(UsuarioAutenticado usuario, long parceriaId, String cota, Set<String> abasAbertas, long conversaId,
                         HttpServletRequest requisicao) {
        this(usuario, parceriaId, cota, abasAbertas, conversaId, requisicao, Traco.NENHUM);
    }

    /** O mesmo contexto, no turno deste traço. */
    public AtlasContexto comTraco(Traco novo) {
        return new AtlasContexto(usuario, parceriaId, cota, abasAbertas, conversaId, requisicao, novo);
    }

    public boolean abaAberta(String codigoDaAba) {
        return abasAbertas.contains(codigoDaAba);
    }
}
