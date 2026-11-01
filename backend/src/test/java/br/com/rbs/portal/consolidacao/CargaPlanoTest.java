package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa a carga do plano comercial (rota 26 com tipo=PLANO), a lista de cargas com o filtro novo (27), as rejeições do plano
 * (28) e a "última carga" do painel sem o plano (30), como o front as usa: login pela rota, multipart e token CSRF.
 * [CARGA] [DUPLICIDADE] [VALIDACAO] [PERMISSAO] [HISTORICO] Os números esperados são conferidos por SQL direto em
 * aproveitamento_previsto, carga e carga_rejeicao. Cada teste é desfeito no fim (@Transactional). A parceria dos testes é a
 * "Cliente Teste" de 2027, criada aqui como a RBS a cria pela tela (sem plano comercial). O exemplo docs/exemplos/plano.csv,
 * numa parceria sem plano, tem 11 linhas: 8 entram, 1 é duplicada e 2 são rejeitadas (docs/banco.md).
 */
@TesteDeIntegracao
@Transactional
class CargaPlanoTest extends ApoioDeCargas {

    private static final String SEM_TIPO = "Tipo de mídia inválido. Use ASSINADA, PROPRIA, MERCHANDISING, BRANDED ou CONTEUDO_DE_MARCA.";
    private static final String OBRIGATORIO = "Campo obrigatório vazio ou inválido";

    /** Uma parceria nova de 2027, sem plano comercial: o que a RBS acabou de cadastrar pela tela. */
    long criarParceria(String nome) {
        long patrocinador = jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES (:nome) RETURNING id")
                .param("nome", nome).query(Long.class).single();
        return jdbc.sql("INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id) VALUES (:evento, :patrocinador, 3) RETURNING id")
                .param("evento", evento(2027)).param("patrocinador", patrocinador).query(Long.class).single();
    }

    long linhasDoPlano(long parceriaId) {
        return contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ?", parceriaId);
    }

    MvcTestResult enviarPlano(SessaoDeApi pessoa, String csv) {
        return enviar(pessoa, "plano.csv", texto(csv), "PLANO", evento(2027), null);
    }

    MvcTestResult rejeicoes(SessaoDeApi pessoa, MvcTestResult carga) {
        return pessoa.get("/api/cargas/" + longo(carga, "$.id") + "/rejeicoes");
    }

    // ---------------------------------------------------------------------
    // O exemplo e as contagens
    // ---------------------------------------------------------------------

