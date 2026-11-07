package br.com.rbs.portal.importacao.extracao;

import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.PlanilhaDoPlano;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Casa a linha importada com a linha do plano comercial da marca (E11). [IMPORTACAO] A ordem é: igual depois de normalizar caixa,
 * acento, aspas e espaços; depois as palavras do formato, quando o veículo é o mesmo (plural, prefixo de cinco letras e os números
 * iguais, com "Indeterminado" no espaço aceitando qualquer espaço); depois a semelhança por trigramas, toda em Java. A sugestão e a
 * semelhança ficam guardadas na linha; abaixo de 0,6 não há sugestão, e a pessoa escolhe a linha do plano na prévia.
 */
@Component
public class CasadorDoPlano {

    /** O que o casador achou: a linha do plano, a semelhança (1,0 na igualdade) e o motivo quando não há plano para a marca. */
    public record Resultado(Long aproveitamentoId, BigDecimal semelhanca, String problema) {
    }

    private static final double LIMIAR = 0.6;
    private static final BigDecimal APROXIMADA = new BigDecimal("0.900");

    private final ImportacaoRepositorio repositorio;

    public CasadorDoPlano(ImportacaoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    public Resultado casar(long parceriaId, String veiculo, String formato, String espacoPrograma) {
        List<PlanilhaDoPlano> plano = repositorio.planoDaParceria(parceriaId);
        if (plano.isEmpty()) {
            return new Resultado(null, null, "Falta o plano comercial desta marca");
        }
        String veiculoNormalizado = normalizar(veiculo);
        String formatoNormalizado = normalizar(formato);
        String espacoNormalizado = normalizar(espacoPrograma);
        PlanilhaDoPlano porPalavras = null;
        PlanilhaDoPlano melhor = null;
        double melhorNota = 0;
        for (PlanilhaDoPlano linha : plano) {
            String outroVeiculo = normalizar(linha.veiculo());
            String outroFormato = normalizar(linha.formato());
            String outroEspaco = normalizar(linha.espacoPrograma());
            boolean mesmoVeiculo = veiculoNormalizado.equals(outroVeiculo);
            boolean mesmoFormato = formatoNormalizado.equals(outroFormato);
            boolean espacoAceito = espacoDoPlanoAceita(espacoNormalizado, outroEspaco);
            if (mesmoVeiculo && mesmoFormato && espacoAceito) {
                return new Resultado(linha.id(), BigDecimal.ONE, null);
            }
            if (mesmoVeiculo && espacoAceito && porPalavras == null
                    && PalavrasDaFonte.parecido(formato, linha.formato())) {
                porPalavras = linha;
            }
            // O mesmo veículo e formato num espaço que não é o da linha não vira sugestão; números diferentes nunca casam, nem pela
            // semelhança de trigramas.
            if ((mesmoVeiculo && mesmoFormato) || PalavrasDaFonte.numerosConflitantes(formato, linha.formato())) {
                continue;
            }
            double nota = semelhanca(veiculoNormalizado + " " + formatoNormalizado + " " + espacoNormalizado,
                    outroVeiculo + " " + outroFormato + " " + outroEspaco);
            if (nota > melhorNota) {
                melhorNota = nota;
                melhor = linha;
            }
        }
        if (porPalavras != null) {
            return new Resultado(porPalavras.id(), APROXIMADA, null);
        }
        if (melhor != null && melhorNota >= LIMIAR) {
            return new Resultado(melhor.id(), BigDecimal.valueOf(melhorNota).setScale(3, RoundingMode.HALF_UP), null);
        }
        return new Resultado(null, null, null);
    }

    /** O espaço do plano vazio ou "Indeterminado" aceita qualquer espaço; o da linha vazio também não atrapalha. */
    private static boolean espacoDoPlanoAceita(String espaco, String outro) {
        return espaco.isEmpty() || outro.isEmpty() || outro.startsWith("indeterminado") || espaco.equals(outro);
    }

    /** Sem caixa, sem acento, sem aspas e com um espaço só entre as palavras. */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT)
                .replaceAll("[\\u2018\\u2019\\u201C\\u201D\"'`´]", "")
                .replaceAll("[^a-z0-9]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    /** A semelhança de Sørensen–Dice sobre os trigramas dos dois textos. */
    static double semelhanca(String a, String b) {
        Set<String> ta = trigramas(a);
        Set<String> tb = trigramas(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return a.equals(b) ? 1 : 0;
        }
        int comuns = 0;
        for (String t : ta) {
            if (tb.contains(t)) {
                comuns++;
            }
        }
        return (2.0 * comuns) / (ta.size() + tb.size());
    }

    private static Set<String> trigramas(String texto) {
        String limpo = texto.replace(" ", "");
        Set<String> trigramas = new HashSet<>();
        for (int i = 0; i + 3 <= limpo.length(); i++) {
            trigramas.add(limpo.substring(i, i + 3));
        }
        if (limpo.length() < 3) {
            trigramas.add(limpo);
        }
        return trigramas;
    }
}
