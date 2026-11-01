package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.AplicacoesDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.ConsolidacaoDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.CortesiasDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.EntregasDoPainelDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.EntregasDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.EventoDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.FaltanteDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.LinhaDoPlanoDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.MidiaDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.PainelDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.ParceriasDoPainelDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.PendenciaDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.RelatoriosDoPainelDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.TotalPorUnidadeDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.TotalPorVeiculoDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.UltimaCargaDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoRepositorio.Contagem;
import br.com.rbs.portal.consolidacao.ConsolidacaoRepositorio.LinhaDeEvento;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Previsto x exibido por parceria (rota 29) e números do painel da RBS (rota 30). Atende os itens 5 e 7 da Entrega 2.
 * [RELATORIO] Nada é estimado nem recalculado fora do banco: as contas vêm das visões, e unidade de mídia diferente nunca
 * é somada. [PERMISSAO] O patrocinador vê só os faltantes que ele mesmo resolve; o painel é só da RBS.
 */
@Service
public class ConsolidacaoService {

    /**
     * [PERMISSAO] Os faltantes que a marca consegue resolver sozinha: entregar o que está pendente e mandar as perguntas.
     * Os demais (mídia sem exibição, sem comprovação, sem resultado...) são trabalho interno da RBS.
     */
    static final List<String> FALTANTES_DA_MARCA = List.of("ENTREGA_PENDENTE", "PESQUISA_SEM_PERGUNTAS");

    /** O painel mostra os 20 faltantes mais importantes, não a lista inteira. */
    static final int LIMITE_DE_PENDENCIAS = 20;

