package br.com.rbs.portal.atlas;

import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os itens {@code pedido_relatorio} e {@code modelo} no Atlas só chat ({@code atlas.relatorio.ligado=false}): saem do mesmo
 * jeito, porque a resposta direta não depende do relatório; os testes estão em RespostasDiretasNaConversaBase.
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class RespostasDiretasNaConversaSoChatTest extends RespostasDiretasNaConversaBase {
}
