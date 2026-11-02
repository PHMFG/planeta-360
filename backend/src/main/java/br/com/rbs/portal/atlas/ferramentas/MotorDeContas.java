package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.ferramentas.funcoes.FuncoesDeConta;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.commons.jexl3.introspection.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * O motor das contas do {@code calcular}: o JEXL 3.7.1 com todas as travas da seção 6.4 do plano do MVP (plano do Atlas completo, M5).
 * [ATLAS] [VALIDACAO] Quem escreve o script é o modelo, e avaliar texto dele sem travas é uma vulnerabilidade conhecida. As travas,
 * cada uma suficiente por si para uma classe de ataque (e todas provadas sem a lista de tokens do {@link ScriptDeConta}):
 * <ul>
 *   <li><b>Recursos</b> ({@link JexlFeatures}): sem {@code new}, {@code pragma}, laço, lambda, chamada de método, efeito (global ou
 *       local), anotação, literal estruturado ({@code [a]}, {@code {a:b}}) nem variável local. Sobra a expressão aritmética.</li>
 *   <li><b>Permissões</b>: só o {@link JexlPermissions#SECURE} do 3.7 mais o pacote das funções
 *       ({@link #PACOTE_DAS_FUNCOES}), que só tem a {@link FuncoesDeConta}. Sem a composição, {@code pct} não resolve (conferido em
 *       30/10/2026).</li>
 *   <li><b>Sandbox</b> que nega tudo, menos a classe das funções, registrada no <b>namespace nulo</b> (assim {@code pct(a, b)} se
 *       escreve sem prefixo). Nenhum outro namespace existe.</li>
 *   <li><b>Prazo</b>: o JEXL não tem timeout pronto, e {@code cancellable(true)} só reage à interrupção da thread. A compilação e a
 *       conta rodam numa thread à parte, esperadas por {@value #PRAZO_EM_MS} ms ({@code Future.get}), e o estouro faz
 *       {@code cancel(true)}.</li>
 *   <li><b>Números</b> em {@link BigDecimal} com {@link MathContext#DECIMAL128}; o arredondamento é só na saída, de quem chama. A
 *       divisão é nossa: divisor zero levanta {@link ArithmeticException}, que aqui vira conta <b>indisponível</b>, nunca zero.</li>
 * </ul>
 * O motor não confere nem o script nem as variáveis (isso é do {@link ScriptDeConta} e do {@link Calcular}): recebe um script e
 * números e devolve um número, ou {@link DadosInvalidosException} se o JEXL recusa o script.
 */
@Component
public class MotorDeContas {

    /** O único pacote que as permissões do JEXL acrescentam ao {@code SECURE}: o das funções {@code pct}, {@code min} e {@code max}. */
    public static final String PACOTE_DAS_FUNCOES = FuncoesDeConta.class.getPackageName();
    /** Quanto a conta pode levar (compilar e rodar). */
    public static final long PRAZO_EM_MS = 200;

    /** O resultado de uma conta: o número, ou nenhum e o motivo (hoje, só divisão por zero). */
    public record Resultado(BigDecimal valor, String motivo) {

        public boolean disponivel() {
            return valor != null;
        }
    }

    /** A divisão do JEXL devolve zero em alguns casos de divisor zero; aqui o divisor zero é erro, e a conta sai indisponível. */
    private static final class Aritmetica extends JexlArithmetic {

        Aritmetica() {
            super(true, MathContext.DECIMAL128, Integer.MIN_VALUE);
        }

        @Override
        public Object divide(Object esquerda, Object direita) {
            BigDecimal divisor = toBigDecimal(direita);
            if (divisor.signum() == 0) {
                throw new ArithmeticException("divisão por zero");
            }
            return toBigDecimal(esquerda).divide(divisor, MathContext.DECIMAL128);
        }
    }

    private static final AtomicInteger NUMERO_DA_THREAD = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(tarefa -> {
        Thread thread = new Thread(tarefa, "atlas-conta-" + NUMERO_DA_THREAD.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final JexlEngine motor;

    public MotorDeContas() {
        JexlFeatures recursos = new JexlFeatures().newInstance(false).pragma(false).loops(false).lambda(false).methodCall(false)
                .sideEffectGlobal(false).sideEffect(false).annotation(false).structuredLiteral(false).localVar(false);
        JexlSandbox sandbox = new JexlSandbox(false);   // nega tudo...
        sandbox.allow(FuncoesDeConta.class.getName());   // ...menos a classe das funções
        Map<String, Object> namespaces = new HashMap<>();
        namespaces.put(null, new FuncoesDeConta());   // namespace nulo: pct(a, b), sem prefixo
        this.motor = new JexlBuilder().features(recursos)
                .permissions(JexlPermissions.SECURE.compose(PACOTE_DAS_FUNCOES + ".*"))
                .sandbox(sandbox).namespaces(namespaces).arithmetic(new Aritmetica())
                .cancellable(true).strict(true).silent(false).create();
    }

    /**
     * Avalia o script com as variáveis. Divisor zero (na divisão ou no {@code pct}) devolve o resultado indisponível. O que o JEXL
     * recusa (sintaxe, recurso desligado, variável que não existe, acesso proibido, função que não é das três) e a conta que passa
     * do prazo sobem como {@link DadosInvalidosException}, com uma frase nossa (nada do texto interno do JEXL).
     */
    public Resultado avaliar(String script, Map<String, BigDecimal> variaveis) {
        Object bruto;
        try {
            bruto = comPrazo(PRAZO_EM_MS, TimeUnit.MILLISECONDS, () -> {
                JexlScript compilado = motor.createScript(script);
                return compilado.execute(new MapContext(new HashMap<>(variaveis)));
            });
        } catch (JexlException erro) {
            if (divisaoPorZero(erro)) {
                return new Resultado(null, "Divisão por zero.");
            }
            throw recusada(frase(erro));
        } catch (ArithmeticException divisaoPorZero) {
            return new Resultado(null, "Divisão por zero.");
        }
        return new Resultado(comoNumero(bruto), null);
    }

    /**
     * Roda a tarefa numa thread à parte e espera pelo prazo. Passou dele: interrompe a thread e recusa a conta. Erro da JVM dentro
     * da tarefa (uma pilha estourada por parênteses demais) também recusa. O que a tarefa levanta de comum sobe como está.
     */
    static <T> T comPrazo(long prazo, TimeUnit unidade, Callable<T> tarefa) {
        Future<T> futuro = EXECUTOR.submit(tarefa);
        try {
            return futuro.get(prazo, unidade);
        } catch (TimeoutException estouro) {
            futuro.cancel(true);
            throw recusada("A conta demorou demais e foi interrompida. Escreva uma conta mais simples.");
        } catch (InterruptedException interrompido) {
            futuro.cancel(true);
            Thread.currentThread().interrupt();
            throw recusada("A conta foi interrompida.");
        } catch (ExecutionException falha) {
            if (falha.getCause() instanceof RuntimeException comum) {
                throw comum;
            }
            if (falha.getCause() instanceof Error) {
                throw recusada("A conta é complicada demais. Escreva uma conta mais simples.");
            }
            throw new IllegalStateException(falha.getCause());
        }
    }

    private static boolean divisaoPorZero(Throwable erro) {
        for (Throwable causa = erro; causa != null; causa = causa.getCause() == causa ? null : causa.getCause()) {
            if (causa instanceof ArithmeticException) {
                return true;
            }
        }
        return false;
    }

    /** A frase que o modelo lê: nossa, sem o texto do JEXL (que traz o nome de classe e a posição no script). */
    private static String frase(JexlException erro) {
        if (erro instanceof JexlException.Parsing || erro instanceof JexlException.Tokenization
                || erro instanceof JexlException.Ambiguous) {
            return "O script não é uma conta válida: use só variáveis, números, + - * / ( ) e as funções pct, min e max.";
        }
        if (erro instanceof JexlException.Feature) {
            return "O script usa um recurso que não é permitido: só vale aritmética sobre as variáveis, com pct, min e max.";
        }
        if (erro instanceof JexlException.Variable) {
            return "O script usa uma variável que não foi declarada em variaveis.";
        }
        if (erro instanceof JexlException.Property || erro instanceof JexlException.Method) {
            return "O script tenta acessar algo que não é permitido: só vale aritmética sobre as variáveis, com pct, min e max.";
        }
        return "O script não pôde ser calculado: use só variáveis, números, + - * / ( ) e as funções pct, min e max.";
    }

    private static DadosInvalidosException recusada(String frase) {
        return new DadosInvalidosException(frase, Map.of("script", frase));
    }

    private static BigDecimal comoNumero(Object bruto) {
        if (bruto instanceof BigDecimal decimal) {
            return decimal;
        }
        if (bruto instanceof BigInteger inteiro) {
            return new BigDecimal(inteiro);
        }
        if (bruto instanceof Integer || bruto instanceof Long || bruto instanceof Short || bruto instanceof Byte) {
            return BigDecimal.valueOf(((Number) bruto).longValue());
        }
        throw recusada("A conta não deu um número. Escreva uma expressão aritmética.");
    }
}
