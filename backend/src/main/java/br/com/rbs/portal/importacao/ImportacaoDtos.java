package br.com.rbs.portal.importacao;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Os corpos de entrada e de saída das rotas 109 a 116 (e da 112b). Só os tipos: nenhuma validação, nenhuma regra e nenhum acesso
 * ao banco moram aqui. O contrato completo, com método, caminho, permissão e status de erro de cada rota, está em
 * docs/atlas/extracao/contrato-onda0.md (local) e em docs/api.md.
 */
public final class ImportacaoDtos {

    private ImportacaoDtos() {
    }

    /**
     * De onde veio a importação: a RBS ou a marca (a marca só importa em Aplicações, para os dados da ativação).
     */
    public enum OrigemDaImportacao {
        /** A RBS importa para qualquer alcance e destino. */
        RBS,
        /** A marca importa para a própria parceria e só o destino de dados da ativação. */
        MARCA
    }

    /**
     * Em que ponto está uma linha proposta.
     */
    public enum SituacaoDaLinha {
        /** Como a extração propôs. */
        PROPOSTA,
        /** A pessoa corrigiu algum valor. */
        EDITADA,
        /** Entra na confirmação. */
        ACEITA,
        /** Não entra; o motivo diz por quê. */
        DESCARTADA,
        /** A conferência recusou; o motivo diz por quê. */
        RECUSADA
    }

    /**
     * Rota 109: o corpo da importação da RBS. O arquivo chega numa parte multipart ("arquivo") e, no lugar dele, a pessoa pode
     * colar um texto (uma conversa), que vira um arquivo .txt.
     *
     * @param eventoId   a edição escolhida
     * @param alcance    a edição inteira, uma cota ou uma parceria
     * @param cota       a cota (o código, como MASTER, ou o id), quando o alcance é COTA
     * @param parceriaId a parceria, quando o alcance é PARCERIA
     * @param destino    o destino escolhido; nulo deixa a sugestão decidir
     * @param texto      o texto colado, quando não há arquivo
     */
    public record NovaImportacao(Long eventoId, AlcanceDaImportacao alcance, String cota, Long parceriaId,
                                 DestinoDaImportacao destino, String texto) {
    }

    /**
     * Rota 117: a importação da marca em Aplicações. O destino é sempre os dados da ativação e o alcance é sempre a parceria
     * dela; o corpo traz o arquivo numa parte multipart ou o texto colado.
     *
     * @param texto o texto colado, quando não há arquivo
     */
    public record NovaImportacaoDaMarca(String texto) {
    }

    /**
     * Rota 110: os filtros da lista de importações da RBS.
     *
     * @param eventoId filtro pela edição
     * @param situacao uma situação, ou várias separadas por vírgula
     * @param pagina   a página, a partir de 0
     * @param tamanho  o tamanho da página (padrão 50, até 200)
     */
    public record FiltroDeImportacoes(Long eventoId, String situacao, Integer pagina, Integer tamanho) {
    }

    /** O arquivo da importação, como a prévia o mostra. */
    public record ArquivoDaImportacaoDto(Long id, String nome, String tipo, long tamanho) {
    }

    /** A edição da importação. */
    public record EventoDaImportacaoDto(Long id, String nome, Integer edicao) {
    }

    /** A cota da importação, quando o alcance é COTA. */
    public record CotaDaImportacaoDto(Long id, String codigo, String nome) {
    }

    /** A parceria (marca) da importação, quando o alcance é PARCERIA ou quando a linha foi ligada a uma marca. */
    public record ParceriaDaImportacaoDto(Long id, String patrocinador) {
    }

    /** Quem criou, confirmou ou retirou a importação. */
    public record UsuarioDaImportacaoDto(Long id, String nome) {
    }

    /** A contagem das linhas da prévia, por situação. */
    public record ContagemDeLinhasDto(int total, int propostas, int editadas, int aceitas, int descartadas, int recusadas) {
    }

    /** Uma suspeita de dado pessoal achada no texto lido (E4): a página (ou o pedaço) e o tipo. */
    public record SuspeitaDeDadoPessoalDto(int pagina, String tipo) {
    }

    /**
     * Uma importação na lista e no topo do detalhe.
     */
    public record ImportacaoResumo(Long id, SituacaoDaImportacao situacao, OrigemDaImportacao origem,
                                   ArquivoDaImportacaoDto arquivo, EventoDaImportacaoDto evento, AlcanceDaImportacao alcance,
                                   CotaDaImportacaoDto cota, ParceriaDaImportacaoDto parceria, DestinoDaImportacao destino,
                                   DestinoDaImportacao destinoSugerido, BigDecimal confianca, ContagemDeLinhasDto linhas,
                                   UsuarioDaImportacaoDto criadaPor, OffsetDateTime criadaEm, OffsetDateTime confirmadaEm,
                                   OffsetDateTime retiradaEm, String erro, List<SuspeitaDeDadoPessoalDto> suspeitas) {
        public ImportacaoResumo {
            suspeitas = suspeitas == null ? List.of() : List.copyOf(suspeitas);
        }
    }

    /** Rota 110: a lista paginada de importações. */
    public record PaginaDeImportacoes(List<ImportacaoResumo> itens, int pagina, int tamanho, long total) {
    }

    /** A linha do plano comercial que o casador sugeriu ou que a pessoa escolheu. */
    public record AproveitamentoDaImportacaoDto(Long id, String veiculo, String formato, String espacoPrograma, BigDecimal semelhanca) {
    }

