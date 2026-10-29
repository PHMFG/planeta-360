package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.materiais.Arquivo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o Armazenamento direto (o que a carga de planilhas vai usar): grava bytes, devolve o registro e confere o arquivo.
 * Atende o item 4 da Entrega 2. [UPLOAD] Cada teste é desfeito no fim (@Transactional), e o arquivo em disco some junto.
 */
@TesteDeIntegracao
@Transactional
class ArmazenamentoTest {

    @Autowired
    Armazenamento armazenamento;

    @Autowired
    JdbcClient jdbc;

    long rbs() {
        return jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    @Test
    void gravarArquivoGeralDevolveORegistroComHashTamanhoETipo() throws Exception {
        byte[] csv = "data;veiculo;quantidade\n2027-02-01;Atlântida FM;3\n".getBytes(StandardCharsets.UTF_8);

        Arquivo gravado = armazenamento.gravar(csv, "veiculacao fevereiro.csv", "RELATORIO_VEICULACAO", rbs(), null);

        assertThat(gravado.id()).isPositive();
        assertThat(gravado.parceriaId()).as("arquivo geral da RBS").isNull();
        assertThat(gravado.entregaId()).isNull();
        assertThat(gravado.abaId()).isNull();
        assertThat(gravado.categoria()).isEqualTo("RELATORIO_VEICULACAO");
        assertThat(gravado.nomeOriginal()).isEqualTo("veiculacao fevereiro.csv");
        assertThat(gravado.mimeType()).isEqualTo("text/csv");
        assertThat(gravado.tamanhoBytes()).isEqualTo(csv.length);
        assertThat(gravado.versao()).isEqualTo(1);
        assertThat(gravado.enviadoPor()).isEqualTo(rbs());
        assertThat(gravado.enviadoEm()).isNotNull();
        assertThat(gravado.hashSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)));
        assertThat(gravado.caminho()).matches("geral/[0-9a-f-]{36}\\.csv");

