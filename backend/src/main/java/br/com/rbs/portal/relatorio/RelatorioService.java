package br.com.rbs.portal.relatorio;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.relatorio.RelatorioDtos.CotaDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.EventoDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.IndicadorDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.RelatorioDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.ResumoDoRelatorioDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.SecaoDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.SituacaoDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.UsuarioResumoDto;
import br.com.rbs.portal.relatorio.RelatorioRepositorio.CabecalhoDoRelatorio;
import br.com.rbs.portal.relatorio.RelatorioRepositorio.EstadoDoRelatorio;
import br.com.rbs.portal.relatorio.RelatorioRepositorio.LinhaDeIndicador;
import br.com.rbs.portal.relatorio.RelatorioRepositorio.LinhaDeSituacao;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras do relatório por parceria: situação de todas as parcerias, geração de versões, validação e disponibilização pela
 * RBS, leitura e CSV. Atende o item 6 da Entrega 2. [RELATORIO] O relatório é uma fotografia: os números vêm da função do
 * banco e nunca são recalculados nem estimados. [PERMISSAO] O patrocinador só enxerga a versão disponibilizada mais nova.
 */
@Service
public class RelatorioService {

    static final String AINDA_NAO_DISPONIVEL = "O relatório desta parceria ainda não está disponível.";

    /**
     * Os 17 indicadores do catálogo (V3), na ordem em que o catálogo os lista. A tabela indicador não tem coluna de ordem, e
     * as seções do relatório seguem a primeira aparição de cada uma aqui. Indicador novo que ainda não esteja nesta lista
     * vai para o fim da sua seção, sem quebrar nada.
     */
    private static final List<String> ORDEM_DO_CATALOGO = List.of(
            "ENTREGAS_CONCLUIDAS_PCT", "ENTREGAS_PENDENTES",
            "MIDIA_PREVISTO", "MIDIA_EXIBIDO", "MIDIA_ENTREGA_PCT", "MIDIA_VEICULO_PCT",
            "APLICACOES_PREVISTAS", "APLICACOES_COMPROVADAS", "APLICACOES_COMPROVADAS_PCT", "COMPROVACOES_ENVIADAS",
            "PESQUISA_BASE", "PESQUISA_RESULTADO",
            "CORTESIAS_DISPONIVEIS", "CORTESIAS_DESTINADAS_PCT", "CREDENCIAIS_DIA",
            "FESTIVAL_NUMERO", "FESTIVAL_PESQUISA");

    /** Um CSV pronto para virar resposta: o nome do arquivo, o cabeçalho e as linhas (veja Csv.resposta). */
    public record ArquivoCsv(String nome, List<String> cabecalho, List<List<Object>> linhas) {
    }

    private final RelatorioRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public RelatorioService(RelatorioRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rotas 31 e 32: situação de cada parceria
    // ---------------------------------------------------------------------

    /** Rota 31. [PERMISSAO] Só a RBS. Sem eventoId vale a edição mais nova; edição que não existe devolve lista vazia. */
    @Transactional(readOnly = true)
    public List<SituacaoDto> listar(UsuarioAutenticado usuario, Long eventoId, String status) {
        verificador.exigirRbs(usuario);
        Long evento = eventoId != null ? eventoId : repositorio.eventoMaisNovo().orElse(null);
        if (evento == null) {
            return List.of();
        }
        return repositorio.situacoes(evento, status).stream().map(RelatorioService::paraSituacao).toList();
    }

    /** Rota 32: as mesmas linhas da rota 31, em CSV. */
    @Transactional(readOnly = true)
    public ArquivoCsv listarEmCsv(UsuarioAutenticado usuario, Long eventoId, String status) {
        List<List<Object>> linhas = new ArrayList<>();
        for (SituacaoDto s : listar(usuario, eventoId, status)) {
            ResumoDoRelatorioDto r = s.relatorio();
            // Parceria sem relatório: versão, datas e contagens ficam em branco (nada de zero que pareça medida).
            linhas.add(Csv.linha(s.parceriaId(), s.patrocinador(), s.cota().nome(), s.evento().edicao(), s.situacao(),
                    r != null ? r.versao() : null, r != null ? r.geradoEm() : null, r != null ? r.validadoEm() : null,
                    r != null ? r.disponibilizadoEm() : null, r != null ? r.indicadoresDisponiveis() : null,
                    r != null ? r.indicadoresIndisponiveis() : null, s.faltantes()));
        }
        return new ArquivoCsv("relatorios-" + Datas.hoje() + ".csv", List.of(
                "parceria_id", "patrocinador", "cota", "edicao", "situacao", "versao", "gerado_em", "validado_em",
                "disponibilizado_em", "indicadores_disponiveis", "indicadores_indisponiveis", "faltantes"), linhas);
    }

    private static SituacaoDto paraSituacao(LinhaDeSituacao l) {
        ResumoDoRelatorioDto resumo = l.relatorioId() == null ? null : new ResumoDoRelatorioDto(l.relatorioId(), l.versao(),
                l.status(), Datas.emBrasilia(l.geradoEm()), Datas.emBrasilia(l.validadoEm()),
                Datas.emBrasilia(l.disponibilizadoEm()), l.disponiveis(), l.indisponiveis());
        return new SituacaoDto(l.parceriaId(), l.patrocinador(), new CotaDto(l.cotaCodigo(), l.cotaNome()),
                new EventoDto(l.eventoId(), l.eventoNome(), l.edicao()), l.status(), resumo, l.faltantes());
    }

    // ---------------------------------------------------------------------
    // Rotas 33 e 34: gerar e mudar o status (só a RBS)
    // ---------------------------------------------------------------------

    /**
     * Rota 33. Gera uma nova versão em RASCUNHO pela função do banco. [HISTORICO] A própria função grava o
     * GERACAO_RELATORIO ("versão N (RASCUNHO)") na mesma transação; por isso este método não grava de novo. A linha dela
     * não tem IP nem navegador, porque a função não os recebe.
     */
    @Transactional
    public RelatorioDto gerar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        long relatorioId = repositorio.gerar(parceriaId, usuario.id());
        return montar(repositorio.buscar(relatorioId).orElseThrow(), true);
    }