    /**
     * Uma linha proposta na prévia, com os valores, a origem de cada um e o que a conferência decidiu.
     */
    public record ItemDaLinha(Long id, int ordem, DestinoDaImportacao destino, SituacaoDaLinha situacao, String motivo,
                              Map<String, Object> dados, Map<String, OrigemDoValor> origem, ParceriaDaImportacaoDto parceria,
                              AproveitamentoDaImportacaoDto aproveitamentoSugerido,
                              AproveitamentoDaImportacaoDto aproveitamento, UsuarioDaImportacaoDto editadaPor,
                              OffsetDateTime editadaEm) {
    }

    /** Uma linha que a leitura não entendeu (título, total, cabeçalho): ela não some, mas não vira proposta. */
    public record NaoEntendidoDto(String localizador, String trecho, String motivo) {
    }

    /** Uma linha rejeitada na confirmação, pela ordem em que ela está na prévia. */
    public record RejeicaoDaLinhaDto(int ordem, String motivo) {
    }

    /** Rota 113: o resultado da confirmação, no formato dos totais da carga. */
    public record ResultadoDaConfirmacao(int lidas, int inseridas, int duplicadas, int rejeitadas,
                                         List<RejeicaoDaLinhaDto> rejeicoes) {
        public ResultadoDaConfirmacao {
            rejeicoes = rejeicoes == null ? List.of() : List.copyOf(rejeicoes);
        }
    }

    /**
     * Rota 111: o detalhe de uma importação. É o {@link ImportacaoResumo} achatado, mais a etapa, as linhas propostas, o que a
     * leitura não entendeu e o resultado da confirmação (nulo antes dela).
     */
    public record ImportacaoDetalhe(Long id, SituacaoDaImportacao situacao, OrigemDaImportacao origem,
                                    ArquivoDaImportacaoDto arquivo, EventoDaImportacaoDto evento, AlcanceDaImportacao alcance,
                                    CotaDaImportacaoDto cota, ParceriaDaImportacaoDto parceria, DestinoDaImportacao destino,
                                    DestinoDaImportacao destinoSugerido, BigDecimal confianca, ContagemDeLinhasDto linhas,
                                    UsuarioDaImportacaoDto criadaPor, OffsetDateTime criadaEm, OffsetDateTime confirmadaEm,
                                    OffsetDateTime retiradaEm, String erro, List<SuspeitaDeDadoPessoalDto> suspeitas,
                                    Long documentoId, String etapa, List<ItemDaLinha> itens, List<NaoEntendidoDto> naoEntendidos,
                                    ResultadoDaConfirmacao resultado) {

        public ImportacaoDetalhe {
            suspeitas = suspeitas == null ? List.of() : List.copyOf(suspeitas);
            itens = itens == null ? List.of() : List.copyOf(itens);
            naoEntendidos = naoEntendidos == null ? List.of() : List.copyOf(naoEntendidos);
        }

        /** O resumo achatado, com os campos que a lista também traz. */
        public static ImportacaoDetalhe de(ImportacaoResumo r, Long documentoId, String etapa, List<ItemDaLinha> itens,
                                           List<NaoEntendidoDto> naoEntendidos, ResultadoDaConfirmacao resultado) {
            return new ImportacaoDetalhe(r.id(), r.situacao(), r.origem(), r.arquivo(), r.evento(), r.alcance(), r.cota(),
                    r.parceria(), r.destino(), r.destinoSugerido(), r.confianca(), r.linhas(), r.criadaPor(), r.criadaEm(),
                    r.confirmadaEm(), r.retiradaEm(), r.erro(), r.suspeitas(), documentoId, etapa, itens, naoEntendidos, resultado);
        }
    }

    /**
     * Rota 112: a edição de uma linha na prévia. O que vier nulo não muda.
     *
     * @param dados            os valores corrigidos (o que faltar fica como estava)
     * @param situacao         ACEITA ou DESCARTADA para decidir a linha
     * @param aproveitamentoId a linha do plano escolhida na lista da parceria
     */
    public record EdicaoDeLinha(Map<String, Object> dados, SituacaoDaLinha situacao, Long aproveitamentoId) {
    }

    /**
     * Rota 112b: a decisão em lote. Ou uma lista de ids, ou todas as linhas PROPOSTA e EDITADA.
     *
     * @param situacao ACEITA ou DESCARTADA
     * @param ids      as linhas a mudar
     * @param todas    todas as linhas PROPOSTA e EDITADA
     */
    public record EdicaoEmLote(SituacaoDaLinha situacao, List<Long> ids, Boolean todas) {
    }

    /** Rota 112b: quantas linhas mudaram. */
    public record LinhasAlteradasDto(int alteradas) {
    }

    /**
     * Rota 113: a confirmação. Sem lista, confirma todas as linhas aceitas; com lista, só as indicadas.
     *
     * @param linhas os ids das linhas a confirmar; nulo confirma todas as que estão aceitas
     */
    public record ConfirmacaoDaImportacao(List<Long> linhas) {
    }

    /**
     * Rota 116: a origem de uma linha, aberta pelo visualizador. Uma planilha traz a grade em volta da célula; um texto, o antes,
     * o trecho e o depois; uma imagem, o arquivo.
     */
    public record OrigemDaLinha(Long arquivoId, String tipo, String localizador, String trecho, PlanilhaDaOrigemDto planilha,
                                TextoDaOrigemDto texto, Integer pagina) {
    }

    /** A grade de até 9 linhas e 9 colunas em volta da célula. */
    public record PlanilhaDaOrigemDto(String aba, String celula, int linhaInicial, String colunaInicial, List<List<String>> grade) {
    }

    /** O trecho de um texto, com até 300 caracteres de cada lado. */
    public record TextoDaOrigemDto(String antes, String trecho, String depois) {
    }
}
