package br.com.rbs.portal.atlas.jev;

import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * O modo e o limiar de cada ponto de decisão do JEV (plano do Atlas completo, M7), lidos uma vez na subida. [ATLAS] O modo padrão
 * de todos os pontos é o de {@code atlas.jev.modo} ({@code ATLAS_JEV}: {@code none}, {@code sombra} ou {@code ativo}); cada ponto
 * pode trocá-lo com {@code atlas.jev.<ponto>.modo}, e o limiar vem de {@code atlas.jev.<ponto>.limiar} (de 0 a 1; o padrão é o do
 * {@link PontoDoJev} até a calibração fixar o do ponto). O padrão do {@code application.yml} é {@code none} e nenhum ponto tem
 * override: quem liga cada ponto, depois da calibração, é quem opera. A chave, o endereço e o modelo do JEV ficam na
 * {@code ConfiguracaoDeProvedores}.
 */
@Component
public class ConfiguracaoDoJev {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDoJev.class);

    private final Map<PontoDoJev, ModoDoJev> modos = new EnumMap<>(PontoDoJev.class);
    private final Map<PontoDoJev, Double> limiares = new EnumMap<>(PontoDoJev.class);
    private final ModoDoJev modoPadrao;

    @Autowired
    public ConfiguracaoDoJev(Environment ambiente) {
        String padrao = ambiente.getProperty("atlas.jev.modo", "none");
        avisarSeDesconhecido("atlas.jev.modo", padrao);
        this.modoPadrao = ModoDoJev.de(padrao);
        for (PontoDoJev ponto : PontoDoJev.values()) {
            String doPonto = ambiente.getProperty("atlas.jev." + ponto.nome() + ".modo");
            if (doPonto != null && !doPonto.isBlank()) {
                avisarSeDesconhecido("atlas.jev." + ponto.nome() + ".modo", doPonto);
                modos.put(ponto, ModoDoJev.de(doPonto));
            } else {
                modos.put(ponto, modoPadrao);
            }
            limiares.put(ponto, limiarDe(ambiente, ponto));
        }
    }

    /** Uma configuração só com os modos e limiares dados (os testes montam a que querem; o resto fica desligado e no padrão). */
    public ConfiguracaoDoJev(ModoDoJev modoPadrao, Map<PontoDoJev, ModoDoJev> modosPorPonto, Map<PontoDoJev, Double> limiaresPorPonto) {
        this.modoPadrao = modoPadrao;
        for (PontoDoJev ponto : PontoDoJev.values()) {
            modos.put(ponto, modosPorPonto.getOrDefault(ponto, modoPadrao));
            limiares.put(ponto, limiaresPorPonto.getOrDefault(ponto, ponto.limiarPadrao()));
        }
    }

    private static double limiarDe(Environment ambiente, PontoDoJev ponto) {
        String chave = "atlas.jev." + ponto.nome() + ".limiar";
        String texto = ambiente.getProperty(chave);
        if (texto == null || texto.isBlank()) {
            return ponto.limiarPadrao();
        }
        try {
            double limiar = Double.parseDouble(texto.strip());
            if (limiar >= 0 && limiar <= 1) {
                return limiar;
            }
        } catch (NumberFormatException naoENumero) {
            // cai no aviso abaixo
        }
        log.warn("Atlas: {} tem um valor fora de 0 a 1; vale o padrão ({}).", chave, ponto.limiarPadrao());
        return ponto.limiarPadrao();
    }

    private static void avisarSeDesconhecido(String chave, String valor) {
        if (!ModoDoJev.conhecido(valor)) {
            log.warn("Atlas: {} tem um valor que não é none, sombra nem ativo; o JEV fica desligado nesse ponto.", chave);
        }
    }

    /** O modo do ponto: o dele, ou o de {@code atlas.jev.modo}. */
    public ModoDoJev modo(PontoDoJev ponto) {
        return modos.get(ponto);
    }

    /** O limiar do ponto: a confiança (ou o valor da {@code noul}) que a decisão precisa ter para o JEV agir. */
    public double limiar(PontoDoJev ponto) {
        return limiares.get(ponto);
    }

    /** O modo de {@code atlas.jev.modo}, o dos pontos sem override. */
    public ModoDoJev modoPadrao() {
        return modoPadrao;
    }

    /** Algum ponto consulta o JEV? */
    public boolean algumLigado() {
        return modos.values().stream().anyMatch(ModoDoJev::consulta);
    }
}
