package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.PlanilhaXlsx;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ComprovanteDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ConsultaDaRbsDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ConsultaDeMidiaDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ConsultaDeMidiaSemMesDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.FiltrosDaRbsDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.FiltrosDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.LinhaDaRbsDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.LinhaDeMidiaDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.LinhaDeMidiaSemMesDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ParceriaDaLinhaDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.TotalPorUnidadeDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaRepositorio.ArquivoDaLinha;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaRepositorio.LinhaDoPlano;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaRepositorio.Parceria;
import br.com.rbs.portal.materiais.HostDoLink;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A consulta da mídia com filtros (rotas 105 a 108, M3 a M7). [RELATORIO] Uma linha por linha do plano de mídia (e por mês,
 * quando há o recorte), com o previsto da linha inteira, o exibido, o percentual cortado na primeira casa, o impacto, os
 * comprovantes e os links; os totais são por unidade, e unidades diferentes nunca se somam. [PERMISSAO] A marca precisa ser
 * da parceria (404) e ter a aba MIDIA aberta (403); o recorte por mês é das cotas Master e da RBS (M7). [HISTORICO] Cada
 * arquivo baixado grava DOWNLOAD.
 */
@Service
public class ConsultaDeMidiaService {

    /** O 422 do recorte por mês para a marca de cota que não é Master (M7). */
    public static final String MES_SO_MASTER = "O recorte por mês é das cotas Master.";

    static final String ENTIDADE_NO_HISTORICO = "midia";

    private static final Pattern MES = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private static final String FRASE_DA_EDICAO = "Não encontramos esta edição do festival.";

    private static final List<String> ORDEM_DAS_UNIDADES = List.of("INSERCAO", "CPM", "APLICACAO");

    private static final List<String> CABECALHO = List.of("Mês", "Veículo", "Formato", "Espaço ou programa",
            "Tipo de mídia", "Unidade", "Previsto (linha do plano)", "Exibido", "Entrega (%)", "Impacto", "Comprovantes");

    /** Um arquivo pronto para baixar. */
    public record Arquivo(String nome, String tipoMime, byte[] bytes) {
    }

    /** A linha já calculada, comum às quatro rotas; a conversão para o DTO decide se o mês e a marca aparecem. */
    private record LinhaCalculada(Long parceriaId, Long aproveitamentoId, String patrocinador, String cota, String veiculo,
                                  String formato, String espacoPrograma, String tipoMidia, String unidade,
                                  BigDecimal previsto, BigDecimal exibido, BigDecimal percentual, BigDecimal impacto,
                                  long exibicoes, List<ComprovanteDto> comprovantes, List<String> links) {
    }

    /**
     * Os dados de uma parceria já autorizados: a parceria, o mês validado (ou nulo), se a cota pode recortar por mês (Master
     * e RBS) e as linhas. Sem o recorte, a resposta não traz os meses nem o mês das linhas.
     */
    private record Dados(Parceria parceria, String mes, boolean recortePorMes, List<LinhaCalculada> linhas) {
    }

    /** Os dados da RBS já autorizados: a edição, o mês validado (ou nulo) e as linhas. */
    private record DadosDaRbs(Long eventoId, int edicao, String mes, List<LinhaCalculada> linhas) {
    }

    /** Um acumulador de unidade para os totais. */
    private static final class Acumulado {
        private BigDecimal previsto = BigDecimal.ZERO;
        private BigDecimal exibido = BigDecimal.ZERO;
        private BigDecimal impacto;

        private void somar(LinhaCalculada linha) {
            previsto = previsto.add(linha.previsto());
            exibido = exibido.add(linha.exibido());
            if (linha.impacto() != null) {
                impacto = impacto == null ? linha.impacto() : impacto.add(linha.impacto());
            }
        }
    }

