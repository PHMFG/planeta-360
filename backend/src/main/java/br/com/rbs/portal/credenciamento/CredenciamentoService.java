package br.com.rbs.portal.credenciamento;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.HostDoLink;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredencialDto;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredenciaisDto;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredenciaisRequisicao;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.ItemRequisicao;
import br.com.rbs.portal.credenciamento.CredenciamentoRepositorio.Base;
import br.com.rbs.portal.credenciamento.CredenciamentoRepositorio.DaEdicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As credenciais de cada parceria e o Open Staff da edição (R5.5, rotas 77 a 79). [CREDENCIAMENTO] A marca e a RBS leem (a marca com a
 * aba Credenciamento aberta); só a RBS grava. [PERMISSAO] 403 da marca antes do corpo (PermissaoAntesDoCorpo); parceria de outra marca,
 * 404; parceria ENCERRADA recusa a grade (409). [VALIDACAO] Quantidade negativa ou acima de 32.767 (o SMALLINT do banco), texto com NUL
 * ou controle e endereço que não seja https dão 422, nunca 500. O endereço do Open Staff é um link externo: só https, sem
 * {@code usuario@} antes do host (que esconderia o destino) e sem espaço, controle ou bidi; o servidor nunca redireciona.
 */
@Service
public class CredenciamentoService {

    static final String ABA = "CREDENCIAMENTO";
    static final String PARCERIA_ENCERRADA = "Esta parceria está encerrada e não aceita alterações.";
    static final int MAXIMO_DE_ITENS = 24;
    static final int QUANTIDADE_MAXIMA = Short.MAX_VALUE;
    static final int URL_MAXIMA = 500;
    private static final Map<String, String> SETORES = Map.of("ARENA", "Arena", "CAMAROTE", "Camarote", "BAIXO_CAMAROTE", "Baixo Camarote",
            "PREMIUM", "Premium", "SALA_IMPRENSA", "Sala de imprensa", "SALA_CONTEUDO", "Sala de conteúdo");
    private static final Map<String, String> BENEFICIARIOS = Map.of("MARCA", "marca", "AGENCIA", "agência",
            "EQUIPE_CONTEUDO", "equipe de conteúdo", "GERAL", "geral");
    private static final Pattern DATA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final CredenciamentoRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public CredenciamentoService(CredenciamentoRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    /** Rota 77. 404 (parceria de outra marca), 403 (aba Credenciamento fechada ou oculta para a marca). */
    @Transactional(readOnly = true)
    public CredenciaisDto ver(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
        return montar(repositorio.base(parceriaId).orElseThrow(CredenciamentoService::parceriaNaoEncontrada));
    }

    /**
     * Rota 78. Só a RBS. Ordem: 403 (marca), 404, 409 (encerrada), 422 (1 a 24 itens, setor, beneficiário, quantidade de 0 a 32.767 e par
     * repetido, todos numa resposta). Tudo ou nada. Quantidade 0 remove a linha. [HISTORICO] EDICAO na entidade {@code credencial_cota},
     * uma por linha que mudou, com a quantidade antes e depois.
     */
    @Transactional
    public CredenciaisDto gravar(UsuarioAutenticado usuario, long parceriaId, CredenciaisRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        Base base = repositorio.travarParceria(parceriaId).orElseThrow(CredenciamentoService::parceriaNaoEncontrada);
        if ("ENCERRADA".equals(base.situacao())) {
            throw new ConflitoException(PARCERIA_ENCERRADA);
        }
        List<Item> itens = validar(corpo);
        for (Item item : itens) {
            CredencialDto atual = repositorio.credencial(parceriaId, item.setor(), item.beneficiario()).orElse(null);
            int antes = atual == null ? 0 : atual.quantidadePorDia();
            if (antes == item.quantidade()) {
                continue;
            }
            long id;
            if (item.quantidade() == 0) {
                id = atual.id();
                repositorio.remover(id);
            } else if (atual == null) {
                id = repositorio.inserir(parceriaId, item.setor(), item.beneficiario(), item.quantidade());
            } else {
                id = atual.id();
                repositorio.alterar(id, item.quantidade());
            }
            historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, "credencial_cota", id, antes == 0 ? null : String.valueOf(antes),
                    item.quantidade() == 0 ? null : String.valueOf(item.quantidade()),
                    SETORES.get(item.setor()) + " · " + BENEFICIARIOS.get(item.beneficiario()), requisicao);
        }
        return montar(repositorio.base(parceriaId).orElseThrow(CredenciamentoService::parceriaNaoEncontrada));
    }

