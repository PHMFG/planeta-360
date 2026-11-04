package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do plano comercial e dos espaços de documento da marca (rotas 69 a 73). [ISOLAMENTO] Nenhum método confere o acesso do
 * usuário: o EspacosService passa antes pelo VerificadorDeAcesso.
 */
@Repository
public class EspacosRepositorio {

    private final JdbcClient jdbc;

    public EspacosRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A situação da parceria. {@code travar} = FOR UPDATE: quem numera ou versiona em série espera a vez. */
    Optional<String> situacaoDaParceria(long parceriaId, boolean travar) {
        return jdbc.sql("SELECT situacao FROM parceria WHERE id = :id" + (travar ? " FOR UPDATE" : ""))
                .param("id", parceriaId).query(String.class).optional();
    }

    private record LinhaDeVersao(Long id, String nomeOriginal, String mimeType, Long tamanhoBytes, Integer versao, String categoria,
                                 OffsetDateTime enviadoEm, String observacao, Long usuarioId, String usuarioNome, String usuarioPerfil) {
    }

    /** As versões do arquivo avulso (sem entrega) da parceria nessa categoria, da mais nova para a mais antiga. */
    List<ArquivoDto> versoesAvulsas(long parceriaId, String categoria) {
        return jdbc.sql("""
                        SELECT a.id, a.nome_original, a.mime_type, a.tamanho_bytes, a.versao, a.categoria, a.enviado_em, a.observacao,
                               u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil
                        FROM arquivo a JOIN usuario u ON u.id = a.enviado_por
                        WHERE a.parceria_id = :parceriaId AND a.categoria = :categoria AND a.entrega_id IS NULL
                        ORDER BY a.versao DESC, a.id DESC
                        """)
                .param("parceriaId", parceriaId)
                .param("categoria", categoria)
                .query(LinhaDeVersao.class)
                .list().stream()
                .map(l -> new ArquivoDto(l.id(), null, l.nomeOriginal(), l.mimeType(), l.tamanhoBytes(), l.versao(), l.categoria(),
                        new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil()), Datas.emBrasilia(l.enviadoEm()),
                        l.observacao()))
                .toList();
    }

    /** Os ids das entregas da parceria com este papel, na ordem de criação (a "Solicitação de ingressos N" segue esta ordem). */
    List<Long> entregasDoPapel(long parceriaId, String papel) {
        return jdbc.sql("SELECT id FROM entrega WHERE parceria_id = :parceriaId AND papel = :papel ORDER BY criado_em, id")
                .param("parceriaId", parceriaId).param("papel", papel).query(Long.class).list();
    }

    /** Quantas solicitações de ingressos a parceria já tem (o número da próxima é este mais 1; quem chama travou a parceria). */
    long contarEntregasDoPapel(long parceriaId, String papel) {
        return jdbc.sql("SELECT count(*) FROM entrega WHERE parceria_id = :parceriaId AND papel = :papel")
                .param("parceriaId", parceriaId).param("papel", papel).query(Long.class).single();
    }

    /**
     * Cria o espaço único (MIV ou logo) se ainda não existe. Dois primeiros envios ao mesmo tempo: o segundo INSERT espera o primeiro
     * terminar e então não faz nada (ON CONFLICT DO NOTHING), em vez de dar erro. Devolve verdadeiro se este chamador foi quem criou.
     */
    boolean criarEspacoUnico(long parceriaId, int abaId, String papel, String titulo) {
        return jdbc.sql("""
                        INSERT INTO entrega (parceria_id, aba_id, papel, titulo, status)
                        VALUES (:parceriaId, :abaId, :papel, :titulo, 'AGUARDANDO_ENVIO')
                        ON CONFLICT DO NOTHING
                        """)
                .param("parceriaId", parceriaId).param("abaId", abaId).param("papel", papel).param("titulo", titulo)
                .update() == 1;
    }

    /** O id do espaço único da parceria, travado (FOR UPDATE): os envios seguintes esperam um pelo outro, e a versão sai sem repetir. */
    Optional<Long> travarEspacoUnico(long parceriaId, String papel) {
        return jdbc.sql("SELECT id FROM entrega WHERE parceria_id = :parceriaId AND papel = :papel FOR UPDATE")
                .param("parceriaId", parceriaId).param("papel", papel).query(Long.class).optional();
    }
}
