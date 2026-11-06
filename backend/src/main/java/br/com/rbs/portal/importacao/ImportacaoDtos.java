package br.com.rbs.portal.importacao;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Os corpos de entrada e de saída das rotas de importação (109 a 117). Só os tipos: nenhuma validação, nenhuma regra e
 * nenhum acesso ao banco moram aqui. O contrato completo, com método, caminho, permissão e status de erro de cada rota,
 * está em docs/atlas/extracao/contrato-onda0.md (local).
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
     * Rota 109: o corpo da importação da RBS. O arquivo chega numa parte multipart ("arquivo") e, no lugar dele, a pessoa
     * pode colar um texto (uma conversa), que vira um arquivo .txt.
     *
     * @param eventoId   a edição escolhida
     * @param alcance    a edição inteira, uma cota ou uma parceria
     * @param cotaTipoId a cota, quando o alcance é COTA
     * @param parceriaId a parceria, quando o alcance é PARCERIA
     * @param destino    o destino escolhido; nulo deixa o JEV ou o modelo sugerir
     * @param texto      o texto colado, quando não há arquivo
     */
    public record NovaImportacao(Long eventoId, AlcanceDaImportacao alcance, Long cotaTipoId, Long parceriaId,
                                 DestinoDaImportacao destino, String texto) {
    }

    /**
     * Rota 117: a importação da marca em Aplicações. O destino é sempre os dados da ativação e o alcance é sempre a
     * parceria dela; o corpo traz o arquivo numa parte multipart ou o texto colado.
     *
     * @param texto o texto colado, quando não há arquivo
     */
    public record NovaImportacaoDaMarca(String texto) {
    }

    /**
     * Rota 110: os filtros da lista de importações da RBS.
     *
     * @param eventoId   filtra pela edição
     * @param situacao   filtra pela situação
     * @param alcance    filtra pelo alcance
     * @param parceriaId filtra pela parceria
     * @param pagina     a página, a partir de 0
     * @param tamanho    o tamanho da página (padrão 50, até 200)
     */
    public record FiltroDeImportacoes(Long eventoId, SituacaoDaImportacao situacao, AlcanceDaImportacao alcance,
                                      Long parceriaId, Integer pagina, Integer tamanho) {
    }

    /**
     * Uma importação na lista e no topo do detalhe.
     *
     * @param id               o id da importação
     * @param eventoId         a edição
     * @param alcance          a edição inteira, uma cota ou uma parceria
     * @param cotaTipoId       a cota, quando o alcance é COTA
     * @param parceriaId       a parceria, quando o alcance é PARCERIA
     * @param origem           quem importou (RBS ou marca)
     * @param destinoSugerido  o destino que o JEV ou o modelo sugeriu
     * @param destino          o destino escolhido
     * @param confianca        a confiança da sugestão, de 0 a 1
     * @param situacao         em que ponto a importação está
     * @param erro             o motivo, quando a situação é ERRO
     * @param linhas           quantas linhas propostas a importação tem
     * @param tokensEntrada    os tokens de entrada gastos na leitura
     * @param tokensSaida      os tokens de saída gastos na leitura
     * @param criadaPor        o nome de quem importou
     * @param criadaEm         quando a importação nasceu
     * @param confirmadaEm     quando ela foi confirmada
     * @param retiradaEm       quando ela foi retirada
     */
    public record ImportacaoResumo(long id, long eventoId, AlcanceDaImportacao alcance, Long cotaTipoId, Long parceriaId,
                                   OrigemDaImportacao origem, DestinoDaImportacao destinoSugerido,
                                   DestinoDaImportacao destino, BigDecimal confianca, SituacaoDaImportacao situacao,
                                   String erro, int linhas, Integer tokensEntrada, Integer tokensSaida,
                                   String criadaPor, Instant criadaEm, Instant confirmadaEm, Instant retiradaEm) {
    }

    /**
     * Rota 110: a lista paginada de importações.
     *
     * @param itens   as importações da página
     * @param pagina  a página, a partir de 0
     * @param tamanho o tamanho da página
     * @param total   o total de importações com os filtros
     */
    public record PaginaDeImportacoes(List<ImportacaoResumo> itens, int pagina, int tamanho, long total) {
    }

    /**
     * Uma linha proposta na prévia, com os valores, a origem de cada um e o que a conferência decidiu.
     *
     * @param id                     o id da linha
     * @param ordem                  a ordem na importação, a partir de 1
     * @param destino                para onde a linha vai
     * @param dados                  os valores da linha, no formato do destino
     * @param origem                 de onde saiu cada valor, por campo
     * @param situacao               PROPOSTA, EDITADA, ACEITA, DESCARTADA ou RECUSADA
     * @param motivo                 o motivo da recusa ou do descarte
     * @param parceriaId             a marca resolvida
     * @param aproveitamentoSugeridoId a linha do plano que o casador sugeriu
     * @param aproveitamentoId       a linha do plano escolhida na prévia
     * @param semelhanca             a semelhança da sugestão, de 0 a 1
     * @param editadaPor             o nome de quem editou
     * @param editadaEm              quando a linha foi editada
     */
    public record LinhaDaImportacao(long id, int ordem, DestinoDaImportacao destino, Map<String, Object> dados,
                                    Map<String, OrigemDoValor> origem, SituacaoDaLinha situacao, String motivo,
                                    Long parceriaId, Long aproveitamentoSugeridoId, Long aproveitamentoId,
                                    BigDecimal semelhanca, String editadaPor, Instant editadaEm) {
    }

    /**
     * Rota 111: o detalhe de uma importação, com as linhas propostas.
     *
     * @param importacao a importação
     * @param linhas     as linhas propostas, na ordem
     */
    public record ImportacaoDetalhe(ImportacaoResumo importacao, List<LinhaDaImportacao> linhas) {
    }

    /**
     * Rota 112: a edição de uma linha na prévia. O que vier nulo não muda.
     *
     * @param destino   o destino novo da linha
     * @param dados     os valores corrigidos (o que faltar fica como estava)
     * @param situacao  ACEITA ou DESCARTADA para decidir a linha
     * @param motivo    o motivo do descarte
     */
    public record EdicaoDeLinha(DestinoDaImportacao destino, Map<String, Object> dados, SituacaoDaLinha situacao,
                                String motivo) {
    }

    /**
     * Rota 113: a confirmação. Sem lista, confirma todas as linhas aceitas e propostas; com lista, só as indicadas.
     *
     * @param linhas os ids das linhas a confirmar; nulo confirma todas as que estão para entrar
     */
    public record ConfirmacaoDaImportacao(List<Long> linhas) {
    }

    /**
     * Rota 113: o resultado da confirmação, no formato dos totais da carga.
     *
     * @param lidas      as linhas lidas
     * @param inseridas  as linhas gravadas
     * @param duplicadas as linhas que já estavam na base
     * @param rejeitadas as linhas rejeitadas na gravação, com o motivo em "problemas"
     * @param problemas  o motivo de cada rejeição
     * @param cargaId    a carga criada pelo destino, quando houver
     */
    public record ResultadoDaConfirmacao(int lidas, int inseridas, int duplicadas, int rejeitadas, List<String> problemas,
                                         Long cargaId) {
    }

    /**
     * Rota 116: a origem de uma linha, aberta pelo visualizador. Os trechos são os pedaços do arquivo que contêm os valores.
     *
     * @param linhaId     o id da linha
     * @param nomeArquivo o nome do arquivo importado
     * @param tipoDeArquivo o formato lido
     * @param campos      a origem de cada campo
     * @param trechos     os pedaços de texto que contêm os valores
     */
    public record OrigemDaLinha(long linhaId, String nomeArquivo, String tipoDeArquivo,
                                Map<String, OrigemDoValor> campos, List<Pedaco> trechos) {
    }
}
