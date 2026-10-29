package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Armazenamento;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 26 a 28 (cargas de planilha CSV) como o front as usa: login pela rota, sessão de verdade, multipart e token
 * CSRF. Atende o item 5 da Entrega 2. [CARGA] [DUPLICIDADE] [PERMISSAO] [HISTORICO] Os números esperados são conferidos por
 * SQL direto nas tabelas veiculacao, pesquisa_resultado, carga e carga_rejeicao. Cada teste é desfeito no fim
 * (@Transactional). A amostra já traz a veiculação e a pesquisa de 2027 carregadas; os testes que precisam do banco "limpo"
 * apagam essas linhas antes (dentro da transação do teste, que é desfeita). O exemplo de veiculação, num banco sem
 * veiculações, tem 12 linhas: 10 entram, 1 é repetida e 1 não tem data (docs/banco.md).
 */
@TesteDeIntegracao
@Transactional
class CargaControllerTest extends ApoioDeCargas {

    @Autowired
    Armazenamento armazenamento;

    /** Banco sem veiculações (as da amostra saem): o exemplo de docs/exemplos entra do zero. */
    void semVeiculacoes() {
        jdbc.sql("DELETE FROM veiculacao").update();
    }

    /** Banco sem resultados de pesquisa (os da amostra saem). */
    void semResultadosDePesquisa() {
        jdbc.sql("DELETE FROM pesquisa_resultado").update();
    }

    // ---------------------------------------------------------------------
    // Rota 26: veiculação
    // ---------------------------------------------------------------------

