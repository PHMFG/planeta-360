package br.com.rbs.portal.atlas.relatorio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A especificação de um relatório do Atlas: o modelo-base (seções e blocos, ainda com referências no lugar dos números) mais o
 * texto de cada bloco redigível. [ATLAS] É o JSON que fica em {@code atlas_relatorio.especificacao}. O catálogo de blocos é
 * fechado (capa, titulo, paragrafo, kpi, kpi_grade, tabela, galeria_comprovacoes, campo_informado, aviso_indisponivel, fontes):
 * um tipo que não está aqui é erro ao ler. Nada de HTML vindo do modelo, e nenhum número escrito à mão: onde o relatório precisa
 * de um número, o bloco guarda uma referência ({@code {"ref": "cons:aplicacoes.previstas"}}) e o servidor a resolve.
 * Os records são imutáveis; quem muda o texto de um bloco recebe uma especificação nova ({@link #comTexto}).
 *
 * @param modelo   o código do modelo (ATIVACAO_EVENTO)
 * @param versao   a versão do arquivo do modelo-base de onde esta especificação nasceu
 * @param aba      a aba do portal a que o modelo pertence; com ela fechada para a marca o relatório some
 * @param titulo   o título do relatório
 * @param calculos as fórmulas fixas, por nome (a referência {@code calc:nome})
 * @param lacunas  as chaves do catálogo que o relatório precisa (obrigatórias) ou aproveita (opcionais)
 */
public record Especificacao(String modelo, int versao, String aba, String titulo, List<Secao> secoes,
                            Map<String, Calculo> calculos, Lacunas lacunas) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern NOME_DE_CALCULO = Pattern.compile("[a-z_]{1,60}");

    public Especificacao {
        secoes = secoes == null ? List.of() : List.copyOf(secoes);
        calculos = calculos == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(calculos));
        lacunas = lacunas == null ? new Lacunas(List.of(), List.of()) : lacunas;
    }

    // ---------------------------------------------------------------------
    // Os pedaços
    // ---------------------------------------------------------------------

    public record Secao(String id, String titulo, List<Bloco> blocos) {

        public Secao {
            blocos = blocos == null ? List.of() : List.copyOf(blocos);
        }
    }

    /** Uma referência a um valor que o servidor resolve: {@code {"ref": "dado:ativacao.visitantes_total"}}. */
    public record Referencia(String ref) {
    }

    /** Um item de {@code kpi_grade}: o rótulo e a referência do número. */
    public record ItemKpi(String rotulo, Referencia valor) {
    }

    /** Uma fórmula fixa: o rótulo e a unidade do resultado, o texto da fórmula e a referência de cada variável. */
    public record Calculo(String rotulo, String unidade, String script, Map<String, Referencia> variaveis) {

        public Calculo {
            variaveis = variaveis == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variaveis));
        }
    }

    public record Lacunas(List<String> obrigatorias, List<String> opcionais) {

        public Lacunas {
            obrigatorias = obrigatorias == null ? List.of() : List.copyOf(obrigatorias);
            opcionais = opcionais == null ? List.of() : List.copyOf(opcionais);
        }
    }

    /** O catálogo fechado de blocos. O tipo vai na propriedade {@code tipo}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "tipo")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Capa.class, name = "capa"),
            @JsonSubTypes.Type(value = Titulo.class, name = "titulo"),
            @JsonSubTypes.Type(value = Paragrafo.class, name = "paragrafo"),
            @JsonSubTypes.Type(value = Kpi.class, name = "kpi"),
            @JsonSubTypes.Type(value = KpiGrade.class, name = "kpi_grade"),
            @JsonSubTypes.Type(value = Tabela.class, name = "tabela"),
            @JsonSubTypes.Type(value = GaleriaComprovacoes.class, name = "galeria_comprovacoes"),
            @JsonSubTypes.Type(value = CampoInformado.class, name = "campo_informado"),
            @JsonSubTypes.Type(value = AvisoIndisponivel.class, name = "aviso_indisponivel"),
            @JsonSubTypes.Type(value = Fontes.class, name = "fontes")})
    public sealed interface Bloco permits Capa, Titulo, Paragrafo, Kpi, KpiGrade, Tabela, GaleriaComprovacoes, CampoInformado,
            AvisoIndisponivel, Fontes {

        String id();
    }

    public record Capa(String id, String titulo, Referencia subtitulo, Referencia edicao) implements Bloco {
    }

    public record Titulo(String id, String texto) implements Bloco {
    }

    /**
     * Um parágrafo. Fixo ({@code redigivel} falso, com {@code texto}) ou redigível: aí o {@code texto} começa vazio, o Atlas (ou
     * o plano B, com a {@code fraseModelo}) o preenche e {@code redigidoPor} diz quem foi: ATLAS ou MODELO. O texto só leva
     * marcadores {@code {{ref}}}; os números entram na hora de resolver.
     */
    public record Paragrafo(String id, boolean redigivel,
                            @JsonProperty("frase_modelo") String fraseModelo,
                            String texto,
                            @JsonProperty("redigido_por") String redigidoPor) implements Bloco {
    }

    public record Kpi(String id, String rotulo, Referencia valor) implements Bloco {
    }

    public record KpiGrade(String id, List<ItemKpi> itens) implements Bloco {

        public KpiGrade {
            itens = itens == null ? List.of() : List.copyOf(itens);
        }
    }

    /** A tabela do plano de aplicações. Hoje a única fonte é {@code lista:aplicacoes}. */
    public record Tabela(String id, Referencia fonte) implements Bloco {
    }

    /** A galeria de fotos de comprovação. Hoje a única fonte é {@code lista:comprovacoes}. */
    public record GaleriaComprovacoes(String id, Referencia fonte) implements Bloco {
    }

    /** Um dado informado com o rótulo e a origem à vista. */
    public record CampoInformado(String id, String rotulo, Referencia valor) implements Bloco {
    }

    public record AvisoIndisponivel(String id, String motivo) implements Bloco {
    }

    /** A seção "Fontes e cálculos": o servidor a monta com a origem de cada número usado no relatório. */
    public record Fontes(String id) implements Bloco {
    }

    // ---------------------------------------------------------------------
    // Consultas
    // ---------------------------------------------------------------------

    /** Todos os blocos do relatório, na ordem em que aparecem. */
    public List<Bloco> blocos() {
        List<Bloco> todos = new ArrayList<>();
        secoes.forEach(secao -> todos.addAll(secao.blocos()));
        return todos;
    }

    /** Os parágrafos que o Atlas (ou o plano B) escreve. */
    public List<Paragrafo> redigiveis() {
        return blocos().stream().filter(b -> b instanceof Paragrafo p && p.redigivel()).map(b -> (Paragrafo) b).toList();
    }

    public Optional<Bloco> bloco(String id) {
        return blocos().stream().filter(b -> b.id().equals(id)).findFirst();
    }

    /** As chaves do catálogo de que o relatório precisa, obrigatórias primeiro. */
    public List<String> chavesDeLacuna() {
        List<String> chaves = new ArrayList<>(lacunas.obrigatorias());
        chaves.addAll(lacunas.opcionais());
        return chaves;
    }

    // ---------------------------------------------------------------------
    // Mudança (devolve uma especificação nova)
    // ---------------------------------------------------------------------

    /** O parágrafo com o texto e o autor dados. O bloco precisa existir e ser redigível. */
    public Especificacao comTexto(String blocoId, String texto, String redigidoPor) {
        boolean achou = false;
        List<Secao> novas = new ArrayList<>();
        for (Secao secao : secoes) {
            List<Bloco> blocos = new ArrayList<>();
            for (Bloco bloco : secao.blocos()) {
                if (bloco instanceof Paragrafo p && p.id().equals(blocoId) && p.redigivel()) {
                    blocos.add(new Paragrafo(p.id(), true, p.fraseModelo(), texto, redigidoPor));
                    achou = true;
                } else {
                    blocos.add(bloco);
                }
            }
            novas.add(new Secao(secao.id(), secao.titulo(), blocos));
        }
        if (!achou) {
            throw new IllegalArgumentException("Não existe o bloco redigível " + blocoId + ".");
        }
        return new Especificacao(modelo, versao, aba, titulo, novas, calculos, lacunas);
    }

    // ---------------------------------------------------------------------
    // Conferência do formato (o que o carregamento do modelo-base e a leitura do banco exigem)
    // ---------------------------------------------------------------------

    /**
     * Confere o formato e as referências: nada vazio onde precisa de valor, ids únicos, toda referência na gramática, cada
     * {@code calc:} apontando para uma fórmula que existe, toda fórmula uma das fixas com as variáveis definidas. Lança
     * {@link IllegalArgumentException} com a primeira falha. A existência das chaves de {@code dado:} no catálogo é conferida na
     * resolução, que tem o banco.
     */
    public void validar() {
        exigir(modelo != null && !modelo.isBlank(), "O modelo não tem código.");
        exigir(aba != null && !aba.isBlank(), "O modelo " + modelo + " não diz a que aba pertence.");
        exigir(titulo != null && !titulo.isBlank(), "O modelo " + modelo + " não tem título.");
        exigir(!secoes.isEmpty(), "O modelo " + modelo + " não tem seções.");

        Set<String> idsDeSecao = new LinkedHashSet<>();
        Set<String> idsDeBloco = new LinkedHashSet<>();
        for (Secao secao : secoes) {
            exigir(secao.id() != null && ID.matcher(secao.id()).matches(), "Id de seção inválido: " + secao.id());
            exigir(idsDeSecao.add(secao.id()), "Seção repetida: " + secao.id());
            for (Bloco bloco : secao.blocos()) {
                exigir(bloco.id() != null && ID.matcher(bloco.id()).matches(), "Id de bloco inválido: " + bloco.id());
                exigir(idsDeBloco.add(bloco.id()), "Bloco repetido: " + bloco.id());
                validarBloco(bloco);
            }
        }
        calculos.forEach(this::validarCalculo);
        for (String chave : chavesDeLacuna()) {
            exigir(Referencias.valida("dado:" + chave), "Chave de lacuna inválida: " + chave);
        }
    }

    private void validarBloco(Bloco bloco) {
        switch (bloco) {
            case Capa c -> {
                exigir(c.titulo() != null && !c.titulo().isBlank(), "A capa " + c.id() + " não tem título.");
                validarReferencia(c.subtitulo(), c.id());
                validarReferencia(c.edicao(), c.id());
            }
            case Titulo t -> exigir(t.texto() != null && !t.texto().isBlank(), "O título " + t.id() + " está vazio.");
            case Paragrafo p -> {
                if (p.redigivel()) {
                    exigir(p.fraseModelo() != null && !p.fraseModelo().isBlank(), "O parágrafo " + p.id() + " não tem frase-modelo.");
                } else {
                    exigir(p.texto() != null && !p.texto().isBlank(), "O parágrafo fixo " + p.id() + " está vazio.");
                }
                validarMarcadores(p.fraseModelo(), p.id());
                validarMarcadores(p.texto(), p.id());
                exigir(p.redigidoPor() == null || p.redigidoPor().equals("ATLAS") || p.redigidoPor().equals("MODELO"),
                        "O parágrafo " + p.id() + " foi redigido por alguém que não existe: " + p.redigidoPor());
            }
            case Kpi k -> {
                exigir(k.rotulo() != null && !k.rotulo().isBlank(), "O KPI " + k.id() + " não tem rótulo.");
                validarValor(k.valor(), k.id());
            }
            case KpiGrade g -> {
                exigir(!g.itens().isEmpty(), "A grade de KPIs " + g.id() + " está vazia.");
                g.itens().forEach(item -> {
                    exigir(item.rotulo() != null && !item.rotulo().isBlank(), "Um KPI de " + g.id() + " não tem rótulo.");
                    validarValor(item.valor(), g.id());
                });
            }
            case Tabela t -> exigirFonte(t.fonte(), "lista:aplicacoes", t.id());
            case GaleriaComprovacoes g -> exigirFonte(g.fonte(), "lista:comprovacoes", g.id());
            case CampoInformado c -> {
                exigir(c.rotulo() != null && !c.rotulo().isBlank(), "O campo " + c.id() + " não tem rótulo.");
                exigir(c.valor() != null && Referencias.valida(c.valor().ref()) && "dado".equals(Referencias.prefixo(c.valor().ref())),
                        "O campo informado " + c.id() + " precisa de uma referência dado:.");
            }
            case AvisoIndisponivel a -> exigir(a.motivo() != null && !a.motivo().isBlank(), "O aviso " + a.id() + " não tem motivo.");
            case Fontes f -> {
            }
        }
    }

    private void validarReferencia(Referencia referencia, String bloco) {
        if (referencia != null) {
            validarValor(referencia, bloco);
        }
    }

    /** Uma referência de valor (e não de lista) na gramática, e, se for {@code calc:}, de uma fórmula que existe. */
    private void validarValor(Referencia referencia, String bloco) {
        exigir(referencia != null && Referencias.valida(referencia.ref()), "Referência inválida em " + bloco + ".");
        exigir(!"lista".equals(Referencias.prefixo(referencia.ref())), "Uma lista não vale como valor em " + bloco + ": " + referencia.ref());
        exigirCalculoExistente(referencia.ref(), bloco);
    }

    private void validarMarcadores(String texto, String bloco) {
        for (String ref : Referencias.marcadores(texto)) {
            exigir(Referencias.valida(ref), "Marcador inválido em " + bloco + ": {{" + ref + "}}");
            exigir(!"lista".equals(Referencias.prefixo(ref)), "Uma lista não vale como marcador em " + bloco + ": " + ref);
            exigirCalculoExistente(ref, bloco);
        }
    }

    private void exigirFonte(Referencia fonte, String esperada, String bloco) {
        exigir(fonte != null && esperada.equals(fonte.ref()), "O bloco " + bloco + " precisa da fonte " + esperada + ".");
    }

    private void exigirCalculoExistente(String ref, String bloco) {
        if ("calc".equals(Referencias.prefixo(ref))) {
            exigir(calculos.containsKey(Referencias.nome(ref)), "O bloco " + bloco + " usa " + ref + ", que o modelo não define.");
        }
    }

    private void validarCalculo(String nome, Calculo calculo) {
        exigir(NOME_DE_CALCULO.matcher(nome).matches(), "Nome de cálculo inválido: " + nome);
        exigir(calculo.rotulo() != null && !calculo.rotulo().isBlank(), "O cálculo " + nome + " não tem rótulo.");
        for (String variavel : Calculadora.variaveisDe(calculo.script())) {
            Referencia ref = calculo.variaveis().get(variavel);
            exigir(ref != null && Referencias.valida(ref.ref()), "O cálculo " + nome + " não define a variável " + variavel + ".");
            exigir(!"calc".equals(Referencias.prefixo(ref.ref())) && !"lista".equals(Referencias.prefixo(ref.ref())),
                    "O cálculo " + nome + " só aceita variável de dado ou da consolidação (" + variavel + ").");
        }
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new IllegalArgumentException(mensagem);
        }
    }
}
