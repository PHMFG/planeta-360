package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.PlanilhaDoPlano;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * O destino VEICULACAO: as exibições e os totais mensais da mídia. [CARGA] Grava as linhas em stg_veiculacao e chama
 * fn_carregar_veiculacoes numa carga nova; cada veiculação gravada aponta para a linha da importação (E14). A exclusão na
 * retirada (D5/Y1) fica com o ImportacaoService.
 */
@Component
public class ParaVeiculacao implements DestinoDeGravacao {

    private final ImportacaoRepositorio repositorio;

    public ParaVeiculacao(ImportacaoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.VEICULACAO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        exigir(linha, "patrocinador", problemas);
        exigir(linha, "veiculo", problemas);
        exigir(linha, "formato", problemas);
        Object quantidade = linha.dados().get("quantidade");
        if (quantidade == null) {
            problemas.add("Falta a quantidade da linha.");
        } else {
            BigDecimal numero = MapeadorTabular.decimal(String.valueOf(quantidade));
            if (numero == null || numero.signum() <= 0) {
                problemas.add("A quantidade precisa ser maior que zero.");
            }
        }
        if (linha.dados().get("exibido_em") == null && linha.dados().get("mes") == null) {
            problemas.add("Falta a data ou o mês da entrega.");
        }
        Object impacto = linha.dados().get("impacto");
        if (impacto != null && MapeadorTabular.decimal(String.valueOf(impacto)) == null) {
            problemas.add("O impacto precisa ser um número.");
        }
        return problemas;
    }

    private static void exigir(LinhaProposta linha, String campo, List<String> problemas) {
        Object valor = linha.dados().get(campo);
        if (valor == null || String.valueOf(valor).isBlank()) {
            problemas.add("Falta " + campo + " da linha.");
        }
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        long cargaId = repositorio.criarCarga("MIDIA", contexto.arquivoId(), contexto.eventoId(), usuario.id());
        List<Lida> lidas = new ArrayList<>();
        for (LinhaAceita linha : linhas) {
            BigDecimal quantidade = MapeadorTabular.decimal(String.valueOf(linha.proposta().dados().get("quantidade")));
            BigDecimal impacto = linha.proposta().dados().get("impacto") == null ? null
                    : MapeadorTabular.decimal(String.valueOf(linha.proposta().dados().get("impacto")));
            String mes = texto(linha.proposta().dados().get("mes"));
            OffsetDateTime exibidoEm = data(linha.proposta().dados().get("exibido_em"));
            PlanilhaDoPlano plano = linha.aproveitamentoId() == null ? null
                    : repositorio.planilha(linha.aproveitamentoId()).orElse(null);
            String veiculo = plano != null ? plano.veiculo() : texto(linha.proposta().dados().get("veiculo"));
            String formato = plano != null ? plano.formato() : texto(linha.proposta().dados().get("formato"));
            String espaco = plano != null ? plano.espacoPrograma() : texto(linha.proposta().dados().get("espaco_programa"));
            String link = texto(linha.proposta().dados().get("link"));
            repositorio.gravarStgVeiculacao(cargaId, linha.ordem(), linha.patrocinador(), veiculo, formato, espaco, exibidoEm, mes,
                    quantidade, impacto, link);
            lidas.add(new Lida(linha, exibidoEm, mes, quantidade, impacto, link));
        }
        repositorio.processarVeiculacoes(cargaId, contexto.eventoId());
        for (Lida lida : lidas) {
            if (lida.linha().aproveitamentoId() != null) {
                repositorio.ligarVeiculacoesDaLinha(cargaId, lida.linha().id(), lida.linha().aproveitamentoId(), lida.exibidoEm(),
                        lida.mes(), lida.quantidade(), lida.link(), lida.impacto());
            }
        }
        var totais = repositorio.totaisDaCarga(cargaId);
        List<Rejeicao> rejeicoes = repositorio.rejeicoesDaCarga(cargaId).stream()
                .map(r -> new Rejeicao(r.ordem(), r.motivo())).toList();
        return new ResultadoDaGravacao(cargaId, totais.map(ImportacaoRepositorio.TotaisDaCarga::lidas).orElse(linhas.size()),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::inseridas).orElse(0),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::duplicadas).orElse(0),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::rejeitadas).orElse(rejeicoes.size()), rejeicoes);
    }

    private record Lida(LinhaAceita linha, OffsetDateTime exibidoEm, String mes, BigDecimal quantidade, BigDecimal impacto,
                        String link) {
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    static OffsetDateTime data(Object valor) {
        if (valor == null || String.valueOf(valor).isBlank()) {
            return null;
        }
        String texto = String.valueOf(valor).strip();
        try {
            return OffsetDateTime.parse(texto);
        } catch (RuntimeException semFuso) {
            try {
                return LocalDate.parse(texto.length() > 10 ? texto.substring(0, 10) : texto)
                        .atStartOfDay(br.com.rbs.portal.comum.Datas.BRASILIA).toOffsetDateTime();
            } catch (RuntimeException invalida) {
                return null;
            }
        }
    }
}
