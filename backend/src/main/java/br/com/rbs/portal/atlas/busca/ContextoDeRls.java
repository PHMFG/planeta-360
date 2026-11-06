package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * O que a busca nos documentos faz no começo da transação dela para a RLS valer (plano do Atlas completo, M4). [ATLAS] [ISOLAMENTO]
 * <ol>
 *   <li>{@code SET LOCAL ROLE atlas_ro} e a conferência do {@code current_user}: se não for {@code atlas_ro}, erro e nenhuma busca.
 *       Fora de uma transação, o {@code SET LOCAL} só avisa e a consulta rodaria como {@code portal}, <b>sem RLS</b>; dentro de uma
 *       transação externa o papel ficaria preso até o fim dela (os dois conferidos em 30/10/2026). Por isso a busca abre uma
 *       transação nova, só de leitura, e confere o papel;</li>
 *   <li>{@code set_config('app.evento_id' | 'app.cota_tipo_id' | 'app.parceria_id', ..., true)}, a partir do escopo da parceria
 *       da conversa: é com eles que as políticas das tabelas de documentos decidem o que o {@code atlas_ro} enxerga;</li>
 *   <li>o limite de tempo da consulta e os ajustes do índice vetorial (a busca iterativa do HNSW, que continua achando vizinhos
 *       quando o filtro de escopo descarta os primeiros).</li>
 * </ol>
 * Os valores do escopo são sempre números do servidor, nunca texto da pessoa nem do modelo. No escopo só global a cota e a parceria
 * vão vazias: as políticas as leem como nulas e só o documento {@code GLOBAL} da edição passa. O escopo de toda a edição (o Atlas da
 * RBS, desde a base da RBS) é a exceção: a política não representa "todas as cotas e parcerias", então ele não troca o papel e lê com
 * o filtro de edição do servidor sempre junto; só a conversa da RBS o monta.
 */
final class ContextoDeRls {

    /** O papel sob o qual a busca roda: lê só as duas tabelas de documentos, com RLS, e nenhuma tabela do portal. */
    static final String PAPEL = "atlas_ro";

    private static final Pattern NOME_DE_PAPEL = Pattern.compile("[a-z_][a-z0-9_]*");

    private ContextoDeRls() {
    }

    /**
     * Passa a ler como {@code papel} e grava o escopo. Sobe {@link IllegalStateException} se o papel não pegou (ou se o nome não é
     * um identificador): nesse caso nenhuma consulta pode rodar.
     */
    static void aplicar(JdbcClient jdbc, String papel, Escopo escopo, int limiteDeTempoEmMs) {
        if (escopo.todaAEdicao()) {
            // [ISOLAMENTO] O escopo de toda a edição é o da RBS, que pode ler todos os documentos. A política da RLS é por cota e
            // por parceria e não tem como representar "todas"; sem V nova, este é o único caminho. O filtro do WHERE (só a edição,
            // indexado e ativo) vai sempre junto, e nenhuma conversa de marca monta este escopo.
            configurar(jdbc, escopo, limiteDeTempoEmMs);
            return;
        }
        if (papel == null || !NOME_DE_PAPEL.matcher(papel).matches()) {
            throw new IllegalStateException("Nome de papel inválido para a busca nos documentos.");
        }
        jdbc.sql("SET LOCAL ROLE " + papel).update();
        String atual = jdbc.sql("SELECT current_user").query(String.class).single();
        if (!PAPEL.equals(atual)) {
            throw new IllegalStateException("A busca nos documentos tem de rodar como " + PAPEL + " e está rodando como " + atual
                    + ": nenhuma busca foi feita.");
        }
        // O escopo só global (o Atlas da RBS) grava a cota e a parceria VAZIAS: o NULLIF das políticas as lê como nulas, e a comparação
        // com nulo nunca é verdadeira. Nenhum documento de cota nem de parceria passa, e não depende de um id "que não existe".
        configurar(jdbc, escopo, limiteDeTempoEmMs);
    }

    /** Os parâmetros da transação (o contexto da RLS e o tempo limite), sem mexer no papel. */
    private static void configurar(JdbcClient jdbc, Escopo escopo, int limiteDeTempoEmMs) {
        String cota = escopo.soGlobal() || escopo.todaAEdicao() ? "" : Integer.toString(escopo.cotaTipoId());
        String parceria = escopo.soGlobal() || escopo.todaAEdicao() ? "" : Long.toString(escopo.parceriaId());
        jdbc.sql("""
                        SELECT set_config('app.evento_id', :evento, true), set_config('app.cota_tipo_id', :cota, true),
                               set_config('app.parceria_id', :parceria, true), set_config('statement_timeout', :tempo, true),
                               set_config('hnsw.iterative_scan', 'relaxed_order', true), set_config('hnsw.ef_search', '100', true)
                        """)
                .param("evento", Long.toString(escopo.eventoId())).param("cota", cota)
                .param("parceria", parceria).param("tempo", Integer.toString(limiteDeTempoEmMs))
                .query().singleRow();
    }
}
