package br.com.rbs.portal.atlas.diagnostico;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A configuração do JEV (TypeSafe) e dos embeddings (Gemini), lida uma vez na subida. [ATLAS] Por enquanto serve só ao
 * diagnóstico (rotas 52 e 53 mostram o estado e testam a conexão): o cliente de verdade do Gemini vem no M3 e o do JEV no M7. As
 * chaves vêm de variável de ambiente (ou do {@code config/segredos.local.properties}, no perfil dev) e nunca saem daqui inteiras:
 * {@link ProvedoresService} mostra no máximo os 4 últimos caracteres. As URLs base são configuráveis para os testes usarem um
 * servidor HTTP falso.
 * <ul>
 *   <li>JEV: {@code atlas.jev.chave} ({@code TYPESAFE_API_KEY}), {@code atlas.jev.base-url} ({@code ATLAS_JEV_BASE_URL}), o
 *       modelo ({@code ATLAS_JEV_MODELO}, {@code jev-1.14.0}) e o modo ({@code ATLAS_JEV}: {@code none}, {@code sombra} ou
 *       {@code ativo}).</li>
 *   <li>Gemini: {@code atlas.embeddings.chave} ({@code GEMINI_API_KEY}), {@code atlas.embeddings.base-url}
 *       ({@code ATLAS_GEMINI_BASE_URL}), o modelo ({@code gemini-embedding-2}) e o provedor escolhido ({@code ATLAS_EMBEDDINGS}:
 *       {@code gemini}, {@code teste} ou {@code none}).</li>
 * </ul>
 */
@Component
public class ConfiguracaoDeProvedores {

    private final String jevChave;
    private final String jevBaseUrl;
    private final String jevModelo;
    private final String jevModo;
    private final String geminiChave;
    private final String geminiBaseUrl;
    private final String geminiModelo;

    public ConfiguracaoDeProvedores(@Value("${atlas.jev.chave:}") String jevChave,
                                    @Value("${atlas.jev.base-url:https://api.typesafe.ai}") String jevBaseUrl,
                                    @Value("${atlas.jev.modelo:jev-1.14.0}") String jevModelo,
                                    @Value("${atlas.jev.modo:none}") String jevModo,
                                    @Value("${atlas.embeddings.chave:}") String geminiChave,
                                    @Value("${atlas.embeddings.base-url:https://generativelanguage.googleapis.com}") String geminiBaseUrl,
                                    @Value("${atlas.embeddings.modelo:gemini-embedding-2}") String geminiModelo) {
        this.jevChave = limpa(jevChave);
        this.jevBaseUrl = jevBaseUrl;
        this.jevModelo = jevModelo;
        this.jevModo = jevModo;
        this.geminiChave = limpa(geminiChave);
        this.geminiBaseUrl = geminiBaseUrl;
        this.geminiModelo = geminiModelo;
    }

    private static String limpa(String chave) {
        return chave == null || chave.isBlank() ? null : chave.strip();
    }

    public String jevChave() {
        return jevChave;
    }

    public boolean jevTemChave() {
        return jevChave != null;
    }

    public String jevBaseUrl() {
        return jevBaseUrl;
    }

    public String jevModelo() {
        return jevModelo;
    }

    /** O modo do JEV, no vocabulário da API: {@code DESLIGADO}, {@code SOMBRA} ou {@code ATIVO} ({@code none} também vale como desligado). */
    public String jevModo() {
        return switch (jevModo == null ? "" : jevModo.strip().toLowerCase(Locale.ROOT)) {
            case "sombra" -> "SOMBRA";
            case "ativo" -> "ATIVO";
            default -> "DESLIGADO";
        };
    }

    public String geminiChave() {
        return geminiChave;
    }

    public boolean geminiTemChave() {
        return geminiChave != null;
    }

    public String geminiBaseUrl() {
        return geminiBaseUrl;
    }

    public String geminiModelo() {
        return geminiModelo;
    }
}
