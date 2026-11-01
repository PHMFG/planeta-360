package br.com.rbs.portal.atlas.checagem;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Origens;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A checagem do texto do CHAT quando há fontes (plano do Atlas completo, M4; nó V1 do v2 e a camada 7 do isolamento). [ATLAS]
 * [VALIDACAO] <b>Substitui</b>, no chat, a regra "nenhum algarismo fora de marcador" da {@link ChecagemDeTexto}: com documentos, o
 * Atlas precisa dizer "04/12/2026" e "R$ 312". O que a regra nova garante é que <b>todo número tem origem</b>:
 * <ol>
 *   <li>Toda citação ({@code [T1]}, {@code [R2]}, {@code [C3]}, {@code [T1, T2]}) existe nos resultados DESTE turno
 *       ({@link FontesDoTurno}). Citação de outro turno, inventada ({@code [T9]}, {@code [X1]}, {@code [t1]}, {@code [T 1]}) ou em
 *       outro formato com número ({@code [1]}) é recusada.</li>
 *   <li>Todo número ou data fora de um marcador {@code {{ref}}} aparece <b>numa fonte citada na MESMA frase</b>, no resultado de uma
 *       conta ou contagem de dias deste turno, ou na mensagem da pessoa neste turno. A página, a seção, a versão e o título da fonte
 *       valem como parte dela. A numeração de lista no começo da linha só não conta se estiver em sequência (1, 2, 3...). A data de hoje vale sem fonte; o ano da edição e o
 *       de hoje valem só como ano (veja {@link NormalizadorDeNumeros}), nunca como valor. A comparação é por valor ({@link NormalizadorDeNumeros}): {@code 1.250.000,00} e {@code 1250000}, {@code R$ 312} e
 *       {@code 312}, {@code 04/12/2026}, {@code 4/12}, {@code 4 de dezembro} e {@code 2026-12-04}, {@code 6h30} e {@code 06:30},
 *       {@code 89,4%} e {@code 89,4}. Todo numeral por extenso conta como número, menos "um" e "uma" (artigo) e "dois"/"duas" sem unidade.</li>
 *   <li>Sem URL, link nem imagem em Markdown, sem HTML, sem nome de ferramenta nem chave crua, e todo marcador {@code {{ref}}} resolve e
 *       tem valor (as regras de forma e de marcador são as da {@link ChecagemDeTexto}).</li>
 * </ol>
 * Antes de tudo saem os caracteres de formato do Unicode ({@link #semInvisiveis}: espaço de largura zero, hífen opcional, os de
 * direção da escrita), que não aparecem na tela mas partem ou reordenam um número; o texto aprovado, o que vai à tela e o que o
 * histórico guarda são o texto sem eles. Os algarismos de outros alfabetos ({@code ٣٤}), os de largura total ({@code ３４}), os
 * sobrescritos e os círculos ({@code ², ①}) viram os comuns e valem como qualquer número. O que é número mas não vira algarismo
 * comum ({@code Ⅳ, ௰, ፲, ㊀}) é recusado, com o pedido de algarismos comuns: a regra não sabe o valor dele. Isto é só para o chat:
 * o texto do relatório e o resumo do raciocínio continuam na regra antiga, sem nenhum algarismo fora de marcador. A checagem não
 * grava nada.
 */
@Component
public class ChecagemDeFontes {

    /** A citação colada a um parêntese ({@code [T1](http://...)}) é um link em Markdown; com espaço ({@code [T1] (seção 3.2)}) não é. */
    private static final Pattern CITACAO_COLADA_NUM_LINK = Pattern.compile(Frases.CITACAO.pattern() + "\\(");
    /**
     * Colchete com número que não é uma citação no formato, fechado ou não: {@code [1]}, {@code [t1]}, {@code [X1]}, {@code [T 1]},
     * {@code [T1;T2]}, {@code [T1} (a que o modelo deixou aberta).
     */
    private static final Pattern COLCHETE_COM_NUMERO = Pattern.compile("\\[[^\\]\\[]{0,20}\\d");
    private static final int TAMANHO_DO_TRECHO_NA_RECUSA = 140;

    /**
     * Confere o texto do chat. {@code fontes} é o que as ferramentas devolveram neste turno e {@code resolucao} resolve os
     * marcadores {@code {{ref}}} desta parceria. Devolve o texto aprovado, ou o motivo da recusa, escrito para o modelo ler e
     * corrigir.
     */
    public Veredito chat(Resolucao resolucao, FontesDoTurno fontes, String original) {
        if (original == null || original.isBlank()) {
            return Veredito.recusado("O texto está vazio.");
        }
        String semInvisiveis = semInvisiveis(original);
        Optional<String> deNumeral = motivoDeNumeralSemAlgarismoComum(semInvisiveis);
        if (deNumeral.isPresent()) {
            return Veredito.recusado(deNumeral.get());
        }
        String texto = dobrarAlgarismos(semInvisiveis).strip();
        String compacto = texto.replaceAll("\\s+", " ");
        String semCitacoes = Frases.semCitacoes(texto);
        String semMarcadores = Referencias.semMarcadores(Frases.semCitacoes(compacto));

        if (CITACAO_COLADA_NUM_LINK.matcher(compacto).find()) {
            return Veredito.recusado("Sem link em Markdown: a citação [T1] vai sozinha, sem parêntese colado. Escreva só texto simples.");
        }
        Optional<String> deForma = ChecagemDeTexto.motivoDeForma(compacto, semEmailsConhecidos(semMarcadores, fontes));
        if (deForma.isPresent()) {
            return Veredito.recusado(deForma.get());
        }
        Optional<String> deCitacao = motivoDeCitacao(fontes, texto, Referencias.semMarcadores(semCitacoes));
        if (deCitacao.isPresent()) {
            return Veredito.recusado(deCitacao.get());
        }
        Optional<String> deMarcador = ChecagemDeTexto.motivoDeMarcador(resolucao, compacto, false);
        if (deMarcador.isPresent()) {
            return Veredito.recusado(deMarcador.get());
        }
        if (semMarcadores.indexOf('{') >= 0 || semMarcadores.indexOf('}') >= 0) {
            return Veredito.recusado("Há uma chave { ou } solta no texto. Marcador se escreve {{ref}}, com duas chaves de cada lado.");
        }
        Optional<String> deNumero = motivoDeNumero(fontes, texto);
        if (deNumero.isPresent()) {
            return Veredito.recusado(deNumero.get());
        }
        return Veredito.aprovado(compacto);
    }

    /** Um endereço de e-mail inteiro: a parte antes do arroba só começa onde a palavra começa, para o custo não crescer com o texto. */
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    private static final Pattern CARACTERE_DE_FORMATO = Pattern.compile("\\p{Cf}");
    private static final Pattern COM_ALGARISMO_COMUM = Pattern.compile("[0-9]");

    /**
     * O texto sem os caracteres de formato (categoria Cf do Unicode): o espaço de largura zero, o hífen opcional, os de junção e os
     * que mandam a escrita da direita para a esquerda. Não aparecem na tela, mas partem um número ({@code 3<ZWSP>12} lê-se 312) ou
     * reordenam os algarismos ({@code R$ <RLO>213} aparece como 312). O texto que se confere e o que vai à tela e ao histórico é este.
     */
    public static String semInvisiveis(String texto) {
        return texto == null ? null : CARACTERE_DE_FORMATO.matcher(texto).replaceAll("");
    }

    /**
     * Um número escrito em outra forma que não vira algarismo comum (0 a 9) nem depois de dobrado pelo Unicode: o numeral romano
     * ({@code Ⅳ}), o tâmil ({@code ௰}), o etíope ({@code ፲}), o ideograma em círculo ({@code ㊀}). A regra não tem como saber o valor
     * dele, e então não o deixa passar: pede algarismos comuns. {@code ²}, {@code ①} e {@code ½} viram {@code 2}, {@code 1} e
     * {@code 1⁄2}, e seguem a regra dos números.
     */
    private static Optional<String> motivoDeNumeralSemAlgarismoComum(String texto) {
        for (int i = 0; i < texto.length(); ) {
            int c = texto.codePointAt(i);
            i += Character.charCount(c);
            int tipo = Character.getType(c);
            if ((tipo == Character.OTHER_NUMBER || tipo == Character.LETTER_NUMBER)
                    && !COM_ALGARISMO_COMUM.matcher(Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFKC)).find()) {
                return Optional.of("O texto tem o caractere \"" + new String(Character.toChars(c)) + "\", que é um número em outra escrita e "
                        + "não dá para conferir. Escreva os números só com algarismos comuns (0 a 9).");
            }
        }
        return Optional.empty();
    }

    /**
     * Todo algarismo vira o comum, 0 a 9: o de outro alfabeto (árabe-índico, devanágari), o de largura total e o sobrescrito ou
     * fração ({@code ²}, {@code ½}). O resto do texto fica como está (a letra com acento, a vírgula, o "º").
     */
    static String dobrarAlgarismos(String texto) {
        StringBuilder dobrado = null;
        for (int i = 0; i < texto.length(); ) {
            int c = texto.codePointAt(i);
            int tamanho = Character.charCount(c);
            String troca = null;
            int tipo = Character.getType(c);
            if (tipo == Character.DECIMAL_DIGIT_NUMBER && c > 127) {
                troca = String.valueOf(Character.digit(c, 10));
            } else if (tipo == Character.OTHER_NUMBER || tipo == Character.LETTER_NUMBER) {
                troca = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFKC);
            }
            if (troca != null && dobrado == null) {
                dobrado = new StringBuilder(texto.length() + 8).append(texto, 0, i);
            }
            if (dobrado != null) {
                dobrado.append(troca != null ? troca : texto.substring(i, i + tamanho));
            }
            i += tamanho;
        }
        return dobrado == null ? texto : dobrado.toString();
    }

    /**
     * O texto sem os e-mails que o turno já tem: os que estão em alguma fonte deste turno (o contato de um documento) ou na mensagem
     * da pessoa. O e-mail é um endereço inteiro, comparado sem diferenciar maiúscula de minúscula; um que o turno não tem fica no
     * texto, e a regra de endereço o recusa. O domínio solto, mesmo na fonte, continua recusado: é como a instrução escondida de um
     * documento manda o modelo escrever o endereço de quem coleta o relatório.
     */
    private static String semEmailsConhecidos(String semMarcadores, FontesDoTurno fontes) {
        Set<String> conhecidos = new HashSet<>();
        emailsDe(fontes.mensagemDaPessoa(), conhecidos);
        fontes.fontes().forEach(fonte -> emailsDe(fonte.conteudoCitavel(), conhecidos));
        if (conhecidos.isEmpty()) {
            return semMarcadores;
        }
        Matcher email = EMAIL.matcher(semMarcadores);
        StringBuilder saida = new StringBuilder();
        while (email.find()) {
            email.appendReplacement(saida, conhecidos.contains(email.group().toLowerCase(Locale.ROOT)) ? " " : Matcher.quoteReplacement(email.group()));
        }
        return email.appendTail(saida).toString();
    }

    private static void emailsDe(String texto, Set<String> conhecidos) {
        Matcher email = EMAIL.matcher(semInvisiveis(texto));
        while (email.find()) {
            conhecidos.add(email.group().toLowerCase(Locale.ROOT));
        }
    }

    /** As citações existem neste turno e todo colchete com número é uma citação. */
    private static Optional<String> motivoDeCitacao(FontesDoTurno fontes, String texto, String semCitacoesNemMarcadores) {
        for (String marcador : new LinkedHashSet<>(Frases.citacoes(texto))) {
            if (!fontes.existe(marcador)) {
                String existentes = fontes.fontes().isEmpty() ? "Neste turno nenhuma ferramenta devolveu fonte para citar."
                        : "Neste turno existem: " + String.join(", ", fontes.fontes().stream().map(Fonte::marcador).toList()) + ".";
                return Optional.of("A citação [" + marcador + "] não existe nos resultados deste turno. Cite só os marcadores que as "
                        + "ferramentas devolveram neste turno (os de turnos anteriores não valem: busque de novo). " + existentes);
            }
        }
        Matcher colchete = COLCHETE_COM_NUMERO.matcher(semCitacoesNemMarcadores);
        if (colchete.find()) {
            return Optional.of("O trecho \"" + colchete.group() + "\" parece uma citação, mas não está no formato [T1]. Cite a fonte "
                    + "com o marcador que a ferramenta devolveu, como [T1], ou tire o colchete.");
        }
        return Optional.empty();
    }

    /**
     * Todo número ou data de cada frase tem origem: fonte citada na frase, conta do turno, mensagem da pessoa, edição ou hoje. Uma
     * data sem ano só libera, na resposta, o ano da edição, o anterior a ela ou o de hoje; e se a fonte citada na frase diz esse dia
     * COM ano, vale o ano dela, mesmo que a pessoa tenha escrito a data sem ano. O ano da edição e o de hoje valem como ano, e nunca
     * como valor ({@code R$ 2.027,00}, {@code 2026 credenciais}).
     */
    private static Optional<String> motivoDeNumero(FontesDoTurno fontes, String texto) {
        int edicao = fontes.anoDaEdicao();
        int anoDeHoje = fontes.hoje().getYear();
        Set<String> anosAceitos = new LinkedHashSet<>(List.of(String.valueOf(edicao), String.valueOf(edicao - 1), String.valueOf(anoDeHoje)));
        Origens geral = new Origens(fontes.mensagemDaPessoa()).anosAceitos(anosAceitos);
        geral.adicionarAnoLivre(edicao).adicionarAnoLivre(anoDeHoje).adicionarData(fontes.hoje());
        fontes.numerosLivres().forEach(geral::adicionar);
        for (String frase : Frases.dividir(NormalizadorDeNumeros.semNumeracaoDeLista(texto))) {
            Set<String> citadas = new LinkedHashSet<>(Frases.citacoes(frase));
            Origens daFrase = new Origens().anosAceitos(anosAceitos);
            for (String marcador : citadas) {
                fontes.fonte(marcador).ifPresent(fonte -> daFrase.adicionar(fonte.conteudoCitavel()));
            }
            List<Achado> numeros = NormalizadorDeNumeros.extrair(Referencias.semMarcadores(Frases.semCitacoes(frase)), false);
            for (Achado numero : numeros) {
                // A fonte citada disse o ano desta data: vale o que ela disse, mesmo que a pessoa tenha escrito a data sem ano.
                boolean fonteDisseOAno = numero.ano() != null && daFrase.trazDataComAno(numero.dia());
                boolean temOrigem = fonteDisseOAno ? daFrase.aceita(numero) : geral.aceita(numero) || daFrase.aceita(numero);
                if (!temOrigem) {
                    String trecho = frase.length() > TAMANHO_DO_TRECHO_NA_RECUSA ? frase.substring(0, TAMANHO_DO_TRECHO_NA_RECUSA) + "…"
                            : frase;
                    String quemCita = citadas.isEmpty() ? "A frase não cita nenhuma fonte."
                            : "A fonte que a frase cita (" + String.join(", ", citadas) + ") não traz esse valor.";
                    return Optional.of("O número ou a data \"" + numero.trecho().strip() + "\" na frase \"" + trecho + "\" não tem origem. "
                            + quemCita + " Copie números e datas exatamente como estão no trecho do documento e ponha a citação, "
                            + "como [T1], na mesma frase; dado da parceria vai só com o marcador {{ref}}. Se os trechos não trazem a "
                            + "resposta, escreva exatamente \"Não encontrei isso nos documentos do portal.\"");
                }
            }
        }
        return Optional.empty();
    }
}
