package br.com.rbs.portal.atlas;

import org.springframework.test.context.TestPropertySource;

/** [ATLAS] O evento {@code ferramenta} da resposta em fluxo, com o relatório ligado (veja a DebugDeFerramentasFluxoBase). */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=true", "atlas.debug.ferramentas=true"})
class DebugDeFerramentasFluxoComRelatorioTest extends DebugDeFerramentasFluxoBase {
}
