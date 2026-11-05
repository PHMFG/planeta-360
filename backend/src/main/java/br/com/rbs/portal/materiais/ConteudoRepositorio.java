package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.materiais.MateriaisDtos.LinkDeMidiaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do texto e do link enviados numa entrega (rota 86) e dos links de mídia da parceria (rota 87). [ISOLAMENTO] Nenhum método daqui
 * confere o acesso: quem chama passa antes pelo VerificadorDeAcesso. A tabela entrega_conteudo nunca é alterada: só há INSERT.
 */
@Repository
class ConteudoRepositorio {

    /** Quantos links de cada origem a rota 87 devolve, no máximo (os mais recentes). */
    static final int LIMITE_POR_ORIGEM = 1000;

    record Gravado(Long id, OffsetDateTime enviadoEm) {
    }

    private final JdbcClient jdbc;

    ConteudoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A situação da parceria (ATIVA, ENCERRADA...). */
    Optional<String> situacaoDaParceria(long parceriaId) {
        return jdbc.sql("SELECT situacao FROM parceria WHERE id = :id").param("id", parceriaId).query(String.class).optional();
    }

    /**
     * O número da próxima versão da entrega: depois da maior versão de arquivo e de todas as linhas (arquivos e conteúdos) que ela já
     * tem. Quem chama travou a entrega (FOR UPDATE), então dois envios juntos não pegam o mesmo número.
     */
    int proximaVersao(long entregaId) {
        return jdbc.sql("""
                        SELECT greatest(coalesce((SELECT max(a.versao) FROM arquivo a WHERE a.entrega_id = :id), 0),
                                        (SELECT count(*) FROM arquivo a WHERE a.entrega_id = :id)
                                        + (SELECT count(*) FROM entrega_conteudo c WHERE c.entrega_id = :id)) + 1
                        """)
                .param("id", entregaId).query(Integer.class).single();
    }

    /** Grava o texto ou o link. A data é a do relógio (não a do início da transação), para a ordem das versões não empatar. */
    Gravado inserir(long entregaId, long parceriaId, String tipo, String titulo, String texto, String url, String observacao,
                    long enviadoPor) {
        return jdbc.sql("""
                        INSERT INTO entrega_conteudo (entrega_id, parceria_id, tipo, titulo, texto, url, observacao, enviado_por, enviado_em)
                        VALUES (:entregaId, :parceriaId, :tipo, :titulo, :texto, :url, :observacao, :enviadoPor, clock_timestamp())
                        RETURNING id, enviado_em
                        """)
                .param("entregaId", entregaId).param("parceriaId", parceriaId).param("tipo", tipo).param("titulo", titulo)
                .param("texto", texto).param("url", url).param("observacao", observacao).param("enviadoPor", enviadoPor)
                .query(Gravado.class).single();
    }

    private record LinhaDaCarga(Long id, OffsetDateTime exibidoEm, String veiculo, String formato, String espacoPrograma, String link) {
    }

    /** Os links da veiculação da parceria (coluna veiculacao.link), os mais recentes primeiro. O SQL só adianta o filtro: a regra é a do HostDoLink. */
    List<LinkDeMidiaDto> linksDaCarga(long parceriaId) {
        return jdbc.sql("""
                        SELECT v.id, v.exibido_em, ap.veiculo, ap.formato, ap.espaco_programa, v.link
                        FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                        WHERE ap.parceria_id = :parceriaId AND ap.categoria = 'MIDIA' AND v.link ~ '^https?://'
                        ORDER BY v.exibido_em DESC, v.id DESC
                        LIMIT :limite
                        """)
                .param("parceriaId", parceriaId).param("limite", LIMITE_POR_ORIGEM)
                .query(LinhaDaCarga.class).list().stream()
                .filter(l -> HostDoLink.valido(l.link()))
                .map(l -> new LinkDeMidiaDto("CARGA", l.link(), HostDoLink.de(l.link()), Datas.emBrasilia(l.exibidoEm()), l.veiculo(),
                        l.formato(), l.espacoPrograma(), null, null, null, null, null))
                .toList();
    }

    private record LinhaDeEnvio(Long entregaId, String entregaTitulo, String url, String titulo, String observacao,
                                OffsetDateTime enviadoEm, Long usuarioId, String usuarioNome, String usuarioPerfil) {
    }

    /** Os links enviados como conteúdo nas entregas de Mídia da parceria, os mais recentes primeiro. */
    List<LinkDeMidiaDto> linksEnviados(long parceriaId) {
        return jdbc.sql("""
                        SELECT c.entrega_id, e.titulo AS entrega_titulo, c.url, c.titulo, c.observacao, c.enviado_em,
                               u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil
                        FROM entrega_conteudo c
                        JOIN entrega e ON e.id = c.entrega_id AND e.parceria_id = c.parceria_id
                        JOIN aba ab ON ab.id = e.aba_id
                        JOIN usuario u ON u.id = c.enviado_por
                        WHERE c.parceria_id = :parceriaId AND c.tipo = 'LINK' AND ab.codigo = 'MIDIA' AND c.url ~ '^https?://'
                        ORDER BY c.enviado_em DESC, c.id DESC
                        LIMIT :limite
                        """)
                .param("parceriaId", parceriaId).param("limite", LIMITE_POR_ORIGEM)
                .query(LinhaDeEnvio.class).list().stream()
                .filter(l -> HostDoLink.valido(l.url()))
                .map(l -> new LinkDeMidiaDto("ENVIO", l.url(), HostDoLink.de(l.url()), Datas.emBrasilia(l.enviadoEm()), null, null, null,
                        l.entregaId(), l.entregaTitulo(), l.titulo(), l.observacao(),
                        new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil())))
                .toList();
    }
}
