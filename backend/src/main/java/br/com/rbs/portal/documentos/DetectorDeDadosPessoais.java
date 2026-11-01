package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Desconfia de dado pessoal num documento antes de a RBS aprovar (decisão 13 do plano do Atlas completo). [ATLAS] Sem rede e sem
 * banco: lê o texto das páginas. As regras:
 * <ul>
 *   <li>CPF marca sempre: com pontuação ({@code 123.456.789-09}) ou sem ({@code 12345678909}, só se os dígitos verificadores
 *       baterem, para um número qualquer de 11 dígitos não travar o documento);</li>
 *   <li>RG marca sempre: a palavra RG seguida de um número, ou o número no formato {@code 12.345.678-9};</li>
 *   <li>telefone e e-mail marcam só em lista: 3 ou mais contatos diferentes na mesma página, para um contato institucional (o
 *       e-mail do plantão, um telefone de apoio) não travar um manual.</li>
 * </ul>
 * Valor em reais ({@code R$ 312,00}), data ({@code 29/01/2027}) e intervalo de anos nunca marcam. A suspeita não tem saída pela
 * tela: um falso positivo se resolve corrigindo o documento e enviando de novo (fica em pendencias.md).
 */
@Component
public class DetectorDeDadosPessoais {

    /** O que foi achado e onde: {@code tipo} é CPF, RG ou CONTATOS (a lista de telefones e e-mails). */
    public record Suspeita(int pagina, String tipo) {
    }

    /** Quantos contatos diferentes numa página fazem uma lista. */
    static final int CONTATOS_DE_UMA_LISTA = 3;

    private static final Pattern CPF_COM_PONTUACAO = Pattern.compile("(?<![\\d.,/-])\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}(?![\\d])");
    private static final Pattern CPF_SEM_PONTUACAO = Pattern.compile("(?<![\\d.,/-])\\d{11}(?![\\d.,/-])");
    private static final Pattern RG_COM_PALAVRA = Pattern.compile("(?<![\\p{L}\\d])RG\\b[^\\d\\n]{0,15}\\d[\\d.\\-xX]{4,}");
    private static final Pattern RG_FORMATADO = Pattern.compile("(?<![\\d.,/-])\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dxX](?![\\d])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /**
     * Telefone brasileiro: com o DDD entre parênteses ou separado por espaço ({@code (51) 3333-4444}, {@code 51 99999-8888}),
     * o celular sem DDD ({@code 99999-8888}) e o número com +55. Número solto de 8 dígitos sem DDD ({@code 2026-2027}) não conta.
     */
    private static final Pattern TELEFONE = Pattern.compile(
            "(?<![\\d.,/-])(?:\\+55\\s?)?(?:\\(\\d{2}\\)\\s?|\\d{2}\\s)9?\\d{4}-?\\d{4}(?!\\d)|(?<![\\d.,/-])9\\d{4}-\\d{4}(?!\\d)");

    /** Tudo o que parece dado pessoal, página a página. Vazia quando o documento está limpo. */
    public List<Suspeita> analisar(List<Pagina> paginas) {
        List<Suspeita> suspeitas = new ArrayList<>();
        for (Pagina pagina : paginas) {
            String texto = pagina.texto();
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

    /** Os telefones e e-mails diferentes de um texto, na ordem em que aparecem. */
    static Set<String> contatos(String texto) {
        Set<String> contatos = new LinkedHashSet<>();
        for (Pattern padrao : List.of(EMAIL, TELEFONE)) {
            Matcher achado = padrao.matcher(texto);
            while (achado.find()) {
                contatos.add(achado.group().replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return contatos;
    }

    private static boolean temCpf(String texto) {
        if (CPF_COM_PONTUACAO.matcher(texto).find()) {
            return true;
        }
        Matcher sem = CPF_SEM_PONTUACAO.matcher(texto);
        while (sem.find()) {
            if (cpfValido(sem.group())) {
                return true;
            }
        }
        return false;
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
