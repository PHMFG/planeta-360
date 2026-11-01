package br.com.rbs.portal.atlas.diagnostico;

import br.com.rbs.portal.atlas.AtlasService;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.DiagnosticoDaConversaDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.EtapaDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TurnoDaListaDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TurnoDetalhadoDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TurnoDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.UsuarioResumoDto;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.EtapaLida;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.FiltroDeTurnos;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.TurnoLido;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O diagnóstico lido (rotas 49 a 51 do docs/api.md). [ATLAS] [PERMISSAO] A rota 49 é da marca, só para o dono da conversa, e só com
 * o diagnóstico e a propriedade da marca ligados; as rotas 50 e 51 são da RBS, com o diagnóstico ligado. Desligado, o que a rota
 * guardaria dá 404, como uma rota que não existe. [ISOLAMENTO] Conversa de outra marca, de outro usuário ou que não existe dá 404
 * com a mesma frase (a mesma da rota 45); o turno que não existe, ou cujo id não é um UUID, também. Todo texto já vem sanitizado
 * do banco ({@link RegistroDoTraco}).
 */
@Service
@Transactional(readOnly = true)
public class DiagnosticoService {

    public static final String TURNO_NAO_ENCONTRADO = "Não encontramos este turno.";
    static final List<String> RESULTADOS = Arrays.stream(ResultadoDaEtapa.values()).map(Enum::name).toList();

    private final TracoRepositorio repositorio;
    private final AtlasService atlas;
    private final ModoDoAtlas modo;
    private final VerificadorDeAcesso verificador;

    public DiagnosticoService(TracoRepositorio repositorio, AtlasService atlas, ModoDoAtlas modo, VerificadorDeAcesso verificador) {
        this.repositorio = repositorio;
        this.atlas = atlas;
        this.modo = modo;
        this.verificador = verificador;
    }

    // ---------------------------------------------------------------------
    // Rota 49: o diagnóstico da conversa, para a marca dona dela
    // ---------------------------------------------------------------------

    /**
     * Rota 49. Os turnos da conversa, do mais antigo para o mais novo, com as etapas e os totais de tokens e custo. Só o dono da
     * conversa (a RBS já recebeu 403 antes); conversa de outro dá 404. Sem o diagnóstico ligado para a marca, 404. [PERMISSAO] O
     * {@code dados} das etapas sai sem o texto cru de erro (a mensagem da exceção e o corpo do erro do provedor): ver o SQL ou o que
     * um provedor respondeu não é da marca; a RBS os lê nas rotas 50 e 51.
     */
    public DiagnosticoDaConversaDto daConversa(UsuarioAutenticado usuario, long conversaId) {
        if (!modo.diagnosticoNaMarca()) {
            throw new NaoEncontradoException(AtlasService.CONVERSA_NAO_ENCONTRADA);
        }
        atlas.conversaDoDono(usuario, conversaId);
        List<TurnoLido> lidos = repositorio.turnosDaConversa(conversaId);
        Map<UUID, List<EtapaDto>> etapas = etapasPorTurno(lidos, true);
        List<TurnoDto> turnos = lidos.stream().map(l -> paraTurno(l, etapas.get(l.turno()))).toList();
        long entrada = turnos.stream().mapToLong(TurnoDto::tokensEntrada).sum();
        long saida = turnos.stream().mapToLong(TurnoDto::tokensSaida).sum();
        BigDecimal custo = turnos.stream().map(TurnoDto::custoUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DiagnosticoDaConversaDto(conversaId, entrada, saida, entrada + saida, custo, turnos);
    }

    // ---------------------------------------------------------------------
    // Rotas 50 e 51: os turnos de todas as conversas, para a RBS
    // ---------------------------------------------------------------------

    /**
     * Rota 50. Os turnos, do mais novo para o mais antigo, paginados. Filtros: {@code parceriaId}, {@code de} e {@code ate}
     * (datas AAAA-MM-DD, inclusive, no horário de Brasília, sobre o começo do turno) e {@code resultado}. Data ou resultado
     * inválidos dão 422 com o campo.
     */
    public PaginaDto<TurnoDaListaDto> turnos(UsuarioAutenticado usuario, Long parceriaId, String de, String ate, String resultado,
                                             int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        exigirLigado();
        FiltroDeTurnos filtro = filtro(parceriaId, de, ate, resultado);
        List<TurnoDaListaDto> itens = repositorio.turnos(filtro, pagina, tamanho).stream().map(DiagnosticoService::paraLista).toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contarTurnos(filtro));
    }