        Path emDisco = armazenamento.localizar(gravado.caminho()).orElseThrow();
        assertThat(Files.readAllBytes(emDisco)).isEqualTo(csv);
        assertThat(jdbc.sql("SELECT count(*) FROM arquivo WHERE id = :id").param("id", gravado.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void gravarArquivoDeUmaParceriaSemEntregaGuardaOCaminhoDaParceria() {
        Arquivo gravado = armazenamento.gravar("%PDF-1.4 x".getBytes(StandardCharsets.US_ASCII), "plano.pdf", "PLANO_COMERCIAL", rbs(),
                amarelo());

        assertThat(gravado.parceriaId()).isEqualTo(amarelo());
        assertThat(gravado.caminho()).startsWith("parcerias/" + amarelo() + "/").endsWith(".pdf");
    }

    @Test
    void gravarArquivoAvulsoComAbaGuardaAParceriaEAAbaSemEntrega() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

        Arquivo gravado = armazenamento.gravar(png, "foto.png", "COMPROVACAO", rbs(), amarelo(), "APLICACOES");
        Arquivo semAba = armazenamento.gravar(png, "outra.png", "COMPROVACAO", rbs(), amarelo());

        assertThat(gravado.parceriaId()).isEqualTo(amarelo());
        assertThat(gravado.abaId()).as("a aba APLICACOES, para o download conferir se ela está aberta")
                .isEqualTo(jdbc.sql("SELECT id FROM aba WHERE codigo = 'APLICACOES'").query(Integer.class).single());
        assertThat(gravado.entregaId()).isNull();
        assertThat(gravado.categoria()).isEqualTo("COMPROVACAO");
        assertThat(gravado.mimeType()).isEqualTo("image/png");
        assertThat(gravado.caminho()).startsWith("parcerias/" + amarelo() + "/").endsWith(".png");
        assertThat(armazenamento.localizar(gravado.caminho())).isPresent();
        assertThat(semAba.abaId()).as("a versão sem aba continua como era").isNull();
    }

    @Test
    void gravarComAbaConfereOArquivoELevaErroDeQuemChamaSeAAbaNaoServe() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        long arquivosAntes = jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single();

        assertThatThrownBy(() -> armazenamento.gravar(png, "foto.png", "COMPROVACAO", rbs(), amarelo(), "NAO_EXISTE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NAO_EXISTE");
        assertThatThrownBy(() -> armazenamento.gravar(png, "foto.png", "COMPROVACAO", rbs(), null, "APLICACOES"))
                .isInstanceOf(IllegalArgumentException.class);
        // O conteúdo continua sendo conferido (um executável renomeado para .png é recusado) e nada fica gravado.
        assertThatThrownBy(() -> armazenamento.gravar(new byte[]{'M', 'Z', 0, 0}, "foto.png", "COMPROVACAO", rbs(), amarelo(), "APLICACOES"))
                .isInstanceOf(ArquivoRecusadoException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single()).isEqualTo(arquivosAntes);
    }

    @Test
    void gravarNaEntregaUsaAParceriaEAAbaDelaECalculaAVersao() {
        long entrega = jdbc.sql("SELECT id FROM entrega WHERE parceria_id = :p AND titulo = 'Logo final para aplicações no evento'")
                .param("p", amarelo()).query(Long.class).single();

        Arquivo v3 = armazenamento.gravarNaEntrega("%PDF-1.4 a".getBytes(StandardCharsets.US_ASCII), "logo_final.pdf", "MATERIAL_MARCA", rbs(), entrega);
        Arquivo v4 = armazenamento.gravarNaEntrega("%PDF-1.4 b".getBytes(StandardCharsets.US_ASCII), "logo_final.pdf", "MATERIAL_MARCA", rbs(), entrega);

        assertThat(v3.versao()).as("a amostra já tem as versões 1 e 2").isEqualTo(3);
        assertThat(v4.versao()).isEqualTo(4);
        assertThat(v3.entregaId()).isEqualTo(entrega);
        assertThat(v3.parceriaId()).isEqualTo(amarelo());
        assertThat(v3.abaId()).isEqualTo(jdbc.sql("SELECT aba_id FROM entrega WHERE id = :id").param("id", entrega).query(Integer.class).single());
        assertThat(v3.caminho()).isNotEqualTo(v4.caminho());
    }

    @Test
    void gravarNaEntregaQueNaoExisteDa404() {
        assertThatThrownBy(() -> armazenamento.gravarNaEntrega("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "a.pdf", "OUTRO", rbs(), 999999))
                .isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void arquivoRecusadoNaoVaiParaODiscoNemParaOBanco() throws IOException {
        long antes = jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single();
        byte[] executavel = {'M', 'Z', 0, 0};

        assertThatThrownBy(() -> armazenamento.gravar(executavel, "falso.pdf", "OUTRO", rbs(), null))
                .isInstanceOfSatisfying(ArquivoRecusadoException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> armazenamento.gravar(new byte[21 * 1024 * 1024], "grande.pdf", "OUTRO", rbs(), null))
                .isInstanceOfSatisfying(ArquivoRecusadoException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        assertThatThrownBy(() -> armazenamento.gravar(new byte[0], "vazio.pdf", "OUTRO", rbs(), null))
                .isInstanceOf(DadosInvalidosException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM arquivo").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void localizarNuncaSaiDaPastaDeArmazenamento() {
        assertThat(armazenamento.localizar("../pom.xml")).isEmpty();
        assertThat(armazenamento.localizar("../../../../etc/hosts")).isEmpty();
        assertThat(armazenamento.localizar("/etc/hosts")).isEmpty();
        assertThat(armazenamento.localizar("nao/existe.pdf")).isEmpty();
    }

    @Test
    void nomeOriginalFicaSoComOFimDoCaminhoSemControlesECabeNoBanco() {
        assertThat(Armazenamento.limparNome("C:\\fotos\\logo.png")).isEqualTo("logo.png");
        assertThat(Armazenamento.limparNome("../../etc/a.pdf")).isEqualTo("a.pdf");
        assertThat(Armazenamento.limparNome("  boa\tnoite\n.pdf ")).isEqualTo("boanoite.pdf");
        assertThat(Armazenamento.limparNome(null)).isEmpty();
        String longo = Armazenamento.limparNome("n".repeat(400) + ".pdf");
        assertThat(longo).hasSize(255).endsWith(".pdf");
    }
}
