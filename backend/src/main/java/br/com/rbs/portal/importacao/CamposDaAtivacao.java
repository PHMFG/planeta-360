package br.com.rbs.portal.importacao;

import br.com.rbs.portal.dados.DadoInformadoRepositorio;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeCampo;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeVigente;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Os campos da ativação no catálogo (aba APLICACOES), pelo nome que a planilha da agência traz: a chave
 * ({@code ativacao.visitantes_total}) ou o rótulo ("Visitantes no espaço"), sem diferença de caixa nem de acento.
 * [IMPORTACAO] A planilha da agência é escrita por pessoas, e cada uma usa o rótulo ou a chave; a importação resolve
 * os dois para a mesma linha do catálogo. Nada é gravado aqui.
 */
@Component
public class CamposDaAtivacao {

    private final DadoInformadoRepositorio repositorio;

    public CamposDaAtivacao(DadoInformadoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    /** Todos os campos do catálogo da aba Aplicações, na ordem do catálogo. */
    public List<LinhaDeCampo> campos() {
        return repositorio.campos("APLICACOES");
    }

    /**
     * O campo do catálogo que a planilha nomeou: pela chave ou pelo rótulo, sem caixa nem acento. Vazio quando o texto
     * não é um campo da aba APLICACOES.
     */
    public Optional<LinhaDeCampo> resolver(String referencia) {
        String alvo = normalizar(referencia);
        if (alvo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(porNome().get(alvo));
    }

    /** O valor numérico vigente de cada campo da parceria, pela chave do campo; os sem valor não aparecem. */
    public Map<String, BigDecimal> valoresVigentes(long parceriaId) {
        Map<String, BigDecimal> valores = new LinkedHashMap<>();
        for (LinhaDeVigente vigente : repositorio.vigentes(parceriaId)) {
            if (vigente.valorNumero() != null) {
                valores.put(vigente.campoChave(), vigente.valorNumero());
            }
        }
        return valores;
    }

    /** O mapa da chave e do rótulo normalizados para o campo, montado a cada chamada (o catálogo é pequeno). */
    private Map<String, LinhaDeCampo> porNome() {
        Map<String, LinhaDeCampo> mapa = new LinkedHashMap<>();
        for (LinhaDeCampo campo : campos()) {
            mapa.putIfAbsent(normalizar(campo.chave()), campo);
            mapa.putIfAbsent(normalizar(campo.rotulo()), campo);
        }
        return mapa;
    }

    /** O texto como a planilha costuma variar: sem acento, em minúsculas, com um espaço só entre as palavras. */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