    /**
     * Rota 34. Valida ou disponibiliza. [RELATORIO] A ordem é imposta pelo gatilho do banco: pular uma etapa ou voltar vira
     * 409 com a frase dele (o TratadorDeErros repassa a frase dos gatilhos rn_*). [HISTORICO] VALIDACAO ou DISPONIBILIZACAO
     * na mesma transação; pedir o status que o relatório já tem não muda nada e não grava nada.
     */
    @Transactional
    public RelatorioDto mudarStatus(UsuarioAutenticado usuario, long relatorioId, String novoStatus,
                                    HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        EstadoDoRelatorio atual = repositorio.travar(relatorioId).orElseThrow(RelatorioService::relatorioNaoEncontrado);
        verificador.exigirParceria(usuario, atual.parceriaId());

        repositorio.atualizarStatus(relatorioId, novoStatus, usuario.id());

        if (!novoStatus.equals(atual.status())) {
            AcaoHistorico acao = "DISPONIBILIZADO".equals(novoStatus) ? AcaoHistorico.DISPONIBILIZACAO
                    : AcaoHistorico.VALIDACAO;
            historico.registrar(acao, usuario, atual.parceriaId(), "relatorio", relatorioId, atual.status(), novoStatus,
                    "versão " + atual.versao(), requisicao);
        }
        return montar(repositorio.buscar(relatorioId).orElseThrow(), true);
    }

    // ---------------------------------------------------------------------
    // Rota 35: ver o relatório
    // ---------------------------------------------------------------------

    /**
     * Rota 35. [ISOLAMENTO] Parceria de outro patrocinador: 404. [PERMISSAO] O patrocinador recebe a versão disponibilizada
     * mais nova (e 404 se não houver), e o parâmetro versao não vale para ele; a RBS recebe a mais nova de qualquer
     * status ou a versão pedida.
     */
    @Transactional(readOnly = true)
    public RelatorioDto ver(UsuarioAutenticado usuario, long parceriaId, Integer versao) {
        verificador.exigirParceria(usuario, parceriaId);
        CabecalhoDoRelatorio cabecalho;
        if (usuario.ehRbs()) {
            cabecalho = (versao != null ? repositorio.porVersao(parceriaId, versao) : repositorio.maisNovo(parceriaId))
                    .orElseThrow(() -> new NaoEncontradoException(versao != null
                            ? "Não encontramos esta versão do relatório." : "Esta parceria ainda não tem relatório."));
        } else {
            cabecalho = repositorio.maisNovoDisponibilizado(parceriaId)
                    .orElseThrow(() -> new NaoEncontradoException(AINDA_NAO_DISPONIVEL));
        }
        return montar(cabecalho, usuario.ehRbs());
    }

    // ---------------------------------------------------------------------
    // Rota 36: CSV de um relatório
    // ---------------------------------------------------------------------

