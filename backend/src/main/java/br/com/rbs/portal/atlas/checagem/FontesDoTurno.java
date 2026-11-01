package br.com.rbs.portal.atlas.checagem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * O que o turno tem de onde tirar uma afirmação: as fontes que as ferramentas devolveram NESTE turno (os trechos de documento
 * {@code [T#]}, e, a partir do M5, os registros {@code [R#]} e as contas {@code [C#]}), a mensagem da pessoa e a data de hoje.
 * [ATLAS] [VALIDACAO] É o estado do {@code Turno} do loop (plano, M4: "os marcadores e a contagem de buscas ficam no Turno"): nasce
 * quando o turno começa e morre com ele. Por isso uma citação de um turno anterior não vale: os marcadores recomeçam em T1, e o
 * histórico que o modelo recebe de turnos antigos vem sem o texto (HistoricoDaConversa). Quem escreve aqui é a ferramenta (que
 * registra o que devolveu) e quem lê é a {@link ChecagemDeFontes} e o filtro do fluxo. Roda na thread do turno.
 */
public final class FontesDoTurno {

    /** No máximo 3 buscas por turno (a regra "tentativa &lt; 3" do quadro); a quarta volta como erro ao modelo. */
    public static final int LIMITE_DE_BUSCAS = 3;

    /** De onde veio a fonte: um trecho de documento, um registro do portal ou uma conta. */
    public enum Tipo {
        TRECHO('T'), REGISTRO('R'), CALCULO('C');

        private final char letra;

        Tipo(char letra) {
            this.letra = letra;
        }

        public char letra() {
            return letra;
        }
    }

    /**
     * Uma fonte citável. {@code texto} é o conteúdo (o trecho do documento, o registro, a conta). O título, a versão, a página e a
     * seção são metadados que o modelo também vê no resultado da ferramenta e que valem como parte da fonte: citar "página 6" ou
     * "seção 3.2" ao lado de {@code [T1]} não é número sem origem.
     */
    public record Fonte(String marcador, Tipo tipo, Long trechoId, Long documentoId, String titulo, Integer versao, Integer pagina,
                        String secao, String texto) {

        /** O título, a versão, a página e a seção da fonte: valem como parte dela ("página 5"), mas não são o texto do documento. */
        public String metadados() {
            StringBuilder todo = new StringBuilder();
            if (titulo != null) {
                todo.append(titulo).append(' ');
            }
            if (versao != null) {
                todo.append("versão ").append(versao).append(' ');
            }
            if (pagina != null) {
                todo.append("página ").append(pagina).append(' ');
            }
            if (secao != null) {
                todo.append(secao).append(' ');
            }
            return todo.toString();
        }

        /** Tudo o que a fonte diz, para conferir os números de uma frase que a cita: os metadados e o texto. */
        public String conteudoCitavel() {
            return metadados() + (texto == null ? "" : texto);
        }
    }

    private final String mensagemDaPessoa;
    private final int anoDaEdicao;
    private final LocalDate hoje;
    private final Map<String, Fonte> fontes = new LinkedHashMap<>();
    private final Map<Long, String> marcadorDoTrecho = new HashMap<>();
    private final Map<Tipo, Integer> contadores = new HashMap<>();
    private final List<String> numerosLivres = new ArrayList<>();
    private int buscas;
    private int buscasComTrecho;

    /**
     * @param mensagemDaPessoa o que a pessoa escreveu neste turno (os números dela valem na resposta: "por 34 kVA", "em 03/11")
     * @param anoDaEdicao      o ano da edição do festival da parceria (vale sem fonte)
     * @param hoje             a data de hoje em Brasília (a do prompt; vale sem fonte)
     */
    public FontesDoTurno(String mensagemDaPessoa, int anoDaEdicao, LocalDate hoje) {
        this.mensagemDaPessoa = mensagemDaPessoa == null ? "" : mensagemDaPessoa;
        this.anoDaEdicao = anoDaEdicao;
        this.hoje = hoje;
    }

    public String mensagemDaPessoa() {
        return mensagemDaPessoa;
    }

    public int anoDaEdicao() {
        return anoDaEdicao;
    }

    public LocalDate hoje() {
        return hoje;
    }

    // ---------------------------------------------------------------------
    // Buscas
    // ---------------------------------------------------------------------

    /** Quantas buscas o turno já tentou (as que falharam também contam). */
    public int buscas() {
        return buscas;
    }

    public boolean buscasEsgotadas() {
        return buscas >= LIMITE_DE_BUSCAS;
    }

    /** Anota uma tentativa de busca. */
    public void contarBusca() {
        buscas++;
    }

    /** Anota o fim de uma busca que chegou a um resultado (com trechos ou sem nenhum). A que falhou nunca chega aqui. */
    public void concluirBusca(boolean vazia) {
        if (!vazia) {
            buscasComTrecho++;
        }
    }

    /**
     * O turno buscou e nenhuma busca trouxe trecho: não há em que se apoiar. Vale para a busca que voltou vazia e também para a que
     * falhou (a ferramenta deu erro): numa resposta sem citação e sem dado da parceria, o modelo escreveu de onde não tinha como saber.
     */
    public boolean nenhumaBuscaTrouxeTrecho() {
        return buscas > 0 && buscasComTrecho == 0;
    }

    // ---------------------------------------------------------------------
    // As fontes
    // ---------------------------------------------------------------------

    /**
     * Registra um trecho de documento e devolve a fonte. O mesmo trecho em duas buscas do turno mantém o marcador; a numeração
     * continua entre as buscas (T1, T2... sem repetir).
     */
    public Fonte registrarTrecho(long trechoId, long documentoId, String titulo, int versao, int pagina, String secao, String texto) {
        String existente = marcadorDoTrecho.get(trechoId);
        if (existente != null) {
            return fontes.get(existente);
        }
        Fonte fonte = new Fonte(novoMarcador(Tipo.TRECHO), Tipo.TRECHO, trechoId, documentoId, titulo, versao, pagina, secao, texto);
        fontes.put(fonte.marcador(), fonte);
        marcadorDoTrecho.put(trechoId, fonte.marcador());
        return fonte;
    }

    /** O próximo marcador do tipo: T1, T2...; R1...; C1... */
    public String novoMarcador(Tipo tipo) {
        int proximo = contadores.merge(tipo, 1, Integer::sum);
        return tipo.letra() + String.valueOf(proximo);
    }

    /** Registra uma fonte que não é trecho (registro do portal ou conta), já com o marcador que {@link #novoMarcador} deu. */
    public void registrar(Fonte fonte) {
        fontes.put(fonte.marcador(), fonte);
    }

    public Optional<Fonte> fonte(String marcador) {
        return Optional.ofNullable(fontes.get(marcador));
    }

    public boolean existe(String marcador) {
        return fontes.containsKey(marcador);
    }

    public Collection<Fonte> fontes() {
        return Collections.unmodifiableCollection(fontes.values());
    }

    // ---------------------------------------------------------------------
    // Desfazer: a ferramenta que registrou e não chegou a gravar
    // ---------------------------------------------------------------------

    /**
     * Uma fotografia das fontes, dos marcadores e dos números livres do turno (não das tentativas de busca, que contam mesmo quando a
     * busca falha).
     */
    public record Marca(Map<String, Fonte> fontes, Map<Long, String> marcadorDoTrecho, Map<Tipo, Integer> contadores,
                        List<String> numerosLivres, int buscasComTrecho) {
    }

    /** Tira a fotografia, antes de a ferramenta rodar. */
    public Marca marcar() {
        return new Marca(new LinkedHashMap<>(fontes), new HashMap<>(marcadorDoTrecho), new HashMap<>(contadores),
                new ArrayList<>(numerosLivres), buscasComTrecho);
    }

    /**
     * Volta ao que a {@link Marca} guardou. A ferramenta registra as fontes na fase que roda dentro da transação do loop, e o
     * rollback não desfaz a memória: se a gravação do resultado falha, o modelo recebe um erro e nunca viu aqueles marcadores, mas
     * eles continuariam valendo para citação. A numeração volta junto: o próximo trecho que o modelo receber será o T1 de verdade.
     */
    public void desfazer(Marca marca) {
        fontes.clear();
        fontes.putAll(marca.fontes());
        marcadorDoTrecho.clear();
        marcadorDoTrecho.putAll(marca.marcadorDoTrecho());
        contadores.clear();
        contadores.putAll(marca.contadores());
        numerosLivres.clear();
        numerosLivres.addAll(marca.numerosLivres());
        buscasComTrecho = marca.buscasComTrecho();
    }

    // ---------------------------------------------------------------------
    // Números que valem na frase toda
    // ---------------------------------------------------------------------

    /** O resultado de uma conta ou de uma contagem de dias deste turno: o número dele vale em qualquer frase da resposta. */
    public void adicionarNumeroLivre(String texto) {
        if (texto != null && !texto.isBlank()) {
            numerosLivres.add(texto);
        }
    }

    public List<String> numerosLivres() {
        return Collections.unmodifiableList(numerosLivres);
    }
}