    @Test
    void rbsEnviaOExemploDePlanoEAsContagensBatemComOBanco() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);

        MvcTestResult resposta = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        // 11 linhas de dados: 8 novas (6 aplicações e 2 de mídia), 1 repetida (mesma chave, outra quantidade) e 2 rejeitadas.
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("PLANO");
        assertThat(longo(resposta, "$.eventoId")).isEqualTo(evento(2027));
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(11);
        assertThat(inteiro(resposta, "$.linhasInseridas")).isEqualTo(8);
        assertThat(inteiro(resposta, "$.linhasDuplicadas")).isEqualTo(1);
        assertThat(inteiro(resposta, "$.linhasRejeitadas")).isEqualTo(2);
        assertThat(longo(resposta, "$.executadaPor.id")).isEqualTo(rbsId);
        assertThat((String) lerJson(resposta, "$.processadaEm")).endsWith("-03:00");
        assertThat(chaves(resposta, "$")).containsExactly("id", "tipo", "eventoId", "arquivoId", "executadaPor", "executadaEm",
                "processadaEm", "linhasLidas", "linhasInseridas", "linhasDuplicadas", "linhasRejeitadas");

        // Conferência pelo banco, sem passar pelo que a API respondeu.
        long carga = longo(resposta, "$.id");
        assertThat(linhasDoPlano(teste)).isEqualTo(8);
        assertThat(contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ? AND categoria = 'APLICACAO_MARCA'", teste))
                .isEqualTo(6);
        assertThat(contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ? AND categoria = 'MIDIA' AND tipo_midia IS NOT NULL",
                teste)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM stg_plano WHERE carga_id = ?", carga)).isEqualTo(11);
        // A primeira versão prevalece: a repetida (quantidade 2) não troca a quantidade 1 da primeira "Elemento na Arena".
        assertThat(contar("SELECT quantidade_prevista FROM aproveitamento_previsto WHERE parceria_id = ? AND formato = 'Elemento na Arena'",
                teste)).isEqualTo(1);
        // A aplicação fica sem tipo de mídia e com o veículo "No evento"; a página da fonte vem do arquivo.
        Map<String, Object> aplicacao = jdbc.sql("""
                        SELECT bloco, veiculo, unidade, tipo_midia, fonte_pagina, fonte_arquivo_id FROM aproveitamento_previsto
                        WHERE parceria_id = :p AND formato = 'Elemento na Arena'
                        """).param("p", teste).query().singleRow();
        assertThat(aplicacao.get("bloco")).isEqualTo("Aplicações de marca");
        assertThat(aplicacao.get("veiculo")).isEqualTo("No evento");
        assertThat(aplicacao.get("unidade")).isEqualTo("APLICACAO");
        assertThat(aplicacao.get("tipo_midia")).isNull();
        assertThat(((Number) aplicacao.get("fonte_pagina")).intValue()).isEqualTo(12);
        assertThat(aplicacao.get("fonte_arquivo_id")).as("o CSV é arquivo geral da RBS: a fonte fica nula").isNull();
        Map<String, Object> carga0 = jdbc.sql("SELECT tipo, evento_id, processada_em IS NOT NULL AS processada FROM carga WHERE id = :c")
                .param("c", carga).query().singleRow();
        assertThat(carga0.get("tipo")).as("no banco o plano é PLANO_COMERCIAL").isEqualTo("PLANO_COMERCIAL");
        assertThat(carga0.get("processada")).isEqualTo(true);

        // As duas rejeitadas: a mídia sem tipo (linha 11 do arquivo) e o "Cliente Azul", que não tem parceria (linha 12).
        MvcTestResult motivos = rejeicoes(rbs, resposta);
        assertThat(motivos).hasStatus(HttpStatus.OK);
        assertThat(lerLista(motivos, "$.itens[*].linha")).containsExactly(11, 12);
        assertThat(lerLista(motivos, "$.itens[*].campo")).containsExactly("tipo_midia", "patrocinador");
        assertThat(lerLista(motivos, "$.itens[*].motivo")).containsExactly(SEM_TIPO, "Patrocinador sem parceria nesta edição");
    }

    @Test
    void aPlanilhaFicaGuardadaComoArquivoDaRbsDaCategoriaPlanoComercial() {
        criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        byte[] bytes = exemplo("plano.csv");

        MvcTestResult resposta = enviar(rbs, "plano.csv", bytes, "PLANO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        Map<String, Object> arquivo = jdbc.sql("""
                        SELECT parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho
                        FROM arquivo WHERE id = :id
                        """).param("id", longo(resposta, "$.arquivoId")).query().singleRow();
        assertThat(arquivo.get("parceria_id")).as("arquivo geral da RBS").isNull();
        assertThat(arquivo.get("aba_id")).isNull();
        assertThat(arquivo.get("entrega_id")).isNull();
        assertThat(arquivo.get("categoria")).isEqualTo("PLANO_COMERCIAL");
        assertThat(arquivo.get("nome_original")).isEqualTo("plano.csv");
        assertThat(arquivo.get("mime_type")).isEqualTo("text/csv");
        assertThat(((Number) arquivo.get("tamanho_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat((String) arquivo.get("caminho")).startsWith("geral/").doesNotContain("plano");
    }

    @Test
    void reenviarOMesmoPlanoNaoInsereNadaEAsLinhasValidasViramDuplicadas() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        byte[] bytes = exemplo("plano.csv");

        MvcTestResult primeiro = enviar(rbs, "plano.csv", bytes, "PLANO", evento(2027), null);
        MvcTestResult segundo = enviar(rbs, "plano.csv", bytes, "PLANO", evento(2027), null);

        assertThat(segundo).hasStatus(HttpStatus.CREATED);
        assertThat(longo(segundo, "$.id")).isNotEqualTo(longo(primeiro, "$.id"));
        // [DUPLICIDADE] As 8 que entraram e a 1 que já era repetida viram duplicadas; as 2 ruins seguem rejeitadas.
        assertThat(inteiro(segundo, "$.linhasLidas")).isEqualTo(11);
        assertThat(inteiro(segundo, "$.linhasInseridas")).isZero();
        assertThat(inteiro(segundo, "$.linhasDuplicadas")).isEqualTo(9);
        assertThat(inteiro(segundo, "$.linhasRejeitadas")).isEqualTo(2);
        assertThat(linhasDoPlano(teste)).isEqualTo(8);
    }

    // ---------------------------------------------------------------------
    // Cada motivo de rejeição
    // ---------------------------------------------------------------------

    @Test
    void cadaProblemaDaLinhaViraUmaRejeicaoComCampoEMotivoEAsBoasEntram() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        // Linha 1 = cabeçalho. As linhas 2 e 3 entram; da 4 à 14 cada uma tem um problema só.
        String csv = CABECALHO_PLANO + "\n"
                + "Cliente Teste,MIDIA,Transmissão,Rede Atlântida RS,Chamadas,Manhã,Nov/2027,ASSINADA,10,INSERCAO,3\n"
                + "Cliente Teste,APLICACAO_MARCA,Aplicações de marca,No evento,Elemento na Arena,,,,1,APLICACAO,\n"
                + ",MIDIA,,Rádio 92,Chamadas,,,PROPRIA,1,INSERCAO,\n"                                     // 4: sem patrocinador
                + "Cliente Teste,APLICACAO_MARCA,,,Elemento no Camarote,,,,1,APLICACAO,\n"                // 5: aplicação sem veículo
                + "Cliente Teste,MIDIA,,Rádio 92,Vinhetas,,,PROPRIA,1x,INSERCAO,\n"                      // 6: quantidade com letra
                + "Cliente Teste,MIDIA,,Rádio 92,Boletins,,,PROPRIA,\"1,5\",INSERCAO,\n"                 // 7: vírgula decimal
                + "Cliente Teste,MIDIA,,Rádio 92,Menções,,,PROPRIA,0,INSERCAO,\n"                        // 8: zero
                + "Cliente Teste,MIDIA,,Rádio 92,Selos,,,PROPRIA,-2,INSERCAO,\n"                         // 9: negativa
                + "Cliente Teste,OUTRA,,Rádio 92,Quadros,,,PROPRIA,1,INSERCAO,\n"                        // 10: categoria
                + "Cliente Teste,MIDIA,,Rádio 92,Programetes,,,PROPRIA,1,PACOTE,\n"                      // 11: unidade
                + "Cliente Teste,APLICACAO_MARCA,,No evento,Elemento na Arena VIP,,,,1,INSERCAO,\n"      // 12: aplicação com outra unidade
                + "Cliente Teste,MIDIA,,Rádio 92,Chamadas ao vivo,,,,1,INSERCAO,\n"                      // 13: mídia sem tipo
                + "Cliente Teste,APLICACAO_MARCA,,No evento,Elemento no Lounge,,,FOO,1,APLICACAO,\n"     // 14: tipo inválido numa aplicação
                + "Cliente Teste,MIDIA,,Rádio 92,Patrocínios,,,PROPRIA,,INSERCAO,\n"                     // 15: quantidade vazia
                + "Cliente Azul,MIDIA,,Rádio 92,Chamadas,,,PROPRIA,1,INSERCAO,\n";                       // 16: marca sem parceria

        MvcTestResult carga = enviarPlano(rbs, csv);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasLidas")).isEqualTo(15);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(2);
        assertThat(inteiro(carga, "$.linhasDuplicadas")).isZero();
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(13);
        assertThat(linhasDoPlano(teste)).isEqualTo(2);
        MvcTestResult motivos = rejeicoes(rbs, carga);
        assertThat(lerLista(motivos, "$.itens[*].linha")).containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        assertThat(lerLista(motivos, "$.itens[*].campo")).containsExactly("patrocinador", "veiculo", "quantidade_prevista",
                "quantidade_prevista", "quantidade_prevista", "quantidade_prevista", "categoria", "unidade", "unidade", "tipo_midia",
                "tipo_midia", "quantidade_prevista", "patrocinador");
        String quantidade = "Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).";
        assertThat(lerLista(motivos, "$.itens[*].motivo")).containsExactly(OBRIGATORIO, OBRIGATORIO, quantidade, quantidade,
                "A quantidade deve ser maior que zero.", "A quantidade deve ser maior que zero.",
                "Categoria inválida. Use MIDIA ou APLICACAO_MARCA.", "Unidade inválida. Use INSERCAO, CPM ou APLICACAO.",
                "Aplicação de marca usa a unidade APLICACAO.", SEM_TIPO, SEM_TIPO, OBRIGATORIO,
                "Patrocinador sem parceria nesta edição");
        // O que não deu para ler aparece no conteúdo como estava na planilha.
        assertThat((String) lerJson(motivos, "$.itens[2].conteudo")).endsWith("1x | INSERCAO");
        assertThat((String) lerJson(motivos, "$.itens[3].conteudo")).contains("1,5");
        assertThat((String) lerJson(motivos, "$.itens[12].conteudo")).startsWith("Cliente Azul | MIDIA");
    }

    @Test
    void linhaRepetidaEmOutraCaixaOuSemEspacoNoPlanoVaiParaOMesmoLugar() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        // Mesma chave (veículo + formato + espaço, sem caixa): a 3 e a 4 repetem a 2. O espaço vazio é um espaço como outro: a 5
        // tem o mesmo formato com espaço de programa preenchido, então é uma linha do plano à parte.
        String csv = CABECALHO_PLANO + "\n"
                + "Cliente Teste,APLICACAO_MARCA,,No evento,Testeira no palco,,,,2,APLICACAO,\n"
                + "cliente teste,aplicacao_marca,,NO EVENTO,TESTEIRA NO PALCO,,,,7,aplicacao,\n"
                + "Cliente Teste,APLICACAO_MARCA,Outro bloco,No evento,Testeira no palco,,Nov,,3,APLICACAO,\n"
                + "Cliente Teste,APLICACAO_MARCA,,No evento,Testeira no palco,Palco principal,,,1,APLICACAO,\n";

        MvcTestResult carga = enviarPlano(rbs, csv);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(2);
        assertThat(inteiro(carga, "$.linhasDuplicadas")).isEqualTo(2);
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isZero();
        assertThat(linhasDoPlano(teste)).isEqualTo(2);
        // A primeira versão prevalece (quantidade 2, e não 7 nem 3) e os códigos em minúsculas valem como maiúsculas.
        assertThat(contar("SELECT quantidade_prevista FROM aproveitamento_previsto WHERE parceria_id = ? AND espaco_programa IS NULL",
                teste)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ? AND categoria = 'APLICACAO_MARCA' "
                + "AND unidade = 'APLICACAO'", teste)).isEqualTo(2);
    }

    @Test
    void marcaPeloApelidoEPaginaForaDaFaixaViraNula() {
        long teste = criarParceria("Cliente Teste");
        jdbc.sql("INSERT INTO patrocinador_alias (patrocinador_id, alias) SELECT patrocinador_id, 'Teste' FROM parceria WHERE id = :p")
                .param("p", teste).update();
        String csv = CABECALHO_PLANO + "\n"
                + "Teste,APLICACAO_MARCA,,No evento,Elemento A,,,,1,APLICACAO,12\n"
                + "teste,APLICACAO_MARCA,,No evento,Elemento B,,,,1,APLICACAO,0\n"
                + "Teste,APLICACAO_MARCA,,No evento,Elemento C,,,,1,APLICACAO,32768\n"
                + "Teste,APLICACAO_MARCA,,No evento,Elemento D,,,,1,APLICACAO,pág. 4\n"
                + "Teste,APLICACAO_MARCA,,No evento,Elemento E,,,,1,APLICACAO,32767\n";

        MvcTestResult carga = enviarPlano(entrar(RBS), csv);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasInseridas")).isEqualTo(5);
        assertThat(linhasDoPlano(teste)).isEqualTo(5);
        // A página é opcional: o que não cabe no smallint ou não é número não derruba a linha, só fica sem página.
        List<Integer> paginas = jdbc.sql("SELECT fonte_pagina FROM aproveitamento_previsto WHERE parceria_id = :p ORDER BY formato")
                .param("p", teste).query(Integer.class).list();
        assertThat(paginas).containsExactly(12, null, null, null, 32767);
    }

    @Test
    void aMesmaPlanilhaParaOutraEdicaoRejeitaAsMarcasSemParceriaNela() {
        criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult carga = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2026), null);

        assertThat(carga).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(carga, "$.linhasInseridas")).isZero();
        assertThat(inteiro(carga, "$.linhasRejeitadas")).isEqualTo(11);
        assertThat(longo(carga, "$.eventoId")).isEqualTo(evento(2026));
    }

    // ---------------------------------------------------------------------
    // Erros do arquivo e das partes
    // ---------------------------------------------------------------------

    @Test
    void cabecalhoSemAlgumaColunaOuTextoGrandeDemaisDevolve422SemGravarNada() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        String semColunas ="patrocinador,categoria,veiculo,formato,quantidade_prevista\nCliente Teste,MIDIA,Rádio 92,Chamadas,1\n";
        String blocoGrande = CABECALHO_PLANO + "\nCliente Teste,MIDIA," + "b".repeat(81) + ",Rádio 92,Chamadas,,,PROPRIA,1,INSERCAO,\n";
        String unidadeGrande = CABECALHO_PLANO + "\nCliente Teste,MIDIA,,Rádio 92,Chamadas,,,PROPRIA,1," + "U".repeat(16) + ",\n";

        MvcTestResult faltam = enviarPlano(rbs, semColunas);
        MvcTestResult bloco = enviarPlano(rbs, blocoGrande);
        MvcTestResult unidade = enviarPlano(rbs, unidadeGrande);
        MvcTestResult soCabecalho = enviarPlano(rbs, CABECALHO_PLANO + "\n");

        assertThat(faltam).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(faltam, "$.campos.arquivo")).contains("bloco", "espaco_programa", "janela", "tipo_midia", "unidade",
                "fonte_pagina").doesNotContain("patrocinador,");
        assertThat(bloco).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(bloco, "$.campos.arquivo")).contains("Linha 2").contains("bloco").contains("80");
        assertThat(unidade).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(unidade, "$.campos.arquivo")).contains("unidade").contains("15");
        assertThat(soCabecalho).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(soCabecalho, "$.campos.arquivo")).contains("nenhuma linha de dados");
        // Que a carga, o arquivo e a área de passagem não ficam é provado no CargaRollbackTest (transações de verdade).
        assertThat(linhasDoPlano(teste)).isZero();
    }

    @Test
    void tipoInvalidoListaOsTresTiposEOEscopoSoValeNaPesquisa() {
        SessaoDeApi rbs = entrar(RBS);
        criarParceria("Cliente Teste");

        MvcTestResult invalido = enviar(rbs, "plano.csv", exemplo("plano.csv"), "MIDIA", evento(2027), null);
        MvcTestResult comEscopo = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), "TODOS");
        MvcTestResult semEvento = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", null, null);

        assertThat(invalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invalido, "$.campos.tipo")).isEqualTo("Tipo inválido. Use VEICULACAO, PESQUISA ou PLANO.");
        assertThat(comEscopo).as("o escopo é da pesquisa; no plano a parte é ignorada").hasStatus(HttpStatus.CREATED);
        assertThat(semEvento).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(chaves(semEvento, "$.campos")).containsExactly("eventoId");
    }

    // ---------------------------------------------------------------------
    // Permissão, sessão e histórico
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NaCargaDoPlanoESemTokenCsrfOuSessaoDevolve403Ou401() {
        long teste = criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        long cargasAntes = contar("SELECT count(*) FROM carga");

        for (String email : List.of(ROXO, AMARELO)) {
            MvcTestResult envio = enviar(entrar(email), "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);
            assertThat(envio).as(email).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(envio, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
        }
        MvcTestResult semToken = enviarSemCsrf(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027));
        MvcTestResult semSessao = enviar(SessaoDeApi.anonima(mvc), "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);

        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
        assertThat(semSessao).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(contar("SELECT count(*) FROM carga")).isEqualTo(cargasAntes);
        assertThat(linhasDoPlano(teste)).isZero();
    }

    @Test
    void cadaCargaDoPlanoRegistraAcaoCargaNoHistoricoSemParceria() {
        criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult carga = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);

        var linhas = jdbc.sql("SELECT parceria_id, entidade, valor_novo, detalhe FROM historico WHERE acao = 'CARGA' AND entidade_id = :id")
                .param("id", longo(carga, "$.id")).query().listOfRows();
        assertThat(linhas).hasSize(1);
        assertThat(linhas.getFirst().get("parceria_id")).isNull();
        assertThat(linhas.getFirst().get("entidade")).isEqualTo("carga");
        assertThat(linhas.getFirst().get("valor_novo")).isEqualTo("plano.csv (PLANO, edição 2027)");
        assertThat(linhas.getFirst().get("detalhe")).isEqualTo("11 lidas, 8 inseridas, 1 duplicadas, 2 rejeitadas");
    }

    // ---------------------------------------------------------------------
    // Rotas 27 e 30
    // ---------------------------------------------------------------------

    @Test
    void aListaDeCargasAceitaOFiltroPlanoEConverteOTipoDoBanco() {
        criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult plano = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);

        MvcTestResult soPlano = rbs.get("/api/cargas?tipo=PLANO");
        MvcTestResult soVeiculacao = rbs.get("/api/cargas?tipo=VEICULACAO");
        MvcTestResult todas = rbs.get("/api/cargas");
        MvcTestResult planoDe2026 = rbs.get("/api/cargas?tipo=PLANO&eventoId=" + evento(2026));

        assertThat(soPlano).hasStatus(HttpStatus.OK);
        assertThat(inteiro(soPlano, "$.total")).isEqualTo(1);
        assertThat(lerLista(soPlano, "$.itens[*].tipo")).containsExactly("PLANO");
        assertThat(longo(soPlano, "$.itens[0].id")).isEqualTo(longo(plano, "$.id"));
        assertThat(inteiro(soPlano, "$.itens[0].linhasLidas")).isEqualTo(11);
        assertThat(chaves(soPlano, "$.itens[0]")).containsExactly("id", "tipo", "eventoId", "arquivoId", "executadaPor",
                "executadaEm", "processadaEm", "linhasLidas", "linhasInseridas", "linhasDuplicadas", "linhasRejeitadas");
        assertThat(lerLista(soVeiculacao, "$.itens[*].tipo")).containsOnly("VEICULACAO");
        // A amostra tem 3 cargas; a do plano é a quarta e fica no topo da lista geral.
        assertThat(inteiro(todas, "$.total")).isEqualTo(4);
        assertThat(lerLista(todas, "$.itens[*].tipo").getFirst()).isEqualTo("PLANO");
        assertThat(inteiro(planoDe2026, "$.total")).isZero();
    }

    @Test
    void aUltimaCargaDoPainelNaoContaOPlano() {
        criarParceria("Cliente Teste");
        SessaoDeApi rbs = entrar(RBS);
        long esperada = contar("SELECT id FROM carga WHERE evento_id = ? AND tipo IN ('MIDIA', 'PESQUISA') "
                + "ORDER BY executada_em DESC, id DESC LIMIT 1", evento(2027));
        MvcTestResult antes = rbs.get("/api/painel");
        assertThat(longo(antes, "$.ultimaCarga.id")).isEqualTo(esperada);

        MvcTestResult plano = enviar(rbs, "plano.csv", exemplo("plano.csv"), "PLANO", evento(2027), null);
        MvcTestResult depois = rbs.get("/api/painel");

        assertThat(plano).hasStatus(HttpStatus.CREATED);
        assertThat(contar("SELECT id FROM carga WHERE evento_id = ? ORDER BY executada_em DESC, id DESC LIMIT 1", evento(2027)))
                .as("a carga do plano é a mais nova da edição").isEqualTo(longo(plano, "$.id"));
        assertThat(longo(depois, "$.ultimaCarga.id")).as("mas o card do painel continua com a de mídia ou de pesquisa").isEqualTo(esperada);
        assertThat(lerJson(depois, "$.ultimaCarga.tipo")).isIn("VEICULACAO", "PESQUISA");
    }
}
