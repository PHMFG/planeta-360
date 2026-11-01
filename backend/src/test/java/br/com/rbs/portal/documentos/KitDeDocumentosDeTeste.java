package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.CarregadorDoKit.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * O kit de documentos fictícios no perfil {@code test} (seção 8.1 do plano do Atlas completo). [ATLAS] Sobe e indexa os 13 documentos
 * com os embeddings de teste uma vez por contexto, como a amostra sobe os dados dela: os testes de canário, RLS, busca e visualizador
 * (M4 em diante) precisam de documentos indexados de verdade. O kit fica CONFIRMADO no banco, então os testes não podem achar uma linha
 * dele por acaso: o arquivo é da categoria DOCUMENTO, o histórico é da entidade {@code documento} e usa CRIACAO e ALTERACAO_STATUS,
 * nunca EDICAO. O estado final é sempre o mesmo: D01 substituído pelo D02, D03 a D10 indexados, D11 aguardando aprovação (com dado
 * pessoal), D12 em ERRO e D13 revogado. Está em src/test porque o perfil test é o único que o usa; quem sobe o kit é o
 * {@link CarregadorDoKit}, o mesmo do {@code DocumentosDaAmostra}.
 */
@Component
@Profile("test")
public class KitDeDocumentosDeTeste implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KitDeDocumentosDeTeste.class);

    private final CarregadorDoKit carregador;

    public KitDeDocumentosDeTeste(CarregadorDoKit carregador) {
        this.carregador = carregador;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        long inicio = System.nanoTime();
        var rbs = carregador.equipeDaRbs().orElseThrow(() -> new IllegalStateException("O banco de teste não tem usuário da RBS"));
        Resultado resultado = carregador.carregar(rbs);
        log.info("Kit de documentos de teste: {} enviados em {} ms. Estado: {}", resultado.enviados().size(),
                (System.nanoTime() - inicio) / 1_000_000, resultado.estados());
    }
}
