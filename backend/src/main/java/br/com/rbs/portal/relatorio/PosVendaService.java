package br.com.rbs.portal.relatorio;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.HostDoLink;
import br.com.rbs.portal.materiais.LinksDeMidiaService;
import br.com.rbs.portal.relatorio.PosVendaDtos.AnexoDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.AudienciaDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.InformarResultadoRequisicao;
import br.com.rbs.portal.relatorio.PosVendaDtos.LinkDePostDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.LinkDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.PosVendaDaEdicaoDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.PosVendaDaParceriaDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.PublicarRequisicao;
import br.com.rbs.portal.relatorio.PosVendaDtos.ResultadoDto;
import br.com.rbs.portal.relatorio.PosVendaRepositorio.Linha;
import br.com.rbs.portal.relatorio.PosVendaRepositorio.ParceriaTravada;
import br.com.rbs.portal.relatorio.PosVendaRepositorio.Resultado;
import br.com.rbs.portal.relatorio.RelatorioDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * O anexo "Pós-venda da edição" (R8.1, rotas 96 a 99) e os números da edição (R8.2, rotas 100 e 101). [RELATORIO] O anexo vem depois das
 * 6 seções do relatório e não toca nelas: o catálogo de 17 indicadores e a função que gera o relatório continuam como estavam. A RBS
 * publica uma versão por parceria (uma nova substitui a anterior; retirar não apaga), e a marca só a vê com o relatório DISPONIBILIZADO.
 * [PERMISSAO] Publicar, listar, retirar e os números são só da RBS (o patrocinador recebe 403 antes do corpo). [ISOLAMENTO] Parceria de
 * outra marca: 404. Parceria ENCERRADA recusa a escrita (409). [HISTORICO] CRIACAO, EXCLUSAO (a retirada) e os números, sem parceria: a
 * marca não os vê.
 */
@Service
public class PosVendaService {

    static final String PARCERIA_ENCERRADA = "Esta parceria está encerrada e não aceita alterações.";
    static final String NAO_DISPONIVEL = "O pós-venda desta edição ainda não está disponível.";
    static final int TEXTO_MAXIMO = 5000;
    static final int MAXIMO_DE_LINKS = 30;
    static final int MAXIMO_DE_AUDIENCIAS = 20;
    static final int TITULO_MAXIMO = 160;
    static final int URL_MAXIMA = 500;
    static final int VEICULO_MAXIMO = 80;
    static final int METRICA_MAXIMA = 80;
    static final int UNIDADE_MAXIMA = 20;
    static final int INDICADOR_MAXIMO = 80;
    static final int UNIDADE_DO_NUMERO_MAXIMA = 20;
    static final int FONTE_MAXIMA = 200;
    private static final BigDecimal LIMITE_DO_NUMERO = new BigDecimal("100000000000000");
    private static final String FRASE_DO_MAXIMO_VALOR = "O valor passa do máximo: use menos de 100.000.000.000.000.";
    private static final String FRASE_DO_MAXIMO_NUMERO = "O número passa do máximo: use menos de 100.000.000.000.000.";
    private static final List<String> TIPOS_DE_LINK = List.of("AFTERMOVIE", "CLIPPING", "OUTRO");
    private static final List<String> QUALIFICADORES = List.of("EXATO", "MAIS_DE");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<LinkDto>> LISTA_DE_LINKS = new TypeReference<>() {
    };
    private static final TypeReference<List<AudienciaDto>> LISTA_DE_AUDIENCIAS = new TypeReference<>() {
    };

    private final PosVendaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final LinksDeMidiaService links;

