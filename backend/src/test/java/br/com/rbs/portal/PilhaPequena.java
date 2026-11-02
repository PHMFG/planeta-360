package br.com.rbs.portal;

/**
 * Roda um trecho numa thread de pilha pequena (256 KB), para os testes de expressão regular acharem no Mac o que só aparece no Linux. O
 * {@code java.util.regex} repete um grupo que não é determinístico (como {@code (?:\.[a-z]+)*}) com recursão, uma volta de pilha por
 * repetição: a pilha padrão é de 1 MB no Linux x64 e de 2 MB no Mac arm64, e um texto com milhares de repetições estoura a primeira e
 * passa na segunda. Com 256 KB estoura nos dois. Quem estoura aqui estoura num servidor Linux com um texto um pouco maior. Só existe em
 * {@code src/test}. Não faz parte da API.
 */
public final class PilhaPequena {

    public static final int BYTES = 256 * 1024;

    private PilhaPequena() {
    }

    /** Roda o corpo na pilha pequena. Um {@link StackOverflowError} (ou qualquer erro) do corpo volta ao teste como falha. */
    public static void rodar(Runnable corpo) {
        Throwable falha = executar(corpo);
        if (falha instanceof StackOverflowError estouro) {
            throw new AssertionError("estourou a pilha de " + (BYTES / 1024) + " KB", estouro);
        }
        if (falha instanceof Error erro) {
            throw erro;
        }
        if (falha instanceof RuntimeException erro) {
            throw erro;
        }
        if (falha != null) {
            throw new AssertionError(falha);
        }
    }

    /** O corpo estoura a pilha pequena? (Outra falha do corpo não conta: volta como {@link AssertionError}.) */
    public static boolean estoura(Runnable corpo) {
        Throwable falha = executar(corpo);
        if (falha instanceof StackOverflowError) {
            return true;
        }
        if (falha != null) {
            throw new AssertionError(falha);
        }
        return false;
    }

    private static Throwable executar(Runnable corpo) {
        Throwable[] falha = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                corpo.run();
            } catch (Throwable erro) {
                falha[0] = erro;
            }
        }, "pilha-pequena", BYTES);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(60_000);
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrompido esperando a thread de pilha pequena", interrompido);
        }
        if (thread.isAlive()) {
            throw new AssertionError("a thread de pilha pequena não terminou em 60 s");
        }
        return falha[0];
    }
}