    @Test
    void rbsEnviaOExemploDeVeiculacaoEAsContagensBatemComOBanco() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);
        byte[] bytes = exemplo("veiculacao.csv");

        MvcTestResult resposta = enviar(rbs, "veiculacao.csv", bytes, "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        // O arquivo tem 12 linhas de dados: 10 novas, 1 repetida dentro do próprio arquivo e 1 sem data.
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("VEICULACAO");
        assertThat(longo(resposta, "$.eventoId")).isEqualTo(evento(2027));
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(12);
        assertThat(inteiro(resposta, "$.linhasInseridas")).isEqualTo(10);
        assertThat(inteiro(resposta, "$.linhasDuplicadas")).isEqualTo(1);
        assertThat(inteiro(resposta, "$.linhasRejeitadas")).isEqualTo(1);
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(
                inteiro(resposta, "$.linhasInseridas") + inteiro(resposta, "$.linhasDuplicadas") + inteiro(resposta, "$.linhasRejeitadas"));
        assertThat(longo(resposta, "$.executadaPor.id")).isEqualTo(rbsId);
        assertThat(lerJson(resposta, "$.executadaPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat((String) lerJson(resposta, "$.executadaEm")).endsWith("-03:00");
        assertThat((String) lerJson(resposta, "$.processadaEm")).endsWith("-03:00");

        // Conferência pelo banco, sem passar pelo que a API respondeu.
        long carga = longo(resposta, "$.id");
        assertThat(contar("SELECT count(*) FROM veiculacao")).isEqualTo(10);
        assertThat(contar("SELECT count(*) FROM veiculacao WHERE carga_id = ?", carga)).isEqualTo(10);
        assertThat(contar("SELECT count(*) FROM stg_veiculacao WHERE carga_id = ?", carga)).isEqualTo(12);
        Map<String, Object> linha = jdbc.sql("SELECT tipo, evento_id, executada_por, linhas_lidas, linhas_inseridas, "
                + "linhas_duplicadas, linhas_rejeitadas, processada_em IS NOT NULL AS processada FROM carga WHERE id = :c")
                .param("c", carga).query().singleRow();
        assertThat(linha.get("tipo")).isEqualTo("MIDIA");   // no banco, a veiculação é MIDIA
        assertThat(((Number) linha.get("linhas_inseridas")).intValue()).isEqualTo(10);
        assertThat(((Number) linha.get("executada_por")).longValue()).isEqualTo(rbsId);
        assertThat(linha.get("processada")).isEqualTo(true);
    }

    @Test
    void aPlanilhaFicaGuardadaComoArquivoDaRbsSemParceriaComAutorEHash() throws Exception {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        byte[] bytes = exemplo("veiculacao.csv");

        MvcTestResult resposta = enviar(rbs, "veiculacao.csv", bytes, "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long arquivoId = longo(resposta, "$.arquivoId");
        Map<String, Object> arquivo = jdbc.sql("""
                        SELECT parceria_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, hash_sha256,
                               enviado_por
                        FROM arquivo WHERE id = :id
                        """).param("id", arquivoId).query().singleRow();
        assertThat(arquivo.get("parceria_id")).as("arquivo geral da RBS").isNull();
        assertThat(arquivo.get("entrega_id")).isNull();
        assertThat(arquivo.get("categoria")).isEqualTo("RELATORIO_VEICULACAO");
        assertThat(arquivo.get("nome_original")).isEqualTo("veiculacao.csv");
        assertThat(arquivo.get("mime_type")).isEqualTo("text/csv");
        assertThat(((Number) arquivo.get("tamanho_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(arquivo.get("hash_sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(((Number) arquivo.get("enviado_por")).longValue()).isEqualTo(contar("SELECT id FROM usuario WHERE email = ?", RBS));
        // [UPLOAD] O nome em disco é gerado pelo servidor, e o conteúdo é o que foi enviado.
        assertThat((String) arquivo.get("caminho")).startsWith("geral/").doesNotContain("veiculacao");
        assertThat(java.nio.file.Files.readAllBytes(armazenamento.localizar((String) arquivo.get("caminho")).orElseThrow()))
                .isEqualTo(bytes);
    }

    @Test
    void reenviarOMesmoArquivoNaoInsereNadaEAsLinhasDeAntesViramDuplicadas() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        byte[] bytes = exemplo("veiculacao.csv");

        MvcTestResult primeiro = enviar(rbs, "veiculacao.csv", bytes, "VEICULACAO", evento(2027), null);
        long totalDepoisDoPrimeiro = contar("SELECT count(*) FROM veiculacao");
        MvcTestResult segundo = enviar(rbs, "veiculacao.csv", bytes, "VEICULACAO", evento(2027), null);

        assertThat(primeiro).hasStatus(HttpStatus.CREATED);
        assertThat(segundo).hasStatus(HttpStatus.CREATED);
        assertThat(longo(segundo, "$.id")).isNotEqualTo(longo(primeiro, "$.id"));
        // [DUPLICIDADE] Nada entra de novo; o que tinha entrado e o que já era repetido contam como duplicada.
        assertThat(inteiro(segundo, "$.linhasInseridas")).isZero();
        assertThat(inteiro(segundo, "$.linhasDuplicadas"))
                .isEqualTo(inteiro(primeiro, "$.linhasInseridas") + inteiro(primeiro, "$.linhasDuplicadas"))
                .isEqualTo(11);
        assertThat(inteiro(segundo, "$.linhasRejeitadas")).isEqualTo(inteiro(primeiro, "$.linhasRejeitadas")).isEqualTo(1);
        assertThat(inteiro(segundo, "$.linhasLidas")).isEqualTo(12);
        // A contagem de veiculação no banco não muda entre o 1º e o 2º envio, e nenhuma linha passa para a carga nova.
        assertThat(contar("SELECT count(*) FROM veiculacao")).isEqualTo(totalDepoisDoPrimeiro).isEqualTo(10);
        assertThat(contar("SELECT count(*) FROM veiculacao WHERE carga_id = ?", longo(segundo, "$.id"))).isZero();
        assertThat(contar("SELECT count(*) FROM veiculacao WHERE carga_id = ?", longo(primeiro, "$.id"))).isEqualTo(10);
    }

    @Test
    void comAAmostraCarregadaOTotalDeVeiculacoesSoCrescePeloQueEntrou() {
        SessaoDeApi rbs = entrar(RBS);
        long antes = contar("SELECT count(*) FROM veiculacao");

        MvcTestResult resposta = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(12);
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(
                inteiro(resposta, "$.linhasInseridas") + inteiro(resposta, "$.linhasDuplicadas") + inteiro(resposta, "$.linhasRejeitadas"));
        assertThat(contar("SELECT count(*) FROM veiculacao")).isEqualTo(antes + inteiro(resposta, "$.linhasInseridas"));
        // O reenvio, agora sim, não muda nada.
        MvcTestResult reenvio = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);
        assertThat(inteiro(reenvio, "$.linhasInseridas")).isZero();
        assertThat(contar("SELECT count(*) FROM veiculacao")).isEqualTo(antes + inteiro(resposta, "$.linhasInseridas"));
    }

    @Test
    void aPlanilhaComBomECrlfEntraIgualAoExemplo() {
        semVeiculacoes();
        String comCrlf = new String(exemplo("veiculacao.csv"), StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n");
        byte[] comBom = ("﻿" + comCrlf).getBytes(StandardCharsets.UTF_8);

        MvcTestResult resposta = enviar(entrar(RBS), "veiculacao.csv", comBom, "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(12);
        assertThat(inteiro(resposta, "$.linhasInseridas")).isEqualTo(10);
        assertThat(inteiro(resposta, "$.linhasRejeitadas")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Rota 28: rejeições
    // ---------------------------------------------------------------------

    @Test
    void asRejeicoesTrazemLinhaCampoEMotivo() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult carga = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);

        MvcTestResult resposta = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(inteiro(resposta, "$.total")).isEqualTo(1);
        assertThat(inteiro(resposta, "$.pagina")).isZero();
        assertThat(inteiro(resposta, "$.tamanho")).isEqualTo(50);
        // A linha sem data é a 13ª linha do arquivo (o cabeçalho é a 1ª).
        assertThat(inteiro(resposta, "$.itens[0].linha")).isEqualTo(13);
        assertThat(lerJson(resposta, "$.itens[0].campo")).isEqualTo("exibido_em");
        assertThat(lerJson(resposta, "$.itens[0].motivo")).isEqualTo("Campo obrigatório vazio ou inválido");
        assertThat((String) lerJson(resposta, "$.itens[0].conteudo")).contains("Cliente Verde");
        // O mesmo que está na tabela carga_rejeicao.
        assertThat(contar("SELECT count(*) FROM carga_rejeicao WHERE carga_id = ? AND linha = 13 AND campo = 'exibido_em'",
                longo(carga, "$.id"))).isEqualTo(1);
    }

    @Test
    void patrocinadorSemParceriaEValoresIlegiveisSaoRejeitadosNaOrdemDasLinhas() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        // Linha 1 = cabeçalho; a linha 5 está em branco (não conta como dado, mas conta no número das linhas seguintes):
        // a linha 6 é a "Rádio 92", a 7 tem quantidade 0 e a 8 é a única boa.
        String csv = CABECALHO_VEICULACAO + "\n"
                + "Cliente Azul,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,2026-12-01T10:00:00-03:00,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,ontem à tarde,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,2026-12-01T11:00:00-03:00,muitas,\n"
                + "\n"
                + "Cliente Amarelo,Rádio 92,Formato que não existe,Indeterminado,2026-12-01T12:00:00-03:00,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,2026-12-01T13:00:00-03:00,0,\n"
                + "Cliente Amarelo,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,2026-12-01T14:00:00-03:00,1,\n";

        MvcTestResult carga = enviar(rbs, "ruins.csv", texto(csv), "VEICULACAO", evento(2027), null);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasLidas")).isEqualTo(6);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(1);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(5);
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
        assertThat(lerLista(rejeicoes, "$.itens[*].linha")).containsExactly(2, 3, 4, 6, 7);
        assertThat(lerLista(rejeicoes, "$.itens[*].campo"))
                .containsExactly("patrocinador", "exibido_em", "quantidade", "formato", "quantidade");
        // [VALIDACAO] Data e quantidade ilegíveis dizem o formato esperado; quantidade zero diz que precisa ser maior que zero.
        assertThat(lerLista(rejeicoes, "$.itens[*].motivo")).containsExactly("Patrocinador sem parceria nesta edição",
                "Data inválida. Use o formato 2026-11-20T10:15:30-03:00 (ano-mês-dia, hora e fuso).",
                "Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).",
                "Aproveitamento não encontrado no plano comercial", "A quantidade deve ser maior que zero.");
        // O texto que não deu para ler aparece no conteúdo da rejeição, como estava na planilha.
        assertThat((String) lerJson(rejeicoes, "$.itens[1].conteudo")).contains("ontem à tarde");
        assertThat((String) lerJson(rejeicoes, "$.itens[2].conteudo")).endsWith("muitas");
        assertThat((String) lerJson(rejeicoes, "$.itens[0].conteudo")).contains("Cliente Azul");
    }

    @Test
    void asRejeicoesSaoPaginadas() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        StringBuilder csv = new StringBuilder(CABECALHO_VEICULACAO).append('\n');
        for (int i = 0; i < 5; i++) {
            csv.append("Cliente Azul,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",,2026-12-01T10:0").append(i).append(":00-03:00,1,\n");
        }
        MvcTestResult carga = enviar(rbs, "azul.csv", texto(csv.toString()), "VEICULACAO", evento(2027), null);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(5);
        String uri = "/api/cargas/" + longo(carga, "$.id") + "/rejeicoes";

        MvcTestResult pagina1 = rbs.get(uri + "?pagina=1&tamanho=2");
        MvcTestResult ultima = rbs.get(uri + "?pagina=2&tamanho=2");
        MvcTestResult alemDoFim = rbs.get(uri + "?pagina=9&tamanho=2");

        assertThat(lerLista(pagina1, "$.itens[*].linha")).containsExactly(4, 5);
        assertThat(inteiro(pagina1, "$.total")).isEqualTo(5);
        assertThat(inteiro(pagina1, "$.pagina")).isEqualTo(1);
        assertThat(inteiro(pagina1, "$.tamanho")).isEqualTo(2);
        assertThat(lerLista(ultima, "$.itens[*].linha")).containsExactly(6);
        assertThat(alemDoFim).hasStatus(HttpStatus.OK);
        assertThat(lerLista(alemDoFim, "$.itens")).isEmpty();
        assertThat(rbs.get(uri + "?tamanho=0")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get(uri + "?tamanho=201")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get(uri + "?pagina=-1")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void cargaQueNaoExisteDaNotFoundParaARbs() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = rbs.get("/api/cargas/999999/rejeicoes");

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta carga.");
    }

    // ---------------------------------------------------------------------
    // Rota 26: pesquisa
    // ---------------------------------------------------------------------

    @Test
    void oExemploDePesquisaEntraEOReenvioNaoDuplica() {
        semResultadosDePesquisa();
        SessaoDeApi rbs = entrar(RBS);
        byte[] bytes = exemplo("pesquisa.csv");

        MvcTestResult primeiro = enviar(rbs, "pesquisa.csv", bytes, "PESQUISA", evento(2027), null);
        long totalDepoisDoPrimeiro = contar("SELECT count(*) FROM pesquisa_resultado");
        MvcTestResult segundo = enviar(rbs, "pesquisa.csv", bytes, "PESQUISA", evento(2027), "ATIVACAO");

        // 11 linhas: 9 entram, 1 é a repetida com o apelido "Amarelo" e 1 tem percentual de 140.
        assertThat(primeiro).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(primeiro, "$.tipo")).isEqualTo("PESQUISA");
        assertThat(inteiro(primeiro, "$.linhasLidas")).isEqualTo(11);
        assertThat(inteiro(primeiro, "$.linhasInseridas")).isEqualTo(9);
        assertThat(inteiro(primeiro, "$.linhasDuplicadas")).isEqualTo(1);
        assertThat(inteiro(primeiro, "$.linhasRejeitadas")).isEqualTo(1);
        assertThat(totalDepoisDoPrimeiro).isEqualTo(9);
        assertThat(contar("SELECT count(*) FROM pesquisa_resultado WHERE carga_id = ?", longo(primeiro, "$.id"))).isEqualTo(9);
        // O reenvio não duplica nada: as 9 linhas e a repetida viram duplicadas.
        assertThat(segundo).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(segundo, "$.linhasInseridas")).isZero();
        assertThat(inteiro(segundo, "$.linhasDuplicadas")).isEqualTo(10);
        assertThat(inteiro(segundo, "$.linhasRejeitadas")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pesquisa_resultado")).isEqualTo(totalDepoisDoPrimeiro);
        // A rejeição do percentual fora de 0 a 100 é a 12ª linha do arquivo.
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(primeiro, "$.id") + "/rejeicoes");
        assertThat(inteiro(rejeicoes, "$.itens[0].linha")).isEqualTo(12);
        assertThat(lerJson(rejeicoes, "$.itens[0].campo")).isEqualTo("valor");
        assertThat(lerJson(rejeicoes, "$.itens[0].motivo")).isEqualTo("Percentual fora do intervalo de 0 a 100");
    }

    @Test
    void comAAmostraCarregadaOExemploDePesquisaNaoInsereNada() {
        long antes = contar("SELECT count(*) FROM pesquisa_resultado");

        MvcTestResult resposta = enviar(entrar(RBS), "pesquisa.csv", exemplo("pesquisa.csv"), "PESQUISA", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(resposta, "$.linhasInseridas")).isZero();
        assertThat(inteiro(resposta, "$.linhasDuplicadas")).isEqualTo(10);
        assertThat(contar("SELECT count(*) FROM pesquisa_resultado")).isEqualTo(antes);
    }

    @Test
    void linhaDePerguntaNaoAprovadaEhRejeitada() {
        semResultadosDePesquisa();
        long amarelo = contar("""
                SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027""");
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE parceria_id = :p AND ordem = 3").param("p", amarelo).update();
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult carga = enviar(rbs, "pesquisa.csv", exemplo("pesquisa.csv"), "PESQUISA", evento(2027), null);

        // Pergunta 3 (linhas 8, 9 e 10) não está aprovada; a linha 12 (140%) cai antes, no percentual.
        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasLidas")).isEqualTo(11);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(6);
        assertThat(inteiro(carga, "$.linhasDuplicadas")).isEqualTo(1);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(4);
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
        assertThat(lerLista(rejeicoes, "$.itens[*].linha")).containsExactly(8, 9, 10, 12);
        assertThat(lerLista(rejeicoes, "$.itens[*].motivo")).containsExactly("Pergunta ainda não aprovada pela RBS",
                "Pergunta ainda não aprovada pela RBS", "Pergunta ainda não aprovada pela RBS",
                "Percentual fora do intervalo de 0 a 100");
        assertThat(lerLista(rejeicoes, "$.itens[*].campo")).containsExactly("pergunta", "pergunta", "pergunta", "valor");
        // Nenhum resultado da pergunta 3 foi gravado.
        assertThat(contar("SELECT count(*) FROM pesquisa_resultado r JOIN pesquisa_pergunta q ON q.id = r.pergunta_id "
                + "WHERE q.parceria_id = ? AND q.ordem = 3", amarelo)).isZero();
    }

    @Test
    void aPesquisaGeralDoFestivalAceitaSoIndicadorESemPatrocinadorNemPergunta() {
        SessaoDeApi rbs = entrar(RBS);
        String csv = CABECALHO_PESQUISA + "\n"
                + ",,Satisfação geral,Nota média,8.7,NOTA,1200\n"
                + ",,Pretende voltar,Sim,91,PERCENTUAL,1200\n"
                + "Cliente Amarelo,1,Satisfação geral,Sim,50,PERCENTUAL,1200\n";

        MvcTestResult carga = enviar(rbs, "geral.csv", texto(csv), "PESQUISA", evento(2026), "GERAL");

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasLidas")).isEqualTo(3);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(2);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pesquisa_resultado WHERE carga_id = ? AND parceria_id IS NULL AND pergunta_id IS NULL",
                longo(carga, "$.id"))).isEqualTo(2);
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
        assertThat(inteiro(rejeicoes, "$.itens[0].linha")).isEqualTo(4);
        assertThat(lerJson(rejeicoes, "$.itens[0].motivo")).isEqualTo("A pesquisa geral do festival não tem patrocinador nem pergunta");
    }

    @Test
    void edicaoSemAPesquisaPedidaDevolve422NoCampoEscopo() {
        SessaoDeApi rbs = entrar(RBS);
        String csv = CABECALHO_PESQUISA + "\nCliente Amarelo,1,,Sim,50,PERCENTUAL,100\n";

        // A amostra tem a pesquisa GERAL só em 2026 e a de ATIVACAO só em 2027.
        MvcTestResult ativacaoDe2026 = enviar(rbs, "p.csv", texto(csv), "PESQUISA", evento(2026), null);
        MvcTestResult geralDe2027 = enviar(rbs, "p.csv", texto(csv), "PESQUISA", evento(2027), "GERAL");

        assertThat(ativacaoDe2026).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(ativacaoDe2026, "$.campos.escopo")).contains("2026").contains("ativação");
        assertThat(geralDe2027).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(geralDe2027, "$.campos.escopo")).contains("2027").contains("geral");
    }

    // ---------------------------------------------------------------------
    // Rota 26: erros do arquivo e das partes
    // ---------------------------------------------------------------------

    @Test
    void cabecalhoSemAlgumaColunaDevolve422ComAsQueFaltam() {
        SessaoDeApi rbs = entrar(RBS);
        String csv = "patrocinador,veiculo,formato,exibido_em,quantidade\nCliente Amarelo,Rádio 92,Chamadas,2026-12-01T10:00:00-03:00,1\n";

        MvcTestResult resposta = enviar(rbs, "falta.csv", texto(csv), "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        String faltam = (String) lerJson(resposta, "$.campos.arquivo");
        assertThat(faltam).contains("espaco_programa", "link").doesNotContain("patrocinador,").doesNotContain("exibido_em");
        // Que nada fica pela metade (carga, arquivo e linhas) é provado no CargaRollbackTest, que usa transações de verdade.
    }

    @Test
    void planilhaDePesquisaEnviadaComoVeiculacaoFaltaTodasAsColunasDela() {
        MvcTestResult resposta = enviar(entrar(RBS), "pesquisa.csv", exemplo("pesquisa.csv"), "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resposta, "$.campos.arquivo")).contains("veiculo", "formato", "exibido_em", "quantidade");
    }

    @Test
    void separadorPontoEVirgulaDevolve422ComAOrientacao() {
        String csv = CABECALHO_VEICULACAO.replace(',', ';') + "\nCliente Amarelo;Rádio 92;Chamadas;;2026-12-01T10:00:00-03:00;1;\n";

        MvcTestResult resposta = enviar(entrar(RBS), "ponto-e-virgula.csv", texto(csv), "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(resposta, "$.campos.arquivo")).contains("vírgula");
    }

    @Test
    void arquivoBinarioOuQueNaoEhCsvDevolve415() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] binario = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, (byte) 0xFF, (byte) 0xFE};
        byte[] latin1 = "patrocinador,veiculo\nCliente Amarelo,Rádio".getBytes(StandardCharsets.ISO_8859_1);

        MvcTestResult umPng = enviar(rbs, "planilha.csv", binario, "VEICULACAO", evento(2027), null);
        MvcTestResult emLatin1 = enviar(rbs, "planilha.csv", latin1, "VEICULACAO", evento(2027), null);
        assertThat(umPng).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(emLatin1).as("não é UTF-8").hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // As duas frases são diferentes: o PNG renomeado não é planilha; o texto em Latin-1 só precisa ser salvo de novo em UTF-8.
        assertThat((String) lerJson(umPng, "$.detail")).contains("não é um CSV de verdade").contains("renomeado").doesNotContain("UTF-8");
        assertThat((String) lerJson(emLatin1, "$.detail")).contains("não está em UTF-8").contains("CSV UTF-8 (separado por vírgulas)")
                .doesNotContain("de verdade");
        // Mesmo conteúdo certo, com extensão que não é .csv (o Armazenamento até aceita pdf e xlsx; a carga não).
        byte[] csv = exemplo("veiculacao.csv");
        assertThat(enviar(rbs, "planilha.xlsx", csv, "VEICULACAO", evento(2027), null)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviar(rbs, "planilha.txt", csv, "VEICULACAO", evento(2027), null)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        MvcTestResult semExtensao = enviar(rbs, "planilha", csv, "VEICULACAO", evento(2027), null);
        assertThat(semExtensao).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(semExtensao, "$.title")).isEqualTo("Tipo de conteúdo não aceito");
    }

    @Test
    void planilhaAcimaDe20MbDevolve413() {
        byte[] grande = new byte[(int) armazenamento.limiteBytes() + 1];
        Arrays.fill(grande, (byte) 'a');

        MvcTestResult resposta = enviar(entrar(RBS), "enorme.csv", grande, "VEICULACAO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat((String) lerJson(resposta, "$.detail")).contains("20 MB");
    }

    @Test
    void partesAusentesOuInvalidasDevolvem422ComOsCampos() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult tudoErrado = enviar(rbs, null, null, "OUTRO", 999_999L, null);
        MvcTestResult eventoQueNaoEhNumero = enviar(rbs, "v.csv", exemplo("veiculacao.csv"), "VEICULACAO", "abc", null);
        MvcTestResult semEvento = enviar(rbs, "v.csv", exemplo("veiculacao.csv"), "VEICULACAO", null, null);
        MvcTestResult escopoInvalido = enviar(rbs, "p.csv", exemplo("pesquisa.csv"), "PESQUISA", evento(2027), "TODOS");
        MvcTestResult vazio = enviar(rbs, "vazio.csv", new byte[0], "VEICULACAO", evento(2027), null);

        assertThat(tudoErrado).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(tudoErrado, "$.campos")).containsExactlyInAnyOrder("arquivo", "tipo", "eventoId");
        assertThat((String) lerJson(tudoErrado, "$.campos.eventoId")).contains("não existe");
        assertThat(eventoQueNaoEhNumero).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(eventoQueNaoEhNumero, "$.campos")).containsExactly("eventoId");
        assertThat(semEvento).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(semEvento, "$.campos")).containsExactly("eventoId");
        assertThat(escopoInvalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(escopoInvalido, "$.campos")).containsExactly("escopo");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(vazio, "$.campos")).containsExactly("arquivo");
        // Arquivo de 0 bytes e arquivo que nem foi enviado são problemas diferentes, com frases diferentes.
        assertThat(lerJson(vazio, "$.campos.arquivo")).isEqualTo("A planilha está vazia.");
        assertThat(lerJson(tudoErrado, "$.campos.arquivo")).isEqualTo("Envie a planilha CSV na parte \"arquivo\" do formulário.");
    }

    @Test
    void planilhaDeZeroBytesDizQueEstaVaziaEPlanilhaQueNaoFoiEnviadaPedeOEnvio() {
        SessaoDeApi rbs = entrar(RBS);
        long cargasAntes = contar("SELECT count(*) FROM carga");

        MvcTestResult zeroBytes = enviar(rbs, "planilha.csv", new byte[0], "VEICULACAO", evento(2027), null);
        MvcTestResult naoEnviada = enviar(rbs, null, null, "VEICULACAO", evento(2027), null);

        assertThat(zeroBytes).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(zeroBytes, "$.campos.arquivo")).isEqualTo("A planilha está vazia.");
        assertThat(naoEnviada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(naoEnviada, "$.campos.arquivo")).isEqualTo("Envie a planilha CSV na parte \"arquivo\" do formulário.");
        assertThat(contar("SELECT count(*) FROM carga")).as("nada foi gravado").isEqualTo(cargasAntes);
    }

    @Test
    void dataEQuantidadeIlegiveisSaoRejeitadasComOFormatoEsperadoENaoComOMotivoGenerico() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        String linha = "Cliente Amarelo,Rede Atlântida RS,\"Chamadas assinadas 5\"\"\",Indeterminado,%s,%s,\n";
        String csv = CABECALHO_VEICULACAO + "\n"
                + linha.formatted("31/02/2026", "1")                       // 2: data com dia que não existe
                + linha.formatted("2026-13-45", "1")                       // 3: mês e dia fora do calendário
                + linha.formatted("amanhã", "1")                           // 4: texto
                + linha.formatted("2026-12-02", "1")                       // 5: só a data, sem hora
                + linha.formatted("2026-12-01T10:00:00-03:00", "1x")       // 6: quantidade com letra
                + linha.formatted("2026-12-01T11:00:00-03:00", "12a")      // 7: quantidade com letra no fim
                + linha.formatted("2026-12-01T12:00:00-03:00", "\"1,5\"")  // 8: vírgula decimal (entre aspas, para não virar duas colunas)
                + linha.formatted("2026-12-01T13:00:00-03:00", "-3")       // 9: negativa
                + linha.formatted("", "1")                                 // 10: data vazia
                + linha.formatted("2026-12-01T14:00:00-03:00", "")         // 11: quantidade vazia
                + linha.formatted("2026-12-01T15:00:00-03:00", "1");       // 12: a única boa
        String data = "Data inválida. Use o formato 2026-11-20T10:15:30-03:00 (ano-mês-dia, hora e fuso).";
        String quantidade = "Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).";
        String generico = "Campo obrigatório vazio ou inválido";

        MvcTestResult carga = enviar(rbs, "ilegiveis.csv", texto(csv), "VEICULACAO", evento(2027), null);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasLidas")).isEqualTo(11);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(1);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(10);
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
        assertThat(lerLista(rejeicoes, "$.itens[*].linha")).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(lerLista(rejeicoes, "$.itens[*].campo")).containsExactly("exibido_em", "exibido_em", "exibido_em", "exibido_em",
                "quantidade", "quantidade", "quantidade", "quantidade", "exibido_em", "quantidade");
        assertThat(lerLista(rejeicoes, "$.itens[*].motivo")).containsExactly(data, data, data, data, quantidade, quantidade,
                quantidade, "A quantidade deve ser maior que zero.", generico, generico);
        // O texto que não deu para ler vai no conteúdo da rejeição, como a RBS o escreveu.
        assertThat(lerLista(rejeicoes, "$.itens[*].conteudo")).satisfies(c -> {
            assertThat((String) c.get(0)).endsWith("31/02/2026");
            assertThat((String) c.get(1)).endsWith("2026-13-45");
            assertThat((String) c.get(2)).endsWith("amanhã");
            assertThat((String) c.get(4)).endsWith("1x");
            assertThat((String) c.get(5)).endsWith("12a");
        });
    }

    @Test
    void pesquisaComValorUnidadeBaseEPerguntaIlegiveisTemMotivoEspecifico() {
        semResultadosDePesquisa();
        SessaoDeApi rbs = entrar(RBS);
        String csv = CABECALHO_PESQUISA + "\n"
                + "Cliente Amarelo,1,,Sim,abc,PERCENTUAL,412\n"        // 2: valor com letras
                + "Cliente Amarelo,1,,Não,36,POR_CENTO,412\n"          // 3: unidade que não existe
                + "Cliente Amarelo,2,,Nota média,8.4,NOTA,412x\n"      // 4: base com letra
                + "Cliente Amarelo,2,,Notas 9 e 10,61,PERCENTUAL,0\n"  // 5: base zero
                + "Cliente Amarelo,x,,Melhorou,58,PERCENTUAL,412\n"    // 6: pergunta com letra
                + "Cliente Amarelo,3,,Melhorou,58,PERCENTUAL,412\n";   // 7: a única boa

        MvcTestResult carga = enviar(rbs, "ilegiveis.csv", texto(csv), "PESQUISA", evento(2027), null);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(1);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(5);
        MvcTestResult rejeicoes = rbs.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
        assertThat(lerLista(rejeicoes, "$.itens[*].linha")).containsExactly(2, 3, 4, 5, 6);
        assertThat(lerLista(rejeicoes, "$.itens[*].campo")).containsExactly("valor", "unidade", "base_respondentes",
                "base_respondentes", "pergunta");
        assertThat(lerLista(rejeicoes, "$.itens[*].motivo")).containsExactly(
                "Valor inválido. Use só números, com ponto nos decimais, como 8.4 ou 64.",
                "Unidade inválida. Use PERCENTUAL, NOTA ou CONTAGEM.",
                "Base de respondentes inválida. Use só números inteiros, como 412.",
                "A base de respondentes deve ser maior que zero.",
                "Pergunta inválida. Use o número da pergunta: 1, 2 ou 3.");
    }

    @Test
    void planilhaSoComCabecalhoOuComTextoGrandeDemaisDevolve422() {
        SessaoDeApi rbs = entrar(RBS);
        String linkGrande = "https://exemplo.example/" + "a".repeat(500);

        MvcTestResult soCabecalho = enviar(rbs, "c.csv", texto(CABECALHO_VEICULACAO + "\n"), "VEICULACAO", evento(2027), null);
        MvcTestResult grande = enviar(rbs, "g.csv", texto(CABECALHO_VEICULACAO + "\n"
                + "Cliente Amarelo,Rádio 92,Chamadas,,2026-12-01T10:00:00-03:00,1," + linkGrande + "\n"), "VEICULACAO", evento(2027), null);
        MvcTestResult aspasAbertas = enviar(rbs, "a.csv", texto(CABECALHO_VEICULACAO + "\n\"Cliente Amarelo,Rádio 92\n"), "VEICULACAO",
                evento(2027), null);

        assertThat(soCabecalho).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(soCabecalho, "$.campos.arquivo")).contains("nenhuma linha de dados");
        assertThat(grande).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(grande, "$.campos.arquivo")).contains("Linha 2").contains("link").contains("500");
        assertThat(aspasAbertas).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(aspasAbertas, "$.campos.arquivo")).contains("CSV");
    }

    // ---------------------------------------------------------------------
    // Permissão e sessão
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NasTresRotasDeCargas() {
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult carga = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);
        long cargaId = longo(carga, "$.id");
        long cargasAntes = contar("SELECT count(*) FROM carga");

        for (String email : List.of(ROXO, AMARELO)) {
            SessaoDeApi patrocinador = entrar(email);
            // POST: nada é gravado, nem com a planilha certa.
            MvcTestResult envio = enviar(patrocinador, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);
            assertThat(envio).as(email + " POST /api/cargas").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(envio, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
            // Mesmo com as partes erradas a resposta é 403, e não 422: a permissão é conferida primeiro.
            assertThat(enviar(patrocinador, null, null, null, null, null)).as(email + " POST vazio").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(patrocinador.get("/api/cargas")).as(email + " GET /api/cargas").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(patrocinador.get("/api/cargas?tipo=VEICULACAO&eventoId=" + evento(2027))).hasStatus(HttpStatus.FORBIDDEN);
            // A carga existe e tem linhas do Amarelo, mas o patrocinador não vê as rejeições, nem a existência dela.
            assertThat(patrocinador.get("/api/cargas/" + cargaId + "/rejeicoes")).as(email + " rejeições").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(patrocinador.get("/api/cargas/999999/rejeicoes")).as(email + " carga que não existe")
                    .hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(contar("SELECT count(*) FROM carga")).isEqualTo(cargasAntes);
    }

    @Test
    void semSessaoDevolve401ESemTokenCsrfDevolve403() {
        SessaoDeApi visitante = SessaoDeApi.anonima(mvc);
        SessaoDeApi rbs = entrar(RBS);
        long cargasAntes = contar("SELECT count(*) FROM carga");

        assertThat(visitante.get("/api/cargas")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(visitante.get("/api/cargas/1/rejeicoes")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enviar(visitante, "v.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        MvcTestResult semToken = enviarSemCsrf(rbs, "v.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027));
        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
        assertThat(contar("SELECT count(*) FROM carga")).isEqualTo(cargasAntes);
    }

    // ---------------------------------------------------------------------
    // Rota 27: lista
    // ---------------------------------------------------------------------

    @Test
    void aListaTrazAsCargasDaMaisNovaParaAMaisAntigaComFiltros() {
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult veiculacao = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);
        MvcTestResult pesquisa = enviar(rbs, "pesquisa.csv", exemplo("pesquisa.csv"), "PESQUISA", evento(2027), null);

        MvcTestResult todas = rbs.get("/api/cargas");
        MvcTestResult soPesquisa = rbs.get("/api/cargas?tipo=PESQUISA");
        MvcTestResult soVeiculacaoDe2026 = rbs.get("/api/cargas?tipo=VEICULACAO&eventoId=" + evento(2026));

        // A amostra tem 3 cargas (2 de veiculação e 1 de pesquisa); a lista ganha as 2 de agora, no topo.
        assertThat(todas).hasStatus(HttpStatus.OK);
        assertThat(inteiro(todas, "$.total")).isEqualTo(5);
        assertThat(lerLista(todas, "$.itens[*].id").subList(0, 2))
                .containsExactly((int) longo(pesquisa, "$.id"), (int) longo(veiculacao, "$.id"));
        List<Number> ids = lerLista(todas, "$.itens[*].id");
        assertThat(ids).isSortedAccordingTo((a, b) -> Long.compare(b.longValue(), a.longValue()));
        assertThat(lerLista(todas, "$.itens[*].tipo")).containsOnly("VEICULACAO", "PESQUISA");
        // Cada item tem o formato da resposta da rota 26.
        assertThat(chaves(todas, "$.itens[0]")).containsExactly("id", "tipo", "eventoId", "arquivoId",
                "executadaPor", "executadaEm", "processadaEm", "linhasLidas", "linhasInseridas", "linhasDuplicadas", "linhasRejeitadas");
        assertThat(inteiro(todas, "$.itens[0].linhasLidas")).isEqualTo(11);
        assertThat(lerJson(todas, "$.itens[0].executadaPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(inteiro(soPesquisa, "$.total")).isEqualTo(2);
        assertThat(lerLista(soPesquisa, "$.itens[*].tipo")).containsOnly("PESQUISA");
        assertThat(inteiro(soVeiculacaoDe2026, "$.total")).isZero();
        assertThat(lerLista(soVeiculacaoDe2026, "$.itens")).isEmpty();
    }

    @Test
    void aListaEhPaginadaEValidaOsFiltros() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult pagina = rbs.get("/api/cargas?pagina=1&tamanho=2");

        assertThat(pagina).hasStatus(HttpStatus.OK);
        assertThat(inteiro(pagina, "$.total")).isEqualTo(3);
        assertThat(inteiro(pagina, "$.pagina")).isEqualTo(1);
        assertThat(inteiro(pagina, "$.tamanho")).isEqualTo(2);
        assertThat(lerLista(pagina, "$.itens")).hasSize(1);
        MvcTestResult tipoInvalido = rbs.get("/api/cargas?tipo=MIDIA");
        assertThat(tipoInvalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(tipoInvalido, "$.campos.tipo")).isEqualTo("Tipo inválido. Use VEICULACAO ou PESQUISA.");
        assertThat(rbs.get("/api/cargas?tamanho=0")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/cargas?tamanho=201")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/cargas?pagina=-1")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---------------------------------------------------------------------
    // Histórico
    // ---------------------------------------------------------------------

    @Test
    void cadaCargaRegistraAcaoCargaNoHistoricoSemParceria() {
        semVeiculacoes();
        SessaoDeApi rbs = entrar(RBS);
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);

        MvcTestResult carga = enviar(rbs, "veiculacao.csv", exemplo("veiculacao.csv"), "VEICULACAO", evento(2027), null);

        var linhas = jdbc.sql("""
                        SELECT usuario_id, parceria_id, entidade, valor_novo, detalhe, host(ip) AS ip, user_agent FROM historico
                        WHERE acao = 'CARGA' AND entidade_id = :id
                        """).param("id", longo(carga, "$.id")).query().listOfRows();
        assertThat(linhas).hasSize(1);
        Map<String, Object> historico = linhas.getFirst();
        assertThat(((Number) historico.get("usuario_id")).longValue()).isEqualTo(rbsId);
        assertThat(historico.get("parceria_id")).isNull();
        assertThat(historico.get("entidade")).isEqualTo("carga");
        assertThat(historico.get("valor_novo")).isEqualTo("veiculacao.csv (VEICULACAO, edição 2027)");
        assertThat(historico.get("detalhe")).isEqualTo("12 lidas, 10 inseridas, 1 duplicadas, 1 rejeitadas");
        assertThat(historico.get("ip")).isNotNull();
        assertThat(historico.get("user_agent")).isEqualTo(AGENTE);
        // A RBS enxerga a carga no histórico geral (rota 21).
        MvcTestResult geral = rbs.get("/api/historico?acao=CARGA");
        assertThat(geral).hasStatus(HttpStatus.OK);
        assertThat(lerLista(geral, "$.itens[*].entidadeId")).contains((int) longo(carga, "$.id"));
        // Uma carga recusada (422) não deixa linha no histórico.
        long antes = contar("SELECT count(*) FROM historico WHERE acao = 'CARGA'");
        assertThat(enviar(rbs, "falta.csv", texto("patrocinador\nCliente Amarelo\n"), "VEICULACAO", evento(2027), null))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'CARGA'")).isEqualTo(antes);
    }
}
