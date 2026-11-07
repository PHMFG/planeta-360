package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.PlanilhaXlsx;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalDtos.ComprovacaoMensalDto;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalDtos.LinhaMensalDto;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalDtos.TotalMensalDto;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalRepositorio.LinhaMensal;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalRepositorio.Master;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalRepositorio.Parceria;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A comprovação mensal de mídia (rotas 88 a 91; R3.2 e R3.3): mês x tipo de mídia x unidade, em JSON, {@code .xlsx} e
 * {@code .csv}, e o lote da edição (uma aba por parceria Master). [PERMISSAO] Só a cota Master e a RBS: a marca precisa ser da
 * parceria (404), ter a aba MIDIA aberta (403) e ser Master (403 com a frase de cota). [RELATORIO] Nada é estimado: a soma
 * vem de {@code veiculacao.quantidade} e unidades diferentes nunca se misturam. [HISTORICO] Cada arquivo baixado grava DOWNLOAD.
 */
@Service
public class ComprovacaoMensalService {

    /** A frase do 403 de quem é de uma cota que não recebe a planilha. */
    public static final String SO_AS_MASTER = "A comprovação mensal é das cotas Master.";

    /** A frase do 422 do ?mes= fora de AAAA-MM nas rotas 89 e 90. */
    public static final String MES_INVALIDO = "Use o mês no formato AAAA-MM.";

    /** O mês do recorte: AAAA-MM, como no contrato. */
    private static final java.util.regex.Pattern MES = java.util.regex.Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    public static final Set<String> COTAS_COM_PLANILHA_MENSAL = Set.of("MASTER");

    static final String ENTIDADE_NO_HISTORICO = "comprovacao_mensal";

    private static final List<Object> CABECALHO = List.of("Marca", "Mês", "Tipo de mídia", "Unidade", "Exibido no mês", "Previsto no plano");
    private static final List<Object> CABECALHO_DOS_TOTAIS = List.of("Marca", "Mês", "Unidade", "Exibido no mês");

    static final Map<String, String> TIPOS = Map.of("ASSINADA", "Assinada", "PROPRIA", "Própria", "MERCHANDISING",
            "Merchandising", "BRANDED", "Branded", "CONTEUDO_DE_MARCA", "Conteúdo de marca");
    static final Map<String, String> UNIDADES = Map.of("INSERCAO", "Inserções", "CPM", "CPM", "APLICACAO", "Aplicações");

    /** Um arquivo pronto para baixar. */
    public record Arquivo(String nome, String tipoMime, byte[] bytes) {
    }

    private final ComprovacaoMensalRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public ComprovacaoMensalService(ComprovacaoMensalRepositorio repositorio, VerificadorDeAcesso verificador,
                                    HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    /** Rota 88. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ComprovacaoMensalDto comprovacao(UsuarioAutenticado usuario, long parceriaId) {
        Parceria parceria = autorizar(usuario, parceriaId);
        return montar(parceria, null);
    }

    /** Rota 89. Com {@code mes}, o arquivo traz só aquele mês. [HISTORICO] DOWNLOAD na entidade comprovacao_mensal. */
    @Transactional
    public Arquivo xlsx(UsuarioAutenticado usuario, long parceriaId, String mes, HttpServletRequest requisicao) {
        Parceria parceria = autorizar(usuario, parceriaId);
        String recorte = mesValido(mes);
        ComprovacaoMensalDto dto = montar(parceria, recorte);
        byte[] bytes = PlanilhaXlsx.gerar(List.of(aba("Comprovação mensal", dto), abaDosTotais(dto)));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, parceriaId, ENTIDADE_NO_HISTORICO, parceriaId, null, null, "xlsx", requisicao);
        return new Arquivo("comprovacao-mensal-" + apelido(parceria.patrocinador()) + ".xlsx", PlanilhaXlsx.TIPO_MIME, bytes);
    }

