package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.HistoricoConsultaRepositorio.Filtro;
import br.com.rbs.portal.materiais.HistoricoConsultaRepositorio.Linha;
import br.com.rbs.portal.materiais.MateriaisDtos.HistoricoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.HistoricoGeralDto;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ParceriaDoHistoricoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura do histórico: o da parceria (rota 20), o geral da RBS (21) e o geral em CSV (22). Atende os itens 4 e 5 da Entrega 2.
 * [HISTORICO] [ISOLAMENTO] O patrocinador só lê o histórico da parceria dele, sem IP e sem navegador (dados internos da RBS),
 * sem login/logout, que pertencem à conta, e sem a geração e a validação do relatório, que são etapas internas da RBS (ele
 * vê a disponibilização). [PERMISSAO] O geral e o CSV são só da RBS.
 */
@Service
public class HistoricoConsultaService {

    /** O CSV cabe na memória; passou disso, a RBS precisa filtrar por período. */
    static final int LIMITE_DE_LINHAS_DO_CSV = 50_000;

    static final List<String> CABECALHO_DO_CSV = List.of("id", "ocorrido_em", "acao", "entidade", "entidade_id", "parceria",
            "usuario", "perfil", "valor_anterior", "valor_novo", "detalhe", "ip", "user_agent");

    /** Um CSV pronto para virar resposta: o nome do arquivo, o cabeçalho e as linhas (veja Csv.resposta). */
    public record ArquivoCsv(String nome, List<String> cabecalho, List<List<Object>> linhas) {
    }

    private final HistoricoConsultaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;

    public HistoricoConsultaService(HistoricoConsultaRepositorio repositorio, VerificadorDeAcesso verificador) {
        this.repositorio = repositorio;
        this.verificador = verificador;
    }

