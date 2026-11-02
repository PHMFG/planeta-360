package br.com.rbs.portal.atlas;

import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os itens {@code pedido_relatorio} e {@code modelo} com o relatório ligado (o padrão do perfil de teste); os testes estão
 * em RespostasDiretasNaConversaBase.
 */
@TesteComLlmRoteirizado
@Transactional
class RespostasDiretasNaConversaComRelatorioTest extends RespostasDiretasNaConversaBase {

    @Override
    boolean relatorioLigado() {
        return true;
    }
}
