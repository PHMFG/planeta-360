package br.com.rbs.portal.importacao.extracao;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.OrigemDoValor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A conferência em código (E10): todo número e toda data da linha aparecem na fonte, no localizador citado, pelo
 * {@link NormalizadorDeNumeros}; o mês ou a data está dentro da edição (outubro a fevereiro); a unidade é conhecida. O que falha
 * vira o motivo de uma linha RECUSADA, e a linha não some.
 * <p>
 * Numa linha de texto corrido ({@link LinhaProposta#deTexto()}), a conferência é a mais rigorosa do E8: <b>todo</b> valor precisa de
 * origem e precisa aparecer no trecho citado, não só o número e a data. Os campos que são código do destino (o qualificador, o tipo,
 * a dimensão, a unidade e o tipo de mídia) não precisam aparecer com o nome do código na fonte; a unidade continua tendo de ser uma
 * das conhecidas. As palavras de um campo de texto são comparadas pela regra do {@link PalavrasDaFonte}.
 */
@Component
public class ConferenciaDaExtracao {

    private static final Set<String> UNIDADES = Set.of("INSERCAO", "CPM", "APLICACAO", "PERCENTUAL", "NOTA", "CONTAGEM", "PESSOAS",
            "MARCAS", "IMPACTOS", "REAIS", "UNIDADE");
    /** Os campos numéricos que a conferência compara com os números escritos na fonte. */
    private static final Set<String> NUMEROS = Set.of("quantidade", "impacto", "valor", "base_respondentes", "quantidade_prevista",
            "pergunta", "fonte_pagina");
    /** Os campos que são código do destino: o valor não precisa estar escrito com esse nome na fonte. */
    private static final Set<String> CODIGOS = Set.of("qualificador", "tipo", "dimensao", "unidade", "tipo_midia", "indicador");
    private static final DateTimeFormatter DIA_MES_ANO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ImportacaoRepositorio repositorio;

    public ConferenciaDaExtracao(ImportacaoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance, Long parceriaId) {
        if (linha.deTexto()) {
            return problemasDoTexto(linha, alcance);
        }
        List<String> problemas = new ArrayList<>();
        for (String campo : List.of("quantidade", "impacto", "valor", "pergunta", "base_respondentes", "quantidade_prevista")) {
            Object valor = linha.dados().get(campo);
            if (valor == null) {
                continue;
            }
            // A contagem de uma resposta de texto livre é o número de repetições, e não um número da planilha.
            if ("valor".equals(campo) && "TEXTO".equalsIgnoreCase(texto(linha.dados().get("tipo")))) {
                continue;
            }
            OrigemDoValor origem = linha.origem().get(campo);
            if (origem == null || origem.trecho() == null) {
                problemas.add("Falta a origem " + origemDoCampo(campo) + " na fonte.");
            } else if (!numeroNaFonte(valor, origem.trecho())) {
                problemas.add(nomeDoCampo(campo) + " não aparece na fonte " + origem.localizador() + ".");
            }
        }
        Object mes = linha.dados().get("mes");
        if (mes != null && !textoNaFonte(mes, linha.origem().get("mes"))) {
            problemas.add("O mês não aparece na fonte.");
        }
        if (mes != null && !mesNaEdicao(texto(mes), edicao(alcance.eventoId()))) {
            problemas.add("O mês está fora da edição (outubro a fevereiro).");
        }
        Object exibidoEm = linha.dados().get("exibido_em");
        if (exibidoEm != null && !dataNaEdicao(texto(exibidoEm), edicao(alcance.eventoId()))) {
            problemas.add("A data está fora da edição (outubro a fevereiro).");
        }
        if (mes != null && exibidoEm != null && mesesDiferentes(texto(mes), texto(exibidoEm))) {
            problemas.add("A data da exibição não é do mês informado.");
        }
        Object unidade = linha.dados().get("unidade");
        if (unidade != null && !UNIDADES.contains(texto(unidade).toUpperCase(Locale.ROOT))) {
            problemas.add("A unidade " + unidade + " não é conhecida.");
        }
        return problemas;
    }

    /**
     * A conferência de uma linha de texto corrido: cada valor precisa da origem e precisa aparecer no trecho da fonte. O que não
     * aparece vira o motivo da linha RECUSADA, como no E10.
     */
    private List<String> problemasDoTexto(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        for (var campo : linha.dados().entrySet()) {
            String nome = campo.getKey();
            Object valor = campo.getValue();
            if (valor == null || String.valueOf(valor).isBlank()) {
                continue;
            }
            OrigemDoValor origem = linha.origem().get(nome);
            if (origem == null || origem.trecho() == null || origem.trecho().isBlank()) {
                problemas.add("Falta a origem " + origemDoCampo(nome) + " na fonte.");
                continue;
            }
            if (NUMEROS.contains(nome)) {
                if (!numeroNaFonte(valor, origem.trecho())) {
                    problemas.add(nomeDoCampo(nome) + " não aparece na fonte " + origem.localizador() + ".");
                }
            } else if ("mes".equals(nome)) {
                if (!mesNaFonte(valor, origem.trecho())) {
                    problemas.add("O mês não aparece na fonte " + origem.localizador() + ".");
                }
                if (!mesNaEdicao(texto(valor), edicao(alcance.eventoId()))) {
                    problemas.add("O mês está fora da edição (outubro a fevereiro).");
                }
            } else if ("exibido_em".equals(nome) || "data".equals(nome)) {
                if (!dataNaFonte(valor, origem.trecho())) {
                    problemas.add("A data não aparece na fonte " + origem.localizador() + ".");
                }
                if (!dataNaEdicao(texto(valor), edicao(alcance.eventoId()))) {
                    problemas.add("A data está fora da edição (outubro a fevereiro).");
                }
            } else if ("unidade".equals(nome)) {
                if (!UNIDADES.contains(texto(valor).toUpperCase(Locale.ROOT))) {
                    problemas.add("A unidade " + valor + " não é conhecida.");
                }
            } else if (!CODIGOS.contains(nome) && !textoNaFonte(valor, origem)) {
                problemas.add(nomeDoCampo(nome) + " não aparece na fonte " + origem.localizador() + ".");
            }
        }
        Object mes = linha.dados().get("mes");
        Object exibidoEm = linha.dados().get("exibido_em");
        if (mes != null && exibidoEm != null && mesesDiferentes(texto(mes), texto(exibidoEm))) {
            problemas.add("A data da exibição não é do mês informado.");
        }
        return problemas;
    }

    static boolean numeroNaFonte(Object valor, String trecho) {
        // O valor não é número: quem dá o motivo é o destino, com a frase do campo ("precisa ser um número").
        if (MapeadorTabular.decimal(texto(valor)) == null) {
            return true;
        }
        String alvo;
        try {
            alvo = NormalizadorDeNumeros.normalizarNumero(texto(valor));
            // O sinal negativo é da regra do destino (mínimo); a fonte escreve o número e a conferência só precisa achá-lo.
            if (alvo.startsWith("-")) {
                alvo = alvo.substring(1);
            }
        } catch (RuntimeException naoEhNumero) {
            return true;
        }
        for (Achado achado : NormalizadorDeNumeros.extrair(trecho, true)) {
            if (achado.valores().contains(alvo)) {
                return true;
            }
        }
        return false;
    }

    private static boolean textoNaFonte(Object valor, OrigemDoValor origem) {
        if (origem == null || origem.trecho() == null) {
            return false;
        }
        return PalavrasDaFonte.aparece(texto(valor), origem.trecho());
    }

    /** O mês pode estar escrito como "2026-10", "outubro de 2026" ou como uma data de outubro daquele ano. */
    static boolean mesNaFonte(Object valor, String trecho) {
        String texto = texto(valor);
        if (textoNaFonte(valor, new OrigemDoValor("", trecho))) {
            return true;
        }
        try {
            YearMonth alvo = YearMonth.parse(texto);
            for (Achado achado : NormalizadorDeNumeros.extrair(trecho, true)) {
                if (achado.ehData() && Integer.parseInt(achado.dia().substring(0, 2)) == alvo.getMonthValue()
                        && (achado.ano() == null || achado.ano().equals(String.valueOf(alvo.getYear())))) {
                    return true;
                }
            }
            String semAcento = NormalizadorDeNumeros.semAcento(trecho);
            if (!semAcento.contains(nomeDoMes(alvo.getMonthValue()))) {
                return false;
            }
            // O mês citado sem ano ("em outubro") vale com o ano da edição; se o trecho diz outro ano, não vale.
            boolean temAno = semAcento.matches("(?s).*\\b(?:19|20)\\d{2}\\b.*");
            return !temAno || semAcento.contains(String.valueOf(alvo.getYear()));
        } catch (DateTimeParseException invalido) {
            return false;
        }
    }

    private static String nomeDoMes(int mes) {
        return List.of("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
                "novembro", "dezembro").get(mes - 1);
    }

    /** A data pode estar escrita como "2026-10-18", "18/10/2026" ou "18 de outubro de 2026". */
    static boolean dataNaFonte(Object valor, String trecho) {
        LocalDate alvo = dataDe(texto(valor));
        if (alvo == null) {
            return textoNaFonte(valor, new OrigemDoValor("", trecho));
        }
        for (Achado achado : NormalizadorDeNumeros.extrair(trecho, true)) {
            if (!achado.ehData()) {
                continue;
            }
            int mes = Integer.parseInt(achado.dia().substring(0, 2));
            int dia = Integer.parseInt(achado.dia().substring(3));
            if (mes == alvo.getMonthValue() && dia == alvo.getDayOfMonth()
                    && (achado.ano() == null || achado.ano().equals(String.valueOf(alvo.getYear())))) {
                return true;
            }
        }
        return false;
    }

    /** O mês e a data só podem conviver quando dizem o mesmo mês; nesse caso o serviço tira o mês antes de gravar. */
    static boolean mesesDiferentes(String mes, String data) {
        try {
            YearMonth doMes = YearMonth.parse(mes.strip());
            LocalDate doDia = dataDe(data);
            return doDia != null && !YearMonth.from(doDia).equals(doMes);
        } catch (RuntimeException invalido) {
            return false;
        }
    }

    private Integer edicao(long eventoId) {
        return repositorio.evento(eventoId).map(ImportacaoRepositorio.Evento::edicao).orElse(null);
    }

    static boolean mesNaEdicao(String mes, Integer edicao) {
        if (edicao == null) {
            return true;
        }
        try {
            YearMonth alvo = YearMonth.parse(mes);
            YearMonth inicio = YearMonth.of(edicao - 1, 10);
            YearMonth fim = YearMonth.of(edicao, 2);
            return !alvo.isBefore(inicio) && !alvo.isAfter(fim);
        } catch (DateTimeParseException invalido) {
            return false;
        }
    }

    static boolean dataNaEdicao(String data, Integer edicao) {
        if (edicao == null) {
            return true;
        }
        LocalDate dia = dataDe(data);
        if (dia == null) {
            return false;
        }
        return !dia.isBefore(LocalDate.of(edicao - 1, 10, 1)) && !dia.isAfter(LocalDate.of(edicao, 2, 28));
    }

    /** A data de um valor ISO (com ou sem hora), de "dd/MM/yyyy" ou de "dd-MM-yyyy"; nula quando não é uma data. */
    public static LocalDate dataDe(String data) {
        if (data == null) {
            return null;
        }
        String texto = data.strip();
        try {
            if (texto.length() >= 10 && texto.charAt(4) == '-') {
                return LocalDate.parse(texto.substring(0, 10));
            }
            if (texto.matches("\\d{2}/\\d{2}/\\d{4}")) {
                return LocalDate.parse(texto, DIA_MES_ANO);
            }
            if (texto.matches("\\d{2}-\\d{2}-\\d{4}")) {
                return LocalDate.parse(texto, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            }
            return OffsetDateTime.parse(texto).toLocalDate();
        } catch (DateTimeParseException invalido) {
            return null;
        }
    }

    /** O nome do campo na frase da prévia, em português, sem o nome cru do JSON. */
    static String nomeDoCampo(String campo) {
        return switch (campo) {
            case "patrocinador" -> "A marca";
            case "veiculo" -> "O veículo";
            case "formato" -> "O formato";
            case "espaco_programa" -> "O espaço ou programa";
            case "quantidade" -> "A quantidade";
            case "impacto" -> "O impacto";
            case "valor" -> "O número";
            case "base_respondentes" -> "A base de respondentes";
            case "quantidade_prevista" -> "A quantidade prevista";
            case "pergunta" -> "O número da pergunta";
            case "fonte_pagina" -> "A página da fonte";
            case "indicador" -> "O indicador";
            case "fonte" -> "A fonte";
            case "qualificador" -> "O qualificador";
            case "unidade" -> "A unidade";
            case "tipo" -> "O tipo";
            case "dimensao" -> "A dimensão";
            case "categoria" -> "A categoria";
            case "alternativa" -> "A alternativa";
            case "texto" -> "O texto";
            case "link" -> "O link";
            case "campo_chave" -> "O campo";
            case "bloco" -> "O bloco";
            case "janela" -> "A janela";
            case "tipo_midia" -> "O tipo de mídia";
            case "mes" -> "O mês";
            case "exibido_em", "data" -> "A data";
            default -> "O campo " + campo;
        };
    }

    /** A contração para "Falta a origem ... na fonte." */
    static String origemDoCampo(String campo) {
        return switch (campo) {
            case "patrocinador" -> "da marca";
            case "veiculo" -> "do veículo";
            case "formato" -> "do formato";
            case "espaco_programa" -> "do espaço ou programa";
            case "quantidade" -> "da quantidade";
            case "impacto" -> "do impacto";
            case "valor" -> "do número";
            case "base_respondentes" -> "da base de respondentes";
            case "quantidade_prevista" -> "da quantidade prevista";
            case "pergunta" -> "do número da pergunta";
            case "fonte_pagina" -> "da página da fonte";
            case "indicador" -> "do indicador";
            case "fonte" -> "da fonte";
            case "qualificador" -> "do qualificador";
            case "unidade" -> "da unidade";
            case "tipo" -> "do tipo";
            case "dimensao" -> "da dimensão";
            case "categoria" -> "da categoria";
            case "alternativa" -> "da alternativa";
            case "texto" -> "do texto";
            case "link" -> "do link";
            case "campo_chave" -> "do campo";
            case "bloco" -> "do bloco";
            case "janela" -> "da janela";
            case "tipo_midia" -> "do tipo de mídia";
            case "mes" -> "do mês";
            case "exibido_em", "data" -> "da data";
            default -> "de " + campo;
        };
    }

    private static String texto(Object valor) {
        return valor == null ? "" : String.valueOf(valor);
    }
}