    private final ConsultaDeMidiaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public ConsultaDeMidiaService(ConsultaDeMidiaRepositorio repositorio, VerificadorDeAcesso verificador,
                                  HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 105 e 106: a mídia da parceria
    // ---------------------------------------------------------------------

    /** Rota 105. Para a marca de cota que não é Master, o corpo não traz os meses nem o mês das linhas. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object consulta(UsuarioAutenticado usuario, long parceriaId, String mes, String veiculo, String formato,
                           String tipoMidia, String unidade) {
        Dados dados = dados(usuario, parceriaId, mes, veiculo, formato, tipoMidia, unidade);
        List<TotalPorUnidadeDto> totais = totais(dados.linhas());
        FiltrosDto filtros = new FiltrosDto(dados.mes(), veiculo, formato, tipoMidia, unidade);
        if (!dados.recortePorMes()) {
            return new ConsultaDeMidiaSemMesDto(parceriaId, filtros, totais, dados.linhas().stream()
                    .map(ConsultaDeMidiaService::semMes).toList());
        }
        List<String> meses = repositorio.mesesDaParceria(parceriaId, veiculo, formato, tipoMidia, unidade);
        return new ConsultaDeMidiaDto(parceriaId, filtros, meses, totais, dados.linhas().stream()
                .map(l -> comMes(l, dados.mes())).toList());
    }

    /** Rota 106 em .xlsx. [HISTORICO] DOWNLOAD na entidade midia. */
    @Transactional
    public Arquivo xlsx(UsuarioAutenticado usuario, long parceriaId, String mes, String veiculo, String formato,
                        String tipoMidia, String unidade, HttpServletRequest requisicao) {
        Dados dados = dados(usuario, parceriaId, mes, veiculo, formato, tipoMidia, unidade);
        byte[] bytes = PlanilhaXlsx.gerar(List.of(new PlanilhaXlsx.Aba("Mídia",
                comCabecalho(dados.linhas(), dados.mes(), false))));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, parceriaId, ENTIDADE_NO_HISTORICO, parceriaId, null, null, "xlsx",
                requisicao);
        return new Arquivo(nomeDaParceria(dados.parceria(), dados.mes(), ".xlsx"), PlanilhaXlsx.TIPO_MIME, bytes);
    }

