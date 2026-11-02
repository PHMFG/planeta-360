package br.com.rbs.portal.documentos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/**
 * O kit de documentos fictícios do Atlas (plano do Atlas completo, seção 9): os 13 arquivos de {@code dev/documentos/} com o que cada
 * um é para o portal (título, chave lógica, tipo, escopo, edição). [ATLAS] Quem sobe o kit (o {@code DocumentosDaAmostra} nos perfis dev
 * e demo, e o fixture dos testes) lê as mesmas linhas daqui; os títulos são os que o avaliador de conversas reconhece
 * ({@code ferramentas/avaliacao-atlas/conferencias.mjs}) e não mudam sem mudar lá. O {@code gabarito.json}, ao lado dos arquivos, diz
 * o que cada página contém.
 */
public final class KitDeDocumentos {

    /** Como o documento entra: aprovado e indexado; só enviado ou com aprovação recusada; ou indexado e depois revogado. */
    public enum Destino {
        INDEXAR, TENTAR_APROVAR, SO_ENVIAR, INDEXAR_E_REVOGAR
    }

    /**
     * Uma linha do kit. {@code versao} é a versão lógica do documento no grupo (o D02 é a 2 do manual de 2027), a mesma que o gabarito
     * usa e que o banco dá ao subir os arquivos na ordem. {@code escopo} é GLOBAL, COTA ou PARCERIA; {@code cota} é o código (MASTER,
     * SUBCOTA_G, SUBCOTA) e só vale no COTA; {@code patrocinador} é o nome canônico da marca (Cliente Amarelo...) e só vale no PARCERIA;
     * {@code edicao} é o ano do festival.
     */
    public record Documento(String id, String arquivo, String titulo, String chave, int versao, String tipo, String escopo, int edicao,
                            String cota, String patrocinador, Destino destino) {

        public byte[] conteudo() {
            try {
                return new ClassPathResource(PASTA + arquivo).getContentAsByteArray();
            } catch (IOException erro) {
                throw new UncheckedIOException("Não consegui ler o documento do kit " + arquivo, erro);
            }
        }
    }

    public static final String PASTA = "dev/documentos/";
    public static final String GABARITO = PASTA + "gabarito.json";

    /** Os 13 documentos, na ordem em que o kit sobe: o D01 antes do D02 (que o substitui). */
    public static final List<Documento> DOCUMENTOS = List.of(
            new Documento("D01", "d01-manual-operacoes-2027-v1.pdf", "Manual de Operações 2027", "manual-operacoes", 1,
                    "MANUAL_OPERACOES", "GLOBAL", 2027, null, null, Destino.INDEXAR),
            new Documento("D02", "d02-manual-operacoes-2027-v2.pdf", "Manual de Operações 2027", "manual-operacoes", 2,
                    "MANUAL_OPERACOES", "GLOBAL", 2027, null, null, Destino.INDEXAR),
            new Documento("D03", "d03-plano-comercial-master-2027.pdf", "Plano comercial · Cota Master 2027", "plano-comercial", 1,
                    "PLANO_COMERCIAL", "COTA", 2027, "MASTER", null, Destino.INDEXAR),
            new Documento("D04", "d04-plano-comercial-subcota-g-2027.pdf", "Plano comercial · Subcota G 2027", "plano-comercial", 1,
                    "PLANO_COMERCIAL", "COTA", 2027, "SUBCOTA_G", null, Destino.INDEXAR),
            new Documento("D05", "d05-plano-comercial-subcota-2027.pdf", "Plano comercial · Subcota 2027", "plano-comercial", 1,
                    "PLANO_COMERCIAL", "COTA", 2027, "SUBCOTA", null, Destino.INDEXAR),
            new Documento("D06", "d06-anexo-parceria-amarelo-2027.pdf", "Anexo da parceria · Cliente Amarelo", "anexo-parceria", 1,
                    "ANEXO_PARCERIA", "PARCERIA", 2027, null, "Cliente Amarelo", Destino.INDEXAR),
            new Documento("D07", "d07-anexo-parceria-roxo-2027.pdf", "Anexo da parceria · Cliente Roxo", "anexo-parceria", 1,
                    "ANEXO_PARCERIA", "PARCERIA", 2027, null, "Cliente Roxo", Destino.INDEXAR),
            new Documento("D08", "d08-faq-patrocinador-2027.txt", "FAQ do patrocinador 2027", "faq-patrocinador", 1,
                    "FAQ", "GLOBAL", 2027, null, null, Destino.INDEXAR),
            new Documento("D09", "d09-comunicado-abastecimento.pdf", "Comunicado 12/2026 · Janela extra de abastecimento",
                    "comunicado-abastecimento", 1, "COMUNICADO", "GLOBAL", 2027, null, null, Destino.INDEXAR),
            new Documento("D10", "d10-manual-operacoes-2026.pdf", "Manual de Operações 2026", "manual-operacoes", 1,
                    "MANUAL_OPERACOES", "GLOBAL", 2026, null, null, Destino.INDEXAR),
            new Documento("D11", "d11-lista-credenciados-verde.pdf", "Lista de credenciados · Cliente Verde", "lista-credenciados", 1,
                    "OUTRO", "PARCERIA", 2027, null, "Cliente Verde", Destino.TENTAR_APROVAR),
            new Documento("D12", "d12-documento-imagem.pdf", "Aviso digitalizado · pontos de apoio", "documento-imagem", 1,
                    "OUTRO", "GLOBAL", 2027, null, null, Destino.SO_ENVIAR),
            new Documento("D13", "d13-circular-revogada.pdf", "Circular 07/2026 · Pulseiras prata", "circular-revogada", 1,
                    "COMUNICADO", "GLOBAL", 2027, null, null, Destino.INDEXAR_E_REVOGAR));

    private KitDeDocumentos() {
    }

    /** O documento do kit pelo id (D01 a D13). */
    public static Documento por(String id) {
        return DOCUMENTOS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("O kit não tem o documento " + id));
    }
}
