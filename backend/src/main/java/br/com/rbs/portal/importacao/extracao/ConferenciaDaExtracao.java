package br.com.rbs.portal.importacao.extracao;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.OrigemDoValor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
 */
@Component
public class ConferenciaDaExtracao {

    private static final Set<String> UNIDADES = Set.of("INSERCAO", "CPM", "APLICACAO", "PERCENTUAL", "NOTA", "PESSOAS", "MARCAS",
            "IMPACTOS", "REAIS", "UNIDADE");

    private final ImportacaoRepositorio repositorio;

    public ConferenciaDaExtracao(ImportacaoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance, Long parceriaId) {
        List<String> problemas = new ArrayList<>();
        for (String campo : List.of("quantidade", "impacto", "valor", "base_respondentes", "quantidade_prevista")) {
            Object valor = linha.dados().get(campo);
            if (valor == null) {
                continue;
            }
            OrigemDoValor origem = linha.origem().get(campo);
            if (origem == null || origem.trecho() == null) {
                problemas.add("Falta a origem de " + campo + " na fonte.");
            } else if (!numeroNaFonte(valor, origem.trecho())) {
                problemas.add("O valor de " + campo + " não aparece na fonte " + origem.localizador() + ".");
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
        Object unidade = linha.dados().get("unidade");
        if (unidade != null && !UNIDADES.contains(texto(unidade).toUpperCase(Locale.ROOT))) {
            problemas.add("A unidade " + unidade + " não é conhecida.");
        }
        return problemas;
    }

    private static boolean numeroNaFonte(Object valor, String trecho) {
        String alvo;
        try {
            alvo = NormalizadorDeNumeros.normalizarNumero(texto(valor));
        } catch (RuntimeException naoEhNumero) {
            return false;
        }
        for (Achado achado : NormalizadorDeNumeros.extrair(trecho, true)) {
            if (achado.valores().contains(alvo)) {
                return true;
            }
        }
        return false;
    }

    private static boolean textoNaFonte(Object valor, OrigemDoValor origem) {
        return origem != null && origem.trecho() != null
                && texto(origem.trecho()).replace(" ", "").contains(texto(valor).replace(" ", ""));
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
        LocalDate dia;
        try {
            if (data.length() >= 10) {
                dia = LocalDate.parse(data.substring(0, 10));
            } else {
                dia = LocalDate.parse(data);
            }
        } catch (DateTimeParseException invalido) {
            try {
                dia = OffsetDateTime.parse(data).toLocalDate();
            } catch (DateTimeParseException tambemInvalida) {
                return false;
            }
        }
        return !dia.isBefore(LocalDate.of(edicao - 1, 10, 1)) && !dia.isAfter(LocalDate.of(edicao, 2, 28));
    }

    private static String texto(Object valor) {
        return valor == null ? "" : String.valueOf(valor);
    }
}
