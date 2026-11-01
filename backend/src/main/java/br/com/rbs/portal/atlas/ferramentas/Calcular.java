package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.texto;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Origens;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.MotorDeContas.Resultado;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.relatorio.ContaDoChat;
import br.com.rbs.portal.atlas.relatorio.ContaDoChat.Variavel;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code calcular(nome, script, variaveis, formato?)}: a conta do Atlas, no servidor (plano do Atlas completo, M5; nó C1 do v2).
 * [ATLAS] [VALIDACAO] O modelo não faz conta de cabeça nem escreve o resultado: escreve a <b>forma</b> da conta e diz de onde vem cada
 * número, e o servidor confere tudo antes de rodar.
 * <ol>
 *   <li><b>Cada variável tem origem.</b> {@code {ref}}: {@code cons:}, {@code dado:} ou {@code calc:} (uma conta anterior do turno
 *       ou uma fórmula fixa do modelo), resolvida só para a parceria da conversa (o {@link AtlasContexto}; nunca argumento).
 *       {@code {fonte, valor}}: o {@code valor} literal tem de estar no {@code texto} da fonte citada <b>deste turno</b>
 *       ({@code T#}, {@code R#}, {@code C#}; não o título nem a página) ou, com {@code usuario}, no que a pessoa escreveu nesta
 *       conversa (a mensagem do turno e as últimas 20 anteriores), comparado por valor ({@link Origens}).</li>
 *   <li><b>O script não tem número escrito</b>: passa antes pela lista de tokens do {@link ScriptDeConta} (variáveis, {@code pct},
 *       {@code min}, {@code max}, {@code + - * / ( ) ,} e as constantes 0, 1, 2, 100 e 1000, no máximo duas).</li>
 *   <li><b>Não mistura unidades</b>: INSERCAO, CPM e APLICACAO (a de cada variável vem da referência, do {@code dados.unidade} da
 *       fonte {@code R#} ou do campo {@code unidade} da variável).</li>
 *   <li>Roda no {@link MotorDeContas} (JEXL travado). O resultado é arredondado só na saída, em duas casas (meio para cima), e
 *       formatado como o portal ({@code fn_numero_br}). O formato {@code moeda}, um acréscimo ao plano, mostra sempre duas casas e
 *       não traz o símbolo: {@code fn_numero_br(10608.00)} é "10.608", e a tela precisa de "R$ 10.608,00" com
 *       {@code R$ {{calc:tarifa}}}.</li>
 * </ol>
 * Denominador zero ou referência sem valor devolve {@code {indisponivel: true, motivo}}, <b>sem marcador e sem fonte</b>, e nunca
 * zero. Uma conta boa vira a fonte {@code [C#]} (a conta inteira em {@code dados}, o valor formatado como número livre do turno), a
 * referência {@code calc:<nome>} que o texto usa como {@code {{calc:nome}}}, e, com um relatório em montagem na conversa, uma linha de
 * {@code atlas_calculo} (o relatório a resolve e a lista em "Fontes e cálculos"). Nenhum argumento é parceria.
 */
@Component
public class Calcular implements Ferramenta {

    public static final String NOME = "calcular";
    static final Set<String> UNIDADES = Set.of("INSERCAO", "CPM", "APLICACAO");
    static final int MAXIMO_DE_VARIAVEIS = 20;
    private static final Set<String> ARGUMENTOS = Set.of("nome", "script", "variaveis", "formato");
    private static final Set<String> ARGUMENTOS_DA_VARIAVEL = Set.of("ref", "fonte", "valor", "unidade");
    private static final Set<String> REFERENCIAS_QUE_VALEM = Set.of("cons", "dado", "calc");
    private static final Pattern NOME_DA_CONTA = Pattern.compile("[a-z_]{1,60}");
    private static final Pattern MARCADOR = Pattern.compile("[TRC][1-9][0-9]{0,2}");
    /** O valor literal: algarismos, com a vírgula do portal ({@code 312,00}), o ponto de milhar ({@code 1.250,00}) ou o ponto decimal. */
    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?");
    private static final BigDecimal TETO_DO_RESULTADO = new BigDecimal("1e14");   // atlas_calculo.resultado é NUMERIC(20,6) e fn_numero_br formata até 18 algarismos

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Faz uma conta no servidor, com a origem de cada número conferida. Use sempre que a resposta precisar de soma, subtração, "
                    + "multiplicação, divisão ou percentual; nunca faça a conta de cabeça nem escreva um resultado que esta ferramenta "
                    + "não devolveu. nome é o nome da conta (letras minúsculas e sublinhado, sem repetir neste turno). script é só a "
                    + "forma da conta: os nomes das variáveis, + - * / ( ), as funções pct(parte, todo), min(a, b) e max(a, b) e, no "
                    + "máximo, duas constantes entre 0, 1, 2, 100 e 1000. Qualquer outro número entra como variável. Cada variável é "
                    + "{\"ref\": ...} com uma referência que as ferramentas devolveram (cons:, dado: ou calc:, inclusive de uma conta "
                    + "anterior deste turno), ou {\"fonte\": \"T2\", \"valor\": \"312\"}, com o valor copiado do trecho T#, do "
                    + "registro R# ou da conta C# deste turno, ou {\"fonte\": \"usuario\", \"valor\": \"312\"}, com um valor que a "
                    + "pessoa escreveu nesta mensagem. Não misture INSERCAO, CPM e APLICACAO (campo unidade da variável, quando a "
                    + "fonte não diz). formato: numero (padrão) ou moeda, que mostra sempre duas casas. Devolve o marcador [C#] para "
                    + "citar a conta e a referência calc:nome: na resposta, escreva o resultado só com o marcador {{calc:nome}} (com "
                    + "R$ antes, se for moeda), nunca com o número. Se vier indisponivel, diga com palavras que não dá para calcular.",
            """
            {"type":"object","properties":{
              "nome":{"type":"string","pattern":"^[a-z_]{1,60}$","description":"Nome da conta, para citar como {{calc:nome}}."},
              "script":{"type":"string","maxLength":2000,"description":"A conta, só com variáveis, + - * / ( ), pct, min e max."},
              "variaveis":{"type":"object","description":"Cada variável do script: { ref } ou { fonte, valor }.",
                "additionalProperties":{"type":"object","properties":{
                  "ref":{"type":"string","description":"cons:..., dado:... ou calc:..."},
                  "fonte":{"type":"string","description":"T#, R# ou C# deste turno, ou usuario."},
                  "valor":{"type":"string","description":"O valor, como está na fonte."},
                  "unidade":{"type":"string","enum":["INSERCAO","CPM","APLICACAO"]}},
                 "additionalProperties":false}},
              "formato":{"type":"string","enum":["numero","moeda"],"description":"numero (padrão) ou moeda (duas casas)."}},
             "required":["nome","script","variaveis"],"additionalProperties":false}""");

    /** Uma variável já resolvida: o número, de onde veio e a unidade, se tem. */
    private record Entrada(String nome, String origem, BigDecimal numero, String unidade) {
    }

    private final ResolvedorDeReferencias resolvedor;
    private final ModeloBase modelos;
    private final AtlasRepositorio repositorio;
    private final ModoDoAtlas modo;
    private final MotorDeContas motor;

    public Calcular(ResolvedorDeReferencias resolvedor, ModeloBase modelos, AtlasRepositorio repositorio, ModoDoAtlas modo,
                    MotorDeContas motor) {
        this.resolvedor = resolvedor;
        this.modelos = modelos;
        this.repositorio = repositorio;
        this.modo = modo;
        this.motor = motor;
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
        return 40;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return !estado.cartaoAberto();
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        // 1. A forma dos argumentos
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS, erros);
        Optional<LinhaDeRelatorio> relatorio = relatorioEmMontagem(contexto);
        String nome = nomeDaConta(argumentos, turno, relatorio, erros);
        String script = texto(argumentos, "script", true, erros);
        String formato = formato(argumentos, erros);
        Map<String, Map<String, Object>> declaradas = variaveisDeclaradas(argumentos, erros);
        exigirSemErros(erros);

        // 2. O script: a lista de tokens, antes de qualquer outra coisa
        ScriptDeConta.conferir(script, declaradas.keySet());

        // 3. A origem de cada variável
        Resolucao resolucao = resolucaoDoTurno(contexto, turno);
        List<Entrada> entradas = new ArrayList<>();
        List<String> indisponiveis = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> declarada : declaradas.entrySet()) {
            Entrada entrada = entrada(declarada.getKey(), declarada.getValue(), resolucao, turno, erros, indisponiveis);
            if (entrada != null) {
                entradas.add(entrada);
            }
        }
        exigirSemErros(erros);
        if (!indisponiveis.isEmpty()) {
            return indisponivel(indisponiveis.get(0));
        }
        String unidade = unidadeDaConta(entradas);

        // 4. A conta
        Map<String, BigDecimal> numeros = new LinkedHashMap<>();
        entradas.forEach(entrada -> numeros.put(entrada.nome(), entrada.numero()));
        Resultado resultado = motor.avaliar(script, numeros);
        if (!resultado.disponivel()) {
            return indisponivel("Não dá para calcular: " + resultado.motivo().toLowerCase(java.util.Locale.ROOT));
        }
        BigDecimal valor = resultado.valor().setScale(2, RoundingMode.HALF_UP);
        if (valor.abs().compareTo(TETO_DO_RESULTADO) >= 0) {
            erros.put("script", "O resultado é grande demais para guardar. Confira os valores das variáveis.");
            exigirSemErros(erros);
        }

        // 5. A saída, formatada no padrão do portal
        List<ContaDoChat.Variavel> daConta = new ArrayList<>();
        List<BigDecimal> paraFormatar = new ArrayList<>();
        entradas.forEach(entrada -> paraFormatar.add(entrada.numero()));
        paraFormatar.add(valor);
        BigDecimal parteInteira = valor.abs().setScale(0, RoundingMode.DOWN);
        paraFormatar.add(parteInteira);
        List<String> formatados = repositorio.formatarNumeros(paraFormatar);
        for (int i = 0; i < entradas.size(); i++) {
            Entrada entrada = entradas.get(i);
            daConta.add(new Variavel(entrada.nome(), entrada.origem(), formatados.get(i), entrada.numero(), entrada.unidade()));
        }
        String valorFormatado = "moeda".equals(formato) ? comoMoeda(valor, parteInteira, formatados.get(entradas.size() + 1))
                : formatados.get(entradas.size());
        ContaDoChat conta = new ContaDoChat(nome, valor, valorFormatado, script, unidade, formato, daConta);

        // 6. Tudo conferido: agora a conta vira fonte do turno (e, com relatório em montagem, linha do relatório)
        relatorio.ifPresent(montando -> repositorio.inserirCalculo(montando.id(), nome, script, AtlasJson.escrever(conta.comoDados()), valor,
                unidade));
        Fonte fonte = turno.registrarCalculo(nome, nome, textoDaFonte(conta), conta.comoDados());
        turno.adicionarNumeroLivre(valorFormatado);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("marcador", fonte.marcador());
        json.put("ref", conta.ref());
        json.put("valor", valor);
        json.put("valorFormatado", valorFormatado);
        json.put("script", script);
        List<Map<String, Object>> variaveis = new ArrayList<>();
        for (Variavel variavel : daConta) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nome", variavel.nome());
            item.put("origem", variavel.origem());
            item.put("valor", variavel.valor());
            variaveis.add(item);
        }
        json.put("variaveis", variaveis);
        return Execucao.resultado(AtlasJson.escrever(json));
    }

    // ---------------------------------------------------------------------
    // Os argumentos
    // ---------------------------------------------------------------------

    /** O relatório da conversa, se está em montagem (e o relatório está ligado): a conta vai também para ele. */
    private Optional<LinhaDeRelatorio> relatorioEmMontagem(AtlasContexto contexto) {
        if (!modo.relatorioLigado()) {
            return Optional.empty();
        }
        return repositorio.relatorioAtivoDaConversa(contexto.conversaId()).filter(r -> "MONTANDO".equals(r.status()));
    }

    private String nomeDaConta(Map<String, Object> argumentos, FontesDoTurno turno, Optional<LinhaDeRelatorio> relatorio,
                               Map<String, String> erros) {
        String nome = texto(argumentos, "nome", true, erros);
        if (nome == null) {
            return null;
        }
        if (!NOME_DA_CONTA.matcher(nome).matches()) {
            erros.put("nome", "O nome da conta usa só letras minúsculas sem acento e sublinhado, de 1 a 60 caracteres.");
        } else if (modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow().calculos().containsKey(nome)) {
            erros.put("nome", "O nome " + nome + " é de uma fórmula fixa do modelo. Escolha outro; para usar a fórmula, ponha calc:" + nome
                    + " numa variável.");
        } else if (turno.conta(nome).isPresent()
                || relatorio.map(montando -> repositorio.calculoExiste(montando.id(), nome)).orElse(false)) {
            erros.put("nome", "Já existe uma conta chamada " + nome + " neste turno ou no relatório. Escolha outro nome.");
        }
        return nome;
    }

    private static String formato(Map<String, Object> argumentos, Map<String, String> erros) {
        Object formato = argumentos.get("formato");
        if (formato == null) {
            return "numero";
        }
        if (!"numero".equals(formato) && !"moeda".equals(formato)) {
            erros.put("formato", "formato tem de ser numero ou moeda.");
            return "numero";
        }
        return (String) formato;
    }

    /** As variáveis como mapa de nome para a variável crua, já com a forma conferida (nome, chaves, ref ou fonte e valor). */
    private static Map<String, Map<String, Object>> variaveisDeclaradas(Map<String, Object> argumentos, Map<String, String> erros) {
        Map<String, Map<String, Object>> declaradas = new LinkedHashMap<>();
        Object bruto = argumentos.get("variaveis");
        if (!(bruto instanceof Map<?, ?> mapa) || mapa.isEmpty()) {
            erros.put("variaveis", "variaveis tem de ser um objeto com pelo menos uma variável: { \"nome\": { \"ref\": ... } }.");
            return declaradas;
        }
        if (mapa.size() > MAXIMO_DE_VARIAVEIS) {
            erros.put("variaveis", "Use no máximo " + MAXIMO_DE_VARIAVEIS + " variáveis.");
            return declaradas;
        }
        for (Map.Entry<?, ?> entrada : mapa.entrySet()) {
            String nome = String.valueOf(entrada.getKey());
            String campo = "variaveis." + nome;
            if (!ScriptDeConta.nomeDeVariavelValido(nome)) {
                erros.put(campo, "O nome de variável \"" + nome + "\" não vale: letras minúsculas, algarismos e sublinhado, começando por "
                        + "letra, e nunca uma palavra reservada nem pct, min ou max.");
                continue;
            }
            if (!(entrada.getValue() instanceof Map<?, ?> variavel)) {
                erros.put(campo, nome + " tem de ser um objeto: { \"ref\": ... } ou { \"fonte\": ..., \"valor\": ... }.");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> campos = (Map<String, Object>) variavel;
            boolean achouDesconhecido = false;
            for (String chave : campos.keySet()) {
                if (!ARGUMENTOS_DA_VARIAVEL.contains(chave)) {
                    erros.put(campo, "Argumento desconhecido na variável " + nome + ": não existe " + chave + ".");
                    achouDesconhecido = true;
                }
            }
            if (achouDesconhecido) {
                continue;
            }
            boolean comRef = campos.get("ref") != null;
            boolean comFonte = campos.get("fonte") != null || campos.get("valor") != null;
            if (comRef == comFonte) {
                erros.put(campo, "A variável " + nome + " tem de ter ref, ou fonte e valor, e não os dois.");
            } else if (comRef && !(campos.get("ref") instanceof String)) {
                erros.put(campo, "ref de " + nome + " tem de ser um texto.");
            } else if (comFonte && (!(campos.get("fonte") instanceof String) || !(campos.get("valor") instanceof String))) {
                erros.put(campo, "fonte e valor de " + nome + " são textos, e os dois são obrigatórios; o valor vai entre aspas.");
            } else if (campos.get("unidade") != null && !(campos.get("unidade") instanceof String)) {
                erros.put(campo, "unidade de " + nome + " tem de ser um texto.");
            } else {
                declaradas.put(nome, campos);
            }
        }
        return declaradas;
    }

    // ---------------------------------------------------------------------
    // A origem de cada variável
    // ---------------------------------------------------------------------

    /** A resolução da parceria da conversa com as contas que este turno já fez ({@code calc:} de conta anterior). */
    private Resolucao resolucaoDoTurno(AtlasContexto contexto, FontesDoTurno turno) {
        Resolucao resolucao = resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
        for (Fonte conta : turno.contas()) {
            ContaDoChat.deDados(conta.dados()).ifPresent(resolucao::registrarConta);
        }
        return resolucao;
    }

    /**
     * A variável resolvida, ou nulo se deu erro (anotado em {@code erros}) ou se não tem valor agora (o motivo vai para
     * {@code indisponiveis}).
     */
    private Entrada entrada(String nome, Map<String, Object> variavel, Resolucao resolucao, FontesDoTurno turno, Map<String, String> erros,
                            List<String> indisponiveis) {
        String campo = "variaveis." + nome;
        String declarada = (String) variavel.get("unidade");
        if (declarada != null && !UNIDADES.contains(declarada)) {
            erros.put(campo, "A unidade " + declarada + " não existe: use INSERCAO, CPM ou APLICACAO.");
            return null;
        }
        if (variavel.get("ref") instanceof String ref) {
            return daReferencia(nome, ref, declarada, resolucao, erros, indisponiveis);
        }
        return daFonte(nome, (String) variavel.get("fonte"), (String) variavel.get("valor"), declarada, turno, erros);
    }

    private static Entrada daReferencia(String nome, String ref, String declarada, Resolucao resolucao, Map<String, String> erros,
                                        List<String> indisponiveis) {
        String campo = "variaveis." + nome;
        if (!Referencias.valida(ref)) {
            erros.put(campo, "Referência desconhecida: " + ref + ". Use só as referências que as ferramentas devolveram.");
            return null;
        }
        if (!REFERENCIAS_QUE_VALEM.contains(Referencias.prefixo(ref))) {
            erros.put(campo, "A referência " + ref + " não vale como variável: use cons:, dado: ou calc:.");
            return null;
        }
        Valor valor;
        try {
            valor = resolucao.valor(ref);
        } catch (ReferenciaDesconhecidaException desconhecida) {
            erros.put(campo, desconhecida.getMessage());
            return null;
        }
        if (!valor.disponivel()) {
            indisponiveis.add("Não dá para calcular: " + valor.rotulo() + " está indisponível. " + valor.motivo());
            return null;
        }
        if (valor.numero() == null) {
            erros.put(campo, "A referência " + ref + " não é um número (" + valor.texto() + "): não serve numa conta.");
            return null;
        }
        return comUnidades(nome, ref, valor.numero(), declarada, unidadeDaReferencia(ref, valor), erros);
    }

    /** A unidade de uma referência: APLICACAO nas aplicações previstas e comprovadas, ou a que o valor traz (de uma conta anterior). */
    private static String unidadeDaReferencia(String ref, Valor valor) {
        if (ref.equals("cons:aplicacoes.previstas") || ref.equals("cons:aplicacoes.comprovadas")) {
            return "APLICACAO";
        }
        return valor.unidade() != null && UNIDADES.contains(valor.unidade()) ? valor.unidade() : null;
    }

    private Entrada daFonte(String nome, String marcador, String valorLiteral, String declarada, FontesDoTurno turno,
                            Map<String, String> erros) {
        String campo = "variaveis." + nome;
        BigDecimal numero = numeroLiteral(valorLiteral);
        if (numero == null) {
            erros.put(campo, "O valor \"" + valorLiteral + "\" de " + nome + " não é um número: escreva só os algarismos, como estão na fonte "
                    + "(312, 1.250,00 ou 89,4).");
            return null;
        }
        if (numero.abs().compareTo(TETO_DO_RESULTADO) >= 0) {
            erros.put(campo, "O valor " + valorLiteral + " de " + nome + " é grande demais para uma conta.");
            return null;
        }
        List<Achado> achados = NormalizadorDeNumeros.extrair(valorLiteral, false);
        Origens origens;
        String onde;
        String unidadeDaFonte = null;
        if ("usuario".equals(marcador)) {
            origens = turno.origensDaPessoa();
            onde = "no que a pessoa escreveu nesta conversa (fonte usuario)";
        } else if (MARCADOR.matcher(marcador).matches()) {
            Optional<Fonte> fonte = turno.fonte(marcador);
            if (fonte.isEmpty()) {
                erros.put(campo, "A fonte " + marcador + " não existe neste turno. Use um marcador que uma ferramenta devolveu neste turno, "
                        + "ou usuario.");
                return null;
            }
            origens = new Origens().adicionarFonteCitada(fonte.get().texto());
            onde = "no texto da fonte " + marcador;
            Object unidade = fonte.get().dados().get("unidade");
            unidadeDaFonte = unidade instanceof String texto && UNIDADES.contains(texto) ? texto : null;
        } else {
            erros.put(campo, "A fonte \"" + marcador + "\" não é um marcador (T1, R1, C1...) nem usuario.");
            return null;
        }
        if (achados.isEmpty() || achados.stream().anyMatch(achado -> achado.ehData() || !origens.aceita(achado))) {
            erros.put(campo, "O valor " + valorLiteral + " não está " + onde + ". Copie o valor exatamente como está lá.");
            return null;
        }
        return comUnidades(nome, marcador, numero, declarada, unidadeDaFonte, erros);
    }

    /** O valor literal como número, ou nulo se não são só algarismos (com a vírgula, o milhar ou o ponto decimal). */
    private static BigDecimal numeroLiteral(String texto) {
        String limpo = texto.strip().replace("R$", "").replace("%", "").strip();
        if (limpo.length() > 30 || !NUMERO.matcher(limpo).matches()) {
            return null;
        }
        return new BigDecimal(NormalizadorDeNumeros.normalizarNumero(limpo));
    }

    /** As unidades que a variável carrega: a que o modelo declarou e a que a origem traz. Duas diferentes é erro. */
    private static Entrada comUnidades(String nome, String origem, BigDecimal numero, String declarada, String daOrigem,
                                       Map<String, String> erros) {
        Set<String> unidades = new LinkedHashSet<>();
        if (declarada != null) {
            unidades.add(declarada);
        }
        if (daOrigem != null) {
            unidades.add(daOrigem);
        }
        if (unidades.size() > 1) {
            erros.put("variaveis." + nome, "A unidade declarada (" + declarada + ") não é a da origem (" + daOrigem + ").");
            return null;
        }
        return new Entrada(nome, origem, numero, unidades.isEmpty() ? null : unidades.iterator().next());
    }

    /** A unidade da conta: a das variáveis, se alguma tem; duas diferentes é erro (não mistura INSERCAO, CPM e APLICACAO). */
    private static String unidadeDaConta(List<Entrada> entradas) {
        Set<String> unidades = new LinkedHashSet<>();
        entradas.stream().map(Entrada::unidade).filter(java.util.Objects::nonNull).forEach(unidades::add);
        if (unidades.size() > 1) {
            throw new br.com.rbs.portal.comum.DadosInvalidosException("A conta mistura unidades: " + String.join(" e ", unidades)
                    + ". Não some, subtraia nem divida valores de unidades diferentes.",
                    Map.of("variaveis", "As variáveis misturam as unidades " + String.join(" e ", unidades) + "."));
        }
        return unidades.isEmpty() ? null : unidades.iterator().next();
    }

    // ---------------------------------------------------------------------
    // A saída
    // ---------------------------------------------------------------------

    private static Execucao indisponivel(String motivo) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("indisponivel", true);
        json.put("motivo", motivo);
        return Execucao.resultado(AtlasJson.escrever(json));
    }

    /** O valor com sempre duas casas e o milhar do portal, sem o símbolo: "10.608,00". */
    private static String comoMoeda(BigDecimal valor, BigDecimal parteInteira, String milhares) {
        String centavos = valor.abs().subtract(parteInteira).movePointRight(2).setScale(0, RoundingMode.DOWN).toPlainString();
        return (valor.signum() < 0 ? "-" : "") + milhares + "," + (centavos.length() < 2 ? "0" + centavos : centavos);
    }

    /** Tudo o que a resposta pode citar da conta: o nome, o valor de cada variável e o resultado. */
    private static String textoDaFonte(ContaDoChat conta) {
        StringBuilder texto = new StringBuilder("Conta ").append(conta.nome()).append(": ");
        for (Variavel variavel : conta.variaveis()) {
            texto.append(variavel.nome()).append(" = ").append(variavel.valor()).append("; ");
        }
        return texto.append("resultado = ").append(conta.valorFormatado()).append('.').toString();
    }
}