    /**
     * Rota 79. Só a RBS. O corpo é parcial: {@code openStaffUrl}, {@code inicio} e {@code fim} que vierem trocam o valor ("" ou null
     * limpa); os que não vierem ficam como estão. Ordem: 403, 404 (edição), 422. [HISTORICO] EDICAO na entidade {@code evento}, sem
     * parceria, com o endereço antes e depois (não é dado pessoal). Devolve a edição, como a rota 6.
     */
    @Transactional
    public EventoCompletoDto informarEdicao(UsuarioAutenticado usuario, long eventoId, JsonNode corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        DaEdicao atual = repositorio.travarEdicao(eventoId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta edição do festival."));
        if (corpo == null || !corpo.isObject()) {
            throw new DadosInvalidosException(Map.of("corpo", "Envie um objeto com openStaffUrl, inicio e fim."));
        }
        Map<String, String> campos = new LinkedHashMap<>();
        String url = atual.url();
        LocalDate inicio = atual.inicio();
        LocalDate fim = atual.fim();
        if (corpo.has("openStaffUrl")) {
            url = url(corpo.get("openStaffUrl"), campos);
        }
        if (corpo.has("inicio")) {
            inicio = data(corpo.get("inicio"), "inicio", campos);
        }
        if (corpo.has("fim")) {
            fim = data(corpo.get("fim"), "fim", campos);
        }
        if (campos.isEmpty() && inicio != null && fim != null && fim.isBefore(inicio)) {
            campos.put("fim", "O fim do período não pode ser antes do início.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        boolean urlMudou = !Objects.equals(url, atual.url());
        boolean periodoMudou = !Objects.equals(inicio, atual.inicio()) || !Objects.equals(fim, atual.fim());
        if (urlMudou || periodoMudou) {
            repositorio.gravarCredenciamento(eventoId, url, inicio, fim);
            List<String> o = new ArrayList<>();
            if (urlMudou) {
                o.add("endereço do credenciamento");
            }
            if (periodoMudou) {
                o.add("período do credenciamento");
            }
            historico.registrar(AcaoHistorico.EDICAO, usuario, null, "evento", eventoId, urlMudou ? atual.url() : null,
                    urlMudou ? url : null, String.join(" e ", o), requisicao);
        }
        return repositorio.evento(eventoId);
    }

    // ---------------------------------------------------------------------
    // Validação
    // ---------------------------------------------------------------------

    private record Item(String setor, String beneficiario, int quantidade) {
    }

    private static List<Item> validar(CredenciaisRequisicao corpo) {
        Map<String, String> campos = new LinkedHashMap<>();
        List<ItemRequisicao> recebidos = corpo == null ? null : corpo.credenciais();
        if (recebidos == null || recebidos.isEmpty()) {
            throw new DadosInvalidosException(Map.of("credenciais", "Envie de 1 a " + MAXIMO_DE_ITENS + " credenciais."));
        }
        if (recebidos.size() > MAXIMO_DE_ITENS) {
            throw new DadosInvalidosException(Map.of("credenciais", "Envie no máximo " + MAXIMO_DE_ITENS + " credenciais de uma vez."));
        }
        List<Item> itens = new ArrayList<>();
        Set<String> pares = new HashSet<>();
        for (int i = 0; i < recebidos.size(); i++) {
            ItemRequisicao r = recebidos.get(i);
            String chave = "credenciais[" + i + "].";
            if (r == null) {
                campos.put(chave + "setor", "Informe o setor.");
                continue;
            }
            String setor = r.setor() == null ? null : r.setor().strip();
            String beneficiario = r.beneficiario() == null ? null : r.beneficiario().strip();
            boolean setorOk = setor != null && SETORES.containsKey(setor);
            boolean beneficiarioOk = beneficiario != null && BENEFICIARIOS.containsKey(beneficiario);
            if (!setorOk) {
                campos.put(chave + "setor", "Setor inválido. Use ARENA, CAMAROTE, BAIXO_CAMAROTE, PREMIUM, SALA_IMPRENSA ou SALA_CONTEUDO.");
            }
            if (!beneficiarioOk) {
                campos.put(chave + "beneficiario", "Beneficiário inválido. Use MARCA, AGENCIA, EQUIPE_CONTEUDO ou GERAL.");
            }
            Integer quantidade = quantidade(r.quantidadePorDia(), chave + "quantidadePorDia", campos);
            if (setorOk && beneficiarioOk) {
                if (!pares.add(setor + "|" + beneficiario)) {
                    campos.put(chave + "setor", "Este setor e este beneficiário aparecem mais de uma vez.");
                } else if (quantidade != null) {
                    itens.add(new Item(setor, beneficiario, quantidade));
                }
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        return itens;
    }

    private static Integer quantidade(BigDecimal valor, String campo, Map<String, String> campos) {
        if (valor == null) {
            campos.put(campo, "Informe a quantidade por dia.");
            return null;
        }
        BigDecimal inteiro = valor.stripTrailingZeros();
        if (inteiro.scale() > 0) {
            campos.put(campo, "A quantidade por dia é um número inteiro.");
            return null;
        }
        if (valor.signum() < 0 || valor.compareTo(BigDecimal.valueOf(QUANTIDADE_MAXIMA)) > 0) {
            campos.put(campo, "A quantidade por dia vai de 0 a " + QUANTIDADE_MAXIMA + ". Use 0 para retirar a linha.");
            return null;
        }
        return valor.intValueExact();
    }

    /** Endereço do Open Staff: nulo ou vazio limpa; senão só https, até 500 caracteres, sem {@code usuario@}, espaço, controle ou bidi. */
    private static String url(JsonNode no, Map<String, String> campos) {
        if (no.isNull() || (no.isString() && no.asString().isBlank())) {
            return null;
        }
        if (!no.isString()) {
            campos.put("openStaffUrl", "O endereço do Open Staff é um texto.");
            return null;
        }
        String url = no.asString().strip();
        String frase = null;
        if (url.length() > URL_MAXIMA) {
            frase = "O endereço tem no máximo " + URL_MAXIMA + " caracteres.";
        } else if (!url.startsWith("https://") || !HostDoLink.valido(url) || !temHostValido(url)) {
            frase = "Informe um endereço que comece com https://, sem espaços e sem usuário@ antes do site.";
        }
        if (frase != null) {
            campos.put("openStaffUrl", frase);
            return null;
        }
        return url;
    }

    /** O Java também precisa entender o endereço (host presente): um valor que o navegador leria de outro jeito é recusado. */
    private static boolean temHostValido(String url) {
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException invalida) {
            return false;
        }
    }

    private static LocalDate data(JsonNode no, String campo, Map<String, String> campos) {
        if (no.isNull() || (no.isString() && no.asString().isBlank())) {
            return null;
        }
        String texto = no.isString() ? no.asString().strip() : "";
        try {
            if (!DATA.matcher(texto).matches()) {
                throw new DateTimeException("formato");
            }
            LocalDate data = LocalDate.parse(texto);
            if (data.getYear() < 2000 || data.getYear() > 2100) {
                throw new DateTimeException("ano");
            }
            return data;
        } catch (DateTimeException invalida) {
            campos.put(campo, "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2027-01-10.");
            return null;
        }
    }

    private CredenciaisDto montar(Base base) {
        List<CredencialDto> credenciais = repositorio.credenciais(base.parceriaId());
        int total = credenciais.stream().mapToInt(CredencialDto::quantidadePorDia).sum();
        return new CredenciaisDto(base.parceriaId(), base.eventoId(), total, credenciais, base.openStaffUrl(), base.inicio(), base.fim());
    }

    private static NaoEncontradoException parceriaNaoEncontrada() {
        return new NaoEncontradoException("Não encontramos esta parceria.");
    }
}
