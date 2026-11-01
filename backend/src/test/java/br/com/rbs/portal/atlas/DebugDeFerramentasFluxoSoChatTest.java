package br.com.rbs.portal.atlas;

import org.springframework.test.context.TestPropertySource;

/** [ATLAS] O evento {@code ferramenta} da resposta em fluxo, no Atlas só chat (veja a DebugDeFerramentasFluxoBase). */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=true"})
class DebugDeFerramentasFluxoSoChatTest extends DebugDeFerramentasFluxoBase {
}
