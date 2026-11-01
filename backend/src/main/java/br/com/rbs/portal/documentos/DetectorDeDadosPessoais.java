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
 *   <li>CPF marca sempre com a pontuação inteira ({@code 123.456.789-09}). As outras formas ({@code 12345678909},
 *       {@code 123456789-09}, grupos separados por espaço) marcam se os dígitos verificadores baterem <b>ou</b> se a palavra CPF
 *       vier logo antes (até {@value #DISTANCIA_DA_PALAVRA_CPF} caracteres): um número qualquer de 11 dígitos, sem a palavra,
 *       é código e não trava o documento;</li>
 *   <li>RG marca sempre: a palavra RG (ou R.G.) seguida de um número com cara de RG (7 a 10 dígitos, pontos opcionais, dígito
 *       depois de hífen, X aceito), ou o número no formato {@code 12.345.678-9}. {@code RG 2027} e {@code Unidade RG 2027-2028}
 *       não são RG;</li>
 *   <li>telefone e e-mail marcam só em lista: 3 ou mais contatos diferentes na mesma página, para um contato institucional (o
 *       e-mail do plantão, um telefone de apoio) não travar um manual.</li>
 * </ul>
 * O texto da página é normalizado antes de procurar (NFKC, sem caracteres invisíveis, traços longos viram hífen, espaços especiais
 * viram espaço): sem isso, um dígito de largura total ou um espaço de largura zero escondia o CPF. Um número só conta se não estiver
 * dentro de outro: dígito vizinho, ou dígito seguido de separador ({@code .,/-}), recusa; vírgula, ponto final e hífen depois de
 * letra não. Valor em reais ({@code R$ 312,00}), data ({@code 29/01/2027}) e faixa de anos ({@code 2026-2027}) nunca marcam. A
 * suspeita não tem saída pela tela: um falso positivo se resolve corrigindo o documento e enviando de novo (fica em pendencias.md).
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

    /** Não começa no meio de outro número: sem dígito antes, e sem dígito seguido de separador. */
    private static final String SEM_NUMERO_ANTES = "(?<!\\d)(?<!\\d[.,/-])";
    /** Não termina no meio de outro número: sem dígito depois, e sem separador seguido de dígito. */
    private static final String SEM_NUMERO_DEPOIS = "(?!\\d)(?![.,/-]\\d)";
    private static final int OPCOES_SEM_CAIXA = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CPF_FORMATADO = Pattern.compile(SEM_NUMERO_ANTES + "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}" + SEM_NUMERO_DEPOIS);
    /** As outras formas: 11 dígitos colados, {@code ddddddddd-dd}, os grupos separados por ponto ou espaços. */
    private static final Pattern CPF_OUTRAS_FORMAS = Pattern.compile(
            SEM_NUMERO_ANTES + "\\d{3}(?:\\.|[ \\t]+)?\\d{3}(?:\\.|[ \\t]+)?\\d{3}(?:-|[ \\t]+)?\\d{2}" + SEM_NUMERO_DEPOIS);
    private static final Pattern PALAVRA_CPF = Pattern.compile("(?<![\\p{L}\\d])cpf(?![\\p{L}\\d])", OPCOES_SEM_CAIXA);

    /** Um número com cara de RG: 7 a 10 dígitos (os pontos são opcionais) e, depois de um hífen, o dígito ou o X. */
    private static final String NUMERO_DE_RG = "\\d(?:\\.?\\d){6,9}(?:-[\\dxX])?";
    private static final Pattern RG_COM_PALAVRA = Pattern.compile(
            "(?<![\\p{L}\\d])R\\.?G\\.?(?![\\p{L}\\d])[^\\d\\n]{0,15}" + SEM_NUMERO_ANTES + "(" + NUMERO_DE_RG + ")" + SEM_NUMERO_DEPOIS,
            OPCOES_SEM_CAIXA);
    private static final Pattern RG_FORMATADO = Pattern.compile(SEM_NUMERO_ANTES + "\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dxX]" + SEM_NUMERO_DEPOIS);

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /**
     * Telefone brasileiro. O DDD tem 2 dígitos e nenhum é zero ({@code [1-9][1-9]}): {@code Lote 10 2026-2027} não é telefone. Formas:
     * com +55 (DDD com ou sem parênteses, separadores opcionais), com o DDD entre parênteses ({@code (51) 3333-4444}), com o DDD
     * separado por espaço ou hífen ({@code 51 3333 4444}, {@code 51-3333-4444}) e o celular sem DDD ({@code 99999-8888}). Número solto
     * de 8 dígitos sem DDD não conta. Faixa de anos ({@code 11 2026-2027}) bate no formato e é descartada em {@link #contatos(String)}.
     */
    private static final Pattern TELEFONE = Pattern.compile(SEM_NUMERO_ANTES + "(?:"
            + "\\+55[ ]?\\(?[1-9][1-9]\\)?[ -]?9?\\d{4}[ -]?\\d{4}"
            + "|\\([1-9][1-9]\\)[ ]?9?\\d{4}[ -]?\\d{4}"
            + "|[1-9][1-9][ -]9?\\d{4}[ -]?\\d{4}"
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
            if (RG_COM_PALAVRA.matcher(texto).find() || RG_FORMATADO.matcher(texto).find()) {
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
     * espaço), sem os caracteres invisíveis (largura zero, hífen condicional, marca de ordem de bytes, marcas de direção), com todo
     * traço longo, hífen sem quebra e sinal de menos virando o hífen comum.
     */
    static String normalizar(String texto) {
        String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFKC);
        return normalizado.replaceAll("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]", "")
                .replaceAll("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]", "-")
                .replaceAll("[\\p{Zs}&&[^ ]]", " ");
    }

    /**
     * Os telefones e e-mails diferentes de um texto, na ordem em que aparecem. O telefone vale pelos dígitos (sem o +55), então o
     * mesmo número escrito de dois jeitos conta uma vez; a faixa de anos que bate no formato de telefone é descartada.
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
            String digitos = telefone.group().replaceAll("\\D", "");
            if (ehFaixaDeAnos(digitos)) {
                continue;
            }
            contatos.add("tel:" + (digitos.length() >= 12 && digitos.startsWith("55") ? digitos.substring(2) : digitos));
        }
        return contatos;
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
        Matcher outras = CPF_OUTRAS_FORMAS.matcher(texto);
        while (outras.find()) {
            if (cpfValido(outras.group()) || palavraCpfLogoAntes(texto, outras.start())) {
                return true;
            }
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