    /**
     * Rota 20. [ISOLAMENTO] Parceria de outro dá 404. Login, logout e falha de login nunca entram aqui. [PERMISSAO] Para o
     * patrocinador também ficam de fora GERACAO_RELATORIO e VALIDACAO (as linhas, o total e o filtro por ação); a RBS vê tudo.
     */
    @Transactional(readOnly = true)
    public PaginaDto<HistoricoDto> daParceria(UsuarioAutenticado usuario, long parceriaId, String acao, String entidade,
                                              String de, String ate, int pagina, int tamanho) {
        verificador.exigirParceria(usuario, parceriaId);
        Filtro filtro = filtro(parceriaId, null, acao, entidade, de, ate, null, true, !usuario.ehRbs());
        List<HistoricoDto> itens = repositorio.pagina(filtro, pagina, tamanho).stream()
                .map(l -> paraHistorico(l, usuario.ehRbs())).toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contar(filtro));
    }

    /** Rota 21. [PERMISSAO] Só a RBS; vem com os acessos (login, logout, falha de login) e o IP. */
    @Transactional(readOnly = true)
    public PaginaDto<HistoricoGeralDto> geral(UsuarioAutenticado usuario, Long parceriaId, Long usuarioId, String acao,
                                              String entidade, String de, String ate, String texto, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        Filtro filtro = filtro(parceriaId, usuarioId, acao, entidade, de, ate, texto, false, false);
        List<HistoricoGeralDto> itens = repositorio.pagina(filtro, pagina, tamanho).stream().map(HistoricoConsultaService::paraGeral).toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contar(filtro));
    }

    /**
     * Rota 22. Os mesmos filtros da rota 21, sem paginação. Mais de 50 mil linhas dá 422 pedindo para filtrar por período.
     * [CARGA] A neutralização de fórmulas (=, +, -, @) e os decimais com vírgula são do Csv.
     */
    @Transactional(readOnly = true)
    public ArquivoCsv csv(UsuarioAutenticado usuario, Long parceriaId, Long usuarioId, String acao, String entidade, String de,
                          String ate, String texto) {
        verificador.exigirRbs(usuario);
        Filtro filtro = filtro(parceriaId, usuarioId, acao, entidade, de, ate, texto, false, false);
        long total = repositorio.contar(filtro);
        if (total > LIMITE_DE_LINHAS_DO_CSV) {
            String dica = "Restrinja o período com de e ate.";
            throw new DadosInvalidosException("O histórico filtrado tem " + total + " linhas e o CSV aceita no máximo "
                    + LIMITE_DE_LINHAS_DO_CSV + ". Filtre por período (de e ate) e tente de novo.",
                    Map.of("de", dica, "ate", dica));
        }
        List<List<Object>> linhas = new ArrayList<>();
        for (Linha l : repositorio.todas(filtro)) {
            linhas.add(Csv.linha(l.id(), l.ocorridoEm(), l.acao(), l.entidade(), l.entidadeId(), l.patrocinador(), l.usuarioNome(),
                    l.usuarioPerfil(), l.valorAnterior(), l.valorNovo(), l.detalhe(), l.ip(), l.userAgent()));
        }
        return new ArquivoCsv("historico-" + Datas.hoje() + ".csv", CABECALHO_DO_CSV, linhas);
    }

    // ---------------------------------------------------------------------
    // Filtros e montagem
    // ---------------------------------------------------------------------

    /** [VALIDACAO] Ação que não existe, data fora do formato AAAA-MM-DD e período invertido viram 422 com o campo. */
    private static Filtro filtro(Long parceriaId, Long usuarioId, String acao, String entidade, String de, String ate,
                                 String texto, boolean semAcessos, boolean semEtapasInternas) {
        Map<String, String> campos = new LinkedHashMap<>();
        List<String> acoes = new ArrayList<>();
        if (acao != null) {
            for (String valor : acao.split(",")) {
                if (valor.isBlank()) {
                    continue;
                }
                try {
                    acoes.add(AcaoHistorico.valueOf(valor.trim().toUpperCase(Locale.ROOT)).name());
                } catch (IllegalArgumentException desconhecida) {
                    campos.putIfAbsent("acao", "Ação inválida: " + valor.trim() + ".");
                }
            }
        }
        LocalDate dia1 = data("de", de, campos);
        LocalDate dia2 = data("ate", ate, campos);
        if (dia1 != null && dia2 != null && dia2.isBefore(dia1)) {
            campos.putIfAbsent("ate", "A data final não pode ser anterior à inicial.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        // O dia inteiro, no horário de Brasília: "de" começa à meia-noite; "ate" vale até o fim do dia (exclui o dia seguinte).
        OffsetDateTime inicio = dia1 == null ? null : dia1.atStartOfDay(Datas.BRASILIA).toOffsetDateTime();
        OffsetDateTime fim = dia2 == null ? null : dia2.plusDays(1).atStartOfDay(Datas.BRASILIA).toOffsetDateTime();
        String entidadeLimpa = entidade == null || entidade.isBlank() ? null : entidade.trim().toLowerCase(Locale.ROOT);
        String textoLimpo = texto == null || texto.isBlank() ? null : texto.trim();
        return new Filtro(parceriaId, usuarioId, acoes, entidadeLimpa, inicio, fim, textoLimpo, semAcessos, semEtapasInternas);
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

    private static UsuarioResumoDto autor(Linha l) {
        return l.usuarioId() == null ? null : new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil());
    }

    /** [PERMISSAO] IP e navegador são dados internos da RBS: o patrocinador recebe null nos dois. */
    private static HistoricoDto paraHistorico(Linha l, boolean comRede) {
        return new HistoricoDto(l.id(), l.acao(), l.entidade(), l.entidadeId(), l.valorAnterior(), l.valorNovo(), l.detalhe(),
                autor(l), Datas.emBrasilia(l.ocorridoEm()), comRede ? l.ip() : null, comRede ? l.userAgent() : null);
    }

    private static HistoricoGeralDto paraGeral(Linha l) {
        ParceriaDoHistoricoDto parceria = l.parceriaId() == null ? null : new ParceriaDoHistoricoDto(l.parceriaId(), l.patrocinador());
        return new HistoricoGeralDto(l.id(), parceria, l.acao(), l.entidade(), l.entidadeId(), l.valorAnterior(), l.valorNovo(),
                l.detalhe(), autor(l), Datas.emBrasilia(l.ocorridoEm()), l.ip(), l.userAgent());
    }
}
