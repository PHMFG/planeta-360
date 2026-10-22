package br.com.rbs.portal.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Texto de cada seção do relatório, montado por modelo a partir dos números que vieram do banco.
 * Nenhum valor é calculado ou inventado aqui. Se a equipe de IA & Dados quiser usar um LLM,
 * é neste ponto que ele entra: recebe estes mesmos números e devolve só o parágrafo.
 */
@Service
public class RelatorioTextoService {

    public String paraSecao(String secao, List<Map<String, Object>> indicadores) {
        List<String> linhas = new ArrayList<>();
        List<String> faltantes = new ArrayList<>();
        for (Map<String, Object> i : indicadores) {
            String nome = (String) i.get("nome");
            String recorte = (String) i.get("recorte");
            String rotulo = (recorte == null || recorte.isBlank()) ? nome : nome + " (" + recorte + ")";
            if (Boolean.TRUE.equals(i.get("disponivel")) && i.get("valor") != null) {
                String valor = ((BigDecimal) i.get("valor")).stripTrailingZeros().toPlainString();
                String un = (String) i.get("unidade");
                String base = (String) i.get("baseCalculo");
                linhas.add(rotulo + ": " + valor + (un == null ? "" : " " + un) + (base == null ? "" : " [" + base + "]"));
            } else {
                faltantes.add(rotulo);
            }
        }
        StringBuilder sb = new StringBuilder(secao).append(". ").append(String.join("; ", linhas)).append('.');
        if (!faltantes.isEmpty()) {
            sb.append(" Sem dado na base: ").append(String.join(", ", faltantes)).append('.');
        }
        return sb.toString();
    }
}