    public PosVendaService(PosVendaRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico,
                           LinksDeMidiaService links) {
        this.links = links;
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 96: ver o anexo da parceria
    // ---------------------------------------------------------------------

    /**
     * Rota 96. [ISOLAMENTO] Parceria de outra marca: 404. [PERMISSAO] A marca precisa do relatório DISPONIBILIZADO e de um anexo em vigor;
     * sem um dos dois, 404 com a mesma frase (a marca não descobre se há anexo guardado). A RBS vê sempre, com o aviso de que a marca
     * ainda não o vê. Os links de posts (R5.3.d) são os das veiculações comprovadas da parceria.
     */
    @Transactional(readOnly = true)
    public PosVendaDaParceriaDto ver(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        boolean disponibilizado = repositorio.temRelatorioDisponibilizado(parceriaId);
        Linha vigente = repositorio.vigente(parceriaId).orElse(null);
        if (!usuario.ehRbs() && (!disponibilizado || vigente == null)) {
            throw new NaoEncontradoException(NAO_DISPONIVEL);
        }
        Long eventoId = vigente != null ? vigente.eventoId() : repositorio.eventoDaParceria(parceriaId).orElse(null);
        List<LinkDePostDto> posts = links.daParceria(parceriaId).stream()
                .map(l -> new LinkDePostDto(l.origem(), l.url(), l.host(), l.data(), l.veiculo(), l.formato(), l.titulo())).toList();
        return new PosVendaDaParceriaDto(parceriaId, eventoId, disponibilizado, vigente == null ? null : paraAnexo(vigente, usuario.ehRbs()), posts);
    }

    // ---------------------------------------------------------------------
    // Rotas 97 a 99: publicar, listar e retirar (só a RBS)
    // ---------------------------------------------------------------------

    /**
     * Rota 97. Ordem: 403 (marca), 404 (parceria), 409 (encerrada), 422 (campos, todos numa resposta). A versão é a maior que a parceria já
     * tem mais 1, sob o bloqueio da parceria; a anterior fica como estava e deixa de ser a que a marca vê. [HISTORICO] CRIACAO, sem valor.
     */
    @Transactional
    public PosVendaDaEdicaoDto publicar(UsuarioAutenticado usuario, long parceriaId, PublicarRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        ParceriaTravada parceria = repositorio.travarParceria(parceriaId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        exigirNaoEncerrada(parceria.situacao());

        PublicarRequisicao c = corpo == null ? new PublicarRequisicao(null, null, null) : corpo;
        Map<String, String> campos = new LinkedHashMap<>();
        String texto = limpo(c.texto());
        if (texto != null && temControle(texto, true)) {
            campos.put("texto", "O texto tem caracteres inválidos.");
        } else if (texto != null && texto.length() > TEXTO_MAXIMO) {
            campos.put("texto", "O texto tem no máximo " + TEXTO_MAXIMO + " caracteres.");
        }
        List<LinkDto> links = validarLinks(c.links(), campos);
        List<AudienciaDto> audiencias = validarAudiencias(c.audiencias(), campos);
        if (campos.isEmpty() && texto == null && links.isEmpty() && audiencias.isEmpty()) {
            campos.put("texto", "Informe o texto, ao menos um link ou ao menos uma audiência.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        int versao = repositorio.proximaVersao(parceriaId);
        long id = repositorio.inserir(parceriaId, parceria.eventoId(), versao, texto, JSON.writeValueAsString(links),
                JSON.writeValueAsString(audiencias), usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, null, "evento_posvenda", id, null, null,
                "parceria " + parceriaId + ", versão " + versao, requisicao);
        return paraDaEdicao(repositorio.travar(id).orElseThrow());
    }

    /** Rota 98. Só a RBS. As versões de todas as parcerias da edição, retiradas inclusive, por patrocinador e da mais nova à mais antiga. */
    @Transactional(readOnly = true)
    public List<PosVendaDaEdicaoDto> listar(UsuarioAutenticado usuario, long eventoId) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        return repositorio.daEdicao(eventoId).stream().map(PosVendaService::paraDaEdicao).toList();
    }

    /**
     * Rota 99. Só a RBS. Retira a versão (não apaga): a linha fica, com quem e quando; repetir não muda nada. Ordem: 403, 404, 409
     * (parceria encerrada). [HISTORICO] EXCLUSAO na primeira vez.
     */
    @Transactional
    public void retirar(UsuarioAutenticado usuario, long id, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Linha linha = repositorio.travar(id).orElseThrow(() -> new NaoEncontradoException("Não encontramos este pós-venda."));
        if ("RETIRADO".equals(linha.situacao())) {
            return;
        }
        exigirNaoEncerrada(repositorio.travarParceria(linha.parceriaId()).map(ParceriaTravada::situacao).orElse("ATIVA"));
        repositorio.retirar(id, usuario.id());
        historico.registrar(AcaoHistorico.EXCLUSAO, usuario, null, "evento_posvenda", id, "PUBLICADO", "RETIRADO",
                "parceria " + linha.parceriaId() + ", versão " + linha.versao(), requisicao);
    }

    // ---------------------------------------------------------------------
    // Rotas 100 e 101: os números da edição (só a RBS)
    // ---------------------------------------------------------------------

    /** Rota 100. Só a RBS. Os números da edição, por indicador. */
    @Transactional(readOnly = true)
    public List<ResultadoDto> listarResultados(UsuarioAutenticado usuario, long eventoId) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        return repositorio.resultados(eventoId).stream().map(PosVendaService::paraResultado).toList();
    }

    /** O número gravado e se ele é novo (201) ou trocou um que já existia (200). */
    public record ResultadoGravado(ResultadoDto resultado, boolean criado) {
    }

    /**
     * Rota 101. Só a RBS. Upsert por indicador: o mesmo indicador na mesma edição troca o valor. Ordem: 403, 404 (edição), 422 (campos).
     * [RELATORIO] O relatório é uma fotografia: os números novos só entram numa versão gerada depois. [HISTORICO] CRIACAO ou EDICAO, com o
     * valor de antes e o de depois e sem parceria.
     */
    @Transactional
    public ResultadoGravado informarResultado(UsuarioAutenticado usuario, long eventoId, InformarResultadoRequisicao corpo,
                                              HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        InformarResultadoRequisicao c = corpo == null ? new InformarResultadoRequisicao(null, null, null, null, null) : corpo;
        Map<String, String> campos = new LinkedHashMap<>();
        String indicador = obrigatorio(c.indicador(), "indicador", "Informe o nome do número, como \"Público total\".", INDICADOR_MAXIMO, campos);
        String unidade = obrigatorio(c.unidade(), "unidade", "Informe a unidade, como \"pessoas\".", UNIDADE_DO_NUMERO_MAXIMA, campos);
        String fonte = obrigatorio(c.fonte(), "fonte", "Informe de onde vem o número.", FONTE_MAXIMA, campos);
        BigDecimal valor = c.valor();
        if (valor == null) {
            campos.put("valor", "Informe o valor.");
        } else if (valor.signum() <= 0) {
            campos.put("valor", "O valor tem de ser maior que zero.");
        } else if (valor.compareTo(LIMITE_DO_NUMERO) >= 0) {
            campos.put("valor", FRASE_DO_MAXIMO_VALOR);
        } else if (valor.stripTrailingZeros().scale() > 2) {
            campos.put("valor", "O valor tem no máximo 2 casas decimais.");
        }
        String qualificador = c.qualificador() == null || c.qualificador().isBlank() ? "EXATO" : c.qualificador().strip();
        if (!QUALIFICADORES.contains(qualificador)) {
            campos.put("qualificador", "Use EXATO ou MAIS_DE.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        Resultado anterior = repositorio.resultado(eventoId, indicador).orElse(null);
        long id = repositorio.gravarResultado(eventoId, indicador, valor, qualificador, unidade, fonte);
        historico.registrar(anterior == null ? AcaoHistorico.CRIACAO : AcaoHistorico.EDICAO, usuario, null, "evento_resultado", id,
                anterior == null ? null : descrever(anterior.valor(), anterior.qualificador(), anterior.unidade()),
                descrever(valor, qualificador, unidade), "edição " + eventoId + ", " + indicador, requisicao);
        return new ResultadoGravado(new ResultadoDto(id, indicador, valor, qualificador, unidade, fonte), anterior == null);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private void exigirEdicao(long eventoId) {
        if (!repositorio.eventoExiste(eventoId)) {
            throw new NaoEncontradoException("Não encontramos esta edição.");
        }
    }

    private static void exigirNaoEncerrada(String situacao) {
        if ("ENCERRADA".equals(situacao)) {
            throw new ConflitoException(PARCERIA_ENCERRADA);
        }
    }

    private static List<LinkDto> validarLinks(List<LinkDto> recebidos, Map<String, String> campos) {
        List<LinkDto> links = new ArrayList<>();
        if (recebidos == null) {
            return links;
        }
        if (recebidos.size() > MAXIMO_DE_LINKS) {
            campos.put("links", "Use no máximo " + MAXIMO_DE_LINKS + " links.");
            return links;
        }
        for (int i = 0; i < recebidos.size(); i++) {
            LinkDto l = recebidos.get(i);
            String campo = "links[" + i + "]";
            if (l == null) {
                campos.put(campo, "Informe o tipo, o título e o endereço do link.");
                continue;
            }
            String tipo = l.tipo() == null ? null : l.tipo().strip();
            if (tipo == null || !TIPOS_DE_LINK.contains(tipo)) {
                campos.put(campo + ".tipo", "Use AFTERMOVIE, CLIPPING ou OUTRO.");
            }
            String titulo = obrigatorio(l.titulo(), campo + ".titulo", "Informe o título do link.", TITULO_MAXIMO, campos);
            String url = l.url() == null ? null : l.url().strip();
            if (url == null || url.isEmpty()) {
                campos.put(campo + ".url", "Informe o endereço do link.");
            } else if (url.length() > URL_MAXIMA || !HostDoLink.valido(url)) {
                campos.put(campo + ".url", "O endereço precisa começar com http:// ou https://, sem espaços e sem usuário antes do domínio, "
                        + "com até " + URL_MAXIMA + " caracteres.");
            }
            if (tipo != null && titulo != null && url != null && !campos.containsKey(campo + ".tipo") && !campos.containsKey(campo + ".titulo")
                    && !campos.containsKey(campo + ".url")) {
                links.add(new LinkDto(tipo, titulo, url));
            }
        }
        return links;
    }

    private static List<AudienciaDto> validarAudiencias(List<AudienciaDto> recebidas, Map<String, String> campos) {
        List<AudienciaDto> audiencias = new ArrayList<>();
        if (recebidas == null) {
            return audiencias;
        }
        if (recebidas.size() > MAXIMO_DE_AUDIENCIAS) {
            campos.put("audiencias", "Use no máximo " + MAXIMO_DE_AUDIENCIAS + " audiências.");
            return audiencias;
        }
        for (int i = 0; i < recebidas.size(); i++) {
            AudienciaDto a = recebidas.get(i);
            String campo = "audiencias[" + i + "]";
            if (a == null) {
                campos.put(campo, "Informe o veículo, a métrica, o número e a unidade.");
                continue;
            }
            int antes = campos.size();
            String veiculo = obrigatorio(a.veiculo(), campo + ".veiculo", "Informe o veículo.", VEICULO_MAXIMO, campos);
            String metrica = obrigatorio(a.metrica(), campo + ".metrica", "Informe a métrica, como \"ouvintes\".", METRICA_MAXIMA, campos);
            String unidade = obrigatorio(a.unidade(), campo + ".unidade", "Informe a unidade, como \"pessoas\".", UNIDADE_MAXIMA, campos);
            BigDecimal numero = a.numero();
            if (numero == null) {
                campos.put(campo + ".numero", "Informe o número.");
            } else if (numero.signum() <= 0) {
                campos.put(campo + ".numero", "O número tem de ser maior que zero.");
            } else if (numero.compareTo(LIMITE_DO_NUMERO) >= 0) {
                campos.put(campo + ".numero", FRASE_DO_MAXIMO_NUMERO);
            } else if (numero.stripTrailingZeros().scale() > 2) {
                campos.put(campo + ".numero", "O número tem no máximo 2 casas decimais.");
            }
            if (campos.size() == antes) {
                audiencias.add(new AudienciaDto(veiculo, metrica, numero.stripTrailingZeros().scale() < 0 ? numero.setScale(0) : numero.stripTrailingZeros(), unidade));
            }
        }
        return audiencias;
    }

    /** O texto sem espaços nas pontas, dentro do limite e sem caractere de controle; o motivo vai a {@code campos}. Nulo = recusado. */
    private static String obrigatorio(String texto, String campo, String quandoFalta, int maximo, Map<String, String> campos) {
        String limpo = limpo(texto);
        if (limpo == null) {
            campos.put(campo, quandoFalta);
            return null;
        }
        if (temControle(limpo, false)) {
            campos.put(campo, "Há caracteres inválidos neste campo.");
            return null;
        }
        if (limpo.length() > maximo) {
            campos.put(campo, "Use no máximo " + maximo + " caracteres.");
            return null;
        }
        return limpo;
    }

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** NUL (o PostgreSQL não o guarda) e os demais caracteres de controle; a quebra de linha e o tab só valem onde {@code multilinha}. */
    static boolean temControle(String texto, boolean multilinha) {
        return texto.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multilinha && (c == '\n' || c == '\r' || c == '\t')));
    }

    private static String descrever(BigDecimal valor, String qualificador, String unidade) {
        return ("MAIS_DE".equals(qualificador) ? "mais de " : "") + valor.stripTrailingZeros().toPlainString() + " " + unidade;
    }

    private static ResultadoDto paraResultado(Resultado r) {
        return new ResultadoDto(r.id(), r.indicador(), r.valor().stripTrailingZeros().scale() < 0 ? r.valor().setScale(0) : r.valor().stripTrailingZeros(),
                r.qualificador(), r.unidade(), r.fonte());
    }

    private static AnexoDto paraAnexo(Linha l, boolean comAutor) {
        return new AnexoDto(l.id(), l.versao(), l.texto(), JSON.readValue(l.links(), LISTA_DE_LINKS), JSON.readValue(l.audiencias(), LISTA_DE_AUDIENCIAS),
                Datas.emBrasilia(l.publicadoEm()), comAutor ? new UsuarioResumoDto(l.publicadoPor(), l.publicadoPorNome(), l.publicadoPorPerfil()) : null);
    }

    private static PosVendaDaEdicaoDto paraDaEdicao(Linha l) {
        return new PosVendaDaEdicaoDto(l.id(), l.parceriaId(), l.patrocinador(), l.versao(), l.situacao(), l.texto(),
                JSON.readValue(l.links(), LISTA_DE_LINKS), JSON.readValue(l.audiencias(), LISTA_DE_AUDIENCIAS), Datas.emBrasilia(l.publicadoEm()),
                new UsuarioResumoDto(l.publicadoPor(), l.publicadoPorNome(), l.publicadoPorPerfil()), Datas.emBrasilia(l.retiradoEm()),
                l.retiradoPor() == null ? null : new UsuarioResumoDto(l.retiradoPor(), l.retiradoPorNome(), l.retiradoPorPerfil()));
    }
}
