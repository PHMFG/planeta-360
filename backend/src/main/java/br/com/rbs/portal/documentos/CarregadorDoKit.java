package br.com.rbs.portal.documentos;

import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.DocumentoDtos.DocumentoDto;
import br.com.rbs.portal.documentos.DocumentoService.DadosDoEnvio;
import br.com.rbs.portal.documentos.KitDeDocumentos.Destino;
import br.com.rbs.portal.documentos.KitDeDocumentos.Documento;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sobe o kit de documentos fictícios pelo caminho de verdade: envia cada arquivo como a RBS, aprova, espera a ingestão e, nos que
 * pedem, revoga. [ATLAS] Quem chama é o {@code DocumentosDaAmostra} (perfis dev e demo, com os embeddings configurados) e o fixture
 * dos testes (com os embeddings de teste). Idempotente pelo grupo e a versão: o documento que o grupo já tem na versão do kit não é
 * enviado de novo, então subir a API outra vez não duplica nada, e quem chegou depois (a chave do Gemini) completa o que faltou.
 * O que acontece com cada um vem do {@link Destino}: o D01 e o D02 (que o substitui), os planos, os anexos, o FAQ, o comunicado e o
 * manual de 2026 são aprovados e indexados; o D11 (CPFs) tem a aprovação recusada pelo gatilho; o D12 (só imagem) nasce em ERRO; o D13
 * é indexado e revogado.
 */
@Component
public class CarregadorDoKit {

    private static final Logger log = LoggerFactory.getLogger(CarregadorDoKit.class);
    private static final Duration PRAZO_POR_DOCUMENTO = Duration.ofMinutes(5);

    /**
     * O que o carregamento fez. {@code estados} traz o status final de cada documento do kit (pelo id D01 a D13) que existe no banco,
     * {@code enviados} os que esta chamada criou, {@code recusas} a frase de cada aprovação que o banco recusou.
     */
    public record Resultado(Map<String, String> estados, List<String> enviados, List<String> recusas) {
    }

    private final DocumentoRepositorio repositorio;
    private final DocumentoService servico;
    private final IngestaoService ingestao;
    private final JdbcClient jdbc;

    public CarregadorDoKit(DocumentoRepositorio repositorio, DocumentoService servico, IngestaoService ingestao, JdbcClient jdbc) {
        this.repositorio = repositorio;
        this.servico = servico;
        this.ingestao = ingestao;
        this.jdbc = jdbc;
    }

    /** A primeira pessoa da equipe da RBS, em quem o kit é enviado e aprovado; vazio num banco sem usuário. */
    public Optional<UsuarioAutenticado> equipeDaRbs() {
        return jdbc.sql("SELECT id, nome, email FROM usuario WHERE perfil = 'ADMIN_RBS' AND ativo ORDER BY id LIMIT 1")
                .query((rs, n) -> new UsuarioAutenticado(rs.getLong("id"), rs.getString("nome"), rs.getString("email"), Perfil.ADMIN_RBS))
                .optional();
    }

    public Resultado carregar(UsuarioAutenticado rbs) {
        Map<String, String> estados = new LinkedHashMap<>();
        List<String> enviados = new ArrayList<>();
        List<String> recusas = new ArrayList<>();
        for (Documento d : KitDeDocumentos.DOCUMENTOS) {
            Long eventoId = repositorio.eventoDaEdicao(d.edicao()).orElse(null);
            Long parceriaId = d.patrocinador() == null ? null : repositorio.parceriaDoPatrocinador(d.patrocinador(), d.edicao()).orElse(null);
            Integer cotaId = d.cota() == null ? null : repositorio.cotaPorCodigo(d.cota()).orElse(null);
            if (eventoId == null || (d.patrocinador() != null && parceriaId == null) || (d.cota() != null && cotaId == null)) {
                log.warn("Atlas: o banco não tem a edição, a cota ou a parceria do documento {} ({}); ele não foi enviado.", d.id(), d.titulo());
                continue;
            }
            Long parceriaDoGrupo = "PARCERIA".equals(d.escopo()) ? parceriaId : null;
            if (repositorio.existeNoGrupo(eventoId, d.escopo(), cotaId, parceriaDoGrupo, d.chave(), d.versao())) {
                continue;
            }
            DocumentoDto criado = servico.enviar(rbs, d.arquivo(), d.conteudo(), new DadosDoEnvio(d.titulo(), d.chave(), d.tipo(), d.escopo(),
                    "PARCERIA".equals(d.escopo()) ? null : eventoId.toString(), d.cota(),
                    "PARCERIA".equals(d.escopo()) ? parceriaId.toString() : null), null);
            enviados.add(d.id());
            switch (d.destino()) {
                case SO_ENVIAR -> {
                }
                case TENTAR_APROVAR -> {
                    try {
                        servico.mudarStatus(rbs, criado.id(), "APROVAR", null, null);
                    } catch (RuntimeException recusada) {
                        String frase = fraseDaRecusa(recusada);
                        recusas.add(d.id() + ": " + frase);
                        log.info("Atlas: a aprovação do documento {} foi recusada, como esperado: {}", d.id(), frase);
                    }
                }
                case INDEXAR, INDEXAR_E_REVOGAR -> {
                    servico.mudarStatus(rbs, criado.id(), "APROVAR", null, null);
                    String status = ingestao.aguardar(criado.id(), PRAZO_POR_DOCUMENTO);
                    if (!"INDEXADO".equals(status)) {
                        log.warn("Atlas: o documento {} ({}) ficou {} em vez de INDEXADO: {}", d.id(), d.titulo(), status,
                                repositorio.buscar(criado.id()).map(l -> l.erro()).orElse("?"));
                    } else if (d.destino() == Destino.INDEXAR_E_REVOGAR) {
                        servico.mudarStatus(rbs, criado.id(), "REVOGAR", "Circular revogada (documento de exemplo).", null);
                    }
                }
            }
        }
        for (Documento d : KitDeDocumentos.DOCUMENTOS) {
            jdbc.sql("""
                            SELECT dc.status FROM documento dc JOIN evento e ON e.id = dc.evento_id
                            WHERE dc.titulo = :titulo AND dc.chave = :chave AND dc.versao = :versao AND e.edicao = :edicao
                            ORDER BY dc.id DESC LIMIT 1
                            """)
                    .param("titulo", d.titulo()).param("chave", d.chave()).param("versao", d.versao()).param("edicao", d.edicao())
                    .query(String.class).optional().ifPresent(status -> estados.put(d.id(), status));
        }
        return new Resultado(estados, enviados, recusas);
    }

    /** A frase da regra do banco (o gatilho), se a recusa veio dele; senão, a mensagem da exceção. */
    static String fraseDaRecusa(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause() == atual ? null : atual.getCause()) {
            if (atual instanceof PSQLException psql && psql.getServerErrorMessage() != null && psql.getServerErrorMessage().getMessage() != null) {
                return psql.getServerErrorMessage().getMessage();
            }
        }
        return erro.getMessage();
    }
}