    /** Rota 51. O turno com as etapas, a parceria e quem conversou. Id que não é um UUID, ou que não existe, dá 404. */
    public TurnoDetalhadoDto turno(UsuarioAutenticado usuario, String id) {
        verificador.exigirRbs(usuario);
        exigirLigado();
        UUID turno;
        try {
            turno = UUID.fromString(id);
        } catch (IllegalArgumentException naoEUuid) {
            throw new NaoEncontradoException(TURNO_NAO_ENCONTRADO);
        }
        TurnoLido lido = repositorio.turno(turno).orElseThrow(() -> new NaoEncontradoException(TURNO_NAO_ENCONTRADO));
        TurnoDto base = paraTurno(lido, etapasPorTurno(List.of(lido), false).get(turno));
        return new TurnoDetalhadoDto(base.turno(), lido.conversaId(), lido.parceriaId(), lido.parceria(), dono(lido), base.mensagemId(),
                base.iniciadoEm(), base.duracaoMs(), base.resultado(), base.tokensEntrada(), base.tokensSaida(), base.custoUsd(),
                base.etapas());
    }

    // ---------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------

    private void exigirLigado() {
        if (!modo.diagnosticoLigado()) {
            throw new NaoEncontradoException();
        }
    }

    /** As etapas de cada turno. {@code paraAMarca}: sem o texto cru de erro no {@code dados} (veja {@link TracoRepositorio}). */
    private Map<UUID, List<EtapaDto>> etapasPorTurno(List<TurnoLido> turnos, boolean paraAMarca) {
        Map<UUID, List<EtapaDto>> porTurno = new LinkedHashMap<>();
        for (EtapaLida etapa : repositorio.etapasDosTurnos(turnos.stream().map(TurnoLido::turno).toList(), paraAMarca)) {
            porTurno.computeIfAbsent(etapa.turno(), turno -> new ArrayList<>()).add(new EtapaDto(etapa.ordem(), etapa.etapa(),
                    etapa.resultado(), Datas.emBrasilia(etapa.iniciadaEm()), etapa.duracaoMs(), etapa.mensagemId(), etapa.dados()));
        }
        return porTurno;
    }

    private static TurnoDto paraTurno(TurnoLido l, List<EtapaDto> etapas) {
        return new TurnoDto(l.turno().toString(), l.mensagemId(), Datas.emBrasilia(l.iniciadoEm()), duracao(l), l.resultado(),
                l.tokensEntrada(), l.tokensSaida(), l.custoUsd(), etapas == null ? List.of() : etapas);
    }

    private static TurnoDaListaDto paraLista(TurnoLido l) {
        return new TurnoDaListaDto(l.turno().toString(), l.conversaId(), l.parceriaId(), l.parceria(), dono(l), l.mensagemId(),
                Datas.emBrasilia(l.iniciadoEm()), duracao(l), l.resultado(), l.etapas(), l.tokensEntrada(), l.tokensSaida(), l.custoUsd());
    }

    private static UsuarioResumoDto dono(TurnoLido l) {
        return new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil());
    }

    /** Do começo da primeira etapa sequencial ao fim da última. */
    private static Integer duracao(TurnoLido l) {
        if (l.iniciadoEm() == null || l.terminadoEm() == null) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Duration.between(l.iniciadoEm(), l.terminadoEm()).toMillis());
    }

    // ---------------------------------------------------------------------
    // Filtros
    // ---------------------------------------------------------------------

    /** [VALIDACAO] Data fora do formato AAAA-MM-DD, período invertido e resultado que não existe viram 422 com o campo. */
    private static FiltroDeTurnos filtro(Long parceriaId, String de, String ate, String resultado) {
        Map<String, String> campos = new LinkedHashMap<>();
        LocalDate dia1 = data("de", de, campos);
        LocalDate dia2 = data("ate", ate, campos);
        if (dia1 != null && dia2 != null && dia2.isBefore(dia1)) {
            campos.putIfAbsent("ate", "A data final não pode ser anterior à inicial.");
        }
        String resultadoLimpo = resultado == null || resultado.isBlank() ? null : resultado.trim().toUpperCase(Locale.ROOT);
        if (resultadoLimpo != null && !RESULTADOS.contains(resultadoLimpo)) {
            campos.put("resultado", "Resultado inválido. Use " + RESULTADOS.stream().collect(Collectors.joining(", ")) + ".");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        // O dia inteiro, no horário de Brasília: "de" começa à meia-noite; "ate" vale até o fim do dia (exclui o dia seguinte).
        OffsetDateTime inicio = dia1 == null ? null : dia1.atStartOfDay(Datas.BRASILIA).toOffsetDateTime();
        OffsetDateTime fim = dia2 == null ? null : dia2.plusDays(1).atStartOfDay(Datas.BRASILIA).toOffsetDateTime();
        return new FiltroDeTurnos(parceriaId, inicio, fim, resultadoLimpo);
    }

    private static LocalDate data(String campo, String valor, Map<String, String> campos) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException invalida) {
            campos.put(campo, "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-10-23.");
            return null;
        }
    }
}