    /**
     * Rota 36. [ISOLAMENTO] O relatório de outra parceria responde 404, igual a um id que não existe. [PERMISSAO] O
     * patrocinador só baixa a versão DISPONIBILIZADA. [HISTORICO] Grava DOWNLOAD (entidade relatorio) na mesma transação.
     */
    @Transactional
    public ArquivoCsv baixarCsv(UsuarioAutenticado usuario, long relatorioId, HttpServletRequest requisicao) {
        CabecalhoDoRelatorio cabecalho = repositorio.buscar(relatorioId).orElseThrow(RelatorioService::relatorioNaoEncontrado);
        try {
            verificador.exigirParceria(usuario, cabecalho.parceriaId());
        } catch (NaoEncontradoException deOutraParceria) {
            // Mesma frase de "o relatório não existe": o patrocinador não descobre que o id existe em outra parceria.
            throw relatorioNaoEncontrado();
        }
        if (!usuario.ehRbs() && !"DISPONIBILIZADO".equals(cabecalho.status())) {
            throw new NaoEncontradoException(AINDA_NAO_DISPONIVEL);
        }

        List<List<Object>> linhas = new ArrayList<>();
        for (SecaoDto secao : secoes(cabecalho.id())) {
            for (IndicadorDto i : secao.indicadores()) {
                linhas.add(Csv.linha(secao.secao(), i.codigo(), i.nome(), i.recorte(), i.valor(), i.unidade(),
                        i.baseCalculo(), i.disponivel() ? "sim" : "nao"));
            }
        }
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, cabecalho.parceriaId(), "relatorio", cabecalho.id(), null, null,
                "CSV da versão " + cabecalho.versao(), requisicao);
        return new ArquivoCsv("relatorio-" + apelido(cabecalho.patrocinador()) + "-v" + cabecalho.versao() + ".csv",
                List.of("secao", "indicador_codigo", "indicador", "recorte", "valor", "unidade", "base_calculo", "disponivel"),
                linhas);
    }

    // ---------------------------------------------------------------------
    // Montagem do relatório
    // ---------------------------------------------------------------------

    private static NaoEncontradoException relatorioNaoEncontrado() {
        return new NaoEncontradoException("Não encontramos este relatório.");
    }

    /** {@code comAutor} é falso para o patrocinador: quem gerou é dado interno da RBS e vai nulo. */
    private RelatorioDto montar(CabecalhoDoRelatorio c, boolean comAutor) {
        UsuarioResumoDto geradoPor = comAutor ? new UsuarioResumoDto(c.geradoPorId(), c.geradoPorNome(), c.geradoPorPerfil()) : null;
        return new RelatorioDto(c.id(), c.parceriaId(), c.versao(), c.status(), Datas.emBrasilia(c.geradoEm()), geradoPor,
                Datas.emBrasilia(c.validadoEm()), Datas.emBrasilia(c.disponibilizadoEm()), secoes(c.id()));
    }

    /**
     * Agrupa os números nas seções do catálogo, na ordem do catálogo. [RELATORIO] Número sem dado sai com
     * {@code disponivel: false} e {@code valor: null}, nunca zero.
     */
    private List<SecaoDto> secoes(long relatorioId) {
        Comparator<LinhaDeIndicador> ordem = Comparator
                .comparingInt((LinhaDeIndicador l) -> posicao(l.codigo()))
                .thenComparing(LinhaDeIndicador::codigo)
                .thenComparing(LinhaDeIndicador::recorte);
        Map<String, List<IndicadorDto>> porSecao = new LinkedHashMap<>();
        // As seções aparecem na ordem do catálogo: ordena primeiro todas as linhas, depois agrupa na ordem de chegada.
        repositorio.indicadores(relatorioId).stream().sorted(ordem).forEach(l -> porSecao
                .computeIfAbsent(l.secao(), s -> new ArrayList<>())
                .add(new IndicadorDto(l.codigo(), l.nome(), l.recorte(), l.disponivel() ? limpar(l.valor()) : null, l.unidade(),
                        l.baseCalculo(), l.disponivel())));
        return porSecao.entrySet().stream().map(e -> new SecaoDto(e.getKey(), e.getValue())).toList();
    }

    private static int posicao(String codigo) {
        int posicao = ORDEM_DO_CATALOGO.indexOf(codigo);
        return posicao >= 0 ? posicao : ORDEM_DO_CATALOGO.size();
    }

    /** O banco guarda 2 casas (90.90, 10.00); a API devolve 90.9 e 10, como no contrato. */
    private static BigDecimal limpar(BigDecimal valor) {
        if (valor == null) {
            return null;
        }
        BigDecimal semZeros = valor.signum() == 0 ? BigDecimal.ZERO : valor.stripTrailingZeros();
        return semZeros.scale() < 0 ? semZeros.setScale(0) : semZeros;
    }

    /** "Cliente Amarelo" vira "cliente-amarelo": o nome do patrocinador sem acento, em minúsculas, para o nome do arquivo. */
    private static String apelido(String patrocinador) {
        String semAcento = Normalizer.normalize(patrocinador, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String apelido = semAcento.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return apelido.isEmpty() ? "parceria" : apelido;
    }
}
