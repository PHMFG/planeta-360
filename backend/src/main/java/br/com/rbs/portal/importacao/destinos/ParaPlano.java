package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * O destino PLANO: a carga do plano comercial (stg_plano e fn_carregar_plano). [CARGA] O plano é da marca e da edição; a linha
 * precisa da marca, do veículo, do formato e da quantidade prevista.
 */
@Component
public class ParaPlano implements DestinoDeGravacao {

    private final ImportacaoRepositorio repositorio;

    public ParaPlano(ImportacaoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.PLANO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        for (String campo : List.of("patrocinador", "veiculo", "formato")) {
            Object valor = linha.dados().get(campo);
            if (valor == null || String.valueOf(valor).isBlank()) {
                problemas.add("Falta " + campo + " da linha.");
            }
        }
        Object quantidade = linha.dados().get("quantidade_prevista");
        if (quantidade == null) {
            problemas.add("Falta a quantidade prevista da linha.");
        } else {
            BigDecimal numero = MapeadorTabular.decimal(String.valueOf(quantidade));
            if (numero == null || numero.signum() <= 0) {
                problemas.add("A quantidade prevista precisa ser maior que zero.");
            }
        }
        Object unidade = linha.dados().get("unidade");
        if (unidade != null && !List.of("INSERCAO", "CPM", "APLICACAO").contains(String.valueOf(unidade).toUpperCase(Locale.ROOT))) {
            problemas.add("A unidade do plano precisa ser INSERCAO, CPM ou APLICACAO.");
        }
        return problemas;
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        long cargaId = repositorio.criarCarga("PLANO_COMERCIAL", contexto.arquivoId(), contexto.eventoId(), usuario.id());
        for (LinhaAceita linha : linhas) {
            var dados = linha.proposta().dados();
            repositorio.gravarStgPlano(cargaId, linha.ordem(), linha.patrocinador(),
                    codigo(dados.get("categoria"), "MIDIA"), texto(dados.get("bloco")), texto(dados.get("veiculo")),
                    texto(dados.get("formato")), texto(dados.get("espaco_programa")), texto(dados.get("janela")),
                    codigo(dados.get("tipo_midia"), "ASSINADA"),
                    MapeadorTabular.decimal(String.valueOf(dados.get("quantidade_prevista"))),
                    codigo(dados.get("unidade"), "INSERCAO"), pagina(dados.get("fonte_pagina")));
        }
        repositorio.processarPlano(cargaId, contexto.eventoId());
        var totais = repositorio.totaisDaCarga(cargaId);
        List<Rejeicao> rejeicoes = repositorio.rejeicoesDaCarga(cargaId).stream()
                .map(r -> new Rejeicao(r.ordem(), r.motivo())).toList();
        return new ResultadoDaGravacao(cargaId, totais.map(ImportacaoRepositorio.TotaisDaCarga::lidas).orElse(linhas.size()),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::inseridas).orElse(0),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::duplicadas).orElse(0),
                totais.map(ImportacaoRepositorio.TotaisDaCarga::rejeitadas).orElse(rejeicoes.size()), rejeicoes);
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private static String codigo(Object valor, String padrao) {
        return valor == null || String.valueOf(valor).isBlank() ? padrao : String.valueOf(valor).toUpperCase(Locale.ROOT);
    }

    private static Short pagina(Object valor) {
        if (valor == null) {
            return null;
        }
        try {
            int pagina = Integer.parseInt(String.valueOf(valor).strip());
            return pagina >= 1 && pagina <= Short.MAX_VALUE ? (short) pagina : null;
        } catch (NumberFormatException invalida) {
            return null;
        }
    }
}
