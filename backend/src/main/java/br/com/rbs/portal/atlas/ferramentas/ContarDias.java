package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.Feriados.Feriado;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code contar_dias(inicio, fim | dias, uteis?, sentido?)}: a conta de datas do Atlas, por {@code java.time} (plano do Atlas
 * completo, M5). [ATLAS] [VALIDACAO] O modelo não conta dias de cabeça: pede aqui. A regra é uma só: <b>o dia de {@code inicio} não
 * conta e o {@code fim} conta</b>. 18 dias úteis depois de 03/11/2026 dá 30/11/2026 (o 20/11 é feriado); 20 dá 02/12/2026. Com
 * {@code uteis}, sábado, domingo e feriado ({@link Feriados}) ficam de fora; um {@code fim} em dia que não é útil não é contado.
 * <ul>
 *   <li>{@code dias} (1 a 400) devolve a data, antes ou depois de {@code inicio}; {@code fim} devolve a quantidade de dias.
 *       Exatamente um dos dois. {@code sentido} só vale com {@code dias}.</li>
 *   <li>O resultado volta como {@code [C#]} (o mesmo contador do {@code calcular}), com a regra em uma frase e os feriados que
 *       caíram em dia de semana dentro do intervalo e foram pulados. O servidor registra a fonte com tudo o que a resposta pode
 *       citar (as datas de entrada, a quantidade, o resultado e os feriados) e o resultado como número livre do turno.</li>
 *   <li>Nenhum argumento é parceria. Argumento desconhecido, data inválida ({@code AAAA-MM-DD}), {@code fim} antes de
 *       {@code inicio} e ano fora de 2000 a 2100 voltam ao modelo como erro.</li>
 * </ul>
 */
@Component
public class ContarDias implements Ferramenta {

    public static final String NOME = "contar_dias";
    static final int ANO_MINIMO = 2000;
    static final int ANO_MAXIMO = 2100;
    static final int DIAS_MAXIMO = 400;
    static final String TITULO_DA_FONTE = "Contagem de dias";
    private static final Set<String> ARGUMENTOS = Set.of("inicio", "fim", "dias", "uteis", "sentido");
    private static final Pattern DATA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Conta dias pelo calendário, sem erro de cabeça: a data que dá tantos dias antes ou depois de uma data, ou quantos dias há "
                    + "entre duas datas. O dia de início não conta e o dia final conta. Com uteis verdadeiro, sábados, domingos e "
                    + "feriados nacionais e do RS ficam de fora. Informe inicio e exatamente um de fim ou dias. Devolve o resultado, a "
                    + "regra em uma frase, os feriados que ficaram de fora e o marcador [C#] para citar a conta. Use sempre que a "
                    + "resposta depender de contar dias ou achar uma data; nunca conte você mesmo.",
            """
            {"type":"object","properties":{
              "inicio":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$","description":"Data de partida, AAAA-MM-DD. O dia dela não conta."},
              "fim":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$","description":"Data final, AAAA-MM-DD, para saber quantos dias há até ela. O dia dela conta."},
              "dias":{"type":"integer","minimum":1,"maximum":400,"description":"Quantos dias contar a partir de inicio, para achar a data."},
              "uteis":{"type":"boolean","description":"Verdadeiro para contar só dias úteis. Padrão falso."},
              "sentido":{"type":"string","enum":["depois","antes"],"description":"Só com dias: depois (padrão) ou antes de inicio."}},
             "required":["inicio"],"additionalProperties":false}""");

    private final Feriados feriados;

    public ContarDias(Feriados feriados) {
        this.feriados = feriados;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public DefinicaoDeFerramenta definicao() {
        return DEFINICAO;
    }

    @Override
    public int ordem() {
        return 30;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return !estado.cartaoAberto();
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS, erros);
        LocalDate inicio = data(argumentos, "inicio", true, erros);
        LocalDate fim = data(argumentos, "fim", false, erros);
        Integer dias = dias(argumentos, erros);
        boolean uteis = booleano(argumentos, "uteis", erros);
        String sentido = sentido(argumentos, erros);
        if (!erros.containsKey("fim") && !erros.containsKey("dias") && (fim == null) == (dias == null)) {
            String frase = fim == null ? "Informe fim ou dias." : "Informe só um: fim ou dias, não os dois.";
            erros.put("fim", frase);
            erros.put("dias", frase);
        }
        if (sentido != null && fim != null) {
            erros.put("sentido", "O sentido só vale com dias.");
        }
        if (inicio != null && fim != null && fim.isBefore(inicio)) {
            erros.put("fim", "O fim não pode ser antes do início.");
        }
        exigirSemErros(erros);

        boolean depois = !"antes".equals(sentido);
        LocalDate resultadoEmData = dias == null ? null : achar(inicio, dias, uteis, depois);
        if (resultadoEmData != null && !anoValido(resultadoEmData)) {
            throw new DadosInvalidosException("O resultado cairia em " + DIA.format(resultadoEmData) + ", fora do que a ferramenta conta ("
                    + ANO_MINIMO + " a " + ANO_MAXIMO + ").", Map.of());
        }
        LocalDate ultimo = dias == null ? fim : resultadoEmData;
        String resultado = dias == null ? String.valueOf(quantos(inicio, fim, uteis)) : DIA.format(resultadoEmData);
        List<Feriado> pulados = uteis ? pulados(inicio, ultimo) : List.of();
        String regra = regra(inicio, fim, dias, uteis, depois);

        List<Map<String, String>> feriadosConsiderados = new ArrayList<>();
        for (Feriado feriado : pulados) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("data", DIA.format(feriado.data()));
            item.put("nome", feriado.nome());
            feriadosConsiderados.add(item);
        }
        // o servidor guarda a conta inteira (o que a conversa mostra em "Como calculei" e relê); o marcador é o da linha da fonte
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("ferramenta", NOME);
        dados.put("titulo", TITULO_DA_FONTE);
        dados.put("resultado", resultado);
        dados.put("regra", regra);
        dados.put("feriadosConsiderados", feriadosConsiderados);
        Fonte fonte = turno.registrarCalculo(null, TITULO_DA_FONTE, textoDaFonte(regra, resultado, dias == null, uteis, pulados), dados);
        turno.adicionarNumeroLivre(resultado);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("marcador", fonte.marcador());
        json.put("resultado", resultado);
        json.put("regra", regra);
        json.put("feriadosConsiderados", feriadosConsiderados);
        return Execucao.resultado(AtlasJson.escrever(json));
    }

    // ---------------------------------------------------------------------
    // A conta
    // ---------------------------------------------------------------------

    /** A data que está {@code dias} dias (úteis, se for o caso) antes ou depois de {@code inicio}; o dia de {@code inicio} não conta. */
    private LocalDate achar(LocalDate inicio, int dias, boolean uteis, boolean depois) {
        LocalDate data = inicio;
        int contados = 0;
        while (contados < dias) {
            data = depois ? data.plusDays(1) : data.minusDays(1);
            if (!uteis || feriados.diaUtil(data)) {
                contados++;
            }
        }
        return data;
    }

    /** Quantos dias há de {@code inicio} (que não conta) até {@code fim} (que conta, se for útil, com {@code uteis}). */
    private long quantos(LocalDate inicio, LocalDate fim, boolean uteis) {
        if (!uteis) {
            return ChronoUnit.DAYS.between(inicio, fim);
        }
        long uteisNoIntervalo = 0;
        for (LocalDate data = inicio.plusDays(1); !data.isAfter(fim); data = data.plusDays(1)) {
            if (feriados.diaUtil(data)) {
                uteisNoIntervalo++;
            }
        }
        return uteisNoIntervalo;
    }

    /** Os feriados que caíram em dia de semana entre os dois dias (o início fica de fora, o outro extremo entra), em ordem. */
    private List<Feriado> pulados(LocalDate inicio, LocalDate ultimo) {
        LocalDate de = inicio.isBefore(ultimo) ? inicio.plusDays(1) : ultimo;
        LocalDate ate = inicio.isBefore(ultimo) ? ultimo : inicio.minusDays(1);
        List<Feriado> pulados = new ArrayList<>();
        for (LocalDate data = de; !data.isAfter(ate); data = data.plusDays(1)) {
            Optional<Feriado> feriado = Feriados.fimDeSemana(data) ? Optional.empty() : feriados.em(data);
            feriado.ifPresent(pulados::add);
        }
        return pulados;
    }

    private static String regra(LocalDate inicio, LocalDate fim, Integer dias, boolean uteis, boolean depois) {
        String deixaDeFora = uteis ? ", deixando de fora sábados, domingos e feriados." : ".";
        if (dias != null) {
            String tipo = dias == 1 ? (uteis ? "dia útil" : "dia corrido") : (uteis ? "dias úteis" : "dias corridos");
            return "Contei " + dias + " " + tipo + (depois ? " depois de " : " antes de ") + DIA.format(inicio)
                    + ", sem contar o dia de início" + deixaDeFora;
        }
        return "Contei os " + (uteis ? "dias úteis" : "dias corridos") + " de " + DIA.format(inicio) + " até " + DIA.format(fim)
                + ", sem contar o dia de início e contando o dia final" + (uteis ? " quando ele é dia útil, deixando de fora sábados, "
                + "domingos e feriados." : ".");
    }

    /** Tudo o que a resposta pode citar da conta: as datas de entrada, a quantidade, o resultado e os feriados. */
    private static String textoDaFonte(String regra, String resultado, boolean emQuantidade, boolean uteis, List<Feriado> pulados) {
        StringBuilder texto = new StringBuilder(regra).append(" Resultado: ").append(resultado);
        if (emQuantidade) {
            texto.append(resultado.equals("1") ? (uteis ? " dia útil." : " dia corrido.") : (uteis ? " dias úteis." : " dias corridos."));
        } else {
            texto.append('.');
        }
        if (!pulados.isEmpty()) {
            texto.append(" Feriados que ficaram de fora:");
            for (int i = 0; i < pulados.size(); i++) {
                texto.append(i == 0 ? " " : "; ").append(DIA.format(pulados.get(i).data())).append(" (").append(pulados.get(i).nome()).append(')');
            }
            texto.append('.');
        }
        return texto.toString();
    }

    // ---------------------------------------------------------------------
    // Os argumentos
    // ---------------------------------------------------------------------

    private static LocalDate data(Map<String, Object> argumentos, String nome, boolean obrigatoria, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            if (obrigatoria) {
                erros.put(nome, "Informe " + nome + " no formato AAAA-MM-DD.");
            }
            return null;
        }
        if (!(valor instanceof String texto) || !DATA.matcher(texto).matches()) {
            erros.put(nome, nome + " tem de ser uma data no formato AAAA-MM-DD, como 2026-11-03.");
            return null;
        }
        LocalDate data;
        try {
            data = LocalDate.parse(texto);
        } catch (DateTimeException invalida) {
            erros.put(nome, nome + " não é uma data que existe: " + texto + ".");
            return null;
        }
        if (!anoValido(data)) {
            erros.put(nome, "O ano de " + nome + " tem de estar entre " + ANO_MINIMO + " e " + ANO_MAXIMO + ".");
            return null;
        }
        return data;
    }

    private static boolean anoValido(LocalDate data) {
        return data.getYear() >= ANO_MINIMO && data.getYear() <= ANO_MAXIMO;
    }

    private static Integer dias(Map<String, Object> argumentos, Map<String, String> erros) {
        Object valor = argumentos.get("dias");
        if (valor == null) {
            return null;
        }
        boolean inteiro = valor instanceof Integer || valor instanceof Long || valor instanceof Short || valor instanceof Byte
                || valor instanceof BigInteger;
        if (!inteiro || ((Number) valor).longValue() < 1 || ((Number) valor).longValue() > DIAS_MAXIMO
                || (valor instanceof BigInteger grande && grande.bitLength() > 31)) {
            erros.put("dias", "dias tem de ser um número inteiro de 1 a " + DIAS_MAXIMO + ".");
            return null;
        }
        return ((Number) valor).intValue();
    }

    private static boolean booleano(Map<String, Object> argumentos, String nome, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            return false;
        }
        if (!(valor instanceof Boolean certo)) {
            erros.put(nome, nome + " tem de ser verdadeiro ou falso.");
            return false;
        }
        return certo;
    }

    private static String sentido(Map<String, Object> argumentos, Map<String, String> erros) {
        Object valor = argumentos.get("sentido");
        if (valor == null) {
            return null;
        }
        if (!(valor instanceof String texto) || !(texto.equals("depois") || texto.equals("antes"))) {
            erros.put("sentido", "sentido tem de ser depois ou antes.");
            return null;
        }
        return texto;
    }
}