    /** Rota 106 em CSV (UTF-8 com BOM, ponto e vírgula). */
    @Transactional
    public Arquivo csv(UsuarioAutenticado usuario, long parceriaId, String mes, String veiculo, String formato,
                       String tipoMidia, String unidade, HttpServletRequest requisicao) {
        Dados dados = dados(usuario, parceriaId, mes, veiculo, formato, tipoMidia, unidade);
        byte[] bytes = Csv.gerar(CABECALHO, planilha(dados.linhas(), dados.mes(), false));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, parceriaId, ENTIDADE_NO_HISTORICO, parceriaId, null, null, "csv",
                requisicao);
        return new Arquivo(nomeDaParceria(dados.parceria(), dados.mes(), ".csv"), "text/csv; charset=UTF-8", bytes);
    }

    // ---------------------------------------------------------------------
    // Rota 107 e 108: a mídia da RBS
    // ---------------------------------------------------------------------

    /** Rota 107. Sem eventoId, vale a edição mais nova. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConsultaDaRbsDto consultaDaRbs(UsuarioAutenticado usuario, Long eventoId, Long parceriaId, String cota,
                                          String mes, String veiculo, String formato, String tipoMidia, String unidade) {
        DadosDaRbs dados = dadosDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade);
        List<String> meses = repositorio.mesesDaRbs(dados.eventoId(), parceriaId, cota, veiculo, formato, tipoMidia, unidade);
        return new ConsultaDaRbsDto(
                new FiltrosDaRbsDto(dados.eventoId(), parceriaId, cota, dados.mes(), veiculo, formato, tipoMidia, unidade),
                meses, totais(dados.linhas()), dados.linhas().stream().map(l -> daRbs(l, dados.mes())).toList());
    }

    /** Rota 108 em .xlsx, com a coluna Marca primeiro. */
    @Transactional
    public Arquivo xlsxDaRbs(UsuarioAutenticado usuario, Long eventoId, Long parceriaId, String cota, String mes,
                             String veiculo, String formato, String tipoMidia, String unidade, HttpServletRequest requisicao) {
        DadosDaRbs dados = dadosDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade);
        byte[] bytes = PlanilhaXlsx.gerar(List.of(new PlanilhaXlsx.Aba("Mídia",
                comCabecalho(dados.linhas(), dados.mes(), true))));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, null, ENTIDADE_NO_HISTORICO, dados.eventoId(), null, null,
                "xlsx da edição " + dados.edicao(), requisicao);
        return new Arquivo(nomeDaEdicao(dados.edicao(), dados.mes(), ".xlsx"), PlanilhaXlsx.TIPO_MIME, bytes);
    }

    /** Rota 108 em CSV, com a coluna Marca primeiro. */
    @Transactional
    public Arquivo csvDaRbs(UsuarioAutenticado usuario, Long eventoId, Long parceriaId, String cota, String mes,
                            String veiculo, String formato, String tipoMidia, String unidade, HttpServletRequest requisicao) {
        DadosDaRbs dados = dadosDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade);
        List<String> cabecalho = new ArrayList<>();
        cabecalho.add("Marca");
        cabecalho.addAll(CABECALHO);
        byte[] bytes = Csv.gerar(cabecalho, planilha(dados.linhas(), dados.mes(), true));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, null, ENTIDADE_NO_HISTORICO, dados.eventoId(), null, null,
                "csv da edição " + dados.edicao(), requisicao);
        return new Arquivo(nomeDaEdicao(dados.edicao(), dados.mes(), ".csv"), "text/csv; charset=UTF-8", bytes);
    }

    // ---------------------------------------------------------------------
    // Permissão e recorte
    // ---------------------------------------------------------------------

    /** [PERMISSAO] Nesta ordem: 404 da parceria, 403 da aba MIDIA, 422 do mês para quem não é Master. */
    private Dados dados(UsuarioAutenticado usuario, long parceriaId, String mes, String veiculo, String formato,
                        String tipoMidia, String unidade) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, "MIDIA");
        Parceria parceria = repositorio.parceria(parceriaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        boolean recortePorMes = podeRecortar(usuario, parceria);
        String recorte = mesDoRecorte(usuario, parceria, mes);
        List<LinhaDoPlano> linhas = repositorio.linhasDaParceria(parceriaId, recorte, veiculo, formato, tipoMidia, unidade);
        List<ArquivoDaLinha> arquivos = repositorio.arquivosDaParceria(parceriaId, recorte, veiculo, formato, tipoMidia, unidade);
        return new Dados(parceria, recorte, recortePorMes, calcular(linhas, arquivos));
    }

    /** [PERMISSAO] Só a RBS. Sem eventoId, a edição mais nova; evento que não existe dá 404. */
    private DadosDaRbs dadosDaRbs(UsuarioAutenticado usuario, Long eventoId, Long parceriaId, String cota, String mes,
                                  String veiculo, String formato, String tipoMidia, String unidade) {
        verificador.exigirRbs(usuario);
        long evento = eventoId != null ? eventoId
                : repositorio.eventoMaisNovo().orElseThrow(() -> new NaoEncontradoException(FRASE_DA_EDICAO));
        int edicao = repositorio.edicaoDoEvento(evento).orElseThrow(() -> new NaoEncontradoException(FRASE_DA_EDICAO));
        String recorte = mesValido(mes);
        List<LinhaDoPlano> linhas = repositorio.linhasDaRbs(evento, parceriaId, cota, recorte, veiculo, formato, tipoMidia, unidade);
        List<ArquivoDaLinha> arquivos = repositorio.arquivosDaRbs(evento, parceriaId, cota, recorte, veiculo, formato, tipoMidia, unidade);
        return new DadosDaRbs(evento, edicao, recorte, calcular(linhas, arquivos));
    }

    /**
     * [PERMISSAO] O recorte por mês é das cotas Master e da RBS (M7). Para a marca de outra cota, qualquer mês (até
     * malformado) dá o 422 da cota, como no contrato; para quem pode, o mês fora de AAAA-MM dá o 422 do formato.
     */
    private static String mesDoRecorte(UsuarioAutenticado usuario, Parceria parceria, String mes) {
        if (mes == null) {
            return null;
        }
        if (!podeRecortar(usuario, parceria)) {
            throw new DadosInvalidosException(Map.of("mes", MES_SO_MASTER));
        }
        return mesValido(mes);
    }

    /** [PERMISSAO] O recorte por mês é das cotas Master (M7) e da RBS. */
    private static boolean podeRecortar(UsuarioAutenticado usuario, Parceria parceria) {
        return usuario.ehRbs() || "MASTER".equals(parceria.cota());
    }

    /** [VALIDACAO] O mês do recorte: AAAA-MM; fora disso, 422 em campos.mes. */
    private static String mesValido(String mes) {
        if (mes == null) {
            return null;
        }
        String recorte = mes.strip();
        if (!MES.matcher(recorte).matches()) {
            throw new DadosInvalidosException(Map.of("mes", ComprovacaoMensalService.MES_INVALIDO));
        }
        return recorte;
    }

    // ---------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------

    private static List<LinhaCalculada> calcular(List<LinhaDoPlano> linhas, List<ArquivoDaLinha> arquivos) {
        Map<Long, List<ArquivoDaLinha>> porLinha = new LinkedHashMap<>();
        for (ArquivoDaLinha arquivo : arquivos) {
            porLinha.computeIfAbsent(arquivo.aproveitamentoId(), k -> new ArrayList<>()).add(arquivo);
        }
        List<LinhaCalculada> calculadas = new ArrayList<>(linhas.size());
        for (LinhaDoPlano linha : linhas) {
            List<ComprovanteDto> comprovantes = new ArrayList<>();
            Set<Long> arquivosVistos = new HashSet<>();
            List<String> links = new ArrayList<>();
            Set<String> linksVistos = new HashSet<>();
            for (ArquivoDaLinha arquivo : porLinha.getOrDefault(linha.aproveitamentoId(), List.of())) {
                if (arquivo.arquivoId() != null && arquivosVistos.add(arquivo.arquivoId())) {
                    comprovantes.add(new ComprovanteDto(arquivo.arquivoId(), arquivo.nomeOriginal(),
                            tipoDoArquivo(arquivo.nomeOriginal())));
                }
                if (links.size() < 5 && arquivo.link() != null && HostDoLink.valido(arquivo.link())
                        && linksVistos.add(arquivo.link())) {
                    links.add(arquivo.link());
                }
            }
            calculadas.add(new LinhaCalculada(linha.parceriaId(), linha.aproveitamentoId(), linha.patrocinador(),
                    linha.cota(), linha.veiculo(), linha.formato(), linha.espacoPrograma(), linha.tipoMidia(),
                    linha.unidade(), linha.previsto(), linha.exibido(), percentual(linha.exibido(), linha.previsto()),
                    linha.impacto(), linha.exibicoes(), comprovantes, links));
        }
        return calculadas;
    }

    private static List<TotalPorUnidadeDto> totais(List<LinhaCalculada> linhas) {
        Map<String, Acumulado> porUnidade = new LinkedHashMap<>();
        for (LinhaCalculada linha : linhas) {
            porUnidade.computeIfAbsent(linha.unidade(), k -> new Acumulado()).somar(linha);
        }
        return porUnidade.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ConsultaDeMidiaService::compararUnidades))
                .map(e -> new TotalPorUnidadeDto(e.getKey(), e.getValue().previsto, e.getValue().exibido,
                        percentual(e.getValue().exibido, e.getValue().previsto), e.getValue().impacto))
                .toList();
    }

    private static int compararUnidades(String uma, String outra) {
        int posicaoUma = ORDEM_DAS_UNIDADES.indexOf(uma);
        int posicaoOutra = ORDEM_DAS_UNIDADES.indexOf(outra);
        posicaoUma = posicaoUma < 0 ? ORDEM_DAS_UNIDADES.size() : posicaoUma;
        posicaoOutra = posicaoOutra < 0 ? ORDEM_DAS_UNIDADES.size() : posicaoOutra;
        return posicaoUma != posicaoOutra ? Integer.compare(posicaoUma, posicaoOutra) : uma.compareTo(outra);
    }

    /** [RELATORIO] O percentual é cortado na primeira casa, como o fn_pct_truncado; pode passar de 100. */
    private static BigDecimal percentual(BigDecimal exibido, BigDecimal previsto) {
        if (previsto == null || previsto.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return exibido.multiply(BigDecimal.valueOf(100)).divide(previsto, 1, RoundingMode.DOWN);
    }

    private static LinhaDeMidiaDto comMes(LinhaCalculada l, String mes) {
        return new LinhaDeMidiaDto(l.aproveitamentoId(), l.veiculo(), l.formato(), l.espacoPrograma(), l.tipoMidia(),
                l.unidade(), mes, l.previsto(), l.exibido(), l.percentual(), l.impacto(), l.exibicoes(), l.comprovantes(),
                l.links());
    }

    private static LinhaDeMidiaSemMesDto semMes(LinhaCalculada l) {
        return new LinhaDeMidiaSemMesDto(l.aproveitamentoId(), l.veiculo(), l.formato(), l.espacoPrograma(), l.tipoMidia(),
                l.unidade(), l.previsto(), l.exibido(), l.percentual(), l.impacto(), l.exibicoes(), l.comprovantes(),
                l.links());
    }

    private static LinhaDaRbsDto daRbs(LinhaCalculada l, String mes) {
        return new LinhaDaRbsDto(l.aproveitamentoId(), new ParceriaDaLinhaDto(l.parceriaId(), l.patrocinador(), l.cota()),
                l.veiculo(), l.formato(), l.espacoPrograma(), l.tipoMidia(), l.unidade(), mes, l.previsto(), l.exibido(),
                l.percentual(), l.impacto(), l.exibicoes(), l.comprovantes(), l.links());
    }

    private static String tipoDoArquivo(String nome) {
        return TipoDeArquivo.daExtensao(nome).map(Enum::name).orElse(null);
    }

    // ---------------------------------------------------------------------
    // Planilha
    // ---------------------------------------------------------------------

    /** O cabeçalho e as linhas do .xlsx; {@code comMarca} põe a marca primeiro (rota 108). */
    private static List<List<Object>> comCabecalho(List<LinhaCalculada> linhas, String mes, boolean comMarca) {
        List<List<Object>> tabela = new ArrayList<>(linhas.size() + 1);
        List<Object> cabecalho = new ArrayList<>(CABECALHO.size() + 1);
        if (comMarca) {
            cabecalho.add("Marca");
        }
        cabecalho.addAll(CABECALHO);
        tabela.add(cabecalho);
        tabela.addAll(planilha(linhas, mes, comMarca));
        return tabela;
    }

    /** As linhas do .xlsx e do CSV; {@code comMarca} põe a marca primeiro (rota 108). */
    private static List<List<Object>> planilha(List<LinhaCalculada> linhas, String mes, boolean comMarca) {
        List<List<Object>> tabela = new ArrayList<>(linhas.size());
        for (LinhaCalculada l : linhas) {
            List<Object> linha = new ArrayList<>(12);
            if (comMarca) {
                linha.add(l.patrocinador());
            }
            linha.add(mes == null ? "" : mes);
            linha.add(l.veiculo());
            linha.add(l.formato());
            linha.add(l.espacoPrograma());
            linha.add(l.tipoMidia());
            linha.add(l.unidade());
            linha.add(l.previsto());
            linha.add(l.exibido());
            linha.add(l.percentual());
            linha.add(l.impacto());
            linha.add(comprovantes(l.comprovantes()));
            tabela.add(linha);
        }
        return tabela;
    }

    private static String comprovantes(List<ComprovanteDto> comprovantes) {
        return String.join("; ", comprovantes.stream().map(ComprovanteDto::nome).filter(nome -> nome != null).toList());
    }

    private static String nomeDaParceria(Parceria parceria, String mes, String sufixo) {
        return "midia-" + ComprovacaoMensalService.apelido(parceria.patrocinador())
                + (mes == null ? "" : "-" + mes) + sufixo;
    }

    private static String nomeDaEdicao(int edicao, String mes, String sufixo) {
        return "midia-edicao-" + edicao + (mes == null ? "" : "-" + mes) + sufixo;
    }
}
