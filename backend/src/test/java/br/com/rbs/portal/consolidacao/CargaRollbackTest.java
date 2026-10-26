package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Prova que a carga é tudo ou nada: se a função do banco falhar, ou se o arquivo der erro no meio da leitura, não ficam a
 * carga, as linhas da área de passagem, o registro do arquivo nem o arquivo em disco. Atende o item 5 da Entrega 2.
 * [CARGA] Este teste NÃO usa @Transactional (precisa de transações de verdade, que dão rollback de verdade). Nada é commitado
 * pelas cargas; só o login da RBS grava o histórico, e o gatilho de mentira é apagado no fim.
 */
@TesteDeIntegracao
class CargaRollbackTest extends ApoioDeCargas {

    private static final Path PASTA_DA_RBS = Path.of("target", "armazenamento-teste", "geral").toAbsolutePath();

    /** Tudo o que uma carga mexe, para comparar antes e depois. */
    private record Foto(long cargas, long arquivos, long linhasDePassagem, long rejeicoes, long veiculacoes, long historicoDeCarga,
                        long arquivosEmDisco) {
    }

    Foto fotografar() {
        return new Foto(contar("SELECT count(*) FROM carga"), contar("SELECT count(*) FROM arquivo"),
                contar("SELECT count(*) FROM stg_veiculacao"), contar("SELECT count(*) FROM carga_rejeicao"),
                contar("SELECT count(*) FROM veiculacao"), contar("SELECT count(*) FROM historico WHERE acao = 'CARGA'"),
                arquivosEmDisco());
    }

    private static long arquivosEmDisco() {
        if (!Files.isDirectory(PASTA_DA_RBS)) {
            return 0;
        }
        try (Stream<Path> arquivos = Files.list(PASTA_DA_RBS)) {
            return arquivos.count();
        } catch (IOException erro) {
            throw new IllegalStateException(erro);
        }
    }

    @Test
    void seAFuncaoDoBancoFalharNadaFicaPelaMetade() {
        SessaoDeApi rbs = entrar(RBS);
        // Um gatilho de mentira recusa toda veiculação inserida: a função falha DEPOIS de a carga e as linhas já estarem no banco.
        jdbc.sql("""
                CREATE FUNCTION tmp_recusa_veiculacao() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Veiculação recusada pelo teste.' USING ERRCODE = 'check_violation';
                END $$""").update();
        jdbc.sql("CREATE TRIGGER tmp_recusa BEFORE INSERT ON veiculacao FOR EACH ROW EXECUTE FUNCTION tmp_recusa_veiculacao()")
                .update();
        try {
            Foto antes = fotografar();

            MvcTestResult resposta = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);

            assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
            // [VALIDACAO] A recusa do banco sai como 409 com frase em português, sem o texto cru do banco.
            assertThat((String) lerJson(resposta, "$.detail")).doesNotContain("tmp_recusa").doesNotContain("ERROR");
            assertThat(fotografar()).isEqualTo(antes);
        } finally {
            jdbc.sql("DROP TRIGGER tmp_recusa ON veiculacao").update();
            jdbc.sql("DROP FUNCTION tmp_recusa_veiculacao()").update();
        }
    }

    @Test
    void erroNoMeioDaLeituraDesfazOQueJaTinhaSidoGravadoNaAreaDePassagem() {
        SessaoDeApi rbs = entrar(RBS);
        // 1.500 linhas boas e, no fim, um link grande demais: a API grava o 1º lote de 1.000 linhas e só então descobre o erro.
        StringBuilder csv = new StringBuilder(CABECALHO_VEICULACAO).append('\n');
        for (int i = 0; i < 1500; i++) {
            csv.append("Cliente Amarelo,Rádio 92,\"Chamadas assinadas 5\"\"\",Indeterminado,2027-01-01T00:00:00-03:00,1,\n");
        }
        csv.append("Cliente Amarelo,Rádio 92,Chamadas,,2027-01-01T00:00:00-03:00,1,").append("x".repeat(501)).append('\n');
        Foto antes = fotografar();

        MvcTestResult resposta = enviar(rbs, "grande.csv", texto(csv.toString()), "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resposta, "$.campos.arquivo")).contains("Linha 1502").contains("link");
        assertThat(fotografar()).isEqualTo(antes);
    }
}
