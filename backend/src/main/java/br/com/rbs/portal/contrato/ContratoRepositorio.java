package br.com.rbs.portal.contrato;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL do contrato (rotas 74 a 76). [CONTRATO] Quem decide a transação é o service. */
@Repository
public class ContratoRepositorio {

    /** A linha de {@code contrato} da parceria, com quem informou o signatário. */
    public record Linha(Long id, String signatarioNome, String signatarioEmail, String signatarioCargo, Long informadoPor,
                        String informadoPorNome, String informadoPorPerfil, OffsetDateTime informadoEm, Long arquivoId, LocalDate assinadoEm) {
    }

    /** Uma versão do contrato. */
    public record Versao(Long id, Integer versao, String nomeOriginal, String mimeType, Long tamanhoBytes, Long enviadoPor,
                         String enviadoPorNome, String enviadoPorPerfil, OffsetDateTime enviadoEm, String observacao) {
    }

    private final JdbcClient jdbc;

    public ContratoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Trava a parceria (serializa dois envios do mesmo contrato) e devolve a situação dela; vazio = a parceria não existe. */
    public Optional<String> travarParceria(long parceriaId) {
        return jdbc.sql("SELECT situacao FROM parceria WHERE id = :id FOR UPDATE").param("id", parceriaId).query(String.class).optional();
    }

    public Optional<Linha> buscar(long parceriaId) {
        return jdbc.sql("""
                        SELECT c.id, c.signatario_nome, c.signatario_email, c.signatario_cargo, c.informado_por,
                               u.nome AS informado_por_nome, u.perfil AS informado_por_perfil, c.informado_em, c.arquivo_id, c.assinado_em
                        FROM contrato c LEFT JOIN usuario u ON u.id = c.informado_por
                        WHERE c.parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId).query(Linha.class).optional();
    }

    /** Todos os arquivos CONTRATO da aba Contrato desta parceria (os avulsos e o da entrega da amostra), do mais novo ao mais antigo. */
    public List<Versao> versoes(long parceriaId) {
        return jdbc.sql("""
                        SELECT a.id, a.versao, a.nome_original, a.mime_type, a.tamanho_bytes, a.enviado_por,
                               u.nome AS enviado_por_nome, u.perfil AS enviado_por_perfil, a.enviado_em, a.observacao
                        FROM arquivo a JOIN aba ab ON ab.id = a.aba_id AND ab.codigo = 'CONTRATO'
                        JOIN usuario u ON u.id = a.enviado_por
                        WHERE a.parceria_id = :parceriaId AND a.categoria = 'CONTRATO'
                        ORDER BY a.enviado_em DESC, a.id DESC
                        """)
                .param("parceriaId", parceriaId).query(Versao.class).list();
    }

    public int proximaVersao(long parceriaId) {
        return jdbc.sql("""
                        SELECT coalesce(max(a.versao), 0) + 1 FROM arquivo a JOIN aba ab ON ab.id = a.aba_id AND ab.codigo = 'CONTRATO'
                        WHERE a.parceria_id = :parceriaId AND a.categoria = 'CONTRATO'
                        """)
                .param("parceriaId", parceriaId).query(Integer.class).single();
    }

    /** Grava o signatário (cria a linha do contrato se ela ainda não existe) e devolve o id do contrato. */
    public long gravarSignatario(long parceriaId, String nome, String email, String cargo, long informadoPor) {
        return jdbc.sql("""
                        INSERT INTO contrato (parceria_id, signatario_nome, signatario_email, signatario_cargo, informado_por, informado_em)
                        VALUES (:parceriaId, :nome, :email, :cargo, :por, now())
                        ON CONFLICT (parceria_id) DO UPDATE SET signatario_nome = EXCLUDED.signatario_nome,
                            signatario_email = EXCLUDED.signatario_email, signatario_cargo = EXCLUDED.signatario_cargo,
                            informado_por = EXCLUDED.informado_por, informado_em = EXCLUDED.informado_em
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId).param("nome", nome).param("email", email).param("cargo", cargo).param("por", informadoPor)
                .query(Long.class).single();
    }

    /** Aponta o contrato para a versão nova (cria a linha se preciso). A data é mantida quando o envio não traz outra. */
    public long apontarArquivo(long parceriaId, long arquivoId, LocalDate assinadoEm) {
        return jdbc.sql("""
                        INSERT INTO contrato (parceria_id, arquivo_id, assinado_em) VALUES (:parceriaId, :arquivoId, :assinadoEm)
                        ON CONFLICT (parceria_id) DO UPDATE SET arquivo_id = EXCLUDED.arquivo_id,
                            assinado_em = coalesce(EXCLUDED.assinado_em, contrato.assinado_em)
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId).param("arquivoId", arquivoId).param("assinadoEm", assinadoEm)
                .query(Long.class).single();
    }

    /** O arquivo gravado pelo Armazenamento entra com versão 1; aqui ela vira a do contrato, com a observação de quem enviou. */
    public void numerarVersao(long arquivoId, int versao, String observacao) {
        jdbc.sql("UPDATE arquivo SET versao = :versao, observacao = :observacao WHERE id = :id")
                .param("versao", versao).param("observacao", observacao).param("id", arquivoId).update();
    }
}
