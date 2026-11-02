package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O grupo do documento (edição, escopo, cota, parceria, chave) e a versão dentro dele: documentos de grupos diferentes nunca se
 * substituem. Bloqueante: "anexos de duas parcerias com a mesma chave convivem; publicar um não toca o outro; o D10 nasce v1; dois
 * GLOBAL iguais são recusados pelo índice". Usa o kit confirmado no banco; cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class DocumentoGrupoTest extends ApoioDeDocumentos {

    @Autowired
    IngestaoService ingestao;

    private Map<String, String> anexoDe(String cliente) {
        Map<String, String> campos = global("Anexo da parceria · " + cliente, "anexo-parceria");
        campos.remove("eventoId");
        campos.put("tipo", "ANEXO_PARCERIA");
        campos.put("escopo", "PARCERIA");
        campos.put("parceriaId", String.valueOf(parceria(cliente)));
        return campos;
    }

    @Test
    void osAnexosDeDuasParceriasComAMesmaChaveConvivemNaVersao1() {
        assertThat(doKit("D06")).isNotEqualTo(doKit("D07"));
        assertThat(statusDe(doKit("D06"))).isEqualTo("INDEXADO");
        assertThat(statusDe(doKit("D07"))).isEqualTo("INDEXADO");
        assertThat(contar("SELECT versao FROM documento WHERE id = ?", doKit("D06"))).isEqualTo(1);
        assertThat(contar("SELECT versao FROM documento WHERE id = ?", doKit("D07"))).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM documento WHERE chave = 'anexo-parceria' AND status = 'INDEXADO'")).isEqualTo(2);
    }

    @Test
    void publicarUmNovoAnexoDoAmareloNaoTocaNoAnexoDoRoxo() {
        SessaoDeApi rbs = entrar(RBS);
        long d06 = doKit("D06");
        long d07 = doKit("D07");
        long novo = enviarOk(rbs, "anexo-v2.pdf", KitDeDocumentos.por("D06").conteudo(), anexoDe("Cliente Amarelo"));
        assertThat(contar("SELECT versao FROM documento WHERE id = ?", novo)).as("a v2 do grupo do Amarelo").isEqualTo(2);
        long trechosDoRoxo = contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", d07);

        acao(rbs, novo, "APROVAR");
        ingestao.processar(novo);

        assertThat(statusDe(novo)).isEqualTo("INDEXADO");
        assertThat(statusDe(d06)).as("o anexo anterior do Amarelo").isEqualTo("SUBSTITUIDO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", d06)).isZero();
        assertThat(statusDe(d07)).as("o anexo do Roxo não foi tocado").isEqualTo("INDEXADO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", d07)).isEqualTo(trechosDoRoxo).isGreaterThan(0);
    }

    @Test
    void publicarUmNovoManualDe2027NaoTocaNoManualDe2026OD10NasceNaVersao1() {
        SessaoDeApi rbs = entrar(RBS);
        long d10 = doKit("D10");
        assertThat(contar("SELECT versao FROM documento WHERE id = ?", d10)).as("o manual de 2026 nasceu v1 apesar de a chave ter v1 e v2 em 2027").isEqualTo(1);
        Map<String, String> campos = global("Manual de Operações 2027", "manual-operacoes");
        long v3 = enviarOk(rbs, "manual-v3.pdf", KitDeDocumentos.por("D02").conteudo(), campos);
        assertThat(contar("SELECT versao FROM documento WHERE id = ?", v3)).as("depois da v1 e da v2 de 2027").isEqualTo(3);

        acao(rbs, v3, "APROVAR");
        ingestao.processar(v3);

        assertThat(statusDe(v3)).isEqualTo("INDEXADO");
        assertThat(statusDe(doKit("D02"))).as("a v2 de 2027 foi substituída").isEqualTo("SUBSTITUIDO");
        assertThat(statusDe(d10)).as("o manual de 2026 segue no ar").isEqualTo("INDEXADO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", d10)).isGreaterThan(0);
    }

    @Test
    void oMesmoPlanoEmOutraCotaNaoEOutraVersaoDoMesmoPlano() {
        SessaoDeApi rbs = entrar(RBS);
        Map<String, String> campos = global("Plano comercial · Cota Master 2027", "plano-comercial");
        campos.put("tipo", "PLANO_COMERCIAL");
        campos.put("escopo", "COTA");
        campos.put("cota", "SUBCOTA_G");
        long novo = enviarOk(rbs, "plano.pdf", KitDeDocumentos.por("D04").conteudo(), campos);

        acao(rbs, novo, "APROVAR");
        ingestao.processar(novo);

        assertThat(contar("SELECT versao FROM documento WHERE id = ?", novo)).isEqualTo(2);
        assertThat(statusDe(doKit("D04"))).as("o plano anterior da Subcota G").isEqualTo("SUBSTITUIDO");
        assertThat(statusDe(doKit("D03"))).as("o plano da Master").isEqualTo("INDEXADO");
        assertThat(statusDe(doKit("D05"))).as("o plano da Subcota").isEqualTo("INDEXADO");
    }

    @Test
    void oBancoRecusaDoisDocumentosGlobaisIguaisMesmoComACotaEAParceriaNulas() {
        // Sem NULLS NOT DISTINCT dois GLOBAL iguais passariam, porque a cota e a parceria são nulas. O arquivo é outro, para só o índice do grupo falhar.
        long arquivoNovo = jdbc.sql("""
                        INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por)
                        VALUES ('DOCUMENTO', 'copia.pdf', 'application/pdf', 10, 'teste/' || gen_random_uuid() || '.pdf',
                                (SELECT id FROM usuario WHERE email = 'b2b@rbs.example'))
                        RETURNING id
                        """).query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
                        SELECT d.titulo, d.chave, d.versao, d.tipo, d.escopo, d.evento_id, :arquivo, d.enviado_por FROM documento d WHERE d.id = :id
                        """).param("arquivo", arquivoNovo).param("id", doKit("D02")).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_documento_grupo_versao");
    }
}
