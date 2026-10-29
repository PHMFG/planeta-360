package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.AtlasJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Os modelos-base de relatório do Atlas, lidos de {@code resources/atlas/modelos/*.json} quando a API sobe (hoje só o
 * ATIVACAO_EVENTO, em {@code ativacao-evento.json}, versionado no Git). [ATLAS] Cada arquivo é uma {@link Especificacao}: as
 * seções e os blocos com referências no lugar dos números, os blocos redigíveis com a frase-modelo, as fórmulas fixas e as
 * lacunas (as chaves do catálogo de que o relatório precisa). O arquivo é conferido na subida ({@link Especificacao#validar()}):
 * um modelo com referência fora da gramática, cálculo que não existe ou bloco repetido impede a API de subir, em vez de falhar
 * na demonstração. Um relatório copia o modelo ao nascer: mudar o arquivo depois não muda o que já foi montado.
 */
@Component
public class ModeloBase {

    /** O modelo do MVP. */
    public static final String ATIVACAO_EVENTO = "ATIVACAO_EVENTO";

    private final Map<String, Especificacao> modelos;

    public ModeloBase() {
        this.modelos = carregar();
    }

    /** O modelo pelo código, ou vazio se não existe. A especificação é imutável: pode ser compartilhada. */
    public Optional<Especificacao> obter(String codigo) {
        return Optional.ofNullable(codigo == null ? null : modelos.get(codigo));
    }

    public boolean existe(String codigo) {
        return obter(codigo).isPresent();
    }

    /** Os códigos dos modelos, na ordem em que foram lidos. */
    public Iterable<String> codigos() {
        return modelos.keySet();
    }

    private static Map<String, Especificacao> carregar() {
        Map<String, Especificacao> lidos = new LinkedHashMap<>();
        try {
            Resource[] arquivos = new PathMatchingResourcePatternResolver().getResources("classpath:atlas/modelos/*.json");
            for (Resource arquivo : arquivos) {
                String json = new String(arquivo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    Especificacao modelo = AtlasJson.ler(json, Especificacao.class);
                    modelo.validar();
                    if (lidos.putIfAbsent(modelo.modelo(), modelo) != null) {
                        throw new IllegalStateException("Dois arquivos definem o modelo " + modelo.modelo() + ".");
                    }
                } catch (RuntimeException erro) {
                    throw new IllegalStateException("O modelo-base " + arquivo.getFilename() + " está inválido: " + erro.getMessage(), erro);
                }
            }
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler os modelos-base do Atlas.", erro);
        }
        if (!lidos.containsKey(ATIVACAO_EVENTO)) {
            throw new IllegalStateException("Falta o modelo-base " + ATIVACAO_EVENTO + " em resources/atlas/modelos.");
        }
        return lidos;
    }
}
