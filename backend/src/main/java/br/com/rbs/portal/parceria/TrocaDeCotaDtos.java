package br.com.rbs.portal.parceria;

import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaDetalheDto;
import java.time.OffsetDateTime;
import java.util.List;

/** Os formatos da troca de cota (rotas 102 e 104). */
public final class TrocaDeCotaDtos {

    private TrocaDeCotaDtos() {
    }

    /** Corpo da rota 102. {@code ciente} só pesa quando a parceria tem relatório disponibilizado. */
    public record TrocaDeCotaRequisicao(String cotaCodigo, Boolean ciente) {
    }

    public record AbaDoImpactoDto(String codigo, String nome) {
    }

    public record RelatorioDoImpactoDto(Integer versao, OffsetDateTime disponibilizadoEm) {
    }

    /** Algo que a troca NÃO muda sozinha e que a RBS ajusta na aba indicada. */
    public record AjusteManualDto(String aba, String texto) {
    }

    /**
     * O que a troca faz (rota 104) e o que ela fez (dentro da resposta da 102). {@code pesquisa} e {@code comprovacaoMensal}
     * valem DEIXA_DE_VALER, PASSA_A_VALER ou SEM_MUDANCA.
     */
    public record ImpactoDaTrocaDto(Long parceriaId, CotaDto cotaAtual, CotaDto cotaNova, boolean mudaACota,
                                    List<AbaDoImpactoDto> abasQueFecham, List<AbaDoImpactoDto> abasQuePassamAPoderAbrir,
                                    RelatorioDoImpactoDto relatorioDisponibilizado, boolean exigeCiencia, String pesquisa,
                                    String comprovacaoMensal, List<AjusteManualDto> naoMudamSozinhos, List<String> avisos) {
    }

    public record TrocaDeCotaDto(ImpactoDaTrocaDto impacto, ParceriaDetalheDto<EmailResumoDto> parceria) {
    }
}
