package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.DetectorDeDadosPessoais.Suspeita;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * As páginas de um documento que ficam fora do Atlas (R2.1) e a suspeita de dado pessoal guardada junto. [ATLAS] Só números de página e
 * tipos de achado: nunca o texto da página nem o valor achado. As conversões entre a lista do Java e o que o PostgreSQL grava ficam
 * aqui, num lugar só.
 */
final class PaginasForaDoAtlas {

    /** O máximo de páginas fora do Atlas num documento (o mesmo número do gatilho rn_documento_paginas_ignoradas). */
    static final int LIMITE = 20;

    static final String FRASE_DO_FORMATO = "Informe os números das páginas separados por vírgula, como 4 ou 4,7.";

    private PaginasForaDoAtlas() {
    }

    /** Resultado da leitura do texto de entrada: a lista em ordem crescente, ou a frase do erro de formato. */
    record Leitura(List<Integer> paginas, String erro) {
    }

    /** Lê {@code "4"} ou {@code "4, 7"}: inteiros positivos, sem repetição, até {@value #LIMITE}. Texto vazio é a lista vazia. */
    static Leitura ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return new Leitura(List.of(), null);
        }
        TreeSet<Integer> paginas = new TreeSet<>();
        int lidas = 0;
        for (String parte : texto.split(",", -1)) {
            String numero = parte.strip();
            if (!numero.matches("\\d{1,4}") || Integer.parseInt(numero) < 1) {
                return new Leitura(List.of(), FRASE_DO_FORMATO);
            }
            lidas++;
            paginas.add(Integer.parseInt(numero));
        }
        return conferir(new ArrayList<>(paginas), lidas);
    }

    /** O mesmo para a lista já em números (o corpo JSON da rota de ajuste). */
    static Leitura ler(List<Integer> informadas) {
        if (informadas == null) {
            return new Leitura(List.of(), "Informe as páginas fora do Atlas (uma lista vazia limpa a marcação).");
        }
        if (informadas.stream().anyMatch(n -> n == null || n < 1)) {
            return new Leitura(List.of(), "As páginas são números a partir de 1.");
        }
        return conferir(new ArrayList<>(new TreeSet<>(informadas)), informadas.size());
    }

    private static Leitura conferir(List<Integer> ordenadas, int lidas) {
        if (ordenadas.size() != lidas) {
            return new Leitura(List.of(), "Há página repetida na lista.");
        }
        if (ordenadas.size() > LIMITE) {
            return new Leitura(List.of(), "No máximo " + LIMITE + " páginas ficam fora do Atlas.");
        }
        return new Leitura(ordenadas, null);
    }

    /** A frase de quando a lista não cabe no documento (página além do fim, ou todas as páginas fora). Nulo quando cabe. */
    static String foraDoIntervalo(List<Integer> paginas, int total) {
        if (paginas.isEmpty()) {
            return null;
        }
        if (paginas.get(paginas.size() - 1) > total) {
            return "O documento tem " + total + (total == 1 ? " página" : " páginas") + ": as páginas fora do Atlas vão de 1 a " + total + ".";
        }
        if (paginas.size() >= total) {
            return "Ao menos uma página precisa ficar no Atlas.";
        }
        return null;
    }

    /** O literal de {@code smallint[]} do PostgreSQL ({@code {4,7}}). */
    static String paraArray(List<Integer> paginas) {
        return paginas.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    /** O JSON de {@code documento.suspeitas}: {@code [{"pagina":4,"tipo":"CONTATOS"}]}. O tipo é de uma lista fixa; não há valor achado. */
    static String suspeitasParaJson(List<Suspeita> suspeitas) {
        return suspeitas.stream().map(s -> "{\"pagina\":" + s.pagina() + ",\"tipo\":\"" + s.tipo() + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** Lê a coluna {@code paginas_ignoradas} como o repositório a entrega: {@code "4,7"}, ou nulo/vazio. */
    static List<Integer> daColuna(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Integer> paginas = new ArrayList<>();
        for (String parte : csv.split(",")) {
            paginas.add(Integer.valueOf(parte.strip()));
        }
        return List.copyOf(paginas);
    }

    /** Lê a coluna {@code suspeitas} como o repositório a entrega: {@code "4:CONTATOS,4:CPF"}, ou nulo/vazio. */
    static List<Suspeita> suspeitasDaColuna(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Suspeita> suspeitas = new ArrayList<>();
        for (String parte : csv.split(",")) {
            String[] dois = parte.split(":", 2);
            suspeitas.add(new Suspeita(Integer.parseInt(dois[0]), dois[1]));
        }
        return List.copyOf(suspeitas);
    }
}