    /** Os 7 status de entrega, na ordem do contrato: o painel mostra todos, com zero nos vazios. */
    private static final List<String> STATUS_DE_ENTREGA = List.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "ENVIADO",
            "EM_ANALISE", "AJUSTE_SOLICITADO", "APROVADO", "NAO_APLICAVEL");

    private final ConsolidacaoRepositorio repositorio;
    private final VerificadorDeAcesso verificador;

    public ConsolidacaoService(ConsolidacaoRepositorio repositorio, VerificadorDeAcesso verificador) {
        this.repositorio = repositorio;
        this.verificador = verificador;
    }

    // ---------------------------------------------------------------------
    // Rota 29: consolidação da parceria
    // ---------------------------------------------------------------------

    /**
     * Rota 29. [ISOLAMENTO] Parceria de outro patrocinador: 404. Uma só leitura (REPEATABLE READ) para os números da tela
     * serem do mesmo instante, mesmo que uma carga termine no meio. Seção sem dado vem com lista vazia e totais nulos:
     * nunca zero inventado. [PERMISSAO] A parte de cada aba só sai para o patrocinador se a aba estiver aberta para a
     * parceria dele: mídia (aba MIDIA) vem com as três listas vazias, aplicações (APLICACOES) e cortesias (CORTESIAS) com
     * os totais nulos, exatamente como quando não há dado. As entregas não têm aba própria: o resumo do patrocinador conta só as
     * entregas de aba aberta (as mesmas que a lista de entregas devolve) e, sem nenhuma, vem com os totais nulos. A RBS vê tudo.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConsolidacaoDto consolidar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);

        MidiaDto midia = !verificador.podeVerAba(usuario, parceriaId, "MIDIA") ? new MidiaDto(List.of(), List.of(), List.of())
                : new MidiaDto(
                repositorio.porUnidade(parceriaId).stream()
                        .map(l -> new TotalPorUnidadeDto(l.unidade(), l.previsto(), l.exibido(), l.percentual())).toList(),
                repositorio.porVeiculo(parceriaId).stream()
                        .map(l -> new TotalPorVeiculoDto(l.veiculo(), l.unidade(), l.previsto(), l.exibido(), l.percentual()))
                        .toList(),
                repositorio.linhasDoPlano(parceriaId).stream()
                        .map(l -> new LinhaDoPlanoDto(l.aproveitamentoId(), l.veiculo(), l.formato(), l.espacoPrograma(),
                                l.unidade(), l.previsto(), l.exibido(), l.percentual())).toList());
        AplicacoesDto aplicacoes = !verificador.podeVerAba(usuario, parceriaId, "APLICACOES") ? new AplicacoesDto(null, null, null, null)
                : repositorio.aplicacoes(parceriaId)
                .map(l -> new AplicacoesDto(l.previstas(), l.comprovadas(), l.fotos(), l.percentual()))
                .orElse(new AplicacoesDto(null, null, null, null));
        EntregasDto entregas = repositorio.entregas(parceriaId, !usuario.ehRbs())
                .map(l -> new EntregasDto(l.total(), l.aprovadas(), l.pendentes(), l.naoAplicaveis(), l.percentualConcluido()))
                .orElse(new EntregasDto(null, null, null, null, null));
        CortesiasDto cortesias = !verificador.podeVerAba(usuario, parceriaId, "CORTESIAS") ? new CortesiasDto(null, null, null)
                : repositorio.cortesias(parceriaId)
                .map(l -> new CortesiasDto(l.disponiveis(), l.destinadas(), l.percentual()))
                .orElse(new CortesiasDto(null, null, null));

        // [PERMISSAO] O filtro é aplicado na consulta: o faltante que a marca não pode ver nem sai do banco.
        List<FaltanteDto> faltantes = repositorio.faltantes(parceriaId, usuario.ehRbs() ? null : FALTANTES_DA_MARCA).stream()
                .map(l -> new FaltanteDto(l.tipo(), l.detalhe())).toList();

        return new ConsolidacaoDto(parceriaId, midia, aplicacoes, entregas, cortesias, faltantes);
    }

    // ---------------------------------------------------------------------
    // Rota 30: painel da RBS
    // ---------------------------------------------------------------------

    /**
     * Rota 30. [PERMISSAO] Só a RBS. Sem eventoId vale a edição mais nova; edição que não existe responde 404. Uma só
     * leitura (REPEATABLE READ): o total de entregas bate com a soma por status mesmo com a base mudando.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PainelDto painel(UsuarioAutenticado usuario, Long eventoId) {
        verificador.exigirRbs(usuario);
        LinhaDeEvento evento = repositorio.evento(eventoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta edição do festival."));
        long id = evento.id();

        var parcerias = repositorio.parcerias(id);

        Map<String, Long> porStatus = new LinkedHashMap<>();
        STATUS_DE_ENTREGA.forEach(status -> porStatus.put(status, 0L));
        repositorio.entregasPorStatus(id).forEach(c -> porStatus.put(c.chave(), c.total()));
        var entregas = repositorio.entregasDoPainel(id, Datas.hoje());

        Map<String, Long> situacoes = repositorio.relatoriosPorSituacao(id).stream()
                .collect(Collectors.toMap(Contagem::chave, Contagem::total));

        UltimaCargaDto ultimaCarga = repositorio.ultimaCarga(id)
                .map(c -> new UltimaCargaDto(c.id(), nomeDoTipoDeCarga(c.tipo()), Datas.emBrasilia(c.executadaEm()),
                        c.linhasInseridas(), c.linhasRejeitadas()))
                .orElse(null);

        List<PendenciaDto> pendencias = repositorio.pendencias(id, LIMITE_DE_PENDENCIAS).stream()
                .map(l -> new PendenciaDto(l.parceriaId(), l.patrocinador(), l.tipo(), l.detalhe())).toList();

        return new PainelDto(new EventoDto(evento.id(), evento.nome(), evento.edicao()),
                new ParceriasDoPainelDto(parcerias.total(), parcerias.ativas(), parcerias.semUsuario()),
                new EntregasDoPainelDto(porStatus, entregas.esperandoAnalise(), entregas.atrasadas()),
                new RelatoriosDoPainelDto(situacoes.getOrDefault("SEM_RELATORIO", 0L), situacoes.getOrDefault("RASCUNHO", 0L),
                        situacoes.getOrDefault("VALIDADO", 0L), situacoes.getOrDefault("DISPONIBILIZADO", 0L),
                        repositorio.relatoriosQueAMarcaVe(id)),
                ultimaCarga, pendencias);
    }

    /** No banco o tipo da carga de veiculação é MIDIA; a API (e o front) a chamam de VEICULACAO (docs/api.md, rota 26). */
    static String nomeDoTipoDeCarga(String tipoDoBanco) {
        return "MIDIA".equals(tipoDoBanco) ? "VEICULACAO" : tipoDoBanco;
    }
}
