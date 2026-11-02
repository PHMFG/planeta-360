package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Desconfia de dado pessoal num documento antes de a RBS aprovar (decisão 13 do plano do Atlas completo). [ATLAS] Sem rede e sem
 * banco: lê o texto das páginas. As regras:
 * <ul>
 *   <li>CPF no formato completo ({@code 123.456.789-09}) marca sempre, e só deixa de marcar com <b>dígito colado</b> antes ou depois
 *       ({@code 1529.982.247-25}, {@code 529.982.247-253}); vírgula, barra ou hífen vizinhos não impedem. Com espaços em volta dos
 *       separadores ({@code 529. 982. 247-25}) marca se os dígitos verificadores baterem ou a palavra CPF vier logo antes. As formas
 *       sem espaço ({@code 12345678909}, {@code 123456789-09}) seguem a mesma conta. Os grupos separados por espaço, barra ou ponto
 *       fora do formato ({@code 123 456 789 09}, {@code 123.456.789/09}) só marcam com a palavra CPF até
 *       {@value #DISTANCIA_DA_PALAVRA_CPF} caracteres antes, nunca pelo dígito verificador: uma tabela de números não trava o
 *       documento;</li>
 *   <li>RG marca sempre: o número no formato {@code 12.345.678-9}, ou a palavra RG ({@code RG}, {@code R.G.}, {@code R. G.}) seguida,
 *       até {@value #DISTANCIA_DA_PALAVRA_RG} caracteres sem dígito depois, de um número de 7 a 10 dígitos com ponto, espaço ou hífen
 *       entre eles. Valor ({@code R$} ou {@code %} entre a palavra e o número) e dois anos ({@code 2026.2027}, {@code 2027-2028})
 *       não são RG;</li>
 *   <li>telefone e e-mail marcam só em lista: 3 ou mais contatos diferentes na mesma página, para um contato institucional (o
 *       e-mail do plantão, um telefone de apoio) não travar um manual. Telefone só com espaços ({@code 51 3333 4444}) não conta.</li>
 * </ul>
 * O texto da página é normalizado antes de procurar (NFKC, sem nenhum caractere invisível de formato, traços longos viram hífen,
 * espaços especiais viram espaço): sem isso, um dígito de largura total ou um espaço de largura zero escondia o CPF. Valor em reais
 * ({@code R$ 312,00}), data ({@code 29/01/2027}) e faixa de anos ({@code 2026-2027}) nunca marcam. A suspeita não tem saída pela tela:
 * um falso positivo se resolve corrigindo o documento e enviando de novo (fica em pendencias.md).
 */
@Component
public class DetectorDeDadosPessoais {

    /** O que foi achado e onde: {@code tipo} é CPF, RG ou CONTATOS (a lista de telefones e e-mails). */
    public record Suspeita(int pagina, String tipo) {
    }

    /** Quantos contatos diferentes numa página fazem uma lista. */
    static final int CONTATOS_DE_UMA_LISTA = 3;

    /** Até quantos caracteres antes do número a palavra CPF ainda vale. */
    static final int DISTANCIA_DA_PALAVRA_CPF = 20;

    /** Até quantos caracteres (sem dígito) entre a palavra RG e o número. */
    static final int DISTANCIA_DA_PALAVRA_RG = 25;

    /** Não começa no meio de outro número: sem dígito antes, e sem dígito seguido de separador. */
    private static final String SEM_NUMERO_ANTES = "(?<!\\d)(?<!\\d[.,/-])";
    /** Não termina no meio de outro número: sem dígito depois, e sem separador seguido de dígito. */
    private static final String SEM_NUMERO_DEPOIS = "(?!\\d)(?![.,/-]\\d)";
    /** O formato completo só é recusado com dígito colado: a vírgula, a barra e o hífen vizinhos não o escondem. */
    private static final String SEM_DIGITO_ANTES = "(?<!\\d)";
    private static final String SEM_DIGITO_DEPOIS = "(?!\\d)";
    private static final int OPCOES_SEM_CAIXA = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CPF_FORMATADO = Pattern.compile(SEM_DIGITO_ANTES + "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}" + SEM_DIGITO_DEPOIS);
    /** O formato completo com espaços em volta dos separadores ({@code 529. 982. 247 - 25}): marca com o dígito verificador certo. */
    private static final Pattern CPF_FORMATADO_COM_ESPACOS = Pattern.compile(
            SEM_DIGITO_ANTES + "\\d{3}[ \\t]*\\.[ \\t]*\\d{3}[ \\t]*\\.[ \\t]*\\d{3}[ \\t]*-[ \\t]*\\d{2}" + SEM_DIGITO_DEPOIS);
    /** As formas sem espaço: 11 dígitos colados, {@code ddddddddd-dd} e as que misturam ponto e hífen. */
    private static final Pattern CPF_SEM_ESPACOS = Pattern.compile(SEM_NUMERO_ANTES + "\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}" + SEM_NUMERO_DEPOIS);
    /** Os 11 dígitos com um separador simples entre os grupos (ponto, espaço, hífen, barra): só marca com a palavra CPF antes. */
    private static final Pattern CPF_COM_SEPARADORES = Pattern.compile(
            SEM_NUMERO_ANTES + "\\d{3}(?:[.\\-/]|[ \\t]+)?\\d{3}(?:[.\\-/]|[ \\t]+)?\\d{3}(?:[.\\-/]|[ \\t]+)?\\d{2}" + SEM_NUMERO_DEPOIS);
    private static final Pattern PALAVRA_CPF = Pattern.compile("(?<![\\p{L}\\d])cpf(?![\\p{L}\\d])", OPCOES_SEM_CAIXA);

    /** Um número com cara de RG: 7 a 10 dígitos com ponto, espaço ou hífen entre eles e, no fim, o X. */
    private static final String NUMERO_DE_RG = "\\d(?:[.\\- \\t]?\\d){6,9}(?:[ \\t-]?[xX](?![\\p{L}\\d]))?";
    /** A palavra RG, os caracteres sem dígito (a quebra de linha entra) e o número. O grupo 1 é o que ficou entre os dois. */
    private static final Pattern RG_COM_PALAVRA = Pattern.compile(
            "(?<![\\p{L}\\d])R(?:\\.[ \\t]?)?G\\.?(?![\\p{L}\\d])(\\D{0," + DISTANCIA_DA_PALAVRA_RG + "})" + SEM_DIGITO_ANTES + "("
                    + NUMERO_DE_RG + ")" + SEM_DIGITO_DEPOIS, OPCOES_SEM_CAIXA);
    private static final Pattern RG_FORMATADO = Pattern.compile(SEM_DIGITO_ANTES + "\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dxX]" + SEM_DIGITO_DEPOIS);

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)++");
    /** O que vem depois do DDD: o 9 do celular (com ou sem espaço), 4 dígitos, um hífen com ou sem espaços (ou nada) e 4 dígitos. */
    private static final String NUMERO_DE_TELEFONE = "(?:9[ \\t]?)?\\d{4}[ \\t]*-?[ \\t]*\\d{4}";
    /**
     * Telefone brasileiro. O DDD tem 2 dígitos e nenhum é zero ({@code [1-9][1-9]}): {@code Lote 10 2026-2027} não é telefone. Formas:
     * com +55 (DDD com ou sem parênteses), com o DDD entre parênteses, com ou sem zero ({@code (51) 3333-4444},
     * {@code (051) 3333-4444}, {@code (51) 9 9999-8888}), com o DDD separado por espaço, tabulação ou hífen ({@code 51 3333-4444},
     * {@code 51-3333-4444}), o celular com DDD colado ({@code 51999998888}) e o celular sem DDD ({@code 99999-8888}). Telefone só
     * com espaços ({@code 51 3333 4444}, {@code 25 1500 2500}) é linha de tabela, e não conta: precisa de parênteses, hífen ou +55
     * (a conferência está em {@link #soComEspacos(String)}). Número solto de 8 dígitos sem DDD também não. Faixa de anos
     * ({@code 11 2026-2027}) bate no formato e é descartada em {@link #contatos(String)}, menos com o DDD entre parênteses.
     */
    private static final Pattern TELEFONE = Pattern.compile(SEM_NUMERO_ANTES + "(?:"
            + "\\+55[ \\t]?\\(?[1-9][1-9]\\)?[ \\t-]?" + NUMERO_DE_TELEFONE
            + "|\\(0?[1-9][1-9]\\)[ \\t]?" + NUMERO_DE_TELEFONE
            + "|[1-9][1-9][ \\t-]" + NUMERO_DE_TELEFONE
            + "|[1-9][1-9]9\\d{8}"
            + "|9\\d{4}-\\d{4}"
            + ")" + SEM_NUMERO_DEPOIS);
    private static final Pattern FAIXA_DE_ANOS = Pattern.compile("(19|20)(\\d{2})(19|20)(\\d{2})");

    /** Tudo o que parece dado pessoal, página a página. Vazia quando o documento está limpo. */
    public List<Suspeita> analisar(List<Pagina> paginas) {
        List<Suspeita> suspeitas = new ArrayList<>();
        for (Pagina pagina : paginas) {
            String texto = normalizar(pagina.texto());
            if (temCpf(texto)) {
                suspeitas.add(new Suspeita(pagina.numero(), "CPF"));
            }
            if (temRg(texto)) {
                suspeitas.add(new Suspeita(pagina.numero(), "RG"));
            }
            if (contatos(texto).size() >= CONTATOS_DE_UMA_LISTA) {
                suspeitas.add(new Suspeita(pagina.numero(), "CONTATOS"));
            }
        }
        return suspeitas;
    }

    /** Se qualquer página levanta suspeita: é o que vira {@code documento.contem_dados_pessoais}. */
    public boolean contemDadosPessoais(List<Pagina> paginas) {
        return !analisar(paginas).isEmpty();
    }

    /**
     * O texto como o detector o lê: NFKC (dígito de largura total vira dígito comum, o espaço sem quebra e os espaços finos viram
     * espaço), sem nenhum caractere de formato invisível (a categoria {@code Cf} inteira: largura zero, hífen condicional, marca de
     * ordem de bytes, marcas e isolamentos de direção, marca de letra árabe, separador de vogal mongol, anotações interlineares) e sem
     * o junta-grafemas (U+034F), com todo traço longo, hífen sem quebra e sinal de menos virando o hífen comum.
     */
    static String normalizar(String texto) {
        String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFKC);
        return normalizado.replaceAll("[\\p{Cf}\\u034F]", "")
                .replaceAll("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]", "-")
                .replaceAll("[\\p{Zs}&&[^ ]]", " ");
    }

    /**
     * Os telefones e e-mails diferentes de um texto, na ordem em que aparecem. O telefone vale pelos dígitos (sem o +55 e sem o zero
     * do DDD), então o mesmo número escrito de dois jeitos conta uma vez; a faixa de anos que bate no formato de telefone é descartada,
     * e a linha de tabela com DDD só com espaços também.
     */
    static Set<String> contatos(String texto) {
        String normalizado = normalizar(texto);
        Set<String> contatos = new LinkedHashSet<>();
        Matcher email = EMAIL.matcher(normalizado);
        while (email.find()) {
            contatos.add(email.group().toLowerCase(Locale.ROOT));
        }
        Matcher telefone = TELEFONE.matcher(normalizado);
        while (telefone.find()) {
            String achado = telefone.group();
            if (soComEspacos(achado)) {
                continue;
            }
            String digitos = achado.replaceAll("\\D", "");
            if (!achado.contains("(") && ehFaixaDeAnos(digitos)) {
                continue;
            }
            if (digitos.startsWith("0")) {
                digitos = digitos.substring(1);   // (051) 3333-4444 é o mesmo número de (51) 3333-4444
            }
            contatos.add("tel:" + (digitos.length() >= 12 && digitos.startsWith("55") ? digitos.substring(2) : digitos));
        }
        return contatos;
    }

    /** O telefone com DDD sem parênteses, sem hífen e sem +55 ({@code 51 3333 4444}): é uma linha de tabela, e não um contato. */
    private static boolean soComEspacos(String telefone) {
        return telefone.matches("[1-9][1-9][ \\t]+(?:9[ \\t]?)?\\d{4}[ \\t]+\\d{4}");
    }

    /** Os 8 últimos dígitos do telefone são dois anos seguidos ({@code 2026-2027}): é uma faixa de anos, e não um número. */
    private static boolean ehFaixaDeAnos(String digitos) {
        Matcher anos = FAIXA_DE_ANOS.matcher(digitos.substring(Math.max(0, digitos.length() - 8)));
        return anos.matches() && digitos.length() - 8 <= 4
                && Integer.parseInt(anos.group(1) + anos.group(2)) <= Integer.parseInt(anos.group(3) + anos.group(4));
    }

    private static boolean temCpf(String texto) {
        if (CPF_FORMATADO.matcher(texto).find()) {
            return true;
        }
        if (marcaPorDigitoOuPalavra(CPF_FORMATADO_COM_ESPACOS, texto) || marcaPorDigitoOuPalavra(CPF_SEM_ESPACOS, texto)) {
            return true;
        }
        Matcher comSeparadores = CPF_COM_SEPARADORES.matcher(texto);
        while (comSeparadores.find()) {
            if (palavraCpfLogoAntes(texto, comSeparadores.start())) {
                return true;
            }
        }
        return false;
    }

    /** Algum achado do padrão tem os dois dígitos verificadores certos ou a palavra CPF logo antes. */
    private static boolean marcaPorDigitoOuPalavra(Pattern padrao, String texto) {
        Matcher achado = padrao.matcher(texto);
        while (achado.find()) {
            if (cpfValido(achado.group()) || palavraCpfLogoAntes(texto, achado.start())) {
                return true;
            }
        }
        return false;
    }

    private static boolean temRg(String texto) {
        if (RG_FORMATADO.matcher(texto).find()) {
            return true;
        }
        Matcher comPalavra = RG_COM_PALAVRA.matcher(texto);
        while (comPalavra.find()) {
            String entre = comPalavra.group(1);
            if (entre.contains("R$") || entre.contains("%")) {
                continue;   // "Unidade RG: R$ 1.500.000" é um valor
            }
            if (ehFaixaDeAnos(comPalavra.group(2).replaceAll("\\D", ""))) {
                continue;   // "Unidade RG 2027-2028" são dois anos
            }
            return true;
        }
        return false;
    }

    /** A palavra CPF (sem diferenciar maiúsculas) nos {@value #DISTANCIA_DA_PALAVRA_CPF} caracteres antes do número. */
    private static boolean palavraCpfLogoAntes(String texto, int inicioDoNumero) {
        return PALAVRA_CPF.matcher(texto.substring(Math.max(0, inicioDoNumero - DISTANCIA_DA_PALAVRA_CPF), inicioDoNumero)).find();
    }

    /** Os dois dígitos verificadores do CPF; sequência de um dígito só (111.111.111-11) não vale. */
    static boolean cpfValido(String cpf) {
        String digitos = cpf.replaceAll("\\D", "");
        if (digitos.length() != 11 || digitos.chars().distinct().count() == 1) {
            return false;
        }
        return digitoVerificador(digitos, 9) == digitos.charAt(9) - '0' && digitoVerificador(digitos, 10) == digitos.charAt(10) - '0';
    }

    private static int digitoVerificador(String digitos, int quantos) {
        int soma = 0;
        for (int i = 0; i < quantos; i++) {
            soma += (digitos.charAt(i) - '0') * (quantos + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }
}
