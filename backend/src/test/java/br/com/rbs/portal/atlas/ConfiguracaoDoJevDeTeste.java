package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.jev.ConfiguracaoDoJev;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.mock.env.MockEnvironment;

/**
 * A {@link ConfiguracaoDoJev} dos testes: o modo e o limiar de cada ponto mudam de um teste para o outro, sem um contexto do Spring
 * por combinação. Tudo começa {@code DESLIGADO}, com o limiar padrão do ponto; cada teste liga o que precisa e o
 * {@code @BeforeEach} chama {@link #limpar}. Não faz parte da API.
 */
public class ConfiguracaoDoJevDeTeste extends ConfiguracaoDoJev {

    private final Map<PontoDoJev, ModoDoJev> modos = new ConcurrentHashMap<>();
    private final Map<PontoDoJev, Double> limiares = new ConcurrentHashMap<>();

    public ConfiguracaoDoJevDeTeste() {
        super(new MockEnvironment());
    }

    public ConfiguracaoDoJevDeTeste limpar() {
        modos.clear();
        limiares.clear();
        return this;
    }

    /** Liga o ponto neste modo (e, se vier, com este limiar). */
    public ConfiguracaoDoJevDeTeste ligar(PontoDoJev ponto, ModoDoJev modo) {
        modos.put(ponto, modo);
        return this;
    }

    public ConfiguracaoDoJevDeTeste ligar(PontoDoJev ponto, ModoDoJev modo, double limiar) {
        modos.put(ponto, modo);
        limiares.put(ponto, limiar);
        return this;
    }

    /** Liga todos os pontos neste modo. */
    public ConfiguracaoDoJevDeTeste ligarTodos(ModoDoJev modo) {
        for (PontoDoJev ponto : PontoDoJev.values()) {
            modos.put(ponto, modo);
        }
        return this;
    }

    @Override
    public ModoDoJev modo(PontoDoJev ponto) {
        return modos.getOrDefault(ponto, ModoDoJev.DESLIGADO);
    }

    @Override
    public double limiar(PontoDoJev ponto) {
        return limiares.getOrDefault(ponto, ponto.limiarPadrao());
    }

    @Override
    public boolean algumLigado() {
        return modos.values().stream().anyMatch(ModoDoJev::consulta);
    }
}
