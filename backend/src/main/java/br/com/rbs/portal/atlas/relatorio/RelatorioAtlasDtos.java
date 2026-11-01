package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Os formatos de saída das rotas 47 e 48 (o relatório do Atlas e a lista deles), em record. [ATLAS] O formato é o contrato com
 * o front (docs/api.md, frontend/src/api/atlas.ts): camelCase, o tipo do bloco em {@code tipo}, números sempre como texto já
 * formatado no padrão do portal ("1.200", "89,3%") e, onde faltou dado, {@code null} com o motivo: nunca zero no lugar de
 * "sem dado". O mesmo desenho (seções e blocos já resolvidos) é o que o snapshot guarda em {@code atlas_relatorio.snapshot}.
 */
public final class RelatorioAtlasDtos {

    private RelatorioAtlasDtos() {
    }

    /** O selo que todo relatório do Atlas leva: ele é da marca e a RBS não o validou. */
    public static final String SELO = "Documento de trabalho da marca, montado pelo Atlas. Não validado pela RBS.";

    /** A resposta da rota 47. */
    public record RelatorioAtlasDto(Long id, Long parceriaId, String patrocinador, String modelo, String titulo, String status,
                                    UsuarioResumoDto criadoPor, OffsetDateTime criadoEm, OffsetDateTime finalizadoEm, String selo,
                                    List<SecaoDto> secoes) {
    }

    /** Um item da lista da rota 48. */
    public record RelatorioAtlasResumoDto(Long id, String modelo, String titulo, String status, UsuarioResumoDto criadoPor,
                                          OffsetDateTime criadoEm, OffsetDateTime finalizadoEm) {
    }

    public record SecaoDto(String id, String titulo, List<BlocoDto> blocos) {
    }

    /** Um número do relatório: já formatado, ou indisponível com o motivo. {@code ref} diz de onde ele vem (ou nulo). */
    public record ItemKpiDto(String rotulo, String valor, String indisponivel, String ref) {
    }

    /** Uma foto da galeria de comprovações. {@code registradoEm} é o instante com o fuso de Brasília, como texto ISO 8601. */
    public record FotoDto(Long arquivoId, String nomeOriginal, String aplicacao, String descricao, String registradoEm) {
    }

    /** Uma linha de "Fontes e cálculos": o número, onde nasceu e, para uma conta, quais valores entraram. */
    public record FonteDto(String ref, String rotulo, String valor, String origem) {
    }

    /** O catálogo fechado de blocos já resolvidos. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "tipo")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CapaDto.class, name = "capa"),
            @JsonSubTypes.Type(value = TituloDto.class, name = "titulo"),
            @JsonSubTypes.Type(value = ParagrafoDto.class, name = "paragrafo"),
            @JsonSubTypes.Type(value = KpiDto.class, name = "kpi"),
            @JsonSubTypes.Type(value = KpiGradeDto.class, name = "kpi_grade"),
            @JsonSubTypes.Type(value = TabelaDto.class, name = "tabela"),
            @JsonSubTypes.Type(value = GaleriaComprovacoesDto.class, name = "galeria_comprovacoes"),
            @JsonSubTypes.Type(value = CampoInformadoDto.class, name = "campo_informado"),
            @JsonSubTypes.Type(value = AvisoIndisponivelDto.class, name = "aviso_indisponivel"),
            @JsonSubTypes.Type(value = FontesDto.class, name = "fontes")})
    public sealed interface BlocoDto permits CapaDto, TituloDto, ParagrafoDto, KpiDto, KpiGradeDto, TabelaDto,
            GaleriaComprovacoesDto, CampoInformadoDto, AvisoIndisponivelDto, FontesDto {

        String id();
    }

    public record CapaDto(String id, String titulo, String subtitulo, String edicao) implements BlocoDto {
    }

    public record TituloDto(String id, String texto) implements BlocoDto {
    }

    /** {@code redigidoPor} é ATLAS (o modelo de linguagem escreveu) ou MODELO (a frase-modelo do arquivo do modelo-base). */
    public record ParagrafoDto(String id, String texto, String redigidoPor) implements BlocoDto {
    }

    public record KpiDto(String id, ItemKpiDto item) implements BlocoDto {
    }

    public record KpiGradeDto(String id, List<ItemKpiDto> itens) implements BlocoDto {
    }

    public record TabelaDto(String id, List<String> colunas, List<List<String>> linhas, String ref) implements BlocoDto {
    }

    public record GaleriaComprovacoesDto(String id, List<FotoDto> itens) implements BlocoDto {
    }

    public record CampoInformadoDto(String id, String rotulo, String valor, String origem) implements BlocoDto {
    }

    public record AvisoIndisponivelDto(String id, String motivo) implements BlocoDto {
    }

    public record FontesDto(String id, List<FonteDto> itens) implements BlocoDto {
    }

    // ---------------------------------------------------------------------
    // O snapshot
    // ---------------------------------------------------------------------

    /** Uma variável de uma conta, com o número que entrou (nulo = a marca não informou) e o mesmo número já formatado. */
    public record VariavelDto(String nome, String ref, BigDecimal valor, String valorFormatado, String unidade) {
    }

    /**
     * Uma conta das fórmulas fixas com o que entrou e o que saiu. {@code resultado} é nulo quando indisponível e {@code motivo}
     * diz por quê; senão {@code resultadoFormatado} traz o número no padrão do portal, com "%" nos percentuais.
     */
    public record CalculoDto(String nome, String rotulo, String script, List<VariavelDto> variaveis, BigDecimal resultado,
                             String resultadoFormatado, String unidade, String motivo) {
    }

    /**
     * Os valores resolvidos que ficam em {@code atlas_relatorio.snapshot} quando o relatório fica pronto: as seções e os blocos
     * como a rota 47 os devolve, mais as contas. A seção "Fontes e cálculos" lê daqui, e não da tabela {@code atlas_calculo}.
     */
    public record SnapshotDto(String titulo, List<SecaoDto> secoes, List<CalculoDto> calculos) {
    }
}