    /** Rota 90. Com {@code mes}, o arquivo traz só aquele mês. */
    @Transactional
    public Arquivo csv(UsuarioAutenticado usuario, long parceriaId, String mes, HttpServletRequest requisicao) {
        Parceria parceria = autorizar(usuario, parceriaId);
        String recorte = mesValido(mes);
        ComprovacaoMensalDto dto = montar(parceria, recorte);
        byte[] bytes = Csv.gerar(strings(CABECALHO), linhasDaTabela(dto));
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, parceriaId, ENTIDADE_NO_HISTORICO, parceriaId, null, null, "csv", requisicao);
        return new Arquivo("comprovacao-mensal-" + apelido(parceria.patrocinador()) + ".csv", "text/csv; charset=UTF-8", bytes);
    }

    /** Rota 91. [PERMISSAO] Só a RBS. Uma aba por parceria Master ATIVA da edição, na ordem do nome da marca. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Arquivo lote(UsuarioAutenticado usuario, long eventoId, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        int edicao = repositorio.edicaoDoEvento(eventoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta edição do festival."));
        List<PlanilhaXlsx.Aba> abas = new ArrayList<>();
        for (Master master : repositorio.masters(eventoId)) {
            Parceria parceria = new Parceria(master.id(), master.patrocinador(), "MASTER", edicao);
            abas.add(aba(master.patrocinador(), montar(parceria, null)));
        }
        if (abas.isEmpty()) {
            abas.add(new PlanilhaXlsx.Aba("Sem cota Master", List.of(List.of("Marca"))));
        }
        historico.registrar(AcaoHistorico.DOWNLOAD, usuario, null, ENTIDADE_NO_HISTORICO, eventoId, null, null,
                "lote xlsx da edição " + edicao, requisicao);
        return new Arquivo("comprovacao-mensal-edicao-" + edicao + ".xlsx", PlanilhaXlsx.TIPO_MIME, PlanilhaXlsx.gerar(abas));
    }

    // ---------------------------------------------------------------------
    // Permissão, nesta ordem: 404 da parceria, 403 da aba, 403 da cota
    // ---------------------------------------------------------------------

    private Parceria autorizar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, "MIDIA");
        Parceria parceria = repositorio.parceria(parceriaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        if (!usuario.ehRbs() && !COTAS_COM_PLANILHA_MENSAL.contains(parceria.cota())) {
            throw new SemPermissaoException(SO_AS_MASTER);
        }
        return parceria;
    }

    // ---------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------

    /** [VALIDACAO] O {@code ?mes=} das rotas 89 e 90: nulo = todos os meses; fora de AAAA-MM, 422 em {@code campos.mes}. */
    private static String mesValido(String mes) {
        if (mes == null) {
            return null;
        }
        String recorte = mes.strip();
        if (!MES.matcher(recorte).matches()) {
            throw new DadosInvalidosException(java.util.Map.of("mes", MES_INVALIDO));
        }
        return recorte;
    }

    private ComprovacaoMensalDto montar(Parceria parceria, String mes) {
        List<LinhaMensal> linhas = repositorio.linhas(parceria.id(), mes);
        List<LinhaMensalDto> dtos = linhas.stream()
                .map(l -> new LinhaMensalDto(l.mes(), l.tipoMidia(), TIPOS.getOrDefault(l.tipoMidia(), l.tipoMidia()), l.unidade(),
                        UNIDADES.getOrDefault(l.unidade(), l.unidade()), l.exibido(), l.previsto()))
                .toList();
        // [RELATORIO] O total do mês é por unidade: nunca soma inserções com CPM.
        Map<String, BigDecimal> somas = new LinkedHashMap<>();
        for (LinhaMensal l : linhas) {
            somas.merge(l.mes() + "|" + l.unidade(), l.exibido(), BigDecimal::add);
        }
        List<TotalMensalDto> totais = new ArrayList<>();
        somas.forEach((chave, soma) -> {
            String[] partes = chave.split("\\|");
            totais.add(new TotalMensalDto(partes[0], partes[1], UNIDADES.getOrDefault(partes[1], partes[1]), soma));
        });
        return new ComprovacaoMensalDto(parceria.id(), parceria.patrocinador(), dtos, totais);
    }

    private static List<List<Object>> linhasDaTabela(ComprovacaoMensalDto dto) {
        List<List<Object>> linhas = new ArrayList<>();
        for (LinhaMensalDto l : dto.linhas()) {
            linhas.add(Csv.linha(dto.patrocinador(), l.mes(), l.tipoMidiaNome(), l.unidadeNome(), l.exibido(), l.previsto()));
        }
        return linhas;
    }

    private static PlanilhaXlsx.Aba aba(String nome, ComprovacaoMensalDto dto) {
        List<List<Object>> linhas = new ArrayList<>();
        linhas.add(CABECALHO);
        linhas.addAll(linhasDaTabela(dto));
        return new PlanilhaXlsx.Aba(nome, linhas);
    }

    private static PlanilhaXlsx.Aba abaDosTotais(ComprovacaoMensalDto dto) {
        List<List<Object>> linhas = new ArrayList<>();
        linhas.add(CABECALHO_DOS_TOTAIS);
        for (TotalMensalDto t : dto.totais()) {
            linhas.add(Csv.linha(dto.patrocinador(), t.mes(), t.unidadeNome(), t.exibido()));
        }
        return new PlanilhaXlsx.Aba("Totais por mês", linhas);
    }

    private static List<String> strings(List<Object> cabecalho) {
        return cabecalho.stream().map(Object::toString).toList();
    }

    /** Só letras minúsculas, números e hífen, para o nome do arquivo nunca carregar o nome da marca como veio. */
    static String apelido(String nome) {
        String ascii = Normalizer.normalize(nome == null ? "" : nome, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "marca" : slug.substring(0, Math.min(slug.length(), 60));
    }
}
