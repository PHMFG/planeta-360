package br.com.rbs.portal.atlas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O que o Atlas oferece nesta execução. [ATLAS] Uma propriedade, lida uma vez na subida:
 * <ul>
 *   <li>{@code atlas.relatorio.ligado} ({@code ATLAS_RELATORIO}, desligada por padrão): o relatório de trabalho. Desligada, o
 *       Atlas é só o chat: o modelo recebe só {@code parceria_consultar} e o prompt {@code chat.txt}, o atalho e o cartão não
 *       existem, as rotas 46 a 48 não são mapeadas (404) e a conversa não devolve cartão, relatório pronto nem aviso de cartão.
 *       O código do relatório continua no projeto, e as tabelas do banco ficam sem uso.</li>
 * </ul>
 */
@Component
public class ModoDoAtlas {

    /** O 422 de quem manda {@code atalho} ou {@code modelo} (rotas 42 e 44) com o relatório desligado. */
    public static final String RELATORIO_INDISPONIVEL = "O relatório não está disponível nesta versão.";

    private final boolean relatorioLigado;

    public ModoDoAtlas(@Value("${atlas.relatorio.ligado:false}") boolean relatorioLigado) {
        this.relatorioLigado = relatorioLigado;
    }

    public boolean relatorioLigado() {
        return relatorioLigado;
    }
}
