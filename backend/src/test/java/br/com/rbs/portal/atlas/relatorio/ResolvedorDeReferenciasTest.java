package br.com.rbs.portal.atlas.relatorio;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CalculoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FotoDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.TextoResolvido;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [RELATORIO] A resolução das referências com o banco da amostra: os números da consolidação e do plano de aplicações
 * (os mesmos das rotas 29 e 37, com o filtro de aba do perfil), os dados informados com autor, data e origem, as contas das
 * fórmulas fixas e o que acontece quando falta dado. [ISOLAMENTO] A parceria de outra marca não resolve. Cada teste é desfeito no
 * fim (@Transactional). O Amarelo de 2027 tem 19 aplicações previstas, 17 comprovadas e 17 fotos; o Roxo não tem aplicação.
 */
@TesteDeIntegracao
@Transactional
class ResolvedorDeReferenciasTest {

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    ModeloBase modelos;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    Especificacao modelo() {
        return modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    Resolucao doAmarelo() {
        return resolvedor.abrir(usuario(jdbc, AMARELO), amarelo(), modelo());
    }

    void informar(Object... paresChaveValor) {
        dados.gravar(usuario(jdbc, AMARELO), amarelo(), valores(paresChaveValor), OrigemDoDado.ATLAS, requisicao());
    }

    // ---------------------------------------------------------------------
    // A parceria e a consolidação
    // ---------------------------------------------------------------------

    @Test
    void resolveAParceriaEAEdicao() {
        Resolucao resolucao = doAmarelo();

        assertThat(resolucao.valor("parc:patrocinador").texto()).isEqualTo("Cliente Amarelo");
        assertThat(resolucao.valor("parc:cota").texto()).isEqualTo("Master");
        assertThat(resolucao.valor("evento:edicao").texto()).isEqualTo("Planeta Atlântida 2027");
        assertThat(resolucao.valor("parc:patrocinador").origem()).isEqualTo(ResolvedorDeReferencias.ORIGEM_PARCERIA);
    }

    @Test
    void resolveOsNumerosDaConsolidacaoNoPadraoDoPortal() {
        Resolucao resolucao = doAmarelo();

        assertThat(resolucao.valor("cons:aplicacoes.previstas").texto()).isEqualTo("19");
        assertThat(resolucao.valor("cons:aplicacoes.comprovadas").texto()).isEqualTo("17");
        assertThat(resolucao.valor("cons:aplicacoes.fotos").texto()).isEqualTo("17");
        Valor percentual = resolucao.valor("cons:aplicacoes.percentual");
        assertThat(percentual.texto()).as("17 de 19 corta em 89,4 e leva o %").isEqualTo("89,4%");
        assertThat(percentual.unidade()).isEqualTo("%");
        assertThat(percentual.origem()).isEqualTo("Consolidação do portal: plano comercial e fotos de comprovação");
    }

    @Test
    void parceriaSemAplicacaoNoPlanoNaoViraZero() {
        Resolucao resolucao = resolvedor.abrir(usuario(jdbc, ROXO), roxo(), modelo());

        for (String ref : List.of("cons:aplicacoes.previstas", "cons:aplicacoes.comprovadas", "cons:aplicacoes.fotos",
                "cons:aplicacoes.percentual")) {
            Valor valor = resolucao.valor(ref);
            assertThat(valor.disponivel()).as(ref).isFalse();
            assertThat(valor.texto()).as(ref).isNull();
            assertThat(valor.motivo()).as(ref).isEqualTo(ResolvedorDeReferencias.SEM_APLICACOES);
        }
        assertThat(resolucao.tabela("lista:aplicacoes").linhas()).isEmpty();
        assertThat(resolucao.fotos("lista:comprovacoes")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // As listas
    // ---------------------------------------------------------------------

    @Test
    void aTabelaTrazUmaLinhaPorAplicacaoDoPlano() {
        var tabela = doAmarelo().tabela("lista:aplicacoes");

        assertThat(tabela.colunas()).containsExactly("Aplicação", "Previsto", "Comprovadas", "Fotos");
        assertThat(tabela.linhas()).hasSize(9);
        assertThat(tabela.linhas()).allSatisfy(linha -> assertThat(linha).hasSize(4));
        assertThat(tabela.linhas()).anySatisfy(linha -> assertThat(linha).startsWith("Elementos dentro do Camarote", "3"));
        assertThat(tabela.linhas()).anySatisfy(linha -> assertThat(linha).startsWith("Elemento na principal via de entrada da Arena", "6"));
    }

    @Test
    void aGaleriaTrazAsFotosComAAplicacaoEAData() {
        List<FotoDto> fotos = doAmarelo().fotos("lista:comprovacoes");

        assertThat(fotos).hasSize(17);
        assertThat(fotos).allSatisfy(foto -> {
            assertThat(foto.arquivoId()).isNotNull();
            assertThat(foto.nomeOriginal()).isNotBlank();
            assertThat(foto.aplicacao()).startsWith("Elemento");
            assertThat(foto.registradoEm()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?-03:00");
        });
    }

    // ---------------------------------------------------------------------
    // Os dados informados
    // ---------------------------------------------------------------------

    @Test
    void dadoQueNinguemInformouSaiSemValorComOMotivo() {
        Valor valor = doAmarelo().valor("dado:" + VISITANTES);

        assertThat(valor.disponivel()).isFalse();
        assertThat(valor.texto()).isNull();
        assertThat(valor.numero()).isNull();
        assertThat(valor.motivo()).isEqualTo("Não informado pela marca.");
        assertThat(valor.rotulo()).isEqualTo("Visitantes no espaço");
    }

    @Test
    void dadoInformadoSaiFormatadoComAutorDataEOrigem() {
        informar(VISITANTES, 1200L);
        String hoje = DateTimeFormatter.ofPattern("dd/MM/yyyy").format(java.time.LocalDate.now(br.com.rbs.portal.comum.Datas.BRASILIA));

        Valor valor = doAmarelo().valor("dado:" + VISITANTES);

        assertThat(valor.texto()).isEqualTo("1.200");
        assertThat(valor.numero()).isEqualByComparingTo("1200");
        assertThat(valor.unidade()).isEqualTo("pessoas");
        assertThat(valor.origem()).isEqualTo("Informado por Marketing Amarelo em " + hoje + ", pelo Atlas");
    }

    @Test
    void dadoGravadoPeloFormularioDaAbaNaoDizPeloAtlas() {
        dados.gravar(usuario(jdbc, AMARELO), amarelo(), valores(VISITANTES, 800L), OrigemDoDado.PORTAL, requisicao());

        Valor valor = doAmarelo().valor("dado:" + VISITANTES);

        assertThat(valor.origem()).startsWith("Informado por Marketing Amarelo em ").doesNotContain("Atlas");
    }

    @Test
    void aCorrecaoVigenteEAMaisRecente() {
        informar(VISITANTES, 1000L);
        informar(VISITANTES, 1250L);

        assertThat(doAmarelo().valor("dado:" + VISITANTES).texto()).isEqualTo("1.250");
    }

    // ---------------------------------------------------------------------
    // As contas
    // ---------------------------------------------------------------------

    @Test
    void aTaxaDeCadastroCortaNaPrimeiraCasaELevaOPorcento() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        Valor taxa = doAmarelo().valor("calc:taxa_cadastro");

        assertThat(taxa.texto()).isEqualTo("89,3%");
        assertThat(taxa.numero()).isEqualByComparingTo("89.3");
        assertThat(taxa.origem()).isEqualTo("pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200");
    }

    @Test
    void contaSemDadoNaoDaZeroDaIndisponivelComOMotivo() {
        Valor taxa = doAmarelo().valor("calc:taxa_cadastro");

        assertThat(taxa.disponivel()).isFalse();
        assertThat(taxa.numero()).isNull();
        assertThat(taxa.motivo()).isEqualTo("Faltam dados para o cálculo: Cadastros com consentimento e Visitantes no espaço.");

        informar(VISITANTES, 500L);
        assertThat(doAmarelo().valor("calc:taxa_cadastro").motivo()).isEqualTo("Faltam dados para o cálculo: Cadastros com consentimento.");
    }

    @Test
    void contaComDenominadorZeroNaoDaZero() {
        informar(VISITANTES, 0L, CADASTROS, 0L);

        Valor taxa = doAmarelo().valor("calc:taxa_cadastro");

        assertThat(taxa.disponivel()).isFalse();
        assertThat(taxa.motivo()).isEqualTo("Não dá para calcular: Visitantes no espaço é zero.");
    }

    @Test
    void asContasDoModeloSaemComAsVariaveisEOResultado() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        List<CalculoDto> contas = doAmarelo().calculos();

        assertThat(contas).extracting(CalculoDto::nome).containsExactly("taxa_cadastro", "brindes_pct");
        CalculoDto taxa = contas.get(0);
        assertThat(taxa.script()).isEqualTo("pct(cadastros, visitantes)");
        assertThat(taxa.resultadoFormatado()).isEqualTo("89,3%");
        assertThat(taxa.variaveis()).extracting("nome", "ref", "valorFormatado")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("cadastros", "dado:" + CADASTROS, "1.072"),
                        org.assertj.core.groups.Tuple.tuple("visitantes", "dado:" + VISITANTES, "1.200"));
        CalculoDto brindes = contas.get(1);
        assertThat(brindes.resultado()).isNull();
        assertThat(brindes.motivo()).startsWith("Faltam dados para o cálculo");
    }

    // ---------------------------------------------------------------------
    // Textos
    // ---------------------------------------------------------------------

    @Test
    void trocaOsMarcadoresPelosValores() {
        informar(VISITANTES, 1200L);

        TextoResolvido texto = doAmarelo().texto("A marca {{parc:patrocinador}} recebeu {{dado:" + VISITANTES + "}} visitantes e "
                + "{{cons:aplicacoes.percentual}} das aplicações.");

        assertThat(texto.texto()).isEqualTo("A marca Cliente Amarelo recebeu 1.200 visitantes e 89,4% das aplicações.");
        assertThat(texto.indisponiveis()).isEmpty();
    }

    @Test
    void fraseComReferenciaSemValorNaoSaiComBuraco() {
        TextoResolvido texto = doAmarelo().texto("O espaço recebeu {{dado:" + VISITANTES + "}} visitantes.");

        assertThat(texto.texto()).isNull();
        assertThat(texto.indisponiveis()).extracting(Valor::ref).containsExactly("dado:" + VISITANTES);
    }

    @Test
    void noChatOQueNaoTemValorViraIndisponivel() {
        Resolucao resolucao = doAmarelo();

        assertThat(resolucao.paraExibicao("Foram {{dado:" + VISITANTES + "}} pessoas em {{parc:patrocinador}}."))
                .isEqualTo("Foram indisponível pessoas em Cliente Amarelo.");
        assertThat(resolucao.paraExibicao("Veja {{dado:ativacao.nao_existe}} e {{outro:coisa}}."))
                .isEqualTo("Veja indisponível e indisponível.");
    }

    // ---------------------------------------------------------------------
    // Erros e isolamento
    // ---------------------------------------------------------------------

    @Test
    void referenciaDesconhecidaEErro() {
        Resolucao resolucao = doAmarelo();

        for (String ref : List.of("dado:ativacao.nao_existe", "calc:nao_existe", "cons:aplicacoes.tudo", "parc:cnpj", "lista:aplicacoes",
                "1200", "")) {
            assertThatThrownBy(() -> resolucao.valor(ref)).as(ref).isInstanceOf(ReferenciaDesconhecidaException.class);
        }
        assertThatThrownBy(() -> resolucao.tabela("lista:comprovacoes")).isInstanceOf(ReferenciaDesconhecidaException.class);
        assertThatThrownBy(() -> resolucao.fotos("lista:aplicacoes")).isInstanceOf(ReferenciaDesconhecidaException.class);
        assertThatThrownBy(() -> resolucao.texto("Texto com {{dado:ativacao.nao_existe}}")).isInstanceOf(ReferenciaDesconhecidaException.class);
    }

    @Test
    void campoDeArquivoNaoValeComoDadoDoRelatorio() {
        jdbc.sql("""
                INSERT INTO campo_catalogo (chave, aba_id, ordem, rotulo, tipo)
                VALUES ('ativacao.planilha', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 50, 'Planilha da agência', 'ARQUIVO')
                """).update();

        assertThatThrownBy(() -> doAmarelo().valor("dado:ativacao.planilha")).isInstanceOf(ReferenciaDesconhecidaException.class);
    }

    @Test
    void aMarcaDeOutraParceriaNaoResolveNada() {
        UsuarioAutenticado doRoxo = usuario(jdbc, ROXO);
        Resolucao resolucao = resolvedor.abrir(doRoxo, amarelo(), modelo());

        assertThatThrownBy(() -> resolucao.valor("parc:patrocinador")).isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> resolvedor.abrir(doRoxo, amarelo(), modelo()).valor("cons:aplicacoes.previstas"))
                .isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> resolvedor.abrir(doRoxo, amarelo(), modelo()).tabela("lista:aplicacoes"))
                .isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void comAAbaFechadaAMarcaNaoLeAsListasDoPlano() {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();

        assertThatThrownBy(() -> doAmarelo().tabela("lista:aplicacoes")).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> doAmarelo().fotos("lista:comprovacoes")).isInstanceOf(SemPermissaoException.class);
    }

    @Test
    void aRbsResolveAParceriaDeQualquerMarca() {
        Resolucao resolucao = resolvedor.abrir(usuario(jdbc, RBS), amarelo(), modelo());

        assertThat(resolucao.valor("parc:patrocinador").texto()).isEqualTo("Cliente Amarelo");
        assertThat(resolucao.valor("cons:aplicacoes.previstas").texto()).isEqualTo("19");
    }
}
