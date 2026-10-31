package br.com.rbs.portal.atlas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O que o Atlas oferece nesta execução. [ATLAS] Duas propriedades, lidas uma vez na subida:
 * <ul>
 *   <li>{@code atlas.relatorio.ligado} ({@code ATLAS_RELATORIO}, desligada por padrão): o relatório de trabalho. Desligada, o
 *       Atlas é só o chat: o modelo recebe só {@code parceria_consultar} e o prompt {@code chat.txt}, o atalho e o cartão não
 *       existem, as rotas 46 a 48 não são mapeadas (404) e a conversa não devolve cartão, relatório pronto nem aviso de cartão.
 *       O código do relatório continua no projeto, e as tabelas do banco ficam sem uso.</li>
 *   <li>{@code atlas.debug.ferramentas} ({@code ATLAS_DEBUG_FERRAMENTAS}, desligada por padrão, ligada no perfil dev): temporário.
 *       A conversa mostra cada ferramenta que o agente chamou, com os argumentos e o resultado crus.</li>
 *   <li>{@code atlas.diagnostico.ligado} ({@code ATLAS_DIAGNOSTICO}, desligada por padrão e no teste, ligada no perfil dev): o
 *       diagnóstico. Ligada, cada turno deixa um traço em {@code atlas_traco} e as rotas 50 a 53 (da RBS) respondem; desligada,
 *       nada é gravado e as rotas 49 a 53 respondem 404.</li>
 *   <li>{@code atlas.diagnostico.marca} (desligada por padrão e no teste, ligada no perfil dev): a marca vê o diagnóstico das
 *       próprias conversas (rota 49, o campo {@code diagnosticoDisponivel} e o {@code turno} dos itens). Só vale com o
 *       diagnóstico ligado.</li>
 * </ul>
 */
@Component
public class ModoDoAtlas {

    /** O 422 de quem manda {@code atalho} ou {@code modelo} (rotas 42 e 44) com o relatório desligado. */
    public static final String RELATORIO_INDISPONIVEL = "O relatório não está disponível nesta versão.";

    private final boolean relatorioLigado;
    private final boolean debugDeFerramentas;
    private final boolean diagnosticoLigado;
    private final boolean diagnosticoNaMarca;

    public ModoDoAtlas(@Value("${atlas.relatorio.ligado:false}") boolean relatorioLigado,
                       @Value("${atlas.debug.ferramentas:false}") boolean debugDeFerramentas,
                       @Value("${atlas.diagnostico.ligado:false}") boolean diagnosticoLigado,
                       @Value("${atlas.diagnostico.marca:false}") boolean diagnosticoNaMarca) {
        this.relatorioLigado = relatorioLigado;
        this.debugDeFerramentas = debugDeFerramentas;
        this.diagnosticoLigado = diagnosticoLigado;
        this.diagnosticoNaMarca = diagnosticoNaMarca;
    }

    public boolean relatorioLigado() {
        return relatorioLigado;
    }

    public boolean debugDeFerramentas() {
        return debugDeFerramentas;
    }

    /** O diagnóstico está ligado: os turnos deixam traço e as rotas da RBS (50 a 53) existem. */
    public boolean diagnosticoLigado() {
        return diagnosticoLigado;
    }

    /** A marca vê o diagnóstico das próprias conversas: o diagnóstico ligado E {@code atlas.diagnostico.marca}. */
    public boolean diagnosticoNaMarca() {
        return diagnosticoLigado && diagnosticoNaMarca;
    }
}
