package br.com.rbs.portal.prazos;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.prazos.PrazoDtos.CriarPrazoRequisicao;
import br.com.rbs.portal.prazos.PrazoDtos.DocumentoDoPrazoDto;
import br.com.rbs.portal.prazos.PrazoDtos.ParceriaDoPrazoDto;
import br.com.rbs.portal.prazos.PrazoDtos.PrazoDto;
import br.com.rbs.portal.prazos.PrazoRepositorio.DocumentoDeOrigem;
import br.com.rbs.portal.prazos.PrazoRepositorio.Linha;
import br.com.rbs.portal.prazos.PrazoRepositorio.ParceriaDoPrazo;
import br.com.rbs.portal.prazos.PrazoRepositorio.Travado;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os prazos da edição, da cota e da parceria (rotas 62 a 64, M5 do plano do Atlas completo). [ATLAS] Só a RBS lista, cadastra e
 * desativa; o que o Atlas lê é a ferramenta {@code consultar_prazos}. [PERMISSAO] Quem não é da RBS recebe 403 antes do corpo
 * (PermissaoAntesDoCorpo) e aqui de novo. [HISTORICO] O cadastro grava CRIACAO e a desativação grava ALTERACAO_STATUS, na entidade
 * {@code prazo} (a marca não vê essas linhas na rota 20). Um prazo nunca se apaga nem se edita: desativa-se (a V13 garante).
 */
@Service
public class PrazoService {

    static final Set<String> ESCOPOS = Set.of("GLOBAL", "COTA", "PARCERIA");
    static final Set<String> COTAS = Set.of("MASTER", "SUBCOTA_G", "SUBCOTA");
    static final int LIMITE_TITULO = 200;
    static final int LIMITE_DESCRICAO = 1000;
    static final int ANO_MINIMO = 2000;
    static final int ANO_MAXIMO = 2100;
    private static final Pattern DATA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern HORA = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter HORA_NA_TELA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PrazoRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public PrazoService(PrazoRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 62: listar
    // ---------------------------------------------------------------------

    /**
     * Rota 62. [PERMISSAO] Só a RBS. Os prazos da edição por data, hora e título (o dia inteiro, sem hora, depois dos que têm hora), só
     * os ativos, a menos que {@code incluirInativos}. Edição que não existe dá 404.
     */
    @Transactional(readOnly = true)
    public List<PrazoDto> listar(UsuarioAutenticado usuario, long eventoId, boolean incluirInativos) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        return repositorio.daEdicao(eventoId, incluirInativos).stream().map(PrazoService::paraDto).toList();
    }

    // ---------------------------------------------------------------------
    // Rota 63: cadastrar
    // ---------------------------------------------------------------------

    /**
     * Rota 63. Ordem das respostas: 403 (não é da RBS), 404 (edição que não existe), 422 (todos os campos errados numa resposta só).
     * Os erros de campo são os do plano: escopo, cota (obrigatória só em COTA), parceria (obrigatória só em PARCERIA e da edição),
     * título, data, hora, descrição, documento (existe, é da edição e <b>não tem alcance menor que o do prazo</b>) e página (só com
     * documento, de 1 até as páginas dele).
     */
    @Transactional
    public PrazoDto criar(UsuarioAutenticado usuario, long eventoId, CriarPrazoRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        Entrada e = validar(eventoId, corpo);
        long id = repositorio.inserir(eventoId, e.escopo(), e.cotaTipoId(), e.parceriaId(), e.titulo(), e.data(), e.hora(), e.descricao(),
                e.documentoId(), e.pagina(), usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, e.parceriaId(), "prazo", id, null, e.titulo() + " (" + DIA.format(e.data()) + ")",
                e.escopo() + (e.cotaCodigo() == null ? "" : " · " + e.cotaCodigo()) + (e.documentoId() == null ? "" : " · documento " + e.documentoId()),
                requisicao);
        return paraDto(repositorio.buscar(id).orElseThrow());
    }

    /** Os campos do corpo, já conferidos e convertidos. */
    private record Entrada(String escopo, String cotaCodigo, Integer cotaTipoId, Long parceriaId, String titulo, LocalDate data,
                           LocalTime hora, String descricao, Long documentoId, Integer pagina) {
    }

