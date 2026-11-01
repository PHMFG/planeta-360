package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CalculoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.VariavelDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uma conta do {@code calcular}, como o chat a guarda e a relê (plano do Atlas completo, M5). [ATLAS] A conta vive em três lugares
 * que precisam concordar: o turno ({@code FontesDoTurno}), a linha de {@code atlas_fonte} da mensagem que a usou (a releitura da
 * conversa) e, com um relatório em montagem, a linha de {@code atlas_calculo}. Aqui está o formato dela em {@code dados} (JSON) e o
 * que ela é para a resolução: o valor de {@code {{calc:nome}}} e de {@code calc:nome} como variável de outra conta.
 *
 * @param nome           o nome da conta ({@code [a-z][a-z0-9_]{0,59}}); a referência é {@code calc:<nome>}
 * @param valor          o resultado, arredondado em duas casas
 * @param valorFormatado o resultado como a tela mostra (padrão do portal; com o formato moeda, sempre duas casas e sem o símbolo)
 * @param script         a conta como o modelo a escreveu
 * @param unidade        a unidade das variáveis (INSERCAO, CPM ou APLICACAO), se elas tinham uma
 * @param formato        {@code numero} ou {@code moeda}
 * @param variaveis      as variáveis, na ordem em que o modelo as declarou
 * @param valorExato     o resultado sem arredondar (DECIMAL128): a conta seguinte que usa esta parte dele, e não do {@code valor} de duas
 *                       casas (100/3 &times; 3 é 100, e não 99,99); só o que sai para a tela é arredondado. Nulo nas contas gravadas antes
 *                       deste campo, que usam o {@code valor}
 * @param constantes     quantas constantes do script esta conta soma <b>pela cadeia</b>: as do script dela e as das contas
 *                       {@code calc:} que ela usa (a fórmula fixa do modelo conta zero). O {@code 100} que divide ({@code v * p / 100}) só
 *                       encolhe o número e não entra. O limite de duas constantes vale para o total, e não conta a conta
 */
public record ContaDoChat(String nome, BigDecimal valor, String valorFormatado, String script, String unidade, String formato,
                          List<Variavel> variaveis, BigDecimal valorExato, int constantes) {

    /** De onde vem o valor de uma variável: a referência, o marcador da fonte ou {@code usuario}. */
    public record Variavel(String nome, String origem, String valor, BigDecimal numero, String unidade) {
    }

    /** A origem que o relatório lista em "Fontes e cálculos" para a conta. */
    private static final String ORIGEM = "Conta do Atlas";

    public ContaDoChat {
        variaveis = variaveis == null ? List.of() : List.copyOf(variaveis);
    }

    /** Uma conta sem o resultado exato nem constantes (as gravadas antes desses campos, e a conta montada à mão): usa o {@code valor}. */
    public ContaDoChat(String nome, BigDecimal valor, String valorFormatado, String script, String unidade, String formato,
                       List<Variavel> variaveis) {
        this(nome, valor, valorFormatado, script, unidade, formato, variaveis, null, 0);
    }

    public String ref() {
        return "calc:" + nome;
    }

    /** A conta como valor para a resolução: o texto é o valor formatado, e a origem diz a conta e o que entrou. */
    public Valor comoValor() {
        List<String> entradas = new ArrayList<>();
        variaveis.forEach(variavel -> entradas.add(variavel.nome() + " = " + variavel.valor()));
        return new Valor(ref(), nome, valorFormatado, valorExato != null ? valorExato : valor, unidade,
                ORIGEM + ": " + script + (entradas.isEmpty() ? "" : " com " + ResolvedorDeReferencias.juntar(entradas)), null);
    }

    /** A conta como as contas das fórmulas fixas aparecem em "Fontes e cálculos" do relatório. */
    public CalculoDto comoCalculo() {
        List<VariavelDto> entradas = variaveis.stream()
                .map(variavel -> new VariavelDto(variavel.nome(), variavel.origem(), variavel.numero(), variavel.valor(), variavel.unidade()))
                .toList();
        return new CalculoDto(nome, nome, script, entradas, valor, valorFormatado, unidade, null);
    }

    /**
     * O que se grava em {@code atlas_fonte.dados} (e em {@code atlas_calculo.variaveis}, com um relatório em montagem): a conta
     * inteira, para a releitura trocar o marcador pelo valor sem refazer a conta.
     */
    public Map<String, Object> comoDados() {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("ferramenta", "calcular");
        dados.put("nome", nome);
        dados.put("ref", ref());
        dados.put("valor", valor);
        if (valorExato != null) {
            dados.put("valorExato", valorExato.toPlainString());   // texto: o número do JSON perderia as casas além do double
        }
        dados.put("constantes", constantes);
        dados.put("valorFormatado", valorFormatado);
        dados.put("formato", formato);
        dados.put("unidade", unidade);
        dados.put("script", script);
        List<Map<String, Object>> entradas = new ArrayList<>();
        for (Variavel variavel : variaveis) {
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("nome", variavel.nome());
            entrada.put("origem", variavel.origem());
            entrada.put("valor", variavel.valor());
            entrada.put("numero", variavel.numero());
            entrada.put("unidade", variavel.unidade());
            entradas.add(entrada);
        }
        dados.put("variaveis", entradas);
        return dados;
    }

    /** A conta lida de {@link #comoDados()}; vazio se os dados não são os de uma conta do {@code calcular}. */
    @SuppressWarnings("unchecked")
    public static java.util.Optional<ContaDoChat> deDados(Map<String, Object> dados) {
        if (dados == null || !"calcular".equals(dados.get("ferramenta")) || !(dados.get("nome") instanceof String nome)
                || dados.get("valor") == null || !(dados.get("valorFormatado") instanceof String valorFormatado)
                || !(dados.get("script") instanceof String script)) {
            return java.util.Optional.empty();
        }
        List<Variavel> variaveis = new ArrayList<>();
        if (dados.get("variaveis") instanceof List<?> entradas) {
            for (Object entrada : entradas) {
                Map<String, Object> mapa = (Map<String, Object>) entrada;
                variaveis.add(new Variavel((String) mapa.get("nome"), (String) mapa.get("origem"), (String) mapa.get("valor"),
                        mapa.get("numero") == null ? null : numero(mapa.get("numero")), (String) mapa.get("unidade")));
            }
        }
        return java.util.Optional.of(new ContaDoChat(nome, numero(dados.get("valor")), valorFormatado, script, (String) dados.get("unidade"),
                (String) dados.get("formato"), variaveis, dados.get("valorExato") == null ? null : numero(dados.get("valorExato")),
                dados.get("constantes") instanceof Number constantes ? constantes.intValue() : 0));
    }

    private static BigDecimal numero(Object valor) {
        return valor instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(valor));
    }
}
