package br.com.rbs.portal.materiais;

import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.EntregaDto;
import java.util.List;

/** Corpos de saída das rotas 69 a 73 (plano comercial e espaços de documento da marca). O formato é o contrato com o front (docs/api.md). */
public final class EspacosDtos {

    private EspacosDtos() {
    }

    /** Rota 69: as versões do plano comercial da parceria, da mais nova para a mais antiga (lista vazia se a RBS ainda não enviou). */
    public record PlanoComercialDto(Long parceriaId, List<ArquivoDto> versoes) {
    }

    /** As versões do formulário em branco que a RBS publica (rota 73), da mais nova para a mais antiga. */
    public record FormularioDeIngressosDto(List<ArquivoDto> versoes) {
    }

    /**
     * Rota 71. {@code solicitacoesIngressos}: uma entrega por solicitação, da primeira para a última, cada uma com o seu status e as
     * suas versões. {@code miv} e {@code logo} são nulos enquanto ninguém enviou o primeiro arquivo.
     */
    public record EspacosDto(Long parceriaId, FormularioDeIngressosDto formularioIngressos, List<EntregaDto> solicitacoesIngressos,
                             EntregaDto miv, EntregaDto logo) {
    }
}