    /** [VALIDACAO] Junta os erros de campo numa resposta só (422). */
    private Entrada validar(long eventoId, CriarPrazoRequisicao c) {
        Map<String, String> campos = new LinkedHashMap<>();
        String escopo = limpo(c.escopo());
        boolean escopoValido = escopo != null && ESCOPOS.contains(escopo);
        if (!escopoValido) {
            campos.put("escopo", "Escopo inválido. Use GLOBAL, COTA ou PARCERIA.");
        }

        String cotaCodigo = limpo(c.cota());
        Integer cotaTipoId = null;
        if (escopoValido && "COTA".equals(escopo)) {
            if (cotaCodigo == null || !COTAS.contains(cotaCodigo)) {
                campos.put("cota", "Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
            } else {
                cotaTipoId = repositorio.cotaPorCodigo(cotaCodigo).orElse(null);
            }
        } else if (cotaCodigo != null && escopoValido) {
            campos.put("cota", "Só o prazo de escopo COTA leva cota.");
        }

        Long parceriaId = null;
        ParceriaDoPrazo parceria = null;
        if (escopoValido && "PARCERIA".equals(escopo)) {
            if (c.parceriaId() == null) {
                campos.put("parceriaId", "Informe a parceria (parceriaId) do prazo.");
            } else {
                parceria = repositorio.parceria(c.parceriaId()).orElse(null);
                if (parceria == null) {
                    campos.put("parceriaId", "Esta parceria não existe.");
                } else if (parceria.eventoId() != eventoId) {
                    campos.put("parceriaId", "Esta parceria não é da edição do prazo.");
                    parceria = null;
                } else {
                    parceriaId = parceria.id();
                }
            }
        } else if (c.parceriaId() != null && escopoValido) {
            campos.put("parceriaId", "Só o prazo de escopo PARCERIA leva parceria.");
        }

        String titulo = limpo(c.titulo());
        if (titulo == null) {
            campos.put("titulo", "Informe o título do prazo.");
        } else if (titulo.length() > LIMITE_TITULO) {
            campos.put("titulo", "O título tem no máximo " + LIMITE_TITULO + " caracteres.");
        } else if (temNulo(titulo)) {
            campos.put("titulo", "O título tem caracteres inválidos.");
        }

        LocalDate data = data(c.data(), campos);

        LocalTime hora = null;
        String horaTexto = limpo(c.hora());
        if (horaTexto != null) {
            if (HORA.matcher(horaTexto).matches()) {
                hora = LocalTime.parse(horaTexto);
            } else {
                campos.put("hora", "A hora tem o formato HH:mm, como 18:00.");
            }
        }

        String descricao = limpo(c.descricao());
        if (descricao != null && descricao.length() > LIMITE_DESCRICAO) {
            campos.put("descricao", "A descrição tem no máximo " + LIMITE_DESCRICAO + " caracteres.");
        } else if (temNulo(descricao)) {
            campos.put("descricao", "A descrição tem caracteres inválidos.");
        }

        DocumentoDeOrigem documento = null;
        if (c.documentoId() != null) {
            documento = repositorio.documento(c.documentoId()).orElse(null);
            if (documento == null) {
                campos.put("documentoId", "Este documento não existe.");
            } else if (documento.eventoId() != eventoId) {
                campos.put("documentoId", "Este documento não é da edição do prazo.");
                documento = null;
            } else {
                String fora = alcanceMaiorQueODoDocumento(documento, escopoValido ? escopo : null, cotaTipoId, parceria);
                if (fora != null) {
                    campos.put("documentoId", fora);
                }
            }
        }

        Integer pagina = c.pagina();
        if (pagina != null) {
            if (c.documentoId() == null) {
                campos.put("pagina", "A página só vale com um documento (documentoId).");
            } else if (pagina < 1) {
                campos.put("pagina", "A página começa em 1.");
            } else if (documento != null && documento.paginas() != null && pagina > documento.paginas()) {
                campos.put("pagina", documento.paginas() == 1 ? "O documento tem 1 página." : "O documento tem " + documento.paginas() + " páginas.");
            }
        }

        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        return new Entrada(escopo, "COTA".equals(escopo) ? cotaCodigo : null, cotaTipoId, parceriaId, titulo, data, hora, descricao,
                c.documentoId(), pagina);
    }

    /**
     * [ISOLAMENTO] A frase de recusa se o prazo vale para mais gente que o documento de onde saiu, ou nulo se não vale. Documento
     * GLOBAL: qualquer alcance. Documento COTA: só prazo daquela cota ou de uma parceria dela. Documento PARCERIA: só prazo daquela
     * parceria. Sem isso, o título de um documento restrito chegaria a uma marca que não pode vê-lo. O alcance só é conferido quando o
     * do prazo é conhecido (escopo, cota e parceria válidos): senão o campo já está errado, e o 422 diz isso.
     */
    private static String alcanceMaiorQueODoDocumento(DocumentoDeOrigem documento, String escopo, Integer cotaTipoId, ParceriaDoPrazo parceria) {
        boolean conhecido = escopo != null && (!"COTA".equals(escopo) || cotaTipoId != null) && (!"PARCERIA".equals(escopo) || parceria != null);
        if (!conhecido) {
            return null;
        }
        if ("COTA".equals(documento.escopo())) {
            boolean dentro = ("COTA".equals(escopo) && cotaTipoId.equals(documento.cotaTipoId()))
                    || ("PARCERIA".equals(escopo) && parceria.cotaTipoId().equals(documento.cotaTipoId()));
            return dentro ? null : FRASE_DO_ALCANCE + " Este documento é da cota " + documento.cotaNome()
                    + ": o prazo só pode ser dessa cota ou de uma parceria dela.";
        }
        if ("PARCERIA".equals(documento.escopo())) {
            boolean dentro = "PARCERIA".equals(escopo) && parceria.id().equals(documento.parceriaId());
            return dentro ? null : FRASE_DO_ALCANCE + " Este documento é da parceria " + documento.patrocinador()
                    + ": o prazo só pode ser dessa parceria.";
        }
        return null;
    }

    static final String FRASE_DO_ALCANCE = "Um prazo não vale para mais gente que o documento de onde saiu.";

    /** A data do prazo: AAAA-MM-DD, que exista, de 2000 a 2100 (o que a tabela aceita). Registra o erro em {@code campos}. */
    private static LocalDate data(String texto, Map<String, String> campos) {
        String limpo = limpo(texto);
        if (limpo == null) {
            campos.put("data", "Informe a data do prazo (AAAA-MM-DD).");
            return null;
        }
        if (!DATA.matcher(limpo).matches()) {
            campos.put("data", "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-12-04.");
            return null;
        }
        LocalDate data;
        try {
            data = LocalDate.parse(limpo);
        } catch (DateTimeException invalida) {
            campos.put("data", "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-12-04.");
            return null;
        }
        if (data.getYear() < ANO_MINIMO || data.getYear() > ANO_MAXIMO) {
            campos.put("data", "O ano da data tem de estar entre " + ANO_MINIMO + " e " + ANO_MAXIMO + ".");
            return null;
        }
        return data;
    }

    // ---------------------------------------------------------------------
    // Rota 64: desativar
    // ---------------------------------------------------------------------

    /**
     * Rota 64. Desativa o prazo (não apaga): ele some da lista padrão e do Atlas, e a linha fica. Desativar de novo não é erro e não
     * grava outra linha no histórico. Prazo que não existe dá 404. Grava ALTERACAO_STATUS.
     */
    @Transactional
    public void desativar(UsuarioAutenticado usuario, long id, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Travado prazo = repositorio.travar(id).orElseThrow(() -> new NaoEncontradoException("Não encontramos este prazo."));
        if (!prazo.ativo()) {
            return;
        }
        repositorio.desativar(id);
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, prazo.parceriaId(), "prazo", id, "ATIVO", "DESATIVADO",
                prazo.titulo() + " (" + DIA.format(prazo.data()) + ")", requisicao);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private void exigirEdicao(long eventoId) {
        if (!repositorio.eventoExiste(eventoId)) {
            throw new NaoEncontradoException("Não encontramos esta edição do festival.");
        }
    }

    static PrazoDto paraDto(Linha l) {
        CotaDto cota = l.cotaCodigo() == null ? null : new CotaDto(l.cotaCodigo(), l.cotaNome());
        ParceriaDoPrazoDto parceria = l.parceriaId() == null ? null : new ParceriaDoPrazoDto(l.parceriaId(), l.patrocinador());
        DocumentoDoPrazoDto documento = l.documentoId() == null ? null
                : new DocumentoDoPrazoDto(l.documentoId(), l.documentoTitulo(), l.documentoVersao(), l.documentoStatus());
        return new PrazoDto(l.id(), l.eventoId(), l.escopo(), cota, parceria, l.titulo(), l.data(),
                l.hora() == null ? null : HORA_NA_TELA.format(l.hora()), l.descricao(), documento, l.pagina(), l.ativo(),
                new UsuarioResumoDto(l.criadoPor(), l.criadoPorNome(), l.criadoPorPerfil()), Datas.emBrasilia(l.criadoEm()));
    }

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** O PostgreSQL não guarda o caractere nulo (U+0000): um texto com ele falharia na gravação com 500, e aqui vira 422. */
    private static boolean temNulo(String texto) {
        return texto != null && texto.indexOf('\u0000') >= 0;
    }
}
