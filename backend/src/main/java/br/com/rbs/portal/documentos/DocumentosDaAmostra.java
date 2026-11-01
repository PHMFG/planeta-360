package br.com.rbs.portal.documentos;

import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.CarregadorDoKit.Resultado;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dá documentos de verdade à demonstração do Atlas: ao subir a API nos perfis dev e demo, sobe o kit de 13 documentos fictícios como a
 * RBS (aprova e indexa o D01, publica o D02 que o substitui, tenta aprovar o D11 e registra a recusa esperada, deixa o D12 em ERRO e
 * indexa e revoga o D13). [ATLAS] No molde do {@code ArquivosDaAmostra}: idempotente (pelo grupo e a versão), só nesses perfis, e
 * nunca mexe no que a RBS enviou de verdade. Roda no final da subida, depois de a API já estar de pé: com o Gemini, indexar o kit
 * leva alguns segundos.
 *
 * <p>Com {@code ATLAS_EMBEDDINGS=none} (ou o Gemini sem chave), avisa no log, não cria os documentos, e a API sobe do mesmo jeito; quando
 * a chave chega, a próxima subida cria e indexa o kit. {@code ATLAS_EMBEDDINGS=teste} indexa com os embeddings de teste, e por isso só
 * em banco descartável (portal_e2e, portal_qaN): os vetores não têm o significado dos do Gemini.
 */
@Component
@Profile({"dev", "demo"})
public class DocumentosDaAmostra implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentosDaAmostra.class);

    private final CarregadorDoKit carregador;
    private final ProvedorDeEmbeddings embeddings;

    public DocumentosDaAmostra(CarregadorDoKit carregador, ProvedorDeEmbeddings embeddings) {
        this.carregador = carregador;
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        if (!embeddings.ligado()) {
            log.warn("Documentos da amostra: não criei os documentos de exemplo. {} A API sobe do mesmo jeito; quando a chave chegar, "
                    + "a próxima subida cria e indexa o kit.", embeddings.motivoDeDesligado());
            return;
        }
        Optional<UsuarioAutenticado> rbs = carregador.equipeDaRbs();
        if (rbs.isEmpty()) {
            log.warn("Documentos da amostra: o banco não tem nenhum usuário da RBS (a amostra não foi carregada); não criei os documentos.");
            return;
        }
        Resultado resultado = carregador.carregar(rbs.get());
        if (resultado.enviados().isEmpty()) {
            log.info("Documentos da amostra: o kit já estava no banco. Estado: {}", resultado.estados());
        } else {
            log.info("Documentos da amostra: {} documentos enviados ({}). Estado: {}", resultado.enviados().size(), embeddings.modelo(),
                    resultado.estados());
        }
    }
}
